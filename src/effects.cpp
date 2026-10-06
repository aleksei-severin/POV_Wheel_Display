#include "config.h"
#include "effects.h"
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/semphr.h>
#include <LittleFS.h>
#include <math.h>
// tinfl из ПЗУ — тот же распаковщик, что у заливки по BLE (см. povble.cpp).
#include "rom/miniz.h"

volatile uint8_t  effect_id        = EFF_NONE;
volatile int8_t   pending_effect   = -1;
volatile bool     pending_effect_play = false;
volatile uint16_t effect_speed_red = 45;     // км/ч, при которых шрифт красный

// Пара буферов кадра. Выделяются при запуске эффекта и освобождаются при
// остановке: длинной анимации нужен весь PSRAM, держать 62 кБ «на всякий
// случай» неправильно.
static uint8_t* eff_buf[2] = {nullptr, nullptr};
static uint8_t  eff_read   = 0;              // какой буфер сейчас опубликован

// Взаимное исключение генератора и запуска/остановки. Без него между «генератор
// прочитал effect_id» и «генератор начал писать» помещается вся остановка
// целиком — и запись уходит в уже освобождённую память.
static SemaphoreHandle_t eff_mutex = nullptr;

// --- служебные таблицы, строятся один раз ---
static uint8_t sec_hue[SECTORS];      // сектор → 8-битный угол (0..255)
static float   led_r_norm[LEDS_PER_SIDE];  // радиус диода в долях внешнего

// --- параметры эффектов (OP_FX_GET / OP_FX_SET), живут в /fx.cfg ---
#define FX_FILE "/fx.cfg"
static volatile uint8_t fx_rb_speed  = 10;   // ≈ прежний оборот спектра за ~5 с
static volatile uint8_t fx_rb_sharp  = 0;    // плавный спектр, как было всегда
static volatile uint8_t fx_clk_mode  = 0;
static volatile uint8_t fx_clk_r = 255, fx_clk_g = 255, fx_clk_b = 255;
static volatile uint8_t fx_clk_speed = 30;
static volatile bool    fx_dirty     = false;

// Сдвиг тона радуги, 2^32 — полный круг. Копится по кадрам, а не считается от
// millis(): тогда смена скорости с телефона не дёргает картину скачком.
static uint32_t fx_hue_acc = 0;
static uint32_t fx_last_ms = 0;

static inline uint16_t pack565(int r, int g, int b) {
    if (r < 0) r = 0; else if (r > 255) r = 255;
    if (g < 0) g = 0; else if (g > 255) g = 255;
    if (b < 0) b = 0; else if (b > 255) b = 255;
    return (uint16_t)(((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3));
}

// Цветовой круг по 8-битному тону: 0 — красный, 85 — зелёный, 170 — синий.
static inline void hsv2rgb(uint8_t h, uint8_t s, uint8_t v,
                           int& r, int& g, int& b) {
    uint16_t hh  = (uint16_t)h * 6;
    uint8_t  sec = (uint8_t)(hh >> 8);
    uint8_t  f   = (uint8_t)(hh & 0xFF);
    int p = (v * (255 - s)) >> 8;
    int q = (v * (255 - ((s * f) >> 8))) >> 8;
    int t = (v * (255 - ((s * (255 - f)) >> 8))) >> 8;
    switch (sec) {
        case 0:  r = v; g = t; b = p; break;
        case 1:  r = q; g = v; b = p; break;
        case 2:  r = p; g = v; b = t; break;
        case 3:  r = p; g = q; b = v; break;
        case 4:  r = t; g = p; b = v; break;
        default: r = v; g = p; b = q; break;
    }
}

const char* effectName(uint8_t id) {
    switch (id) {
        case EFF_SPEED:   return "Speed";
        case EFF_RAINBOW: return "Rainbow";
        case EFF_TESTING: return "Testing";
        case EFF_CLOCK:   return "Clock";
        case EFF_TEXT:    return "Text";
        default:          return "Off";
    }
}

float currentSpeedKmh() {
    uint32_t period, hall_t;
    noInterrupts();
    period = rotation_period;
    hall_t = last_hall_time;
    interrupts();
    if (period == 0 || hall_t == 0) return 0.0f;
    uint32_t silence = (uint32_t)(micros() - hall_t);
    if (silence >= 3000000UL) return 0.0f;
    uint32_t eff = (silence > period) ? silence : period;
    // Оборот за eff мкс, за оборот колесо проезжает длину окружности (мм).
    // мм/мкс → км/ч: ×3.6 (мм/мс) ×1000 (мкс→мс) /1000 (мм→м)... итого ×3600/1000.
    return (float)wheel_circumference / (float)eff * 3600.0f;
}

// Сдвиг тона за прошедшее с прошлого кадра время. speed 100 — два оборота
// цветового круга в секунду: 2^32 · 2 / (100 · 1000) ≈ 85899 на мс и единицу.
static void advanceHue(uint8_t speed, uint32_t t) {
    uint32_t dt = fx_last_ms ? (uint32_t)(t - fx_last_ms) : 0;
    if (dt > 500) dt = 500;               // после паузы (лента гасла) — без скачка
    fx_last_ms = t;
    fx_hue_acc += (uint32_t)((uint64_t)dt * speed * 85899u);
}

// =====================================================================
//                        ЭФФЕКТЫ
//  Каждый пишет 360×44 пикселей RGB565 в out[sector * LEDS_PER_SIDE + led].
// =====================================================================

// Маска яркости → кадр: одним цветом или радугой, которая течёт по углу (тон
// от сектора плюс сдвиг во времени). Общая для «Текста», часов и скорости.
static void colorizeMask(uint16_t* out, const uint8_t* alpha, bool rainbow,
                         int cr, int cg, int cb) {
    uint8_t phase = (uint8_t)(fx_hue_acc >> 24);
    for (int s = 0; s < SECTORS; s++) {
        if (rainbow) hsv2rgb((uint8_t)(sec_hue[s] + phase), 255, 255, cr, cg, cb);
        const uint8_t* a = alpha + s * LEDS_PER_SIDE;
        uint16_t* row = out + s * LEDS_PER_SIDE;
        for (int i = 0; i < LEDS_PER_SIDE; i++) {
            int m = a[i];
            row[i] = m ? pack565(cr * m / 255, cg * m / 255, cb * m / 255) : 0;
        }
    }
}

// --- Радуга: тон по углу и по радиусу ---
// Чистое кольцо одного цвета на вращающемся колесе читается как мигание всей
// плоскости, поэтому тон меняется ещё и вдоль луча — получается спираль,
// которая честно выглядит движущейся.
//
// Положение на круге (10 бит: сектор, сдвиг во времени, +12 на диод) → цвет
// через таблицу: резкость меняет только её, и кадр стоит одно чтение на диод.
#define RB_LUT 1024
static uint16_t rb_lut[RB_LUT];
static int      rb_lut_sharp = -1;            // для какой резкости построена

// Семь цветов радуги — тон в градусах: красный, оранжевый, жёлтый, зелёный,
// голубой, синий, фиолетовый. Последний — снова красный, замыкает круг.
// Те же числа — в приложении (ui/EffectPreviews.kt, RAINBOW_ANCHORS).
static const float RB_ANCHORS[8] = {0, 30, 60, 120, 190, 240, 280, 360};

// Тон для положения f (0…1) на круге при резкости p (0…1). Круг делится на
// семь равных полос, у каждой «плато» её цвета шириной p и линейный переход к
// соседней на остаток. Чистые полосы (p = 1) неравномерно распределённым тонам
// ничем не обязаны, а при p = 0 тон обязан быть ровно прежним равномерным
// спектром — поэтому итог плавно смешивает оба: (1 − p)·равномерный + p·полосы.
// Смесь двух неубывающих функций сама неубывающая — спектр не идёт вспять.
static float rainbowHue(float f, float p) {
    float x = f * 7.0f;
    int   k = (int)x;
    if (k > 6) k = 6;
    float t = x - k, g;
    if (p >= 0.999f)      g = (t < 0.5f) ? 0.0f : 1.0f;
    else {
        g = (t - p * 0.5f) / (1.0f - p);
        if (g < 0) g = 0; else if (g > 1) g = 1;
    }
    float band = RB_ANCHORS[k] + g * (RB_ANCHORS[k + 1] - RB_ANCHORS[k]);
    return (1.0f - p) * f * 360.0f + p * band;
}

static void buildRainbowLut(int sharp) {
    float p = sharp / 100.0f;
    for (int u = 0; u < RB_LUT; u++) {
        float h = fmodf(rainbowHue((float)u / RB_LUT, p), 360.0f);
        // HSV при полной насыщенности и яркости, тон в градусах.
        float hh = h / 60.0f;
        int   sec = (int)hh;
        float fr = hh - sec;
        int up = (int)lroundf(255.0f * fr), dn = 255 - up;
        int r, g, b;
        switch (sec % 6) {
            case 0:  r = 255; g = up;  b = 0;   break;
            case 1:  r = dn;  g = 255; b = 0;   break;
            case 2:  r = 0;   g = 255; b = up;  break;
            case 3:  r = 0;   g = dn;  b = 255; break;
            case 4:  r = up;  g = 0;   b = 255; break;
            default: r = 255; g = 0;   b = dn;  break;
        }
        rb_lut[u] = pack565(r, g, b);
    }
    rb_lut_sharp = sharp;
}

static void effRainbow(uint16_t* out, uint32_t t) {
    advanceHue(fx_rb_speed, t);
    int sharp = fx_rb_sharp;
    if (sharp != rb_lut_sharp) buildRainbowLut(sharp);
    uint32_t phase = fx_hue_acc >> 22;          // 10 бит
    for (int s = 0; s < SECTORS; s++) {
        uint32_t base = (uint32_t)(s * RB_LUT / SECTORS) + phase;
        uint16_t* row = out + s * LEDS_PER_SIDE;
        for (int i = 0; i < LEDS_PER_SIDE; i++) row[i] = rb_lut[(base + i * 12) & (RB_LUT - 1)];
    }
}

// --- Текст из растрового шрифта: скорость и часы ---
// Строки рисуются не в промежуточную декартову маску, а прямо в полярную:
// каждая ячейка «сектор × диод» усредняет FX_SS × FX_SS точек, и каждая точка
// проверяется на попадание в «пиксель» шрифта аналитически. Отсюда дробный
// масштаб (дата мельче времени — иначе не помещается в круг) и честное
// сглаживание краёв без буфера 192 × 192. Тот же алгоритм — в приложении
// (convert/FxMask.kt), миниатюры обязаны выглядеть как обод.
//
// Координаты — единицы, в которых крайний диод на радиусе 47.5 (как у прежней
// маски 96 × 96), центр в нуле, ось y вниз.
#define FX_SS     4
#define FX_R_OUT  47.5f

// Шрифт 5×7, по строке на байт (младшие 5 бит, старший — левый столбец).
// Ширина у точки — один столбец: время «hh.mm.ss» иначе не влезает в круг.
enum { G_K = 10, G_M, G_SLASH, G_H, G_DOT, G_DASH, G_COUNT };
static const uint8_t FONT57[G_COUNT][7] = {
    {0x0E,0x11,0x13,0x15,0x19,0x11,0x0E}, // 0
    {0x04,0x0C,0x04,0x04,0x04,0x04,0x0E}, // 1
    {0x0E,0x11,0x01,0x02,0x04,0x08,0x1F}, // 2
    {0x1F,0x02,0x04,0x02,0x01,0x11,0x0E}, // 3
    {0x02,0x06,0x0A,0x12,0x1F,0x02,0x02}, // 4
    {0x1F,0x10,0x1E,0x01,0x01,0x11,0x0E}, // 5
    {0x06,0x08,0x10,0x1E,0x11,0x11,0x0E}, // 6
    {0x1F,0x01,0x02,0x04,0x08,0x08,0x08}, // 7
    {0x0E,0x11,0x11,0x0E,0x11,0x11,0x0E}, // 8
    {0x0E,0x11,0x11,0x0F,0x01,0x02,0x0C}, // 9
    {0x10,0x10,0x12,0x14,0x18,0x14,0x12}, // k
    {0x00,0x00,0x1A,0x15,0x15,0x15,0x15}, // m
    {0x01,0x02,0x02,0x04,0x08,0x08,0x10}, // /
    {0x10,0x10,0x16,0x19,0x11,0x11,0x11}, // h
    {0x00,0x00,0x00,0x00,0x00,0x00,0x10}, // .  (ширина 1)
    {0x00,0x00,0x00,0x0E,0x00,0x00,0x00}, // -
};
static inline int glyphW(int g) { return g == G_DOT ? 1 : 5; }

// Строка, разложенная по столбцам: в каждом — 7 бит горящих строк шрифта.
#define RUN_COLS 64
struct FxRun {
    float   x0, y0, sc;      // левый верхний угол и размер «пикселя» шрифта
    int     cols;
    uint8_t col[RUN_COLS];
};

// Строка по центру по горизонтали; y0 — верх строки.
static void fxRunMake(FxRun& r, const uint8_t* g, int n, float y0, float sc) {
    int c = 0;
    for (int k = 0; k < n; k++) {
        if (k) { if (c < RUN_COLS) r.col[c] = 0; c++; }       // столбец-зазор
        for (int x = 0; x < glyphW(g[k]); x++) {
            uint8_t bits = 0;
            int bit = (g[k] == G_DOT) ? 0x10 : (0x10 >> x);
            for (int row = 0; row < 7; row++) if (FONT57[g[k]][row] & bit) bits |= (1 << row);
            if (c < RUN_COLS) r.col[c] = bits;
            c++;
        }
    }
    if (c > RUN_COLS) c = RUN_COLS;
    r.cols = c;
    r.sc   = sc;
    r.x0   = -c * sc * 0.5f;
    r.y0   = y0;
}

static uint8_t* fx_alpha = nullptr;      // 360 × 44, полярная маска скорости/часов; PSRAM

static void fxRender(uint8_t* alpha, const FxRun* runs, int nr) {
    const float pitch = (LED_R_OUTER_MM - LED_R_INNER_MM) / (LEDS_PER_SIDE - 1) / LED_R_OUTER_MM * FX_R_OUT;
    float jit[FX_SS];
    for (int k = 0; k < FX_SS; k++) jit[k] = (k + 0.5f) / FX_SS - 0.5f;
    const int full = FX_SS * FX_SS;
    for (int s = 0; s < SECTORS; s++) {
        float ca[FX_SS], sa[FX_SS];
        for (int k = 0; k < FX_SS; k++) {
            float a = (s + jit[k]) * (float)M_PI / 180.0f;
            ca[k] = cosf(a); sa[k] = sinf(a);
        }
        uint8_t* outRow = alpha + s * LEDS_PER_SIDE;
        for (int i = 0; i < LEDS_PER_SIDE; i++) {
            float r0 = led_r_norm[i] * FX_R_OUT;
            int hits = 0;
            for (int ka = 0; ka < FX_SS; ka++) {
                for (int kr = 0; kr < FX_SS; kr++) {
                    float rr = r0 + jit[kr] * pitch;
                    float x = rr * ca[ka], y = rr * sa[ka];
                    for (int q = 0; q < nr; q++) {
                        const FxRun& R = runs[q];
                        float u = (x - R.x0) / R.sc, v = (y - R.y0) / R.sc;
                        if (u < 0 || v < 0 || v >= 7.0f || u >= R.cols) continue;
                        if ((R.col[(int)u] >> (int)v) & 1) { hits++; break; }
                    }
                }
            }
            outRow[i] = (uint8_t)((hits * 255 + full / 2) / full);
        }
    }
}

// --- Скорость ---
// Число НАД центром, подпись — под ним: в середине диска дырка радиусом 49 мм
// (8.5 единиц), и всё, что её накроет, потеряет середину.
static int speed_shown = -1;

static void effSpeed(uint16_t* out, uint32_t t) {
    (void)t;
    int v = (int)(currentSpeedKmh() + 0.5f);
    if (v > 999) v = 999;
    if (v != speed_shown) {
        uint8_t dig[3]; int nd = 0;
        if (v >= 100) { dig[nd++] = v / 100; dig[nd++] = (v / 10) % 10; dig[nd++] = v % 10; }
        else if (v >= 10) { dig[nd++] = v / 10; dig[nd++] = v % 10; }
        else { dig[nd++] = v; }
        float sc = (nd >= 3) ? 3.0f : 4.0f;
        static const uint8_t UNIT[4] = {G_K, G_M, G_SLASH, G_H};
        FxRun runs[2];
        fxRunMake(runs[0], dig, nd, -10.0f - 7.0f * sc, sc);   // низ числа на 10 выше центра
        fxRunMake(runs[1], UNIT, 4, 10.0f, 2.0f);              // верх подписи на 10 ниже
        fxRender(fx_alpha, runs, 2);
        speed_shown = v;
    }
    // Цвет: зелёный на малой скорости, красный на effect_speed_red и выше.
    // Идём по тону 85→0, поэтому переход проходит через жёлтый сам собой.
    float red = (float)effect_speed_red;
    if (red < 1.0f) red = 1.0f;
    float k = (float)v / red;
    if (k > 1.0f) k = 1.0f;
    int rr, gg, bb;
    hsv2rgb((uint8_t)(85.0f * (1.0f - k) + 0.5f), 255, 255, rr, gg, bb);
    colorizeMask(out, fx_alpha, false, rr, gg, bb);
}

// --- Часы ---
// Цифровые: время «hh.mm.ss» над центром, дата «yyyy.mm.dd» под ним. Дата
// мельче — длиннее на два знака и обязана поместиться в хорду круга. Часы не
// заведены — прочерки вместо цифр. Маска перестраивается раз в секунду (по
// смене строки), каждый кадр — только цвет: радуга течёт и между секундами.
#define CLK_SC_TIME 2.0f
#define CLK_SC_DATE 1.6f
static int32_t clock_shown = -2;

static void effClock(uint16_t* out, uint32_t t) {
    bool rainbow = fx_clk_mode == 1;
    if (rainbow) advanceHue(fx_clk_speed, t);

    int hh, mm, ss, yy, mo, dd;
    bool ok = localClock(hh, mm, ss) && localDate(yy, mo, dd);
    // Ключ строки: месяц, день и секунда суток. Год в него не входит — его смена
    // при той же дате и секунде невозможна, а скачок часов поправит следующая.
    int32_t key = ok ? ((mo * 32 + dd) * 86400 + hh * 3600 + mm * 60 + ss) : -1;
    if (key != clock_shown) {
        uint8_t tm[8], dt[10];
        if (ok) {
            uint8_t T[8] = {(uint8_t)(hh / 10), (uint8_t)(hh % 10), G_DOT,
                            (uint8_t)(mm / 10), (uint8_t)(mm % 10), G_DOT,
                            (uint8_t)(ss / 10), (uint8_t)(ss % 10)};
            uint8_t D[10] = {(uint8_t)(yy / 1000 % 10), (uint8_t)(yy / 100 % 10),
                             (uint8_t)(yy / 10 % 10), (uint8_t)(yy % 10), G_DOT,
                             (uint8_t)(mo / 10), (uint8_t)(mo % 10), G_DOT,
                             (uint8_t)(dd / 10), (uint8_t)(dd % 10)};
            memcpy(tm, T, 8); memcpy(dt, D, 10);
        } else {
            for (int k = 0; k < 8; k++)  tm[k] = (k == 2 || k == 5) ? G_DOT : G_DASH;
            for (int k = 0; k < 10; k++) dt[k] = (k == 4 || k == 7) ? G_DOT : G_DASH;
        }
        FxRun runs[2];
        fxRunMake(runs[0], tm, 8, -10.0f - 7.0f * CLK_SC_TIME, CLK_SC_TIME);
        fxRunMake(runs[1], dt, 10, 10.0f, CLK_SC_DATE);
        fxRender(fx_alpha, runs, 2);
        clock_shown = key;
    }
    colorizeMask(out, fx_alpha, rainbow, fx_clk_r, fx_clk_g, fx_clk_b);
}

// --- Текст по окружности ---
// Маску рисует телефон: его шрифты, кириллица и честное сглаживание стоят
// дешевле, чем любой шрифт, который влез бы в прошивку. Здесь — только цвет.
#define TEXT_FILE      "/text.fx"
#define TEXT_MASK_SIZE (SECTORS * LEDS_PER_SIDE)
#define TEXT_STR_MAX   255
#define TEXT_RAW_MAX   (1 + TEXT_STR_MAX + TEXT_MASK_SIZE)
#define TEXT_COMP_MAX  (16 * 1024)

static uint8_t* text_alpha    = nullptr;   // 360 × 44, сектор → диод; PSRAM
static uint8_t* text_comp     = nullptr;   // блоб как пришёл — для /text.fx и OP_TEXT_GET
static uint16_t text_comp_len = 0;
static volatile uint8_t text_mode  = 0;
static volatile uint8_t text_r = 255, text_g = 255, text_b = 255;
static volatile uint8_t text_speed = 30;
static volatile bool    text_dirty = false;

static void effText(uint16_t* out, uint32_t t) {
    if (!text_alpha) { memset(out, 0, FRAME_SIZE); return; }
    const bool rainbow = (text_mode == 1);
    // Радуга течёт по углу: тон зависит от сектора и сдвигается во времени.
    // speed 100 — два оборота цветового круга в секунду.
    if (rainbow) advanceHue(text_speed, t);
    colorizeMask(out, text_alpha, rainbow, text_r, text_g, text_b);
}

// Распаковка и разбор блоба. Отдельно от публикации, чтобы загрузка из файла
// при старте не взводила text_dirty.
static bool textApply(const uint8_t* comp, size_t len) {
    if (!comp || len == 0 || len > TEXT_COMP_MAX) return false;
    uint8_t* raw = (uint8_t*)ps_malloc(TEXT_RAW_MAX);
    tinfl_decompressor* d = (tinfl_decompressor*)ps_malloc(sizeof(tinfl_decompressor));
    bool ok = false;
    if (raw && d) {
        tinfl_init(d);
        size_t in_len = len, out_len = TEXT_RAW_MAX;
        // Выход целиком в одном буфере, без кольцевого словаря: блоб маленький.
        tinfl_status st = tinfl_decompress(d, (const mz_uint8*)comp, &in_len,
                                           (mz_uint8*)raw, (mz_uint8*)raw, &out_len,
                                           TINFL_FLAG_USING_NON_WRAPPING_OUTPUT_BUF);
        ok = st == TINFL_STATUS_DONE && out_len >= 1 &&
             out_len == (size_t)1 + raw[0] + TEXT_MASK_SIZE;
    }
    if (ok) {
        if (!text_alpha) text_alpha = (uint8_t*)ps_malloc(TEXT_MASK_SIZE);
        if (!text_comp)  text_comp  = (uint8_t*)ps_malloc(TEXT_COMP_MAX);
        ok = text_alpha && text_comp;
    }
    if (ok) {
        // Под тем же мьютексом, что держит генератор на время кадра: иначе он
        // мог бы показать маску наполовину старой, наполовину новой.
        xSemaphoreTake(eff_mutex, portMAX_DELAY);
        memcpy(text_alpha, raw + 1 + raw[0], TEXT_MASK_SIZE);
        memcpy(text_comp, comp, len);
        text_comp_len = (uint16_t)len;
        xSemaphoreGive(eff_mutex);
    }
    if (raw) free(raw);
    if (d)   free(d);
    return ok;
}

bool effectsTextSetBlob(const uint8_t* comp, size_t len) {
    if (!textApply(comp, len)) return false;
    text_dirty = true;
    return true;
}

void effectsTextSetStyle(uint8_t mode, uint8_t r, uint8_t g, uint8_t b, uint8_t speed) {
    text_mode  = mode ? 1 : 0;
    text_r = r; text_g = g; text_b = b;
    text_speed = speed > 100 ? 100 : speed;
    text_dirty = true;
}

void effectsTextGetStyle(uint8_t& mode, uint8_t& r, uint8_t& g, uint8_t& b, uint8_t& speed) {
    mode = text_mode; r = text_r; g = text_g; b = text_b; speed = text_speed;
}

size_t effectsTextBlob(uint8_t* dst, size_t cap) {
    xSemaphoreTake(eff_mutex, portMAX_DELAY);
    size_t n = text_comp ? text_comp_len : 0;
    if (n > cap) n = 0;
    if (n) memcpy(dst, text_comp, n);
    xSemaphoreGive(eff_mutex);
    return n;
}

// /text.fx: "TXF1", mode, r, g, b, speed, rsv, u16 длина блоба, блоб.
void effectsTextFlush() {
    if (!text_dirty) return;
    text_dirty = false;
    File f = LittleFS.open(TEXT_FILE, "w");
    if (!f) { webLog("[EFF] Text save failed"); return; }
    uint8_t h[12] = {'T', 'X', 'F', '1', text_mode, text_r, text_g, text_b, text_speed, 0, 0, 0};
    xSemaphoreTake(eff_mutex, portMAX_DELAY);
    uint16_t n = text_comp ? text_comp_len : 0;
    memcpy(h + 10, &n, 2);
    f.write(h, sizeof(h));
    if (n) f.write(text_comp, n);
    xSemaphoreGive(eff_mutex);
    f.close();
    webLog("[EFF] Text saved");
}

// Вызывается из effectsInit(): LittleFS к этому моменту смонтирован, отрисовка
// ещё не идёт — читать флеш можно.
static void textLoad() {
    File f = LittleFS.open(TEXT_FILE, "r");
    if (!f) return;
    uint8_t h[12];
    if (f.read(h, sizeof(h)) == sizeof(h) && memcmp(h, "TXF1", 4) == 0) {
        text_mode  = h[4] ? 1 : 0;
        text_r = h[5]; text_g = h[6]; text_b = h[7];
        text_speed = h[8] > 100 ? 100 : h[8];
        uint16_t n; memcpy(&n, h + 10, 2);
        if (n > 0 && n <= TEXT_COMP_MAX) {
            uint8_t* tmp = (uint8_t*)ps_malloc(n);
            if (tmp) {
                if (f.read(tmp, n) == n && !textApply(tmp, n)) webLog("[EFF] Stored text is damaged");
                free(tmp);
            }
        }
    }
    f.close();
}

// ---- Параметры эффектов ----

void effectsFxGet(FxParams& p) {
    p.speed_red = effect_speed_red;
    p.rb_speed  = fx_rb_speed;
    p.rb_sharp  = fx_rb_sharp;
    p.clk_mode  = fx_clk_mode;
    p.clk_r = fx_clk_r; p.clk_g = fx_clk_g; p.clk_b = fx_clk_b;
    p.clk_speed = fx_clk_speed;
}

void effectsFxSet(const FxParams& p) {
    // Красная точка скорости — в SettingsBlob (так было до /fx.cfg), остальное — в файле.
    if (p.speed_red >= 5 && p.speed_red <= 200 && p.speed_red != effect_speed_red) {
        effect_speed_red = p.speed_red;
        settings_dirty = true;
    }
    fx_rb_speed  = p.rb_speed  > 100 ? 100 : p.rb_speed;
    fx_rb_sharp  = p.rb_sharp  > 100 ? 100 : p.rb_sharp;
    fx_clk_mode  = p.clk_mode ? 1 : 0;
    fx_clk_r = p.clk_r; fx_clk_g = p.clk_g; fx_clk_b = p.clk_b;
    fx_clk_speed = p.clk_speed > 100 ? 100 : p.clk_speed;
    fx_dirty = true;
    settings_dirty = true;      // повод для flushSettings() дойти и до /fx.cfg
}

// /fx.cfg: "FXP1", rb_speed, rb_sharp, clk_mode, clk_r, clk_g, clk_b, clk_speed, rsv.
void effectsFxFlush() {
    if (!fx_dirty) return;
    fx_dirty = false;
    File f = LittleFS.open(FX_FILE, "w");
    if (!f) { webLog("[EFF] Effect settings save failed"); return; }
    uint8_t h[12] = {'F', 'X', 'P', '1', fx_rb_speed, fx_rb_sharp, fx_clk_mode,
                     fx_clk_r, fx_clk_g, fx_clk_b, fx_clk_speed, 0};
    f.write(h, sizeof(h));
    f.close();
    webLog("[EFF] Effect settings saved");
}

static void fxLoad() {
    File f = LittleFS.open(FX_FILE, "r");
    if (!f) return;
    uint8_t h[12];
    if (f.read(h, sizeof(h)) == sizeof(h) && memcmp(h, "FXP1", 4) == 0) {
        fx_rb_speed  = h[4] > 100 ? 100 : h[4];
        fx_rb_sharp  = h[5] > 100 ? 100 : h[5];
        fx_clk_mode  = h[6] ? 1 : 0;
        fx_clk_r = h[7]; fx_clk_g = h[8]; fx_clk_b = h[9];
        fx_clk_speed = h[10] > 100 ? 100 : h[10];
    }
    f.close();
}

// =====================================================================
//                   ГЕНЕРАТОР И УПРАВЛЕНИЕ
// =====================================================================

static uint32_t effPeriodMs(uint8_t id) {
    switch (id) {
        case EFF_RAINBOW:
        // Текст: радуга течёт, а смена цвета с телефона должна доезжать сразу.
        case EFF_TEXT:    return 40;      // 25 к/с — движение должно быть плавным
        // Часы: радуге нужна плавность, одному цвету — только смена секунды.
        case EFF_CLOCK:   return fx_clk_mode == 1 ? 40 : 100;
        // EFF_TESTING содержимого этого буфера не читает вообще (см.
        // fillSectorIntoBuffer() в main.cpp) — период не важен.
        default:          return 200;     // скорость меняется медленно
    }
}

static void renderEffect(uint8_t id, uint8_t* buf) {
    uint16_t* out = (uint16_t*)buf;
    uint32_t  t   = millis();
    switch (id) {
        case EFF_SPEED:   effSpeed(out, t);   break;
        case EFF_RAINBOW: effRainbow(out, t); break;
        case EFF_CLOCK:   effClock(out, t);   break;
        case EFF_TEXT:    effText(out, t);    break;
        // EFF_TESTING рисуется в main.cpp прямо по ray, минуя этот буфер —
        // он никогда не читается, memset ниже просто держит его валидным.
        default: memset(buf, 0, FRAME_SIZE);  break;
    }
}

// Память эффекта: два кадра в PSRAM плюс, если нужно этому эффекту, полярная
// маска текста. Вспомогательный буфер выделяется ТОЛЬКО своему эффекту:
// держать его, пока крутится радуга, незачем.
static bool effAlloc(uint8_t id) {
    if (!eff_buf[0]) eff_buf[0] = (uint8_t*)ps_malloc(FRAME_SIZE);
    if (!eff_buf[1]) eff_buf[1] = (uint8_t*)ps_malloc(FRAME_SIZE);
    if (!eff_buf[0] || !eff_buf[1]) return false;

    if (id == EFF_SPEED || id == EFF_CLOCK) {
        if (!fx_alpha) fx_alpha = (uint8_t*)ps_malloc((size_t)SECTORS * LEDS_PER_SIDE);
        if (!fx_alpha) return false;
    } else if (fx_alpha) { free(fx_alpha); fx_alpha = nullptr; }
    return true;
}

static void effFree() {
    for (int i = 0; i < 2; i++) { if (eff_buf[i]) { free(eff_buf[i]); eff_buf[i] = nullptr; } }
    if (fx_alpha) { free(fx_alpha); fx_alpha = nullptr; }
}

static void effectsTask(void* pv) {
    (void)pv;
    for (;;) {
        uint32_t wait = 100;
        xSemaphoreTake(eff_mutex, portMAX_DELAY);
        uint8_t id = effect_id;
        // Пока лента не светится, считать кадры незачем: последний остаётся в
        // буфере и будет показан сразу, как только колесо раскрутится.
        if (id != EFF_NONE && eff_buf[0] && power_state == PWR_FULL) {
            uint8_t w = (uint8_t)(1 - eff_read);
            // Рендер держит указатель на кадр только на время сборки сектора
            // (сотни мкс). Дождаться его дешевле, чем рассуждать о вероятностях.
            for (int i = 0; i < 200 && render_in_fill; i++) taskYIELD();
            renderEffect(id, eff_buf[w]);
            eff_read    = w;
            frameBuffer = eff_buf[w];      // публикация: указатель выровнен, запись атомарна
            wait = effPeriodMs(id);
        }
        xSemaphoreGive(eff_mutex);
        vTaskDelay(pdMS_TO_TICKS(wait));
    }
}

void effectsInit() {
    for (int s = 0; s < SECTORS; s++) sec_hue[s] = (uint8_t)((s * 256) / 360);
    const float step = (LED_R_OUTER_MM - LED_R_INNER_MM) / (float)(LEDS_PER_SIDE - 1);
    for (int i = 0; i < LEDS_PER_SIDE; i++) {
        led_r_norm[i] = (LED_R_INNER_MM + i * step) / LED_R_OUTER_MM;
    }
    eff_mutex = xSemaphoreCreateMutex();
    textLoad();                          // после мьютекса: textApply() берёт его
    fxLoad();
    xTaskCreatePinnedToCore(effectsTask, "effects", 4096, NULL, 1, NULL, 0);
}

bool effectsStart(uint8_t id) {
    if (id == EFF_NONE) { effectsStop(); return true; }
    if (!effectValid(id)) return false;

    // Гасим ленту тем же приёмом, что и загрузчик файла: подменять frameBuffer
    // и освобождать старый под работающим рендером нельзя.
    bool was_loading = frame_loading;
    frame_loading = true;
    wakeRenderingTask();
    for (int i = 0; i < 1000 && render_in_fill;   i++) vTaskDelay(1);
    for (int i = 0; i <  200 && rendering_active; i++) vTaskDelay(1);

    xSemaphoreTake(eff_mutex, portMAX_DELAY);
    if (!effAlloc(id)) {
        effFree();
        xSemaphoreGive(eff_mutex);
        frame_loading = was_loading;
        webLog("[EFF] PSRAM alloc failed");
        return false;
    }
    // Старый буфер кадра — только если он не наш: повторный запуск эффекта
    // не должен освободить буфер, который мы тут же и опубликуем.
    uint8_t* oldBuf = (frameBuffer == eff_buf[0] || frameBuffer == eff_buf[1])
                    ? nullptr : frameBuffer;

    effect_id   = id;
    speed_shown = -1;                    // маски строятся заново: буфер мог смениться
    clock_shown = -2;
    fx_last_ms  = 0;
    eff_read    = 0;
    renderEffect(id, eff_buf[0]);        // первый кадр готовим до публикации

    totalFrames       = 1;               // эффект всегда один кадр: смешивать нечего
    frameDelay        = 0;
    currentFrameIndex = 0;
    frame_fmt         = FRAME_FMT_565;
    // Speed, Clock и Text рисуют текст, его надо читать с обеих сторон колеса —
    // fillSectorIntoBuffer() зеркалит для этого дальнюю сторону луча (см. там).
    // Rainbow и диагностический Testing — им обе стороны одинаковы.
    mirror_back_face  = (id == EFF_SPEED || id == EFF_CLOCK || id == EFF_TEXT);
    frameBuffer       = eff_buf[0];
    palette_gen++;
    if (oldBuf) free(oldBuf);
    xSemaphoreGive(eff_mutex);

    lastFrameSwitchTime = millis();
    newFrameReady      = true;
    force_stop_display = false;
    // request_play_flag здесь НЕ ставим: эффект запускают и автоматические
    // смены — слайдшоу и тик синхронной группы (OP_SYNC_TICK), который
    // прилетает каждый интервал сам по себе. Флаг значит «человек нажал Play»:
    // loop() считает его подтверждённой активностью (last_motion_ms,
    // last_play_ms) и поднимает DCDC №1. Ставился бы здесь — неподвижное колесо
    // в показе с эффектами не доходило бы ни до PWR_OFF, ни до сна. Запуск
    // человеком поднимает его сам: см. pending_effect_play в fileLoaderTask().
    frame_loading      = was_loading;
    // Взводим здесь, а не в обработчике HTTP: тот ставит лишь заявку, и запись
    // настроек могла бы успеть сохранить ещё прежний номер эффекта.
    settings_dirty     = true;
    webLogf("[EFF] %s", effectName(id));
    return true;
}

void effectsStop() {
    if (effect_id == EFF_NONE && eff_buf[0] == nullptr) return;

    bool was_loading = frame_loading;
    frame_loading = true;
    wakeRenderingTask();
    for (int i = 0; i < 1000 && render_in_fill; i++) vTaskDelay(1);

    xSemaphoreTake(eff_mutex, portMAX_DELAY);
    effect_id = EFF_NONE;
    // Рендер мог захватить наш буфер — снимаем указатель ДО освобождения.
    // nullptr он обрабатывает сам: гасит все диоды.
    if (frameBuffer == eff_buf[0] || frameBuffer == eff_buf[1]) {
        frameBuffer = nullptr;
        totalFrames = 1;
    }
    effFree();
    xSemaphoreGive(eff_mutex);

    // Запуск файла тоже проходит здесь: сохранённый эффект надо снять, иначе
    // после перезагрузки поднимется он, а не файл, который играли последним.
    settings_dirty = true;
    frame_loading  = was_loading;
}
