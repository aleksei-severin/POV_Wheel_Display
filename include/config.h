#pragma once
#include <Arduino.h>
#include <Preferences.h>

// =====================================================================
//  POV Wheel Display — аппаратная ревизия V5
//  6 лучей × 88 диодов SK9822-A (по 44 на каждой стороне луча),
//  6 датчиков Холла DRV5023 (по одному на луч), два DCDC TPS631000,
//  зарядник IP2312U, датчик света ALS-PT19, вибродатчик HX 0805-C2.
// =====================================================================

// --- ПАРАМЕТРЫ ДИСПЛЕЯ ---
#define NUM_ARMS        6                              // Лучей всегда 6 (задано железом)
#define LEDS_PER_SIDE   44                             // Диодов на одной стороне луча
#define LEDS_PER_ARM    (LEDS_PER_SIDE * 2)            // 88 диодов на луче (обе стороны)
#define NUM_LEDS        (NUM_ARMS * LEDS_PER_ARM)      // 528 диодов в цепочке
#define SECTORS         360                            // Угловое разрешение кадра
#define ARM_STEP_DEG    (360 / NUM_ARMS)               // 60° между соседними лучами
#define FRAME_SIZE      (SECTORS * LEDS_PER_SIDE * 3)  // 47520 байт на кадр

// Физическая геометрия луча — радиусы центров крайних диодов (мм).
// Используются для угловой поправки при смещении луча от оси (Hub Offset).
#define LED_R_INNER_MM  49.0f
#define LED_R_OUTER_MM  273.0f

// --- ПИНЫ ESP32-S3 (V5) ---
#define PIN_LED_DATA       11   // SK9822 DATA (SPI MOSI)
#define PIN_LED_CLK        12   // SK9822 CLK
#define PIN_BUTTON          0   // Кнопка (boot)
#define PIN_VIBRATION      15   // HX 0805-C2: импульсы LOW, источник пробуждения из сна
#define PIN_EN_DCDC_ARM1   10   // TPS631000 №1: луч 1 + Холл 1 + датчик света
#define PIN_EN_DCDC_REST   38   // TPS631000 №2: лучи 2–6 + Холлы 2–6
#define PIN_ADC_VUSB        6   // ADC1_CH5, делитель 12к/12к (1:2) от VUSB
#define PIN_ADC_VBAT        7   // ADC1_CH6, делитель 1:2 от VBAT
#define PIN_CHG_STAT        8   // IP2312U D2 через делитель: HIGH = заряд завершён
#define PIN_ADC_LIGHT       9   // ADC1_CH8, ALS-PT19 (нагрузка 12к), питание от DCDC №1

// Датчики Холла DRV5023 — по одному на каждый луч.
// Выходы open-drain подтянуты резисторами 1к к неотключаемой линии 3V3.
#define HALL_COUNT      NUM_ARMS
#define HALL_PIN_LIST   { 13, 21, 14, 18, 17, 16 }

// Пороги включения/выключения отрисовки, об/мин (гистерезис 5 RPM)
#define RPM_RENDER_ON   60.0f
#define RPM_RENDER_OFF  55.0f

// Коэффициент делителей VBAT/VUSB (два одинаковых резистора → ×2)
#define ADC_DIVIDER_RATIO   2.0f

// Освещённость, при которой яркость выходит на максимум.
// ALS-PT19 с нагрузкой 12к даёт ≈2400 мВ при 1000 лк.
#define ALS_MV_AT_1000LX    2400.0f
#define LUX_FULL_SCALE      1000.0f

// Порог наличия питания на USB (мВ)
#define VUSB_PRESENT_MV     4000

// --- СОСТОЯНИЕ ПИТАНИЯ (двухступенчатое) ---
// PWR_OFF    — оба DCDC выключены
// PWR_SPINUP — включён DCDC №1: работают луч 1, Холл 1 и датчик света,
//              считаются обороты, светодиоды погашены
// PWR_FULL   — включены оба DCDC: работают все 6 лучей и все 6 Холлов, идёт отрисовка
enum PowerState : uint8_t { PWR_OFF = 0, PWR_SPINUP = 1, PWR_FULL = 2 };
extern volatile PowerState power_state;

// --- ГЛОБАЛЬНЫЕ ПЕРЕМЕННЫЕ (Объявления для всех файлов) ---
extern volatile uint8_t global_brightness;
extern uint8_t min_brightness;
extern uint8_t max_brightness;
extern volatile int global_angle_offset;
extern uint8_t* frameBuffer;
extern volatile bool newFrameReady;
extern String hostName;
extern bool force_stop_display;
extern bool peripherals_active;          // true когда включён хотя бы DCDC №1
extern volatile float last_lux_value;    // Последнее усреднённое показание ALS-PT19 (лк)
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

// --- ДАННЫЕ ДАТЧИКОВ ХОЛЛА ---
extern volatile uint32_t last_hall_time;   // micros() последнего события (любой датчик)
extern volatile uint32_t rotation_period;  // Период полного оборота, мкс
extern volatile int8_t   rotation_dir;     // +1 — сектор растёт со временем, -1 — убывает

// Слайдшоу (slideshowActive и slideInterval живут в RTC — сохраняются через deep sleep)
extern RTC_DATA_ATTR bool     slideshowActive;
extern RTC_DATA_ATTR uint32_t slideInterval;    // мс между сменами файлов
extern int      slideCurrentIndex;              // текущий индекс в savedFiles (-1 = не запущен)
extern uint32_t slideLastSwitch;                // millis() последней смены файла

// Список файлов на LittleFS (обновляется updateFileList)
#include <vector>
extern std::vector<String> savedFiles;
extern void updateFileList();

extern Preferences prefs;

// Флаг: web UI запросил воспроизведение — loop() должен включить питание LED
extern volatile bool request_play_flag;

// Асинхронная загрузка файлов: fileLoaderTask ждёт семафора, грузит pendingFilePath
#include <freertos/semphr.h>
extern SemaphoreHandle_t fileLoaderSemaphore;
extern String pendingFilePath;

// Гамма-коррекция
extern volatile float global_gamma;      // 1.0 = линейная, до 5.0 = максимум
extern uint8_t lut_r[256];  // Гамма + контраст + R-gain
extern uint8_t lut_g[256];  // Гамма + контраст + G-gain
extern uint8_t lut_b[256];  // Гамма + контраст + B-gain

// Коррекция насыщенности
extern volatile float global_saturation; // 1.0 = без изменений, до 3.0 = максимум

// Контраст: 0..100 %, 0 = без изменений (factor 1.0..3.0)
extern volatile float global_contrast;

// Коррекция каналов RGB: 0..200 %, 100 = без изменений
extern volatile float global_r_gain;
extern volatile float global_g_gain;
extern volatile float global_b_gain;

// Длина окружности колеса в мм (для расчёта скорости)
extern volatile uint16_t wheel_circumference;

// Смещение начала спицы от оси (мм): 0 = лучи из центра, ±100 = касательная к фланцу 100 мм.
// Положительное значение — смещение в правую сторону (по ходу вращения), отрицательное — в левую.
extern volatile int16_t global_spoke_offset;

// Порядок лучей в цепочке SK9822 относительно направления вращения.
// false — луч N смещён на +60°·N относительно первого (сборка «по ходу вращения»),
// true  — на −60°·N (сборка «против хода»). Если картинка собирается из
// перепутанных секторов — переключить в Web UI, перепрошивка не нужна.
extern volatile bool global_arm_reverse;

// ABL: лимит суммарного тока 0–100 %, 100 = без ограничения
extern volatile float global_abl_limit;

// RMS загрузка тока за последний полный оборот, 0.0–1.0 (обновляется renderingTask)
extern volatile float global_abl_rms;

// Эффективная яркость после применения ABL (0–31). Обновляется каждый сектор.
// Отличается от global_brightness когда ABL режет ток ниже установленного значения.
extern volatile uint8_t global_effective_brightness;

// DMA-вывод в SK9822 — определён в main.cpp, используется также в network.cpp
extern void blankAllLEDs_DMA();
extern volatile bool ota_in_progress;

// Web-логирование: дублирует Serial и хранит последние строки в RTC RAM
// (переживает deep sleep). Безопасно вызывать из любого контекста.
extern void webLog(const char* msg);
extern void webLogf(const char* fmt, ...);
