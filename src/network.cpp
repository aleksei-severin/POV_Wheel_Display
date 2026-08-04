#include "network.h"
#include <WiFi.h>
#include <ESPmDNS.h>
#include <ArduinoOTA.h>
#include <ESPAsyncWebServer.h>
#include <ElegantOTA.h>
#include <LittleFS.h>
#include <Preferences.h>
#include <HTTPClient.h>
#include <esp_heap_caps.h>
#include <stdarg.h>
#include <vector>
#include <memory>
#include <algorithm>

AsyncWebServer server(80);
Preferences prefs;
File uploadFile;
// Взводится обработчиком тела /upload, читается обработчиком запроса: тело и
// ответ — разные колбэки, а сказать браузеру о неудаче нужно именно в ответе.
static bool uploadFailed = false;
// Владелец текущей загрузки. uploadFile — один на всё устройство, поэтому две
// параллельные загрузки (двойной клик по Convert&Upload) писали бы куски двух
// разных тел в один файл: получается мусор, который не отличить от битой
// картинки. Второй запрос не трогает состояние и получает 409.
static AsyncWebServerRequest *uploadOwner = nullptr;
static uint32_t uploadLastChunkMs = 0;
// Если от владельца давно не было куска — считаем передачу мёртвой (клиент
// исчез без TCP disconnect) и отдаём слот новому запросу.
#define UPLOAD_OWNER_TIMEOUT_MS  5000

String hostName;
String currentDisplayFile = "";   // Имя файла, загруженного в frameBuffer

// Счётчик версии состояния: инкрементируется при любом изменении (настройки,
// файлы, воспроизведение). Клиенты сравнивают с последней известной версией
// и обновляют UI при расхождении — синхронизация нескольких браузеров.
static uint32_t state_version = 0;

// Отдельный счётчик версии списка файлов: инкрементируется только при
// upload/delete/play — НЕ при изменении настроек. Браузер обновляет список
// (и запускает загрузку превью) только когда этот счётчик меняется.
static uint32_t file_version = 0;

// ===================== WEB LOG BUFFER =====================
// Кольцевой буфер в RTC SLOW RAM — переживает deep sleep.
// ESP32-S3 RTC SLOW RAM = 8192 байт, из них ~1 кБ занимает ESP-IDF.
// 64 строки × 96 байт = 6144 байт — укладываемся с запасом.
#define WEB_LOG_COUNT   64
#define WEB_LOG_LINE    96

// Хранит строку сообщения без временно́й метки (она добавляется при записи в буфер)
// Формат в буфере: "YYYY-MM-DD HH:MM:SS msg\0"
RTC_DATA_ATTR static char     _log_buf[WEB_LOG_COUNT][WEB_LOG_LINE];
RTC_DATA_ATTR static uint32_t _log_ms[WEB_LOG_COUNT];  // millis() в момент записи каждой строки
RTC_DATA_ATTR static uint32_t _log_head  = 0;  // Индекс следующей записи (кольцо)
RTC_DATA_ATTR static uint32_t _log_total = 0;  // Всего записей с начала времён

// Unix-время в момент последней синхронизации + millis() в тот же момент.
// Переживают deep sleep — позволяют считать текущее время без NTP.
RTC_DATA_ATTR static uint32_t _time_epoch_base  = 0;  // Unix timestamp при синхронизации
RTC_DATA_ATTR static uint32_t _time_millis_base = 0;  // millis() при синхронизации
RTC_DATA_ATTR static int32_t  _time_tz_offset   = 0;  // Смещение часового пояса в секундах (UTC+2 = +7200)

static portMUX_TYPE _log_mux = portMUX_INITIALIZER_UNLOCKED;

// Возвращает текущий Unix timestamp (секунды), используя сохранённую базу + millis().
// После deep sleep millis() сбрасывается в 0, поэтому _time_epoch_base = 0 устанавливается
// явно в resetTimeSync() вызываемом из setup() — это гарантирует ??:??:?? для загрузочных
// сообщений и корректную ретроспективную расстановку меток после первой синхронизации.
static uint32_t _currentEpoch() {
    if (_time_epoch_base == 0) return 0;
    // Защита от uint32_t underflow когда millis() < _time_millis_base:
    // такое случается если база была сохранена RTC, а millis() ещё не догнал её.
    uint32_t now_ms = millis();
    if (now_ms < _time_millis_base) return 0;
    return _time_epoch_base + (now_ms - _time_millis_base) / 1000;
}

// Сбрасывает временну́ю базу — вызывать в начале setup().
// Без этого после deep sleep _time_epoch_base != 0, но _time_millis_base стала,
// из-за чего _currentEpoch() даёт UTC вместо локального времени до первой синхронизации.
void resetTimeSync() {
    _time_epoch_base  = 0;
    _time_millis_base = 0;
    // _time_tz_offset не сбрасываем: браузер отправит его вместе со временем
}

// Форматирует "YYYY-MM-DD HH:MM:SS" из Unix timestamp с учётом часового пояса в буфер buf[20].
static void _fmtDateTime(uint32_t epoch, char* buf) {
    if (epoch == 0) {
        strcpy(buf, "???? ?? ?? ??:??:??");
        return;
    }
    // Применяем смещение часового пояса
    int64_t local_epoch = (int64_t)epoch + (int64_t)_time_tz_offset;
    if (local_epoch < 0) local_epoch = 0;

    // Расчёт даты (алгоритм Томаса — без libc mktime/localtime)
    uint32_t days = (uint32_t)(local_epoch / 86400);
    uint32_t secs = (uint32_t)(local_epoch % 86400);

    // Преобразование количества дней с 1970-01-01 → год/месяц/день
    uint32_t z = days + 719468;
    uint32_t era = z / 146097;
    uint32_t doe = z - era * 146097;
    uint32_t yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    uint32_t y   = yoe + era * 400;
    uint32_t doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    uint32_t mp  = (5 * doy + 2) / 153;
    uint32_t d   = doy - (153 * mp + 2) / 5 + 1;
    uint32_t m   = (mp < 10) ? (mp + 3) : (mp - 9);
    y += (m <= 2) ? 1 : 0;

    snprintf(buf, 20, "%04u-%02u-%02u %02u:%02u:%02u",
             y, m, d,
             secs / 3600, (secs % 3600) / 60, secs % 60);
}

// Ретроспективно проставляет метки времени строкам у которых метка начинается с '?'.
// Вызывается из /settime после первой синхронизации с браузером.
// Использует сохранённый millis() каждой строки для восстановления точного времени.
static void _retroFillTimestamps() {
    // Граница буфера: строки доступны от (total - min(total, COUNT)) до total
    uint32_t count = (_log_total < WEB_LOG_COUNT) ? _log_total : WEB_LOG_COUNT;
    uint32_t from  = _log_total - count;
    for (uint32_t i = from; i < _log_total; i++) {
        uint32_t idx = i % WEB_LOG_COUNT;
        if (_log_buf[idx][0] != '?') continue;  // Уже есть метка
        // Вычисляем epoch момента записи по сохранённому millis()
        uint32_t ms_at_write = _log_ms[idx];
        uint32_t epoch_at_write = _time_epoch_base
            + (uint32_t)(((int64_t)ms_at_write - (int64_t)_time_millis_base) / 1000);
        char ts[20];
        _fmtDateTime(epoch_at_write, ts);
        // Перезаписываем только первые 19 символов (метку "YYYY-MM-DD HH:MM:SS"), остальное не трогаем
        memcpy(_log_buf[idx], ts, 19);
    }
}

void webLog(const char* msg) {
    // Дедупликация: не записываем если последнее сообщение идентично текущему.
    // Сравниваем только текст без временно́й метки (метка занимает первые 20 символов: "YYYY-MM-DD HH:MM:SS ").
    portENTER_CRITICAL(&_log_mux);
    if (_log_total > 0) {
        const char* last = _log_buf[(_log_head - 1) % WEB_LOG_COUNT];
        const char* last_msg = (strlen(last) > 20) ? last + 20 : last;
        if (strcmp(last_msg, msg) == 0) {
            portEXIT_CRITICAL(&_log_mux);
            return;  // Дубликат — не пишем
        }
    }

    char ts[20];
    _fmtDateTime(_currentEpoch(), ts);

    uint32_t idx = _log_head % WEB_LOG_COUNT;
    _log_ms[idx] = millis();  // Сохраняем millis() для ретроспективной метки
    snprintf(_log_buf[idx], WEB_LOG_LINE, "%s %s", ts, msg);
    _log_head++;
    _log_total++;
    portEXIT_CRITICAL(&_log_mux);
}

void webLogf(const char* fmt, ...) {
    char tmp[WEB_LOG_LINE];
    va_list args;
    va_start(args, fmt);
    vsnprintf(tmp, sizeof(tmp), fmt, args);
    va_end(args);
    webLog(tmp);
}

// --- WiFi credentials ---
const char* HOTSPOT_SSID = "Bunnies Stan 2.4G";
const char* HOTSPOT_PASS = "ValentinaAleksei";

// --- Статусные флаги для LED-индикации WiFi ---
volatile bool blink_wifi_ok_flag   = false;
volatile bool blink_wifi_fail_flag = false;
volatile bool blink_ap_client_flag = false;

// --- Состояние переподключения ---
static bool     sta_was_connected      = false;
static bool     initial_connect_done   = false;
static uint32_t last_reconnect_attempt = 0;

void safeOTAShutdown() {
    // 1. Выставляем флаги — renderingTask прерывает текущий оборот,
    //    loop() не включает питание повторно по вибрации или Холлу.
    ota_in_progress    = true;
    force_stop_display = true;
    power_state        = PWR_OFF;
    peripherals_active = false;

    // 2. Ждём 200 мс — гарантируем завершение текущей DMA-транзакции renderingTask,
    //    чтобы dmaMutex был свободен и blankAllLEDs_DMA не вызвал deadlock.
    vTaskDelay(pdMS_TO_TICKS(200));

    // 3. Гасим светодиоды и снимаем питание обоих DCDC.
    blankAllLEDs_DMA();
    digitalWrite(PIN_EN_DCDC_REST, LOW);
    digitalWrite(PIN_EN_DCDC_ARM1, LOW);

    // 4. Размонтируем LittleFS — ElegantOTA для ESP32 не делает это автоматически,
    //    запись поверх смонтированной FS приводит к её повреждению и краш/статус 0.
    LittleFS.end();

    webLog("[OTA] Display off, FS unmounted, starting update...");
}

// --- Совместимость со старым форматом RGB888 ---------------------------------
// Кадры, залитые до перехода на RGB565, лежат на флеше как 47520 байт RGB888.
// Переливать библиотеку не нужно — конвертируем при загрузке, и экономия PSRAM
// (и длины анимации) работает сразу на всём, что уже есть. Вес самого файла
// упадёт только у перезалитых.
//
// Дизеринга здесь намеренно нет: исходник уже квантован до 8 бит, и добавлять
// шум к готовым данным смысла не имеет — упорядоченный дизеринг живёт в
// браузерном конвертере, где под ним есть непрерывные значения.
// Округление, а не отбрасывание младших битов: усечение систематически
// затемняло бы кадр на пол-уровня.
static inline uint16_t pack565(int r, int g, int b) {
    int r5 = (r * 31 + 127) / 255;
    int g6 = (g * 63 + 127) / 255;
    int b5 = (b * 31 + 127) / 255;
    return (uint16_t)((r5 << 11) | (g6 << 5) | b5);
}

// Допускается src == dst: запись идёт медленнее чтения (2 байта против 3),
// так что конвертация на месте безопасна.
static void frame888to565(const uint8_t* src, uint8_t* dst) {
    for (int p = 0; p < SECTORS * LEDS_PER_SIDE; p++) {
        uint16_t v = pack565(src[0], src[1], src[2]);
        dst[0] = (uint8_t)(v & 0xFF);   // little-endian — рендер читает пиксель одним uint16
        dst[1] = (uint8_t)(v >> 8);
        src += 3;
        dst += 2;
    }
}

void loadFrameFromFile(String path) {
    File f = LittleFS.open(path, "r");
    if (!f) return;
    if (f.size() == 0) {
        f.close();
        LittleFS.remove(path);
        webLogf("[WARN] Removed zero-size file on play: %s", path.c_str());
        return;
    }

    // Гасим ленту на время чтения. Дело не в скорости: ЛЮБАЯ операция с флешем
    // отключает кеш инструкций и паркует второе ядро, поэтому renderingTask
    // (он исполняется из флеша и читает кадр из PSRAM) всё равно замирает —
    // но замирает не вовремя, и DMA продолжает светить кадром, снятым под
    // другим углом. На ободе это блочный мусор. Чёрное честнее.
    frame_loading = true;
    for (int i = 0; i < 1000 && render_in_fill; i++) vTaskDelay(1);

    uint32_t  t_load        = millis();
    uint8_t*  newBuf        = nullptr;
    uint32_t  newTotalFrames = 1;
    uint16_t  newFrameDelay  = 100;

    size_t fileSize = f.size();

    // Формат задаётся magic'ом: "ANI5" — кадры RGB565, "ANIM" — старые RGB888.
    // Статичная картинка заголовка не имеет и различается по размеру файла.
    char magic[4] = {0, 0, 0, 0};
    if (fileSize >= 8) f.read((uint8_t*)magic, 4);
    bool anim565 = (memcmp(magic, "ANI5", 4) == 0);
    bool anim888 = (memcmp(magic, "ANIM", 4) == 0);

    if (anim565 || anim888) {
        f.read((uint8_t*)&newTotalFrames, 2);
        f.read((uint8_t*)&newFrameDelay,  2);

        // Размер файла обязан быть заголовок + N кадров. Не сходится — файл
        // залит не полностью или со сдвигом, и рендер покажет шум. Сказать об
        // этом в лог дешевле, чем гадать, глядя на обод.
        size_t expect = 8 + (size_t)newTotalFrames *
                            (size_t)(anim888 ? FRAME_SIZE_888 : FRAME_SIZE);
        if (fileSize != expect) {
            webLogf("[WARN] %s: size %u, header says %u frames (expected %u)",
                    path.c_str(), (unsigned)fileSize,
                    (unsigned)newTotalFrames, (unsigned)expect);
        }

        size_t dataSize = (size_t)newTotalFrames * FRAME_SIZE;
        newBuf = (uint8_t*)ps_malloc(dataSize);

        // Старому формату нужен буфер под ОДИН исходный кадр: разворачивать всю
        // анимацию в RGB888 нельзя — ради этого объёма всё и затевалось.
        uint8_t* tmp = nullptr;
        if (newBuf && anim888) {
            tmp = (uint8_t*)ps_malloc(FRAME_SIZE_888);
            if (!tmp) { free(newBuf); newBuf = nullptr; }
        }

        if (newBuf) {
            // Отрисовка уже погашена, беречь шину не от кого — читаем целыми
            // кадрами, чтобы чёрная пауза вышла как можно короче. Уступаем такт
            // раз в кадр (~32 КБ): сплошное чтение мегабайтами держало бы lwIP
            // без CPU и роняло соединения.
            uint32_t got = 0;
            for (; got < newTotalFrames; got++) {
                uint8_t* dst = newBuf + (size_t)got * FRAME_SIZE;
                bool ok;
                if (anim888) {
                    ok = (f.read(tmp, FRAME_SIZE_888) == (int)FRAME_SIZE_888);
                    if (ok) frame888to565(tmp, dst);
                } else {
                    ok = (f.read(dst, FRAME_SIZE) == (int)FRAME_SIZE);
                }
                if (!ok) break;
                vTaskDelay(1);
            }
            // Файл оказался короче заявленного числа кадров — хвост должен
            // быть чёрным, а не мусором из PSRAM.
            if (got < newTotalFrames) {
                size_t done = (size_t)got * FRAME_SIZE;
                memset(newBuf + done, 0, dataSize - done);
                webLogf("[WARN] Short read: %u of %u frames",
                        (unsigned)got, (unsigned)newTotalFrames);
            }
        } else {
            newTotalFrames = 0;
            webLog("[ERR] PSRAM alloc failed");
        }
        if (tmp) free(tmp);
    } else {
        // Статичная картинка: старая — FRAME_SIZE_888 байт, новая — FRAME_SIZE.
        bool legacy = (fileSize >= FRAME_SIZE_888);
        newBuf = (uint8_t*)ps_malloc(FRAME_SIZE);
        // Обнуляем перед чтением: файл может оказаться короче кадра (например,
        // снятый со старой версии железа) — хвост должен быть чёрным, а не
        // мусором из PSRAM.
        if (newBuf) {
            memset(newBuf, 0, FRAME_SIZE);
            f.seek(0);
            if (legacy) {
                uint8_t* tmp = (uint8_t*)ps_malloc(FRAME_SIZE_888);
                if (tmp) {
                    memset(tmp, 0, FRAME_SIZE_888);
                    f.read(tmp, FRAME_SIZE_888);
                    frame888to565(tmp, newBuf);
                    free(tmp);
                }
            } else {
                f.read(newBuf, (fileSize < FRAME_SIZE) ? fileSize : FRAME_SIZE);
            }
        }
    }

    f.close();

    // Если выделить память не удалось — оставляем старую анимацию, не меняем ничего.
    if (newBuf == nullptr) {
        frame_loading = false;
        return;
    }

    // Новый буфер готов. Переключаем целиком, а не по одному полю: рендер
    // адресует кадр как frameBuffer + frame_idx·FRAME_SIZE, где frame_idx
    // считается по totalFrames. Если новое число кадров окажется выставлено
    // раньше нового буфера, рендер уедет далеко за пределы старого — на ободе
    // это блочный мусор из PSRAM, тем заметнее, чем тяжелее новая анимация.
    // Отрисовка уже стоит по frame_loading, так что гонки здесь нет.
    uint8_t* oldBuf = frameBuffer;  // Запоминаем старый указатель для free()

    // Устанавливаем новые параметры и буфер
    totalFrames       = newTotalFrames;
    frameDelay        = newFrameDelay;
    currentFrameIndex = 0;
    frameBuffer       = newBuf;

    if (oldBuf != nullptr) free(oldBuf);

    // Запускаем таймер кадров только ПОСЛЕ завершения чтения файла:
    // если поставить в начало, первый кадр будет немедленно пропущен в renderingTask.
    lastFrameSwitchTime = millis();
    newFrameReady = true;
    currentDisplayFile = path;
    frame_loading = false;          // отрисовка возобновляется

    // Длительность загрузки — это и есть длительность чёрной паузы.
    uint32_t ms = millis() - t_load;
    if (newTotalFrames > 1) {
        webLogf("[DISP] Loaded: %s  %lu frames @ %ums  (%lums)", path.c_str(),
                (unsigned long)newTotalFrames, (unsigned)newFrameDelay, (unsigned long)ms);
    } else {
        webLogf("[DISP] Loaded: %s  (%lums)", path.c_str(), (unsigned long)ms);
    }
}

void setupNetwork() {
    prefs.begin("pov_config", false);

    uint8_t mac[6];
    WiFi.macAddress(mac);
    char nameBuf[20];
    sprintf(nameBuf, "pov-wheel-%02x%02x", mac[4], mac[5]);
    hostName = String(nameBuf);

    WiFi.mode(WIFI_AP_STA);

    IPAddress apIP(192, 168, 4, 1);
    WiFi.softAPConfig(apIP, apIP, IPAddress(255, 255, 255, 0));
    WiFi.softAP(hostName.c_str(), "", 1);

    // Подключение клиента к нашей точке доступа
    WiFi.onEvent([](WiFiEvent_t event, WiFiEventInfo_t info) {
        blink_ap_client_flag = true;
        uint8_t* mac = info.wifi_ap_staconnected.mac;
        webLogf("[NET] AP client connected: %02X:%02X:%02X:%02X:%02X:%02X",
                mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
    }, ARDUINO_EVENT_WIFI_AP_STACONNECTED);

    // Отключение клиента от нашей точки доступа
    WiFi.onEvent([](WiFiEvent_t event, WiFiEventInfo_t info) {
        uint8_t* mac = info.wifi_ap_stadisconnected.mac;
        webLogf("[NET] AP client disconnected: %02X:%02X:%02X:%02X:%02X:%02X",
                mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
    }, ARDUINO_EVENT_WIFI_AP_STADISCONNECTED);

    WiFi.begin(HOTSPOT_SSID, HOTSPOT_PASS);

    uint32_t startAttempt = millis();
    bool connected = false;

    while (millis() - startAttempt < 10000) {
        if (WiFi.status() == WL_CONNECTED) {
            connected = true;
            break;
        }
        delay(500);
    }

    // ВАЖНО: НЕ переключаемся в WIFI_AP при неудаче —
    // остаемся в WIFI_AP_STA и будем повторять попытки в loopNetwork().
    if (connected) {
        sta_was_connected = true;
        blink_wifi_ok_flag = true;
        webLogf("[NET] WiFi connected, IP: %s", WiFi.localIP().toString().c_str());
    } else {
        blink_wifi_fail_flag = true;
        webLog("[NET] WiFi failed, retry in 30s");
    }
    initial_connect_done  = true;
    last_reconnect_attempt = millis();

    MDNS.begin(hostName.c_str());

    // --- WEB SERVER ---
    server.on("/", HTTP_GET, [](AsyncWebServerRequest *request){
        last_web_activity_time = millis();
        if (!LittleFS.exists("/index.html")) {
            request->send(404, "text/plain", "Upload Filesystem Image!");
            return;
        }
        // Запрещаем кеширование: конвертация картинок в полярный буфер живёт
        // в этой же странице, и после uploadfs браузер обязан взять новую
        // версию. Иначе он молча продолжает крутить старый JS, а загруженные
        // файлы получаются по старым правилам — отладка такого стоит часов.
        AsyncWebServerResponse* resp = request->beginResponse(LittleFS, "/index.html", "text/html");
        resp->addHeader("Cache-Control", "no-cache, must-revalidate");
        request->send(resp);
    });

    server.on("/settings", HTTP_GET, [](AsyncWebServerRequest *request){
        last_web_activity_time = millis();
        if (request->hasParam("bmin")) {
            int v = request->getParam("bmin")->value().toInt();
            if (v >= 1 && v <= 31) min_brightness = (uint8_t)v;
        }
        if (request->hasParam("bmax")) {
            int v = request->getParam("bmax")->value().toInt();
            if (v >= 1 && v <= 31) max_brightness = (uint8_t)v;
        }
        if (request->hasParam("a")) global_angle_offset = request->getParam("a")->value().toInt();
        if (request->hasParam("g")) {
            float gv = request->getParam("g")->value().toFloat();
            if (gv >= 1.0f && gv <= 5.0f) global_gamma = gv;
        }
        if (request->hasParam("s")) {
            float sv = request->getParam("s")->value().toFloat();
            if (sv >= 1.0f && sv <= 3.0f) global_saturation = sv;
        }
        if (request->hasParam("circ")) {
            int cv = request->getParam("circ")->value().toInt();
            if (cv >= 2000 && cv <= 2500) wheel_circumference = (uint16_t)cv;
        }
        if (request->hasParam("co")) {
            float cov = request->getParam("co")->value().toFloat();
            if (cov >= 0.0f && cov <= 100.0f) global_contrast = cov;
        }
        // Порядок лучей в цепочке относительно направления вращения (0/1)
        if (request->hasParam("ao")) {
            global_arm_reverse = request->getParam("ao")->value().toInt() != 0;
        }
        if (request->hasParam("spoke")) {
            int sv = request->getParam("spoke")->value().toInt();
            if (sv >= -50 && sv <= 50) global_spoke_offset = (int16_t)sv;
        }
        if (request->hasParam("abl")) {
            float v = request->getParam("abl")->value().toFloat();
            if (v >= 0.0f && v <= 100.0f) global_abl_limit = v;
        }
        // Радиальная компенсация яркости: 0 = выключена, 100 = полная (∝ r)
        if (request->hasParam("rad")) {
            float v = request->getParam("rad")->value().toFloat();
            if (v >= 0.0f && v <= 100.0f) global_radial_gain = v;
        }
        if (request->hasParam("rg")) {
            float v = request->getParam("rg")->value().toFloat();
            if (v >= 0.0f && v <= 100.0f) global_r_gain = v;
        }
        if (request->hasParam("gg")) {
            float v = request->getParam("gg")->value().toFloat();
            if (v >= 0.0f && v <= 100.0f) global_g_gain = v;
        }
        if (request->hasParam("bg")) {
            float v = request->getParam("bg")->value().toFloat();
            if (v >= 0.0f && v <= 100.0f) global_b_gain = v;
        }
        // Мгновенный пересчёт яркости — не ждём следующего тика датчика (50 мс)
        float ratio = constrain(last_lux_value / 1000.0f, 0.0f, 1.0f);
        global_brightness = (uint8_t)constrain(
            (int)(ratio * (float)max_brightness),
            (int)min_brightness,
            (int)max_brightness
        );
        // Пока рендеринг не активен — effective совпадает с brightness (ABL не применяется)
        if (!peripherals_active) global_effective_brightness = global_brightness;
        state_version++;
        request->send(200, "text/plain", "OK");
    });

    server.on("/get_settings", HTTP_GET, [](AsyncWebServerRequest *request){
        // Фоновый поллинг — не сбрасывает таймер активности.
        // snprintf в стековый буфер — ноль heap-аллокаций, не фрагментирует SRAM.
        char buf[448];
        snprintf(buf, sizeof(buf),
            "{\"bmin\":%u,\"bmax\":%u,\"angle\":%d,\"brightness\":%u,\"eff_bri\":%u"
            ",\"gamma\":%.1f,\"saturation\":%.1f,\"contrast\":%.1f"
            ",\"circ\":%u,\"ao\":%u,\"spoke\":%d,\"lux\":%.0f"
            ",\"abl\":%.1f,\"abl_rms\":%.1f,\"rad\":%.0f"
            ",\"rg\":%.1f,\"gg\":%.1f,\"bg\":%.1f"
            ",\"slideshow\":%s"
            ",\"ver\":%lu,\"fver\":%lu}",
            (unsigned)min_brightness, (unsigned)max_brightness,
            (int)global_angle_offset, (unsigned)global_brightness,
            (unsigned)global_effective_brightness,
            (float)global_gamma, (float)global_saturation, (float)global_contrast,
            (unsigned)wheel_circumference, (unsigned)(global_arm_reverse ? 1 : 0),
            (int)global_spoke_offset, (float)last_lux_value,
            (float)global_abl_limit, (float)(global_abl_rms * 100.0f),
            (float)global_radial_gain,
            (float)global_r_gain, (float)global_g_gain, (float)global_b_gain,
            slideshowActive ? "true" : "false",
            (unsigned long)state_version, (unsigned long)file_version
        );
        request->send(200, "application/json", buf);
    });

    server.on("/list", HTTP_GET, [](AsyncWebServerRequest *request){
        last_web_activity_time = millis();

        // Первый проход: собираем нулевые файлы для удаления.
        // Нельзя удалять во время итерации — LittleFS теряет позицию в директории.
        {
            std::vector<String> toDelete;
            File root = LittleFS.open("/");
            File f = root.openNextFile();
            while (f) {
                String fn = String(f.name());
                if (fn.endsWith(".bin") && f.size() == 0) toDelete.push_back("/" + fn);
                f = root.openNextFile();
            }
            for (auto& p : toDelete) {
                LittleFS.remove(p);
                webLogf("[WARN] Removed zero-size file: %s", p.c_str());
            }
        }

        // Второй проход: строим JSON списка файлов.
        // Используем malloc вместо String — один блок вместо многих маленьких аллокаций.
        // Каждый файл: {"name":"filename.bin","size":999999,"frames":999} — ~60 символов.
        // Резервируем 64 файла × 80 байт + скобки = ~5 кБ.
        const size_t LIST_CAP = 64 * 80 + 8;
        char* jsonBuf = (char*)malloc(LIST_CAP);
        if (!jsonBuf) {
            request->send(500, "text/plain", "OOM");
            return;
        }
        size_t pos = 0;
        jsonBuf[pos++] = '[';

        File root = LittleFS.open("/");
        File file = root.openNextFile();
        bool first = true;
        while (file) {
            const char* fn = file.name();
            size_t fnlen = strlen(fn);
            if (fnlen >= 4 && strcmp(fn + fnlen - 4, ".bin") == 0) {
                // Читаем заголовок: ANI5/ANIM — берём кол-во кадров. Раньше
                // распознавался только ANIM, и у всех новых анимаций счётчик
                // кадров в списке пропадал — а по нему видно, бьётся ли размер
                // файла с заголовком.
                uint16_t frames = 0;
                if (file.size() > 6) {
                    uint8_t hdr[6];
                    file.read(hdr, 6);
                    if (hdr[0]=='A' && hdr[1]=='N' && hdr[2]=='I' &&
                        (hdr[3]=='5' || hdr[3]=='M')) {
                        frames = hdr[4] | (hdr[5] << 8);
                    }
                }
                if (!first && pos < LIST_CAP - 1) jsonBuf[pos++] = ',';
                first = false;
                if (frames > 0) {
                    pos += snprintf(jsonBuf + pos, LIST_CAP - pos,
                        "{\"name\":\"%s\",\"size\":%u,\"frames\":%u}",
                        fn, (unsigned)file.size(), (unsigned)frames);
                } else {
                    pos += snprintf(jsonBuf + pos, LIST_CAP - pos,
                        "{\"name\":\"%s\",\"size\":%u}",
                        fn, (unsigned)file.size());
                }
            }
            file = root.openNextFile();
        }
        if (pos < LIST_CAP - 1) jsonBuf[pos++] = ']';
        jsonBuf[pos] = '\0';
        request->send(200, "application/json", jsonBuf);
        free(jsonBuf);
    });

    server.on("/fs_info", HTTP_GET, [](AsyncWebServerRequest *request){
        size_t total = LittleFS.totalBytes();
        size_t used  = LittleFS.usedBytes();
        // Анимация целиком живёт в PSRAM, поэтому места на флеше мало —
        // браузер должен уметь предупредить о слишком длинном ролике ДО заливки,
        // а не ловить "PSRAM alloc failed" при воспроизведении. Отдаём самый
        // большой непрерывный блок: ps_malloc просит именно непрерывный.
        size_t ps_free = heap_caps_get_largest_free_block(MALLOC_CAP_SPIRAM);
        char buf[128];
        snprintf(buf, sizeof(buf),
                 "{\"total\":%u,\"used\":%u,\"free\":%u,\"psram_free\":%u,\"frame_size\":%u}",
                 (unsigned)total, (unsigned)used, (unsigned)(total - used),
                 (unsigned)ps_free, (unsigned)FRAME_SIZE);
        request->send(200, "application/json", buf);
    });

    server.on("/play", HTTP_GET, [](AsyncWebServerRequest *request){
        last_web_activity_time = millis();
        if (request->hasParam("file")) {
            String fname = request->getParam("file")->value();
            // Сохраняем файл ДО загрузки: если загрузка упадёт с крашем,
            // после перезагрузки устройство восстановит правильный файл.
            prefs.putString("last_file", fname);
            slideshowActive = false;
            force_stop_display = false;
            // Передаём загрузку в fileLoaderTask (Core 0, приоритет 2).
            // Это освобождает WiFi-задачу немедленно — браузер получает ответ
            // без ожидания пока LittleFS прочитает весь файл (может быть секунды).
            pendingFilePath = "/" + fname;
            request_play_flag = true;
            xSemaphoreGive(fileLoaderSemaphore);
            state_version++;
            file_version++;
            webLogf("[DISP] Play: %s", fname.c_str());
            request->send(200, "text/plain", "Playing");
        }
    });

    server.on("/stop", HTTP_GET, [](AsyncWebServerRequest *request){
        last_web_activity_time = millis();
        slideshowActive = false;
        force_stop_display = true;
        state_version++;
        webLog("[DISP] Stop");
        request->send(200, "text/plain", "Stopped");
    });

    // GET /album?action=start|stop&delay=<мс> — управление слайдшоу
    server.on("/album", HTTP_GET, [](AsyncWebServerRequest *request){
        last_web_activity_time = millis();
        if (request->hasParam("action")) {
            String action = request->getParam("action")->value();
            if (action == "start") {
                if (request->hasParam("delay")) {
                    uint32_t ms = (uint32_t)request->getParam("delay")->value().toInt();
                    if (ms >= 1000 && ms <= 300000) slideInterval = ms;
                }
                if (slideshowActive) {
                    // Слайдшоу уже идёт — только обновляем интервал, не сбрасываем индекс
                    webLogf("[DISP] Slideshow interval -> %lus", (unsigned long)(slideInterval / 1000));
                    request->send(200, "text/plain", "OK");
                    return;
                }
                updateFileList(); // Обновляем список файлов перед стартом
                if (savedFiles.size() == 0) {
                    request->send(400, "text/plain", "No files");
                    return;
                }
                force_stop_display = false;
                slideshowActive = true;
                slideCurrentIndex = -1;  // loop() немедленно запустит первый файл
                slideLastSwitch   = 0;
                state_version++;
                webLogf("[DISP] Slideshow start, interval %lus", (unsigned long)(slideInterval / 1000));
                request->send(200, "text/plain", "OK");
            } else if (action == "stop") {
                slideshowActive = false;
                state_version++;
                webLog("[DISP] Slideshow stop");
                request->send(200, "text/plain", "OK");
            } else {
                request->send(400, "text/plain", "Unknown action");
            }
        } else {
            // GET без параметров — возвращает текущее состояние
            char buf[64];
            snprintf(buf, sizeof(buf), "{\"active\":%s,\"delay\":%lu}",
                     slideshowActive ? "true" : "false",
                     (unsigned long)slideInterval);
            request->send(200, "application/json", buf);
        }
    });

    server.on("/delete", HTTP_GET, [](AsyncWebServerRequest *request){
        last_web_activity_time = millis();
        if (request->hasParam("file")) {
            String path = "/" + request->getParam("file")->value();
            // Если файл сейчас открыт на запись (прерванная загрузка) — закрываем его,
            // иначе LittleFS не освободит блоки при удалении
            if (uploadFile && String(uploadFile.path()) == path) {
                uploadFile.close();
                webLogf("[WARN] Closed open upload before delete: %s", path.c_str());
            }
            LittleFS.remove(path);
            state_version++;
            file_version++;
            request->send(200, "text/plain", "Deleted");
        }
    });

    server.on("/upload", HTTP_POST, [](AsyncWebServerRequest *request){
        last_web_activity_time = millis();
        // Этот запрос не получал слот (шла чужая загрузка) — он ничего не писал,
        // поэтому здесь только честный отказ, без чистки чужого файла.
        if (request != uploadOwner) {
            request->send(409, "text/plain", "Upload already in progress");
            return;
        }
        uploadOwner = nullptr;
        // Если uploadFile всё ещё открыт — передача прервалась на полуслове.
        // Закрываем и удаляем незавершённый файл, чтобы не оставлять мусор 0 kB.
        if (uploadFile) {
            String badPath = uploadFile.path();
            uploadFile.close();
            LittleFS.remove(badPath);
            webLogf("[ERR] Upload aborted, removed: %s", badPath.c_str());
            request->send(500, "text/plain", "Upload incomplete");
            return;
        }
        // Тело записалось не полностью (кончилось место, пропущенный кусок).
        // Файл уже удалён в обработчике тела — здесь только честный ответ,
        // иначе браузер посчитает битый файл успешно залитым.
        if (uploadFailed) {
            uploadFailed = false;
            request->send(507, "text/plain", "Write failed (out of space?)");
            return;
        }
        state_version++;
        request->send(200, "text/plain", "OK");
    }, NULL, [](AsyncWebServerRequest *request, uint8_t *data, size_t len, size_t index, size_t total){
        last_web_activity_time = millis();
        String filepath = "/" + (request->hasParam("name") ? request->getParam("name")->value() : "temp.bin");
        if (index == 0) {
            // Слот занят живой загрузкой (куски идут прямо сейчас) — второй
            // запрос отбрасываем целиком: ни файла, ни флагов он не трогает.
            if (uploadFile && uploadOwner && uploadOwner != request &&
                (millis() - uploadLastChunkMs) < UPLOAD_OWNER_TIMEOUT_MS) {
                webLogf("[WARN] Parallel upload rejected: %s", filepath.c_str());
                return;
            }
            uploadOwner  = request;
            uploadFailed = false;
            // Если предыдущая загрузка не завершилась корректно — убираем мусор
            if (uploadFile) {
                String badPath = uploadFile.path();
                uploadFile.close();
                LittleFS.remove(badPath);
                webLogf("[ERR] Stale upload removed: %s", badPath.c_str());
            }
            // Явно удаляем файл перед созданием: гарантирует что LittleFS
            // освободит старые блоки до выделения новых, а не после.
            if (LittleFS.exists(filepath)) LittleFS.remove(filepath);
            uploadFile = LittleFS.open(filepath, "w");

            // Регистрируем обработчик разрыва соединения (обновление страницы,
            // потеря связи): закрываем и удаляем незавершённый файл немедленно.
            // onDisconnect — метод request, вызывается при TCP disconnect.
            // Проверка владельца обязательна: колбэк срабатывает и при штатном
            // закрытии соединения, уже после того как слот мог перейти к
            // следующей загрузке — без неё он удалил бы чужой файл.
            request->onDisconnect([request](){
                if (uploadOwner != request) return;
                uploadOwner = nullptr;
                if (uploadFile) {
                    String badPath = uploadFile.path();
                    uploadFile.close();
                    LittleFS.remove(badPath);
                    webLogf("[ERR] Upload interrupted (disconnect), removed: %s", badPath.c_str());
                }
            });
        }
        // Кусок от запроса, которому слот не достался — молча выбрасываем.
        if (request != uploadOwner) return;
        uploadLastChunkMs = millis();
        // Записываем только если ещё не сорвались: после ошибки файла уже нет,
        // и продолжать сыпать в него куски незачем.
        if (uploadFile && !uploadFailed) {
            // Позиция в файле обязана совпадать с index. Не совпала — кусок
            // тела потерялся или пришёл дважды, и всё, что дальше, легло бы со
            // сдвигом: кадры поехали бы относительно заголовка, а это ровно тот
            // «шум вместо картинки», который не отличить от битого файла.
            if (index != uploadFile.position()) {
                webLogf("[ERR] Upload out of sync at %u (file at %u): %s",
                        (unsigned)index, (unsigned)uploadFile.position(), filepath.c_str());
                uploadFailed = true;
            } else if (uploadFile.write(data, len) != len) {
                // LittleFS кончилось место. Раньше результат write() не
                // проверялся: файл молча обрезался, а браузер получал 200 OK.
                webLogf("[ERR] Short write at %u/%u (no space?): %s",
                        (unsigned)index, (unsigned)total, filepath.c_str());
                uploadFailed = true;
            }
            if (uploadFailed) {
                uploadFile.close();
                LittleFS.remove(filepath);
                return;
            }
        }
        if (index + len == total && uploadFile) {
            // Размер обязан совпасть с заявленным Content-Length.
            size_t written = uploadFile.position();
            uploadFile.close();  // после close() объект становится false — сигнал onRequest об успехе
            if (written != total) {
                webLogf("[ERR] Size mismatch %u != %u, removed: %s",
                        (unsigned)written, (unsigned)total, filepath.c_str());
                LittleFS.remove(filepath);
                uploadFailed = true;
                return;
            }
            file_version++;
        }
    });

    server.on("/ping", HTTP_GET, [](AsyncWebServerRequest *request){
        // Фоновый heartbeat — не сбрасывает таймер активности.
        request->send(200, "text/plain", "OK");
    });

    server.on("/info", HTTP_GET, [](AsyncWebServerRequest *request){
        // Фоновый поллинг — не сбрасывает таймер активности
        uint32_t period  = rotation_period;
        uint32_t hall_t  = last_hall_time;
        uint32_t now_us  = micros();
        // Защита от переполнения uint32_t при вычитании
        uint32_t elapsed = (now_us >= hall_t) ? (now_us - hall_t)
                                              : (0xFFFFFFFFUL - hall_t + now_us + 1);
        // period — время полного оборота, измеренное одним датчиком Холла.
        // Если событий давно не было, реальный период не меньше времени молчания —
        // берём максимум из двух, чтобы RPM спадал плавно, а не «залипал».
        float rpm = 0.0f;
        if (period > 0 && elapsed < 3000000UL) {
            uint32_t eff = (elapsed > period) ? elapsed : period;
            rpm = 60000000.0f / (float)eff;
        }
        // step — угол, который луч проходит между обновлениями ленты; это и есть
        // предел угловой чёткости. fill — время сборки кадра, для контроля запаса
        // по CPU (должно оставаться заметно меньше времени передачи по SPI).
        char buf[112];
        snprintf(buf, sizeof(buf),
                 "{\"rpm\":%.1f,\"dir\":%d,\"pwr\":%u,\"step\":%.2f,\"fill\":%u}",
                 rpm, (int)rotation_dir, (unsigned)power_state,
                 (float)global_render_span, (unsigned)global_render_fill_us);
        request->send(200, "application/json", buf);
    });

    // POST /settime?t=<unix>&tz=<секунды> — синхронизирует время и часовой пояс.
    // t  — Unix timestamp UTC (секунды с 1970-01-01)
    // tz — смещение часового пояса в секундах (UTC+2 → +7200, передаётся браузером)
    server.on("/settime", HTTP_POST, [](AsyncWebServerRequest *request){
        if (request->hasParam("t")) {
            _time_epoch_base  = (uint32_t)request->getParam("t")->value().toInt();
            _time_millis_base = millis();
        }
        if (request->hasParam("tz")) {
            _time_tz_offset = (int32_t)request->getParam("tz")->value().toInt();
        }
        // Ретроспективно проставляем метки строкам записанным до синхронизации (??)
        portENTER_CRITICAL(&_log_mux);
        _retroFillTimestamps();
        portEXIT_CRITICAL(&_log_mux);
        request->send(200, "text/plain", "OK");
    });

    // GET /logs?since=N — возвращает JSON с записями начиная с глобального индекса N.
    // Клиент передаёт последний полученный total, получает только новые строки.
    // Формат: {"total":42,"lines":["msg1","msg2",...]}
    server.on("/logs", HTTP_GET, [](AsyncWebServerRequest *request){
        uint32_t since = 0;
        if (request->hasParam("since")) since = request->getParam("since")->value().toInt();

        portENTER_CRITICAL(&_log_mux);
        uint32_t total = _log_total;
        portEXIT_CRITICAL(&_log_mux);

        // Определяем диапазон записей для отдачи: [since, total)
        uint32_t from = since;
        if (from > total) from = 0;
        if (total - from > WEB_LOG_COUNT) from = total - WEB_LOG_COUNT;

        // Максимальный размер ответа: заголовок ~40 + N строк × (2 + WEB_LOG_LINE + 2 escape)
        // WEB_LOG_COUNT=64, WEB_LOG_LINE=96 → 64 × (96*2+4) = ~12 кБ — вмещается в один буфер.
        // Выделяем из heap (не стек — слишком велик для задачи AsyncWebServer ~4кБ стека).
        const size_t BUF_CAP = 40 + WEB_LOG_COUNT * (WEB_LOG_LINE * 2 + 4) + 4;
        char* buf = (char*)malloc(BUF_CAP);
        if (!buf) {
            request->send(500, "text/plain", "OOM");
            return;
        }

        size_t pos = 0;
        // Заголовок
        pos += snprintf(buf + pos, BUF_CAP - pos,
            "{\"total\":%lu,\"now\":%lu,\"lines\":[",
            (unsigned long)total, (unsigned long)_currentEpoch());

        bool first = true;
        for (uint32_t i = from; i < total; i++) {
            uint32_t idx = i % WEB_LOG_COUNT;
            if (!first && pos < BUF_CAP - 1) buf[pos++] = ',';
            first = false;
            if (pos < BUF_CAP - 1) buf[pos++] = '"';
            // Экранируем кавычки и обратные слэши для корректного JSON
            const char* p = _log_buf[idx];
            while (*p && pos < BUF_CAP - 3) {
                if (*p == '"' || *p == '\\') buf[pos++] = '\\';
                buf[pos++] = *p++;
            }
            if (pos < BUF_CAP - 1) buf[pos++] = '"';
        }
        if (pos < BUF_CAP - 2) { buf[pos++] = ']'; buf[pos++] = '}'; }
        buf[pos] = '\0';

        request->send(200, "application/json", buf);
        free(buf);
    });

    // GET /preview?file=X — возвращает первый фрейм (FRAME_SIZE байт) для рендеринга превью в браузере.
    // Для ANIM-файла пропускает 8-байтовый заголовок и возвращает первый фрейм.
    // Для статичного изображения возвращает данные с начала файла.
    server.on("/preview", HTTP_GET, [](AsyncWebServerRequest *request){
        if (!request->hasParam("file")) {
            request->send(400, "text/plain", "Missing file");
            return;
        }
        String path = "/" + request->getParam("file")->value();
        File f = LittleFS.open(path, "r");
        if (!f || f.size() < 8) {
            if (f) f.close();
            request->send(404, "text/plain", "Not found");
            return;
        }

        // Определяем смещение первого фрейма и его формат.
        uint8_t hdr[8];
        f.read(hdr, 8);
        size_t offset = 0;
        bool   legacy = false;
        if (hdr[0]=='A' && hdr[1]=='N' && hdr[2]=='I' && hdr[3]=='5') {
            offset = 8;                             // RGB565: magic + frame_count + frame_delay
        } else if (hdr[0]=='A' && hdr[1]=='N' && hdr[2]=='I' && hdr[3]=='M') {
            offset = 8;  legacy = true;             // старая анимация RGB888
        } else {
            offset = 0;                             // статичное изображение — данные с начала
            legacy = (f.size() >= FRAME_SIZE_888);
        }

        // Наружу всегда уходит RGB565: браузеру незачем знать о старом формате.
        size_t srcLen = legacy ? FRAME_SIZE_888 : FRAME_SIZE;
        size_t avail  = f.size() - offset;

        // Читаем в PSRAM; ownership передаётся лямбде через shared_ptr —
        // память освобождается автоматически когда AsyncWebServer завершит отправку.
        // Кадр великоват, чтобы держать его во внутреннем heap.
        auto frameBuf = std::shared_ptr<uint8_t>((uint8_t*)ps_malloc(srcLen), free);
        if (!frameBuf) {
            f.close();
            request->send(500, "text/plain", "OOM");
            return;
        }
        // Обнуляем: обрезанный файл должен дать чёрный хвост, а не мусор из PSRAM.
        memset(frameBuf.get(), 0, srcLen);
        f.seek(offset);
        f.read(frameBuf.get(), (avail < srcLen) ? avail : srcLen);
        f.close();
        if (legacy) frame888to565(frameBuf.get(), frameBuf.get());

        size_t capturedSize = FRAME_SIZE;
        AsyncWebServerResponse* resp = request->beginChunkedResponse(
            "application/octet-stream",
            [frameBuf, capturedSize](uint8_t* buf, size_t maxLen, size_t index) -> size_t {
                if (index >= capturedSize) return 0;
                size_t chunk = std::min(maxLen, capturedSize - index);
                memcpy(buf, frameBuf.get() + index, chunk);
                return chunk;
            }
        );
        resp->addHeader("Cache-Control", "max-age=300");
        request->send(resp);
    });

    ElegantOTA.begin(&server);
    ElegantOTA.onStart(safeOTAShutdown);
    // server.begin() намеренно НЕ вызывается здесь.
    // AsyncTCP начинает принимать соединения сразу после begin(),
    // и браузер, открытый до загрузки устройства, немедленно шлёт запросы
    // пока setup() ещё не завершился (LittleFS, /battery endpoint и т.д.).
    // Задача AsyncTCP зависает ожидая lwIP → Task WDT через 5с → перезагрузка.
    // server.begin() вызывается из setup() после полной инициализации.

    ArduinoOTA.setHostname(hostName.c_str());
    ArduinoOTA.onStart(safeOTAShutdown);
    ArduinoOTA.begin();
}

void loopNetwork() {
    ArduinoOTA.handle();
    ElegantOTA.loop();

    // --- Мониторинг и переподключение к домашней сети WiFi ---
    if (initial_connect_done) {
        wl_status_t sta_status = WiFi.status();
        uint32_t now_ms = millis();

        if (sta_status == WL_CONNECTED && !sta_was_connected) {
            // Новое соединение установлено (первичное или после обрыва)
            sta_was_connected = true;
            blink_wifi_ok_flag = true;
            webLogf("[NET] WiFi reconnected, IP: %s", WiFi.localIP().toString().c_str());
        } else if (sta_status != WL_CONNECTED && sta_was_connected) {
            // Соединение потеряно
            sta_was_connected = false;
            webLog("[NET] WiFi lost");
        }

        // Периодическая попытка переподключения каждые 30 секунд
        if (sta_status != WL_CONNECTED && now_ms - last_reconnect_attempt > 30000) {
            last_reconnect_attempt = now_ms;
            webLog("[NET] WiFi reconnecting...");
            WiFi.begin(HOTSPOT_SSID, HOTSPOT_PASS);
        }
    }
}
