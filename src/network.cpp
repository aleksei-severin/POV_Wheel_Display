#include "network.h"
#include <WiFi.h>
#include <ESPmDNS.h>
#include <ArduinoOTA.h>
#include <ESPAsyncWebServer.h>
#include <ElegantOTA.h>
#include <LittleFS.h>
#include <Preferences.h>
#include <FastLED.h>
#include <HTTPClient.h>

AsyncWebServer server(80);
Preferences prefs;
File uploadFile;

String hostName;

// --- WiFi credentials ---
const char* HOTSPOT_SSID = "Bunnies Stan 2.4G";
const char* HOTSPOT_PASS = "ValentinaAleksei";

// --- Статусные флаги для LED-индикации WiFi ---
volatile bool blink_wifi_ok_flag   = false;
volatile bool blink_wifi_fail_flag = false;
volatile bool blink_ap_client_flag = false;

// --- Состояние переподключения ---
static bool     sta_was_connected      = false;
static bool     initial_connect_done   = false;
static uint32_t last_reconnect_attempt = 0;

void safeOTAShutdown() {
    FastLED.clear();
    sendLEDs_DMA();
}

void loadFrameFromFile(String path) {
    File f = LittleFS.open(path, "r");
    if (!f) return;

    // Останавливаем рендеринг ДО освобождения буфера: renderingTask (Core 1) может
    // работать параллельно (Core 0), и увидеть frameBuffer = nullptr → краш.
    newFrameReady = false;

    if (frameBuffer != nullptr) {
        free(frameBuffer);
        frameBuffer = nullptr;
    }

    totalFrames = 1;
    frameDelay = 100;
    currentFrameIndex = 0;

    size_t fileSize = f.size();

    if (fileSize > FRAME_SIZE) {
        char magic[4];
        f.read((uint8_t*)magic, 4);
        if (magic[0] == 'A' && magic[1] == 'N' && magic[2] == 'I' && magic[3] == 'M') {
            f.read((uint8_t*)&totalFrames, 2);
            f.read((uint8_t*)&frameDelay, 2);

            size_t dataSize = totalFrames * FRAME_SIZE;
            frameBuffer = (uint8_t*)ps_malloc(dataSize);

            if (frameBuffer) {
                f.read(frameBuffer, dataSize);
            } else {
                totalFrames = 0;
                Serial.println("PSRAM alloc error loading animation!");
            }
        } else {
            f.seek(0);
            frameBuffer = (uint8_t*)ps_malloc(FRAME_SIZE);
            if (frameBuffer) f.read(frameBuffer, FRAME_SIZE);
        }
    } else {
        frameBuffer = (uint8_t*)ps_malloc(FRAME_SIZE);
        if (frameBuffer) f.read(frameBuffer, FRAME_SIZE);
    }

    f.close();
    // Если выделить память не удалось — не запускаем рендеринг с нулевым буфером.
    // renderingTask обратится к frameBuffer по нулевому указателю → паника.
    if (frameBuffer == nullptr) return;
    // Запускаем таймер кадров только ПОСЛЕ завершения чтения файла:
    // если поставить в начало, то при медленном чтении (100–500 мс) первый кадр
    // будет немедленно пропущен в renderingTask, т.к. frameDelay уже истёк.
    lastFrameSwitchTime = millis();
    newFrameReady = true;
}

void setupNetwork() {
    prefs.begin("pov_config", false);

    uint8_t mac[6];
    WiFi.macAddress(mac);
    char nameBuf[20];
    sprintf(nameBuf, "pov-wheel-%02x%02x", mac[4], mac[5]);
    hostName = String(nameBuf);

    WiFi.mode(WIFI_AP_STA);

    IPAddress apIP(192, 168, 4, 1);
    WiFi.softAPConfig(apIP, apIP, IPAddress(255, 255, 255, 0));
    WiFi.softAP(hostName.c_str(), "", 1);

    // Мигание желтым, когда кто-то подключается к нашей точке доступа
    WiFi.onEvent([](WiFiEvent_t event, WiFiEventInfo_t info) {
        blink_ap_client_flag = true;
    }, ARDUINO_EVENT_WIFI_AP_STACONNECTED);

    WiFi.begin(HOTSPOT_SSID, HOTSPOT_PASS);

    uint32_t startAttempt = millis();
    bool connected = false;

    while (millis() - startAttempt < 10000) {
        if (WiFi.status() == WL_CONNECTED) {
            connected = true;
            break;
        }
        delay(500);
    }

    // ВАЖНО: НЕ переключаемся в WIFI_AP при неудаче —
    // остаемся в WIFI_AP_STA и будем повторять попытки в loopNetwork().
    if (connected) {
        sta_was_connected = true;
        blink_wifi_ok_flag = true;
        Serial.printf("WiFi STA: подключено, IP: %s\n", WiFi.localIP().toString().c_str());
    } else {
        blink_wifi_fail_flag = true;
        Serial.println("WiFi STA: подключение не удалось. Повтор через 30 с.");
    }
    initial_connect_done  = true;
    last_reconnect_attempt = millis();

    MDNS.begin(hostName.c_str());

    // --- WEB SERVER ---
    server.on("/", HTTP_GET, [](AsyncWebServerRequest *request){
        last_web_activity_time = millis();
        if (LittleFS.exists("/index.html")) request->send(LittleFS, "/index.html", "text/html");
        else request->send(404, "text/plain", "Upload Filesystem Image!");
    });

    server.on("/settings", HTTP_GET, [](AsyncWebServerRequest *request){
        last_web_activity_time = millis();
        if (request->hasParam("bmin")) min_brightness = request->getParam("bmin")->value().toInt();
        if (request->hasParam("bmax")) max_brightness = request->getParam("bmax")->value().toInt();
        if (request->hasParam("a")) global_angle_offset = request->getParam("a")->value().toInt();
        if (request->hasParam("g")) {
            float gv = request->getParam("g")->value().toFloat();
            if (gv >= 1.0f && gv <= 5.0f) global_gamma = gv;
        }
        if (request->hasParam("s")) {
            float sv = request->getParam("s")->value().toFloat();
            if (sv >= 1.0f && sv <= 3.0f) global_saturation = sv;
        }
        // Мгновенный пересчёт яркости — не ждём следующего тика датчика (50 мс)
        float ratio = constrain(last_lux_value / 1000.0f, 0.0f, 1.0f);
        global_brightness = (uint8_t)constrain(
            (int)(ratio * (float)max_brightness),
            (int)min_brightness,
            (int)max_brightness
        );
        request->send(200, "text/plain", "OK");
    });

    server.on("/get_settings", HTTP_GET, [](AsyncWebServerRequest *request){
        last_web_activity_time = millis();
        String json = "{";
        json += "\"bmin\":" + String(min_brightness) + ",";
        json += "\"bmax\":" + String(max_brightness) + ",";
        json += "\"angle\":" + String(global_angle_offset) + ",";
        json += "\"brightness\":" + String(global_brightness) + ",";
        json += "\"gamma\":" + String(global_gamma, 1) + ",";
        json += "\"saturation\":" + String(global_saturation, 1);
        json += "}";
        request->send(200, "application/json", json);
    });

    server.on("/list", HTTP_GET, [](AsyncWebServerRequest *request){
        last_web_activity_time = millis();
        File root = LittleFS.open("/");
        String json = "[";
        File file = root.openNextFile();
        bool first = true;
        while(file) {
            String fn = String(file.name());
            if(fn.endsWith(".bin")) {
                if(!first) json += ",";
                json += "{\"name\":\"" + fn + "\",\"size\":" + String(file.size()) + "}";
                first = false;
            }
            file = root.openNextFile();
        }
        json += "]";
        request->send(200, "application/json", json);
    });

    server.on("/fs_info", HTTP_GET, [](AsyncWebServerRequest *request){
        size_t total = LittleFS.totalBytes();
        size_t used  = LittleFS.usedBytes();
        String json = "{\"total\":" + String(total) + ",\"used\":" + String(used) + ",\"free\":" + String(total - used) + "}";
        request->send(200, "application/json", json);
    });

    server.on("/play", HTTP_GET, [](AsyncWebServerRequest *request){
        last_web_activity_time = millis();
        if (request->hasParam("file")) {
            String fname = request->getParam("file")->value();
            // Сохраняем файл ДО загрузки: если загрузка упадёт с крашем,
            // после перезагрузки устройство восстановит правильный файл.
            prefs.putString("last_file", fname);
            force_stop_display = false;
            // Передаём загрузку в fileLoaderTask (Core 0, приоритет 2).
            // Это освобождает WiFi-задачу немедленно — браузер получает ответ
            // без ожидания пока LittleFS прочитает весь файл (может быть секунды).
            pendingFilePath = "/" + fname;
            request_play_flag = true;
            xSemaphoreGive(fileLoaderSemaphore);
            request->send(200, "text/plain", "Playing");
        }
    });

    server.on("/stop", HTTP_GET, [](AsyncWebServerRequest *request){
        last_web_activity_time = millis();
        force_stop_display = true;
        request->send(200, "text/plain", "Stopped");
    });

    server.on("/delete", HTTP_GET, [](AsyncWebServerRequest *request){
        last_web_activity_time = millis();
        if (request->hasParam("file")) {
            LittleFS.remove("/" + request->getParam("file")->value());
            request->send(200, "text/plain", "Deleted");
        }
    });

    server.on("/upload", HTTP_POST, [](AsyncWebServerRequest *request){
        last_web_activity_time = millis();
        //blink_ok_flag = true; // Trigger green blink on upload complete
        request->send(200, "text/plain", "OK");
    }, NULL, [](AsyncWebServerRequest *request, uint8_t *data, size_t len, size_t index, size_t total){
        last_web_activity_time = millis();
        String filepath = "/" + (request->hasParam("name") ? request->getParam("name")->value() : "temp.bin");
        if (index == 0) uploadFile = LittleFS.open(filepath, "w");
        if (uploadFile) uploadFile.write(data, len);
        if (index + len == total && uploadFile) uploadFile.close();
    });

    server.on("/ping", HTTP_GET, [](AsyncWebServerRequest *request){
        last_web_activity_time = millis();
        request->send(200, "text/plain", "OK");
    });

    server.on("/info", HTTP_GET, [](AsyncWebServerRequest *request){
        last_web_activity_time = millis();
        uint32_t period  = rotation_period;
        uint32_t hall_t  = last_hall_time;
        uint32_t now_us  = micros();
        // Защита от переполнения uint32_t при вычитании
        uint32_t elapsed = (now_us >= hall_t) ? (now_us - hall_t)
                                              : (0xFFFFFFFFUL - hall_t + now_us + 1);
        // Если с последнего прохода магнита прошло > 2с — колесо остановлено
        float rpm = 0.0f;
        if (period > 0 && elapsed < 2000000UL) {
            rpm = 60000000.0f / (float)period;
        }
        String json = "{\"rpm\":" + String(rpm, 1) + "}";
        request->send(200, "application/json", json);
    });

    ElegantOTA.begin(&server);
    ElegantOTA.onStart(safeOTAShutdown);
    server.begin();

    ArduinoOTA.setHostname(hostName.c_str());
    ArduinoOTA.onStart(safeOTAShutdown);
    ArduinoOTA.begin();
}

void loopNetwork() {
    ArduinoOTA.handle();
    ElegantOTA.loop();

    // --- Мониторинг и переподключение к домашней сети WiFi ---
    if (initial_connect_done) {
        wl_status_t sta_status = WiFi.status();
        uint32_t now_ms = millis();

        if (sta_status == WL_CONNECTED && !sta_was_connected) {
            // Новое соединение установлено (первичное или после обрыва)
            sta_was_connected = true;
            blink_wifi_ok_flag = true;
            Serial.printf("WiFi STA: подключено, IP: %s\n", WiFi.localIP().toString().c_str());
        } else if (sta_status != WL_CONNECTED && sta_was_connected) {
            // Соединение потеряно
            sta_was_connected = false;
            Serial.println("WiFi STA: соединение потеряно");
        }

        // Периодическая попытка переподключения каждые 30 секунд
        if (sta_status != WL_CONNECTED && now_ms - last_reconnect_attempt > 30000) {
            last_reconnect_attempt = now_ms;
            Serial.println("WiFi STA: попытка переподключения...");
            WiFi.begin(HOTSPOT_SSID, HOTSPOT_PASS);
        }
    }
}
