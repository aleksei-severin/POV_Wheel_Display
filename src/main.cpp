#include "config.h"
#include "network.h"
#include <WiFi.h>

#include <Arduino.h>
#include <LittleFS.h>
#include <vector>
#include <ESPAsyncWebServer.h>
#include "driver/gpio.h"
#include "driver/spi_master.h"
#include "esp_heap_caps.h"
#include "esp_system.h"
#include "esp_sleep.h"
#include "esp_task_wdt.h"

// Экспортируем сервер из network.cpp для добавления нового эндпоинта
extern AsyncWebServer server;

// --- ИНИЦИАЛИЗАЦИЯ ГЛОБАЛЬНЫХ ПЕРЕМЕННЫХ ---
volatile uint8_t global_brightness = 8; // единицы SK9822 (0–31)

RTC_DATA_ATTR uint8_t min_brightness = 1;  // единицы SK9822 (1–30)
RTC_DATA_ATTR uint8_t max_brightness = 30; // единицы SK9822 (1–30)
RTC_DATA_ATTR volatile int global_angle_offset = 90;

uint8_t* frameBuffer = nullptr;

// Глобальные переменные для поддержки GIF анимаций
uint32_t currentFrameIndex = 0;
uint32_t totalFrames = 1;
uint16_t frameDelay = 100;
uint32_t lastFrameSwitchTime = 0;

volatile bool newFrameReady = false;
volatile bool ota_in_progress = false; // блокирует рендеринг на время OTA-обновления

std::vector<String> savedFiles;

// --- ПИТАНИЕ ---
volatile PowerState power_state = PWR_OFF;
bool peripherals_active = false;               // true когда включён хотя бы DCDC №1
RTC_DATA_ATTR bool force_stop_display = false;
volatile uint32_t last_dcdc_off_time = 0;
volatile uint32_t last_dcdc_on_time  = 0;      // Момент включения питания (защита от раннего выключения)
// Последняя ПОДТВЕРЖДЁННАЯ активность: запрос Play или реально раскрутившееся колесо.
// Отдельно от last_dcdc_on_time: вибродатчик срабатывает от любой тряски, и если
// считать каждую попытку раскрутки активностью, устройство в рюкзаке не уснёт никогда.
static uint32_t last_motion_ms = 0;
// До этого момента вибрацию игнорируем — предыдущая попытка не дала вращения
static uint32_t vibration_block_until = 0;
static bool     spinup_from_vibration = false;
#define VIBRATION_RETRY_MS  30000UL
volatile uint32_t last_web_activity_time = 0;  // Отслеживание активности в Web UI
volatile bool     wakeup_event       = false;  // Импульс с вибродатчика
volatile bool     request_play_flag  = false;

volatile float last_lux_value = 0.0f;          // Последнее усреднённое показание ALS-PT19 (лк)

// =====================================================================
//                      ДАТЧИКИ ХОЛЛА (6 штук)
// =====================================================================
// Один неподвижный магнит на вилке + шесть датчиков на лучах:
// за оборот приходит 6 событий, по одному на каждые 60°.
//
// Скорость считается по интервалу между двумя срабатываниями ОДНОГО
// датчика — это ровно один оборот, и погрешность установки датчиков
// на неё не влияет вообще. Каждый из шести датчиков даёт своё измерение
// оборота, смещённое на 60°, поэтому оценка скорости обновляется
// 6 раз за оборот вместо одного.
static const uint8_t HALL_PIN[HALL_COUNT] = HALL_PIN_LIST;

// Антидребезг: между соседними датчиками не может пройти меньше 1.5 мс
// (это ≈6600 об/мин), один и тот же датчик не может сработать чаще 10 мс.
#define HALL_MIN_GAP_US   1500UL
#define HALL_MIN_REV_US  10000UL

static volatile uint32_t hall_last_us[HALL_COUNT] = {};  // micros() последнего события каждого датчика
static volatile uint8_t  hall_seen_mask   = 0;           // какие датчики уже срабатывали
static volatile uint8_t  hall_active_mask = 0;           // какие датчики сейчас запитаны
static volatile uint8_t  hall_prev_idx    = 0xFF;        // индекс предыдущего сработавшего датчика

volatile uint32_t last_hall_time  = 0;   // micros() последнего события (любой датчик)
volatile uint32_t rotation_period = 0;   // Период полного оборота, мкс
volatile int8_t   rotation_dir    = 1;   // +1 — сектор растёт со временем, -1 — убывает
static volatile uint8_t  last_hall_idx = 0;   // Индекс датчика последнего события
static volatile uint32_t last_hall_rev = 0;   // Период оборота по этому датчику (0 = невалиден)
static volatile uint32_t hall_seq      = 0;   // Счётчик событий
static volatile int8_t   dir_score     = 0;   // Голосование за направление, [-4..4]

// Калибровка углового положения датчиков.
// hall_cal[k] — поправка в градусах к фазе, снимаемой с датчика k
// (hall_cal[0] всегда 0 — это опорный датчик). Компенсирует разброс
// установки датчиков и порога срабатывания: без неё привязка фазы к
// разным датчикам вносила бы «дрожание» картинки 6 раз за оборот.
#define HALL_CAL_MAGIC   0x43414C35UL   // "CAL5"
#define HALL_CAL_MIN_N   8              // Столько замеров на датчик нужно для готовности
RTC_DATA_ATTR static uint32_t rtc_cal_magic = 0;
RTC_DATA_ATTR static float    rtc_hall_cal[HALL_COUNT];
RTC_DATA_ATTR static uint8_t  rtc_hall_cal_n[HALL_COUNT];
static bool hall_cal_ready = false;

// Оценка вращения (обновляется renderingTask, читается loop()/web)
static volatile float rotor_omega = 0.0f;  // град/мкс, знаковая
static volatile float rotor_alpha = 0.0f;  // град/мкс², знаковая

// --- ЕSP-IDF SPI DMA для SK9822 ---
// 528 диодов: старт-фрейм 4 байта + 528×4 байта данных + end-frame N/2 бит.
#define SK9822_END_FRAMES (NUM_LEDS / 2)
#define SK9822_BUF_SIZE   (4 + NUM_LEDS * 4 + SK9822_END_FRAMES)

// RMS за текущий оборот: среднее нормированное потребление тока (0.0–1.0).
// Считается от нередуцированного bri_level — показывает реальную нагрузку.
static float rms_accum = 0.0f;

static spi_device_handle_t sk9822_spi   = nullptr;
static uint8_t*            dma_buf[2]   = {nullptr, nullptr}; // Два буфера для ping-pong DMA
static uint8_t*            dma_tx_buffer = nullptr;           // = dma_buf[0], для служебных заливок
static spi_transaction_t   spi_trans[2] = {};                 // Предвыделенные транзакции (не на стеке)
static SemaphoreHandle_t   hallSemaphore = nullptr;
static SemaphoreHandle_t   dmaMutex      = nullptr;
SemaphoreHandle_t          fileLoaderSemaphore = nullptr;
String                     pendingFilePath;

// Слайдшоу: переключение файлов по таймеру
// Флаг и интервал живут в RTC — восстанавливаются после deep sleep
RTC_DATA_ATTR bool     slideshowActive    = false;
RTC_DATA_ATTR uint32_t slideInterval      = 10000; // мс между сменами (по умолчанию 10 с)
uint32_t slideLastSwitch    = 0;
int      slideCurrentIndex  = -1;    // индекс текущего файла в savedFiles (-1 = не запущен)

RTC_DATA_ATTR volatile float global_gamma         = 2.5f;
RTC_DATA_ATTR volatile float global_saturation    = 1.5f;
RTC_DATA_ATTR volatile float global_contrast      = 5.0f;
RTC_DATA_ATTR volatile float global_r_gain        = 100.0f; // 0..100 %, 100 = без изменений
RTC_DATA_ATTR volatile float global_g_gain        = 65.0f;
RTC_DATA_ATTR volatile float global_b_gain        = 75.0f;
RTC_DATA_ATTR volatile uint16_t wheel_circumference = 2355;
RTC_DATA_ATTR volatile int16_t  global_spoke_offset = 0;   // мм, 0 = лучи из центра
RTC_DATA_ATTR volatile bool     global_arm_reverse  = false; // порядок лучей в цепочке
RTC_DATA_ATTR volatile float    global_abl_limit    = 40.0f; // ABL: 0–100 %, 100 = без ограничения
volatile float                  global_abl_rms      = 0.0f;  // RMS загрузка тока 0.0–1.0
volatile uint8_t                global_effective_brightness = 8;
uint8_t lut_r[256];
uint8_t lut_g[256];
uint8_t lut_b[256];

// --- КЕШ ТЕЛЕМЕТРИИ ПИТАНИЯ ---
// Обновляется из loop() (Core 1), читается из /battery (Core 0).
// Поля — примитивы ≤32 бит, запись атомарна на Xtensa, мьютекс не нужен.
struct PowerCache {
    int16_t vbat_mv = 0;
    int16_t vusb_mv = 0;
    uint8_t chg     = 0;      // 0 = разряд, 1 = идёт заряд, 2 = заряжено
    bool    usb     = false;
};
static PowerCache pwr_cache;

// =====================================================================
//                         ПРЕРЫВАНИЯ
// =====================================================================

// Вибродатчик HX 0805-C2: подтянут к неотключаемой 3V3, при тряске даёт импульсы LOW.
// Реагируем только когда питание снято — это сигнал «пора просыпаться».
void IRAM_ATTR vibrationInterruptHandler() {
    if (power_state == PWR_OFF) {
        wakeup_event = true;
    }
}

// Общий обработчик для всех шести датчиков Холла. Индекс датчика передаётся
// через attachInterruptArg. Внутри только целочисленная арифметика —
// на Xtensa нельзя пользоваться FPU в контексте прерывания.
void IRAM_ATTR hallInterruptHandler(void* arg) {
    uint32_t k = (uint32_t)arg;

    // Датчик обесточен (или не должен учитываться) — игнорируем.
    if (!(hall_active_mask & (1u << k))) return;

    uint32_t now = micros();

    // Глобальный антидребезг между любыми двумя событиями
    if (last_hall_time != 0 && (uint32_t)(now - last_hall_time) < HALL_MIN_GAP_US) return;

    // Антидребезг конкретного датчика: полный оборот не быстрее HALL_MIN_REV_US
    bool     seen = (hall_seen_mask & (1u << k)) != 0;
    uint32_t rev  = (uint32_t)(now - hall_last_us[k]);
    if (seen && rev < HALL_MIN_REV_US) return;

    // --- Направление вращения по порядку срабатывания датчиков ---
    // Следующим срабатывает датчик того луча, который находится на 60° позади
    // текущего, то есть его сектор сейчас на arm_step меньше. Отсюда:
    //   прирост сектора за событие  δ = -(k' - k) · arm_step
    // Знак δ и есть направление (rotation_dir = +1, когда сектор растёт).
    if (hall_prev_idx < HALL_COUNT) {
        int8_t  s    = global_arm_reverse ? -1 : 1;   // arm_step / 60
        uint8_t step = (uint8_t)((k + HALL_COUNT - hall_prev_idx) % HALL_COUNT);
        int8_t  vote = 0;
        if      (step == 1)              vote = -s;   // индекс растёт
        else if (step == HALL_COUNT - 1) vote =  s;   // индекс убывает
        if (vote > 0 && dir_score <  4) dir_score++;
        if (vote < 0 && dir_score > -4) dir_score--;
        if      (dir_score >=  2) rotation_dir =  1;
        else if (dir_score <= -2) rotation_dir = -1;
    }

    last_hall_rev = seen ? rev : 0;
    if (last_hall_rev) rotation_period = last_hall_rev;

    hall_last_us[k] = now;
    hall_seen_mask |= (1u << k);
    hall_prev_idx   = (uint8_t)k;
    last_hall_idx   = (uint8_t)k;
    last_hall_time  = now;
    hall_seq++;

    if (hallSemaphore) {
        BaseType_t xHigherPriorityTaskWoken = pdFALSE;
        xSemaphoreGiveFromISR(hallSemaphore, &xHigherPriorityTaskWoken);
        portYIELD_FROM_ISR(xHigherPriorityTaskWoken);
    }
}

// =====================================================================
//                      КАЛИБРОВКА ДАТЧИКОВ
// =====================================================================

static void loadHallCalibration() {
    if (rtc_cal_magic == HALL_CAL_MAGIC) {
        // Данные пережили deep sleep — берём их
    } else {
        size_t got = prefs.getBytes("hallcal", (void*)rtc_hall_cal, sizeof(rtc_hall_cal));
        if (got != sizeof(rtc_hall_cal)) {
            for (int i = 0; i < HALL_COUNT; i++) rtc_hall_cal[i] = 0.0f;
            for (int i = 0; i < HALL_COUNT; i++) rtc_hall_cal_n[i] = 0;
        } else {
            // Калибровка из NVS считается готовой
            for (int i = 0; i < HALL_COUNT; i++) rtc_hall_cal_n[i] = HALL_CAL_MIN_N;
        }
        rtc_cal_magic = HALL_CAL_MAGIC;
    }
    rtc_hall_cal[0]   = 0.0f;   // опорный датчик по определению без поправки
    rtc_hall_cal_n[0] = 255;

    hall_cal_ready = true;
    for (int i = 1; i < HALL_COUNT; i++)
        if (rtc_hall_cal_n[i] < HALL_CAL_MIN_N) hall_cal_ready = false;

    if (hall_cal_ready) {
        webLogf("[HALL] Cal: %.1f %.1f %.1f %.1f %.1f",
                rtc_hall_cal[1], rtc_hall_cal[2], rtc_hall_cal[3],
                rtc_hall_cal[4], rtc_hall_cal[5]);
    }
}

// Сохраняет калибровку в NVS только если она заметно изменилась —
// бережём ресурс флеша, запись идёт лишь при уходе в deep sleep.
static void saveHallCalibration() {
    if (!hall_cal_ready) return;
    float stored[HALL_COUNT] = {};
    size_t got = prefs.getBytes("hallcal", (void*)stored, sizeof(stored));
    bool changed = (got != sizeof(stored));
    if (!changed) {
        for (int i = 1; i < HALL_COUNT; i++)
            if (fabsf(stored[i] - rtc_hall_cal[i]) > 0.5f) changed = true;
    }
    if (changed) prefs.putBytes("hallcal", (const void*)rtc_hall_cal, sizeof(rtc_hall_cal));
}

// =====================================================================
//                         SK9822 / DMA
// =====================================================================

void initSK9822_DMA() {
    spi_bus_config_t buscfg = {};
    buscfg.mosi_io_num     = PIN_LED_DATA;
    buscfg.miso_io_num     = -1;
    buscfg.sclk_io_num     = PIN_LED_CLK;
    buscfg.quadwp_io_num   = -1;
    buscfg.quadhd_io_num   = -1;
    buscfg.max_transfer_sz = SK9822_BUF_SIZE;

    spi_device_interface_config_t devcfg = {};
    devcfg.clock_speed_hz = 23 * 1000 * 1000;  // 23 МГц
    devcfg.mode           = 0;
    devcfg.spics_io_num   = -1;
    devcfg.queue_size     = 1;
    devcfg.flags          = SPI_DEVICE_NO_DUMMY;

    ESP_ERROR_CHECK(spi_bus_initialize(SPI2_HOST, &buscfg, SPI_DMA_CH_AUTO));
    ESP_ERROR_CHECK(spi_bus_add_device(SPI2_HOST, &devcfg, &sk9822_spi));

    for (int b = 0; b < 2; b++) {
        dma_buf[b] = (uint8_t*)heap_caps_malloc(SK9822_BUF_SIZE, MALLOC_CAP_DMA);
        assert(dma_buf[b] != nullptr);
        memset(dma_buf[b], 0x00, SK9822_BUF_SIZE);                            // Старт-фрейм и данные в 0
        memset(dma_buf[b] + 4 + NUM_LEDS * 4, 0xFF, SK9822_END_FRAMES);       // End-frame
        spi_trans[b].tx_buffer = dma_buf[b];
        spi_trans[b].length    = SK9822_BUF_SIZE * 8;
    }
    dma_tx_buffer = dma_buf[0];
    dmaMutex = xSemaphoreCreateMutex();
    webLog("[SYS] SK9822 DMA ready");
}

// Гасит все 528 диодов — ток=0, цвет=0.
// Используется при включении питания и при остановке рендеринга.
void blankAllLEDs_DMA() {
    if (!dma_tx_buffer || !sk9822_spi || !dmaMutex) return;
    xSemaphoreTake(dmaMutex, portMAX_DELAY);
    uint8_t* led_ptr = dma_tx_buffer + 4;
    for (int i = 0; i < NUM_LEDS; i++) {
        led_ptr[i * 4 + 0] = 0xE0; // SK9822: 111bbbbb, brightness=0 → ток=0
        led_ptr[i * 4 + 1] = 0;
        led_ptr[i * 4 + 2] = 0;
        led_ptr[i * 4 + 3] = 0;
    }
    spi_transaction_t t = {};
    t.length    = SK9822_BUF_SIZE * 8;
    t.tx_buffer = dma_tx_buffer;
    spi_device_transmit(sk9822_spi, &t);
    xSemaphoreGive(dmaMutex);
}

// Перестраивает три LUT (R, G, B): гамма + контраст + gain канала.
// Все три преобразования объединены в одну таблицу — горячий цикл рендера не меняется.
void rebuildGammaLUT() {
    float g        = global_gamma;
    float factor   = 1.0f + global_contrast * 0.02f; // 0% → 1.0, 100% → 3.0
    float gain[3]  = { global_r_gain * 0.01f, global_g_gain * 0.01f, global_b_gain * 0.01f };
    uint8_t* luts[3] = { lut_r, lut_g, lut_b };
    for (int ch = 0; ch < 3; ch++) {
        for (int i = 0; i < 256; i++) {
            float v = powf(i / 255.0f, g) * 255.0f;      // гамма
            v = 128.0f + (v - 128.0f) * factor;           // контраст
            v = v * gain[ch];                              // усиление канала
            luts[ch][i] = (uint8_t)constrain((int)(v + 0.5f), 0, 255);
        }
    }
}

// Кеш параметров LUT — обновляются в начале каждого оборота в renderingTask,
// вне горячего цикла секторов, чтобы не задерживать рендеринг на вызов powf().
static float   lut_last_gamma    = -1.0f;
static float   lut_last_contrast = -999.0f;
static float   lut_last_r        = -1.0f;
static float   lut_last_g        = -1.0f;
static float   lut_last_b        = -1.0f;
static float   lut_last_sat      = -1.0f;
static int16_t lut_sat_fxp       = 256;

// Угловые поправки (в секторах) для каждого из 44 LED на стороне луча.
// Пересчитываются при изменении global_spoke_offset — не внутри горячего цикла.
static int8_t  spoke_corr[LEDS_PER_SIDE] = {};
static int16_t spoke_last                = -999;

static void updateSpokeCorrIfNeeded() {
    int16_t off = global_spoke_offset;
    if (off == spoke_last) return;
    spoke_last = off;
    const float step = (LED_R_OUTER_MM - LED_R_INNER_MM) / (float)(LEDS_PER_SIDE - 1);
    for (int i = 0; i < LEDS_PER_SIDE; i++) {
        float r_mm = LED_R_INNER_MM + i * step;
        spoke_corr[i] = (off != 0)
            ? (int8_t)(atan2f((float)off, r_mm) * (360.0f / (2.0f * 3.14159265f)) + 0.5f)
            : 0;
    }
}

static void updateLUTIfNeeded() {
    if (global_gamma    != lut_last_gamma    ||
        global_contrast != lut_last_contrast ||
        global_r_gain   != lut_last_r        ||
        global_g_gain   != lut_last_g        ||
        global_b_gain   != lut_last_b) {
        rebuildGammaLUT();
        lut_last_gamma    = global_gamma;
        lut_last_contrast = global_contrast;
        lut_last_r        = global_r_gain;
        lut_last_g        = global_g_gain;
        lut_last_b        = global_b_gain;
    }
    if (global_saturation != lut_last_sat) {
        lut_sat_fxp  = (int16_t)(global_saturation * 256.0f);
        lut_last_sat = global_saturation;
    }
}

// Заполняет DMA-буфер данными сектора без отправки по SPI.
// Вызывается из renderingTask пока предыдущий буфер ещё передаётся —
// CPU и DMA работают параллельно.
//
// sector0 — сектор, который в этот момент показывает ПЕРВЫЙ луч.
// Остальные лучи смещены на ±60°·N: знак задаётся global_arm_reverse и
// зависит от того, в какую сторону пронумерованы лучи в цепочке SK9822.
static void fillSectorIntoBuffer(uint8_t* buf, int sector0) {
    uint8_t* led_ptr = buf + 4;

    if (frameBuffer == nullptr) {
        // Буфер ещё не загружен или уже освобождён — гасим все диоды,
        // чтобы не отправить устаревшие данные из предыдущей анимации.
        for (int i = 0; i < NUM_LEDS; i++) {
            led_ptr[i * 4 + 0] = 0xE0;
            led_ptr[i * 4 + 1] = 0;
            led_ptr[i * 4 + 2] = 0;
            led_ptr[i * 4 + 3] = 0;
        }
        return;
    }

    int16_t sat_fxp = lut_sat_fxp;

    // Кадр анимации вычисляется по абсолютному времени — не раз в оборот, а при каждом секторе.
    // lastFrameSwitchTime сбрасывается при загрузке файла; elapsed растёт непрерывно,
    // поэтому кадр может смениться прямо посередине оборота при высоком fps.
    uint32_t frame_idx;
    if (totalFrames > 1 && frameDelay > 0) {
        uint32_t elapsed_ms = millis() - lastFrameSwitchTime;
        frame_idx = (elapsed_ms / frameDelay) % totalFrames;
    } else {
        frame_idx = 0;
    }
    uint32_t anim_offset = frame_idx * FRAME_SIZE;

    uint8_t bri_level = global_brightness & 0x1F; // 0–31
    float   abl       = global_abl_limit;          // 0–100 %
    int     arm_step  = global_arm_reverse ? -ARM_STEP_DEG : ARM_STEP_DEG;

    // Рендерим цвета в led_ptr с временным bri_byte=0xFF (bri_level применяется ниже).
    // ABL считается ПО РЕЗУЛЬТАТУ рендера — те же данные что и RMS, одна шкала.
    // spoke_corr[i] предвычислен в updateSpokeCorrIfNeeded() — atan2 вне горячего цикла.
    for (int ray = 0; ray < NUM_ARMS; ray++) {
        int base_front = ((sector0 + ray * arm_step) % 360 + 360) % 360;
        // Обратная сторона луча видна с другой стороны колеса — зеркалим картинку
        int base_back  = (540 - base_front) % 360;

        for (int i = 0; i < LEDS_PER_SIDE; i++) {
            int ang_corr = spoke_corr[i];

            // --- Лицевая сторона луча (LED 0–43): поправка прибавляется ---
            int sector_f = ((base_front + ang_corr) % 360 + 360) % 360;
            const uint8_t* src_f = frameBuffer + anim_offset + sector_f * LEDS_PER_SIDE * 3;

            uint8_t r = lut_r[src_f[i * 3]];
            uint8_t g = lut_g[src_f[i * 3 + 1]];
            uint8_t b = lut_b[src_f[i * 3 + 2]];
            if (sat_fxp != 256) {
                int16_t L  = (int16_t)((77 * r + 150 * g + 29 * b) >> 8);
                int16_t r2 = L + (((int16_t)r - L) * sat_fxp >> 8);
                int16_t g2 = L + (((int16_t)g - L) * sat_fxp >> 8);
                int16_t b2 = L + (((int16_t)b - L) * sat_fxp >> 8);
                r = (uint8_t)constrain(r2, 0, 255);
                g = (uint8_t)constrain(g2, 0, 255);
                b = (uint8_t)constrain(b2, 0, 255);
            }
            int idx_a = (ray * LEDS_PER_ARM + i) * 4;
            led_ptr[idx_a + 0] = 0xFF;
            led_ptr[idx_a + 1] = b;
            led_ptr[idx_a + 2] = g;
            led_ptr[idx_a + 3] = r;

            // --- Обратная сторона луча (LED 44–87): поправка вычитается (зеркало) ---
            int sector_b = ((base_back - ang_corr) % 360 + 360) % 360;
            const uint8_t* src_b = frameBuffer + anim_offset + sector_b * LEDS_PER_SIDE * 3;

            uint8_t rb = lut_r[src_b[i * 3]];
            uint8_t gb = lut_g[src_b[i * 3 + 1]];
            uint8_t bb = lut_b[src_b[i * 3 + 2]];
            if (sat_fxp != 256) {
                int16_t L  = (int16_t)((77 * rb + 150 * gb + 29 * bb) >> 8);
                int16_t r2 = L + (((int16_t)rb - L) * sat_fxp >> 8);
                int16_t g2 = L + (((int16_t)gb - L) * sat_fxp >> 8);
                int16_t b2 = L + (((int16_t)bb - L) * sat_fxp >> 8);
                rb = (uint8_t)constrain(r2, 0, 255);
                gb = (uint8_t)constrain(g2, 0, 255);
                bb = (uint8_t)constrain(b2, 0, 255);
            }
            int idx_b = (ray * LEDS_PER_ARM + (LEDS_PER_ARM - 1 - i)) * 4;
            led_ptr[idx_b + 0] = 0xFF;
            led_ptr[idx_b + 1] = bb;
            led_ptr[idx_b + 2] = gb;
            led_ptr[idx_b + 3] = rb;
        }
    }

    // --- Считаем pixel_sum один раз — используется и для RMS, и для ABL.
    uint32_t pixel_sum = 0;
    uint32_t max_sum   = (uint32_t)NUM_LEDS * 3 * 255;
    for (int i = 0; i < NUM_LEDS; i++) {
        pixel_sum += led_ptr[i * 4 + 1]; // B
        pixel_sum += led_ptr[i * 4 + 2]; // G
        pixel_sum += led_ptr[i * 4 + 3]; // R
    }

    // --- RMS: реальная нагрузка тока = pixel_fill × bri / 31.
    rms_accum += (max_sum > 0)
        ? (float)pixel_sum / (float)max_sum * (float)bri_level / 31.0f
        : 0.0f;

    // --- ABL: лимитирует общий ток (pixel_fill × bri / 31 ≤ abl/100).
    if (abl < 100.0f && pixel_sum > 0) {
        float bri_max = abl * 0.01f * 31.0f * (float)max_sum / (float)pixel_sum;
        if ((float)bri_level > bri_max) {
            bri_level = (uint8_t)bri_max; // floor — не превышаем лимит
        }
    }
    uint8_t bri_byte = 0xE0 | (bri_level & 0x1F);
    global_effective_brightness = bri_level;

    for (int i = 0; i < NUM_LEDS; i++) {
        led_ptr[i * 4 + 0] = bri_byte;
    }
}

// =====================================================================
//                       ЗАДАЧА РЕНДЕРИНГА
// =====================================================================
// Ping-pong DMA: spi_device_queue_trans ставит передачу в очередь,
// spi_device_get_trans_result ждёт завершения не держа spinlock —
// WiFi ISR (prio >10) прерывает задачу когда нужно обработать пакеты.
//
// Угол ротора вычисляется непрерывно из micros():
//   sector0 = anchor_deg + ω·Δt + ½·α·Δt² + angle_offset
// где anchor_deg/anchor_t — «якорь» фазы, поставленный последним
// событием Холла. Учёт углового ускорения α — это то, что удерживает
// картинку на месте при разгоне и торможении: одной только средней
// скорости за прошлый оборот не хватает, ошибка ½·α·T² при резком
// торможении достигает десятка градусов.
void renderingTask(void* pvParameters) {
    static bool rendering_active = false;
    uint8_t active     = 0;
    bool    tx_pending = false;

    // Отписываем IDLE-задачу Core 1 от Task WDT: renderingTask занимает Core 1
    // почти непрерывно и IDLE не получает тиков.
    esp_task_wdt_delete(xTaskGetIdleTaskHandleForCPU(1));

    uint32_t seen_seq = 0;

    // Якорь фазы: в момент anchor_t первый луч показывал сектор anchor_deg
    uint32_t anchor_t   = 0;
    float    anchor_deg = 0.0f;
    bool     anchor_ok  = false;

    // Предыдущее измерение средней скорости (для оценки ускорения)
    float    w_avg_prev = 0.0f;
    uint32_t t_mid_prev = 0;
    bool     w_prev_ok  = false;

    int  sectors_drawn = 0;
    bool arm_reverse_seen = global_arm_reverse;

    while (true) {
        // Ждём нового события Холла максимум 500 мс.
        xSemaphoreTake(hallSemaphore, pdMS_TO_TICKS(500));

        // Смена порядка лучей меняет знак arm_step — накопленная калибровка
        // датчиков в старой системе координат больше не действительна.
        if (arm_reverse_seen != (bool)global_arm_reverse) {
            arm_reverse_seen = global_arm_reverse;
            for (int i = 0; i < HALL_COUNT; i++) { rtc_hall_cal[i] = 0.0f; rtc_hall_cal_n[i] = 0; }
            rtc_hall_cal_n[0] = 255;
            hall_cal_ready = false;
            webLog("[HALL] Arm order changed, calibration reset");
        }

        // --- Снимок данных ISR ---
        noInterrupts();
        uint32_t ev_seq  = hall_seq;
        uint32_t ev_t    = last_hall_time;
        uint8_t  ev_idx  = last_hall_idx;
        uint32_t ev_rev  = last_hall_rev;
        int8_t   ev_dir  = rotation_dir;
        uint32_t t_ref0  = hall_last_us[0];
        bool     ref0_ok = (hall_seen_mask & 1u) != 0;
        interrupts();

        if (ev_seq != seen_seq) {
            seen_seq = ev_seq;

            // Публикуем RMS раз в оборот — по опорному датчику
            if (ev_idx == 0 && sectors_drawn > 0) {
                global_abl_rms = rms_accum / (float)sectors_drawn;
                rms_accum      = 0.0f;
                sectors_drawn  = 0;
            }

            // --- Скорость и ускорение ---
            // ev_rev — время полного оборота, измеренное ОДНИМ датчиком.
            if (ev_rev >= HALL_MIN_REV_US && ev_rev <= 1500000UL) {
                float    w_avg = (float)ev_dir * 360.0f / (float)ev_rev; // град/мкс
                uint32_t t_mid = ev_t - ev_rev / 2;   // момент, к которому относится средняя скорость

                if (w_prev_ok) {
                    float dt_mid = (float)(int32_t)(t_mid - t_mid_prev);
                    if (dt_mid > 2000.0f) {
                        float a = (w_avg - w_avg_prev) / dt_mid;
                        // Ограничиваем: поправка за оборот не более 30°
                        float lim = 60.0f / ((float)ev_rev * (float)ev_rev);
                        rotor_alpha = constrain(a, -lim, lim);
                    }
                } else {
                    rotor_alpha = 0.0f;
                }
                w_avg_prev = w_avg;
                t_mid_prev = t_mid;
                w_prev_ok  = true;

                // Экстраполируем среднюю скорость на момент события
                rotor_omega = w_avg + rotor_alpha * (float)(ev_rev / 2);

                // --- Автокалибровка углового положения датчиков ---
                // Между событием опорного датчика 0 и событием датчика k ротор
                // повернулся на ω·Δt. Разница с номинальными 60°·k и есть поправка.
                // Формула симметрична по направлению вращения, поэтому годится
                // и для заднего хода. Обновляем только на ровном ходу.
                bool steady = fabsf(rotor_alpha) * (float)ev_rev * (float)ev_rev < 2.0f;
                if (ev_idx != 0 && ref0_ok && steady) {
                    uint32_t d = (uint32_t)(ev_t - t_ref0);
                    if (d > 0 && d < ev_rev) {
                        float meas = w_avg * (float)d;   // сколько градусов прошло от датчика 0
                        int   sgn  = global_arm_reverse ? -1 : 1;
                        float e    = meas + (float)sgn * (float)ev_idx * ARM_STEP_DEG;
                        // Нормируем в (-180, 180]
                        while (e >  180.0f) e -= 360.0f;
                        while (e <= -180.0f) e += 360.0f;
                        if (fabsf(e) < 20.0f) {
                            rtc_hall_cal[ev_idx] += (e - rtc_hall_cal[ev_idx]) * 0.06f;
                            if (rtc_hall_cal_n[ev_idx] < 255) rtc_hall_cal_n[ev_idx]++;
                            if (!hall_cal_ready) {
                                bool ready = true;
                                for (int i = 1; i < HALL_COUNT; i++)
                                    if (rtc_hall_cal_n[i] < HALL_CAL_MIN_N) ready = false;
                                if (ready) {
                                    hall_cal_ready = true;
                                    webLog("[HALL] Sensor calibration ready");
                                }
                            }
                        }
                    }
                }
            }

            // --- Якорь фазы ---
            // Пока калибровка не набрана, привязываемся только к опорному датчику:
            // это в точности поведение старой прошивки с одним датчиком, без
            // риска добавить дрожание от разброса установки датчиков.
            if (hall_cal_ready || ev_idx == 0) {
                int sgn    = global_arm_reverse ? 1 : -1;
                anchor_t   = ev_t;
                anchor_deg = (float)sgn * (float)ev_idx * ARM_STEP_DEG + rtc_hall_cal[ev_idx];
                anchor_ok  = true;
            }
        }

        // --- Условия остановки отрисовки ---
        if (force_stop_display || power_state != PWR_FULL || !newFrameReady || ota_in_progress) {
            if (rendering_active) {
                rendering_active = false;
                webLog("[PWR] Rendering stopped");
                blankAllLEDs_DMA();
                global_abl_rms              = 0.0f;
                rms_accum                   = 0.0f;
                sectors_drawn               = 0;
                global_effective_brightness = global_brightness;
            }
            continue;
        }

        // Допустимое «время дожития» без событий: полтора оборота, но не более 1.1 с.
        uint32_t rev = rotation_period;
        uint32_t age_limit = 1100000UL;
        if (rev > 0 && rev + rev / 2 < age_limit) age_limit = rev + rev / 2;

        // Порог оборотов проверяем здесь, а не только в loop(): во время
        // отрисовки renderingTask вытесняет loop(), и картинка «доживала» бы
        // до момента, когда до автомата питания дойдут руки. Гистерезис 5 RPM:
        // старт на RPM_RENDER_ON, продолжение работы — до RPM_RENDER_OFF.
        float rpm_now = (rev > 0) ? 60000000.0f / (float)rev : 0.0f;
        float rpm_thr = rendering_active ? RPM_RENDER_OFF : RPM_RENDER_ON;

        uint32_t age_us = (uint32_t)(micros() - anchor_t);
        if (!anchor_ok || rev == 0 || rpm_now < rpm_thr || age_us > age_limit) {
            if (rendering_active) {
                rendering_active = false;
                webLog("[PWR] Rotation lost, rendering paused");
                blankAllLEDs_DMA();
                global_abl_rms              = 0.0f;
                rms_accum                   = 0.0f;
                sectors_drawn               = 0;
                global_effective_brightness = global_brightness;
            }
            // Оценки скорости устарели — начинаем набирать их заново,
            // иначе после остановки колеса alpha считалась бы по разрыву в секундах.
            anchor_ok   = false;
            w_prev_ok   = false;
            rotor_alpha = 0.0f;
            rotor_omega = 0.0f;
            continue;
        }
        if (!rendering_active) {
            rendering_active = true;
            webLog("[PWR] Rendering started");
        }

        // LUT и spoke_corr обновляем один раз за проход — вне горячего цикла.
        updateLUTIfNeeded();
        updateSpokeCorrIfNeeded();

        int last_sector = -1;

        while (true) {
            if (force_stop_display || power_state != PWR_FULL || !newFrameReady || ota_in_progress) break;

            // Пришло новое событие Холла — выходим, чтобы переставить якорь фазы
            noInterrupts();
            bool got_new = (hall_seq != seen_seq);
            interrupts();
            if (got_new) break;

            uint32_t dt_u = (uint32_t)(micros() - anchor_t);
            if (dt_u > age_limit) break;   // событие потерялось — наверх, на переоценку

            float dt   = (float)dt_u;
            float dpsi = rotor_omega * dt + 0.5f * rotor_alpha * dt * dt;
            float base = anchor_deg + dpsi + (float)global_angle_offset;

            int sector = (int)lroundf(base) % 360;
            if (sector < 0) sector += 360;

            if (sector != last_sector) {
                uint8_t idle = 1 - active;
                fillSectorIntoBuffer(dma_buf[idle], sector);
                sectors_drawn++;

                if (tx_pending) {
                    spi_transaction_t* done;
                    spi_device_get_trans_result(sk9822_spi, &done, portMAX_DELAY);
                }
                spi_device_queue_trans(sk9822_spi, &spi_trans[idle], portMAX_DELAY);
                active      = idle;
                tx_pending  = true;
                last_sector = sector;
            } else if (rotor_omega != 0.0f && fabsf(1.0f / rotor_omega) > 2000.0f) {
                // Сектор длится больше 2 мс (ниже ~80 об/мин) — DMA давно закончил
                // и мы просто крутим пустой цикл. Уступаем такт планировщику,
                // иначе loop() (приоритет 1) не получит CPU: авто-яркость и
                // телеметрия замерли бы на всё время вращения.
                vTaskDelay(1);
            }
        }

        // Забираем незавершённую транзакцию
        if (tx_pending) {
            spi_transaction_t* done;
            spi_device_get_trans_result(sk9822_spi, &done, portMAX_DELAY);
            tx_pending = false;
        }
    }
}

// =====================================================================
//                     ТЕЛЕМЕТРИЯ (АЦП)
// =====================================================================

static uint32_t readMilliVoltsAvg(uint8_t pin, uint8_t samples) {
    uint32_t acc = 0;
    for (uint8_t i = 0; i < samples; i++) acc += analogReadMilliVolts(pin);
    return acc / samples;
}

// Обновляет кеш батареи/USB. Вызывается из loop() раз в секунду.
static void updatePowerTelemetry() {
    uint32_t vbat = (uint32_t)(readMilliVoltsAvg(PIN_ADC_VBAT, 8) * ADC_DIVIDER_RATIO);
    uint32_t vusb = (uint32_t)(readMilliVoltsAvg(PIN_ADC_VUSB, 8) * ADC_DIVIDER_RATIO);
    bool usb_ok   = vusb > VUSB_PRESENT_MV;

    // IP2312U: на IO8 лог. 1 = батарея заряжена. Признак «идёт заряд» —
    // лог. 0 при наличии напряжения на USB.
    uint8_t chg = 0;
    if (usb_ok) chg = (digitalRead(PIN_CHG_STAT) == HIGH) ? 2 : 1;

    pwr_cache.vbat_mv = (int16_t)vbat;
    pwr_cache.vusb_mv = (int16_t)vusb;
    pwr_cache.usb     = usb_ok;
    pwr_cache.chg     = chg;
}

// Авто-яркость по ALS-PT19. Датчик питается от DCDC №1, поэтому опрашиваем
// только когда он включён; иначе держим последнее значение.
static void updateAutoBrightness() {
    if (power_state != PWR_OFF) {
        float mv  = (float)readMilliVoltsAvg(PIN_ADC_LIGHT, 4);
        float lux = mv * (LUX_FULL_SCALE / ALS_MV_AT_1000LX);

        static float   lux_buf[10] = {};
        static uint8_t lux_idx     = 0;
        static uint8_t lux_count   = 0;
        lux_buf[lux_idx] = lux;
        lux_idx = (lux_idx + 1) % 10;
        if (lux_count < 10) lux_count++;
        float sum = 0;
        for (uint8_t i = 0; i < lux_count; i++) sum += lux_buf[i];
        last_lux_value = sum / lux_count;
    }

    float ratio = constrain(last_lux_value / LUX_FULL_SCALE, 0.0f, 1.0f);
    global_brightness = (uint8_t)(
        min_brightness + ratio * (float)(max_brightness - min_brightness) + 0.5f
    );
    if (power_state != PWR_FULL) global_effective_brightness = global_brightness;
}

// =====================================================================
//                     УПРАВЛЕНИЕ ПИТАНИЕМ
// =====================================================================
// Разрешает работу указанных датчиков Холла и сбрасывает накопленное состояние
// для тех, что были обесточены: их метки времени устарели и дали бы неверный
// период оборота при первом же срабатывании после подачи питания.
static void setHallMask(uint8_t mask) {
    noInterrupts();
    hall_active_mask = mask;
    hall_seen_mask  &= mask;      // забываем метки времени обесточенных датчиков
    hall_prev_idx    = 0xFF;      // порядок срабатывания надо набрать заново
    dir_score        = 0;
    if (mask == 0) rotation_period = 0;
    interrupts();
}

// Переключение ступеней питания. Вызывать ТОЛЬКО из loop().
static void applyPowerState(PowerState target) {
    if (target == power_state) return;

    switch (target) {
        case PWR_OFF:
            // Попытка раскрутки после тряски не дала вращения — на время
            // перестаём реагировать на вибродатчик, иначе тряска в транспорте
            // будет держать устройство включённым бесконечно.
            if (spinup_from_vibration) {
                vibration_block_until = millis() + VIBRATION_RETRY_MS;
                spinup_from_vibration = false;
                webLogf("[PWR] No spin-up, vibration ignored for %lus",
                        (unsigned long)(VIBRATION_RETRY_MS / 1000));
            }
            // Сначала снимаем флаг — renderingTask прекращает трогать SPI,
            // затем гасим диоды, и только потом снимаем питание.
            power_state = PWR_OFF;
            setHallMask(0);
            delay(3);
            blankAllLEDs_DMA();
            digitalWrite(PIN_EN_DCDC_REST, LOW);
            digitalWrite(PIN_EN_DCDC_ARM1, LOW);
            peripherals_active = false;
            last_dcdc_off_time = millis();
            webLog("[PWR] Power off");
            break;

        case PWR_SPINUP:
            if (power_state == PWR_FULL) {
                // Обороты упали — гасим лучи 2–6, первый оставляем под питанием
                power_state = PWR_SPINUP;
                setHallMask(0x01);
                delay(3);
                blankAllLEDs_DMA();
                digitalWrite(PIN_EN_DCDC_REST, LOW);
                webLog("[PWR] RPM low, arms 2-6 off");
            } else {
                // Просыпаемся: включаем только первый луч и его датчик Холла
                last_dcdc_on_time = millis();  // до delay — иначе now_ms < last_dcdc_on_time
                digitalWrite(PIN_EN_DCDC_ARM1, HIGH);
                peripherals_active = true;
                power_state = PWR_SPINUP;
                delay(5);                      // ждём стабилизации питания SK9822
                blankAllLEDs_DMA();
                setHallMask(0x01);
                webLog("[PWR] Arm 1 on, measuring RPM");
            }
            break;

        case PWR_FULL:
            // Колесо реально раскрутилось — это подтверждённая активность
            last_dcdc_on_time     = millis();
            last_motion_ms        = last_dcdc_on_time;
            spinup_from_vibration = false;
            digitalWrite(PIN_EN_DCDC_REST, HIGH);
            peripherals_active = true;
            delay(5);                          // ждём стабилизации питания лучей 2–6
            blankAllLEDs_DMA();
            setHallMask((uint8_t)((1u << HALL_COUNT) - 1));
            power_state = PWR_FULL;
            webLog("[PWR] All arms on, rendering enabled");
            break;
    }
}

static void enterDeepSleep() {
    // Снимаем питание и глушим прерывания
    digitalWrite(PIN_EN_DCDC_REST, LOW);
    digitalWrite(PIN_EN_DCDC_ARM1, LOW);
    for (int i = 0; i < HALL_COUNT; i++) detachInterrupt(digitalPinToInterrupt(HALL_PIN[i]));
    detachInterrupt(digitalPinToInterrupt(PIN_VIBRATION));

    saveHallCalibration();

    // Пробуждение по вибродатчику. EXT0 срабатывает по УРОВНЮ, поэтому
    // ловим уровень, противоположный текущему: если контакт сейчас замкнут,
    // ждём размыкания, иначе — замыкания.
    if (digitalRead(PIN_VIBRATION) == LOW) esp_sleep_enable_ext0_wakeup((gpio_num_t)PIN_VIBRATION, 1);
    else                                   esp_sleep_enable_ext0_wakeup((gpio_num_t)PIN_VIBRATION, 0);

    // Замораживаем Enable обоих DCDC в LOW на время сна
    gpio_hold_en((gpio_num_t)PIN_EN_DCDC_ARM1);
    gpio_hold_en((gpio_num_t)PIN_EN_DCDC_REST);
    gpio_deep_sleep_hold_en();

    esp_deep_sleep_start();
}

// =====================================================================
//                          ЗАДАЧИ
// =====================================================================

void updateFileList() {
    savedFiles.clear();
    File root = LittleFS.open("/");
    File file = root.openNextFile();
    while(file) {
        String fn = file.name();
        if (fn.endsWith(".bin")) savedFiles.push_back(fn);
        file = root.openNextFile();
    }
}

// Задача загрузки файлов — работает на Core 0 (не мешает рендерингу на Core 1),
// приоритет 2 (ниже WiFi) — не блокирует HTTP-стек во время чтения LittleFS.
void fileLoaderTask(void* pvParameters) {
    while (true) {
        xSemaphoreTake(fileLoaderSemaphore, portMAX_DELAY);
        if (pendingFilePath.length() > 0) {
            loadFrameFromFile(pendingFilePath);
            pendingFilePath = "";
        }
    }
}

// Сетевая задача: ArduinoOTA + ElegantOTA + переподключение WiFi.
// Вынесена из loop() на Core 0: renderingTask на Core 1 вытесняет loop()
// во время рендеринга, из-за чего loopNetwork() не вызывался достаточно часто.
void networkTask(void* pvParameters) {
    for (;;) {
        loopNetwork();
        vTaskDelay(pdMS_TO_TICKS(5));
    }
}

// =====================================================================
//                           SETUP
// =====================================================================

void setup() {
    // millis() сбрасывается при каждом запуске — старая millis-база из RTC недействительна.
    resetTimeSync();

    // Снимаем удержание пинов после сна
    gpio_deep_sleep_hold_dis();
    gpio_hold_dis((gpio_num_t)PIN_EN_DCDC_ARM1);
    gpio_hold_dis((gpio_num_t)PIN_EN_DCDC_REST);

    // Записываем LOW в защёлку ДО переключения в OUTPUT — иначе при смене режима
    // пин на долю мкс оказывается в HIGH, DCDC включается и светодиоды видят
    // случайный сигнал. gpio_set_level не меняет direction — безопасно.
    gpio_set_level((gpio_num_t)PIN_EN_DCDC_ARM1, 0);
    gpio_set_level((gpio_num_t)PIN_EN_DCDC_REST, 0);
    pinMode(PIN_EN_DCDC_ARM1, OUTPUT);
    pinMode(PIN_EN_DCDC_REST, OUTPUT);
    digitalWrite(PIN_EN_DCDC_ARM1, LOW);
    digitalWrite(PIN_EN_DCDC_REST, LOW);
    peripherals_active = false;
    power_state        = PWR_OFF;

    // Входы. Выходы DRV5023 и вибродатчика подтянуты внешними резисторами
    // к неотключаемой линии 3V3 — внутренняя подтяжка нужна лишь как страховка.
    // gpio_hold_dis: прошивка V4 замораживала GPIO21 перед сном, и защёлка
    // переживает программный сброс — снимаем её со всех пинов Холла.
    for (int i = 0; i < HALL_COUNT; i++) {
        gpio_hold_dis((gpio_num_t)HALL_PIN[i]);
        pinMode(HALL_PIN[i], INPUT);
    }
    pinMode(PIN_VIBRATION, INPUT_PULLUP);
    pinMode(PIN_BUTTON,    INPUT_PULLUP);
    pinMode(PIN_CHG_STAT,  INPUT);

    // АЦП: делители 1:2 дают до 2.6 В при 5 В на USB — нужен полный диапазон
    analogSetPinAttenuation(PIN_ADC_VBAT,  ADC_11db);
    analogSetPinAttenuation(PIN_ADC_VUSB,  ADC_11db);
    analogSetPinAttenuation(PIN_ADC_LIGHT, ADC_11db);

    esp_sleep_wakeup_cause_t wakeup_reason = esp_sleep_get_wakeup_cause();
    esp_reset_reason_t       reset_reason  = esp_reset_reason();

    if (wakeup_reason == ESP_SLEEP_WAKEUP_EXT0) {
        webLog("[SYS] Wakeup: vibration (IO15)");
    } else if (wakeup_reason == ESP_SLEEP_WAKEUP_UNDEFINED) {
        const char* rr_str = "unknown";
        switch (reset_reason) {
            case ESP_RST_POWERON:   rr_str = "power-on";       break;
            case ESP_RST_EXT:       rr_str = "ext-reset";      break;
            case ESP_RST_SW:        rr_str = "sw-reset";       break;
            case ESP_RST_PANIC:     rr_str = "panic/crash";    break;
            case ESP_RST_INT_WDT:   rr_str = "WDT-interrupt";  break;
            case ESP_RST_TASK_WDT:  rr_str = "WDT-task";       break;
            case ESP_RST_WDT:       rr_str = "WDT-other";      break;
            case ESP_RST_DEEPSLEEP: rr_str = "deep-sleep";     break;
            case ESP_RST_BROWNOUT:  rr_str = "brownout";       break;
            case ESP_RST_SDIO:      rr_str = "SDIO";           break;
            default:                                            break;
        }
        webLogf("[SYS] Full reset: %s", rr_str);
    } else {
        webLogf("[SYS] Boot, wakeup cause: %d", (int)wakeup_reason);
    }

    // Наличие питания на USB определяем сразу — от этого зависит, не уснуть ли обратно
    uint32_t vusb_mv = (uint32_t)(readMilliVoltsAvg(PIN_ADC_VUSB, 8) * ADC_DIVIDER_RATIO);
    bool     usb_present = vusb_mv > VUSB_PRESENT_MV;

    // Холодный старт (подключили батарею) без USB и без вибрации — сразу спать,
    // чтобы устройство не разряжало батарею на полке. После sw-reset (OTA) и
    // прочих перезагрузок остаёмся в работе — иначе не поймать окно для прошивки.
    if (wakeup_reason != ESP_SLEEP_WAKEUP_EXT0 &&
        reset_reason  == ESP_RST_POWERON && !usb_present) {
        webLog("[SYS] Cold boot, no USB, sleeping...");
        enterDeepSleep();
    }

    webLogf("[SYS] Initializing... (VUSB %lu mV)", (unsigned long)vusb_mv);

    if (psramFound()) {
        frameBuffer = (uint8_t*)ps_malloc(FRAME_SIZE);
        if (frameBuffer) memset(frameBuffer, 0, FRAME_SIZE);
    }
    LittleFS.begin(true);

    setupNetwork();                     // здесь же открывается prefs
    loadHallCalibration();
    last_web_activity_time = millis();  // Считаем загрузку страницы активностью

    // Эндпоинт телеметрии питания — отдаёт кешированные данные без обращения к АЦП.
    server.on("/battery", HTTP_GET, [](AsyncWebServerRequest *request){
        // Фоновый поллинг — не сбрасывает таймер активности.
        char buf[160];
        snprintf(buf, sizeof(buf),
            "{\"vbat\":%d,\"vusb\":%d,\"chg\":%u,\"usb\":%s,\"connected\":%s}",
            (int)pwr_cache.vbat_mv, (int)pwr_cache.vusb_mv,
            (unsigned)pwr_cache.chg,
            pwr_cache.usb ? "true" : "false",
            pwr_cache.usb ? "true" : "false"
        );
        request->send(200, "application/json", buf);
    });

    updateFileList();

    // SPI инициализируется ДО включения DCDC: пины DATA и CLK переходят под
    // контроль SPI-драйвера (LOW в idle), и светодиоды не видят мусорный
    // сигнал в момент подачи питания.
    initSK9822_DMA();
    blankAllLEDs_DMA();

    hallSemaphore       = xSemaphoreCreateBinary();
    fileLoaderSemaphore = xSemaphoreCreateBinary();

    String last_file = prefs.getString("last_file", "");
    if (last_file != "" && LittleFS.exists("/" + last_file)) {
        if (slideshowActive) {
            webLogf("[DISP] Autoplay (slideshow resume): %s", last_file.c_str());
        } else {
            webLogf("[DISP] Autoplay: %s", last_file.c_str());
        }
        loadFrameFromFile("/" + last_file);
        request_play_flag = true;   // loop() поднимет питание первого луча
    } else if (slideshowActive) {
        webLog("[DISP] Slideshow resume: waiting for first file load");
    }

    // Задача асинхронной загрузки файлов: Core 0, приоритет 2.
    xTaskCreatePinnedToCore(fileLoaderTask, "loader", 4096, NULL, 2, NULL, 0);

    // Сетевая задача: ArduinoOTA + ElegantOTA + WiFi reconnect — Core 0, приоритет 3.
    xTaskCreatePinnedToCore(networkTask, "network", 4096, NULL, 3, NULL, 0);

    // Core 1, приоритет 2: выше loop() (prio 1), ниже WiFi ISR и системных задач.
    xTaskCreatePinnedToCore(renderingTask, "render", 4096, NULL, 2, NULL, 1);

    // loopTask (Arduino loop) работает на Core 1 с приоритетом 1 и вытесняется
    // renderingTask во время вращения — отписываем его от Task WDT.
    esp_task_wdt_delete(xTaskGetCurrentTaskHandle());

    // Прерывания: шесть датчиков Холла + вибродатчик
    for (uint32_t i = 0; i < HALL_COUNT; i++) {
        attachInterruptArg(digitalPinToInterrupt(HALL_PIN[i]), hallInterruptHandler,
                           (void*)i, FALLING);
    }
    attachInterrupt(digitalPinToInterrupt(PIN_VIBRATION), vibrationInterruptHandler, CHANGE);

    // Открываем сервер только после полной инициализации.
    server.begin();
    webLog("[NET] HTTP server started");
}

// =====================================================================
//                            LOOP
// =====================================================================

void loop() {
    static uint32_t last_play_ms = 0; // Время последнего запроса /play

    uint32_t now_ms = millis();

    // --- Текущее состояние вращения ---
    noInterrupts();
    uint32_t rev_period = rotation_period;
    uint32_t hall_t     = last_hall_time;
    interrupts();

    uint32_t now_us = micros();
    uint32_t hall_age_us = UINT32_MAX;
    if (hall_t != 0) hall_age_us = (uint32_t)(now_us - hall_t);

    // Обороты: если событий давно не было — период фактически не меньше времени
    // молчания, поэтому RPM падает плавно, а не держится на старом значении.
    float rpm = 0.0f;
    if (rev_period > 0 && hall_age_us < 3000000UL) {
        uint32_t eff = (hall_age_us > rev_period) ? hall_age_us : rev_period;
        rpm = 60000000.0f / (float)eff;
    }

    // --- Обработка запроса Play из Web UI ---
    // Датчики Холла обесточены, поэтому вращения ждать нельзя — питание
    // первого луча поднимаем сами, дальше решает автомат по оборотам.
    static bool play_pending = false;
    if (request_play_flag) {
        request_play_flag = false;
        last_play_ms   = now_ms;
        last_motion_ms = now_ms;
        play_pending   = true;
    }

    // --- Слайдшоу: автоматическая смена файлов по таймеру ---
    // Пауза слайдшоу при отсутствии вращения: не переключаем файлы и не обновляем
    // last_web_activity_time — это позволяет устройству уйти в sleep если нет активности.
    bool rotation_present = (hall_age_us < 3000000UL);
    if (slideshowActive && savedFiles.size() > 0 &&
        (slideCurrentIndex < 0 ||
         (rotation_present && (now_ms - slideLastSwitch >= slideInterval)))) {
        slideLastSwitch = now_ms;
        slideCurrentIndex = (slideCurrentIndex + 1) % (int)savedFiles.size();
        String nextFile = savedFiles[slideCurrentIndex];
        pendingFilePath = "/" + nextFile;
        prefs.putString("last_file", nextFile);
        force_stop_display = false;
        request_play_flag = true;
        xSemaphoreGive(fileLoaderSemaphore);
        last_web_activity_time = now_ms; // предотвращаем засыпание во время активного слайдшоу
        webLogf("[DISP] Slideshow: %s (%d/%d)", nextFile.c_str(),
                slideCurrentIndex + 1, (int)savedFiles.size());
    }

    // --- Пробуждение по вибродатчику ---
    bool vibration = false;
    if (wakeup_event) {
        wakeup_event = false;
        vibration = true;
    }

    // =================== АВТОМАТ ПИТАНИЯ ===================
    // PWR_OFF → PWR_SPINUP : вибрация или запрос Play (есть что показывать)
    // PWR_SPINUP → PWR_FULL: обороты достигли RPM_RENDER_ON
    // PWR_FULL → PWR_SPINUP: обороты упали ниже RPM_RENDER_OFF (гистерезис 5 RPM)
    // любое → PWR_OFF      : нет вращения дольше 3 с либо Stop из Web UI
    bool content_ready = newFrameReady && !force_stop_display && !ota_in_progress;

    if (!content_ready) {
        if (power_state != PWR_OFF) applyPowerState(PWR_OFF);
    } else {
        switch (power_state) {
            case PWR_OFF: {
                bool vib_allowed = vibration && (int32_t)(now_ms - vibration_block_until) >= 0;
                if (vib_allowed || play_pending) {
                    spinup_from_vibration = vib_allowed && !play_pending;
                    applyPowerState(PWR_SPINUP);
                }
                break;
            }

            case PWR_SPINUP:
                if (rpm >= RPM_RENDER_ON) {
                    applyPowerState(PWR_FULL);
                } else if (hall_age_us > 3000000UL &&
                           (now_ms - last_play_ms)      > 10000 &&
                           (now_ms - last_dcdc_on_time) > 2000) {
                    webLog("[PWR] No rotation >3s");
                    applyPowerState(PWR_OFF);
                }
                break;

            case PWR_FULL:
                if (rpm < RPM_RENDER_OFF) applyPowerState(PWR_SPINUP);
                break;
        }
    }
    play_pending = false;

    // --- Deep Sleep (Таймаут 1 минута) ---
    // Считаем от подтверждённой активности (last_motion_ms), а не от каждого
    // включения DCDC: иначе безрезультатные попытки раскрутки по тряске
    // бесконечно откладывали бы сон.
    uint32_t time_since_web_activity_ms = now_ms - last_web_activity_time;
    uint32_t time_since_motion_ms       = now_ms - last_motion_ms;

    // Обратный отсчёт до сна: лог каждые 10 секунд + уведомление при сбросе таймера.
    {
        static uint32_t last_activity_snap = 0;
        static uint32_t last_motion_snap   = 0;
        static int      last_logged_tick   = -1;

        uint32_t web_age_s    = time_since_web_activity_ms / 1000;
        uint32_t hall_age_s   = (hall_age_us == UINT32_MAX) ? 60 : (hall_age_us / 1000000);
        uint32_t motion_age_s = time_since_motion_ms / 1000;

        uint32_t min_age_s = web_age_s;
        if (hall_age_s   < min_age_s) min_age_s = hall_age_s;
        if (motion_age_s < min_age_s) min_age_s = motion_age_s;

        bool activity_reset = (last_web_activity_time != last_activity_snap) ||
                              (last_motion_ms         != last_motion_snap);
        last_activity_snap = last_web_activity_time;
        last_motion_snap   = last_motion_ms;

        if (activity_reset) {
            if (last_logged_tick >= 0) webLog("[NET] Activity detected, sleep timer reset");
            last_logged_tick = -1;
        }

        if (min_age_s >= 10 && min_age_s < 60) {
            int tick = (int)(min_age_s / 10) * 10;
            if (tick != last_logged_tick) {
                last_logged_tick = tick;
                webLogf("[NET] Idle, sleep in %ds", 60 - tick);
            }
        }
    }

    if (power_state == PWR_OFF && hall_age_us > 60000000UL &&
        time_since_web_activity_ms > 60000 && time_since_motion_ms > 60000) {
        if (pwr_cache.usb) {
            last_hall_time = now_us;
            webLog("[SYS] USB connected, sleep cancelled");
        } else {
            webLogf("[SYS] Idle >60s (web: %lus), sleeping...",
                    (unsigned long)(time_since_web_activity_ms / 1000));
            // Ждём минимум 3 поллинга браузера (интервал 2с) — лог об уходе в сон
            // должен дойти до UI до фактического отключения.
            delay(3000);
            enterDeepSleep();
        }
    }

    // --- Авто-яркость (каждые 100 мс) ---
    static uint32_t last_light_ms = 0;
    if (now_ms - last_light_ms >= 100) {
        last_light_ms = now_ms;
        updateAutoBrightness();
    }

    // --- Телеметрия питания (раз в секунду) ---
    static uint32_t last_power_ms = 0;
    if (now_ms - last_power_ms >= 1000) {
        last_power_ms = now_ms;
        updatePowerTelemetry();
    }
}
