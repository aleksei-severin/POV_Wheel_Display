#pragma once
#include "config.h"
#include <WString.h>

void setupStorage();                // Открыть NVS. Безусловно, в начале setup()
void loadFrameFromFile(String path);
void unloadCurrentFrame();          // Освободить буфер кадра, ничего не загружая взамен
void webLog(const char* msg);
void webLogf(const char* fmt, ...);
void resetTimeSync();               // Вызывать в начале setup() — сбрасывает millis-базу
// Погасить ленту, снять питание лучей и размонтировать LittleFS перед прошивкой.
// Обязательна для ЛЮБОГО пути OTA: запись поверх смонтированной FS её ломает.
void safeOTAShutdown();

extern String currentDisplayFile;   // Имя файла, загруженного в frameBuffer

// --- Мостики для BLE ---
// Кольцо лога и база времени лежат в RTC-памяти и должны иметь ровно одну
// точку записи, поэтому povble.cpp работает с ними только через эти две.
void     povSetTime(uint32_t epoch, int32_t tz);       // по секундам; точные часы не сбивает
void     povSetTimeUs(int64_t wall_us, int32_t tz);    // точно (OP_TIME_SET)
uint32_t povBuildLogs(uint8_t* out, size_t cap, uint32_t since);
