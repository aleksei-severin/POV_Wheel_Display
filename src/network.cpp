#include "network.h"
#include <WiFi.h>
#include <ESPmDNS.h>
#include <ArduinoOTA.h>
#include <ESPAsyncWebServer.h>
#include <ElegantOTA.h>
#include <LittleFS.h>
#include <Preferences.h>
#include <FastLED.h>
#include <HTTPClient.h>
#include <stdarg.h>
#include <vector>

AsyncWebServer server(80);
Preferences prefs;
File uploadFile;

String hostName;
String currentDisplayFile = "";   // Имя файла, загруженного в frameBuffer

// Счётчик версии состояния: инкрементируется при любом изменении (настройки,
// файлы, воспроизведение). Клиенты сравнивают с последней известной версией
// и обновляют UI при расхождении — синхронизация нескольких браузеров.
static uint32_t state_version = 0;

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
    FastLED.clear();
    sendLEDs_DMA();
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

    // Читаем новый файл в ВРЕМЕННЫЙ буфер, не трогая frameBuffer.
    // Старая анимация продолжает рендериться на Core 1 всё время загрузки.
    uint8_t*  newBuf        = nullptr;
    uint32_t  newTotalFrames = 1;
    uint16_t  newFrameDelay  = 100;

    size_t fileSize = f.size();

    if (fileSize > FRAME_SIZE) {
        char magic[4];
        f.read((uint8_t*)magic, 4);
        if (magic[0] == 'A' && magic[1] == 'N' && magic[2] == 'I' && magic[3] == 'M') {
            f.read((uint8_t*)&newTotalFrames, 2);
            f.read((uint8_t*)&newFrameDelay,  2);

            size_t dataSize = newTotalFrames * FRAME_SIZE;
            newBuf = (uint8_t*)ps_malloc(dataSize);

            if (newBuf) {
                f.read(newBuf, dataSize);
            } else {
                newTotalFrames = 0;
                webLog("[ERR] PSRAM alloc failed");
            }
        } else {
            f.seek(0);
            newBuf = (uint8_t*)ps_malloc(FRAME_SIZE);
            if (newBuf) f.read(newBuf, FRAME_SIZE);
        }
    } else {
        newBuf = (uint8_t*)ps_malloc(FRAME_SIZE);
        if (newBuf) f.read(newBuf, FRAME_SIZE);
    }

    f.close();

    // Если выделить память не удалось — оставляем старую анимацию, не меняем ничего.
    if (newBuf == nullptr) return;

    // Новый буфер готов. Атомарно переключаем: останавливаем рендеринг только на
    // одну итерацию loop renderingTask, чтобы безопасно заменить указатель.
    newFrameReady = false;          // renderingTask сделает continue на следующем обороте

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

    if (newTotalFrames > 1) {
        webLogf("[DISP] Loaded: %s  %lu frames @ %ums", path.c_str(), (unsigned long)newTotalFrames, (unsigned)newFrameDelay);
    } else {
        webLogf("[DISP] Loaded: %s", path.c_str());
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
        if (LittleFS.exists("/index.html")) request->send(LittleFS, "/index.html", "text/html");
        else request->send(404, "text/plain", "Upload Filesystem Image!");
    });

    server.on("/settings", HTTP_GET, [](AsyncWebServerRequest *request){
        last_web_activity_time = millis();
        if (request->hasParam("bmin")) min_brightness = request->getParam("bmin")->value().toInt();
        if (request->hasParam("bmax")) max_brightness = request->getParam("bmax")->value().toInt();
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
        // Мгновенный пересчёт яркости — не ждём следующего тика датчика (50 мс)
        float ratio = constrain(last_lux_value / 1000.0f, 0.0f, 1.0f);
        global_brightness = (uint8_t)constrain(
            (int)(ratio * (float)max_brightness),
            (int)min_brightness,
            (int)max_brightness
        );
        state_version++;
        request->send(200, "text/plain", "OK");
    });

    server.on("/get_settings", HTTP_GET, [](AsyncWebServerRequest *request){
        // Фоновый поллинг — не сбрасывает таймер активности
        String json = "{";
        json += "\"bmin\":" + String(min_brightness) + ",";
        json += "\"bmax\":" + String(max_brightness) + ",";
        json += "\"angle\":" + String(global_angle_offset) + ",";
        json += "\"brightness\":" + String(global_brightness) + ",";
        json += "\"gamma\":" + String(global_gamma, 1) + ",";
        json += "\"saturation\":" + String(global_saturation, 1) + ",";
        json += "\"contrast\":" + String(global_contrast, 1) + ",";
        json += "\"circ\":" + String(wheel_circumference) + ",";
        json += "\"ver\":" + String(state_version);
        json += "}";
        request->send(200, "application/json", json);
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

        // Второй проход: строим JSON списка файлов
        File root = LittleFS.open("/");
        String json = "[";
        File file = root.openNextFile();
        bool first = true;
        while(file) {
            String fn = String(file.name());
            if(fn.endsWith(".bin")) {
                if(!first) json += ",";
                // Читаем заголовок: если файл начинается с "ANIM" — это GIF, берём кол-во кадров
                uint16_t frames = 0;
                if (file.size() > 6) {
                    uint8_t hdr[6];
                    file.read(hdr, 6);
                    if (hdr[0]=='A' && hdr[1]=='N' && hdr[2]=='I' && hdr[3]=='M') {
                        frames = hdr[4] | (hdr[5] << 8);
                    }
                }
                json += "{\"name\":\"" + fn + "\",\"size\":" + String(file.size());
                if (frames > 0) json += ",\"frames\":" + String(frames);
                json += "}";
                first = false;
            }
            file = root.openNextFile();
        }
        json += "]";
        request->send(200, "application/json", json);
    });

    server.on("/fs_info", HTTP_GET, [](AsyncWebServerRequest *request){
        size_t total = LittleFS.totalBytes();
        size_t used  = LittleFS.usedBytes();
        String json = "{\"total\":" + String(total) + ",\"used\":" + String(used) + ",\"free\":" + String(total - used) + "}";
        request->send(200, "application/json", json);
    });

    server.on("/play", HTTP_GET, [](AsyncWebServerRequest *request){
        last_web_activity_time = millis();
        if (request->hasParam("file")) {
            String fname = request->getParam("file")->value();
            // Сохраняем файл ДО загрузки: если загрузка упадёт с крашем,
            // после перезагрузки устройство восстановит правильный файл.
            prefs.putString("last_file", fname);
            force_stop_display = false;
            // Передаём загрузку в fileLoaderTask (Core 0, приоритет 2).
            // Это освобождает WiFi-задачу немедленно — браузер получает ответ
            // без ожидания пока LittleFS прочитает весь файл (может быть секунды).
            pendingFilePath = "/" + fname;
            request_play_flag = true;
            xSemaphoreGive(fileLoaderSemaphore);
            state_version++;
            webLogf("[DISP] Play: %s", fname.c_str());
            request->send(200, "text/plain", "Playing");
        }
    });

    server.on("/stop", HTTP_GET, [](AsyncWebServerRequest *request){
        last_web_activity_time = millis();
        force_stop_display = true;
        state_version++;
        webLog("[DISP] Stop");
        request->send(200, "text/plain", "Stopped");
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
            request->send(200, "text/plain", "Deleted");
        }
    });

    server.on("/upload", HTTP_POST, [](AsyncWebServerRequest *request){
        last_web_activity_time = millis();
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
        state_version++;
        request->send(200, "text/plain", "OK");
    }, NULL, [](AsyncWebServerRequest *request, uint8_t *data, size_t len, size_t index, size_t total){
        last_web_activity_time = millis();
        String filepath = "/" + (request->hasParam("name") ? request->getParam("name")->value() : "temp.bin");
        if (index == 0) {
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
            request->onDisconnect([](){
                if (uploadFile) {
                    String badPath = uploadFile.path();
                    uploadFile.close();
                    LittleFS.remove(badPath);
                    webLogf("[ERR] Upload interrupted (disconnect), removed: %s", badPath.c_str());
                }
            });
        }
        if (uploadFile) uploadFile.write(data, len);
        if (index + len == total && uploadFile) {
            uploadFile.close();  // после close() объект становится false — сигнал onRequest об успехе
        }
    });

    server.on("/ping", HTTP_GET, [](AsyncWebServerRequest *request){
        last_web_activity_time = millis();
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
        // Если с последнего прохода магнита прошло > 2с — колесо остановлено
        float rpm = 0.0f;
        if (period > 0 && elapsed < 2000000UL) {
            rpm = 60000000.0f / (float)period;
        }
        String json = "{\"rpm\":" + String(rpm, 1) + "}";
        request->send(200, "application/json", json);
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
        uint32_t head  = _log_head;
        portEXIT_CRITICAL(&_log_mux);

        // Определяем диапазон записей для отдачи: [since, total)
        uint32_t from = since;
        if (from > total) from = 0;                            // Устройство перезагрузилось — отдаём с начала
        if (total - from > WEB_LOG_COUNT) from = total - WEB_LOG_COUNT; // Буфер уже перезаписан

        String json = "{\"total\":" + String(total) +
                      ",\"now\":" + String(_currentEpoch()) +
                      ",\"lines\":[";
        bool first = true;
        for (uint32_t i = from; i < total; i++) {
            uint32_t idx = i % WEB_LOG_COUNT;
            if (!first) json += ',';
            first = false;
            // Экранируем кавычки и обратные слэши для корректного JSON
            json += '"';
            const char* p = _log_buf[idx];
            while (*p) {
                if (*p == '"' || *p == '\\') json += '\\';
                json += *p++;
            }
            json += '"';
        }
        json += "]}";
        request->send(200, "application/json", json);
    });

    ElegantOTA.begin(&server);
    ElegantOTA.onStart(safeOTAShutdown);
    server.begin();

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
