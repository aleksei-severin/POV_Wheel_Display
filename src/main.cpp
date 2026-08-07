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

RTC_DATA_ATTR uint8_t min_brightness = 1;  // единицы SK9822 (1–31)
RTC_DATA_ATTR uint8_t max_brightness = 31; // единицы SK9822 (1–31), 31 = максимум тока
RTC_DATA_ATTR volatile int global_angle_offset = 93;

uint8_t* frameBuffer = nullptr;

// Глобальные переменные для поддержки GIF анимаций
uint32_t currentFrameIndex = 0;
uint32_t totalFrames = 1;
uint16_t frameDelay = 100;
uint32_t lastFrameSwitchTime = 0;

volatile bool newFrameReady = false;
volatile bool render_in_fill = false;   // renderingTask сейчас читает frameBuffer
volatile bool frame_loading  = false;   // идёт чтение файла — отрисовка погашена
// Лента сейчас светится. Загрузчик файла ждёт сброса этого флага, прежде чем
// трогать флеш: пока он взведён, на диодах висит защёлкнутый кадр.
volatile bool rendering_active = false;
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
// 528 диодов: старт-фрейм 4 байта + 528×4 байта данных + end-frame.
// Протоколу нужно N/2 БИТ хвоста (528/2 = 264 бита = 33 байта), а раньше слалось
// 264 БАЙТА — лишние 231 байт на каждом кадре, почти 10 % времени шины впустую.
// Берём 33 байта + 4 байта запаса.
#define SK9822_END_BYTES  (NUM_LEDS / 16 + 4)
#define SK9822_BUF_SIZE   (4 + NUM_LEDS * 4 + SK9822_END_BYTES)

// Время передачи одного кадра по SPI, мкс. Это и есть шаг дискретизации угла:
// пока кадр идёт по шине, лента показывает предыдущие данные.
#define SK9822_FRAME_US   ((float)(SK9822_BUF_SIZE * 8) * 1000000.0f / (float)SK9822_SPI_HZ)

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
RTC_DATA_ATTR volatile float global_g_gain        = 80.0f;
RTC_DATA_ATTR volatile float global_b_gain        = 100.0f;
RTC_DATA_ATTR volatile uint16_t wheel_circumference = 2355;
RTC_DATA_ATTR volatile bool     global_arm_reverse  = false; // порядок лучей в цепочке
RTC_DATA_ATTR volatile float    global_abl_limit    = 100.0f; // ABL: 0–100 %, 100 = без ограничения
volatile float                  global_abl_rms      = 0.0f;  // RMS загрузка тока 0.0–1.0
volatile float                  global_render_span    = 0.0f; // угол между обновлениями ленты, °
volatile uint32_t               global_render_fill_us = 0;    // время сборки кадра, мкс
volatile uint8_t                global_effective_brightness = 8;
RTC_DATA_ATTR volatile float    rpm_render_on  = RPM_RENDER_ON;
RTC_DATA_ATTR volatile float    rpm_render_off = RPM_RENDER_OFF;
uint8_t lut_tone5[32];
uint8_t lut_tone6[64];

// =====================================================================
//                  СОХРАНЕНИЕ НАСТРОЕК В NVS
// =====================================================================
// RTC_DATA_ATTR переживает только deep sleep: после снятия питания настройки
// возвращались к заводским. Поэтому весь набор дублируется в NVS ОДНИМ блобом —
// одна запись вместо дюжины ключей, то есть одно стирание страницы флеша
// вместо дюжины.
//
// Запись отложена по той же причине, что и имя последнего файла: putBytes
// отключает кеш инструкций на ОБОИХ ядрах, а renderingTask исполняется из
// флеша и замирает на десятки миллисекунд. Ползунок настройки двигают
// непрерывно, и запись на каждое движение означала бы рваную картинку и сотни
// циклов стирания за минуту. Флаг взводится в обработчике /settings, сброс —
// когда отрисовка заведомо не идёт.
struct __attribute__((packed)) SettingsBlob {
    uint16_t magic;
    uint8_t  version;
    uint8_t  bmin;
    uint8_t  bmax;
    uint8_t  arm_reverse;
    uint8_t  slideshow;
    uint8_t  _pad;
    int16_t  angle;
    uint16_t circ;
    uint32_t slide_ms;
    float    gamma, saturation, contrast;
    float    r_gain, g_gain, b_gain, abl;
    float    rpm_on, rpm_off;                    // добавлены в версии 2
};
static const uint16_t SETTINGS_MAGIC = 0x5056;   // 'PV'
static const uint8_t  SETTINGS_VER   = 2;
// Блоб растёт только в конец, поэтому старая запись читается как есть: новые
// поля остаются нулями и отсеиваются проверкой диапазона — настройки,
// сохранённые прошлой прошивкой, при обновлении не теряются.
static const size_t   SETTINGS_V1_SIZE = 44;

volatile bool   settings_dirty       = false;    // взводится из /settings и /album
static uint32_t settings_dirty_since = 0;

static void fillSettingsBlob(SettingsBlob& b) {
    memset(&b, 0, sizeof(b));
    b.magic       = SETTINGS_MAGIC;
    b.version     = SETTINGS_VER;
    b.bmin        = min_brightness;
    b.bmax        = max_brightness;
    b.arm_reverse = global_arm_reverse ? 1 : 0;
    b.slideshow   = slideshowActive ? 1 : 0;
    b.angle       = (int16_t)global_angle_offset;
    b.circ        = wheel_circumference;
    b.slide_ms    = slideInterval;
    b.gamma       = global_gamma;
    b.saturation  = global_saturation;
    b.contrast    = global_contrast;
    b.r_gain      = global_r_gain;
    b.g_gain      = global_g_gain;
    b.b_gain      = global_b_gain;
    b.abl         = global_abl_limit;
    b.rpm_on      = rpm_render_on;
    b.rpm_off     = rpm_render_off;
}

// Диапазоны проверяются и при чтении: одного magic мало, испорченный блоб не
// должен увести гамму или яркость туда, где рендер покажет мусор.
static void loadSettingsFromNVS() {
    SettingsBlob b;
    memset(&b, 0, sizeof(b));   // хвост от старой записи должен читаться как нули
    size_t got = prefs.getBytes("settings", &b, sizeof(b));
    bool ok = (b.magic == SETTINGS_MAGIC) &&
              ((got == sizeof(b)          && b.version == SETTINGS_VER) ||
               (got == SETTINGS_V1_SIZE   && b.version == 1));
    if (!ok) {
        webLog("[SYS] No stored settings, using defaults");
        return;
    }
    if (b.bmin >= 1 && b.bmin <= 31) min_brightness = b.bmin;
    if (b.bmax >= 1 && b.bmax <= 31) max_brightness = b.bmax;
    if (max_brightness < min_brightness) max_brightness = min_brightness;
    global_angle_offset = ((b.angle % 360) + 360) % 360;
    if (b.circ >= 2000 && b.circ <= 2500)             wheel_circumference = b.circ;
    if (b.slide_ms >= 1000 && b.slide_ms <= 300000)   slideInterval = b.slide_ms;
    if (b.gamma      >= 1.0f && b.gamma      <= 5.0f)   global_gamma      = b.gamma;
    if (b.saturation >= 1.0f && b.saturation <= 3.0f)   global_saturation = b.saturation;
    if (b.contrast   >= 0.0f && b.contrast   <= 100.0f) global_contrast   = b.contrast;
    if (b.r_gain     >= 0.0f && b.r_gain     <= 100.0f) global_r_gain     = b.r_gain;
    if (b.g_gain     >= 0.0f && b.g_gain     <= 100.0f) global_g_gain     = b.g_gain;
    if (b.b_gain     >= 0.0f && b.b_gain     <= 100.0f) global_b_gain     = b.b_gain;
    if (b.abl        >= 0.0f && b.abl        <= 100.0f) global_abl_limit  = b.abl;
    // Порог остановки обязан быть ниже порога старта — иначе картинка мигала бы
    // на границе каждый оборот.
    if (b.rpm_on >= 30.0f && b.rpm_on <= 600.0f &&
        b.rpm_off >= 20.0f && b.rpm_off < b.rpm_on) {
        rpm_render_on  = b.rpm_on;
        rpm_render_off = b.rpm_off;
    }
    global_arm_reverse = (b.arm_reverse != 0);
    slideshowActive    = (b.slideshow   != 0);
    webLog("[SYS] Settings restored from NVS");
}

// Сброс отложенных настроек. Вызывать только когда отрисовка остановлена.
static void flushSettings() {
    if (!settings_dirty) return;
    SettingsBlob now, stored;
    fillSettingsBlob(now);
    size_t got = prefs.getBytes("settings", &stored, sizeof(stored));
    // Сравниваем с записанным: повторная запись того же блоба зря жжёт флеш.
    if (got != sizeof(stored) || memcmp(&now, &stored, sizeof(now)) != 0) {
        prefs.putBytes("settings", &now, sizeof(now));
        webLog("[SYS] Settings saved");
    }
    settings_dirty = false;
}

// --- КЕШ ТЕЛЕМЕТРИИ ПИТАНИЯ ---
// Обновляется из loop() (Core 1), читается из /battery (Core 0).
// Поля — примитивы ≤32 бит, запись атомарна на Xtensa, мьютекс не нужен.
struct PowerCache {
    int16_t vbat_mv = 0;
    int16_t vusb_mv = 0;      // напряжение на клеммах, как есть
    int16_t ocv_mv  = 0;      // восстановленная ЭДС (для калибровки/диагностики)
    uint8_t soc     = 0;      // заряд, %
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
    devcfg.clock_speed_hz  = SK9822_SPI_HZ;
    devcfg.duty_cycle_pos  = SK9822_DUTY_POS;
    devcfg.mode            = 0;
    devcfg.spics_io_num   = -1;
    devcfg.queue_size     = 1;
    devcfg.flags          = SPI_DEVICE_NO_DUMMY;

    ESP_ERROR_CHECK(spi_bus_initialize(SPI2_HOST, &buscfg, SPI_DMA_CH_AUTO));
    ESP_ERROR_CHECK(spi_bus_add_device(SPI2_HOST, &devcfg, &sk9822_spi));

    for (int b = 0; b < 2; b++) {
        dma_buf[b] = (uint8_t*)heap_caps_malloc(SK9822_BUF_SIZE, MALLOC_CAP_DMA);
        assert(dma_buf[b] != nullptr);
        memset(dma_buf[b], 0x00, SK9822_BUF_SIZE);                            // Старт-фрейм и данные в 0
        memset(dma_buf[b] + 4 + NUM_LEDS * 4, 0xFF, SK9822_END_BYTES);        // End-frame
        spi_trans[b].tx_buffer = dma_buf[b];
        spi_trans[b].length    = SK9822_BUF_SIZE * 8;
    }
    dma_tx_buffer = dma_buf[0];
    dmaMutex = xSemaphoreCreateMutex();

    // Реальная частота может отличаться от запрошенной — драйвер умеет только 80 МГц/N.
    // Она определяет максимальную частоту обновления, а с ней и угловую чёткость.
    int actual_hz = spi_get_actual_clock(APB_CLK_FREQ, SK9822_SPI_HZ, SK9822_DUTY_POS);
    // Длительности фаз клока — то, во что упирается длинная цепочка SK9822.
    // Делитель n восстанавливаем из фактической частоты (верно при pre = 1,
    // а это весь рабочий диапазон 13–40 МГц); такт APB = 12.5 нс.
    int n = actual_hz ? (APB_CLK_FREQ + actual_hz / 2) / actual_hz : 1;
    int h = (SK9822_DUTY_POS * n + 127) / 256;
    if (h < 1) h = 1;
    webLogf("[SYS] SK9822: %d kHz, duty %d/%d (%.1f/%.1f ns), %d B/frame, %.0f us",
            actual_hz / 1000, h, n,
            h * 12.5f, (n - h) * 12.5f,
            (int)SK9822_BUF_SIZE,
            (float)(SK9822_BUF_SIZE * 8) * 1000000.0f / (float)(actual_hz ? actual_hz : 1));
}

// Текущий байт яркости, лежащий в каждом из двух DMA-буферов.
// Позволяет не переписывать 528 ячеек на каждом секторе — байт меняется редко.
static uint8_t buf_bri_cache[2] = {0, 0};

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
    // Дважды: SK9822 защёлкивает данные в PWM-регистры по приходу следующего
    // старт-фрейма, а после гашения других посылок может долго не быть.
    spi_device_transmit(sk9822_spi, &t);
    spi_device_transmit(sk9822_spi, &t);
    buf_bri_cache[0] = 0xE0;   // dma_tx_buffer == dma_buf[0]
    xSemaphoreGive(dmaMutex);
}

// Перестраивает тональную кривую: гамма + контраст. Одна таблица на все каналы.
// Поканальные гейны отсюда убраны намеренно: это баланс белого дисплея, а не
// обработка изображения. Пока они сидели в LUT, насыщенность применялась к уже
// разбалансированному белому и растаскивала его дальше — нейтральный серый
// уезжал в цвет тем сильнее, чем выше насыщенность. Теперь порядок такой:
//   гамма+контраст → насыщенность → баланс белого × радиальная компенсация.
// Таблиц две — по одной на разрядность канала в RGB565. Кривая берётся от
// точной доли кода (i/31, i/63), а не от развёрнутого 8-битного значения:
// лишнее округление на входе гаммы сдвигало бы тёмный край, где она круче всего.
void rebuildGammaLUT() {
    float g      = global_gamma;
    float factor = 1.0f + global_contrast * 0.02f; // 0% → 1.0, 100% → 3.0
    for (int i = 0; i < 64; i++) {
        float v = powf(i / 63.0f, g) * 255.0f;      // гамма
        v = 128.0f + (v - 128.0f) * factor;          // контраст
        lut_tone6[i] = (uint8_t)constrain((int)(v + 0.5f), 0, 255);
    }
    for (int i = 0; i < 32; i++) {
        float v = powf(i / 31.0f, g) * 255.0f;
        v = 128.0f + (v - 128.0f) * factor;
        lut_tone5[i] = (uint8_t)constrain((int)(v + 0.5f), 0, 255);
    }
}

// Кеш параметров LUT — обновляются в начале каждого оборота в renderingTask,
// вне горячего цикла секторов, чтобы не задерживать рендеринг на вызов powf().
static float   lut_last_gamma    = -1.0f;
static float   lut_last_contrast = -999.0f;
static float   lut_last_sat      = -1.0f;
static int16_t lut_sat_fxp       = 256;

// Поканальные коэффициенты для каждого из 44 диодов, 8.8 fixed point (256 = ×1.0).
// Свёрнуты два независимых множителя, оба применяются после насыщенности:
//
//  • Баланс белого дисплея (R/G/B gain) — одинаков для всех диодов. Зелёный и
//    синий кристаллы SK9822 ярче красного, без коррекции белый уходит в голубой.
//
//  • Радиальная компенсация. За оборот диод на радиусе r засвечивает кольцо
//    площадью 2πr·Δr, поэтому при постоянном потоке яркость падает как 1/r:
//    край в 49/273 = 0.18 раза тусклее ступицы. Поднять край нельзя — он и так
//    на максимуме, поэтому гасим центр: gain = (r/R_outer)^k, где k фиксировано
//    RADIAL_GAIN_PCT. Потерянный общий ток возвращает ABL — лимит разрешает
//    поднять bri_level.
//
// Свёртка бесплатна: в горячем цикле как было одно умножение на канал, так и
// осталось, просто коэффициент берётся из своей таблицы.
static uint16_t gain_r[LEDS_PER_SIDE];
static uint16_t gain_g[LEDS_PER_SIDE];
static uint16_t gain_b[LEDS_PER_SIDE];
static float    gain_last_r   = -1.0f;
static float    gain_last_g   = -1.0f;
static float    gain_last_b   = -1.0f;

static void updateGainTablesIfNeeded() {
    if (global_r_gain == gain_last_r &&
        global_g_gain == gain_last_g && global_b_gain == gain_last_b) return;
    gain_last_r   = global_r_gain;
    gain_last_g   = global_g_gain;
    gain_last_b   = global_b_gain;

    const float k = RADIAL_GAIN_PCT * 0.01f;  // фиксировано: 0 = выкл, 1 = полная
    float cr = global_r_gain * 0.01f;
    float cg = global_g_gain * 0.01f;
    float cb = global_b_gain * 0.01f;
    const float step = (LED_R_OUTER_MM - LED_R_INNER_MM) / (float)(LEDS_PER_SIDE - 1);

    for (int i = 0; i < LEDS_PER_SIDE; i++) {
        float r_mm = LED_R_INNER_MM + i * step;
        float rad  = (k > 0.0f) ? powf(r_mm / LED_R_OUTER_MM, k) : 1.0f;
        gain_r[i] = (uint16_t)constrain((int)(rad * cr * 256.0f + 0.5f), 0, 256);
        gain_g[i] = (uint16_t)constrain((int)(rad * cg * 256.0f + 0.5f), 0, 256);
        gain_b[i] = (uint16_t)constrain((int)(rad * cb * 256.0f + 0.5f), 0, 256);
    }
}

static void updateLUTIfNeeded() {
    if (global_gamma != lut_last_gamma || global_contrast != lut_last_contrast) {
        rebuildGammaLUT();
        lut_last_gamma    = global_gamma;
        lut_last_contrast = global_contrast;
    }
    if (global_saturation != lut_last_sat) {
        lut_sat_fxp  = (int16_t)(global_saturation * 256.0f);
        lut_last_sat = global_saturation;
    }
}

// --- Угловой предфильтр -------------------------------------------------
// Лента обновляется целиком, поэтому каждое значение горит не «в точке», а на
// всём угле, который луч успевает пройти до следующей посылки:
//   span = 360 · (RPM/60) · t_кадра
// При 20 МГц кадр идёт 861 мкс, то есть span = RPM · 0.0052° — на 200 об/мин это
// уже целый градус, 4.9 мм дуги на радиусе 273 мм (у ступицы те же 1° — 0.9 мм,
// потому там и чисто). Точечная выборка в середине этого интервала оставляет
// алиасинг: частоты выше 1/(2·span) заворачиваются обратно и ровная линия
// рассыпается на ступеньки. Убирает их только усреднение ПО ВСЕМУ span —
// обязательный предфильтр, а не «размытие ради мягкости».
//
// Раскладывает интервал [c - span/2, c + span/2] на секторы кадра и их веса
// (сумма ровно 256). Возвращает число задействованных секторов.
#define ANG_TAPS_MAX 4

static inline int boxWeights(float c, float span, const uint16_t* frame,
                             const uint16_t** rows, int* w)
{
    float a = c - span * 0.5f;
    float b = a + span;
    int   i0 = (int)floorf(a);
    int   n  = (int)floorf(b) - i0 + 1;
    if (n < 1)            n = 1;
    if (n > ANG_TAPS_MAX) n = ANG_TAPS_MAX;

    const float inv = 256.0f / span;
    int acc = 0, best = 0;
    for (int j = 0; j < n; j++) {
        float lo = (float)(i0 + j);       if (lo < a) lo = a;
        float hi = (float)(i0 + j + 1);   if (hi > b) hi = b;
        int   ww = (int)((hi - lo) * inv + 0.5f);
        if (ww < 0) ww = 0;
        w[j] = ww;
        acc += ww;
        if (ww > w[best]) best = j;
        int s = (i0 + j) % 360;
        if (s < 0) s += 360;
        rows[j] = frame + s * LEDS_PER_SIDE;   // шаг в пикселях: кадр типизован как uint16
    }
    // Остаток округления кладём в самый весомый отвод — так он не может увести
    // маленький вес в минус, а суммарная яркость остаётся точной.
    w[best] += 256 - acc;
    return n;
}

// Считает цвет одного диода и пишет его в DMA-буфер. Возвращает r+g+b для ABL/RMS.
// Байт яркости (dst[0]) заполняется отдельным проходом — он зависит от суммы по кадру.
// Смешивание идёт ПОСЛЕ гамма-таблицы: усреднять надо световой поток, а не код,
// иначе наполовину перекрытый край выйдет втрое темнее, чем должен.
static inline uint32_t samplePix(const uint16_t** rows, const int* w, int n, int i,
                                 int sat, int kr, int kg, int kb, uint8_t* dst)
{
    int r = 0, g = 0, b = 0;
    for (int j = 0; j < n; j++) {
        // Распаковка RGB565 бесплатна: поле кода — сразу индекс своей таблицы.
        uint16_t v  = rows[j][i];
        int      ww = w[j];
        r += lut_tone5[ v >> 11        ] * ww;
        g += lut_tone6[(v >>  5) & 0x3F] * ww;
        b += lut_tone5[ v        & 0x1F] * ww;
    }
    r >>= 8;  g >>= 8;  b >>= 8;

    // Насыщенность — ДО баланса белого: нейтральный серый обязан остаться
    // нейтральным независимо от того, как скорректированы каналы дисплея.
    if (sat != 256) {
        int L = (77 * r + 150 * g + 29 * b) >> 8;
        r = constrain(L + (((r - L) * sat) >> 8), 0, 255);
        g = constrain(L + (((g - L) * sat) >> 8), 0, 255);
        b = constrain(L + (((b - L) * sat) >> 8), 0, 255);
    }
    // Баланс белого × радиальная компенсация — одним умножением на канал.
    r = (r * kr + 128) >> 8;
    g = (g * kg + 128) >> 8;
    b = (b * kb + 128) >> 8;

    dst[1] = (uint8_t)b;
    dst[2] = (uint8_t)g;
    dst[3] = (uint8_t)r;
    return (uint32_t)(r + g + b);
}

// Заполняет DMA-буфер данными сектора без отправки по SPI.
// Вызывается из renderingTask пока предыдущий буфер ещё передаётся —
// CPU и DMA работают параллельно.
//
// sector0 — ДРОБНЫЙ сектор, который в момент показа будет у ПЕРВОГО луча.
// span    — угол, который луч пройдёт, пока горят эти данные (см. boxWeights).
// Остальные лучи смещены на ±60°·N: знак задаётся global_arm_reverse и
// зависит от того, в какую сторону пронумерованы лучи в цепочке SK9822.
static void fillSectorIntoBuffer(uint8_t* buf, uint8_t buf_idx, float sector0, float span) {
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
        buf_bri_cache[buf_idx] = 0xE0;
        return;
    }

    const int sat = lut_sat_fxp;

    // Номер кадра считается по абсолютному времени, но ЗАЩЁЛКИВАЕТСЯ на границе
    // 60°. Шесть лучей рисуют по своему сектору одновременно, поэтому полная
    // картинка успевает лечь за 1/6 оборота — это и есть период обновления
    // изображения (RPM/10 кадров в секунду, 20 Гц на 200 об/мин). Смена кадра
    // посреди такой развёртки оставляла часть круга от кадра N, а часть от N+1:
    // на видео 10 fps это заметный шов. Защёлка привязывает смену к границе
    // развёртки, где шов есть и так.
    static uint32_t latched_frame_idx = 0;
    static int      latched_sector60  = -1;
    uint32_t frame_idx;
    if (totalFrames > 1 && frameDelay > 0) {
        // base приходит ненормированным (anchor + ω·Δt может уйти в минус или
        // за 360°), а нам нужен именно номер сектора 0..5.
        float sn = fmodf(sector0, 360.0f);
        if (sn < 0.0f) sn += 360.0f;
        int s60 = (int)(sn * (1.0f / 60.0f));
        if (s60 != latched_sector60) {
            latched_sector60 = s60;
            uint32_t elapsed_ms = millis() - lastFrameSwitchTime;
            latched_frame_idx = (elapsed_ms / frameDelay) % totalFrames;
        }
        // Новый файл может оказаться короче предыдущего, а защёлка пережила бы
        // загрузку со старым значением — и адресация ушла бы за конец буфера.
        if (latched_frame_idx >= totalFrames) latched_frame_idx = 0;
        frame_idx = latched_frame_idx;
    } else {
        frame_idx = 0;
    }
    // Кадр адресуется как массив пикселей: FRAME_SIZE кратен 2, ps_malloc
    // выравнивает начало, поэтому 16-битные чтения всегда выровнены.
    const uint16_t* frame = (const uint16_t*)(frameBuffer + frame_idx * FRAME_SIZE);

    uint8_t bri_level = global_brightness & 0x1F; // 0–31
    float   abl       = global_abl_limit;          // 0–100 %
    float   arm_step  = global_arm_reverse ? -(float)ARM_STEP_DEG : (float)ARM_STEP_DEG;

    // pixel_sum копится прямо здесь: отдельный проход по 528 диодам ради суммы
    // стоил столько же, сколько сам рендер, а значения и так уже в регистрах.
    uint32_t pixel_sum = 0;

    for (int ray = 0; ray < NUM_ARMS; ray++) {
        float bf = fmodf(sector0 + (float)ray * arm_step, 360.0f);
        if (bf < 0.0f) bf += 360.0f;
        // Обратная сторона луча видна с другой стороны колеса — зеркалим картинку.
        // bf ∈ [0,360) → bb ∈ (180,540], хватает одной проверки.
        float bb = 540.0f - bf;
        if (bb >= 360.0f) bb -= 360.0f;

        uint8_t* dst_f = led_ptr + (ray * LEDS_PER_ARM) * 4;                      // LED 0–43
        uint8_t* dst_b = led_ptr + (ray * LEDS_PER_ARM + LEDS_PER_ARM - 1) * 4;   // LED 87–44

        // Лучи расходятся строго из центра, поэтому все 44 диода стороны лежат на
        // одном радиусе — набор секторов и весов у них общий, и разбор угла
        // выносится из цикла по диодам.
        const uint16_t* rows_f[ANG_TAPS_MAX];
        int             wts_f[ANG_TAPS_MAX];
        const uint16_t* rows_b[ANG_TAPS_MAX];
        int             wts_b[ANG_TAPS_MAX];
        int nf = boxWeights(bf, span, frame, rows_f, wts_f);
        int nb = boxWeights(bb, span, frame, rows_b, wts_b);
        for (int i = 0; i < LEDS_PER_SIDE; i++) {
            int kr = gain_r[i], kg = gain_g[i], kb = gain_b[i];
            pixel_sum += samplePix(rows_f, wts_f, nf, i, sat, kr, kg, kb, dst_f + i * 4);
            pixel_sum += samplePix(rows_b, wts_b, nb, i, sat, kr, kg, kb, dst_b - i * 4);
        }
    }

    const uint32_t max_sum = (uint32_t)NUM_LEDS * 3 * 255;

    // --- RMS: реальная нагрузка тока = pixel_fill × bri / 31.
    rms_accum += (float)pixel_sum / (float)max_sum * (float)bri_level / 31.0f;

    // --- ABL: лимитирует общий ток (pixel_fill × bri / 31 ≤ abl/100).
    // Радиальная компенсация снижает pixel_sum примерно на 40 %, поэтому здесь
    // ABL сам возвращает часть общей яркости — периферия становится даже ярче.
    if (abl < 100.0f && pixel_sum > 0) {
        float bri_max = abl * 0.01f * 31.0f * (float)max_sum / (float)pixel_sum;
        if ((float)bri_level > bri_max) {
            bri_level = (uint8_t)bri_max; // floor — не превышаем лимит
        }
    }
    uint8_t bri_byte = 0xE0 | (bri_level & 0x1F);
    global_effective_brightness = bri_level;

    // Байт яркости меняется редко (авто-яркость раз в 100 мс, ABL плавно), поэтому
    // переписываем 528 ячеек только когда он реально изменился.
    if (buf_bri_cache[buf_idx] != bri_byte) {
        buf_bri_cache[buf_idx] = bri_byte;
        for (int i = 0; i < NUM_LEDS; i++) led_ptr[i * 4] = bri_byte;
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
//
// Угол считается на МОМЕНТ ПОКАЗА, а не на момент расчёта: данные загорятся
// только когда весь кадр уйдёт по SPI (SK9822_FRAME_US), и будут гореть до
// следующего обновления. Без этой поправки картинка уезжала тем сильнее,
// чем выше обороты, и «Angle Offset» приходилось бы крутить под скорость.
void renderingTask(void* pvParameters) {
    uint8_t active     = 0;
    bool    tx_pending = false;

    const float dma_frame_us = SK9822_FRAME_US;   // время передачи кадра по SPI
    uint32_t    tx_start_us  = 0;                 // когда ушла последняя транзакция
    float       fill_us      = 200.0f;            // измеренное время заполнения буфера
    float       show_us      = SK9822_FRAME_US;   // измеренный интервал между посылками

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

            // Куда, по нашей модели, ротор пришёл к моменту события. Считаем ДО
            // обновления ω/α — здесь нужны те значения, что действовали на интервале.
            float pred    = 0.0f;
            bool  pred_ok = false;
            if (anchor_ok) {
                float dtp = (float)(uint32_t)(ev_t - anchor_t);
                if (dtp < 1.0e6f) {
                    pred    = anchor_deg + rotor_omega * dtp + 0.5f * rotor_alpha * dtp * dtp;
                    pred_ok = true;
                }
            }

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

            // --- Якорь фазы (ФАПЧ) ---
            // Пока калибровка не набрана, привязываемся только к опорному датчику:
            // это в точности поведение старой прошивки с одним датчиком, без
            // риска добавить дрожание от разброса установки датчиков.
            //
            // Жёсткая привязка к каждому событию превращала остаточную погрешность
            // калибровки датчика в скачок фазы: за оборот получалось шесть чуть
            // повёрнутых копий изображения — у ступицы они сливаются, а на периферии
            // расходятся веером. Поэтому корректируем фазу лишь на долю невязки:
            // средняя фаза остаётся той же, а дрожание падает в 1/K раз.
            if (hall_cal_ready || ev_idx == 0) {
                int   sgn  = global_arm_reverse ? 1 : -1;
                float meas = (float)sgn * (float)ev_idx * ARM_STEP_DEG + rtc_hall_cal[ev_idx];

                if (pred_ok) {
                    float err = meas - pred;
                    while (err >   180.0f) err -= 360.0f;
                    while (err <= -180.0f) err += 360.0f;
                    // Большая невязка — не разброс датчиков, а потеря синхронизации:
                    // захватываем фазу жёстко, чтобы не ползти к ней целый оборот.
                    anchor_deg = (fabsf(err) > 10.0f) ? meas : (pred + err * HALL_PLL_K);
                } else {
                    anchor_deg = meas;
                }

                // pred накапливается от оборота к обороту — держим в [0, 360),
                // иначе за минуты вращения float потеряет точность в долях градуса.
                anchor_deg = fmodf(anchor_deg, 360.0f);
                if (anchor_deg < 0.0f) anchor_deg += 360.0f;

                anchor_t  = ev_t;
                anchor_ok = true;
            }
        }

        // --- Условия остановки отрисовки ---
        if (force_stop_display || power_state != PWR_FULL || !newFrameReady ||
            ota_in_progress || frame_loading) {
            if (rendering_active) {
                rendering_active = false;
                // Пауза на загрузку файла — штатная и частая (слайдшоу),
                // в лог её не пишем, иначе он забьётся.
                if (!frame_loading) webLog("[PWR] Rendering stopped");
                blankAllLEDs_DMA();
                global_abl_rms              = 0.0f;
                rms_accum                   = 0.0f;
                sectors_drawn               = 0;
                global_effective_brightness = global_brightness;
                global_render_span          = 0.0f;
            }
            continue;
        }

        // Допустимое «время дожития» без событий: полтора оборота, но не более 1.1 с.
        uint32_t rev = rotation_period;
        uint32_t age_limit = 1100000UL;
        if (rev > 0 && rev + rev / 2 < age_limit) age_limit = rev + rev / 2;

        // Порог оборотов проверяем здесь, а не только в loop(): во время
        // отрисовки renderingTask вытесняет loop(), и картинка «доживала» бы
        // до момента, когда до автомата питания дойдут руки. Гистерезис 20 RPM:
        // старт на RPM_RENDER_ON, продолжение работы — до RPM_RENDER_OFF.
        float rpm_now = (rev > 0) ? 60000000.0f / (float)rev : 0.0f;
        float rpm_thr = rendering_active ? rpm_render_off : rpm_render_on;

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
                global_render_span          = 0.0f;
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

        // LUT и таблицы усиления обновляем один раз за проход — powf не место
        // в горячем цикле.
        updateLUTIfNeeded();
        updateGainTablesIfNeeded();

        float last_psi  = 0.0f;   // угол ротора на момент последнего обновления ленты
        bool  psi_valid = false;

        while (true) {
            if (force_stop_display || power_state != PWR_FULL || !newFrameReady ||
                ota_in_progress || frame_loading) break;

            // Пришло новое событие Холла — выходим, чтобы переставить якорь фазы
            noInterrupts();
            bool got_new = (hall_seq != seen_seq);
            interrupts();
            if (got_new) break;

            uint32_t now  = micros();
            uint32_t dt_u = (uint32_t)(now - anchor_t);
            if (dt_u > age_limit) break;   // событие потерялось — наверх, на переоценку

            float dt0   = (float)dt_u;
            float psi   = anchor_deg + rotor_omega * dt0 + 0.5f * rotor_alpha * dt0 * dt0;
            float w_abs = fabsf(rotor_omega);

            // --- Темп обновления ---
            // Раньше строка перерисовывалась при смене ЦЕЛОГО сектора, то есть не
            // чаще раза на градус. Теперь шаг мельче: угол дробный, и промежуточные
            // положения дают ленте реальное преимущество. Оценка идёт по «сырому»
            // углу ротора — он монотонен, в отличие от угла на момент показа.
            if (psi_valid) {
                float adv = fabsf(psi - last_psi);
                if (adv < ANGLE_MIN_STEP) {
                    // Ждать долго — уступаем такт планировщику, иначе loop()
                    // (приоритет 1) не получит CPU: авто-яркость и телеметрия
                    // замерли бы на всё время вращения.
                    if (w_abs <= 0.0f || (ANGLE_MIN_STEP - adv) / w_abs > 1000.0f) vTaskDelay(1);
                    continue;
                }
            }

            // --- Момент, к которому считаем угол ---
            // Кадр загорится, когда уйдёт по SPI, и будет гореть до следующего
            // обновления. Целимся в середину этого интервала.
            float bus_busy = 0.0f;
            if (tx_pending) {
                float since = (float)(uint32_t)(now - tx_start_us);
                if (since < dma_frame_us) bus_busy = dma_frame_us - since;
            }
            float start_in = (fill_us > bus_busy) ? fill_us : bus_busy;  // когда уйдёт наш кадр
            float dtf      = dt0 + start_in + dma_frame_us + show_us * 0.5f;

            float base = anchor_deg + rotor_omega * dtf + 0.5f * rotor_alpha * dtf * dtf
                       + (float)global_angle_offset;

            // Угол, который луч пройдёт, пока горят эти данные. Меньше 1° брать
            // незачем — это шаг самого кадра; больше ANG_TAPS_MAX-1 нельзя.
            float span = w_abs * show_us;
            if (span < 1.0f) span = 1.0f;
            if (span > (float)(ANG_TAPS_MAX - 1)) span = (float)(ANG_TAPS_MAX - 1);

            // Захват буфера кадра. Флаг ставим ДО проверки newFrameReady, иначе
            // загрузчик успел бы проскочить в зазор между проверкой и началом
            // чтения и подменить буфер прямо под нами.
            render_in_fill = true;
            if (!newFrameReady) { render_in_fill = false; break; }

            uint8_t  idle = 1 - active;
            uint32_t t_fill0 = micros();
            fillSectorIntoBuffer(dma_buf[idle], idle, base, span);
            fill_us = (float)(uint32_t)(micros() - t_fill0);
            render_in_fill = false;
            sectors_drawn++;

            global_render_span    = span;
            global_render_fill_us = (uint32_t)fill_us;

            if (tx_pending) {
                spi_transaction_t* done;
                spi_device_get_trans_result(sk9822_spi, &done, portMAX_DELAY);
            }
            spi_device_queue_trans(sk9822_spi, &spi_trans[idle], portMAX_DELAY);
            uint32_t t_queued = micros();
            // Интервал между посылками меряем, а не моделируем: он и есть время
            // свечения кадра, а значит и ширина предфильтра. Модель промахнулась бы
            // на любой задержке планировщика или промахе кеша PSRAM.
            if (tx_pending) {
                float iv = (float)(uint32_t)(t_queued - tx_start_us);
                if (iv < dma_frame_us) iv = dma_frame_us;
                if (iv > 20000.0f)     iv = 20000.0f;
                show_us += (iv - show_us) * 0.25f;
            }
            tx_start_us = t_queued;
            active      = idle;
            tx_pending  = true;
            last_psi    = psi;
            psi_valid   = true;
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

// =====================================================================
//                        ЗАРЯД БАТАРЕИ
// =====================================================================
// Кривая разряда LiPo 1S: ЭДС (мВ) → заряд (%).
// Середина крайне пологая — 3.73…3.87 В это весь диапазон 20…60 %, то есть
// 20 мВ стоят 6 % заряда. Поэтому линейная шкала 3.0…4.2 В врала сама по себе,
// а без компенсации просадки точность в этой зоне недостижима в принципе.
static const uint16_t BATT_OCV_MV[] = {
    3270, 3610, 3690, 3710, 3730, 3750, 3770, 3790, 3800, 3820, 3840,
    3850, 3870, 3910, 3950, 3980, 4020, 4080, 4110, 4150, 4200
};
static const uint8_t BATT_OCV_PCT[] = {
       0,    5,   10,   15,   20,   25,   30,   35,   40,   45,   50,
      55,   60,   65,   70,   75,   80,   85,   90,   95,  100
};

static uint8_t battSocFromOcv(int32_t mv) {
    const int n = sizeof(BATT_OCV_MV) / sizeof(BATT_OCV_MV[0]);
    if (mv <= (int32_t)BATT_OCV_MV[0])     return 0;
    if (mv >= (int32_t)BATT_OCV_MV[n - 1]) return 100;
    for (int i = 1; i < n; i++) {
        if (mv < (int32_t)BATT_OCV_MV[i]) {
            int32_t v0 = BATT_OCV_MV[i - 1], v1 = BATT_OCV_MV[i];
            int32_t p0 = BATT_OCV_PCT[i - 1], p1 = BATT_OCV_PCT[i];
            return (uint8_t)(p0 + (mv - v0) * (p1 - p0) / (v1 - v0));
        }
    }
    return 100;
}

static float    batt_sag_k     = (float)BATT_SAG_MV_AT_FULL; // мВ просадки при RMS = 1.0
static uint16_t batt_chg_rise  = BATT_CHG_RISE_MV;           // мВ подъёма от тока заряда
static uint16_t batt_rest_mv   = 0;      // последнее напряжение на холостом ходу
static uint32_t batt_rest_ms   = 0;
static uint16_t batt_prev_mv   = 0;      // напряжение на предыдущем такте (1 с назад)
static bool     batt_prev_usb  = false;
static float    batt_ocv_filt  = 0.0f;
static bool     batt_soc_valid = false;

// Восстанавливает ЭДС и переводит её в проценты. Вызывается раз в секунду.
//
// Ток нагрузки отдельно не меряется — он и не нужен: global_abl_rms по
// построению и есть нормированная загрузка по току (заполнение кадра × bri/31).
// Достаточно знать, сколько милливольт просадки приходится на единицу RMS,
// а этот коэффициент прошивка определяет сама: в момент включения отрисовки
// заряд физически измениться не успевает, поэтому вся разница напряжения
// относительно холостого хода — это и есть просадка.
static void updateBatterySoc(uint32_t vbat_mv, bool usb_ok, uint8_t chg) {
    uint32_t now = millis();
    float    rms = global_abl_rms;

    if (power_state == PWR_OFF && !usb_ok) {
        // Холостой ход: опорная точка для самокалибровки
        batt_rest_mv = (uint16_t)vbat_mv;
        batt_rest_ms = now;
    } else if (power_state == PWR_FULL && !usb_ok && rms > 0.05f &&
               batt_rest_mv != 0 && (now - batt_rest_ms) < 120000) {
        // Опорная точка не старше двух минут — за это время заряд ещё не ушёл
        float k = ((float)batt_rest_mv - (float)vbat_mv - (float)BATT_BASE_SAG_MV) / rms;
        if (k > 100.0f && k < 4000.0f) batt_sag_k += (k - batt_sag_k) * 0.05f;
    }

    // Подъём от тока заряда меряем тем же приёмом, что и просадку: в секунду
    // подключения зарядника заряд ещё не изменился, значит весь скачок
    // напряжения — это I_зар × R_вн. Только на холостом ходу, иначе в скачок
    // подмешается изменение нагрузки.
    bool usb_edge = (usb_ok != batt_prev_usb);
    if (usb_edge && usb_ok && power_state == PWR_OFF && batt_prev_mv != 0) {
        int32_t d = (int32_t)vbat_mv - (int32_t)batt_prev_mv;
        if (d > 0 && d < 400) batt_chg_rise = (uint16_t)d;
    }
    batt_prev_usb = usb_ok;
    batt_prev_mv  = (uint16_t)vbat_mv;

    int32_t ocv = (int32_t)vbat_mv;
    if (power_state != PWR_OFF) ocv += BATT_BASE_SAG_MV;
    ocv += (int32_t)(rms * batt_sag_k);
    if (chg == 1) {
        // Ток втекает в батарею — напряжение завышено. К концу заряда зарядник
        // уходит в CV, ток спадает, и вместе с ним подъём: масштабируем по
        // остатку до 4.2 В, иначе на финише заряда шкала занижала бы.
        float taper = (4200.0f - (float)vbat_mv) / (float)BATT_CHG_TAPER_MV;
        ocv -= (int32_t)((float)batt_chg_rise * constrain(taper, 0.0f, 1.0f));
    }

    // Смена режима питания меняет саму формулу — фильтр начинаем заново,
    // иначе его инерция вылезет как ложный провал показаний.
    if (!batt_soc_valid || usb_edge) batt_ocv_filt = (float)ocv;
    else                             batt_ocv_filt += ((float)ocv - batt_ocv_filt) * 0.2f;

    uint8_t target = battSocFromOcv((int32_t)batt_ocv_filt);
    if (chg == 2) target = 100;              // зарядник отрапортовал окончание

    // На зарядке без нагрузки шкала не имеет права падать: батарея набирает
    // заряд, и любое снижение показаний — ошибка модели, а не физика. Оценка
    // подъёма остаётся приблизительной (величину тока IP2312U не сообщает),
    // поэтому просто не даём ей утащить показания вниз.
    // При работающей отрисовке запрет не действует: там ток диодов может
    // превышать зарядный, и батарея реально разряжается, несмотря на USB.
    if (usb_ok && rms < 0.05f && batt_soc_valid && target < pwr_cache.soc) {
        target = pwr_cache.soc;
    }

    // Ограничение скорости 1 %/с: остаточная ошибка модели выбирается плавно,
    // а не прыжком. Первое значение ставим сразу, иначе шкала ползла бы от нуля.
    if (!batt_soc_valid)             { pwr_cache.soc = target; batt_soc_valid = true; }
    else if (target > pwr_cache.soc)   pwr_cache.soc++;
    else if (target < pwr_cache.soc)   pwr_cache.soc--;

    pwr_cache.ocv_mv = (int16_t)batt_ocv_filt;
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

    updateBatterySoc(vbat, usb_ok, chg);
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
// Имя последнего воспроизведённого файла — для автозапуска после перезагрузки.
// Запись отложена: putString стирает страницу флеша, а на время записи кеш
// инструкций отключается на ОБОИХ ядрах, и renderingTask, исполняемый из флеша,
// замирает на десятки миллисекунд. В слайдшоу это происходило каждые 10 секунд
// (заодно 8600 циклов записи в сутки — впустую жгло ресурс флеша).
static String pending_last_file;

// Сбрасывает отложенное имя в NVS. Вызывать только когда отрисовка остановлена.
static void flushLastFile() {
    if (pending_last_file.length() == 0) return;
    if (prefs.getString("last_file", "") != pending_last_file) {
        prefs.putString("last_file", pending_last_file);
    }
    pending_last_file = "";
}

// Будит renderingTask вне очереди. Нужно загрузчику файла: иначе флаг
// frame_loading будет замечен только на следующем событии Холла, и лента
// успеет отсветить лишний кусок оборота старым кадром.
void wakeRenderingTask() {
    if (hallSemaphore) xSemaphoreGive(hallSemaphore);
}

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
            // Отрисовка остановлена — самое время сбросить отложенные записи
            // в NVS: помешать они уже никому не могут.
            flushLastFile();
            flushSettings();
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
            last_dcdc_on_time = millis();
            last_motion_ms    = last_dcdc_on_time;
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
    flushLastFile();
    flushSettings();
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
    loadSettingsFromNVS();              // до построения таблиц: они зависят от гаммы и балансов
    loadHallCalibration();

    // Таблицы рендера — до первого кадра: пока они не построены,
    // радиальные коэффициенты нулевые и картинка была бы чёрной.
    rebuildGammaLUT();
    updateGainTablesIfNeeded();
    last_web_activity_time = millis();  // Считаем загрузку страницы активностью

    // Эндпоинт телеметрии питания — отдаёт кешированные данные без обращения к АЦП.
    server.on("/battery", HTTP_GET, [](AsyncWebServerRequest *request){
        // Фоновый поллинг — не сбрасывает таймер активности.
        // soc — заряд по восстановленной ЭДС; остальное для калибровки:
        // ocv не должен меняться при включении отрисовки и подключении зарядника,
        // sag и rise показывают, к чему сошлись самокалибровки.
        char buf[240];
        snprintf(buf, sizeof(buf),
            "{\"vbat\":%d,\"vusb\":%d,\"chg\":%u,\"usb\":%s,\"connected\":%s"
            ",\"soc\":%u,\"ocv\":%d,\"sag\":%d,\"rise\":%u}",
            (int)pwr_cache.vbat_mv, (int)pwr_cache.vusb_mv,
            (unsigned)pwr_cache.chg,
            pwr_cache.usb ? "true" : "false",
            pwr_cache.usb ? "true" : "false",
            (unsigned)pwr_cache.soc, (int)pwr_cache.ocv_mv,
            (int)batt_sag_k, (unsigned)batt_chg_rise
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

    // --- Отложенная запись настроек ---
    // Ждать выключения питания необязательно: пока колесо не раскручено до
    // порога, renderingTask ничего не рисует, и запись во флеш никому не мешает.
    // Пауза в 3 с нужна, чтобы перетаскивание ползунка (десятки запросов
    // /settings подряд) уложилось в одну запись.
    if (settings_dirty) {
        if (settings_dirty_since == 0) settings_dirty_since = now_ms;
        else if (power_state != PWR_FULL && (now_ms - settings_dirty_since) > 3000) {
            flushSettings();
            settings_dirty_since = 0;
        }
    } else {
        settings_dirty_since = 0;
    }

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
        // Не пишем в NVS прямо здесь: запись во флеш заморозила бы рендер.
        // Уйдёт на диск при остановке колеса или перед deep sleep.
        pending_last_file = nextFile;
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
    // PWR_FULL → PWR_SPINUP: обороты упали ниже RPM_RENDER_OFF (гистерезис 20 RPM)
    // любое → PWR_OFF      : нет вращения дольше 3 с либо Stop из Web UI
    bool content_ready = newFrameReady && !force_stop_display && !ota_in_progress;

    if (!content_ready) {
        if (power_state != PWR_OFF) applyPowerState(PWR_OFF);
    } else {
        switch (power_state) {
            case PWR_OFF:
                if (vibration || play_pending) applyPowerState(PWR_SPINUP);
                break;

            case PWR_SPINUP:
                if (rpm >= rpm_render_on) {
                    applyPowerState(PWR_FULL);
                } else if (hall_age_us > 3000000UL &&
                           (now_ms - last_play_ms)      > 10000 &&
                           (now_ms - last_dcdc_on_time) > 2000) {
                    webLog("[PWR] No rotation >3s");
                    applyPowerState(PWR_OFF);
                }
                break;

            case PWR_FULL:
                if (rpm < rpm_render_off) applyPowerState(PWR_SPINUP);
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
