#include "beeper.h"
#include "config.h"
#include "driver/gpio.h"
#include "driver/ledc.h"
#include "esp_timer.h"
#include "esp_rom_gpio.h"
#include "soc/gpio_reg.h"
#include "soc/gpio_sig_map.h"
#include <math.h>

// LEDC в проекте больше никто не занимает; берём последние номера, чтобы не
// столкнуться с ledcAttach/analogWrite, если они когда-нибудь появятся.
#define BEEP_LEDC_TIMER    LEDC_TIMER_3
#define BEEP_LEDC_CHANNEL  LEDC_CHANNEL_7
#define BEEP_LEDC_BITS     10
// Выходной сигнал канала в GPIO-матрице (на S3 есть только low-speed каналы).
#define BEEP_LEDC_SIG      (LEDC_LS_SIG_OUT0_IDX + BEEP_LEDC_CHANNEL)

static esp_timer_handle_t beep_timer = nullptr;
static portMUX_TYPE       beep_mux   = portMUX_INITIALIZER_UNLOCKED;
static bool               beep_on    = false;   // под beep_mux
static int64_t            beep_end   = 0;       // esp_timer_get_time() конца тона, под beep_mux

// Подключение через ROM-функцию матрицы: она не во флеше и годится для ISR.
// SIG_GPIO_OUT_IDX возвращает вывод обычному GPIO, а выходной регистр обоих
// выводов выставлен в 0 один раз в beeperInit() и больше не меняется.
static inline void IRAM_ATTR beepConnect() {
    esp_rom_gpio_connect_out_signal(PIN_PIEZO_A, BEEP_LEDC_SIG, false, false);
    esp_rom_gpio_connect_out_signal(PIN_PIEZO_B, BEEP_LEDC_SIG, true,  false);
}

static inline void IRAM_ATTR beepDisconnect() {
    esp_rom_gpio_connect_out_signal(PIN_PIEZO_A, SIG_GPIO_OUT_IDX, false, false);
    esp_rom_gpio_connect_out_signal(PIN_PIEZO_B, SIG_GPIO_OUT_IDX, false, false);
}

// Колбэк esp_timer (задача esp_timer, ядро 0), а ISR Холла — на ядре 1.
// Гасим тон, только если его срок действительно вышел: событие, пришедшее
// за миг до этого, уже сдвинуло beep_end и перезапустило таймер — тогда
// выключит следующий вызов, а не этот, обрезав свежий тон.
static void beepOffCb(void*) {
    portENTER_CRITICAL(&beep_mux);
    if (beep_on && esp_timer_get_time() >= beep_end) {
        beepDisconnect();
        beep_on = false;
    }
    portEXIT_CRITICAL(&beep_mux);
}

void IRAM_ATTR beeperTrigger() {
    if (!beep_timer) return;
    int64_t now = esp_timer_get_time();
    // _SAFE: вызывается и из ISR Холла, и из задачи (самопроверка).
    portENTER_CRITICAL_SAFE(&beep_mux);
    beep_end = now + PIEZO_BEEP_US;
    if (!beep_on) {
        beepConnect();
        beep_on = true;
    }
    portEXIT_CRITICAL_SAFE(&beep_mux);
    // esp_timer_stop/start_once лежат в IRAM и защищены собственной
    // спин-блокировкой — из прерывания их вызывать можно. Взведённый таймер
    // повторно не стартует (ESP_ERR_INVALID_STATE), поэтому сначала stop;
    // его ошибку для невзведённого таймера игнорируем.
    esp_timer_stop(beep_timer);
    esp_timer_start_once(beep_timer, PIEZO_BEEP_US);
}

// Самопроверка выходного каскада. Входной буфер обоих выводов включён, и
// GPIO_IN читает реальный уровень на площадках. Тон запускает тот же
// beeperTrigger(), что и ISR Холла, гасит тот же таймер — проверяется весь
// путь, кроме пайки и самого пьезо.
//
// Во время тона ровно один вывод в HIGH, в тишине оба LOW. Отсюда признаки:
// «оба LOW дольше полного периода» — тон выключен (порог длиннее
// полупериода, так что с паузой меандра это не спутать даже при неисправной
// инверсии); «оба HIGH» надолго — IO2 не инвертирован.
//
// Именно надолго, а не вообще. Пьезо — конденсатор между двумя выводами, и на
// каждом переключении он затягивает оба фронта: доли микросекунды оба вывода
// выше порога входа одновременно (на этой плате ~40 выборок «оба HIGH» из
// ~4500 за 5 мс, каждая не длиннее шага выборки ~1.1 мкс) — при исправной
// противофазе. Без инверсии «оба HIGH» длится полпериода подряд. Поэтому
// судим по самому длинному отрезку, а число таких выборок пишем в лог как
// признак нагрузки: 0 — к выводам, похоже, ничего не подключено.
static void beeperSelfTest() {
    const uint32_t mA     = 1u << PIN_PIEZO_A;
    const uint32_t mB     = 1u << PIN_PIEZO_B;
    const int64_t  off_us = 1000000 / PIEZO_FREQ_HZ;
    uint32_t rises_a = 0, rises_b = 0, both_hi = 0;
    int64_t  first_rise = 0, last_rise = 0, lo_since = 0, t_off = 0;
    int64_t  hi_since = 0, hi_run_max = 0;   // самый длинный отрезок «оба HIGH», мкс

    // Планировщик ядра 1 на время замера стоит: вытеснение на полпериода
    // съело бы фронты и занизило частоту. Прерывания работают, а таймер
    // отключения живёт в задаче esp_timer на ядре 0 — ему это не мешает.
    vTaskSuspendAll();
    int64_t t0 = esp_timer_get_time();
    beeperTrigger();
    uint32_t prev = REG_READ(GPIO_IN_REG);
    while (true) {
        int64_t  t  = esp_timer_get_time();
        uint32_t in = REG_READ(GPIO_IN_REG);
        bool a = in & mA, b = in & mB;
        if (a && !(prev & mA)) { if (rises_a++ == 0) first_rise = t; last_rise = t; }
        if (b && !(prev & mB)) rises_b++;
        if (a && b) {
            both_hi++;
            if (hi_since == 0) hi_since = t;
        } else if (hi_since) {
            // Оценка сверху: отрезок плюс до одного шага выборки
            if (t - hi_since > hi_run_max) hi_run_max = t - hi_since;
            hi_since = 0;
        }
        if (a || b)                     lo_since = 0;
        else if (lo_since == 0)         lo_since = t;
        else if (t - lo_since > off_us) { t_off = lo_since; break; }
        prev = in;
        if (t - t0 > 2 * PIEZO_BEEP_US + 10000) break;   // тон так и не выключился
    }
    xTaskResumeAll();

    float freq = (rises_a > 1 && last_rise > first_rise)
               ? (float)(rises_a - 1) * 1e6f / (float)(last_rise - first_rise) : 0.0f;
    int32_t dur = t_off ? (int32_t)(t_off - t0) : -1;
    // Перекрытие на фронте должно быть много короче полупериода: 1/8 периода
    // (7 мкс на 18 кГц) на порядок больше того, что даёт ёмкость пьезо, и
    // вчетверо меньше того, что даёт сломанная инверсия.
    bool ok = hi_run_max * 8 < off_us &&
              rises_a > 1 && (rises_a > rises_b ? rises_a - rises_b : rises_b - rises_a) <= 1 &&
              fabsf(freq - PIEZO_FREQ_HZ) < PIEZO_FREQ_HZ * 0.02f &&
              dur >= 0 && abs(dur - PIEZO_BEEP_US) <= PIEZO_BEEP_US / 10;

    // Строка лога — 96 байт вместе с 20-символьной меткой времени.
    // load — выборки «оба HIGH» на фронтах (след ёмкости пьезо), A/B — фронты
    // на IO1/IO2, run — самый длинный отрезок «оба HIGH», «>» — тон не выключился.
    char line[80];
    if (ok) {
        snprintf(line, sizeof(line), "[SYS] Piezo self-test OK: %.0f Hz, antiphase, %.1f ms, load %u",
                 freq, dur / 1000.0f, (unsigned)both_hi);
    } else {
        int32_t shown = dur >= 0 ? dur : (int32_t)(2 * PIEZO_BEEP_US + 10000);
        snprintf(line, sizeof(line), "[ERR] Piezo self-test FAIL: A%u B%u run%uus %.0fHz %s%.1fms",
                 (unsigned)rises_a, (unsigned)rises_b, (unsigned)hi_run_max, freq,
                 dur >= 0 ? "" : ">", shown / 1000.0f);
    }
    webLog(line);
}

void beeperInit() {
    // «Тишина» — оба вывода выходы в LOW: на пьезо ноль, а не висящий в
    // воздухе конденсатор. Входной буфер нужен самопроверке, выходу он не мешает.
    gpio_set_level((gpio_num_t)PIN_PIEZO_A, 0);
    gpio_set_level((gpio_num_t)PIN_PIEZO_B, 0);
    gpio_config_t io = {};
    io.pin_bit_mask = (1ULL << PIN_PIEZO_A) | (1ULL << PIN_PIEZO_B);
    io.mode         = GPIO_MODE_INPUT_OUTPUT;
    gpio_config(&io);

    // Меандр 50 %. Источник — APB: он остаётся 80 МГц при любой частоте CPU
    // (см. setCpuFreqForPower), так что тон не плывёт при переключении 80/240.
    ledc_timer_config_t tc = {};
    tc.speed_mode      = LEDC_LOW_SPEED_MODE;
    tc.duty_resolution = (ledc_timer_bit_t)BEEP_LEDC_BITS;
    tc.timer_num       = BEEP_LEDC_TIMER;
    tc.freq_hz         = PIEZO_FREQ_HZ;
    tc.clk_cfg         = LEDC_USE_APB_CLK;
    if (ledc_timer_config(&tc) != ESP_OK) {
        webLog("[ERR] Piezo: LEDC timer config failed");
        return;
    }

    ledc_channel_config_t cc = {};
    cc.gpio_num   = PIN_PIEZO_A;
    cc.speed_mode = LEDC_LOW_SPEED_MODE;
    cc.channel    = BEEP_LEDC_CHANNEL;
    cc.timer_sel  = BEEP_LEDC_TIMER;
    cc.duty       = 1u << (BEEP_LEDC_BITS - 1);   // ровно половина периода
    cc.hpoint     = 0;
    if (ledc_channel_config(&cc) != ESP_OK) {
        webLog("[ERR] Piezo: LEDC channel config failed");
        return;
    }
    // ledc_channel_config() сразу подключил канал к IO1 и переключил вывод
    // в чистый выход — возвращаем его GPIO и снова включаем входной буфер.
    beepDisconnect();
    gpio_set_direction((gpio_num_t)PIN_PIEZO_A, GPIO_MODE_INPUT_OUTPUT);

    esp_timer_create_args_t ta = {};
    ta.callback = beepOffCb;
    ta.name     = "beep";
    if (esp_timer_create(&ta, &beep_timer) != ESP_OK) {
        beep_timer = nullptr;   // без таймера тон некому выключить — не включаем вовсе
        webLog("[ERR] Piezo: esp_timer create failed");
        return;
    }

    beeperSelfTest();
}
