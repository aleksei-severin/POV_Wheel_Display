#include "config.h"
#include "network.h"
#include <WiFi.h>

#include <Arduino.h>
#include <SPI.h>
#include <Wire.h>
#include <ICM45605S.h>
#include <FastLED.h>
#include <LittleFS.h>
#include <BH1750.h>
#include <vector>
#include <ESPAsyncWebServer.h>
#include "driver/gpio.h"
#include "driver/spi_master.h"
#include "esp_heap_caps.h"
#include "esp_system.h"
#include "esp_task_wdt.h"

// Экспортируем сервер из network.cpp для добавления нового эндпоинта
extern AsyncWebServer server;

// --- ИНИЦИАЛИЗАЦИЯ ГЛОБАЛЬНЫХ ПЕРЕМЕННЫХ ---
volatile uint8_t global_brightness = 8; // единицы SK9822 (0–31)

RTC_DATA_ATTR uint8_t min_brightness = 2;  // единицы SK9822 (0–31)
RTC_DATA_ATTR uint8_t max_brightness = 16; // единицы SK9822 (0–31)
RTC_DATA_ATTR volatile int global_angle_offset = 91;

uint8_t* frameBuffer = nullptr; 

// Глобальные переменные для поддержки GIF анимаций
uint32_t currentFrameIndex = 0;
uint32_t totalFrames = 1;
uint16_t frameDelay = 100;
uint32_t lastFrameSwitchTime = 0;

volatile bool newFrameReady = false;
CRGB leds[NUM_LEDS];

std::vector<String> savedFiles;

volatile uint32_t last_magnet_time = 0;   
volatile uint32_t revolution_time = 0;    
volatile bool magnet_triggered = false;
volatile uint32_t last_power_toggle_time = 0; // Защита от EMI глитчей

ICM456xx imu(SPI, PIN_CS);
BH1750 lightMeter;

// --- ГЛОБАЛЬНЫЕ ПЕРЕМЕННЫЕ (Замените старые переменные времени этими) ---
volatile bool hall_event = false;
volatile uint32_t last_hall_time = 0;
volatile uint32_t rotation_period = 0;
RTC_DATA_ATTR bool force_stop_display = false;
volatile uint32_t last_web_activity_time = 0; // Добавлено: отслеживание активности в Web UI

// --- ПЕРЕМЕННЫЕ BQ25798 ---
volatile bool bq_interrupt_flag = false;

volatile uint32_t last_dcdc_off_time = 0;
volatile uint32_t last_dcdc_on_time = 0;  // Момент включения DCDC (для защиты от раннего выключения)
bool peripherals_active = true;
//volatile bool blink_ok_flag = false;
volatile float last_lux_value = 0.0f; // Последнее валидное показание BH1750 (lux)

// --- КЕШ ДАННЫХ БАТАРЕИ (обновляется из loop(), читается из /battery без Wire) ---
// Доступ только из Core 1 (loop) на запись и из Core 0 (AsyncWebServer) на чтение.
// Отдельные поля — примитивы ≤32 бит, запись атомарна на Xtensa — мьютекс не нужен.
struct BatteryCache {
    int16_t  vbat      = 0;
    int16_t  ibat      = 0;
    int16_t  vbus      = 0;
    int16_t  ibus      = 0;
    uint8_t  chg_stat  = 0;
    bool     vbus_ok   = false;
};
static BatteryCache bat_cache;

// --- ESP-IDF SPI DMA для SK9822 ---
// Буферы и транзакции — фиксированный максимум для 8 лучей × 76 диодов = 608.
// Неиспользуемые диоды гасятся в fillSectorIntoBuffer — каждый кадр они получают команду выкл.
#define SK9822_MAX_LEDS   (8 * 76)                              // 608 — максимум при 8 лучах
#define SK9822_END_FRAMES (SK9822_MAX_LEDS / 2)                // 304 end-frame бита (по спецификации N/2)
#define SK9822_BUF_SIZE   (4 + SK9822_MAX_LEDS * 4 + SK9822_END_FRAMES) // максимальный размер буфера

// Накопитель RMS за текущий оборот: заполняется в fillSectorIntoBuffer,
// публикуется в global_abl_rms в конце оборота renderingTask.
static float rms_accum = 0.0f;

static spi_device_handle_t sk9822_spi   = nullptr;
static uint8_t*            dma_buf[2]   = {nullptr, nullptr}; // Два буфера для ping-pong DMA
static uint8_t*            dma_tx_buffer = nullptr;           // = dma_buf[0], для sendLEDs_DMA
static spi_transaction_t   spi_trans[2] = {};                 // Предвыделенные транзакции (не на стеке)
static SemaphoreHandle_t   hallSemaphore = nullptr;
static SemaphoreHandle_t   dmaMutex      = nullptr;
SemaphoreHandle_t          fileLoaderSemaphore = nullptr;
String                     pendingFilePath;

volatile bool wakeup_event = false;
volatile bool request_play_flag = false;

RTC_DATA_ATTR volatile float global_gamma         = 3.5f;
RTC_DATA_ATTR volatile float global_saturation    = 1.0f;
RTC_DATA_ATTR volatile float global_contrast      = 10.0f;
RTC_DATA_ATTR volatile float global_r_gain        = 100.0f; // 0..200 %, 100 = без изменений
RTC_DATA_ATTR volatile float global_g_gain        = 100.0f;
RTC_DATA_ATTR volatile float global_b_gain        = 100.0f;
RTC_DATA_ATTR volatile uint16_t wheel_circumference = 2355;
RTC_DATA_ATTR volatile uint8_t  global_num_arms     = 7;
RTC_DATA_ATTR volatile float    global_abl_limit    = 100.0f; // ABL: 0–100 %, 100 = без ограничения
volatile float                  global_abl_rms      = 0.0f;  // RMS загрузка тока 0.0–1.0, не сохраняем в RTC
volatile uint8_t                global_effective_brightness = 8; // bri_level после ABL; совпадает с global_brightness пока нет рендеринга
uint8_t lut_r[256];
uint8_t lut_g[256];
uint8_t lut_b[256];

// Хелперы для I2C чтения BQ25798
uint8_t readBQ8(uint8_t reg) {
    Wire.beginTransmission(BQ25792_ADDR);
    Wire.write(reg);
    Wire.endTransmission(false);
    Wire.requestFrom((uint16_t)BQ25792_ADDR, (uint8_t)1);
    if (Wire.available()) return Wire.read();
    return 0;
}

int16_t readBQ16(uint8_t reg) {
    Wire.beginTransmission(BQ25792_ADDR);
    Wire.write(reg);
    Wire.endTransmission(false);
    Wire.requestFrom((uint16_t)BQ25792_ADDR, (uint8_t)2);
    if (Wire.available() >= 2) {
        return (Wire.read() << 8) | Wire.read();
    }
    return 0;
}

void IRAM_ATTR wakeupInterruptHandler() {
    // Реагируем только если периферия спит
    if (!peripherals_active) {
        wakeup_event = true;
    }
}

void IRAM_ATTR magnetInterruptHandler() {
    // IO4 питается от 5В DCDC — показания не валидны пока DCDC выключен.
    // Игнорируем все сигналы пока периферия неактивна (защита от глитчей при обесточивании).
    if (!peripherals_active) {
        return;
    }

    uint32_t now = micros();

    // 50ms аппаратный антидребезг
    if (now - last_hall_time > 50000) {
        rotation_period = now - last_hall_time;
        last_hall_time = now;
        hall_event = true;

        // Будим renderingTask для нового оборота
        if (hallSemaphore) {
            BaseType_t xHigherPriorityTaskWoken = pdFALSE;
            xSemaphoreGiveFromISR(hallSemaphore, &xHigherPriorityTaskWoken);
            portYIELD_FROM_ISR(xHigherPriorityTaskWoken);
        }
    }
}

// Обработчик прерывания BQ25798 (INTn на GPIO 21)
void IRAM_ATTR bqInterruptHandler() {
    bq_interrupt_flag = true;
}

void initBQ25792() {
    // DIS_LDO
    Wire.beginTransmission(BQ25792_ADDR); 
    Wire.write(0x12); // REG12_Charger_Control_3 Register
    Wire.write(0x04); // Значение 0x04: DIS_LDO = 1
    Wire.endTransmission(); 

   // DIS REG08_Precharge_Control
    Wire.beginTransmission(BQ25792_ADDR); 
    Wire.write(0x08); // REG08_Precharge_Control Register
    Wire.write(0x19); // Precharge 1000mA
    Wire.endTransmission(); 

    // 1. Перезапуск цикла заряда: сначала отключаем зарядку (ИСПРАВЛЕНО: 0x82 вместо 0x1A, чтобы не включать HIZ)
    Wire.beginTransmission(BQ25792_ADDR); 
    Wire.write(0x0F); // Регистр Charger Control 0
    Wire.write(0x82); // Значение 0x82: EN_CHG = 0, EN_HIZ = 0
    Wire.endTransmission(); 
    delay(500);       // Пауза 500 мс для стабилизации

    // 2. Включаем зарядку обратно (ИСПРАВЛЕНО: 0xA2 вместо 0x3A, чтобы не включать HIZ)
    Wire.beginTransmission(BQ25792_ADDR); 
    Wire.write(0x0F); 
    Wire.write(0xA2); // Значение 0xA2: EN_CHG = 1, EN_HIZ = 0
    Wire.endTransmission();

    // 3. Отключаем сторожевой таймер (Watchdog)
    Wire.beginTransmission(BQ25792_ADDR); 
    Wire.write(0x10); // Регистр Charger Control 1
    Wire.write(0x00); // Значение 0x00: отключает Watchdog. Иначе чип сбросит настройки через ~40 сек.
    Wire.endTransmission();

    // 4. Настройка минимального системного напряжения (VSYSMIN) - ИСПРАВЛЕНО ДЛЯ 1S БАТАРЕИ
    Wire.beginTransmission(BQ25792_ADDR); 
    Wire.write(0x00); // Регистр Minimal System Voltage (VSYSMIN)
    Wire.write(0x04); // Значение 0x04: 2.5V + (4 * 0.25V) = 3.5V. (0x06 было 4.0V, что блокировало зарядку 1S)
    Wire.endTransmission();

    // Установка лимита напряжения заряда (VBATREG) на 4.2V - Перенесено сюда для надежности
    Wire.beginTransmission(BQ25792_ADDR);
    Wire.write(0x01); Wire.write(0x01); // 4.2V MSB
    Wire.endTransmission();
    Wire.beginTransmission(BQ25792_ADDR);
    Wire.write(0x02); Wire.write(0xA4); // 4.2V LSB (0x01A4 = 420 * 10mV = 4200mV)
    Wire.endTransmission();

    // 5. Установка лимита входного тока (IINDPM) на 1.5 А
    Wire.beginTransmission(BQ25792_ADDR); 
    Wire.write(0x06); // Регистр Input Current Limit (Старший байт - MSB)
    Wire.write(0x00); // Значение 0x00
    Wire.endTransmission();
    
    Wire.beginTransmission(BQ25792_ADDR); 
    Wire.write(0x07); // Регистр Input Current Limit (Младший байт - LSB)
    Wire.write(0x96); // Значение 0x96
    Wire.endTransmission();
    // Итоговое значение: 0x0096 (в десятичной системе = 150). Шаг 10 мА -> 1500 мА.

    // VINDPM = 4.3V (Input voltage regulation point)
    Wire.beginTransmission(BQ25792_ADDR);
    Wire.write(0x05);        // VINDPM register MSB
    Wire.write(0x24);
    Wire.endTransmission();
    
    // 6. Установка лимита тока зарядки (ICHG) на 3.0 А
    Wire.beginTransmission(BQ25792_ADDR); 
    Wire.write(0x03); // Регистр Charge Current Limit (Старший байт - MSB)
    Wire.write(0x01); // Значение 0x01
    Wire.endTransmission();
    
    Wire.beginTransmission(BQ25792_ADDR); 
    Wire.write(0x04); // Регистр Charge Current Limit (Младший байт - LSB)
    Wire.write(0x2C); // Значение 0x2C
    Wire.endTransmission();
    // Итоговое значение: 0x012C (в десятичной системе = 300). Шаг 10 мА -> 3000 мА.
}

// Инициализация аппаратного SPI с DMA вместо FastLED
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

    // Выделяем оба DMA-буфера под максимум (8 лучей).
    // End-frame заполняется полностью 0xFF — при меньшем числе лучей
    // SPI передаёт меньше байт, и хвост не уходит на шину.
    for (int b = 0; b < 2; b++) {
        dma_buf[b] = (uint8_t*)heap_caps_malloc(SK9822_BUF_SIZE, MALLOC_CAP_DMA);
        assert(dma_buf[b] != nullptr);
        memset(dma_buf[b], 0x00, SK9822_BUF_SIZE);                              // Весь буфер в 0
        memset(dma_buf[b] + 4 + SK9822_MAX_LEDS * 4, 0xFF, SK9822_END_FRAMES); // End-frame
        spi_trans[b].tx_buffer = dma_buf[b];
        spi_trans[b].length    = SK9822_BUF_SIZE * 8;                           // Фиксированная длина навсегда
    }
    dma_tx_buffer = dma_buf[0]; // sendLEDs_DMA использует dma_buf[0]
    dmaMutex = xSemaphoreCreateMutex();
    webLog("[SYS] SK9822 DMA ready");
}

// Гасит все SK9822_MAX_LEDS (608) диодов — ток=0, цвет=0.
// Используется при включении DCDC и при остановке рендеринга.
// НЕ читает leds[] — не зависит от содержимого FastLED-массива.
static void blankAllLEDs_DMA() {
    if (!dma_tx_buffer || !sk9822_spi || !dmaMutex) return;
    xSemaphoreTake(dmaMutex, portMAX_DELAY);
    uint8_t* led_ptr = dma_tx_buffer + 4;
    for (int i = 0; i < SK9822_MAX_LEDS; i++) {
        led_ptr[i * 4 + 0] = 0xE0; // SK9822: ток=0
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

// Заменитель FastLED.show() для статусных заливок.
// Отправляет leds[0..NUM_LEDS-1] + гасит лучи 5–8 (NUM_LEDS..SK9822_MAX_LEDS-1).
// Вызывать только когда leds[] содержит корректные данные (не при старте).
void sendLEDs_DMA() {
    if (!dma_tx_buffer || !sk9822_spi || !dmaMutex) return;
    xSemaphoreTake(dmaMutex, portMAX_DELAY);
    uint8_t* led_ptr = dma_tx_buffer + 4;
    for (int i = 0; i < NUM_LEDS; i++) {
        led_ptr[i * 4 + 0] = 0xFF;
        led_ptr[i * 4 + 1] = leds[i].b;
        led_ptr[i * 4 + 2] = leds[i].g;
        led_ptr[i * 4 + 3] = leds[i].r;
    }
    for (int i = NUM_LEDS; i < SK9822_MAX_LEDS; i++) {
        led_ptr[i * 4 + 0] = 0xE0;
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

// Заполняет DMA-буфер данными сектора без отправки по SPI.
// Вызывается из renderingTask пока предыдущий буфер ещё передаётся — CPU и DMA работают параллельно.
// Кеш параметров LUT — обновляются в начале каждого оборота в renderingTask,
// вне горячего цикла секторов, чтобы не задерживать рендеринг на вызов powf().
static float   lut_last_gamma    = -1.0f;
static float   lut_last_contrast = -999.0f;
static float   lut_last_r        = -1.0f;
static float   lut_last_g        = -1.0f;
static float   lut_last_b        = -1.0f;
static float   lut_last_sat      = -1.0f;
static int16_t lut_sat_fxp       = 256;

// Проверяет изменение параметров и пересчитывает LUT если нужно.
// Вызывается один раз в начале оборота — не внутри fillSectorIntoBuffer.
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

static void fillSectorIntoBuffer(uint8_t* buf, int current_sector) {
    if (frameBuffer == nullptr) {
        // Буфер ещё не загружен или уже освобождён — гасим все диоды,
        // чтобы не отправить устаревшие данные из предыдущей анимации.
        uint8_t* led_ptr = buf + 4;
        for (int i = 0; i < SK9822_MAX_LEDS; i++) {
            led_ptr[i * 4 + 0] = 0xE0; // SK9822: 111bbbbb, brightness=0 → ток=0
            led_ptr[i * 4 + 1] = 0;
            led_ptr[i * 4 + 2] = 0;
            led_ptr[i * 4 + 3] = 0;
        }
        return;
    }
    // LUT и sat_fxp обновляются снаружи (updateLUTIfNeeded) — здесь читаем напрямую.
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
    uint8_t* led_ptr     = buf + 4;

    // Количество лучей читаем один раз — не меняется в середине кадра
    uint8_t num_arms  = global_num_arms;           // 1–8
    int     leds_total = num_arms * 76;
    int     step_deg  = 360 / num_arms;

    uint8_t bri_level = global_brightness & 0x1F; // 0–31
    float   abl       = global_abl_limit;          // 0–100 %

    // Гасим неиспользуемые лучи сверх num_arms.
    for (int i = leds_total; i < SK9822_MAX_LEDS; i++) {
        led_ptr[i * 4 + 0] = 0xE0;
        led_ptr[i * 4 + 1] = 0;
        led_ptr[i * 4 + 2] = 0;
        led_ptr[i * 4 + 3] = 0;
    }

    // Рендерим цвета в led_ptr с временным bri_byte=0xFF (bri_level игнорируется пока).
    // ABL считается ПО РЕЗУЛЬТАТУ рендера — те же данные что и RMS, одна шкала.
    for (int ray = 0; ray < num_arms; ray++) {
        int sector_front = (current_sector + ray * step_deg) % 360;
        int sector_back  = (540 - sector_front) % 360;

        const uint8_t* src_f = frameBuffer + anim_offset + sector_front * 38 * 3;
        const uint8_t* src_b = frameBuffer + anim_offset + sector_back  * 38 * 3;

        for (int i = 0; i < 38; i++) {
            // --- Передняя половина луча (LEDs 0–37) ---
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
            int idx_a = (ray * 76 + i) * 4;
            led_ptr[idx_a + 0] = 0xFF; // bri_byte пропишем позже
            led_ptr[idx_a + 1] = b;
            led_ptr[idx_a + 2] = g;
            led_ptr[idx_a + 3] = r;

            // --- Задняя половина луча (LEDs 38–75) ---
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
            int idx_b = (ray * 76 + 75 - i) * 4;
            led_ptr[idx_b + 0] = 0xFF; // bri_byte пропишем позже
            led_ptr[idx_b + 1] = bb;
            led_ptr[idx_b + 2] = gb;
            led_ptr[idx_b + 3] = rb;
        }
    }

    // --- ABL: считаем pixel_sum из уже отрендеренного led_ptr (после LUT+насыщенности).
    // Та же база что и у RMS — оба показателя гарантированно в одной шкале.
    if (abl < 100.0f) {
        uint32_t pixel_sum = 0;
        for (int i = 0; i < leds_total; i++) {
            pixel_sum += led_ptr[i * 4 + 1]; // B
            pixel_sum += led_ptr[i * 4 + 2]; // G
            pixel_sum += led_ptr[i * 4 + 3]; // R
        }
        uint32_t max_sum   = (uint32_t)leds_total * 3 * 255;
        uint32_t limit_sum = (uint32_t)(max_sum * abl * 0.01f);
        if (pixel_sum > limit_sum && pixel_sum > 0) {
            bri_level = (uint8_t)((uint32_t)bri_level * limit_sum / pixel_sum);
        }
    }
    uint8_t bri_byte = 0xE0 | bri_level;
    global_effective_brightness = bri_level; // для отображения NOW в UI

    // Проставляем финальный bri_byte во все активные слоты.
    for (int i = 0; i < leds_total; i++) {
        led_ptr[i * 4 + 0] = bri_byte;
    }

    // --- RMS: та же сумма RGB что считал ABL, умноженная на итоговый bri_level/31.
    // 100% = все активные лучи белые при bri=31.
    {
        uint32_t sector_sum = 0;
        for (int i = 0; i < leds_total; i++) {
            sector_sum += led_ptr[i * 4 + 1]; // B
            sector_sum += led_ptr[i * 4 + 2]; // G
            sector_sum += led_ptr[i * 4 + 3]; // R
        }
        uint32_t max_sector = (uint32_t)leds_total * 3 * 255;
        float sector_load = (max_sector > 0)
            ? (float)sector_sum / (float)max_sector * (float)bri_level / 31.0f
            : 0.0f;
        rms_accum += sector_load;
    }
}

// Задача рендеринга: Core 1, приоритет 2.
// Ping-pong DMA: spi_device_queue_trans ставит передачу в очередь,
// spi_device_get_trans_result ждёт завершения не держа spinlock —
// WiFi ISR (prio >10) прерывает задачу когда нужно обработать пакеты.
void renderingTask(void* pvParameters) {
    static bool     rendering_active = false; // Флаг: были ли обороты >125 RPM (для лога при снижении)
    uint8_t active     = 0;
    bool    tx_pending = false;

    // Отписываем IDLE-задачу Core 1 от Task WDT: renderingTask (приоритет 18)
    // занимает Core 1 почти непрерывно и IDLE не получает тиков.
    esp_task_wdt_delete(xTaskGetIdleTaskHandleForCPU(1));
    // renderingTask НЕ подписываем на WDT: taskYIELD() и vTaskDelay внутри цикла
    // могут передать CPU системным задачам с prio>18, и WDT сработает раньше сброса.

    while (true) {
        // Таймаут 1100мс: если холл молчит дольше 1100мс — скорость точно <60RPM.
        // При portMAX_DELAY задача зависала в ожидании и не успевала погасить
        // светодиоды при резкой остановке — loop() выключал питание только через 3с.
        xSemaphoreTake(hallSemaphore, pdMS_TO_TICKS(1100));

        if (force_stop_display || !peripherals_active || !newFrameReady) continue;

        noInterrupts();
        uint32_t t0     = last_hall_time;
        uint32_t period = rotation_period;
        interrupts();

        // Пропускаем рендеринг ниже 60 RPM (period > 1000 мс).
        // Критично: при первом обороте после долгой остановки
        // rotation_period = micros() - last_hall_time = десятки миллионов мкс.
        // Без этой проверки renderingTask уходит в busy-wait на минуты,
        // вытесняя WiFi/loop, WDT срабатывает через 5 с → краш → перезагрузка.
        //
        // Дополнительно: если с момента последнего сигнала холла прошло >1000мс —
        // обороты точно <60RPM независимо от сохранённого rotation_period.
        uint32_t now_us = micros();
        uint32_t age_us = (now_us >= t0) ? (now_us - t0) : (0xFFFFFFFFUL - t0 + now_us + 1);
        if (period == 0 || period > 1000000 || age_us > 1000000) {
            if (rendering_active) {
                rendering_active = false;
                webLog("[PWR] RPM <60, rendering paused");
                blankAllLEDs_DMA();
                global_abl_rms              = 0.0f;
                rms_accum                   = 0.0f;
                global_effective_brightness = global_brightness; // нет рендера → ABL не действует
            }
            continue;
        }
        if (!rendering_active) {
            rendering_active = true;
            // Питание уже включено hall_event'ом в loop() — здесь только логируем
            // факт достижения порога рендеринга.
            webLog("[PWR] RPM >=60, rendering started");
        }

        // Пересчёт LUT выполняется один раз в начале оборота — не внутри fillSectorIntoBuffer.
        // rebuildGammaLUT() содержит ~768 вызовов powf() (~1–3 мс), что пропустило бы
        // несколько секторов если бы вызывалось в горячем цикле при первом изменении параметра.
        updateLUTIfNeeded();

        int last_sector   = -1;
        int sectors_drawn = 0;  // счётчик реально отрендеренных секторов за оборот
        tx_pending        = false;

        while (true) {
            // Прерываем оборот если буфер освобождается во время рендеринга:
            // loadFrameFromFile (Core 0) ставит newFrameReady=false ДО free(frameBuffer),
            // поэтому эта проверка надёжно защищает от use-after-free.
            if (force_stop_display || !peripherals_active || !newFrameReady) break;

            uint32_t elapsed = micros() - t0;
            if (elapsed >= period) break;

            int base = (int)((uint64_t)elapsed * 360 / period);
            if (base >= 360) break;

            int sector = (base + (int)global_angle_offset + 360) % 360;

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
            }
        }

        // Забираем последнюю незавершённую транзакцию перед следующим оборотом
        if (tx_pending) {
            spi_transaction_t* done;
            spi_device_get_trans_result(sk9822_spi, &done, portMAX_DELAY);
            tx_pending = false;
        }

        // Публикуем RMS: среднее по секторам → значение 0.0–1.0
        if (sectors_drawn > 0)
            global_abl_rms = rms_accum / (float)sectors_drawn;
        rms_accum = 0.0f;
    }
}

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
// Вынесена из loop() на Core 0: renderingTask (приоритет 18) на Core 1 полностью
// вытесняет loop() (приоритет 1) во время рендеринга, из-за чего loopNetwork()
// не вызывался достаточно часто → Task WDT на loopTask.
// Core 0 не затронут рендерингом — loopNetwork() выполняется стабильно каждые 5мс.
void networkTask(void* pvParameters) {
    for (;;) {
        loopNetwork();
        vTaskDelay(pdMS_TO_TICKS(5));
    }
}

void setup() {
    // millis() сбрасывается при каждом запуске — старая millis-база из RTC недействительна.
    // Без этого _currentEpoch() вычисляет неверное UTC время вместо ??:??:??
    resetTimeSync();

    // Снимаем глобальную блокировку пинов после сна
    gpio_deep_sleep_hold_dis();
    // Размораживаем конкретно наш пин 21
    gpio_hold_dis((gpio_num_t)21);
    
    // Возвращаем пин 21 в обычное состояние для активной работы
    gpio_pullup_dis((gpio_num_t)21);

    // Записываем LOW в защёлку ДО переключения в OUTPUT — иначе при смене режима
    // пин на долю мкс оказывается в HIGH (или неопределён), DCDC включается и
    // светодиоды видят случайный сигнал. gpio_set_level не меняет direction — безопасно.
    gpio_set_level((gpio_num_t)PIN_EN_DCDC,        0);
    gpio_set_level((gpio_num_t)PIN_EN_LEVEL_SHIFT, 0);
    pinMode(PIN_EN_DCDC, OUTPUT); pinMode(PIN_EN_LEVEL_SHIFT, OUTPUT);
    // Явно подтверждаем LOW после смены direction
    digitalWrite(PIN_EN_DCDC, LOW); digitalWrite(PIN_EN_LEVEL_SHIFT, LOW);
    pinMode(PIN_WAKEUP, INPUT_PULLUP); pinMode(PIN_BUTTON, INPUT_PULLUP); pinMode(PIN_COLOR_INT, INPUT_PULLUP);
    
    digitalWrite(PIN_EN_DCDC, LOW);
    digitalWrite(PIN_EN_LEVEL_SHIFT, LOW); 
    // Настраиваем прерывание BQ25798 на GPIO 21
    pinMode(21, INPUT_PULLUP);
    attachInterrupt(digitalPinToInterrupt(21), bqInterruptHandler, FALLING);
    
    Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL);

    // Проверяем подключен ли адаптер сразу после пробуждения
    bool is_adapter_connected = false;
    uint8_t stat = readBQ8(0x1B);
    is_adapter_connected = ((stat >> 5) & 0x07) != 0;

    esp_sleep_wakeup_cause_t wakeup_reason = esp_sleep_get_wakeup_cause();

    if (wakeup_reason == ESP_SLEEP_WAKEUP_EXT0) {
        webLog("[SYS] Wakeup: Let's ride! (IO5)");
    } else if (wakeup_reason == ESP_SLEEP_WAKEUP_EXT1) {
        webLog("[SYS] Wakeup: charger (GPIO21)");
    } else if (wakeup_reason == ESP_SLEEP_WAKEUP_UNDEFINED) {
        esp_reset_reason_t rr = esp_reset_reason();
        const char* rr_str = "unknown";
        switch (rr) {
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

    // Если пробуждение не от кнопки (EXT0) и не от зарядки (EXT1) и адаптер не подключен
    if (wakeup_reason != ESP_SLEEP_WAKEUP_EXT0 && wakeup_reason != ESP_SLEEP_WAKEUP_EXT1 && !is_adapter_connected) {
        webLog("[SYS] No wakeup source + no charger, sleeping...");
        digitalWrite(PIN_EN_DCDC, LOW); 
        digitalWrite(PIN_EN_LEVEL_SHIFT, LOW);

        Wire.beginTransmission(0x23);
        Wire.write(0x00); 
        Wire.endTransmission();

        SPI.begin();
        pinMode(PIN_CS, OUTPUT);
        digitalWrite(PIN_CS, HIGH); delay(5);
        digitalWrite(PIN_CS, LOW);
        SPI.transfer(0x4E); 
        SPI.transfer(0x01); 
        digitalWrite(PIN_CS, HIGH);

        esp_sleep_enable_ext0_wakeup((gpio_num_t)PIN_WAKEUP, 0); 
        esp_sleep_enable_ext1_wakeup(1ULL << 21, ESP_EXT1_WAKEUP_ANY_LOW); // Просыпаться от зарядного устройства
        esp_deep_sleep_start();
    }

    webLog("[SYS] Initializing...");
    // DCDC и level shifter пока выключены — включим позже:
    // либо при автоплее (если есть last_file), либо по hall-событию в loop().
    digitalWrite(PIN_EN_DCDC, LOW);
    digitalWrite(PIN_EN_LEVEL_SHIFT, LOW);
    peripherals_active = false;

    // Если проснулись от зарядки или она была подключена, инициируем настройку BQ
    if (wakeup_reason == ESP_SLEEP_WAKEUP_EXT1 || is_adapter_connected) {
        bq_interrupt_flag = true;
    }

    if (psramFound()) {
        frameBuffer = (uint8_t*)ps_malloc(FRAME_SIZE);
        if (frameBuffer) memset(frameBuffer, 0, FRAME_SIZE);
    }
    LittleFS.begin(true);

    initBQ25792();
    // HIGH_RES_MODE: 1 lx точность, ~120 мс на замер — усредняем по 10 замерам для плавности
    lightMeter.begin(BH1750::CONTINUOUS_HIGH_RES_MODE, 0x23, &Wire);

    setupNetwork();
    last_web_activity_time = millis();  // Считаем загрузку страницы активностью

    // Эндпоинт для телеметрии батареи — отдаёт кешированные данные без Wire.
    // Данные обновляются из loop() раз в секунду на Core 1.
    // Wire из Core 0 (AsyncWebServer) вызывался параллельно с loop() → гонка → WDT.
    server.on("/battery", HTTP_GET, [](AsyncWebServerRequest *request){
        // Фоновый поллинг — не сбрасывает таймер активности.
        // snprintf в стековый буфер — ноль heap-аллокаций.
        char buf[128];
        snprintf(buf, sizeof(buf),
            "{\"vbat\":%d,\"ibat\":%d,\"vbus\":%d,\"ibus\":%d"
            ",\"chg_stat\":%u,\"vbus_ok\":%s,\"connected\":%s}",
            (int)bat_cache.vbat, (int)bat_cache.ibat,
            (int)bat_cache.vbus, (int)bat_cache.ibus,
            (unsigned)bat_cache.chg_stat,
            bat_cache.vbus_ok ? "true" : "false",
            bat_cache.vbus_ok ? "true" : "false"
        );
        request->send(200, "application/json", buf);
    });

    updateFileList();

    // SPI инициализируется ДО включения DCDC/level-shifter:
    // пины DATA и CLK переходят под контроль SPI-драйвера (LOW в idle),
    // и светодиоды не видят мусорный сигнал в момент подачи питания.
    initSK9822_DMA();
    blankAllLEDs_DMA(); // гасим все 608 диодов — leds[] ещё не инициализирован

    String last_file = prefs.getString("last_file", "");
    if (last_file != "" && LittleFS.exists("/" + last_file)) {
        webLogf("[DISP] Autoplay: %s", last_file.c_str());
        webLog("[PWR] Autoplay: LEDs power on");
        digitalWrite(PIN_EN_DCDC, HIGH);
        digitalWrite(PIN_EN_LEVEL_SHIFT, HIGH);
        // SK9822 при подаче питания стартует с неопределённым содержимым регистров.
        // Ждём стабилизации питания (~5мс) перед отправкой команды гашения.
        delay(5);
        blankAllLEDs_DMA(); // гасим все 608 диодов до начала рендеринга
        peripherals_active = true;
        last_dcdc_on_time = millis();
        loadFrameFromFile("/" + last_file);
    }

    hallSemaphore = xSemaphoreCreateBinary();

    // Задача асинхронной загрузки файлов: Core 0, приоритет 2.
    // Выполняет loadFrameFromFile вне контекста WiFi-задачи, не блокируя HTTP-стек.
    fileLoaderSemaphore = xSemaphoreCreateBinary();
    xTaskCreatePinnedToCore(fileLoaderTask, "loader", 4096, NULL, 2, NULL, 0);

    // Сетевая задача: ArduinoOTA + ElegantOTA + WiFi reconnect — Core 0, приоритет 3.
    // Вынесена из loop(): renderingTask на Core 1 полностью вытесняет loop() во время
    // рендеринга, loopNetwork() не вызывается → WDT на loopTask.
    xTaskCreatePinnedToCore(networkTask, "network", 4096, NULL, 3, NULL, 0);

    // Core 1, приоритет 2: выше loop()/BQ25792 (prio 1), ниже WiFi ISR и системных задач.
    // Приоритет 18 вызывал непрерывный busy-wait без уступок WiFi-прерываниям →
    // SPI spinlock блокировал beacon-обработку → WiFi рвал ассоциацию → ребут.
    // При prio 2 WiFi ISR (prio >10 в IDF) всегда прерывает рендеринг когда нужно.
    // Джиттер <5мкс на сектор при 14мкс/сектор (200 RPM) — визуально незаметен.
    xTaskCreatePinnedToCore(renderingTask, "render", 4096, NULL, 2, NULL, 1);

    // loopTask (Arduino loop) работает на Core 1 с приоритетом 1.
    // renderingTask (приоритет 18) полностью вытесняет его во время вращения —
    // loop() не вызывается сотни миллисекунд. Task WDT убивает устройство через 5с.
    // Решение: отписываем loopTask от WDT. Всё критичное (network, BQ25792, sleep)
    // уже вынесено в отдельные задачи с собственными WDT или без них.
    esp_task_wdt_delete(xTaskGetCurrentTaskHandle());

    // Подключаем прерывания
    attachInterrupt(digitalPinToInterrupt(PIN_COLOR_INT), magnetInterruptHandler, FALLING);
    attachInterrupt(digitalPinToInterrupt(PIN_WAKEUP), wakeupInterruptHandler, FALLING);

    // Открываем сервер только после полной инициализации:
    // все эндпоинты зарегистрированы, Wire/I2C готов, задачи созданы.
    // Это исключает WDT-краш когда браузер открыт заранее и шлёт запросы
    // пока setup() ещё работает с BQ25792 или LittleFS.
    server.begin();
    webLog("[NET] HTTP server started");
}

void loop() {
    static uint32_t last_play_ms = 0; // Время последнего запроса /play (для Section 2)

    uint32_t now_ms = millis();
    uint32_t now_us = micros();


    // --- Обработка запроса Play из Web UI ---
    if (request_play_flag) {
        request_play_flag = false;
        if (!peripherals_active) {
            // Явно включаем питание LED: датчик Холла может быть обесточен,
            // поэтому нельзя ждать hall_event — включаем сами.
            webLogf("[PWR] Play \"%s\": LEDs power on", pendingFilePath.c_str());
            digitalWrite(PIN_EN_DCDC, HIGH);
            digitalWrite(PIN_EN_LEVEL_SHIFT, HIGH);
            delay(5); // ждём стабилизации питания SK9822
            blankAllLEDs_DMA(); // гасим все 608 диодов до начала рендеринга
            peripherals_active = true;
            last_dcdc_on_time = millis();
        }
        // Не сбрасываем last_hall_time — это портит rotation_period в ISR:
        // если Холл сработает вскоре после сброса, rotation_period = несколько мс
        // вместо ~333мс, и renderingTask выйдет из цикла после 1 сектора.
        // Запоминаем момент запроса — секция 2 даст 2с на загрузку файла.
        last_play_ms = millis();
    }

    // safe_rotation_period используется в /info endpoint через глобальную переменную rotation_period.
    // Здесь дополнительное чтение не нужно — time_since_magnet_us вычисляется свежим micros()
    // непосредственно перед проверкой таймаута (см. секцию 2).

    // --- Обработка прерывания зарядного устройства BQ25798 ---
   if (bq_interrupt_flag) {
        bq_interrupt_flag = false;
        uint8_t fault0 = readBQ8(0x20);
        uint8_t fault1 = readBQ8(0x21);
        uint8_t stat1C = readBQ8(0x1C);
        bool vbus_present = ((stat1C >> 5) & 0x07) != 0;  // CHG_STAT в reg1C bits[7:5]
        uint8_t charge_state = (stat1C >> 5) & 0x07;
        webLogf("[BMS] BQ: VBUS=%s chg=%d fault=%02X%02X",
            vbus_present ? "YES" : "NO", charge_state, fault0, fault1);
    }

    // --- 1. Обработка прерывания Холла (Event Handler) ---
    if (hall_event) {
        hall_event = false;
        // Включаем питание только если есть файл для показа.
        // Без файла DCDC остаётся выключенным до нажатия Play.
        if (!force_stop_display && !peripherals_active && newFrameReady) {
            // Включаем питание немедленно по первому hall-сигналу.
            // Лог о начале рендеринга выводит renderingTask когда period подтвердит >=60 RPM.
            digitalWrite(PIN_EN_DCDC, HIGH);
            digitalWrite(PIN_EN_LEVEL_SHIFT, HIGH);
            delay(5); // ждём стабилизации питания SK9822
            blankAllLEDs_DMA(); // гасим все 608 диодов до начала рендеринга
            peripherals_active = true;
            last_dcdc_on_time = millis();
            webLog("[PWR] Hall: LEDs power on (waiting for >=60 RPM)");
        }
        // Рендеринг управляется renderingTask через hallSemaphore
    }

    // --- 1.5 Пробуждение от PIN_WAKEUP (IO5, 20Гц low-energy Hall) ---
    // IO5 используется ТОЛЬКО для пробуждения периферии (включение DCDC).
    // Обороты не считаем по IO5 — не обновляем last_hall_time и rotation_period.
    // Реальные обороты считывает IO4 (5В Hall), он начнёт выдавать корректные
    // сигналы после стабилизации DCDC (~500мс).
    //
    // ВАЖНО: не реагируем на IO5 если IO4 работал недавно (< 2с назад).
    // При высоких оборотах renderingTask (приоритет 18, Core 1) вытесняет loop()
    // на > 1с, и DCDC успевает выключиться по таймауту — IO5 тут же его включает
    // обратно, вызывая мигание. Защита: если last_hall_time свежий — IO4 живёт,
    // включать DCDC не нужно (он уже включён или выключился только что).
    if (wakeup_event) {
        wakeup_event = false;
        // Читаем last_hall_time атомарно
        noInterrupts();
        uint32_t hall_t = last_hall_time;
        interrupts();
        uint32_t io4_age_us = (now_us >= hall_t) ? (now_us - hall_t)
                                                  : (0xFFFFFFFFUL - hall_t + now_us + 1);
        // IO4 молчит дольше 3с — значит DCDC действительно выключен и колесо крутится:
        // включаем питание. Если IO4 работал недавно — игнорируем IO5.
        // Без файла для показа DCDC не включаем — ждём нажатия Play.
        if (!peripherals_active && !force_stop_display && newFrameReady && io4_age_us > 3000000UL) {
            webLog("[PWR] IO5: LEDs power on");
            digitalWrite(PIN_EN_DCDC, HIGH);
            digitalWrite(PIN_EN_LEVEL_SHIFT, HIGH);
            delay(5); // ждём стабилизации питания SK9822
            blankAllLEDs_DMA(); // гасим все 608 диодов до начала рендеринга
            peripherals_active = true;
            last_dcdc_on_time = millis();
            // last_hall_time НЕ трогаем: IO4 обновит его сам после стабилизации DCDC
        }
    }

    // Вычисляем реальное время с последнего прохода магнита.
    // ВАЖНО: используем свежий micros() и читаем last_hall_time заново —
    // renderingTask (приоритет 18, Core 1) вытесняет loop() на целые обороты,
    // и safe_last_hall_time, прочитанный в начале loop(), может быть устаревшим.
    // Прямое чтение ISR-переменной с запретом прерываний — единственный надёжный способ.
    noInterrupts();
    uint32_t fresh_hall_time = last_hall_time;
    interrupts();
    uint32_t fresh_now_us = micros();
    // Если колесо ни разу не крутилось (last_hall_time == 0) — считаем что магнита
    // не было "с начала времён": используем UINT32_MAX чтобы условие засыпания
    // по hall не блокировало переход в deep sleep.
    uint32_t time_since_magnet_us = UINT32_MAX;
    if (fresh_hall_time > 0) {
        time_since_magnet_us = (fresh_now_us >= fresh_hall_time)
            ? (fresh_now_us - fresh_hall_time)
            : (0xFFFFFFFFUL - fresh_hall_time + fresh_now_us + 1);
    }

    // --- 2. Логика остановки (Таймаут 3 секунды) ---
    // Увеличено с 1с до 3с: при >200 RPM renderingTask занимает Core 1 настолько
    // плотно, что loop() может не запускаться >1с подряд. За это время IO4 ISR
    // исправно обновляет last_hall_time, но если таймаут слишком короткий —
    // DCDC успевает выключиться до следующей итерации loop().
    // 3с — достаточно для любых реалистичных пауз планировщика, но быстро
    // реагирует на реальную остановку колеса.
    // 5-секундная защита после /play и 2с после включения DCDC остаются.
    if (peripherals_active && time_since_magnet_us > 3000000 && !force_stop_display &&
        (now_ms - last_play_ms) > 5000 && (now_ms - last_dcdc_on_time) > 2000) {
        webLog("[PWR] No rotation >3s, LEDs power off");
        FastLED.clear(); sendLEDs_DMA();
        digitalWrite(PIN_EN_LEVEL_SHIFT, LOW);
        digitalWrite(PIN_EN_DCDC, LOW);
        last_dcdc_off_time = millis();
        peripherals_active = false;
    }

    // --- 3. Принудительная остановка из Web UI (Stop Display) ---
    if (force_stop_display && peripherals_active) {
        webLog("[PWR] Force stop, LEDs power off");
        FastLED.clear(); sendLEDs_DMA();
        digitalWrite(PIN_EN_LEVEL_SHIFT, LOW);
        digitalWrite(PIN_EN_DCDC, LOW);
        last_dcdc_off_time = millis();
        peripherals_active = false;
    }

    // --- 4. Deep Sleep (Таймаут 1 минута) ---
    uint32_t time_since_web_activity_ms = now_ms - last_web_activity_time;
    // time_since_dcdc_on_ms: время с последнего включения DCDC.
    // IO5 (20Гц Hall) мог разбудить устройство совсем недавно — не уходим в сон
    // раньше чем через 1 минуту с момента включения.
    uint32_t time_since_dcdc_on_ms = now_ms - last_dcdc_on_time;

    // Обратный отсчёт до сна: лог каждые 10 секунд + уведомление при сбросе таймера.
    // "Ведущий" таймер — тот из трёх, который истёк позже всего (наибольший остаток).
    {
        static uint32_t last_activity_snap = 0;  // Snapshot last_web_activity_time на прошлой итерации
        static uint32_t last_dcdc_snap     = 0;  // Snapshot last_dcdc_on_time на прошлой итерации
        static int      last_logged_tick   = -1; // Последний напечатанный tick (0,10,20,30,40,50)

        // Вычисляем реальный "возраст" каждого таймера (сколько секунд прошло).
        // hall_age_s: если колесо не крутилось (UINT32_MAX) — берём 60 чтобы
        // он не влиял на min_age_s (условие уже выполнено).
        uint32_t web_age_s  = time_since_web_activity_ms / 1000;
        uint32_t hall_age_s = (time_since_magnet_us == UINT32_MAX) ? 60
                              : (uint32_t)(time_since_magnet_us / 1000000);
        uint32_t dcdc_age_s = time_since_dcdc_on_ms / 1000;

        // Минимальный возраст = сколько прошло с последнего ЛЮБОГО события,
        // которое сдвигает момент засыпания. Спим через 60с после самого позднего.
        uint32_t min_age_s = web_age_s;
        if (hall_age_s < min_age_s) min_age_s = hall_age_s;
        if (dcdc_age_s < min_age_s) min_age_s = dcdc_age_s;

        // Определяем сброс таймера: кто-то нажал кнопку/двинул слайдер (web),
        // или включился DCDC.
        bool activity_reset = (last_web_activity_time != last_activity_snap) ||
                              (last_dcdc_on_time      != last_dcdc_snap);
        last_activity_snap = last_web_activity_time;
        last_dcdc_snap     = last_dcdc_on_time;

        if (activity_reset) {
            if (last_logged_tick >= 0) {
                webLog("[NET] Activity detected, sleep timer reset");
            }
            last_logged_tick = -1;
        }

        // Логируем на отметках 10, 20, 30, 40, 50 секунд бездействия (каждые 10с).
        // tick = округление min_age_s вниз до кратного 10, диапазон [10..50].
        if (min_age_s >= 10 && min_age_s < 60) {
            int tick = (int)(min_age_s / 10) * 10;  // 10, 20, 30, 40, 50
            if (tick != last_logged_tick) {
                last_logged_tick = tick;
                webLogf("[NET] Idle, sleep in %ds", 60 - tick);
            }
        }
    }

    if (!peripherals_active && time_since_magnet_us > 60000000 &&
        time_since_web_activity_ms > 60000 && time_since_dcdc_on_ms > 60000) {
        bool is_adapter_connected = (readBQ8(0x1B) & 0x08) != 0;  // VBUS_PRESENT_STAT: reg1B bit[3]
        
        if (is_adapter_connected) {
            last_hall_time = now_us;
            webLog("[SYS] Charger connected, sleep cancelled");
        } else {
        webLogf("[SYS] Idle >60s (web: %lus, hall: %lus), sleeping...",
            time_since_web_activity_ms / 1000, time_since_magnet_us / 1000000);
            
            // 1. Отключаем АЦП BQ25798 (чтобы он не дернул INT во сне по окончании замера)
            Wire.beginTransmission(BQ25792_ADDR);
            Wire.write(0x2E);
            Wire.write(0x00); 
            Wire.endTransmission();

            // 2. Усыпляем ваши датчики
            Wire.beginTransmission(0x23); Wire.write(0x00); Wire.endTransmission();
            digitalWrite(PIN_CS, LOW); SPI.transfer(0x4E); SPI.transfer(0x01); digitalWrite(PIN_CS, HIGH);
            
            // ВАЖНО: Даем напряжению стабилизироваться после отключения датчиков
            delay(50); 
            
            // 3. ТОЛЬКО ТЕПЕРЬ очищаем прерывания BQ25798! 
            readBQ8(0x20); readBQ8(0x21); readBQ8(0x1B); readBQ8(0x1C);
            
            // 4. Глушим датчики Холла
            detachInterrupt(digitalPinToInterrupt(PIN_COLOR_INT));
            detachInterrupt(digitalPinToInterrupt(PIN_WAKEUP));

            // 5. Настраиваем пробуждение по PIN_WAKEUP
            if (digitalRead(PIN_WAKEUP) == LOW) {
                esp_sleep_enable_ext0_wakeup((gpio_num_t)PIN_WAKEUP, 1); 
            } else {
                esp_sleep_enable_ext0_wakeup((gpio_num_t)PIN_WAKEUP, 0); 
            }
            
            // 6. БОРЬБА СО 100кОм ПОДТЯЖКОЙ НА BQ INT
            // Жестко запрещаем ESP32 тянуть пин к земле и включаем подтяжку к питанию
            gpio_pulldown_dis((gpio_num_t)21);
            gpio_pullup_en((gpio_num_t)21);
            
            // ВАЖНО: Замораживаем текущее состояние пина (Hold), 
            // чтобы сон не сбросил наши настройки pull-up!
            gpio_hold_en((gpio_num_t)21);
            gpio_deep_sleep_hold_en(); // Применяем удержание пинов в Deep Sleep
            
            // Проверка "на дурака": если пин всё ещё в нуле - мы где-то не дочитали регистр BQ
            if (digitalRead(21) == LOW) {
                webLog("[ERR] GPIO21 LOW before sleep, will wake immediately!");
            }

            esp_sleep_enable_ext1_wakeup(1ULL << 21, ESP_EXT1_WAKEUP_ANY_LOW);

            webLog("[SYS] Sleeping...");
            // Ждём минимум 3 поллинга браузера (интервал 2с) — лог об уходе в сон
            // должен дойти до UI до фактического отключения.
            delay(3000);

            esp_deep_sleep_start();
        }
    }

    // --- 5. Авто-яркость ---
    // Wire вызывается только из loop() — исключает гонку с renderingTask.
    if (lightMeter.measurementReady()) {
        float lux = lightMeter.readLightLevel();
        if (lux >= 0) {
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
        float ratio = constrain(last_lux_value / 1000.0f, 0.0f, 1.0f);
        global_brightness = (uint8_t)constrain(
            (int)(ratio * (float)max_brightness),
            (int)min_brightness,
            (int)max_brightness
        );
        global_effective_brightness = global_brightness;
    }

    // --- 6. Мониторинг BMS (Непрерывный мониторинг, даже при остановленном рендере) ---
    static uint32_t last_bms_check_time = 0;
    static uint32_t last_bms_recovery_time = 0;
    static bool bms_recovery_in_progress = false;
    static uint32_t bms_recovery_off_time = 0;

    // Завершение восстановления: 5 секунд прошло — включаем питание обратно
    if (bms_recovery_in_progress && (now_ms - bms_recovery_off_time >= 5000)) {
        bms_recovery_in_progress = false;
        Wire.beginTransmission(BQ25792_ADDR);
        Wire.write(0x0F);
        Wire.write(0xA2); // Re-enable charging
        Wire.endTransmission();
        digitalWrite(PIN_EN_DCDC, HIGH);
        digitalWrite(PIN_EN_LEVEL_SHIFT, HIGH);
        delay(5); // ждём стабилизации питания SK9822
        blankAllLEDs_DMA(); // гасим все 608 диодов до начала рендеринга
        peripherals_active = true;
        last_dcdc_on_time = millis();
        last_bms_recovery_time = now_ms;
        webLog("[BMS] Recovery complete");
    }

    // Проверка раз в секунду, только если восстановление не идёт
    if (!bms_recovery_in_progress && now_ms - last_bms_check_time > 1000) {
        last_bms_check_time = now_ms;

        Wire.beginTransmission(BQ25792_ADDR);
        Wire.write(0x2E);
        Wire.write(0x80); // ADC_EN = 1
        Wire.endTransmission();

        int16_t vbat_raw = readBQ16(0x3B);

        // Обновляем кеш батареи — /battery endpoint читает отсюда без Wire
        bat_cache.vbat     = vbat_raw;
        bat_cache.ibat     = readBQ16(0x33); // IBAT_ADC
        bat_cache.vbus     = readBQ16(0x35); // VBUS_ADC
        bat_cache.ibus     = readBQ16(0x31); // IBUS_ADC
        {
            uint8_t reg1B      = readBQ8(0x1B);
            uint8_t reg1C      = readBQ8(0x1C);
            bat_cache.vbus_ok  = (reg1B & 0x08) != 0;
            bat_cache.chg_stat = (reg1C >> 5) & 0x07;
        }

        if (vbat_raw >= 1000 && vbat_raw <= 1400) {
            if (now_ms - last_bms_recovery_time > 30000) {
                webLogf("[BMS] Latch! VBAT=%dmV, recovering...", vbat_raw);

                Wire.beginTransmission(BQ25792_ADDR);
                Wire.write(0x0F);
                Wire.write(0x82);
                Wire.endTransmission();

                FastLED.clear();
                if (peripherals_active) sendLEDs_DMA();
                digitalWrite(PIN_EN_DCDC, LOW);
                digitalWrite(PIN_EN_LEVEL_SHIFT, LOW);
                peripherals_active = false;
                last_dcdc_off_time = millis();

                bms_recovery_in_progress = true;
                bms_recovery_off_time = now_ms;
                webLog("[BMS] LEDs Power off, wait 5s...");
            }
        }
    }
}