#include "beeper.h"
#include "config.h"
#include <math.h>
#include "driver/gpio.h"
#include "driver/rmt.h"
#include "hal/rmt_ll.h"
#include "soc/gpio_reg.h"
#include "soc/gpio_periph.h"
#include "soc/io_mux_reg.h"
#include "esp_timer.h"

// Каналы RMT: A → IO1 (положительные полупериоды), B → IO2 (отрицательные).
// Каждому по два блока памяти (96 слов): канал 0 занимает блоки 0–1, канал 2 —
// блоки 2–3, поэтому каналы 1 и 3 заняты вместе с ними. На S3 DMA есть только
// у TX-канала 3, а синхронных каналов нужно два — поэтому дозаливка по
// прерыванию порога, а не DMA.
#define CH_A        0
#define CH_B        2
#define MEM_WORDS   (2 * SOC_RMT_MEM_WORDS_PER_CHANNEL)   // 96 слов на канал
#define HALF_WORDS  (MEM_WORDS / 2)                       // доливаем половинами
#define WAVE_MAX    300        // слов на канал и направление; чирп 15→20 кГц × 15 мс ≈ 265
#define DUR_MAX     32767      // 15 бит длительности в слове RMT
#define PULSE_MIN_S 0.25e-6    // импульс короче — не выдаём: амплитуда <2 %, а фронт всё равно не успеет

// Самопроверка: импульсы уже этого считаем «крупными» — их ширину выборка
// GPIO_IN (~1.1 мкс) видит надёжно; по центрам первых и последних NQ из них
// меряем частоту в начале и в конце свипа.
#define ST_MIN_W_US 2
#define NQ          30

// Таблицы формы: [направление: 0 — вверх, 1 — вниз][канал A/B]. Лежат во
// внутренней DRAM: прерывание RMT читает их и при выключенном кеше флеша.
static uint32_t wave[2][2][WAVE_MAX];
static uint16_t wave_len[2][2];

// Ожидаемые значения для самопроверки, считаются вместе с таблицами.
struct WaveStats {
    uint16_t n_all[2];    // импульсов на каналах A/B
    uint16_t n_big[2];    // из них шире ST_MIN_W_US
    double   f_start;     // частота по центрам первых NQ крупных импульсов A, Гц
    double   f_end;       // и последних NQ
    double   t_end_us;    // спад последнего импульса, мкс от старта
    double   ab_mid_us;   // центр B минус центр предыдущего A в середине чирпа
};
static WaveStats wave_stats[2];

// Состояние проигрывания. Трогают ISR Холла и прерывание RMT — оба на ядре 1,
// но под общей блокировкой: прерывание RMT может оказаться выше уровнем.
static portMUX_TYPE ch_mux    = portMUX_INITIALIZER_UNLOCKED;
static bool         ch_ready  = false;   // RMT настроен, таблицы готовы
static bool         ch_busy   = false;   // чирп звучит
static bool         ch_loaded = false;   // в памяти RMT лежит начало чирпа ch_dir
static uint8_t      ch_dir    = 0;
static uint8_t      ch_ended  = 0;       // биты A/B: канал дошёл до маркера конца
static uint16_t     ch_pos[2];           // следующее слово таблицы для дозаливки
static uint8_t      ch_half[2];          // какую половину памяти доливать следующей

// always_inline: зовётся из IRAM-прерывания, а не встроенная копия легла бы во флеш.
__attribute__((always_inline))
static inline uint32_t chOf(int i) { return i ? CH_B : CH_A; }

static inline uint32_t rmtWord(uint32_t lv0, uint32_t d0, uint32_t lv1, uint32_t d1) {
    return (d0 & 0x7FFF) | (lv0 << 15) | ((d1 & 0x7FFF) << 16) | (lv1 << 31);
}

// =====================================================================
//  Проигрывание (всё в IRAM: работает и пока кеш флеша выключен)
// =====================================================================

// Кладёт в память обоих каналов начало чирпа и сбрасывает указатели чтения.
// После этого старт — две записи в регистры.
static void IRAM_ATTR chirpLoad(uint8_t dir) {
    for (int i = 0; i < 2; i++) {
        uint32_t ch = chOf(i);
        rmt_ll_tx_reset_pointer(&RMT, ch);
        uint16_t n = wave_len[dir][i] < MEM_WORDS ? wave_len[dir][i] : MEM_WORDS;
        rmt_ll_write_memory(&RMTMEM, ch, wave[dir][i], n, 0);
        ch_pos[i]  = n;
        ch_half[i] = 0;
        rmt_ll_clear_tx_thres_interrupt(&RMT, ch);
        rmt_ll_clear_tx_end_interrupt(&RMT, ch);
    }
    ch_dir    = dir;
    ch_loaded = true;
    ch_ended  = 0;
}

// Порог: канал отдал очередные HALF_WORDS слов — доливаем ту половину памяти,
// которую он только что прошёл, пока играет вторая (~2.5 мс запаса). Когда
// таблица кончилась, кладём лишний маркер конца — так же делает драйвер IDF.
static void IRAM_ATTR chirpRefill(int i) {
    uint32_t ch  = chOf(i);
    uint16_t off = ch_half[i] ? HALF_WORDS : 0;
    uint16_t len = wave_len[ch_dir][i];
    uint16_t n   = len - ch_pos[i];
    if (n > HALF_WORDS) n = HALF_WORDS;
    if (n) {
        rmt_ll_write_memory(&RMTMEM, ch, &wave[ch_dir][i][ch_pos[i]], n, off);
        ch_pos[i] += n;
    } else {
        uint32_t stop = 0;
        rmt_ll_write_memory(&RMTMEM, ch, &stop, 1, off);
    }
    ch_half[i] ^= 1;
}

static void IRAM_ATTR chirpIsr(void*) {
    portENTER_CRITICAL_ISR(&ch_mux);
    uint32_t st = RMT.int_st.val;
    for (int i = 0; i < 2; i++) {
        uint32_t ch = chOf(i);
        if (st & (1u << (ch + 8))) {            // порог
            rmt_ll_clear_tx_thres_interrupt(&RMT, ch);
            if (ch_busy) chirpRefill(i);
        }
        if (st & (1u << ch)) {                  // дошли до маркера конца
            rmt_ll_clear_tx_end_interrupt(&RMT, ch);
            if (ch_busy) ch_ended |= (uint8_t)(1u << i);
        }
    }
    // Чирп доиграл — сразу кладём начало следующего (того же направления:
    // оно меняется, только если колесо закрутили в другую сторону).
    if (ch_busy && ch_ended == 3) {
        ch_busy = false;
        chirpLoad(ch_dir);
    }
    portEXIT_CRITICAL_ISR(&ch_mux);
}

void IRAM_ATTR beeperTrigger(bool up) {
    if (!ch_ready) return;
    uint8_t dir = up ? 0 : 1;
    // _SAFE: зовётся из ISR Холла и из задачи (самопроверка).
    portENTER_CRITICAL_SAFE(&ch_mux);
    if (ch_busy) {
        // Перезапуск: новое событие пришло раньше конца чирпа (выше ~670 об/мин).
        // Остановленный канал сразу уходит в холостой LOW.
        rmt_ll_tx_stop(&RMT, CH_A);
        rmt_ll_tx_stop(&RMT, CH_B);
        ch_busy   = false;
        ch_loaded = false;
    }
    // Обычный случай — начало уже в памяти, копировать нечего.
    if (!ch_loaded || ch_dir != dir) chirpLoad(dir);
    ch_loaded = false;          // память теперь проигрывается
    ch_busy   = true;
    // Оба канала в синхронной группе: стартуют вместе, когда взведены оба.
    rmt_ll_tx_start(&RMT, CH_A);
    rmt_ll_tx_start(&RMT, CH_B);
    portEXIT_CRITICAL_SAFE(&ch_mux);
}

// =====================================================================
//  Расчёт формы (один раз при загрузке)
// =====================================================================

// Первые и последние NQ отметок времени (с) — частота по центрам импульсов.
struct EdgeFreq {
    double first[NQ];
    double last[NQ];
    int    nf = 0, nl = 0;   // nl — всего добавлено, last — кольцо
    void add(double t) {
        if (nf < NQ) first[nf++] = t;
        last[nl % NQ] = t;
        nl++;
    }
    double fStart() const {
        return nf >= 2 ? (nf - 1) / (first[nf - 1] - first[0]) : 0.0;
    }
    double fEnd() const {
        int m = nl < NQ ? nl : NQ;
        if (m < 2) return 0.0;
        return (m - 1) / (last[(nl - 1) % NQ] - last[(nl - m) % NQ]);
    }
};

// Огибающая: приподнятый косинус на краях, единица в середине.
static double envelope(double t, double D, double R) {
    if (t <= 0.0 || t >= D) return 0.0;
    if (t < R)     return 0.5 - 0.5 * cos(M_PI * t / R);
    if (t > D - R) return 0.5 - 0.5 * cos(M_PI * (D - t) / R);
    return 1.0;
}

// Таблица для одного направления. Каждый период — одно слово RMT на канал:
// LOW до начала импульса, затем HIGH шириной w по центру своего полупериода
// (A — фаза k+¼, B — фаза k+¾). Основная гармоника такого трёхуровневого
// сигнала ∝ sin(π·w/T), поэтому для амплитуды a ширина w = T/π·asin(a): при
// a = 1 это ровно полпериода, то есть обычный полный меандр моста.
// Фронты считаются в абсолютном времени и только потом округляются до тика,
// поэтому ошибка округления не копится за 260 периодов.
static bool buildWave(uint8_t dir, double tick_hz) {
    const double D  = PIEZO_CHIRP_US * 1e-6;
    const double R  = PIEZO_CHIRP_FADE_US * 1e-6;
    const double f0 = dir == 0 ? PIEZO_CHIRP_F_LO_HZ : PIEZO_CHIRP_F_HI_HZ;
    const double f1 = dir == 0 ? PIEZO_CHIRP_F_HI_HZ : PIEZO_CHIRP_F_LO_HZ;
    const double c  = (f1 - f0) / D;          // скорость свипа, Гц/с
    const double P  = D * (f0 + f1) / 2.0;    // периодов за чирп

    WaveStats& st = wave_stats[dir];
    st = WaveStats{};
    EdgeFreq ef;
    double a_last = -1.0;                     // центр последнего A до середины чирпа

    for (int side = 0; side < 2; side++) {
        uint32_t* out    = wave[dir][side];
        uint16_t  n      = 0;
        int64_t   cursor = 0;                 // тики от старта
        for (int k = 0; ; k++) {
            double p = k + (side ? 0.75 : 0.25);
            if (p >= P) break;
            // Фаза линейного чирпа φ(t) = f0·t + c·t²/2 (в периодах) → момент центра.
            double tc = (sqrt(f0 * f0 + 2.0 * c * p) - f0) / c;
            double f  = f0 + c * tc;
            double w  = asin(envelope(tc, D, R)) / (M_PI * f);
            if (w < PULSE_MIN_S) continue;

            int64_t rise = llround((tc - w / 2) * tick_hz);
            int64_t fall = llround((tc + w / 2) * tick_hz);
            if (rise <= cursor) rise = cursor + 1;
            if (fall <= rise)   fall = rise + 1;
            int64_t low = rise - cursor;
            while (low > DUR_MAX) {           // пауза длиннее 15 бит — словом из двух LOW
                if (n >= WAVE_MAX - 2) return false;
                out[n++] = rmtWord(0, DUR_MAX / 2, 0, DUR_MAX / 2);
                low -= 2 * (DUR_MAX / 2);
            }
            if (n >= WAVE_MAX - 1) return false;
            out[n++] = rmtWord(0, (uint32_t)low, 1, (uint32_t)(fall - rise));
            cursor = fall;

            st.n_all[side]++;
            double t_fall_us = fall * 1e6 / tick_hz;
            if (t_fall_us > st.t_end_us) st.t_end_us = t_fall_us;
            if (w * 1e6 >= ST_MIN_W_US) {
                st.n_big[side]++;
                if (side == 0) {
                    ef.add(tc);
                    if (tc < D / 2) a_last = tc;
                } else if (st.ab_mid_us == 0.0 && a_last >= 0.0 && tc > a_last) {
                    st.ab_mid_us = (tc - a_last) * 1e6;
                }
            }
        }
        out[n++] = 0;                         // маркер конца: длительность 0
        wave_len[dir][side] = n;
    }
    st.f_start = ef.fStart();
    st.f_end   = ef.fEnd();
    return true;
}

// =====================================================================
//  Самопроверка: проигрываем оба чирпа и читаем уровни прямо на площадках
// =====================================================================

struct ChirpMeas {
    uint32_t pa = 0, pb = 0;      // импульсов на IO1 / IO2
    uint32_t both_hi = 0;         // выборок «оба HIGH» — след ёмкости пьезо на фронтах
    int64_t  hi_run_max = 0;      // самый длинный отрезок «оба HIGH», мкс
    double   f_start = 0, f_end = 0, t_end_us = 0, ab_mid_us = 0;
    bool     ended = false;
};

static void measureChirp(bool up, ChirpMeas& m) {
    const uint32_t mA     = 1u << PIN_PIEZO_A;
    const uint32_t mB     = 1u << PIN_PIEZO_B;
    const double   half_d = PIEZO_CHIRP_US / 2.0;   // мкс
    EdgeFreq ef;
    int64_t  rise_a = 0, rise_b = 0, hi_since = 0, last_act = 0;
    double   a_last = -1.0;
    bool     ab_frozen = false;

    // Планировщик ядра 1 стоит, чтобы вытеснение не съедало фронты.
    // Прерывания работают — дозаливка RMT идёт как в бою.
    vTaskSuspendAll();
    int64_t t0 = esp_timer_get_time();
    beeperTrigger(up);
    uint32_t prev = REG_READ(GPIO_IN_REG);
    while (true) {
        int64_t  t  = esp_timer_get_time();
        uint32_t in = REG_READ(GPIO_IN_REG);
        bool a = in & mA, b = in & mB;
        bool pa = prev & mA, pb = prev & mB;

        if (a && !pa) { m.pa++; rise_a = t; }
        if (!a && pa && rise_a && t - rise_a >= ST_MIN_W_US) {
            double ca = (rise_a + t) / 2.0 - t0;
            ef.add(ca * 1e-6);
            if (ca >= half_d) ab_frozen = true;
            a_last = ca;
        }
        if (b && !pb) { m.pb++; rise_b = t; }
        if (!b && pb && rise_b && t - rise_b >= ST_MIN_W_US && !ab_frozen && a_last >= 0.0) {
            double cb = (rise_b + t) / 2.0 - t0;
            if (cb > a_last) m.ab_mid_us = cb - a_last;
        }

        if (a && b) {
            m.both_hi++;
            if (hi_since == 0) hi_since = t;
        } else if (hi_since) {
            if (t - hi_since > m.hi_run_max) m.hi_run_max = t - hi_since;
            hi_since = 0;
        }

        if (a || b)                              last_act = t;
        else if (last_act && t - last_act > 1000) { m.ended = true; break; }
        if (t - t0 > PIEZO_CHIRP_US + 10000) break;   // так и не замолчал
        prev = in;
    }
    xTaskResumeAll();

    m.t_end_us = last_act ? (double)(last_act - t0) : 0.0;
    m.f_start  = ef.fStart();
    m.f_end    = ef.fEnd();
}

static bool checkChirp(uint8_t dir, const ChirpMeas& m) {
    const WaveStats& s = wave_stats[dir];
    // Самые узкие импульсы краёв на ёмкостной нагрузке могут не дотянуть до
    // порога входа — счёт допускается от «крупных» до всех.
    bool cnt_a = m.pa + 2 >= s.n_big[0] && m.pa <= s.n_all[0] + 1u;
    bool cnt_b = m.pb + 2 >= s.n_big[1] && m.pb <= s.n_all[1] + 1u;
    // «Оба HIGH» допустимо только на фронтах — доли микросекунды. Без
    // раздельных импульсов (сломан канал или синхронизация) отрезки были бы
    // в полпериода.
    bool phase = m.hi_run_max * 8 < 1000000 / PIEZO_CHIRP_F_HI_HZ;
    return m.ended && cnt_a && cnt_b && phase &&
           fabs(m.f_start   - s.f_start)   < 0.02 * s.f_start &&
           fabs(m.f_end     - s.f_end)     < 0.02 * s.f_end &&
           fabs(m.t_end_us  - s.t_end_us)  < 300.0 &&
           fabs(m.ab_mid_us - s.ab_mid_us) < 3.0;
}

static void beeperSelfTest() {
    ChirpMeas mu, md;
    measureChirp(true, mu);
    delay(5);
    measureChirp(false, md);   // заодно проверяет перезагрузку другого направления
    bool ok_u = checkChirp(0, mu);
    bool ok_d = checkChirp(1, md);

    // Строка лога — 96 байт вместе с 20-символьной меткой времени.
    char line[80];
    if (ok_u && ok_d) {
        // load — выборки «оба HIGH» на фронтах: 0 — к выводам, похоже, ничего не подключено.
        snprintf(line, sizeof(line), "[SYS] Piezo self-test OK: up %.1f-%.1fk dn %.1f-%.1fk %.1fms load %u",
                 mu.f_start / 1000, mu.f_end / 1000, md.f_start / 1000, md.f_end / 1000,
                 mu.t_end_us / 1000, (unsigned)mu.both_hi);
        webLog(line);
        return;
    }
    for (int d = 0; d < 2; d++) {
        const ChirpMeas& m = d ? md : mu;
        if (d ? ok_d : ok_u) continue;
        // A/B — импульсы измерено/по таблице, run — самый длинный «оба HIGH»,
        // ab — сдвиг IO2 относительно IO1 в середине (по таблице ~полпериода).
        snprintf(line, sizeof(line), "[ERR] Piezo %s FAIL: A%u/%u B%u/%u run%u %.1f-%.1fk %.1fms ab%.1f",
                 d ? "dn" : "up", (unsigned)m.pa, (unsigned)wave_stats[d].n_all[0],
                 (unsigned)m.pb, (unsigned)wave_stats[d].n_all[1], (unsigned)m.hi_run_max,
                 m.f_start / 1000, m.f_end / 1000, m.t_end_us / 1000, m.ab_mid_us);
        webLog(line);
    }
}

// =====================================================================
//  Инициализация
// =====================================================================

static bool rmtChannelInit(rmt_channel_t ch, int pin) {
    rmt_config_t c = {};
    c.rmt_mode                 = RMT_MODE_TX;
    c.channel                  = ch;
    c.gpio_num                 = (gpio_num_t)pin;
    c.clk_div                  = 1;            // 80 МГц от APB: шаг 12.5 нс
    c.mem_block_num            = MEM_WORDS / SOC_RMT_MEM_WORDS_PER_CHANNEL;
    c.tx_config.carrier_en     = false;
    c.tx_config.loop_en        = false;
    c.tx_config.idle_output_en = true;         // тишина — LOW
    c.tx_config.idle_level     = RMT_IDLE_LEVEL_LOW;
    return rmt_config(&c) == ESP_OK;
}

void beeperInit() {
    // До подключения RMT оба вывода — обычные выходы в LOW.
    gpio_set_level((gpio_num_t)PIN_PIEZO_A, 0);
    gpio_set_level((gpio_num_t)PIN_PIEZO_B, 0);
    gpio_config_t io = {};
    io.pin_bit_mask = (1ULL << PIN_PIEZO_A) | (1ULL << PIN_PIEZO_B);
    io.mode         = GPIO_MODE_OUTPUT;
    gpio_config(&io);

    if (!rmtChannelInit((rmt_channel_t)CH_A, PIN_PIEZO_A) ||
        !rmtChannelInit((rmt_channel_t)CH_B, PIN_PIEZO_B)) {
        webLog("[ERR] Piezo: RMT config failed");
        return;
    }
    uint32_t tick_hz = 0;
    rmt_get_counter_clock((rmt_channel_t)CH_A, &tick_hz);
    if (tick_hz == 0) tick_hz = 80000000;

    if (!buildWave(0, tick_hz) || !buildWave(1, tick_hz)) {
        webLog("[ERR] Piezo: chirp table overflow");
        return;
    }

    // Память — напрямую по адресам (не через FIFO), включена; кольцевой режим
    // с порогом в половину памяти; без повторов.
    rmt_ll_enable_mem_access(&RMT, true);
    rmt_ll_power_down_mem(&RMT, false);
    for (int i = 0; i < 2; i++) {
        uint32_t ch = chOf(i);
        rmt_ll_tx_enable_loop(&RMT, ch, false);
        rmt_ll_tx_enable_pingpong(&RMT, ch, true);
        rmt_ll_tx_set_limit(&RMT, ch, HALF_WORDS);
    }
    // Синхронная группа: каналы стартуют в один такт, когда взведены оба.
    rmt_ll_tx_enable_sync(&RMT, true);
    rmt_ll_tx_add_to_sync_group(&RMT, CH_A);
    rmt_ll_tx_add_to_sync_group(&RMT, CH_B);
    rmt_ll_tx_reset_channels_clock_div(&RMT, (1u << CH_A) | (1u << CH_B));

    // Входной буфер — только для самопроверки: rmt_config() его выключил.
    PIN_INPUT_ENABLE(GPIO_PIN_MUX_REG[PIN_PIEZO_A]);
    PIN_INPUT_ENABLE(GPIO_PIN_MUX_REG[PIN_PIEZO_B]);

    // Своё прерывание вместо драйвера IDF (rmt_driver_install): в IRAM, чтобы
    // дозаливка шла и во время чтения файла с флеша — иначе на это время
    // RMT проигрывал бы старое содержимое кольца.
    rmt_isr_handle_t h;
    if (rmt_isr_register(chirpIsr, nullptr, ESP_INTR_FLAG_IRAM, &h) != ESP_OK) {
        webLog("[ERR] Piezo: RMT ISR alloc failed");
        return;
    }
    for (int i = 0; i < 2; i++) {
        uint32_t ch = chOf(i);
        rmt_ll_clear_tx_thres_interrupt(&RMT, ch);
        rmt_ll_clear_tx_end_interrupt(&RMT, ch);
        rmt_ll_enable_tx_thres_interrupt(&RMT, ch, true);
        rmt_ll_enable_tx_end_interrupt(&RMT, ch, true);
    }

    chirpLoad(0);
    ch_ready = true;
    beeperSelfTest();
}
