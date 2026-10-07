// Хранилище и служебное: NVS, загрузка кадра с LittleFS, кольцо лога в RTC,
// часы, останов перед OTA. Раньше это жило в network.cpp вместе с Wi-Fi и
// веб-сервером; их больше нет — единственный транспорт BLE (povble.cpp).
#include "storage.h"
#include "effects.h"
#include "povble.h"
#include "hall_log.h"
#include <LittleFS.h>
#include <Preferences.h>
#include <stdarg.h>
#include <sys/time.h>

Preferences prefs;

String currentDisplayFile = "";   // Имя файла, загруженного в frameBuffer
// Имя, которое нужно записать в NVS как последний воспроизведённый. Запись
// откладывается до момента, когда лента уже погашена (см. loadFrameFromFile).
String pendingPlayFile = "";

// Счётчики версий состояния и списка файлов определены в povble.cpp
// (pov_state_version / pov_file_version). Клиент сравнивает версию с
// последней известной и обновляет UI только при расхождении.

// ===================== LOG BUFFER =====================
// Кольцевой буфер в RTC SLOW RAM — переживает deep sleep и любой сброс,
// кроме включения питания (в том числе падение: см. RTC_NOINIT ниже).
// ESP32-S3 RTC SLOW RAM = 8192 байт, из них ~1 кБ занимает ESP-IDF.
// 64 строки × 96 байт = 6144 байт — укладываемся с запасом.
#define WEB_LOG_COUNT   64
#define WEB_LOG_LINE    96

// Формат в буфере: "YYYY-MM-DD HH:MM:SS msg\0"
//
// RTC_NOINIT, а не RTC_DATA. RTC_DATA загрузчик заново заполняет начальными
// значениями при любом сбросе, кроме пробуждения из сна, — и после падения
// (panic, WDT, brownout) лог начинался с чистого листа, теряя ровно те строки,
// что вели к сбою. NOINIT не трогает никто; после включения питания там мусор,
// его отличает метка (_logEnsure).
#define LOG_MAGIC 0x4C4F4731u   // "LOG1"
RTC_NOINIT_ATTR static uint32_t _log_magic;
RTC_NOINIT_ATTR static char     _log_buf[WEB_LOG_COUNT][WEB_LOG_LINE];
RTC_NOINIT_ATTR static uint32_t _log_ms[WEB_LOG_COUNT];  // millis() в момент записи каждой строки
RTC_NOINIT_ATTR static uint32_t _log_head;   // Индекс следующей записи (кольцо)
RTC_NOINIT_ATTR static uint32_t _log_total;  // Всего записей с начала времён
static bool _log_checked = false;            // обычная RAM: false на каждом старте

// Unix-время в момент последней синхронизации + millis() в тот же момент.
// Переживают deep sleep — позволяют считать текущее время без NTP.
RTC_DATA_ATTR static uint32_t _time_epoch_base  = 0;  // Unix timestamp при синхронизации
RTC_DATA_ATTR static uint32_t _time_millis_base = 0;  // millis() при синхронизации
RTC_DATA_ATTR static int32_t  _time_tz_offset   = 0;  // Смещение часового пояса в секундах (UTC+2 = +7200)

static portMUX_TYPE _log_mux = portMUX_INITIALIZER_UNLOCKED;

// Первое обращение к кольцу за сеанс — проверить, что в нём не мусор после
// включения питания. Вызывать под _log_mux.
static void _logEnsure() {
    if (_log_checked) return;
    _log_checked = true;
    if (_log_magic != LOG_MAGIC || _log_head != _log_total) {
        memset(_log_buf, 0, sizeof(_log_buf));
        memset(_log_ms, 0, sizeof(_log_ms));
        _log_head = _log_total = 0;
        _log_magic = LOG_MAGIC;
        return;
    }
    // Пережили сброс: строка, которую писали в момент сбоя, могла остаться без
    // завершающего нуля.
    for (int i = 0; i < WEB_LOG_COUNT; i++) _log_buf[i][WEB_LOG_LINE - 1] = '\0';
}

// Часы идут по СИСТЕМНОМУ времени newlib, а не по паре «epoch + millis()».
// Разница принципиальная: системное время привязано к счётчику RTC, который
// продолжает считать и в глубоком сне, и через программный сброс, а ESP-IDF
// восстанавливает из него время на старте (esp_set_time_from_rtc). Пара с
// millis() этого не умела — millis() в новой сессии начинается с нуля, и время
// терялось при КАЖДОМ засыпании, то есть каждые 60 с простоя.
//
// Если восстановление почему-то не сработает, time() вернёт значение около нуля,
// проверка ниже сочтёт время неизвестным, и поведение выродится в прежнее:
// «??:??:??» в логе и пустой циферблат, пока часы не выставит телефон. То есть
// хуже, чем было, стать не может.
//
// Порог — начало 2023 года: меньшее значение означает, что часы не заводили.
#define TIME_VALID_FROM  1700000000UL

static uint32_t _currentEpoch() {
    time_t now = time(nullptr);
    if ((uint32_t)now < TIME_VALID_FROM) return 0;
    return (uint32_t)now;
}

// Смещение пояса лежит в RTC-памяти и переживает сон вместе с часами. После
// полного снятия питания там может оказаться что угодно, а теперь по нему
// рисуется циферблат — поэтому проверяем диапазон реальных поясов (±14 ч).
static int32_t _tzOffset() {
    if (_time_tz_offset < -50400 || _time_tz_offset > 50400) return 0;
    return _time_tz_offset;
}

// Местное время часы/минуты/секунды. false — телефон ещё не выставлял часы,
// и показывать что-либо, кроме пустого циферблата, было бы враньём.
bool localClock(int& h, int& m, int& s) {
    uint32_t epoch = _currentEpoch();
    if (epoch == 0) { h = m = s = 0; return false; }
    int64_t local = (int64_t)epoch + (int64_t)_tzOffset();
    if (local < 0) local = 0;
    uint32_t secs = (uint32_t)(local % 86400);
    h = (int)(secs / 3600);
    m = (int)((secs % 3600) / 60);
    s = (int)(secs % 60);
    return true;
}

// Местная дата — для цифровых часов. Дни от эпохи в гражданский календарь
// (алгоритм Хиннанта): gmtime_r() здесь не нужен, пояс уже сложен вручную.
bool localDate(int& y, int& mo, int& d) {
    uint32_t epoch = _currentEpoch();
    if (epoch == 0) { y = mo = d = 0; return false; }
    int64_t local = (int64_t)epoch + (int64_t)_tzOffset();
    if (local < 0) local = 0;
    int32_t z   = (int32_t)(local / 86400) + 719468;
    int32_t era = z / 146097;
    uint32_t doe = (uint32_t)(z - era * 146097);
    uint32_t yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    uint32_t doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    uint32_t mp  = (5 * doy + 2) / 153;
    d  = (int)(doy - (153 * mp + 2) / 5 + 1);
    mo = (int)(mp < 10 ? mp + 3 : mp - 9);
    y  = (int)yoe + era * 400 + (mo <= 2 ? 1 : 0);
    return true;
}

// Вызывается в начале setup(). Сами часы здесь НЕ сбрасываются — они живут в
// системном времени и обязаны пережить сон. Обнуляется только пара, по которой
// задним числом проставляются метки строк лога: она привязана к millis(), а он
// в новой сессии начинается заново.
// _time_tz_offset тоже сохраняем: он лежит в RTC, и после пробуждения время
// сразу показывается местное, не дожидаясь телефона.
void resetTimeSync() {
    _time_epoch_base  = 0;
    _time_millis_base = 0;
}

// Форматирует "YYYY-MM-DD HH:MM:SS" из Unix timestamp с учётом часового пояса в буфер buf[20].
static void _fmtDateTime(uint32_t epoch, char* buf) {
    if (epoch == 0) {
        strcpy(buf, "???? ?? ?? ??:??:??");
        return;
    }
    // Применяем смещение часового пояса
    int64_t local_epoch = (int64_t)epoch + (int64_t)_tzOffset();
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

    // Вручную, без snprintf: функцию зовут и под спин-блокировкой лога
    // (_retroFillTimestamps), а там нельзя ничего, что может взять мьютекс.
    uint32_t hh = secs / 3600, mi = (secs % 3600) / 60, ss = secs % 60;
    buf[0] = '0' + (y / 1000) % 10; buf[1] = '0' + (y / 100) % 10;
    buf[2] = '0' + (y / 10) % 10;   buf[3] = '0' + y % 10;
    buf[4] = '-';  buf[5] = '0' + m / 10;  buf[6] = '0' + m % 10;
    buf[7] = '-';  buf[8] = '0' + d / 10;  buf[9] = '0' + d % 10;
    buf[10] = ' '; buf[11] = '0' + hh / 10; buf[12] = '0' + hh % 10;
    buf[13] = ':'; buf[14] = '0' + mi / 10; buf[15] = '0' + mi % 10;
    buf[16] = ':'; buf[17] = '0' + ss / 10; buf[18] = '0' + ss % 10;
    buf[19] = '\0';
}

// Ретроспективно проставляет метки времени строкам у которых метка начинается с '?'.
// Вызывается после первой синхронизации часов с телефоном.
// Использует сохранённый millis() каждой строки для восстановления точного времени.
static void _retroFillTimestamps() {
    _logEnsure();
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
    // Строку собираем ДО спин-блокировки. Внутри неё (прерывания выключены)
    // нельзя ничего, что берёт мьютекс, а time() берёт мьютекс newlib: если в
    // этот момент его держит другая задача, ESP-IDF вызывает abort(). Так и
    // падало колесо в синхронном показе — loop() на другом ядре постоянно
    // читал часы, и первая же строка лога из загрузчика попадала в него.
    char ts[20];
    _fmtDateTime(_currentEpoch(), ts);
    char line[WEB_LOG_LINE];
    snprintf(line, sizeof(line), "%s %s", ts, msg);
    const uint32_t ms = millis();

    // Дедупликация: не записываем если последнее сообщение идентично текущему.
    // Сравниваем только текст без временно́й метки (метка занимает первые 20 символов: "YYYY-MM-DD HH:MM:SS ").
    portENTER_CRITICAL(&_log_mux);
    _logEnsure();
    if (_log_total > 0) {
        const char* last = _log_buf[(_log_head - 1) % WEB_LOG_COUNT];
        const char* last_msg = (strlen(last) > 20) ? last + 20 : last;
        if (strcmp(last_msg, line + 20) == 0) {
            portEXIT_CRITICAL(&_log_mux);
            return;  // Дубликат — не пишем
        }
    }
    uint32_t idx = _log_head % WEB_LOG_COUNT;
    _log_ms[idx] = ms;        // millis() для ретроспективной метки
    memcpy(_log_buf[idx], line, sizeof(line));
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

    // 4. Лог Холла — во флеш, пока ФС ещё смонтирована: после прошивки колесо
    //    перезагрузится, и всё, что накопилось в PSRAM с последней остановки,
    //    пропало бы. Обычно это пусто (колесо стоит, и при остановке лог уже
    //    сброшен); лента к этому моменту погашена — замораживать нечего.
    hallLogFlush();
    // Отложенные настройки, текст и параметры эффектов — тоже: правка, сделанная
    // за секунды до прошивки, иначе не пережила бы её.
    flushPendingSettings();

    // 5. Размонтируем LittleFS — Update сам этого не делает, а запись поверх
    //    смонтированной FS приводит к её повреждению.
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
// шум к готовым данным смысла не имеет.
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


// Фаза анимации от абсолютного момента [originMs] (UTC, мс): кадр, который
// сейчас на ободе, — тот, что шёл бы, начнись анимация ровно тогда. Нужна
// синхронному показу: два колеса грузят один и тот же файл не одинаково долго,
// а кадры у них обязаны совпадать. millis() и системное время идут от одного
// esp_timer, поэтому разность, снятая один раз, дальше не уплывает.
int64_t currentPhaseOriginMs = 0;

bool alignFramePhase(int64_t originMs) {
    int64_t wall = hallWallUs();
    if (originMs <= 0 || wall == 0) return false;
    uint32_t m = millis();
    int64_t elapsed = wall / 1000 - originMs;
    if (elapsed < 0) elapsed = 0;
    lastFrameSwitchTime  = m - (uint32_t)elapsed;   // рендер считает millis() − это, беззнаково
    currentPhaseOriginMs = originMs;
    return true;
}

void loadFrameFromFile(String path, int64_t phaseOriginMs) {
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
    //
    // Ключевое здесь — ДОЖДАТЬСЯ гашения, а не просто попросить о нём. Раньше
    // чтение файла начиналось сразу после сброса render_in_fill, и renderingTask
    // замирал, не успев дойти до blankAllLEDs_DMA(): SK9822 держат последний
    // защёлкнутый кадр сколь угодно долго, и всё время загрузки (у длинной
    // анимации это секунды) на ободе висела застывшая картинка. Будим задачу
    // сами, чтобы она увидела флаг сейчас, а не на следующем событии Холла.
    frame_loading = true;
    wakeRenderingTask();
    for (int i = 0; i < 1000 && render_in_fill;   i++) vTaskDelay(1);
    for (int i = 0; i <  200 && rendering_active; i++) vTaskDelay(1);

    // Эффект и файл — два независимых источника кадра, и живыми одновременно
    // они быть не могут: генератор продолжил бы писать в буфер, который мы
    // сейчас освободим. Лента уже погашена, так что остановка ничего не стоит.
    effectsStop();

    // Имя файла для автозапуска пишем здесь, а не в обработчике OP_PLAY: запись
    // в NVS стирает страницу флеша и на десятки миллисекунд морозит рендер.
    // Теперь она попадает в уже погашенное окно и ничего не портит, а на диск
    // по-прежнему ложится ДО подмены буфера — падение во время загрузки
    // оставит в NVS правильный файл.
    if (pendingPlayFile.length()) {
        if (prefs.getString("last_file", "") != pendingPlayFile) {
            prefs.putString("last_file", pendingPlayFile);
        }
        pendingPlayFile = "";
    }

    uint32_t  t_load        = millis();
    uint8_t*  newBuf        = nullptr;
    uint32_t  newTotalFrames = 1;
    uint16_t  newFrameDelay  = 100;
    uint8_t   newFmt         = FRAME_FMT_565;
    bool      newMirrorBack  = false;   // по умолчанию заднюю сторону не зеркалим

    size_t fileSize = f.size();

    // Формат задаётся magic'ом: "ANI6" — палитра 256 цветов на кадр (основной),
    // "ANI5" — кадры RGB565, "ANIM" — старые RGB888. Статичная картинка старого
    // конвертера заголовка не имеет и различается по размеру файла.
    char magic[4] = {0, 0, 0, 0};
    if (fileSize >= 8) f.read((uint8_t*)magic, 4);
    bool animpal = (memcmp(magic, "ANI6", 4) == 0);
    bool anim565 = (memcmp(magic, "ANI5", 4) == 0);
    bool anim888 = (memcmp(magic, "ANIM", 4) == 0);

    if (animpal || anim565 || anim888) {
        uint16_t hdrCount = 0;
        f.read((uint8_t*)&hdrCount,       2);
        f.read((uint8_t*)&newFrameDelay,  2);

        // Старший бит поля «число кадров» в заголовке ANI6 — флаг «зеркалить
        // заднюю сторону луча». Реальное число кадров упирается в размер
        // раздела LittleFS (13,9 МБ / 16608 ≈ 870 кадров на всё) и в PSRAM
        // (~480), поэтому биты выше 10-го в этом поле физически всегда нули —
        // место под флаг безопасно. Для legacy-форматов (ANI5/ANIM) флага нет,
        // маску всё равно применяем: их счётчик заведомо мал.
        newMirrorBack  = animpal && (hdrCount & 0x8000u);
        newTotalFrames = hdrCount & 0x7FFFu;

        // Палитровый кадр ложится в PSRAM как есть, RGB888 разворачивается в
        // RGB565 — поэтому размер на диске и размер в памяти считаются отдельно.
        size_t srcFrame = animpal ? FRAME_STRIDE_PAL
                                  : (anim888 ? FRAME_SIZE_888 : FRAME_SIZE);
        size_t dstFrame = animpal ? FRAME_STRIDE_PAL : FRAME_SIZE;
        newFmt = animpal ? FRAME_FMT_PAL8 : FRAME_FMT_565;

        // Размер файла обязан быть заголовок + N кадров. Не сходится — файл
        // залит не полностью или со сдвигом, и рендер покажет шум. Сказать об
        // этом в лог дешевле, чем гадать, глядя на обод.
        size_t expect = 8 + (size_t)newTotalFrames * srcFrame;
        if (fileSize != expect) {
            webLogf("[WARN] %s: size %u, header says %u frames (expected %u)",
                    path.c_str(), (unsigned)fileSize,
                    (unsigned)newTotalFrames, (unsigned)expect);
        }

        size_t dataSize = (size_t)newTotalFrames * dstFrame;
        newBuf = (uint8_t*)ps_malloc(dataSize);

        // Не хватило РЯДОМ со старым буфером (пик old+new). Лента уже погашена по
        // frame_loading, показывать нечего — освобождаем прежнюю анимацию и
        // пробуем снова. Один файл в PSRAM помещается всегда, если поместился во
        // флеш (потолок в OP_FSINFO считается именно от полного объёма PSRAM).
        if (!newBuf && frameBuffer != nullptr) {
            free(frameBuffer);
            frameBuffer        = nullptr;
            totalFrames        = 0;
            currentDisplayFile = "";
            newBuf = (uint8_t*)ps_malloc(dataSize);
            webLogf("[DISP] Dropped previous buffer to fit %u KB", (unsigned)(dataSize / 1024));
        }

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
            // раз в кадр (~32 КБ): сплошное чтение мегабайтами держало бы задачу
            // хоста NimBLE без CPU и роняло соединение.
            uint32_t got = 0;
            for (; got < newTotalFrames; got++) {
                uint8_t* dst = newBuf + (size_t)got * dstFrame;
                bool ok;
                if (anim888) {
                    ok = (f.read(tmp, FRAME_SIZE_888) == (int)FRAME_SIZE_888);
                    if (ok) frame888to565(tmp, dst);
                } else {
                    ok = (f.read(dst, dstFrame) == (int)dstFrame);
                }
                if (!ok) break;
                vTaskDelay(1);
            }
            // Файл оказался короче заявленного числа кадров — хвост должен
            // быть чёрным, а не мусором из PSRAM. Для палитрового формата ноль
            // тоже безопасен: индекс 0 в обнулённой палитре — чёрный.
            if (got < newTotalFrames) {
                size_t done = (size_t)got * dstFrame;
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
    // адресует кадр как frameBuffer + frame_idx·шаг, где frame_idx считается по
    // totalFrames, а сам шаг — по frame_fmt. Если хоть одно из трёх полей
    // окажется выставлено раньше нового буфера, рендер уедет за пределы старого
    // — на ободе это блочный мусор из PSRAM, тем заметнее, чем тяжелее новая
    // анимация. Отрисовка уже стоит по frame_loading, так что гонки здесь нет.
    uint8_t* oldBuf = frameBuffer;  // Запоминаем старый указатель для free()

    // Устанавливаем новые параметры и буфер
    totalFrames       = newTotalFrames;
    frameDelay        = newFrameDelay;
    currentFrameIndex = 0;
    frame_fmt         = newFmt;
    mirror_back_face  = newMirrorBack;   // публикуется в том же погашенном окне
    frameBuffer       = newBuf;
    // Развёрнутая палитра относится к прежнему буферу: номер кадра после
    // загрузки снова 0, и без этого рендер принял бы старый разворот за свой.
    palette_gen++;

    if (oldBuf != nullptr) free(oldBuf);

    // Запускаем таймер кадров только ПОСЛЕ завершения чтения файла:
    // если поставить в начало, первый кадр будет немедленно пропущен в renderingTask.
    // В синхронном показе — от начала слота, а не от конца чтения (см. выше).
    if (!alignFramePhase(phaseOriginMs)) {
        lastFrameSwitchTime  = millis();
        currentPhaseOriginMs = 0;
    }
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

// Освобождает буфер кадра, ничего не загружая взамен. Нужен, когда файл,
// который сейчас показывается, только что удалили с флеша (см.
// handleFileDeleted() в main.cpp): перезалить нечего, а держать в PSRAM
// декодированную копию удалённого файла незачем. Тот же гасим-и-ждём, что и
// в loadFrameFromFile(), но без чтения.
void unloadCurrentFrame() {
    frame_loading = true;
    wakeRenderingTask();
    for (int i = 0; i < 1000 && render_in_fill;   i++) vTaskDelay(1);
    for (int i = 0; i <  200 && rendering_active; i++) vTaskDelay(1);

    effectsStop();

    if (frameBuffer != nullptr) { free(frameBuffer); frameBuffer = nullptr; }
    totalFrames        = 0;
    currentDisplayFile = "";
    currentPhaseOriginMs = 0;
    newFrameReady      = false;
    force_stop_display = true;
    frame_loading      = false;
}

// =====================================================================
//  Мостики для BLE: часы и лог
//
//  Кольцо лога и база времени — static в этом файле и обязаны такими остаться:
//  они лежат в RTC-памяти и переживают сон, а второй точки записи у них быть
//  не должно. Поэтому BLE не лезет к ним напрямую, а зовёт эти две функции.
// =====================================================================

// Грубая установка часов по секундам — OP_SETTIME.
// Часы, выставленные точно (OP_TIME_SET), она не сбивает: расхождение меньше
// двух секунд — это округление до секунды на той стороне, а не ошибка часов,
// и переставлять их значило бы испортить привязку лога Холла к времени
// (см. hall_log.h) ради точности, которой у этого источника нет.
static void _setClockSeconds(uint32_t epoch) {
    if (epoch < TIME_VALID_FROM) return;
    int64_t now = hallWallUs();
    if (hallClockPrecise() && now != 0) {
        int64_t d = (int64_t)epoch * 1000000LL - now;
        if (d > -2000000LL && d < 2000000LL) return;
    }
    // Системные часы newlib: дальше их держит счётчик RTC, и время
    // переживёт и глубокий сон, и перезагрузку по OTA.
    struct timeval tv = { .tv_sec = (time_t)epoch, .tv_usec = 0 };
    settimeofday(&tv, nullptr);
    // Эта пара нужна только ретроспективной простановке меток лога —
    // она считает от millis() текущей сессии.
    _time_epoch_base  = epoch;
    _time_millis_base = millis();
    hallClockSet(false);
}

static void _setTimezone(int32_t tz) {
    // Пояс правится независимо от часов: приложение шлёт его и тогда, когда
    // время уже верное, а пользователь пересёк границу поясов.
    if (tz >= -50400 && tz <= 50400 && tz != _time_tz_offset) {
        _time_tz_offset = tz;
        // Пишем в лог: перепутанный пояс иначе никак не отличить от
        // неверно идущих часов — на экране и то и другое выглядит
        // одинаково, просто время «не то».
        webLogf("[SYS] Timezone set to UTC%+.1f h", (double)tz / 3600.0);
    }
}

// Синхронизация часов с телефона по секундам (OP_SETTIME).
void povSetTime(uint32_t epoch, int32_t tz) {
    _setClockSeconds(epoch);
    _setTimezone(tz);
    // Строки, записанные до синхронизации, получают наконец настоящие метки.
    portENTER_CRITICAL(&_log_mux);
    _retroFillTimestamps();
    portEXIT_CRITICAL(&_log_mux);
}

// Точная установка (OP_TIME_SET): телефон сам вычислил время UTC с
// точностью до миллисекунд по пингам OP_TIME.
void povSetTimeUs(int64_t wall_us, int32_t tz) {
    if (wall_us >= (int64_t)TIME_VALID_FROM * 1000000LL) {
        struct timeval tv;
        tv.tv_sec  = (time_t)(wall_us / 1000000LL);
        tv.tv_usec = (suseconds_t)(wall_us % 1000000LL);
        settimeofday(&tv, nullptr);
        _time_epoch_base  = (uint32_t)tv.tv_sec;
        _time_millis_base = millis();
        hallClockSet(true);
    }
    _setTimezone(tz);
    portENTER_CRITICAL(&_log_mux);
    _retroFillTimestamps();
    portEXIT_CRITICAL(&_log_mux);
}

// Лог для приложения: [u32 next][строки, разделённые \n].
// Без JSON: экранировать нечего, а каждый лишний байт
// в ответе BLE стоит round-trip'а. Возвращает длину готового ответа.
uint32_t povBuildLogs(uint8_t* out, size_t cap, uint32_t since) {
    if (!out || cap < 8) return 0;

    portENTER_CRITICAL(&_log_mux);
    _logEnsure();
    uint32_t total = _log_total;
    portEXIT_CRITICAL(&_log_mux);

    // Кольцо на WEB_LOG_COUNT строк: всё, что старше, уже затёрто. Клиент,
    // помолчавший дольше, получит не дырку в нумерации, а последние строки —
    // и заново синхронизируется по возвращённому next.
    uint32_t from = since;
    if (from > total) from = 0;                                  // счётчик уехал назад: перезагрузка
    if (total - from > WEB_LOG_COUNT) from = total - WEB_LOG_COUNT;

    size_t pos = 4;
    for (uint32_t i = from; i < total; i++) {
        const char* ln = _log_buf[i % WEB_LOG_COUNT];
        size_t      n  = strnlen(ln, WEB_LOG_LINE);
        if (pos + n + 1 > cap) {
            // Не влезло — отдаём то, что собрали, и говорим, на чём встали:
            // остаток приедет следующим запросом, а не потеряется.
            total = i;
            break;
        }
        memcpy(out + pos, ln, n);
        pos += n;
        out[pos++] = '\n';
    }
    memcpy(out, &total, 4);
    return (uint32_t)pos;
}

// =====================================================================
//  NVS. Вызывается безусловно в начале setup(): Preferences без begin() не
//  падает, а молча отдаёт значения по умолчанию — и настройки, калибровка
//  датчиков и имя последнего файла тихо откатились бы на заводские.
// =====================================================================
void setupStorage() {
    prefs.begin("pov_config", false);
}

// =====================================================================
//  Паника: что и где упало
//
//  Штатный обработчик паники ESP-IDF печатает причину и цепочку вызовов в
//  консоль, а консоли у колеса нет (Serial не поднят, USB на ходу не
//  подключён) — после сброса оставалось только «panic/crash». Перехватываем
//  его (-Wl,--wrap=esp_panic_handler в platformio.ini): до штатной печати
//  запоминаем причину, адрес и цепочку вызовов в RTC_NOINIT — её не трогает
//  ни один сброс, кроме включения питания, — а после перезагрузки выводим в
//  лог (crashReportLog()). Адреса — для addr2line по firmware.elf той же сборки.
// =====================================================================
#include "esp_private/panic_internal.h"
#include "esp_debug_helpers.h"
#include "xtensa/xtensa_context.h"

#define CRASH_MAGIC 0x43525348u   // "CRSH"
#define CRASH_DEPTH 12
struct CrashRec {
    uint32_t    magic;
    uint32_t    core;
    uint32_t    exccause;
    uint32_t    excvaddr;
    const char* reason;
    uint32_t    n;
    uint32_t    pc[CRASH_DEPTH];
};
RTC_NOINIT_ATTR static CrashRec crash_rec;

extern "C" void __real_esp_panic_handler(panic_info_t* info);

extern "C" void IRAM_ATTR __wrap_esp_panic_handler(panic_info_t* info) {
    crash_rec.magic    = 0;
    crash_rec.core     = (uint32_t)info->core;
    crash_rec.reason   = info->reason;
    crash_rec.exccause = 0;
    crash_rec.excvaddr = 0;
    crash_rec.n        = 0;
    const XtExcFrame* f = (const XtExcFrame*)info->frame;
    if (f) {
        crash_rec.exccause = (uint32_t)f->exccause;
        crash_rec.excvaddr = (uint32_t)f->excvaddr;
        esp_backtrace_frame_t fr;
        fr.pc        = (uint32_t)f->pc;
        fr.sp        = (uint32_t)f->a1;
        fr.next_pc   = (uint32_t)f->a0;
        fr.exc_frame = f;
        crash_rec.pc[crash_rec.n++] = fr.pc;
        while (crash_rec.n < CRASH_DEPTH && fr.next_pc != 0 && esp_backtrace_get_next_frame(&fr)) {
            crash_rec.pc[crash_rec.n++] = fr.pc;
        }
    }
    crash_rec.magic = CRASH_MAGIC;
    __real_esp_panic_handler(info);
}

// Адрес из цепочки — так же, как его печатает сам ESP-IDF
// (esp_cpu_process_stack_pc): старшие биты — счётчик окна, минус 3 — сама
// инструкция вызова, а не адрес возврата.
static uint32_t crashPc(uint32_t pc) {
    if (pc & 0x80000000u) pc = (pc & 0x3fffffffu) | 0x40000000u;
    return pc - 3;
}

void crashReportLog() {
    if (crash_rec.magic != CRASH_MAGIC) return;
    crash_rec.magic = 0;
    // Строка причины лежит во флеше прошивки (DROM) — читать её можно, только
    // если указатель туда и смотрит.
    uint32_t r = (uint32_t)crash_rec.reason;
    const char* reason = (r >= 0x3C000000u && r < 0x3E000000u) ? crash_rec.reason : "?";
    webLogf("[SYS] Panic core %u: %.40s, cause %u, vaddr 0x%08x", (unsigned)crash_rec.core, reason,
            (unsigned)crash_rec.exccause, (unsigned)crash_rec.excvaddr);
    uint32_t n = crash_rec.n > CRASH_DEPTH ? CRASH_DEPTH : crash_rec.n;
    for (uint32_t i = 0; i < n; i += 4) {
        char line[64];
        int  p = snprintf(line, sizeof(line), "[SYS] BT%u:", (unsigned)i);
        for (uint32_t k = i; k < n && k < i + 4; k++)
            p += snprintf(line + p, sizeof(line) - p, " %08x", (unsigned)(k ? crashPc(crash_rec.pc[k]) : crash_rec.pc[k]));
        webLog(line);
    }
}

