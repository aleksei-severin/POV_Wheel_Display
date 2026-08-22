#pragma once
#include <Arduino.h>

// =====================================================================
//  BLE-интерфейс управления (GATT, NimBLE)
//
//  Заменяет веб-страницу как основной способ работы с колесом. Причины ровно
//  три, и все они про телефон, а не про удобство кода:
//    1. Точка доступа ESP32 не имеет выхода в интернет, и Android, подключаясь
//       к ней, отбирает мобильную передачу данных у всего телефона.
//    2. К двум колёсам одновременно по Wi-Fi подключиться нельзя вообще —
//       у телефона один STA-интерфейс. По BLE их можно держать сколько угодно.
//    3. Приёмник Wi-Fi в режиме точки доступа включён постоянно и стоит
//       ~100 мА. BLE просыпается только на интервал соединения.
//
//  Протокол намеренно бинарный, а не JSON: полезная нагрузка одной посылки
//  ATT — 244…514 байта, и тратить их на имена полей значит платить лишним
//  round-trip'ом на каждый чих.
//
//  ХАРАКТЕРИСТИКИ
//    CMD  (write)          — команда от телефона, см. PovOp
//    RSP  (notify)         — ответ на команду, при нужде фрагментами
//    DATA (write-no-rsp)   — тело заливки: только байты, без заголовков
//    FLOW (notify)         — сколько тела переварено; окно для телефона
//    TELE (read + notify)  — телеметрия, раз в 500 мс
// =====================================================================

#define POV_BLE_PROTO       1

#define POV_SVC_UUID   "5f6b1000-9c4e-4a7d-b3f2-1d8e6a5c4b30"
#define POV_CMD_UUID   "5f6b1001-9c4e-4a7d-b3f2-1d8e6a5c4b30"
#define POV_RSP_UUID   "5f6b1002-9c4e-4a7d-b3f2-1d8e6a5c4b30"
#define POV_DATA_UUID  "5f6b1003-9c4e-4a7d-b3f2-1d8e6a5c4b30"
#define POV_FLOW_UUID  "5f6b1004-9c4e-4a7d-b3f2-1d8e6a5c4b30"
#define POV_TELE_UUID  "5f6b1005-9c4e-4a7d-b3f2-1d8e6a5c4b30"

// Код операции. Пакет CMD: [op][seq][payload…]
enum PovOp : uint8_t {
    OP_HELLO       = 0x01,  // →  ничего            ←  PovHello
    OP_GET_SET     = 0x02,  // →  ничего            ←  PovSettings
    OP_SET_SET     = 0x03,  // →  PovSettings       ←  ничего (значения зажимаются)
    OP_SAVE        = 0x04,  // →  ничего            ←  ничего (settings_dirty = true)
    OP_LIST        = 0x05,  // →  ничего            ←  [u16 count]{[u8 len][name][u32 size]}…
    OP_PLAY        = 0x06,  // →  имя файла
    OP_STOP        = 0x07,
    OP_DELETE      = 0x08,  // →  имя файла
    OP_EFFECT      = 0x09,  // →  [u8 id][u16 speed_red]
    OP_ALBUM       = 0x0A,  // →  [u8 action 0=stop 1=start][u32 delay_ms]
    OP_TELE        = 0x0B,  // →  ничего            ←  PovTele
    OP_PREVIEW     = 0x0C,  // →  имя файла         ←  [u8 sec][u8 rad][RGB565 sec*rad]
    OP_SETTIME     = 0x0D,  // →  [u32 epoch][i32 tz_sec]
    OP_FSINFO      = 0x0E,  // →  ничего            ←  PovFsInfo
    OP_LOGS        = 0x0F,  // →  [u32 since]       ←  [u32 next]{текст с \n}
    OP_UP_BEGIN    = 0x10,  // →  PovUpBegin + имя  ←  [u16 chunk][u32 window]
    OP_UP_END      = 0x11,  // →  ничего            ←  ничего (проверка размера и CRC)
    OP_UP_ABORT    = 0x12,
    OP_OTA_BEGIN   = 0x13,  // →  [u32 size][u32 crc32]
    OP_OTA_END     = 0x14,
    OP_REBOOT      = 0x15,
    OP_WIFI        = 0x16,  // →  [u8 on] — поднять Wi-Fi для OTA по воздуху/веба
    OP_SLEEP       = 0x17,
    OP_FRAG        = 0x18,  // →  [u32 off][u16 len] ←  срез большого ответа
};

// Код результата в ответе
enum PovStatus : uint8_t {
    ST_OK        = 0,
    ST_BAD_OP    = 1,
    ST_BAD_ARG   = 2,
    ST_BUSY      = 3,
    ST_NOT_FOUND = 4,
    ST_NO_SPACE  = 5,
    ST_CRC       = 6,
    ST_STATE     = 7,
    ST_FAIL      = 8,
    ST_OOM       = 9,
};

// Бит в поле flags ответа: за этим фрагментом будут ещё
#define POV_F_MORE  0x01

#pragma pack(push, 1)

// Ответ на OP_HELLO. Всё, что телефону нужно знать до первого запроса.
struct PovHello {
    uint8_t  proto;            // POV_BLE_PROTO
    uint8_t  arms;             // NUM_ARMS
    uint8_t  leds_per_side;    // LEDS_PER_SIDE
    uint8_t  pal_colors;       // 0 = 256 (в байт не влезает)
    uint16_t sectors;          // SECTORS
    uint16_t frame_stride;     // FRAME_STRIDE_PAL — сколько стоит один кадр
    uint16_t mtu;              // согласованный ATT MTU
    uint16_t features;         // POV_FEAT_*
    uint32_t uptime_s;
    char     name[20];         // hostName, с завершающим нулём
    char     fw[12];           // дата сборки, с завершающим нулём
};

#define POV_FEAT_DEFLATE  0x0001   // OP_UP_BEGIN понимает comp = 1
#define POV_FEAT_OTA      0x0002   // прошивка по BLE
#define POV_FEAT_PREVIEW  0x0004
#define POV_FEAT_WIFI     0x0008   // Wi-Fi можно поднять по требованию

// Настройки, симметричные на чтение и запись. Всё, что имеет побочные эффекты
// (эффект, слайдшоу, воспроизведение), сюда НЕ входит — у этого свои команды,
// потому что смена ждёт, пока рендер отпустит буфер кадра.
struct PovSettings {
    uint8_t  bmin;          // 1…31
    uint8_t  bmax;          // 1…31
    int16_t  angle;         // global_angle_offset, градусы
    uint16_t gamma_x100;    // 100…500
    uint16_t sat_x100;      // 100…300
    uint16_t contrast_x10;  // 0…1000
    uint16_t circ;          // 2000…2500 мм
    uint8_t  arm_reverse;   // 0/1
    uint8_t  _pad;
    uint16_t abl_x10;       // 0…1000
    uint16_t rg_x10;        // 0…1000
    uint16_t gg_x10;
    uint16_t bg_x10;
    uint16_t rpm_on;        // 30…600
    uint16_t rpm_off;       // 20…<rpm_on
};

// Телеметрия. Одна посылка, шлётся раз в 500 мс, пока кто-то подписан.
struct PovTele {
    uint16_t rpm_x10;
    int8_t   dir;
    uint8_t  pwr;             // PowerState
    uint16_t step_x100;       // global_render_span, градусы
    uint32_t fill_us;
    uint16_t kmh_x10;
    int16_t  vbat_mv;
    int16_t  vusb_mv;
    int16_t  ocv_mv;
    int16_t  sag_mv;
    uint16_t rise_mv;
    uint8_t  soc;             // %
    uint8_t  chg;             // 0 разряд, 1 заряд, 2 заряжено
    uint8_t  usb;             // 0/1
    uint8_t  lux_hi;          // освещённость / 4, чтобы влезла в байт
    uint8_t  bri;             // global_brightness
    uint8_t  eff_bri;         // global_effective_brightness
    uint8_t  abl_rms;         // %
    uint8_t  abl_cap;         // %, потолок от защиты батареи
    uint8_t  cutoff;          // 0/1
    uint8_t  effect;          // EffectId
    uint8_t  play;            // 0/1 (инверсия force_stop_display)
    uint8_t  slideshow;       // 0/1
    uint8_t  frames_total;    // totalFrames, обрезано до 255 — только для UI
    uint8_t  wifi;            // 0/1 — Wi-Fi поднят
    uint32_t state_ver;
    uint32_t file_ver;
    uint32_t epoch;           // текущее время устройства, 0 = не синхронизировано
    char     file[32];        // имя проигрываемого файла, с завершающим нулём
    // Интервал слайдшоу, секунды. Добавлено В КОНЕЦ намеренно: любое поле,
    // вставленное выше, сдвинуло бы все последующие смещения, по которым
    // Proto.kt разбирает посылку жёстко.
    //
    // Нужно оно потому, что регулятор интервала обязан показывать то, что на
    // устройстве на самом деле. Без обратного чтения приложение показывало
    // собственное значение по умолчанию, и на переподключении оно молча
    // расходилось с реальным — а раз интервал применяется сразу, такой
    // регулятор не просто врёт, а при первом же касании навязывает колесу
    // цифру, которую пользователь не выбирал.
    uint16_t slide_secs;
};

struct PovFsInfo {
    uint32_t total;
    uint32_t used;
    uint32_t free;
    uint32_t psram_free;      // самый большой непрерывный блок PSRAM
    uint16_t frame_stride;
    uint16_t max_frames;      // сколько кадров влезет прямо сейчас
};

// Начало заливки. За структурой идёт имя файла (без ведущего '/').
struct PovUpBegin {
    uint8_t  comp;        // 0 — как есть, 1 — поток raw deflate
    uint8_t  flags;
    uint32_t raw_size;    // размер РАСПАКОВАННОГО файла
    uint32_t comp_size;   // сколько байт придёт по DATA
    uint32_t crc32;       // CRC32 распакованных данных (как java.util.zip.CRC32)
};

// Ответ на OP_UP_BEGIN / OP_OTA_BEGIN
struct PovUpReady {
    uint16_t chunk;       // максимум байт в одной посылке DATA
    uint32_t window;      // сколько байт можно отправить, не дожидаясь FLOW
};

// Уведомление FLOW: сколько байт тела уже переварено. Телефон держит
// (отправлено − consumed) ≤ window.
struct PovFlow {
    uint32_t consumed;    // байт DATA принято и обработано
    uint32_t written;     // байт записано в файл (после распаковки)
    uint8_t  status;      // PovStatus, != 0 — заливка сорвана
};

#pragma pack(pop)

// Снимок телеметрии питания. Реализована в main.cpp: pwr_cache и коэффициенты
// самокалибровки там static, и вытаскивать их в глобальные ради BLE не за чем.
struct PovPowerTele {
    int16_t  vbat_mv;
    int16_t  vusb_mv;
    int16_t  ocv_mv;
    int16_t  sag_mv;
    uint16_t rise_mv;
    uint8_t  soc;
    uint8_t  chg;
    uint8_t  usb;
};
void povGetPowerTele(PovPowerTele* out);

// Состарить таймеры простоя, чтобы loop() ушёл в сон штатным путём.
// Реализована в main.cpp: last_motion_ms там static.
void povRequestSleep();

// Заявка на подъём Wi-Fi из loop(). См. OP_WIFI.
extern volatile bool pending_wifi_on;

// Занимает свои буферы в PSRAM. Вызывать РАНО, до автозапуска анимации:
// кадры забирают PSRAM мегабайтами, и 112 кБ после них может уже не найтись.
// Отдельно от bleSetup(), чтобы адвертайзинг начинался в конце setup(), когда
// семафоры и задачи уже существуют и команду есть кому исполнить.
void bleReserve();

// Поднимает стек и начинает адвертайзинг. Вызывать один раз из setup().
// false — стек не поднялся; вызывающий обязан дать другой путь к устройству,
// иначе оно останется доступно только по USB.
bool bleSetup();

// Периодические дела: телеметрия, дожатие отложенных операций. Из loop().
void bleLoop();

// true пока телефон подключён. Используется таймером сна: пока связь есть,
// порог простоя такой же щедрый, как раньше при клиенте на точке доступа —
// конвертация длинного GIF на телефоне занимает десятки секунд тишины.
bool bleConnected();

// Счётчики версий состояния и списка файлов. Живут здесь, а не в network.cpp:
// теперь их дёргают и BLE-команды тоже, а веб может быть вообще не поднят.
extern volatile uint32_t pov_state_version;
extern volatile uint32_t pov_file_version;

// Wi-Fi поднят? По умолчанию нет — его включает только OP_WIFI, чтобы можно
// было прошиться по воздуху из PlatformIO или открыть старую веб-страницу.
extern volatile bool wifi_enabled;
