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
// что у часов в storage.cpp.
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
static int64_t   hl_last_t = 0;              // время последнего события в записанных записях
static uint32_t  hl_boot   = 0;
static portMUX_TYPE hl_mux = portMUX_INITIALIZER_UNLOCKED;

// --- Упаковка (см. «УПАКОВКА» в hall_log.h). Всё — под hl_mux. ---
#define PK_HIST 7                              // K = 6 требует шага по 7 датчикам
static uint8_t  pk_sens[PK_HIST];              // датчики последних событий, [0] — самое свежее
static uint32_t pk_gap[PK_HIST];               // их Δt (восстановленные), мкс
static uint8_t  pk_n    = 0;                   // сколько событий в истории
static int16_t  pk_q[4];                       // поправки ожидающей записи
static uint8_t  pk_cnt  = 0;                   // сколько их
static uint8_t  pk_wide = 0;                   // ожидающая запись — HLOG_P2, а не HLOG_P4
static int64_t  pk_t    = 0;                   // время последнего события, включая ожидающие

// Что уже во флеше: номер первой несброшенной записи и время до неё.
static uint32_t  hl_flushed   = 0;
static int64_t   hl_flushed_t = 0;
// Сколько сейчас занимают /hall.log и /hall.old — для резерва LittleFS.
static volatile uint32_t hl_fs_bytes = 0;

static uint32_t hlFileSize(const char* path) {
    if (!LittleFS.exists(path)) return 0;
    File f = LittleFS.open(path, "r");
    if (!f) return 0;
    uint32_t n = f.size();
    f.close();
    return n;
}

static void hlUpdateFsBytes() {
    hl_fs_bytes = hlFileSize(HLOG_FILE) + hlFileSize(HLOG_FILE_OLD);
}

uint32_t hallLogFsReserveLeft() {
    uint32_t used = hl_fs_bytes;
    return used >= HLOG_FS_RESERVE ? 0 : HLOG_FS_RESERVE - used;
}

size_t fsFreeForAnimations() {
    size_t total = LittleFS.totalBytes(), used = LittleFS.usedBytes();
    size_t freeb = total > used ? total - used : 0;
    size_t res   = hallLogFsReserveLeft();
    return freeb > res ? freeb - res : 0;
}

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
    hlUpdateFsBytes();                      // LittleFS к этому моменту смонтирована
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
    pk_t         = hl_last_t;
    pk_n = pk_cnt = 0;
    hl_cp[0]     = hl_last_t;
    hl_head      = 0;
    hl_flushed   = 0;
    hl_flushed_t = hl_last_t;
    webLogf("[HLOG] Ring %u records (%u kB), session %08lx",
            (unsigned)HLOG_CAP, (unsigned)(HLOG_PSRAM_BYTES / 1024), (unsigned long)hl_boot);
}

// Запись в кольцо. Вызывается под hl_mux. Запись на контрольной точке начинает
// новый отрезок: читатель может начать с неё, не зная ничего раньше, поэтому
// предсказание упаковки забывает всё, что было до неё.
static inline IRAM_ATTR void hlPutRaw(uint32_t e) {
    uint32_t s = hl_head;
    if ((s & (HLOG_CP_EVERY - 1)) == 0) {
        hl_cp[(s / HLOG_CP_EVERY) & (HLOG_NCP - 1)] = hl_last_t;
        pk_n = 0;
    }
    hl_ring[s & (HLOG_CAP - 1)] = e;
    hl_head = s + 1;
}

// Дописать ожидающую упакованную запись (незанятые слоты — минимальным значением).
static inline IRAM_ATTR void hlCommit() {
    if (!pk_cnt) return;
    uint32_t e;
    if (pk_wide) {
        e = ((uint32_t)HLOG_P2 << 28) | (((uint32_t)pk_q[0] & 0x3FFF) << 14) |
            ((uint32_t)(pk_cnt > 1 ? pk_q[1] : -8192) & 0x3FFF);
    } else {
        e = (uint32_t)HLOG_P4 << 28;
        for (uint8_t i = 0; i < 4; i++)
            e |= ((uint32_t)(i < pk_cnt ? pk_q[i] : -64) & 0x7F) << (21 - 7 * i);
    }
    hlPutRaw(e);
    hl_last_t = pk_t;
    pk_cnt = 0;
}

static inline IRAM_ATTR void hlRemember(uint8_t sensor, uint32_t gap) {
    for (uint8_t i = PK_HIST - 1; i > 0; i--) { pk_sens[i] = pk_sens[i - 1]; pk_gap[i] = pk_gap[i - 1]; }
    pk_sens[0] = sensor;
    pk_gap[0]  = gap;
    if (pk_n < PK_HIST) pk_n++;
}

// Поправку — в ожидающую запись. false — ставить некуда: новую упакованную
// запись пришлось бы начать на контрольной точке, а там читателю не на что
// опереться. Тогда событие идёт полной записью. Заполненную запись дописывает
// вызывающий — после того как учтёт время события (hlCommit берёт его из pk_t).
static inline IRAM_ATTR bool hlPlace(int32_t q) {
    bool small = q >= -63 && q <= 63;
    if (pk_cnt && !pk_wide && !small) {
        if (pk_cnt == 1) pk_wide = 1;          // одна мелкая влезает и в P2
        else hlCommit();                       // две-три мелких — своей записью P4
    }
    if (!pk_cnt) {
        if ((hl_head & (HLOG_CP_EVERY - 1)) == 0) return false;
        pk_wide = small ? 0 : 1;
    }
    pk_q[pk_cnt++] = (int16_t)q;
    return true;
}

// Полная запись события (с EXT, если пауза длиннее 24 бит). Возвращает Δt.
static inline IRAM_ATTR uint32_t hlFull(int64_t t, uint8_t type, uint8_t arg) {
    hlCommit();
    int64_t dt = t - pk_t;
    if (dt < 0) dt = 0;
    if (dt > 0xFFFFFF) {
        // Пара EXT + событие не должна разрываться контрольной точкой: читатель,
        // начавший с неё, потерял бы старшие биты паузы.
        if (((hl_head + 1) & (HLOG_CP_EVERY - 1)) == 0)
            hlPutRaw(((uint32_t)HLOG_NOP << 28));
        hlPutRaw(((uint32_t)HLOG_EXT << 28) | (uint32_t)((dt >> 24) & 0xFFFFFF));
    }
    hlPutRaw(((uint32_t)type << 28) | ((uint32_t)(arg & 0x0F) << 24) | (uint32_t)(dt & 0xFFFFFF));
    hl_last_t = pk_t = t;
    return dt > 0xFFFFFFFFLL ? 0xFFFFFFFFu : (uint32_t)dt;
}

// Событие Холла: упаковать, если оно продолжает ровный ход, иначе — полной записью.
static inline IRAM_ATTR void hlHall(int64_t t, uint8_t sensor) {
    int64_t dt = t - pk_t;
    if (pk_n >= 2 && dt > 0 && dt <= 0xFFFFFF) {
        uint8_t step = (uint8_t)((pk_sens[0] + 6 - pk_sens[1]) % 6);
        uint8_t K = step ? 6 : 1;
        // Тот же шаг у нового события и у K предыдущих: иначе промежуток K событий
        // назад — между другой парой датчиков (пропуск магнита, смена направления).
        bool ok = pk_n >= K + 1 && (uint8_t)((sensor + 6 - pk_sens[0]) % 6) == step;
        for (uint8_t i = 1; ok && i < K; i++)
            ok = (uint8_t)((pk_sens[i] + 6 - pk_sens[i + 1]) % 6) == step;
        if (ok && pk_gap[K - 1] <= 0xFFFFFF) {
            int32_t pred = (int32_t)pk_gap[K - 1];
            int32_t d = (int32_t)dt - pred;
            int32_t q = d >= 0 ?  (d + HLOG_PACK_US / 2) / HLOG_PACK_US
                               : -((-d + HLOG_PACK_US / 2) / HLOG_PACK_US);
            int32_t g = pred + q * HLOG_PACK_US;
            if (g > 0 && q >= -8191 && q <= 8191 && hlPlace(q)) {
                pk_t += g;
                hlRemember(sensor, (uint32_t)g);
                if (pk_cnt == (pk_wide ? 2 : 4)) hlCommit();
                return;
            }
        }
    }
    uint32_t g = hlFull(t, HLOG_HALL, sensor);
    hlRemember(sensor, g);
}

void IRAM_ATTR hallLogIsr(int64_t t_us, uint8_t sensor) {
    if (!hl_ring) return;
    portENTER_CRITICAL_ISR(&hl_mux);
    hlHall(t_us, sensor);
    portEXIT_CRITICAL_ISR(&hl_mux);
}

void hallLogMark(uint8_t type, uint8_t arg) {
    if (!hl_ring) return;
    int64_t t = esp_timer_get_time();
    portENTER_CRITICAL(&hl_mux);
    hlFull(t, type, arg);
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
    hlCommit();                   // телефону — всё до последнего события, без недобранной записи
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
    hlCommit();
    // Следующий блок начнётся с записи h — предсказание упаковки не должно
    // опираться на то, что в него не попадёт.
    pk_n = 0;
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
    hlUpdateFsBytes();
    if (!ok) { webLog("[HLOG] Flush write failed (flash full?)"); return; }
    hl_flushed   = h;
    hl_flushed_t = th;
    webLogf("[HLOG] Flushed %lu records", (unsigned long)n);
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
