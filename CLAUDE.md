# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.
Если мой запрос на русском языке, то отвечай тоже на русском. Комментарии к коду должны быть тоже на русском языке.
Если у тебя есть предложения, или ты не согласен с моим предложением - напиши об этом и предложи лучший вариант реализации, опиши его сильные стороны.
В конце пиши очень краткий комментарий для коммита на английском по всем внесенным в код изменениям, если таковые были.

## Project Overview

POV (Persistence of Vision) Wheel Display — an ESP32-S3 embedded system that drives 304 SK9822-A addressable LEDs across a 4-arm spinning rotor. A Hall effect sensor synchronizes LED rendering to rotation. Features a web UI, OTA updates, and wireless master-slave synchronization via ESP-NOW.

## Build & Upload Commands

```bash
# Build
pio run -e cable

# Upload via USB
pio run -e cable --target upload

# Upload OTA to specific devices
pio run -e wheel_dc14 --target upload    # 192.168.0.16
pio run -e wheel_2348 --target upload    # 192.168.0.17
pio run -e wheel_3 --target upload       # mDNS: pov-wheel-5e6f.local

# Serial monitor
pio device monitor -e cable -b 115200

# Clean rebuild
pio run --clean-first -e cable
```

No automated tests exist. Validation is done via serial monitor and the web UI at the device IP or `http://<hostname>.local`.

## Architecture

### Rendering Pipeline

1. **Hall sensor** (PIN_COLOR_INT, GPIO 4) triggers `magnetInterruptHandler()` on each wheel revolution, timestamping `last_hall_time` and `rotation_period`.
2. **`loop()`** in [src/main.cpp](src/main.cpp) computes the current sector (0–119) from elapsed time since last magnet pass.
3. **`drawSector(int sector)`** reads from the PSRAM-allocated `frameBuffer` and writes to the `leds[]` FastLED array (304 LEDs, SPI via GPIO 11/12). Each arm mirrors data symmetrically — 38 LEDs per half-arm.
4. **`FastLED.show()`** sends updated data to the SK9822-A chain.

### Frame Buffer Format

- **Static image:** 765,887 bytes of raw RGB — `360 sectors × 38 LEDs × 3 bytes`
- **Animation (GIF-like):** `"ANIM"` magic header (4 bytes) + frame count (2 bytes) + frame delay ms (2 bytes) + N × 13,680 bytes of frame data
- All files uploaded to LittleFS must have a `.bin` extension
- Buffer is allocated in PSRAM; old buffer is explicitly freed before loading a new file

### Power Management

- **Active:** DCDC converter (GPIO 10) and level shifter (GPIO 9) powered on when rotation detected
- **Idle:** After ~1 second with no Hall pulses, peripherals power down
- **Deep sleep:** After 60 seconds of no web UI activity, enters deep sleep (~1.5–2mA). Wake sources: PIN_WAKEUP (GPIO 5 button) or BQ25792 charger interrupt
- GPIO hold states must be configured before entering deep sleep to maintain pin levels

### Module Breakdown

| File | Role |
|------|------|
| [src/main.cpp](src/main.cpp) | Setup, main loop, rendering, power management, BQ25792 charger init |
| [src/network.cpp](src/network.cpp) | WiFi (AP+STA), AsyncWebServer, file upload/playback, OTA, mDNS |
| [src/esp_now_sync.cpp](src/esp_now_sync.cpp) | ESP-NOW master-slave sync, device discovery, settings broadcast |
| [include/config.h](include/config.h) | GPIO pin definitions, `NUM_LEDS` (304), `FRAME_SIZE` (13,680) |
| [data/index.html](data/index.html) | Web UI served from LittleFS |

### Web API Endpoints

Served by AsyncWebServer on port 80:

```
GET  /list              # JSON list of .bin files on LittleFS
GET  /play?file=X       # Load and start playing file X
GET  /stop              # Stop rendering
GET  /delete?file=X     # Delete file from LittleFS
GET  /settings          # Update brightness/angle (params: bmin, bmax, a)
GET  /battery           # JSON: battery voltage and current
GET  /album             # Slideshow control
GET  /scan_slaves       # Trigger ESP-NOW discovery broadcast
GET  /scan_results      # Return discovered slave name/MAC
GET  /pair?mac=XX:XX... # Pair with a slave device (persisted to preferences)
POST /upload            # Multipart upload of .bin file to LittleFS
```

### ESP-NOW Synchronization

Three packet types: `PKT_SETTINGS` (0x01), `PKT_DISCOVER` (0x03), `PKT_REPLY` (0x04).

- **Master** broadcasts brightness, angle offset, and animation settings to all paired slaves
- **Slave** mirrors received settings and renders identically
- A 3-second hold of GPIO 0 (PIN_BUTTON) toggles master/slave mode and restarts the device
- Paired slave MAC is persisted in NVS preferences across reboots

### Auto-Brightness

BH1750 lux sensor polled every 200ms in `loop()`. Brightness is clamped between `min_brightness` and `max_brightness` (set via `/settings`). `sendSettingsToSlave()` is called on every change to keep slaves in sync.

## Key Configuration (config.h)

```c
#define NUM_LEDS           304   // 4 arms × 76 LEDs
#define FRAME_SIZE       41040   // 360 sectors × 38 LEDs × 3 bytes RGB
#define PIN_LED_DATA        11   // SK9822 SPI data
#define PIN_LED_CLK         12   // SK9822 SPI clock
#define PIN_COLOR_INT        4   // Hall effect sensor
#define PIN_EN_DCDC         10   // DCDC power gate
#define PIN_EN_LEVEL_SHIFT   9   // Level shifter power gate
#define PIN_WAKEUP           5   // Wake/button
#define BQ25792_ADDR      0x6B   // Charger IC I2C address
```

## Important Caveats

- **WiFi credentials** are hardcoded in [src/network.cpp](src/network.cpp) (lines 25–26). The device always creates its own AP hotspot (`pov-wheel-XXXX`) regardless of STA connection status.
- **`FASTLED_ALLOW_INTERRUPTS 1`** in config.h is required — disabling it breaks Hall sensor timing.
- **BQ25792 watchdog** is explicitly disabled (register 0x10 = 0x00) during init; re-enabling it will cause automatic charger resets.
- **Volatile interrupt variables** (`new_magnet_detected`, `bq_interrupt`, etc.) are read with interrupts briefly disabled in `loop()` to avoid races.
- Flash partitioned as 16MB via `default_16MB.csv`; changing the partition scheme requires re-flashing the full chip.
