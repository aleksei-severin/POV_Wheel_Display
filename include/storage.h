#pragma once
#include "config.h"
#include <WString.h>

void setupStorage();                // Открыть NVS. Безусловно, в начале setup()
// phaseOriginMs > 0 — фаза анимации от этого момента (UTC, мс), см. alignFramePhase().
void loadFrameFromFile(String path, int64_t phaseOriginMs = 0);
// Выставить фазу текущей анимации от момента originMs (UTC, мс) без перечитывания.
// false — часы не заведены или originMs не задан.
bool alignFramePhase(int64_t originMs);
extern int64_t currentPhaseOriginMs;   // от чего считается фаза текущего файла, 0 — от его загрузки
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
// Если прошлый сеанс кончился паникой — её причина и цепочка вызовов в лог
// (см. __wrap_esp_panic_handler в storage.cpp). Звать в setup() после причины сброса.
void crashReportLog();

void     povSetTime(uint32_t epoch, int32_t tz);       // по секундам; точные часы не сбивает
void     povSetTimeUs(int64_t wall_us, int32_t tz);    // точно (OP_TIME_SET)
uint32_t povBuildLogs(uint8_t* out, size_t cap, uint32_t since);
