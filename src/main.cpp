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

// Экспортируем сервер из network.cpp для добавления нового эндпоинта
extern AsyncWebServer server;

// --- ИНИЦИАЛИЗАЦИЯ ГЛОБАЛЬНЫХ ПЕРЕМЕННЫХ ---
volatile uint8_t global_brightness = 20; 

RTC_DATA_ATTR uint8_t min_brightness = 10;
RTC_DATA_ATTR uint8_t max_brightness = 50;
RTC_DATA_ATTR volatile int global_angle_offset = 86;

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
bool peripherals_active = true;
//volatile bool blink_ok_flag = false;
volatile float last_lux_value = 0.0f; // Последнее валидное показание BH1750 (lux)

// --- ESP-IDF SPI DMA для SK9822 ---
#define SK9822_END_FRAMES 20
#define SK9822_BUF_SIZE   (4 + NUM_LEDS * 4 + SK9822_END_FRAMES)

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

RTC_DATA_ATTR volatile float global_gamma      = 3.5f; // Сохраняется в RTC-памяти (переживает deep sleep)
RTC_DATA_ATTR volatile float global_saturation = 1.0f; // 1.0 = без изменений, >1 усиливает насыщенность
uint8_t gamma_lut[256];

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
    uint32_t now_ms = millis();
    uint32_t now = micros();
    
    // Игнорируем прерывания полсекунды после отключения питания (защита от глитча)
    if (!peripherals_active && (now_ms - last_dcdc_off_time < 500)) {
        return; 
    }

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
    delay(200);       // Пауза 200 мс для стабилизации

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

    // Выделяем оба DMA-буфера и инициализируем заголовки/хвосты SK9822
    for (int b = 0; b < 2; b++) {
        dma_buf[b] = (uint8_t*)heap_caps_malloc(SK9822_BUF_SIZE, MALLOC_CAP_DMA);
        assert(dma_buf[b] != nullptr);
        memset(dma_buf[b], 0x00, 4);                                        // Start-frame
        memset(dma_buf[b] + 4, 0, NUM_LEDS * 4);                           // LED data (выкл.)
        memset(dma_buf[b] + 4 + NUM_LEDS * 4, 0xFF, SK9822_END_FRAMES);    // End-frame
        // Предзаполняем транзакции — tx_buffer и length фиксированы навсегда
        spi_trans[b].length    = SK9822_BUF_SIZE * 8;
        spi_trans[b].tx_buffer = dma_buf[b];
    }
    dma_tx_buffer = dma_buf[0]; // sendLEDs_DMA использует dma_buf[0]
    dmaMutex = xSemaphoreCreateMutex();
    Serial.println("SK9822 DMA initialized (double buffer)");
}

// Заменитель FastLED.show() для статусных заливок и очистки
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
    spi_transaction_t t = {};
    t.length    = SK9822_BUF_SIZE * 8;
    t.tx_buffer = dma_tx_buffer;
    spi_device_transmit(sk9822_spi, &t);
    xSemaphoreGive(dmaMutex);
}

// Перестраивает LUT гамма-коррекции; вызывается из setup() и loop()
// 256 итераций powf — быстро (однократно), в рендеринге — только табличный поиск
void rebuildGammaLUT() {
    float g = global_gamma;
    for (int i = 0; i < 256; i++) {
        gamma_lut[i] = (uint8_t)(powf(i / 255.0f, g) * 255.0f + 0.5f);
    }
}

// Заполняет DMA-буфер данными сектора без отправки по SPI.
// Вызывается из renderingTask пока предыдущий буфер ещё передаётся — CPU и DMA работают параллельно.
static void fillSectorIntoBuffer(uint8_t* buf, int current_sector) {
    if (frameBuffer == nullptr) {
        // Буфер ещё не загружен или уже освобождён — гасим все диоды,
        // чтобы не отправить устаревшие данные из предыдущей анимации.
        uint8_t* led_ptr = buf + 4;
        for (int i = 0; i < NUM_LEDS; i++) {
            led_ptr[i * 4 + 0] = 0xE0; // SK9822: 111bbbbb, brightness=0 → ток=0
            led_ptr[i * 4 + 1] = 0;
            led_ptr[i * 4 + 2] = 0;
            led_ptr[i * 4 + 3] = 0;
        }
        return;
    }
    static float   last_built_gamma = -1.0f;
    static float   last_built_sat   = -1.0f;
    static int16_t sat_fxp          = 256;

    if (global_gamma != last_built_gamma) {
        rebuildGammaLUT();
        last_built_gamma = global_gamma;
    }
    if (global_saturation != last_built_sat) {
        sat_fxp = (int16_t)(global_saturation * 256.0f);
        last_built_sat = global_saturation;
    }

    uint32_t anim_offset = currentFrameIndex * FRAME_SIZE;
    uint8_t  bri_byte    = 0xE0 | ((global_brightness * 31) / 100);
    uint8_t* led_ptr     = buf + 4;

    for (int ray = 0; ray < 4; ray++) {
        // Передняя сторона луча: сектора идут в порядке вращения
        int sector_front = (current_sector + ray * 90) % 360;
        // Задняя сторона луча: зеркало по горизонтали + сдвиг горизонта на 180°,
        // чтобы изображение обеих сторон было в одном горизонте.
        int sector_back  = (540 - sector_front) % 360;

        const uint8_t* src_f = frameBuffer + anim_offset + sector_front * 38 * 3;
        const uint8_t* src_b = frameBuffer + anim_offset + sector_back  * 38 * 3;

        for (int i = 0; i < 38; i++) {
            // --- Передняя половина луча (LEDs 0-37) ---
            uint8_t r = gamma_lut[src_f[i * 3]];
            uint8_t g = gamma_lut[src_f[i * 3 + 1]];
            uint8_t b = gamma_lut[src_f[i * 3 + 2]];
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
            led_ptr[idx_a + 0] = bri_byte; led_ptr[idx_a + 1] = b;
            led_ptr[idx_a + 2] = g;        led_ptr[idx_a + 3] = r;

            // --- Задняя половина луча (LEDs 38-75, зеркальный сектор + 180°) ---
            uint8_t rb = gamma_lut[src_b[i * 3]];
            uint8_t gb = gamma_lut[src_b[i * 3 + 1]];
            uint8_t bb = gamma_lut[src_b[i * 3 + 2]];
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
            led_ptr[idx_b + 0] = bri_byte; led_ptr[idx_b + 1] = bb;
            led_ptr[idx_b + 2] = gb;        led_ptr[idx_b + 3] = rb;
        }
    }
}

// Задача рендеринга: Core 1, высокий приоритет.
// Ping-pong DMA: пока dma_buf[active] идёт по SPI (polling — без планировщика),
// CPU заполняет dma_buf[idle] следующим сектором.
// spi_device_polling_start/end — детерминированный busy-wait, исключает джиттер
// от задержки пробуждения FreeRTOS (источник дрожания изображения 3–5°).
void renderingTask(void* pvParameters) {
    static uint32_t last_bri_time = 0;
    uint8_t active     = 0;
    bool    tx_pending = false;

    while (true) {
        xSemaphoreTake(hallSemaphore, portMAX_DELAY);

        if (force_stop_display || !peripherals_active || !newFrameReady) continue;

        // --- Переключение кадров анимации (GIF таймер) ---
        // Выполняется здесь, а не в loop(): renderingTask (приоритет 18) делает
        // занятое ожидание весь оборот колеса и полностью вытесняет loop()
        // (приоритет 1) на том же Core 1 — тот не успевает выполниться.
        // Важно: за один оборот может пройти несколько frameDelay — вычисляем,
        // сколько кадров реально истекло, и прыгаем сразу на нужный, иначе
        // при period >> frameDelay анимация играет в period/frameDelay раз медленнее.
        if (totalFrames > 1 && frameBuffer != nullptr) {
            uint32_t now_anim = millis();
            uint32_t elapsed  = now_anim - lastFrameSwitchTime;
            if (elapsed >= frameDelay) {
                uint32_t steps = elapsed / frameDelay;
                lastFrameSwitchTime += steps * frameDelay;
                currentFrameIndex = (currentFrameIndex + steps) % totalFrames;
            }
        }

        // Авто-яркость: раз в 100 мс — loop() вытесняется этой задачей
        uint32_t now_rt = millis();
        if (now_rt - last_bri_time >= 100) {
            last_bri_time = now_rt;
            float lux = lightMeter.readLightLevel();
            if (lux >= 0) last_lux_value = lux;
            float ratio = constrain(last_lux_value / 1000.0f, 0.0f, 1.0f);
            global_brightness = (uint8_t)constrain(
                (int)(ratio * (float)max_brightness),
                (int)min_brightness,
                (int)max_brightness
            );
        }

        noInterrupts();
        uint32_t t0     = last_hall_time;
        uint32_t period = rotation_period;
        interrupts();

        // Пропускаем рендеринг ниже 100 RPM (period > 600 мс).
        // Критично: при первом обороте после долгой остановки
        // rotation_period = micros() - last_hall_time = десятки миллионов мкс.
        // Без этой проверки renderingTask уходит в busy-wait на минуты,
        // вытесняя WiFi/loop, WDT срабатывает через 5 с → краш → перезагрузка.
        if (period == 0 || period > 600000) continue;

        int last_sector = -1;
        tx_pending      = false;

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

                // Заполняем свободный буфер ПОКА предыдущая DMA-передача ещё идёт (~50 мкс).
                fillSectorIntoBuffer(dma_buf[idle], sector);

                // Ждём завершения предыдущей передачи детерминированным busy-wait —
                // без планировщика, без случайных задержек пробуждения задачи.
                if (tx_pending) {
                    spi_device_polling_end(sk9822_spi, portMAX_DELAY);
                }

                // Немедленно стартуем следующий сектор — минимальная задержка переключения
                spi_device_polling_start(sk9822_spi, &spi_trans[idle], portMAX_DELAY);

                active      = idle;
                tx_pending  = true;
                last_sector = sector;
            }
        }

        // Закрываем последнюю незавершённую транзакцию перед следующим оборотом
        if (tx_pending) {
            spi_device_polling_end(sk9822_spi, portMAX_DELAY);
            tx_pending = false;
        }
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

void setup() {
    Serial.begin(115200);
    delay(2000);

    // Снимаем глобальную блокировку пинов после сна
    gpio_deep_sleep_hold_dis();
    // Размораживаем конкретно наш пин 21
    gpio_hold_dis((gpio_num_t)21);
    
    // Возвращаем пин 21 в обычное состояние для активной работы
    gpio_pullup_dis((gpio_num_t)21);

    pinMode(PIN_EN_DCDC, OUTPUT); pinMode(PIN_EN_LEVEL_SHIFT, OUTPUT);
    pinMode(PIN_WAKEUP, INPUT_PULLUP); pinMode(PIN_BUTTON, INPUT_PULLUP); pinMode(PIN_COLOR_INT, INPUT_PULLUP);
    
    // Настраиваем прерывание BQ25798 на GPIO 21
    pinMode(21, INPUT_PULLUP);
    attachInterrupt(digitalPinToInterrupt(21), bqInterruptHandler, FALLING);
    
    Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL);

    // Проверяем подключен ли адаптер сразу после пробуждения
    bool is_adapter_connected = false;
    uint8_t stat = readBQ8(0x1B);
    is_adapter_connected = ((stat >> 5) & 0x07) != 0;

    esp_sleep_wakeup_cause_t wakeup_reason = esp_sleep_get_wakeup_cause();
    Serial.printf("\n--- BOOT --- Wakeup reason: %d\n", wakeup_reason);
    
    if (wakeup_reason == ESP_SLEEP_WAKEUP_EXT0) {
        Serial.println("ПРОСНУЛИСЬ ОТ PIN_WAKEUP (IO5) - Глитч подтвержден!");
    } else if (wakeup_reason == ESP_SLEEP_WAKEUP_EXT1) {
        Serial.println("ПРОСНУЛИСЬ ОТ BQ25798 (GPIO 21) - Залипло прерывание зарядки!");
    } else if (wakeup_reason == ESP_SLEEP_WAKEUP_UNDEFINED) {
        Serial.println("ПОЛНАЯ ПЕРЕЗАГРУЗКА (Возможно краш, WDT или Brownout)");
    }
    
    // Если пробуждение не от кнопки (EXT0) и не от зарядки (EXT1) и адаптер не подключен
    if (wakeup_reason != ESP_SLEEP_WAKEUP_EXT0 && wakeup_reason != ESP_SLEEP_WAKEUP_EXT1 && !is_adapter_connected) {
        Serial.println("Not an EXT0/EXT1 wakeup and no charger. Going to Deep Sleep...");
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

    Serial.println("Wakeup Detected! Initializing...");
    digitalWrite(PIN_EN_DCDC, HIGH); 
    digitalWrite(PIN_EN_LEVEL_SHIFT, HIGH);

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
    // LOW_RES_MODE: 16 мс на замер (против 120 мс у HIGH_RES) — нужно для быстрой реакции
    lightMeter.begin(BH1750::CONTINUOUS_LOW_RES_MODE, 0x23, &Wire);

    setupNetwork();

    // Эндпоинт для телеметрии батареи с добавленным полным дебагом конфигурации ЗУ
    server.on("/battery", HTTP_GET, [](AsyncWebServerRequest *request){
        last_web_activity_time = millis();
        // Включаем ADC (на случай если он был отключен)
        Wire.beginTransmission(BQ25792_ADDR);
        Wire.write(0x2E);
        Wire.write(0x80); // ADC_EN = 1
        Wire.endTransmission();
        
        int16_t vbus = readBQ16(0x35); // VBUS_ADC (0x35)
        int16_t ibus = readBQ16(0x31); // IBUS_ADC (0x31)
        int16_t vbat = readBQ16(0x3B); // VBAT_ADC (0x3B)
        int16_t ibat = readBQ16(0x33); // IBAT_ADC (0x33)
        
        // --- ДЕТАЛЬНЫЙ ДЕБАГ РЕГИСТРОВ ЗАРЯДА ---
        uint8_t reg00 = readBQ8(0x00); // VSYSMIN
        uint8_t reg0F = readBQ8(0x0F); // Charger Control 0
        uint8_t reg1B = readBQ8(0x1B); // Charger Status 0
        uint8_t reg1C = readBQ8(0x1C); // Charger Status 1
        uint8_t reg20 = readBQ8(0x20); // Fault Status 0
        uint8_t reg21 = readBQ8(0x21); // Fault Status 1

        uint16_t chg_v_limit = readBQ16(0x01); // Charge Voltage Limit
        uint16_t chg_i_limit = readBQ16(0x03); // Charge Current Limit
        uint16_t in_i_limit = readBQ16(0x06);  // Input Current Limit

        bool vbus_present = ((reg1B >> 5) & 0x07) != 0;
        bool charge_enabled = (reg0F >> 5) & 0x01;
        uint8_t chg_state = (reg1B >> 3) & 0x03;
        bool ilim_active = (reg1C >> 6) & 0x01;

        Serial.println("\n=== BQ25798 CHARGER DEBUG ===");
        Serial.printf("Charge current register (0x03-0x04): 0x%04X (%d mA)\n", chg_i_limit, chg_i_limit * 10);
        Serial.printf("Charge voltage register (0x01-0x02): 0x%04X (%d mV)\n", chg_v_limit, chg_v_limit * 10);
        Serial.printf("Input current limit (0x06-0x07): 0x%04X (%d mA)\n", in_i_limit, in_i_limit * 10);
        Serial.printf("VSYSMIN register (0x00): 0x%02X (%d mV)\n", reg00, 2500 + (reg00 & 0x3F) * 250);
        Serial.printf("Charger control register (0x0F): 0x%02X\n", reg0F);
        Serial.printf("Charger status register (0x1B): 0x%02X\n", reg1B);
        Serial.printf("Power status register (0x1C): 0x%02X\n", reg1C);
        Serial.printf("Fault status register (0x20-0x21): 0x%02X 0x%02X\n", reg20, reg21);

        Serial.println("\n--- DECODED MEANINGS ---");
        Serial.printf("Adapter present: %s\n", vbus_present ? "YES" : "NO");
        Serial.printf("Charging enabled: %s\n", charge_enabled ? "YES" : "NO");
        
        String state_str = "Unknown";
        switch(chg_state) {
            case 0: state_str = "Not Charging"; break;
            case 1: state_str = "Trickle / Pre-charge"; break;
            case 2: state_str = "Fast Charging"; break;
            case 3: state_str = "Taper Charge"; break;
        }
        Serial.printf("Charging state: %s\n", state_str.c_str());
        
        Serial.printf("Input limit active (IINDPM): %s\n", ilim_active ? "YES" : "NO");
        Serial.printf("Fault flags: ");
        if(reg20 == 0 && reg21 == 0) Serial.print("NONE\n");
        else Serial.printf("0x%02X 0x%02X\n", reg20, reg21);
        Serial.println("===============================\n");
        // ----------------------------------------

        bool connected = vbus_present;

        String json = "{";
        json += "\"vbat\":" + String(vbat) + ","; 
        json += "\"ibat\":" + String(ibat) + ","; 
        json += "\"vbus\":" + String(vbus) + ",";
        json += "\"ibus\":" + String(ibus) + ",";
        json += "\"connected\":" + String(connected ? "true" : "false");
        json += "}";
        request->send(200, "application/json", json);
    });

    updateFileList();

    String last_file = prefs.getString("last_file", "");
    if (last_file != "" && LittleFS.exists("/" + last_file)) {
        Serial.println("Автозагрузка последнего изображения: " + last_file);
        loadFrameFromFile("/" + last_file);
    }

    initSK9822_DMA();
    FastLED.clear();
    sendLEDs_DMA();

    hallSemaphore = xSemaphoreCreateBinary();

    // Задача асинхронной загрузки файлов: Core 0, приоритет 2.
    // Выполняет loadFrameFromFile вне контекста WiFi-задачи, не блокируя HTTP-стек.
    fileLoaderSemaphore = xSemaphoreCreateBinary();
    xTaskCreatePinnedToCore(fileLoaderTask, "loader", 4096, NULL, 2, NULL, 0);

    // Приоритет 18: выше WiFi/lwIP (1–3), выше loop() (1), ниже системных задач ESP-IDF (22+).
    // Высокий приоритет минимизирует вытеснение во время рендеринга.
    xTaskCreatePinnedToCore(renderingTask, "render", 4096, NULL, 18, NULL, 1);

    // Подключаем прерывания
    attachInterrupt(digitalPinToInterrupt(PIN_COLOR_INT), magnetInterruptHandler, FALLING);
    attachInterrupt(digitalPinToInterrupt(PIN_WAKEUP), wakeupInterruptHandler, FALLING);
}

void loop() {
    static uint32_t last_network_us = 0;
    static uint32_t last_play_ms    = 0; // Время последнего запроса /play (для Section 2)
    uint32_t now_us_net = micros();
    if (now_us_net - last_network_us >= 5000) {
        loopNetwork();
        last_network_us = micros();
    }

    uint32_t now_ms = millis();
    uint32_t now_us = micros();


    // --- Обработка запроса Play из Web UI ---
    if (request_play_flag) {
        request_play_flag = false;
        if (!peripherals_active) {
            // Явно включаем питание LED: датчик Холла может быть обесточен,
            // поэтому нельзя ждать hall_event — включаем сами.
            digitalWrite(PIN_EN_DCDC, HIGH);
            digitalWrite(PIN_EN_LEVEL_SHIFT, HIGH);
            peripherals_active = true;
        }
        // Не сбрасываем last_hall_time — это портит rotation_period в ISR:
        // если Холл сработает вскоре после сброса, rotation_period = несколько мс
        // вместо ~333мс, и renderingTask выйдет из цикла после 1 сектора.
        // Запоминаем момент запроса — секция 2 даст 2с на загрузку файла.
        last_play_ms = millis();
    }

    // --- БЕЗОПАСНОЕ ЧТЕНИЕ ПРЕРЫВАНИЙ ---
    // Защита от race conditions: читаем volatile переменные с отключенными прерываниями,
    // чтобы ISR не изменил их прямо во время математических расчетов.
    uint32_t safe_last_hall_time;
    uint32_t safe_rotation_period;
    noInterrupts();
    safe_last_hall_time = last_hall_time;
    safe_rotation_period = rotation_period;
    interrupts();

    // --- Обработка прерывания зарядного устройства BQ25798 ---
   if (bq_interrupt_flag) {
        bq_interrupt_flag = false;
        uint8_t fault0 = readBQ8(0x20);
        uint8_t fault1 = readBQ8(0x21);
        uint8_t stat = readBQ8(0x1B);
        bool vbus_present = ((stat >> 5) & 0x07) != 0;
        uint8_t charge_state = (stat >> 3) & 0x03;
        Serial.println("=== BQ25798 INT ===");
        Serial.printf("VBUS present: %s\n", vbus_present ? "YES" : "NO");
        Serial.printf("Charge state: %d\n", charge_state);
        Serial.printf("Fault: 0x%02X 0x%02X\n", fault0, fault1);
    }

    // --- 1. Обработка прерывания Холла (Event Handler) ---
    if (hall_event) {
        hall_event = false;
        if (!force_stop_display && !peripherals_active) {
            digitalWrite(PIN_EN_DCDC, HIGH);
            digitalWrite(PIN_EN_LEVEL_SHIFT, HIGH);
            peripherals_active = true;
        }
        // Рендеринг управляется renderingTask через hallSemaphore
    }

    // --- 1.5 Пробуждение от PIN_WAKEUP ---
    if (wakeup_event) {
        wakeup_event = false;
        if (!peripherals_active && !force_stop_display) {
            Serial.println("Rotation detected via PIN_WAKEUP - restarting DCDC");
            digitalWrite(PIN_EN_DCDC, HIGH);
            digitalWrite(PIN_EN_LEVEL_SHIFT, HIGH);
            peripherals_active = true;
            last_hall_time = now_us;
        }
    }

    // Вычисляем реальное время с последнего прохода магнита (с защитой от переполнения)
    uint32_t time_since_magnet_us = 0;
    if (safe_last_hall_time > 0) {
        if (now_us >= safe_last_hall_time) {
            time_since_magnet_us = now_us - safe_last_hall_time;
        } else {
            time_since_magnet_us = (0xFFFFFFFF - safe_last_hall_time) + now_us + 1;
        }
    }

    // --- 2. Логика остановки (Таймаут 1 секунда) ---
    // 5-секундная защита после запроса /play: loadFrameFromFile читает до 500 мс,
    // и без защиты peripherals сразу отключатся — колесо не успеет начать крутиться.
    // Используем last_play_ms (не last_web_activity_time, иначе страница пингует
    // каждые 500 мс и peripherals никогда не выключатся).
    if (peripherals_active && time_since_magnet_us > 1000000 && !force_stop_display &&
        (now_ms - last_play_ms) > 5000) {
        FastLED.clear(); sendLEDs_DMA();
        digitalWrite(PIN_EN_LEVEL_SHIFT, LOW);
        digitalWrite(PIN_EN_DCDC, LOW);
        last_dcdc_off_time = millis();
        peripherals_active = false;
    }

    // --- 3. Принудительная остановка из Web UI (Stop Display) ---
    if (force_stop_display && peripherals_active) {
        FastLED.clear(); sendLEDs_DMA();
        digitalWrite(PIN_EN_LEVEL_SHIFT, LOW);
        digitalWrite(PIN_EN_DCDC, LOW);
        last_dcdc_off_time = millis();
        peripherals_active = false;
    }

    // --- 4. Deep Sleep (Таймаут 1 минута) ---
    uint32_t time_since_web_activity_ms = now_ms - last_web_activity_time; // Вычисляем время бездействия в веб-интерфейсе
    
    if (!peripherals_active && time_since_magnet_us > 60000000 && time_since_web_activity_ms > 60000) { // 1 минута в микросекундах и 1 минута в веб-интерфейсе
        bool is_adapter_connected = ((readBQ8(0x1B) >> 5) & 0x07) != 0;
        
        if (is_adapter_connected) {
            last_hall_time = now_us;
            Serial.println("External adapter connected. Preventing Deep Sleep.");
        } else {
        Serial.println("Таймаут. Подготовка к Deep Sleep...");
            
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
                Serial.println("ОШИБКА: Пин BQ25798 INT все еще LOW. Сон будет прерван!");
            }
            
            esp_sleep_enable_ext1_wakeup(1ULL << 21, ESP_EXT1_WAKEUP_ANY_LOW);
            
            Serial.println("Ушел в сон.");
            Serial.flush();
            delay(10); 
            
            esp_deep_sleep_start();
        }
    }

    // --- 5. Авто-яркость ---
    // Чтение BH1750 и обновление global_brightness выполняется в renderingTask (Core 1)
    // каждые 100 мс — дублировать здесь нельзя, так как Wire не потокобезопасен
    // при одновременном вызове с двух ядер.

    // --- 6. Мониторинг BMS (Непрерывный мониторинг, даже при остановленном рендере) ---
    static uint32_t last_bms_check_time = 0;
    static uint32_t last_bms_recovery_time = 0;
    static bool bms_recovery_in_progress = false;
    static uint32_t bms_recovery_off_time = 0;

    // Завершение восстановления: 5 секунд прошло — включаем питание обратно
    if (bms_recovery_in_progress && (now_ms - bms_recovery_off_time >= 5000)) {
        bms_recovery_in_progress = false;
        digitalWrite(PIN_EN_DCDC, HIGH);
        digitalWrite(PIN_EN_LEVEL_SHIFT, HIGH);
        peripherals_active = true;
        Wire.beginTransmission(BQ25792_ADDR);
        Wire.write(0x0F);
        Wire.write(0xA2); // Re-enable charging
        Wire.endTransmission();
        FastLED.clear(); sendLEDs_DMA();
        last_bms_recovery_time = now_ms;
        Serial.println("BMS Recovery Complete.");
    }

    // Проверка раз в секунду, только если восстановление не идёт
    if (!bms_recovery_in_progress && now_ms - last_bms_check_time > 1000) {
        last_bms_check_time = now_ms;

        Wire.beginTransmission(BQ25792_ADDR);
        Wire.write(0x2E);
        Wire.write(0x80); // ADC_EN = 1
        Wire.endTransmission();

        int16_t vbat_raw = readBQ16(0x3B);

        if (vbat_raw >= 1000 && vbat_raw <= 1400) {
            if (now_ms - last_bms_recovery_time > 30000) {
                Serial.println("BMS Latch Detected (VBAT 1.0-1.4V)! Initiating Recovery Sequence...");

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
                Serial.println("Power off. Waiting 5s non-blocking...");
            }
        }
    }
}