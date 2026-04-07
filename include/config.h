#pragma once
#include <Arduino.h>

// Разрешаем прерывания во время вывода на LED. Это критично для стабильности
// энкодера/Холла, чтобы FastLED не блокировал ISR (SK9822 это поддерживает)
#define FASTLED_ALLOW_INTERRUPTS 1
#include <FastLED.h>
#include <Preferences.h>

// --- ПАРАМЕТРЫ ДИСПЛЕЯ ---
#define NUM_LEDS 304              // 4 луча по 76 диодов
#define FRAME_SIZE (360 * 38 * 3) // 41040 байт

// --- ПИНЫ ESP32-S3 ---
#define PIN_CS             41
#define PIN_LED_DATA       11
#define PIN_LED_CLK        12
#define PIN_WAKEUP         5
#define PIN_BUTTON         0

#define PIN_COLOR_INT      4
#define PIN_EN_DCDC        10
#define PIN_EN_LEVEL_SHIFT 9
#define PIN_I2C_SDA        39
#define PIN_I2C_SCL        40
#define BQ25792_ADDR       0x6B

// --- ГЛОБАЛЬНЫЕ ПЕРЕМЕННЫЕ (Объявления для всех файлов) ---
extern volatile uint8_t global_brightness;
extern uint8_t min_brightness;
extern uint8_t max_brightness;
extern volatile int global_angle_offset;
extern uint8_t* frameBuffer;
extern volatile bool newFrameReady;
extern CRGB leds[];
extern String hostName;
extern bool force_stop_display;
extern bool peripherals_active;
extern volatile bool blink_ok_flag;
extern volatile float last_lux_value; // Последнее валидное показание BH1750 (lux)
extern volatile bool blink_wifi_ok_flag;    // Зеленый: подключились к домашней сети
extern volatile bool blink_wifi_fail_flag;  // Красный: не удалось подключиться к сети
extern volatile bool blink_ap_client_flag;  // Желтый: клиент подключился к точке доступа

// --- ПЕРЕМЕННЫЕ АНИМАЦИИ (GIF) ---
extern uint32_t currentFrameIndex;
extern uint32_t totalFrames;
extern uint16_t frameDelay;
extern uint32_t lastFrameSwitchTime;

// Отслеживание активности Web UI
extern volatile uint32_t last_web_activity_time;

// Данные датчика Холла (для расчёта RPM в /info)
extern volatile uint32_t last_hall_time;
extern volatile uint32_t rotation_period;

// Настройки альбома
extern bool slideshowActive;
extern uint16_t slideInterval;

extern Preferences prefs;

// Флаг: web UI запросил воспроизведение — loop() должен включить питание LED
extern volatile bool request_play_flag;

// Асинхронная загрузка файлов: fileLoaderTask ждёт семафора, грузит pendingFilePath
#include <freertos/semphr.h>
extern SemaphoreHandle_t fileLoaderSemaphore;
extern String pendingFilePath;

// Гамма-коррекция
extern volatile float global_gamma;      // 1.0 = линейная, до 5.0 = максимум
extern uint8_t        gamma_lut[256];    // ЛУТ, перестраивается в drawSectorDMA при изменении gamma

// Коррекция насыщенности
extern volatile float global_saturation; // 1.0 = без изменений, до 3.0 = максимум

// Контраст: 0..100 %, 0 = без изменений (factor 1.0..3.0)
extern volatile float global_contrast;

// Длина окружности колеса в мм (для расчёта скорости)
extern volatile uint16_t wheel_circumference;

// DMA-замена FastLED.show() — определена в main.cpp, используется также в network.cpp
extern void sendLEDs_DMA();

// Web-логирование: дублирует Serial и хранит 100 последних строк в RTC RAM
// (переживает deep sleep). Безопасно вызывать из любого контекста.
extern void webLog(const char* msg);
extern void webLogf(const char* fmt, ...);