#pragma once
#include "config.h"
#include <WString.h>

void setupNetwork();
void loopNetwork();
void loadFrameFromFile(String path);
void webLog(const char* msg);
void webLogf(const char* fmt, ...);
void resetTimeSync();               // Вызывать в начале setup() — сбрасывает millis-базу

extern String currentDisplayFile;   // Имя файла, загруженного в frameBuffer