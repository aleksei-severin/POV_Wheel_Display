#pragma once
#include "config.h"
#include <WString.h>

void setupStorage();                // NVS + имя устройства. Всегда, даже без Wi-Fi
void setupNetwork();                // Подъём радио. Только по команде OP_WIFI из приложения
void loopNetwork();
void loadFrameFromFile(String path);
void webLog(const char* msg);
void webLogf(const char* fmt, ...);
void resetTimeSync();               // Вызывать в начале setup() — сбрасывает millis-базу
// Погасить ленту, снять питание лучей и размонтировать LittleFS перед прошивкой.
// Обязательна для ЛЮБОГО пути OTA: запись поверх смонтированной FS её ломает.
void safeOTAShutdown();

extern String currentDisplayFile;   // Имя файла, загруженного в frameBuffer

// --- Мостики для BLE (реализованы в network.cpp) ---
// Кольцо лога и база времени лежат в RTC-памяти и должны иметь ровно одну
// точку записи, поэтому povble.cpp работает с ними только через эти две.
void     povSetTime(uint32_t epoch, int32_t tz);
uint32_t povBuildLogs(uint8_t* out, size_t cap, uint32_t since);
