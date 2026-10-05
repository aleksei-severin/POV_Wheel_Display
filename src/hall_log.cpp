// =====================================================================
//  Лог событий Холла. Зачем и как устроен — в include/hall_log.h.
// =====================================================================

#include "config.h"
#include "hall_log.h"
#include <LittleFS.h>
#include <esp_timer.h>
#include <esp_system.h>
#include <sys/time.h>

// Системные часы считаются заведёнными с начала 2023 года — тот же порог,
// что у часов в network.cpp.
#define HLOG_TIME_VALID_US  (1700000000LL * 1000000LL)

#define HLOG_NCP  (HLOG_CAP / HLOG_CP_EVERY)
static_assert((HLOG_CAP & (HLOG_CAP - 1)) == 0, "HLOG_CAP — степень двойки");
static_assert((HLOG_CP_EVERY & (HLOG_CP_EVERY - 1)) == 0, "HLOG_CP_EVERY — степень двойки");

// --- Часы: переживают сон, поэтому в RTC ---
RTC_DATA_ATTR static uint64_t rtc_sleep_us    = 0;   // проспано с последней установки часов
RTC_DATA_ATTR static int64_t  rtc_sleep_enter = 0;   // системные часы при засыпании, 0 — не спали
RTC_DATA_ATTR static uint32_t rtc_clock_gen   = 0;
RTC_DATA_ATTR static uint8_t  rtc_clock_prec  = 0;

// --- Кольцо ---
static uint32_t* hl_ring = nullptr;
static int64_t*  hl_cp   = nullptr;          // время ДО записи с номером k·HLOG_CP_EVERY
static volatile uint32_t hl_head = 0;        // номер следующей записи
static int64_t   hl_last_t = 0;              // время последнего события
static uint32_t  hl_boot   = 0;
static portMUX_TYPE hl_mux = portMUX_INITIALIZER_UNLOCKED;

// Что уже во флеше: номер первой несброшенной записи и время до неё.
static uint32_t  hl_flushed   = 0;
static int64_t   hl_flushed_t = 0;

int64_t hallWallUs() {
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    int64_t us = (int64_t)tv.tv_sec * 1000000LL + (int64_t)tv.tv_usec;
    return us >= HLOG_TIME_VALID_US ? us : 0;
}

void hallLogNoteWake() {
    if (rtc_sleep_enter == 0) return;
    int64_t now = hallWallUs();
    if (now > rtc_sleep_enter) rtc_sleep_us += (uint64_t)(now - rtc_sleep_enter);
    rtc_sleep_enter = 0;
}

void hallLogNoteSleep() {
    // Сон без пробуждения через setup() (повторное засыпание прямо из него)
    // тоже должен попасть в счёт — досчитываем, прежде чем ставить новую метку.
    hallLogNoteWake();
    rtc_sleep_enter = hallWallUs();
}

void hallClockSet(bool precise) {
    rtc_clock_gen++;
    rtc_clock_prec  = precise ? 1 : 0;
    rtc_sleep_us    = 0;
    rtc_sleep_enter = 0;
}
uint32_t hallClockGen()     { return rtc_clock_gen; }
bool     hallClockPrecise() { return rtc_clock_prec != 0; }
uint64_t hallSleepUs()      { return rtc_sleep_us; }
uint32_t hallLogBootId()    { return hl_boot; }
uint32_t hallLogHead()      { return hl_head; }

void hallLogInit() {
    if (hl_ring) return;
    hl_ring = (uint32_t*)ps_malloc(HLOG_CAP * sizeof(uint32_t));
    hl_cp   = (int64_t*)ps_malloc(HLOG_NCP * sizeof(int64_t));
    if (!hl_ring || !hl_cp) {
        if (hl_ring) { free(hl_ring); hl_ring = nullptr; }
        if (hl_cp)   { free(hl_cp);   hl_cp   = nullptr; }
        webLog("[HLOG] PSRAM alloc failed, Hall log disabled");
        return;
    }
    hl_boot = esp_random() | 1u;            // 0 оставляем под «нет сессии»
    hl_last_t    = esp_timer_get_time();
    hl_cp[0]     = hl_last_t;
    hl_head      = 0;
    hl_flushed   = 0;
    hl_flushed_t = hl_last_t;
    webLogf("[HLOG] Ring %u events (%u kB), session %08lx",
            (unsigned)HLOG_CAP, (unsigned)(HLOG_PSRAM_BYTES / 1024), (unsigned long)hl_boot);
}

// Запись в кольцо. Вызывается под hl_mux.
static inline IRAM_ATTR void hlPutRaw(uint32_t e) {
    uint32_t s = hl_head;
    if ((s & (HLOG_CP_EVERY - 1)) == 0) hl_cp[(s / HLOG_CP_EVERY) & (HLOG_NCP - 1)] = hl_last_t;
    hl_ring[s & (HLOG_CAP - 1)] = e;
    hl_head = s + 1;
}

static inline IRAM_ATTR void hlPush(int64_t t, uint8_t type, uint8_t arg) {
    int64_t dt = t - hl_last_t;
    if (dt < 0) dt = 0;
    if (dt > 0xFFFFFF) {
        // Пара EXT + событие не должна разрываться контрольной точкой: читатель,
        // начавший с неё, потерял бы старшие биты паузы.
        if (((hl_head + 1) & (HLOG_CP_EVERY - 1)) == 0)
            hlPutRaw(((uint32_t)HLOG_NOP << 28));
        hlPutRaw(((uint32_t)HLOG_EXT << 28) | (uint32_t)((dt >> 24) & 0xFFFFFF));
    }
    hlPutRaw(((uint32_t)type << 28) | ((uint32_t)(arg & 0x0F) << 24) | (uint32_t)(dt & 0xFFFFFF));
    hl_last_t = t;
}

void IRAM_ATTR hallLogIsr(int64_t t_us, uint8_t sensor) {
    if (!hl_ring) return;
    portENTER_CRITICAL_ISR(&hl_mux);
    hlPush(t_us, HLOG_HALL, sensor);
    portEXIT_CRITICAL_ISR(&hl_mux);
}

void hallLogMark(uint8_t type, uint8_t arg) {
    if (!hl_ring) return;
    int64_t t = esp_timer_get_time();
    portENTER_CRITICAL(&hl_mux);
    hlPush(t, type, arg);
    portEXIT_CRITICAL(&hl_mux);
}

// Самая старая запись, которую ещё безопасно читать: кольцо могло
// переписать начало, и пока мы копируем, писатель уходит дальше — поэтому
// запас в две контрольные точки, и начало — ровно на контрольной точке.
static uint32_t hlOldestSafe(uint32_t head) {
    if (head <= HLOG_CAP) return 0;
    uint32_t o = head - HLOG_CAP + 2 * HLOG_CP_EVERY;
    return (o + HLOG_CP_EVERY - 1) & ~(HLOG_CP_EVERY - 1);
}

size_t hallLogRead(uint32_t from, uint32_t* dst, size_t max,
                   uint32_t* seq0, int64_t* t0, uint32_t* oldest, uint32_t* head) {
    *seq0 = 0; *t0 = 0; *oldest = 0; *head = 0;
    if (!hl_ring) return 0;
    portENTER_CRITICAL(&hl_mux);
    uint32_t h = hl_head;
    portEXIT_CRITICAL(&hl_mux);
    uint32_t old = hlOldestSafe(h);
    uint32_t s = from & ~(HLOG_CP_EVERY - 1);
    if (s < old) s = old;
    *oldest = old;
    *head   = h;
    if (s >= h) { *seq0 = h; return 0; }
    uint32_t n = h - s;
    if (n > max) n = (uint32_t)max;
    // Копируем кусками: под замком ISR Холла ждёт, а его метка времени снята
    // ещё до замка — задержка точность не портит, но держать замок долго
    // всё равно незачем.
    portENTER_CRITICAL(&hl_mux);
    *t0 = hl_cp[(s / HLOG_CP_EVERY) & (HLOG_NCP - 1)];
    portEXIT_CRITICAL(&hl_mux);
    uint32_t done = 0;
    while (done < n) {
        uint32_t k = n - done;
        if (k > 256) k = 256;
        portENTER_CRITICAL(&hl_mux);
        for (uint32_t i = 0; i < k; i++) dst[done + i] = hl_ring[(s + done + i) & (HLOG_CAP - 1)];
        portEXIT_CRITICAL(&hl_mux);
        done += k;
    }
    *seq0 = s;
    return n;
}

// Первый блок файла — по нему телефон узнаёт, тот ли это файл.
static bool hlFileKey(const char* path, uint32_t* size, uint32_t* key_boot, uint32_t* key_seq) {
    *size = 0; *key_boot = 0; *key_seq = 0;
    if (!LittleFS.exists(path)) return false;
    File f = LittleFS.open(path, "r");
    if (!f) return false;
    *size = f.size();
    HallBlockHdr h;
    if (f.read((uint8_t*)&h, sizeof(h)) == sizeof(h) && h.magic == HLOG_BLOCK_MAGIC) {
        *key_boot = h.boot_id;
        *key_seq  = h.first_seq;
    }
    f.close();
    return true;
}

void hallLogFlush() {
    if (!hl_ring) return;
    portENTER_CRITICAL(&hl_mux);
    uint32_t h  = hl_head;
    int64_t  th = hl_last_t;      // время до записи h: head никогда не стоит внутри пары EXT
    portEXIT_CRITICAL(&hl_mux);
    if (h == hl_flushed) return;

    uint32_t s  = hl_flushed;
    int64_t  ts = hl_flushed_t;
    uint32_t old = hlOldestSafe(h);
    if (s < old) {
        // Кольцо успело переписать то, что не сбросили: колесо крутилось дольше,
        // чем вмещает PSRAM. Сбрасываем то, что осталось.
        webLogf("[HLOG] Ring overrun: %lu events lost before flush", (unsigned long)(old - s));
        s  = old;
        portENTER_CRITICAL(&hl_mux);
        ts = hl_cp[(s / HLOG_CP_EVERY) & (HLOG_NCP - 1)];
        portEXIT_CRITICAL(&hl_mux);
    }
    uint32_t n = h - s;

    HallBlockHdr hdr;
    memset(&hdr, 0, sizeof(hdr));
    hdr.magic     = HLOG_BLOCK_MAGIC;
    hdr.boot_id   = hl_boot;
    hdr.first_seq = s;
    hdr.count     = n;
    hdr.t_first   = ts;
    hdr.ref_esp   = esp_timer_get_time();
    hdr.ref_wall  = hallWallUs();
    hdr.sleep_us  = rtc_sleep_us;
    hdr.clock_gen = rtc_clock_gen;
    hallGetCal(hdr.cal_x100, &hdr.arm_reverse);
    hdr.flags     = rtc_clock_prec ? 1 : 0;

    // Ротация: текущий файл не растёт выше HLOG_FILE_MAX.
    uint32_t cur = 0, kb, ks;
    hlFileKey(HLOG_FILE, &cur, &kb, &ks);
    if (cur > 0 && cur + sizeof(hdr) + n * 4 > HLOG_FILE_MAX) {
        LittleFS.remove(HLOG_FILE_OLD);
        LittleFS.rename(HLOG_FILE, HLOG_FILE_OLD);
    }
    File f = LittleFS.open(HLOG_FILE, "a");
    if (!f) { webLog("[HLOG] Cannot open " HLOG_FILE); return; }
    bool ok = f.write((const uint8_t*)&hdr, sizeof(hdr)) == sizeof(hdr);
    // Записи — через буфер во внутренней ОЗУ, кусками по 4 кБ с паузой: так
    // же, как пишет заливка по BLE (см. WRITE_CHUNK_MAX в povble.cpp).
    static uint32_t buf[1024];
    uint32_t done = 0;
    while (ok && done < n) {
        uint32_t k = n - done;
        if (k > 1024) k = 1024;
        portENTER_CRITICAL(&hl_mux);
        for (uint32_t i = 0; i < k; i++) buf[i] = hl_ring[(s + done + i) & (HLOG_CAP - 1)];
        portEXIT_CRITICAL(&hl_mux);
        ok = f.write((const uint8_t*)buf, k * 4) == k * 4;
        done += k;
        vTaskDelay(1);
    }
    f.close();
    if (!ok) { webLog("[HLOG] Flush write failed (flash full?)"); return; }
    hl_flushed   = h;
    hl_flushed_t = th;
    webLogf("[HLOG] Flushed %lu events", (unsigned long)n);
}

size_t hallLogHistRead(uint8_t which, uint32_t off, uint8_t* dst, size_t max,
                       uint32_t* size, uint32_t* key_boot, uint32_t* key_seq) {
    const char* path = which ? HLOG_FILE : HLOG_FILE_OLD;
    if (!hlFileKey(path, size, key_boot, key_seq)) return 0;
    if (off >= *size) return 0;
    File f = LittleFS.open(path, "r");
    if (!f) return 0;
    f.seek(off);
    size_t n = f.read(dst, max);
    f.close();
    return n;
}
