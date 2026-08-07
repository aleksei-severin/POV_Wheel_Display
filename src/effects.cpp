#include "config.h"
#include "effects.h"
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/semphr.h>
#include <math.h>

volatile uint8_t  effect_id        = EFF_NONE;
volatile int8_t   pending_effect   = -1;
volatile uint16_t effect_speed_red = 40;     // км/ч, при которых шрифт красный

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
static float   cos_s[SECTORS];        // косинус угла сектора
static float   sin_s[SECTORS];
static uint8_t sec_hue[SECTORS];      // сектор → 8-битный угол (0..255)
static int8_t  sin8t[256];            // синус по 8-битному углу, −127..127
static uint16_t fire_pal[256];        // палитра огня: чёрный → красный → жёлтый → белый
static float   led_r_norm[LEDS_PER_SIDE];  // радиус диода в долях внешнего

static inline int8_t sin8(int a) { return sin8t[(uint8_t)a]; }

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

// Быстрый генератор псевдослучайных чисел. rand() тянет за собой блокировку
// и деление, а огню нужно 15840 случайных чисел на кадр.
static uint32_t rnd_state = 0x1234567u;
static inline uint32_t rnd32() {
    rnd_state ^= rnd_state << 13;
    rnd_state ^= rnd_state >> 17;
    rnd_state ^= rnd_state << 5;
    return rnd_state;
}
static inline uint8_t  rnd8()  { return (uint8_t)(rnd32() >> 24); }
static inline uint16_t rnd16() { return (uint16_t)(rnd32() >> 16); }

const char* effectName(uint8_t id) {
    switch (id) {
        case EFF_SPEED:   return "Speed";
        case EFF_FIRE:    return "Fire";
        case EFF_RAINBOW: return "Rainbow";
        case EFF_PLASMA:  return "Plasma";
        case EFF_RIPPLE:  return "Ripples";
        case EFF_CLOCK:   return "Clock";
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

// =====================================================================
//                        ЭФФЕКТЫ
//  Каждый пишет 360×44 пикселей RGB565 в out[sector * LEDS_PER_SIDE + led].
// =====================================================================

// --- Радуга: тон по углу и по радиусу ---
// Чистое кольцо одного цвета на вращающемся колесе читается как мигание всей
// плоскости, поэтому тон меняется ещё и вдоль луча — получается спираль,
// которая честно выглядит движущейся.
static void effRainbow(uint16_t* out, uint32_t t) {
    uint8_t phase = (uint8_t)(t / 20);            // полный круг тона за ~5 с
    for (int s = 0; s < SECTORS; s++) {
        uint8_t base = (uint8_t)(sec_hue[s] + phase);
        uint16_t* row = out + s * LEDS_PER_SIDE;
        for (int i = 0; i < LEDS_PER_SIDE; i++) {
            int r, g, b;
            hsv2rgb((uint8_t)(base + i * 3), 255, 255, r, g, b);
            row[i] = pack565(r, g, b);
        }
    }
}

// --- Плазма: сумма четырёх синусов от угла и радиуса ---
// Периоды подобраны взаимно непериодичными, иначе узор быстро «схлопывается»
// в правильную решётку и перестаёт выглядеть живым.
static void effPlasma(uint16_t* out, uint32_t t) {
    int t1 = (int)(t / 30), t2 = (int)(t / 17), t3 = (int)(t / 43);
    for (int s = 0; s < SECTORS; s++) {
        int a = sec_hue[s];
        uint16_t* row = out + s * LEDS_PER_SIDE;
        for (int i = 0; i < LEDS_PER_SIDE; i++) {
            int r6 = i * 6;
            int v = sin8(a * 3 + t1) + sin8(r6 + t2)
                  + sin8(a + r6 + t3) + sin8(r6 - a * 2 + t1);
            int r, g, b;
            hsv2rgb((uint8_t)((v >> 2) + 128), 255, 255, r, g, b);
            row[i] = pack565(r, g, b);
        }
    }
}

// --- Концентрические волны ---
// Единственный радиально-симметричный эффект в наборе: он не зависит от угла,
// поэтому стоит абсолютно неподвижно и не выдаёт остаточную ошибку фазы ФАПЧ.
// Небольшая угловая модуляция добавлена, чтобы кольца всё же дышали.
static void effRipple(uint16_t* out, uint32_t t) {
    int phase = (int)(t / 12);
    int hue   = (int)(t / 60);
    int wob   = (int)(t / 25);
    for (int s = 0; s < SECTORS; s++) {
        int a = sec_hue[s];
        uint16_t* row = out + s * LEDS_PER_SIDE;
        int off = sin8(a * 2 + wob) >> 4;          // ±8: лёгкое «дыхание» колец
        for (int i = 0; i < LEDS_PER_SIDE; i++) {
            int w = sin8(i * 17 - phase + off);   // ~3 кольца на радиус
            // Только гребни: впадины остаются чёрными, иначе вместо колец
            // выходит равномерная засветка всего диска.
            int v = (w > 0) ? (w * 2) : 0;
            int r, g, b;
            hsv2rgb((uint8_t)(hue + i * 2), 255, (uint8_t)(v > 255 ? 255 : v), r, g, b);
            row[i] = pack565(r, g, b);
        }
    }
}

// --- Огонь ---
// Поле температур живёт между кадрами: остывание, перенос тепла от ступицы к
// ободу и искры у основания. Основание у СТУПИЦЫ, языки уходят к ободу —
// там угловое разрешение лучше всего, и мелкая игра языков видна.
static uint8_t* fire_heat = nullptr;     // 360×44, внутренняя память
static uint8_t  fire_tmp[SECTORS];

// Параметры подобраны на симуляции всего колеса, а не на глаз по одной колонке.
// Ключевая величина — РАЗБРОС температуры между секторами: пока искры рождались
// в одиночных секторах, он держался около 12 из 255, и огонь выглядел ровным
// свечением, а не языками. С дугами разброс вырос примерно до 45.
#define FIRE_COOL     8     // максимум остывания за кадр (0..7)
#define FIRE_SPARKS   2     // новых языков за кадр
#define FIRE_W_MIN   10     // полуширина языка в секторах
#define FIRE_W_SPAN  20
#define FIRE_BLUR     2     // проходов углового размытия

static void effFire(uint16_t* out, uint32_t t) {
    (void)t;
    const int H = LEDS_PER_SIDE;
    for (int s = 0; s < SECTORS; s++) {
        uint8_t* col = fire_heat + s * H;
        for (int i = 0; i < H; i++) {              // остывание
            int c = rnd8() % FIRE_COOL;
            col[i] = (col[i] > c) ? (uint8_t)(col[i] - c) : 0;
        }
        for (int i = H - 1; i >= 2; i--) {         // перенос тепла наружу
            col[i] = (uint8_t)(((int)col[i - 1] + col[i - 2] + col[i - 2]) / 3);
        }
    }
    // Искра рождается ДУГОЙ, а не одним сектором: язык пламени шире градуса, и
    // одиночный сектор размытие ниже просто съело бы. Профиль треугольный —
    // у дуги не должно быть ступеньки по краям.
    for (int k = 0; k < FIRE_SPARKS; k++) {
        int c   = rnd16() % SECTORS;
        int w   = FIRE_W_MIN + rnd8() % FIRE_W_SPAN;
        int amp = 190 + (rnd8() & 0x3F);
        int i   = rnd8() & 3;
        for (int d = -w; d <= w; d++) {
            int s = (c + d) % SECTORS;
            if (s < 0) s += SECTORS;
            uint8_t* px = fire_heat + s * H + i;
            int v = *px + amp * (w - (d < 0 ? -d : d)) / w;
            *px = (uint8_t)(v > 255 ? 255 : v);
        }
    }
    // Угловое размытие. Без него 360 колонок остывают независимо, и вместо
    // пламени выходит радиальный шум.
    for (int pass = 0; pass < FIRE_BLUR; pass++) {
        for (int i = 0; i < H; i++) {
            for (int s = 0; s < SECTORS; s++) fire_tmp[s] = fire_heat[s * H + i];
            for (int s = 0; s < SECTORS; s++) {
                int a = fire_tmp[(s + SECTORS - 1) % SECTORS];
                int b = fire_tmp[s];
                int c = fire_tmp[(s + 1) % SECTORS];
                fire_heat[s * H + i] = (uint8_t)((a + 2 * b + c) >> 2);
            }
        }
    }
    for (int s = 0; s < SECTORS; s++) {
        const uint8_t* col = fire_heat + s * H;
        uint16_t* row = out + s * H;
        for (int i = 0; i < H; i++) row[i] = fire_pal[col[i]];
    }
}

// --- Общая текстовая маска (скорость и подписи циферблата) ---
// Текст рисуется в декартову маску и уже оттуда переносится в полярный кадр:
// строить шрифт сразу в полярных координатах значит гнуть его вместе с сеткой.
#define MASKW 96
static uint8_t* text_mask  = nullptr;    // MASKW × MASKW, оттенки серого
static uint8_t* clock_base = nullptr;    // готовый циферблат: строится один раз

// Шрифт 5×7, по строке на байт (младшие 5 бит). 0–9, затем 'k','m','/','h'.
static const uint8_t FONT57[14][7] = {
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
};

static void maskGlyph(int gi, int x0, int y0, int sc) {
    for (int row = 0; row < 7; row++) {
        uint8_t bits = FONT57[gi][row];
        for (int col = 0; col < 5; col++) {
            if (!(bits & (0x10 >> col))) continue;
            for (int dy = 0; dy < sc; dy++) {
                int y = y0 + row * sc + dy;
                if (y < 0 || y >= MASKW) continue;
                uint8_t* p = text_mask + y * MASKW;
                for (int dx = 0; dx < sc; dx++) {
                    int x = x0 + col * sc + dx;
                    if (x >= 0 && x < MASKW) p[x] = 255;
                }
            }
        }
    }
}

static void maskText(const int* glyphs, int n, int cx, int y0, int sc) {
    int w = n * 5 * sc + (n - 1) * sc;
    int x = cx - w / 2;
    for (int k = 0; k < n; k++) {
        maskGlyph(glyphs[k], x, y0, sc);
        x += 6 * sc;
    }
}

// Переносит маску в полярный кадр. add=false — маска задаёт кадр целиком
// (скорость), add=true — ложится поверх уже нарисованного (подписи часов).
static void blitMask(uint16_t* out, int cr, int cg, int cb, bool add) {
    const float C = (MASKW - 1) * 0.5f;
    for (int s = 0; s < SECTORS; s++) {
        float cx = cos_s[s], sy = sin_s[s];
        uint16_t* row = out + s * LEDS_PER_SIDE;
        for (int i = 0; i < LEDS_PER_SIDE; i++) {
            float rp = led_r_norm[i] * C;
            float x  = C + rp * cx;
            // ПЛЮС, а не минус: в кадре ось y направлена вниз (тот же порядок,
            // что и в браузерном конвертере), и «математический» знак
            // переворачивал бы весь текст вверх ногами.
            float y  = C + rp * sy;
            int x0 = (int)x, y0 = (int)y;
            int m = 0;
            if (x0 >= 0 && y0 >= 0 && x0 < MASKW - 1 && y0 < MASKW - 1) {
                float fx = x - x0, fy = y - y0;
                const uint8_t* p0 = text_mask + y0 * MASKW + x0;
                const uint8_t* p1 = p0 + MASKW;
                // Билинейная выборка маски — бесплатное сглаживание краёв.
                float m0 = p0[0] * (1 - fx) + p0[1] * fx;
                float m1 = p1[0] * (1 - fx) + p1[1] * fx;
                m = (int)(m0 * (1 - fy) + m1 * fy);
            }
            if (m == 0) { if (!add) row[i] = 0; continue; }
            int r = cr * m / 255, g = cg * m / 255, b = cb * m / 255;
            if (add) {
                uint16_t v = row[i];
                r += ((v >> 11) & 0x1F) << 3;
                g += ((v >>  5) & 0x3F) << 2;
                b += ( v        & 0x1F) << 3;
            }
            row[i] = pack565(r, g, b);
        }
    }
}

// Мелкий шрифт 3×5 только для цифр циферблата. Пятёрка по ширине сюда не
// помещается: на радиусе подписей на один час приходится ~110 мм дуги, а «12»
// шрифтом 5×7 заняло бы почти всю их.
static const uint8_t FONT35[10][5] = {
    {0x7,0x5,0x5,0x5,0x7}, {0x2,0x6,0x2,0x2,0x7}, {0x7,0x1,0x7,0x4,0x7},
    {0x7,0x1,0x7,0x1,0x7}, {0x5,0x5,0x7,0x1,0x1}, {0x7,0x4,0x7,0x1,0x7},
    {0x7,0x4,0x7,0x5,0x7}, {0x7,0x1,0x1,0x1,0x1}, {0x7,0x5,0x7,0x5,0x7},
    {0x7,0x5,0x7,0x1,0x7},
};

static void maskNum35(int v, int cx, int cy, int sc) {
    int dig[2], nd = 0;
    if (v >= 10) dig[nd++] = v / 10;
    dig[nd++] = v % 10;
    int x  = cx - (nd * 3 * sc + (nd - 1) * sc) / 2;
    int y0 = cy - (5 * sc) / 2;
    for (int k = 0; k < nd; k++) {
        for (int row = 0; row < 5; row++) {
            uint8_t bits = FONT35[dig[k]][row];
            for (int col = 0; col < 3; col++) {
                if (!(bits & (0x4 >> col))) continue;
                for (int dy = 0; dy < sc; dy++) {
                    int yy = y0 + row * sc + dy;
                    if (yy < 0 || yy >= MASKW) continue;
                    uint8_t* p = text_mask + yy * MASKW;
                    for (int dx = 0; dx < sc; dx++) {
                        int xx = x + col * sc + dx;
                        if (xx >= 0 && xx < MASKW) p[xx] = 255;
                    }
                }
            }
        }
        x += 4 * sc;
    }
}

// --- Часы ---
// Сектор 0 смотрит вправо, номер сектора растёт по часовой стрелке (так же
// заданы углы в браузерном конвертере), поэтому 12 часов — это сектор 270.
// Куда именно на колесе попадёт «верх», определяет калибровка angle_offset.
static void clockHand(uint16_t* out, float deg, int i_from, int i_to,
                      float halfw_mm, int cr, int cg, int cb) {
    float centre = fmodf(270.0f + deg, 360.0f);
    if (i_from < 0) i_from = 0;
    if (i_to > LEDS_PER_SIDE) i_to = LEDS_PER_SIDE;
    for (int i = i_from; i < i_to; i++) {
        float r_mm = LED_R_INNER_MM + i * ((LED_R_OUTER_MM - LED_R_INNER_MM) / (LEDS_PER_SIDE - 1));
        // Постоянная физическая толщина: у ступицы стрелка занимает много
        // градусов, у обода — единицы. Иначе она была бы клином.
        float w = halfw_mm / r_mm * 57.2958f;
        if (w < 0.6f) w = 0.6f;
        int span = (int)(w + 1.0f);
        for (int d = -span; d <= span; d++) {
            float dist = fabsf((float)d) ;
            // Мягкий край шириной в один сектор: жёсткая граница на ободе
            // заметно «лестничная», а лишний сектор охвата почти бесплатен.
            float cov = w + 0.5f - dist;
            if (cov <= 0.0f) continue;
            if (cov > 1.0f) cov = 1.0f;
            int s = ((int)lroundf(centre) + d) % SECTORS;
            if (s < 0) s += SECTORS;
            uint16_t* px = out + s * LEDS_PER_SIDE + i;
            int r = (int)(cr * cov), g = (int)(cg * cov), b = (int)(cb * cov);
            // Складываем, а не заменяем: на пересечении стрелок иначе побеждала
            // бы нарисованная последней.
            int orr = ((*px >> 11) & 0x1F) << 3;
            int org = ((*px >>  5) & 0x3F) << 2;
            int orb = ( *px        & 0x1F) << 3;
            *px = pack565(orr + r, org + g, orb + b);
        }
    }
}

// Циферблат целиком — деления и подписи. Он не меняется никогда, поэтому
// строится один раз при запуске эффекта, а каждый кадр только копируется:
// перерисовывать двенадцать чисел десять раз в секунду не за что.
static void buildClockBase(uint16_t* base) {
    memset(base, 0, (size_t)SECTORS * LEDS_PER_SIDE * 2);
    // Деления одинаковой длины: раньше «12» выделялось длинной риской, но с
    // подписями это уже лишнее, а длинная риска налезала бы на само число.
    for (int k = 0; k < 12; k++) {
        clockHand(base, k * 30.0f, 39, LEDS_PER_SIDE, 5.0f, 90, 90, 90);
    }
    // Подписи ставим внутрь от делений: те занимают радиус 43.9…47.5 маски,
    // число высотой 10 px с центром на 37 укладывается в 32…42 — с зазором.
    memset(text_mask, 0, (size_t)MASKW * MASKW);
    const float C = (MASKW - 1) * 0.5f;
    const float R = 37.0f;
    for (int k = 1; k <= 12; k++) {
        float a = (270.0f + k * 30.0f) * (float)M_PI / 180.0f;
        maskNum35(k, (int)lroundf(C + R * cosf(a)), (int)lroundf(C + R * sinf(a)), 2);
    }
    blitMask(base, 170, 170, 190, true);
}

static void effClock(uint16_t* out, uint32_t t) {
    (void)t;
    memcpy(out, clock_base, (size_t)SECTORS * LEDS_PER_SIDE * 2);

    int hh = 0, mm = 0, ss = 0;
    if (!localClock(hh, mm, ss)) return;   // время неизвестно — только циферблат

    float sec_deg  = ss * 6.0f;
    float min_deg  = mm * 6.0f + ss * 0.1f;
    float hour_deg = (hh % 12) * 30.0f + mm * 0.5f;

    clockHand(out, hour_deg, 0, 26, 7.0f, 255, 170,  40);   // часовая — янтарная
    clockHand(out, min_deg,  0, 38, 5.0f, 220, 220, 255);   // минутная — белая
    clockHand(out, sec_deg,  0, 42, 2.5f, 255,  40,  40);   // секундная — тонкая красная
}

// --- Скорость ---
static void effSpeed(uint16_t* out, uint32_t t) {
    (void)t;
    int v = (int)(currentSpeedKmh() + 0.5f);
    if (v > 999) v = 999;

    memset(text_mask, 0, (size_t)MASKW * MASKW);

    int dig[3], nd = 0;
    if (v >= 100) { dig[nd++] = v / 100; dig[nd++] = (v / 10) % 10; dig[nd++] = v % 10; }
    else if (v >= 10) { dig[nd++] = v / 10; dig[nd++] = v % 10; }
    else { dig[nd++] = v; }

    // Число ставим НАД центром, подпись — под ним: в середине диска дырка
    // радиусом 49 мм (это 8.5 px маски от центра), и всё, что её накроет,
    // потеряет середину. Отсюда зазоры: низ числа на 9.5 px выше центра,
    // верх подписи на 10.5 px ниже.
    int sc = (nd >= 3) ? 3 : 4;
    maskText(dig, nd, MASKW / 2, 38 - 7 * sc, sc);
    static const int UNIT[4] = {10, 11, 12, 13};      // k m / h
    maskText(UNIT, 4, MASKW / 2, 58, 2);

    // Цвет: зелёный на малой скорости, красный на effect_speed_red и выше.
    // Идём по тону 85→0, поэтому переход проходит через жёлтый сам собой.
    float red = (float)effect_speed_red;
    if (red < 1.0f) red = 1.0f;
    float k = (float)v / red;
    if (k > 1.0f) k = 1.0f;
    int rr, gg, bb;
    hsv2rgb((uint8_t)(85.0f * (1.0f - k) + 0.5f), 255, 255, rr, gg, bb);

    blitMask(out, rr, gg, bb, false);
}

// =====================================================================
//                   ГЕНЕРАТОР И УПРАВЛЕНИЕ
// =====================================================================

static uint32_t effPeriodMs(uint8_t id) {
    switch (id) {
        case EFF_FIRE:
        case EFF_RAINBOW:
        case EFF_PLASMA:
        case EFF_RIPPLE:  return 40;      // 25 к/с — движение должно быть плавным
        case EFF_CLOCK:   return 100;     // секундная стрелка
        default:          return 200;     // скорость меняется медленно
    }
}

static void renderEffect(uint8_t id, uint8_t* buf) {
    uint16_t* out = (uint16_t*)buf;
    uint32_t  t   = millis();
    switch (id) {
        case EFF_SPEED:   effSpeed(out, t);   break;
        case EFF_FIRE:    effFire(out, t);    break;
        case EFF_RAINBOW: effRainbow(out, t); break;
        case EFF_PLASMA:  effPlasma(out, t);  break;
        case EFF_RIPPLE:  effRipple(out, t);  break;
        case EFF_CLOCK:   effClock(out, t);   break;
        default: memset(buf, 0, FRAME_SIZE);  break;
    }
}

// Память эффекта: два кадра в PSRAM плюс, если нужно этому эффекту, поле
// температур или маска шрифта во внутренней памяти — к ним идёт много мелких
// обращений, и PSRAM на таком доступе медленна. Вспомогательные буферы
// выделяются ТОЛЬКО своему эффекту: держать 25 кБ внутреннего heap ради огня,
// пока крутится радуга, незачем — эта память нужна WiFi.
static bool effAlloc(uint8_t id) {
    if (!eff_buf[0]) eff_buf[0] = (uint8_t*)ps_malloc(FRAME_SIZE);
    if (!eff_buf[1]) eff_buf[1] = (uint8_t*)ps_malloc(FRAME_SIZE);
    if (!eff_buf[0] || !eff_buf[1]) return false;

    if (id == EFF_FIRE) {
        if (!fire_heat) fire_heat = (uint8_t*)calloc(SECTORS * LEDS_PER_SIDE, 1);
        if (!fire_heat) return false;
    } else if (fire_heat) { free(fire_heat); fire_heat = nullptr; }

    // Маска нужна и цифрам скорости, и подписям циферблата.
    if (id == EFF_SPEED || id == EFF_CLOCK) {
        if (!text_mask) text_mask = (uint8_t*)malloc((size_t)MASKW * MASKW);
        if (!text_mask) return false;
    } else if (text_mask) { free(text_mask); text_mask = nullptr; }

    // Циферблат не меняется никогда — держим готовый кадр и копируем его.
    if (id == EFF_CLOCK) {
        if (!clock_base) clock_base = (uint8_t*)ps_malloc(FRAME_SIZE);
        if (!clock_base) return false;
        buildClockBase((uint16_t*)clock_base);
    } else if (clock_base) { free(clock_base); clock_base = nullptr; }
    return true;
}

static void effFree() {
    for (int i = 0; i < 2; i++) { if (eff_buf[i]) { free(eff_buf[i]); eff_buf[i] = nullptr; } }
    if (fire_heat)  { free(fire_heat);  fire_heat  = nullptr; }
    if (text_mask)  { free(text_mask);  text_mask  = nullptr; }
    if (clock_base) { free(clock_base); clock_base = nullptr; }
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
    for (int s = 0; s < SECTORS; s++) {
        float a  = s * (float)M_PI / 180.0f;
        cos_s[s] = cosf(a);
        sin_s[s] = sinf(a);
        sec_hue[s] = (uint8_t)((s * 256) / 360);
    }
    for (int i = 0; i < 256; i++) {
        sin8t[i] = (int8_t)lroundf(127.0f * sinf(i * 2.0f * (float)M_PI / 256.0f));
    }
    const float step = (LED_R_OUTER_MM - LED_R_INNER_MM) / (float)(LEDS_PER_SIDE - 1);
    for (int i = 0; i < LEDS_PER_SIDE; i++) {
        led_r_norm[i] = (LED_R_INNER_MM + i * step) / LED_R_OUTER_MM;
    }
    // Палитра огня: три отрезка — разгорание до красного, до жёлтого, до белого.
    for (int h = 0; h < 256; h++) {
        int r, g, b;
        if (h < 85)       { r = h * 3;             g = 0;               b = 0; }
        else if (h < 170) { r = 255;               g = (h - 85) * 3;    b = 0; }
        else              { r = 255;               g = 255;             b = (h - 170) * 3; }
        fire_pal[h] = pack565(r, g > 255 ? 255 : g, b > 255 ? 255 : b);
    }
    eff_mutex = xSemaphoreCreateMutex();
    xTaskCreatePinnedToCore(effectsTask, "effects", 4096, NULL, 1, NULL, 0);
}

bool effectsStart(uint8_t id) {
    if (id == EFF_NONE) { effectsStop(); return true; }
    if (id >= EFF_COUNT) return false;

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

    effect_id = id;
    if (fire_heat) memset(fire_heat, 0, (size_t)SECTORS * LEDS_PER_SIDE);
    eff_read = 0;
    renderEffect(id, eff_buf[0]);        // первый кадр готовим до публикации

    totalFrames       = 1;               // эффект всегда один кадр: смешивать нечего
    frameDelay        = 0;
    currentFrameIndex = 0;
    frame_fmt         = FRAME_FMT_565;
    frameBuffer       = eff_buf[0];
    palette_gen++;
    if (oldBuf) free(oldBuf);
    xSemaphoreGive(eff_mutex);

    lastFrameSwitchTime = millis();
    newFrameReady      = true;
    force_stop_display = false;
    request_play_flag  = true;           // поднять питание, если лента погашена
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
