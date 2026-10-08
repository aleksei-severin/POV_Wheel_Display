// =====================================================================
//  BLE-интерфейс управления. См. include/povble.h — там протокол.
//
//  ГЛАВНОЕ ПРО СКОРОСТЬ ЗАЛИВКИ
//  Одна анимация — это N × 16608 байт, и на 480 кадрах выходит 8 МБ. Сырым
//  потоком по BLE это минуты. Поэтому телефон присылает файл сжатым обычным
//  deflate, а распаковывает его РОМ: tinfl_decompress лежит в ПЗУ ESP32-S3
//  (0x40000828) и не стоит ни байта флеша. На индексах палитры deflate даёт
//  3–5×, то есть эффективная скорость втрое-впятеро выше пропускной способности
//  радио. Всё остальное — это уже борьба за саму пропускную способность:
//    • MTU 517 → 514 байт полезной нагрузки в посылке вместо 20;
//    • запись БЕЗ подтверждения (write-no-response) на отдельной характеристике;
//    • LE 2M PHY — вдвое быстрее радио, если телефон умеет;
//    • Data Length Extension (251 байт на пакет канального уровня);
//    • интервал соединения 7.5–15 мс;
//    • кредитное окно: телефон льёт непрерывно, пока устройство не отстанет,
//      а не ждёт ответа на каждую посылку.
//
//  Флеш пишет ОТДЕЛЬНАЯ задача, а не колбэк ATT. Колбэк только копирует байты
//  в кольцевой буфер в PSRAM: стирание страницы флеша занимает десятки
//  миллисекунд, и задача хоста NimBLE на это время перестала бы отвечать —
//  и связь рвётся по супервизии соединения.
// =====================================================================

#include "config.h"
#include "povble.h"
#include "storage.h"
#include "effects.h"
#include "hall_log.h"
#include <NimBLEDevice.h>
#include <LittleFS.h>
#include <Update.h>
#include <esp_heap_caps.h>
#include <esp_ota_ops.h>
#include <esp_system.h>
#include <esp_timer.h>
#include <sys/time.h>
#include <time.h>

// tinfl из ПЗУ. Заголовок лежит в esp_rom/include/esp32s3 и сам обёрнут
// в extern "C" — своей обёртки не нужно.
#include "rom/miniz.h"

// ---------------------------------------------------------------------
//  Раскладка структур — часть протокола, а не деталь реализации.
//  На той стороне те же поля разбирает ByteBuffer в Proto.kt по жёстко
//  прошитым смещениям. Разъехавшийся на байт размер не даст ни ошибки, ни
//  предупреждения: телефон просто начнёт писать гамму в контраст. Пусть лучше
//  не собирается.
// ---------------------------------------------------------------------
static_assert(sizeof(PovHello)    == 48, "PovHello != Hello.SIZE в Proto.kt");
static_assert(sizeof(PovSettings) == 26, "PovSettings != Settings.SIZE в Proto.kt");
static_assert(sizeof(PovTele)     == 82, "PovTele != Tele.SIZE в Proto.kt");
static_assert(sizeof(PovFsInfo)   == 20, "PovFsInfo != FsInfo.SIZE в Proto.kt");
static_assert(sizeof(PovUpBegin)  == 14, "PovUpBegin != заголовок заливки в BleClient.kt");
static_assert(sizeof(PovUpReady)  ==  6, "PovUpReady != UpReady.parse в Proto.kt");
static_assert(sizeof(PovFlow)     ==  9, "PovFlow != Flow.parse в Proto.kt");
static_assert(sizeof(PovTime)     == 40, "PovTime != TimeInfo.SIZE в Proto.kt");
static_assert(sizeof(PovHallPage) == 44, "PovHallPage != HallPage.SIZE в Proto.kt");
static_assert(sizeof(PovHallHist) == 20, "PovHallHist != HallHist.SIZE в Proto.kt");
static_assert(sizeof(PovTextStyle) == 8, "PovTextStyle != TextStyle.SIZE в Proto.kt");
static_assert(sizeof(PovFxParams) == 16, "PovFxParams != FxParams.SIZE в Proto.kt");

// Приём сжатой маски эффекта «Текст» (OP_TEXT_SET): куски складываются здесь,
// пока не придёт последний. Маска 360 × 44 со строкой сжимается в единицы
// килобайт; запас — под слой цвета эмодзи (см. TEXT_COMP_MAX в effects.h).
#define TEXT_RX_CAP  TEXT_COMP_MAX

// Приём длинного отбора слайдшоу (OP_ALBUM_LIST): имена копятся здесь, пока
// OP_ALBUM с n = 0xFFFF не заберёт их. Предел — с запасом на всю библиотеку.
#define ALBUM_RX_MAX 1000
static std::vector<String> album_rx;
static uint16_t            album_rx_total = 0;
static uint8_t*  text_rx       = nullptr;
static uint16_t  text_rx_total = 0;
static uint16_t  text_rx_len   = 0;

// ---------------------------------------------------------------------
//  Общие счётчики версий состояния и списка файлов.
// ---------------------------------------------------------------------
volatile uint32_t pov_state_version = 0;
volatile uint32_t pov_file_version  = 0;

// Из storage.cpp: имя, которое загрузчик кадра запишет в NVS уже с погашенной
// лентой. Писать его здесь нельзя — стирание флеша заморозит renderingTask.
extern String pendingPlayFile;

// Заявка на транспортный режим (OP_POWEROFF). Исполняет loop():
// enterTransportSleep() гасит ленту через SPI, сбрасывает настройки и
// калибровку во флеш и уходит в сон, из которого будит только удержание кнопки.
volatile bool pending_transport_off = false;

// Заявка на перезагрузку (OP_REBOOT). Исполняет loop().
volatile bool pending_reboot = false;

// ---------------------------------------------------------------------
//  CRC32 (полином 0xEDB88320, отражённый) — тот же, что java.util.zip.CRC32.
//  Своя реализация, а не esp_rom_crc32_le: у ромовой неочевидная трактовка
//  начального значения, а расходиться с телефоном тут нельзя совсем.
// ---------------------------------------------------------------------
static uint32_t crc32_update(uint32_t crc, const uint8_t* d, size_t n) {
    static const uint32_t tbl[16] = {
        0x00000000, 0x1DB71064, 0x3B6E20C8, 0x26D930AC,
        0x76DC4190, 0x6B6B51F4, 0x4DB26158, 0x5005713C,
        0xEDB88320, 0xF00F9344, 0xD6D6A3E8, 0xCB61B38C,
        0x9B64C2B0, 0x86D3D2D4, 0xA00AE278, 0xBDBDF21C
    };
    crc = ~crc;
    while (n--) {
        crc ^= *d++;
        crc = (crc >> 4) ^ tbl[crc & 0x0F];
        crc = (crc >> 4) ^ tbl[crc & 0x0F];
    }
    return ~crc;
}

// ---------------------------------------------------------------------
//  Состояние BLE
// ---------------------------------------------------------------------
static NimBLEServer*         srv      = nullptr;
static NimBLECharacteristic* chCmd    = nullptr;
static NimBLECharacteristic* chRsp    = nullptr;
static NimBLECharacteristic* chData   = nullptr;
static NimBLECharacteristic* chFlow   = nullptr;
static NimBLECharacteristic* chTele   = nullptr;

static volatile uint16_t conn_id   = 0xFFFF;
static volatile bool     connected = false;
static volatile uint16_t peer_mtu  = 23;

// Буфер сборки ответа. Большие ответы (список файлов, превью, лог) телефон
// вычитывает командой OP_FRAG по смещению — так фрагмент нельзя потерять
// молча, и приложение не собирает поток уведомлений в конечный автомат.
#define STAGE_CAP  (48 * 1024)
static_assert(TEXT_COMP_MAX + sizeof(PovTextStyle) + 2 <= STAGE_CAP, "блоб текста не влезет в ответ OP_TEXT_GET");
static uint8_t*  stage     = nullptr;
static uint32_t  stage_len = 0;

// ---------------------------------------------------------------------
//  Кольцевой буфер тела заливки
//  Один производитель (задача хоста NimBLE) и один потребитель (bleWriterTask),
//  обе на ядре 0 — поэтому хватает volatile-индексов без барьеров.
// ---------------------------------------------------------------------
#define RING_CAP  (64 * 1024)

// Максимум байт в одном обращении к флешу. См. комментарий в bleWriterTask.
#define WRITE_CHUNK_MAX  4096

// Сколько молчания считать брошенной передачей. Заведомо больше любой паузы
// внутри живой заливки: телефон льёт непрерывно, а окно кредитов не даёт ему
// уйти в тишину дольше одного круга уведомлений.
#define XFER_STALL_MS    10000
static uint8_t*           ring = nullptr;
static volatile uint32_t  ring_head = 0;   // пишет колбэк ATT
static volatile uint32_t  ring_tail = 0;   // пишет bleWriterTask

enum XferMode : uint8_t { XFER_NONE = 0, XFER_FILE = 1, XFER_OTA = 2 };
static volatile uint8_t  xfer_mode  = XFER_NONE;
static volatile bool     xfer_error = false;
static volatile uint8_t  xfer_status = ST_OK;

static uint8_t   xfer_comp      = 0;
static uint32_t  xfer_raw_size  = 0;
static uint32_t  xfer_comp_size = 0;
static uint32_t  xfer_crc_want  = 0;
static uint32_t  xfer_crc_calc  = 0;
static volatile uint32_t xfer_in_recv  = 0;   // байт принято в кольцо
static volatile uint32_t xfer_in_done  = 0;   // байт вынуто из кольца
static volatile uint32_t xfer_out_done = 0;   // байт записано после распаковки
static String    xfer_path;
static File      xfer_file;
static uint32_t  xfer_started_ms = 0;

// Рукопожатие с bleWriterTask. Задача хоста NimBLE идёт с приоритетом заметно
// выше и на том же ядре 0, поэтому вытесняет писателя на любой инструкции —
// в том числе прямо внутри inflateFeed(). Освобождать словарь и закрывать файл
// в этот момент значит вырвать их у писателя из-под ног. Приём тот же, что у
// loadFrameFromFile() с render_in_fill: писатель поднимает флаг ДО проверки
// режима, а тот, кто сворачивает передачу, сначала снимает режим и только
// потом ждёт, пока флаг опустится.
static volatile bool writer_busy = false;

// Распаковщик. Словарь обязан быть ровно 32768 байт и кольцевым — так требует
// tinfl, если не задан TINFL_FLAG_USING_NON_WRAPPING_OUTPUT_BUF.
static tinfl_decompressor* tinfl_st  = nullptr;
static uint8_t*            tinfl_dic = nullptr;
static uint32_t            dict_ofs  = 0;
static bool                inflate_done = false;

// ---------------------------------------------------------------------
//  Ответы
// ---------------------------------------------------------------------
static inline uint16_t attPayload() {
    uint16_t m = peer_mtu;
    if (m < 23) m = 23;
    uint16_t n = m - 3;
    // Потолок 512 — и это НЕ перестраховка, а причина, по которой не работала
    // ни одна заливка.
    //
    // При MTU 517 в посылку влезает 514 байт, и столько мы и обещали телефону
    // в PovUpReady.chunk. Но длина значения атрибута ограничена спецификацией
    // 512 байтами: NimBLEAttValue зажимает запрошенный max_len до
    // BLE_ATT_ATTR_MAX_LEN (NimBLEAttValue.h), а обработчик записи сравнивает
    // с ним длину пришедшего пакета и отвечает BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN
    // (NimBLECharacteristic.cpp). Характеристика DATA объявлена без
    // подтверждения — отказ телефону не доезжает вообще. В итоге он спокойно
    // лил байты, которые устройство отвергало все до единого: кольцо не
    // наполнялось, счётчик принятого стоял на нуле, и заливка умирала по
    // таймауту, сообщая «failed» без единой подсказки почему.
    // Константа стека, а не число: предел один и тот же и здесь, и в проверке
    // внутри NimBLE, и разъехаться им нельзя.
    if (n > BLE_ATT_ATTR_MAX_LEN) n = BLE_ATT_ATTR_MAX_LEN;
    return n;
}

static void sendRsp(uint8_t op, uint8_t seq, uint8_t status,
                    const void* payload = nullptr, size_t len = 0, uint8_t flags = 0) {
    if (!chRsp || !connected) return;
    uint8_t buf[520];
    size_t  cap = attPayload();
    if (cap > sizeof(buf)) cap = sizeof(buf);
    // Обрезать ответ молча нельзя: телефон разбирает структуры по жёстким
    // смещениям и на укороченной посылке прочитает мусор либо бросит
    // исключение, которое наверху выглядит как «настройки не пришли». Если не
    // влезает (МТУ остался 23 — обмен не удался), честнее вернуть ошибку.
    if (len > cap - 4) {
        webLogf("[BLE] Reply %u B does not fit MTU %u", (unsigned)len, (unsigned)peer_mtu);
        len = 0;
        status = ST_FAIL;
    }
    buf[0] = op; buf[1] = seq; buf[2] = status; buf[3] = flags;
    if (len && payload) memcpy(buf + 4, payload, len);
    chRsp->notify(buf, len + 4);
}

// Готовит большой ответ в stage и отдаёт телефону только его длину.
static void stageRsp(uint8_t op, uint8_t seq) {
    uint32_t n = stage_len;
    sendRsp(op, seq, ST_OK, &n, sizeof(n));
}

// ---------------------------------------------------------------------
//  Заливка: запись распакованного потока
// ---------------------------------------------------------------------
static bool xferWriteOut(const uint8_t* p, size_t n) {
    if (!n) return true;
    // Не даём записать больше заявленного: врущий (или битый) заголовок иначе
    // забил бы флеш до конца.
    if (xfer_out_done + n > xfer_raw_size) {
        xfer_status = ST_BAD_ARG;
        return false;
    }
    xfer_crc_calc = crc32_update(xfer_crc_calc, p, n);
    if (xfer_mode == XFER_FILE) {
        if (xfer_file.write(p, n) != n) { xfer_status = ST_NO_SPACE; return false; }
    } else {
        if (Update.write((uint8_t*)p, n) != n) { xfer_status = ST_FAIL; return false; }
    }
    xfer_out_done += n;
    return true;
}

// Прогоняет очередной кусок сжатого потока через tinfl.
static bool inflateFeed(const uint8_t* in, size_t in_len, bool more_input) {
    for (;;) {
        size_t din  = in_len;
        size_t dout = TINFL_LZ_DICT_SIZE - dict_ofs;
        uint32_t flags = more_input ? TINFL_FLAG_HAS_MORE_INPUT : 0;
        tinfl_status st = tinfl_decompress(tinfl_st, (const mz_uint8*)in, &din,
                                           (mz_uint8*)tinfl_dic,
                                           (mz_uint8*)tinfl_dic + dict_ofs, &dout, flags);
        in     += din;
        in_len -= din;
        if (dout) {
            if (!xferWriteOut(tinfl_dic + dict_ofs, dout)) return false;
            dict_ofs = (dict_ofs + dout) & (TINFL_LZ_DICT_SIZE - 1);
        }
        if (st == TINFL_STATUS_DONE)              { inflate_done = true; return true; }
        if (st < 0)                               { xfer_status = ST_CRC;  return false; }
        if (st == TINFL_STATUS_NEEDS_MORE_INPUT)  return true;
        // TINFL_STATUS_HAS_MORE_OUTPUT — словарь заполнен, крутим ещё
    }
}

static void xferCleanup(bool remove_file) {
    // Сначала запрещаем писателю входить в работу, потом ждём, пока он выйдет
    // из уже начатого куска, и только после этого что-либо освобождаем.
    uint8_t was = xfer_mode;
    xfer_mode = XFER_NONE;
    uint32_t t0 = millis();
    while (writer_busy && (millis() - t0) < 3000) vTaskDelay(pdMS_TO_TICKS(2));

    if (was == XFER_FILE) {
        if (xfer_file) xfer_file.close();
        if (remove_file && xfer_path.length()) LittleFS.remove(xfer_path);
    } else if (was == XFER_OTA) {
        if (remove_file) Update.abort();
    }
    if (tinfl_st)  { free(tinfl_st);  tinfl_st  = nullptr; }
    if (tinfl_dic) { free(tinfl_dic); tinfl_dic = nullptr; }
    xfer_error = false;
    ring_head  = 0;
    ring_tail  = 0;
    xfer_path  = "";
}

// Обратная к safeOTAShutdown(). Прошивка не состоялась — устройство обязано
// вернуться в рабочее состояние, и само оно этого не сделает: LittleFS
// монтируется ровно в одном месте, в setup().
//
// Без этого неудачный Update.begin() (образ больше слота — а слот 1472 кБ, и
// слитый «merged» файл в него не влезает) оставлял колесо в тупике: файловая
// система размонтирована, список пуст, любой файл «не найден», лента погашена,
// а ota_in_progress заперт навсегда. Последнее хуже всего — на нём завязаны ОБА
// пути сна, и глубокий по простою, и спасательный при подзаряде, так что плата
// не засыпает уже никогда и высаживает ячейку до отсечки BMS.
//
// begin(false), а не begin(true): автоформат уместен только на первом старте.
// Здесь ФС заведомо цела — прошивка пишется в раздел приложения, не в spiffs, —
// и форматирование на пути восстановления стёрло бы всю библиотеку анимаций.
static void otaShutdownUndo() {
    if (!LittleFS.begin(false)) webLog("[BLE] FS remount after a failed OTA failed");
    ota_in_progress = false;
    // force_stop_display НЕ трогаем: его показывает PovTele.play, и приложение
    // честно скажет «остановлено», а Play вернёт картинку. Снять его вслепую
    // значило бы запустить показ, который пользователь до прошивки остановил.
}

static void flowNotify() {
    if (!chFlow || !connected) return;
    PovFlow f;
    f.consumed = xfer_in_done;
    f.written  = xfer_out_done;
    f.status   = xfer_error ? xfer_status : ST_OK;
    chFlow->notify((uint8_t*)&f, sizeof(f));
}

// ---------------------------------------------------------------------
//  Задача записи. Ядро 0 — то же, что у хоста NimBLE и у загрузчика файлов;
//  ядро 1 целиком занято renderingTask.
// ---------------------------------------------------------------------
static void bleWriterTask(void*) {
    uint32_t last_flow   = 0;
    uint32_t stall_mark  = 0;   // xfer_in_recv на момент последней проверки тишины
    uint32_t stall_since = 0;
    for (;;) {
        // Флаг поднимается ДО проверки: иначе сворачивающий передачу успел бы
        // проскочить в зазор между проверкой и началом работы.
        writer_busy = true;
        if (xfer_mode == XFER_NONE || xfer_error) {
            writer_busy = false;
            // Счётчик отчётов принадлежит передаче: без сброса следующая
            // заливка считала бы разницу от чужого числа, получала переполнение
            // и слала уведомление на каждый кусок, отбирая радиовремя у данных.
            last_flow   = 0;
            stall_mark  = 0;
            stall_since = millis();
            vTaskDelay(pdMS_TO_TICKS(10));
            continue;
        }
        uint32_t head = ring_head, tail = ring_tail;
        if (head == tail) {
            // Кольцо пусто — сообщим телефону окно и подождём
            writer_busy = false;
            if (xfer_in_done != last_flow) { flowNotify(); last_flow = xfer_in_done; }

            // Сторож брошенной передачи. Телефон, замолчавший без разрыва
            // связи (свернули приложение, ушли из зоны, убил Doze), оставлял
            // xfer_mode взведённым НАВСЕГДА: файл открыт, OP_UP_BEGIN вечно
            // отвечает ST_BUSY, а bleLoop() из экономии радиовремени молчит на
            // всю передачу — устройство выглядит мёртвым и лечится только
            // перезагрузкой. Считаем тишину от последнего принятого байта.
            if (xfer_in_recv == stall_mark) {
                if (millis() - stall_since > XFER_STALL_MS) {
                    xfer_error = true;
                    // Два очень разных случая, которые снаружи выглядят
                    // одинаково: телефон замолчал — или он исправно писал, а
                    // приёмная сторона всё отвергла (так и было при куске в
                    // 514 байт против предела атрибута в 512, и характеристика
                    // без подтверждения не дала телефону этого заметить).
                    // Ноль принятых байт отличает второй случай от первого.
                    if (xfer_in_recv == 0) {
                        xfer_status = ST_STATE;
                        webLogf("[BLE] Upload: not one byte arrived in %u s — "
                                "the phone's chunks are being rejected, not lost",
                                (unsigned)(XFER_STALL_MS / 1000));
                    } else {
                        xfer_status = ST_FAIL;
                        webLogf("[BLE] Upload stalled at %lu/%lu, aborting",
                                (unsigned long)xfer_out_done, (unsigned long)xfer_raw_size);
                    }
                    flowNotify();
                }
            } else {
                stall_mark  = xfer_in_recv;
                stall_since = millis();
            }
            vTaskDelay(pdMS_TO_TICKS(2));
            continue;
        }
        size_t chunk = (head > tail) ? (head - tail) : (RING_CAP - tail);
        // Потолок куска. Без него сюда прилетало до 64 кБ одним write(): это
        // десятки миллисекунд записи во флеш, а запись во флеш гасит кеш команд
        // на ОБОИХ ядрах и замораживает renderingTask, который исполняется из
        // флеша. Тот же приём, что в loadFrameFromFile(), — читать блоками и
        // отпускать процессор между ними.
        if (chunk > WRITE_CHUNK_MAX) chunk = WRITE_CHUNK_MAX;
        const uint8_t* p = ring + tail;

        bool ok;
        if (xfer_comp) {
            bool more = (xfer_in_done + chunk) < xfer_comp_size;
            ok = inflateFeed(p, chunk, more);
        } else {
            ok = xferWriteOut(p, chunk);
        }
        if (!ok) {
            xfer_error = true;
            writer_busy = false;
            webLogf("[BLE] Upload failed at %lu/%lu", (unsigned long)xfer_out_done,
                    (unsigned long)xfer_raw_size);
            flowNotify();
            continue;
        }
        ring_tail    = (tail + chunk) & (RING_CAP - 1);
        xfer_in_done += chunk;
        writer_busy  = false;

        // Окно телефону — не на каждый кусок: уведомление тоже занимает
        // радиовремя, которое нужно самим данным.
        if (xfer_in_done - last_flow >= 8192) { flowNotify(); last_flow = xfer_in_done; }

        // Отдать процессор ОБЯЗАТЕЛЬНО, и именно на ветке «данные идут».
        // Задача сидит на ядре 0 с приоритетом 1, IDLE0 — с нулевым, и IDLE0
        // в отличие от IDLE1 остаётся под наблюдением Task WDT (снимаем мы
        // только ядро 1, main.cpp). Пока кольцо не пустеет — а при deflate
        // 3–5× оно не пустеет минутами, — IDLE0 не получает ни тика, и через
        // пять секунд сторож перезагружает плату ровно посреди заливки.
        // Цена уступки: 4 кБ за такт = 4 МБ/с потолка, вчетверо выше того, что
        // вообще способен принести радиоканал.
        vTaskDelay(1);
    }
}

// ---------------------------------------------------------------------
//  Приём тела (характеристика DATA)
// ---------------------------------------------------------------------
class DataCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* c) override {
        if (xfer_mode == XFER_NONE || xfer_error) return;
        NimBLEAttValue v = c->getValue();
        const uint8_t* p = v.data();
        size_t n = v.length();
        if (!n) return;

        uint32_t head = ring_head, tail = ring_tail;
        size_t free_sp = (RING_CAP - 1) - ((head - tail) & (RING_CAP - 1));
        if (n > free_sp) {
            // Кредитное окно нарушено — честнее оборвать заливку, чем записать
            // файл с дырой: битый кадр от целого на ободе не отличить.
            xfer_error  = true;
            xfer_status = ST_BUSY;
            webLog("[BLE] Ring overflow, upload aborted");
            // Сказать об этом обязаны здесь: задача записи после взведённого
            // xfer_error сразу уходит в ожидание и уведомления уже не пошлёт,
            // а телефон узнаёт об обрыве только из FLOW — иначе он досидит до
            // своего таймаута и назовёт причину неверно.
            flowNotify();
            return;
        }
        size_t first = RING_CAP - head;
        if (first > n) first = n;
        memcpy(ring + head, p, first);
        if (n > first) memcpy(ring, p + first, n - first);
        ring_head = (head + n) & (RING_CAP - 1);
        xfer_in_recv += n;
        last_web_activity_time = millis();
    }
};

// ---------------------------------------------------------------------
//  Сбор ответов в stage
// ---------------------------------------------------------------------
static void buildFileList() {
    stage_len = 0;
    if (!stage) return;
    uint16_t count = 0;
    uint32_t pos = 2;                       // место под счётчик

    File root = LittleFS.open("/");
    File f = root.openNextFile();
    while (f) {
        String fn = String(f.name());
        if (fn.startsWith("/")) fn = fn.substring(1);
        uint32_t sz = f.size();
        if (fn.endsWith(".bin") && sz > 0) {
            uint8_t nl = fn.length();
            if (pos + 1 + nl + 4 <= STAGE_CAP) {
                stage[pos++] = nl;
                memcpy(stage + pos, fn.c_str(), nl); pos += nl;
                memcpy(stage + pos, &sz, 4);         pos += 4;
                count++;
            }
        }
        f = root.openNextFile();
    }
    stage[0] = count & 0xFF;
    stage[1] = (count >> 8) & 0xFF;
    stage_len = pos;
}

// Превью: кадр файла, прореженный до PV_SEC × PV_RAD и приведённый к RGB565.
// Наружу всегда один формат — приложению незачем знать, что лежит на флеше.
#define PV_SEC  120
#define PV_RAD   22

// Кадр файла для превью, прочитанный с флеша в PSRAM.
struct PvSrc {
    uint8_t* buf    = nullptr;   // кадр; освобождает вызывающий
    bool     pal    = false;     // ANI6: палитра 768 Б + индексы
    bool     legacy = false;     // RGB888 (ANIM и старые статичные)
    uint16_t frames = 1;         // из заголовка; у файла без заголовка — один
    uint16_t delay  = 0;         // мс на кадр, из заголовка
};

// false — файла нет или в нём нет кадра frame.
static bool pvRead(const String& name, uint32_t frame, PvSrc& s) {
    File f = LittleFS.open("/" + name, "r");
    if (!f || f.size() < 8) { if (f) f.close(); return false; }

    uint8_t hdr[8];
    f.read(hdr, 8);
    size_t offset = 0;
    bool   anim = true;
    if      (memcmp(hdr, "ANI6", 4) == 0) { offset = 8; s.pal = true; }
    else if (memcmp(hdr, "ANI5", 4) == 0) { offset = 8; }
    else if (memcmp(hdr, "ANIM", 4) == 0) { offset = 8; s.legacy = true; }
    else { anim = false; s.legacy = (f.size() >= FRAME_SIZE_888); }
    if (anim) {
        // Бит 15 числа кадров — флаг зеркала задней стороны (см. loadFrameFromFile).
        s.frames = (uint16_t)((hdr[4] | (hdr[5] << 8)) & 0x7FFF);
        s.delay  = (uint16_t)(hdr[6] | (hdr[7] << 8));
        if (s.frames == 0) s.frames = 1;
    }
    if (frame >= s.frames) { f.close(); return false; }

    size_t srcLen = s.pal ? FRAME_STRIDE_PAL : (s.legacy ? FRAME_SIZE_888 : FRAME_SIZE);
    s.buf = (uint8_t*)ps_malloc(srcLen);
    if (!s.buf) { f.close(); return false; }
    memset(s.buf, 0, srcLen);                 // обрезанный файл даст чёрный хвост
    size_t at = offset + (size_t)frame * srcLen;
    if (at < f.size()) {
        size_t avail = f.size() - at;
        f.seek(at);
        f.read(s.buf, avail < srcLen ? avail : srcLen);
    }
    f.close();
    return true;
}

static inline void pvRgb(const PvSrc& s, int sec, int led, uint8_t* r, uint8_t* g, uint8_t* b) {
    uint32_t i = (uint32_t)sec * LEDS_PER_SIDE + led;
    if (s.pal) {
        const uint8_t* e = s.buf + s.buf[PAL_BYTES + i] * 3;
        *r = e[0]; *g = e[1]; *b = e[2];
    } else if (s.legacy) {
        const uint8_t* e = s.buf + i * 3;
        *r = e[0]; *g = e[1]; *b = e[2];
    } else {
        uint16_t v = ((const uint16_t*)s.buf)[i];
        *r = (v >> 8) & 0xF8; *g = (v >> 3) & 0xFC; *b = (v << 3) & 0xF8;
    }
}

static inline uint32_t pvPut565(uint32_t pos, uint32_t r, uint32_t g, uint32_t b) {
    uint16_t v = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3);
    stage[pos++] = v & 0xFF;
    stage[pos++] = v >> 8;
    return pos;
}

// OP_PREVIEW: первый кадр, по одной точке на клетку.
static bool buildPreview(const String& name) {
    stage_len = 0;
    PvSrc s;
    if (!pvRead(name, 0, s)) return false;
    stage[0] = PV_SEC;
    stage[1] = PV_RAD;
    uint32_t pos = 2;
    for (int sc = 0; sc < PV_SEC; sc++) {
        int ss = sc * (SECTORS / PV_SEC);
        for (int r = 0; r < PV_RAD; r++) {
            // Растягиваем на ВЕСЬ радиус: при шаге LEDS_PER_SIDE/PV_RAD = 2
            // последняя строка попадала бы на диод 42 из 43, и миниатюра
            // выезжала бы наружу на половину диода.
            int rr = r * (LEDS_PER_SIDE - 1) / (PV_RAD - 1);
            uint8_t R, G, B;
            pvRgb(s, ss, rr, &R, &G, &B);
            pos = pvPut565(pos, R, G, B);
        }
    }
    free(s.buf);
    stage_len = pos;
    return true;
}

// OP_PREVIEW_AT: кадр frame для анимированного превью. Клетка — среднее блока
// 3 сектора × 2 диода, а не одна точка: кадров в клипе много, и рябь выборки по
// точке на маленьком диске видна сильнее, чем лёгкая мягкость. Возвращает статус.
static uint8_t buildPreviewAt(const String& name, uint32_t frame) {
    stage_len = 0;
    PvSrc s;
    if (!pvRead(name, frame, s)) {
        if (s.buf) free(s.buf);
        return LittleFS.exists("/" + name) ? ST_BAD_ARG : ST_NOT_FOUND;
    }
    PovPreviewAt h;
    h.frames   = s.frames;
    h.delay_ms = s.delay;
    h.sec      = PV_SEC;
    h.rad      = PV_RAD;
    memcpy(stage, &h, sizeof(h));
    uint32_t pos = sizeof(h);
    const int SB = SECTORS / PV_SEC, LB = LEDS_PER_SIDE / PV_RAD, N = SB * LB;
    for (int sc = 0; sc < PV_SEC; sc++) {
        for (int r = 0; r < PV_RAD; r++) {
            uint32_t R = 0, G = 0, B = 0;
            for (int a = 0; a < SB; a++) {
                for (int b = 0; b < LB; b++) {
                    uint8_t r8, g8, b8;
                    pvRgb(s, sc * SB + a, r * LB + b, &r8, &g8, &b8);
                    R += r8; G += g8; B += b8;
                }
            }
            pos = pvPut565(pos, (R + N / 2) / N, (G + N / 2) / N, (B + N / 2) / N);
        }
    }
    free(s.buf);
    stage_len = pos;
    return ST_OK;
}

// ---------------------------------------------------------------------
//  Настройки
// ---------------------------------------------------------------------
static void fillSettings(PovSettings* s) {
    s->bmin         = min_brightness;
    s->bmax         = max_brightness;
    s->angle        = (int16_t)global_angle_offset;
    s->gamma_x100   = (uint16_t)lroundf(global_gamma * 100.0f);
    s->sat_x100     = (uint16_t)lroundf(global_saturation * 100.0f);
    s->contrast_x10 = (uint16_t)lroundf(global_contrast * 10.0f);
    s->circ         = wheel_circumference;
    s->arm_reverse  = global_arm_reverse ? 1 : 0;
    s->_pad         = 0;
    s->abl_x10      = (uint16_t)lroundf(global_abl_limit * 10.0f);
    s->rg_x10       = (uint16_t)lroundf(global_r_gain * 10.0f);
    s->gg_x10       = (uint16_t)lroundf(global_g_gain * 10.0f);
    s->bg_x10       = (uint16_t)lroundf(global_b_gain * 10.0f);
    s->rpm_on       = (uint16_t)lroundf(rpm_render_on);
    s->rpm_off      = (uint16_t)lroundf(rpm_render_off);
}

// Границы значений настроек: всё, что приходит снаружи, зажимается здесь.
static void applySettings(const PovSettings* s) {
    if (s->bmin >= 1 && s->bmin <= 31) min_brightness = s->bmin;
    if (s->bmax >= 1 && s->bmax <= 31) max_brightness = s->bmax;
    global_angle_offset = s->angle;
    float g = s->gamma_x100 / 100.0f;
    if (g >= 1.0f && g <= 5.0f) global_gamma = g;
    float sa = s->sat_x100 / 100.0f;
    if (sa >= 1.0f && sa <= 3.0f) global_saturation = sa;
    float co = s->contrast_x10 / 10.0f;
    if (co >= 0.0f && co <= 100.0f) global_contrast = co;
    if (s->circ >= 2000 && s->circ <= 2500) wheel_circumference = s->circ;
    global_arm_reverse = s->arm_reverse != 0;
    float v = s->abl_x10 / 10.0f;  if (v >= 0.0f && v <= 100.0f) global_abl_limit = v;
    v = s->rg_x10 / 10.0f;         if (v >= 0.0f && v <= 100.0f) global_r_gain = v;
    v = s->gg_x10 / 10.0f;         if (v >= 0.0f && v <= 100.0f) global_g_gain = v;
    v = s->bg_x10 / 10.0f;         if (v >= 0.0f && v <= 100.0f) global_b_gain = v;
    float on = s->rpm_on, off = s->rpm_off;
    if (on >= 30.0f && on <= 600.0f && off >= 20.0f && off < on) {
        rpm_render_on  = on;
        rpm_render_off = off;
    }
    // Мгновенный пересчёт яркости — как в /settings, не ждём тика датчика
    float ratio = constrain(last_lux_value / 1000.0f, 0.0f, 1.0f);
    global_brightness = (uint8_t)constrain((int)(ratio * (float)max_brightness),
                                           (int)min_brightness, (int)max_brightness);
    if (!peripherals_active) global_effective_brightness = global_brightness;
    settings_dirty = true;
    pov_state_version++;
}

// ---------------------------------------------------------------------
//  Телеметрия
// ---------------------------------------------------------------------
static void fillTele(PovTele* t) {
    memset(t, 0, sizeof(*t));
    uint32_t period = rotation_period;
    uint32_t hall_t = last_hall_time;
    uint32_t now_us = micros();
    uint32_t elapsed = (now_us >= hall_t) ? (now_us - hall_t)
                                         : (0xFFFFFFFFUL - hall_t + now_us + 1);
    float rpm = 0.0f;
    if (period > 0 && elapsed < 3000000UL) {
        uint32_t eff = (elapsed > period) ? elapsed : period;
        rpm = 60000000.0f / (float)eff;
    }
    t->rpm_x10   = (uint16_t)lroundf(rpm * 10.0f);
    t->dir       = rotation_dir;
    t->pwr       = (uint8_t)power_state;
    t->step_x100 = (uint16_t)lroundf(global_render_span * 100.0f);
    t->fill_us   = global_render_fill_us;
    t->kmh_x10   = (uint16_t)lroundf(currentSpeedKmh() * 10.0f);

    PovPowerTele p;
    povGetPowerTele(&p);
    t->vbat_mv = p.vbat_mv; t->vusb_mv = p.vusb_mv; t->ocv_mv = p.ocv_mv;
    t->sag_mv  = p.sag_mv;  t->rise_mv = p.rise_mv;
    t->soc     = p.soc;     t->chg     = p.chg;     t->usb    = p.usb;

    float lux = last_lux_value;
    if (lux < 0) lux = 0;
    if (lux > 1020) lux = 1020;
    t->lux_hi     = (uint8_t)(lux / 4.0f);
    t->bri        = global_brightness;
    t->eff_bri    = global_effective_brightness;
    t->abl_rms    = (uint8_t)constrain((int)lroundf(global_abl_rms * 100.0f), 0, 255);
    t->abl_cap    = (uint8_t)constrain((int)lroundf((float)batt_abl_cap), 0, 100);
    t->cutoff     = batt_cutoff ? 1 : 0;
    t->effect     = effect_id;
    t->play       = force_stop_display ? 0 : 1;
    t->slideshow  = slideshowActive ? 1 : 0;
    t->frames_total = (uint8_t)(totalFrames > 255 ? 255 : totalFrames);
    t->state_ver  = pov_state_version;
    t->file_ver   = pov_file_version;
    time_t nowt = time(nullptr);
    t->epoch      = (nowt > 1672531200) ? (uint32_t)nowt : 0;   // до 2023 — «не задано»
    // Идёт эффект — на ободе не файл. В слайдшоу currentDisplayFile держит имя
    // последнего файла и не сбрасывается сам, поэтому гасим его здесь по effect_id.
    const char* cf = (effect_id != EFF_NONE) ? "" : currentDisplayFile.c_str();
    if (*cf == '/') cf++;
    strncpy(t->file, cf, sizeof(t->file) - 1);
    t->slide_secs = (uint16_t)(slideInterval / 1000);
}

// ---------------------------------------------------------------------
//  Проверка имени файла. Правила те же, что у Ani6.buildFileName() в приложении:
//  LittleFS в arduino-esp32 держит имя не длиннее 31 байта, и только ASCII.
// ---------------------------------------------------------------------
// ---------------------------------------------------------------------
//  Имя устройства
//
//  По умолчанию POV-xxxx по двум младшим байтам MAC — этим колёса и
//  различались. Когда их два на одном велосипеде, толку от таких имён мало:
//  какое из «POV-0c68» и «POV-1a44» переднее, на глаз не скажешь. Поэтому имя
//  можно задать своё и хранится оно в NVS.
// ---------------------------------------------------------------------
static String ble_name;                       // то, чем представляемся сейчас
static String ble_name_pending;               // ждёт записи в NVS
static volatile bool ble_name_dirty = false;

// Заводское имя из MAC. Wi-Fi MAC, а не BT: по нему уже названы существующие
// колёса, и менять их опознавательный хвост незачем.
static String bleDefaultName() {
    uint8_t mac[6];
    esp_read_mac(mac, ESP_MAC_WIFI_STA);
    char nm[16];
    snprintf(nm, sizeof(nm), "POV-%02x%02x", mac[4], mac[5]);
    return String(nm);
}

// Разрешены латиница, цифры, дефис и подчёркивание. Не вкусовщина: имя уезжает
// в рекламный пакет и в PovHello.name как ASCII, и один символ обязан быть
// одним байтом — иначе кириллица молча обрежется посередине буквы.
static bool bleNameOk(const String& n) {
    if (n.length() == 0 || n.length() > POV_NAME_MAX) return false;
    for (size_t i = 0; i < n.length(); i++) {
        char c = n[i];
        bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                  (c >= '0' && c <= '9') || c == '-' || c == '_';
        if (!ok) return false;
    }
    return true;
}

static bool nameOk(const String& n) {
    if (n.length() == 0 || n.length() > 31) return false;
    if (!n.endsWith(".bin")) return false;
    for (size_t i = 0; i < n.length(); i++) {
        char c = n[i];
        bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                  (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
        if (!ok) return false;
    }
    return n.indexOf("..") < 0;
}

// Ответ OP_TIME / OP_TIME_SET. Метка esp_timer снимается последней, прямо
// перед отправкой: телефон относит её к середине своего круга запрос-ответ,
// и всё, что случилось бы между меткой и уходом посылки, легло бы в ошибку.
static void sendTime(uint8_t op, uint8_t seq) {
    PovTime t;
    memset(&t, 0, sizeof(t));
    t.boot_id   = hallLogBootId();
    t.clock_gen = hallClockGen();
    t.sleep_us  = hallSleepUs();
    t.head_seq  = hallLogHead();
    t.pwr       = (uint8_t)power_state;
    t.flags     = hallClockPrecise() ? 1 : 0;
    t.wall_us   = hallWallUs();      // пара снимается подряд — расходятся на микросекунды
    t.esp_us    = esp_timer_get_time();
    sendRsp(op, seq, ST_OK, &t, sizeof(t));
}

// ---------------------------------------------------------------------
//  Обработка команд
// ---------------------------------------------------------------------
static void handleCmd(const uint8_t* d, size_t n) {
    if (n < 2) return;
    uint8_t op  = d[0];
    uint8_t seq = d[1];
    const uint8_t* pl = d + 2;
    size_t pn = n - 2;

    // Телеметрию и поллинг не считаем активностью — иначе устройство не уснёт
    // никогда, пока приложение открыто в фоне. Реальное взаимодействие
    // (OP_PLAY, OP_STOP, настройки и т.п.) сбрасывает таймер как обычно;
    // повторная присылка того же расписания синхронного показа — нет (см.
    // OP_ALBUM ниже).
    //
    // Обмен лога Холла (OP_TIME/OP_TIME_SET/OP_HALL_LOG/OP_HALL_HIST) — того же
    // рода: приложение ведёт его само, пока колесо на связи, чтобы архив для
    // склейки видео был полным. Колесо на полке рядом с телефоном обязано
    // уснуть, как и без него.
    const uint32_t prev_activity = last_web_activity_time;
    // OP_ALBUM_LIST — только данные: решает следующий за ним OP_ALBUM.
    if (op != OP_TELE && op != OP_FRAG && op != OP_ALBUM_LIST &&
        op != OP_TIME && op != OP_TIME_SET && op != OP_HALL_LOG && op != OP_HALL_HIST)
        last_web_activity_time = millis();

    switch (op) {

    case OP_HELLO: {
        PovHello h;
        memset(&h, 0, sizeof(h));
        h.proto         = POV_BLE_PROTO;
        h.arms          = NUM_ARMS;
        h.leds_per_side = LEDS_PER_SIDE;
        h.pal_colors    = 0;                    // 256 в байт не влезает
        h.sectors       = SECTORS;
        h.frame_stride  = FRAME_STRIDE_PAL;
        h.mtu           = peer_mtu;
        h.features      = POV_FEAT_DEFLATE | POV_FEAT_OTA | POV_FEAT_PREVIEW |
                          POV_FEAT_ALBUM_SEL | POV_FEAT_HALL_LOG2 | POV_FEAT_TEXT | POV_FEAT_FX |
                          POV_FEAT_TEXT_RGB | POV_FEAT_SLIDE_CLOCK | POV_FEAT_ALBUM_LONG |
                          POV_FEAT_PREVIEW_AT;
        h.uptime_s      = millis() / 1000;
        // Именно видимое имя: приложение подписывает им строку списка, и
        // расходиться с тем, что пришло в рекламе, оно не должно.
        strncpy(h.name, ble_name.c_str(), sizeof(h.name) - 1);
        strncpy(h.fw, __DATE__, sizeof(h.fw) - 1);
        sendRsp(op, seq, ST_OK, &h, sizeof(h));
        break;
    }

    case OP_GET_SET: {
        PovSettings s; fillSettings(&s);
        sendRsp(op, seq, ST_OK, &s, sizeof(s));
        break;
    }

    case OP_SET_SET: {
        if (pn < sizeof(PovSettings)) { sendRsp(op, seq, ST_BAD_ARG); break; }
        PovSettings s; memcpy(&s, pl, sizeof(s));
        applySettings(&s);
        sendRsp(op, seq, ST_OK);
        break;
    }

    case OP_SAVE:
        settings_dirty = true;
        sendRsp(op, seq, ST_OK);
        break;

    case OP_LIST:
        buildFileList();
        stageRsp(op, seq);
        break;

    case OP_SETNAME: {
        if (!pn) { sendRsp(op, seq, ST_BAD_ARG); break; }
        String nn((const char*)pl, pn);
        nn.trim();
        if (!bleNameOk(nn)) { sendRsp(op, seq, ST_BAD_ARG); break; }

        ble_name = nn;
        // GAP-имя меняется сразу, реклама — тоже, но увидит её телефон только
        // при следующем сканировании: пока он подключён, рекламы просто нет.
        NimBLEDevice::setDeviceName(ble_name.c_str());
        NimBLEDevice::getAdvertising()->setName(ble_name.c_str());

        // В NVS пишем НЕ ЗДЕСЬ. Стирание страницы флеша гасит кеш команд на
        // обоих ядрах и морозит renderingTask на десятки миллисекунд; если в
        // этот момент колесо крутится, по ободу проедет мусор. Откладываем до
        // остановки — тем же приёмом, что и last_file с настройками.
        ble_name_pending = ble_name;
        ble_name_dirty   = true;

        pov_state_version++;
        webLogf("[BLE] Renamed to %s", ble_name.c_str());
        sendRsp(op, seq, ST_OK);
        break;
    }

    case OP_FRAG: {
        if (pn < 6) { sendRsp(op, seq, ST_BAD_ARG); break; }
        uint32_t off; uint16_t len;
        memcpy(&off, pl, 4); memcpy(&len, pl + 4, 2);
        if (off > stage_len) { sendRsp(op, seq, ST_BAD_ARG); break; }
        uint32_t avail = stage_len - off;
        if (len > avail) len = avail;
        uint16_t cap = attPayload() - 4;
        if (len > cap) len = cap;
        sendRsp(op, seq, ST_OK, stage + off, len,
                (off + len < stage_len) ? POV_F_MORE : 0);
        break;
    }

    case OP_PLAY: {
        if (!pn) { sendRsp(op, seq, ST_BAD_ARG); break; }
        String fname((const char*)pl, pn);
        if (!nameOk(fname)) { sendRsp(op, seq, ST_BAD_ARG); break; }
        if (!LittleFS.exists("/" + fname)) { sendRsp(op, seq, ST_NOT_FOUND); break; }
        // Порядок ровно как в /play: имя в NVS пишет загрузчик, уже погасив
        // ленту, и до подмены буфера кадра.
        pendingPlayFile    = fname;
        slideshowActive    = false;
        pending_effect     = -1;
        force_stop_display = false;
        pendingFilePath    = "/" + fname;
        request_play_flag  = true;
        xSemaphoreGive(fileLoaderSemaphore);
        pov_state_version++;
        webLogf("[BLE] Play: %s", fname.c_str());
        sendRsp(op, seq, ST_OK);
        break;
    }

    case OP_STOP:
        stopDisplayAndSlideshow();
        webLog("[BLE] Stop");
        sendRsp(op, seq, ST_OK);
        break;

    case OP_DELETE: {
        if (!pn) { sendRsp(op, seq, ST_BAD_ARG); break; }
        String fname((const char*)pl, pn);
        if (!nameOk(fname)) { sendRsp(op, seq, ST_BAD_ARG); break; }
        LittleFS.remove("/" + fname);
        handleFileDeleted("/" + fname);
        pov_state_version++;
        pov_file_version++;
        webLogf("[BLE] Delete: %s", fname.c_str());
        sendRsp(op, seq, ST_OK);
        break;
    }

    case OP_EFFECT: {
        if (pn < 1) { sendRsp(op, seq, ST_BAD_ARG); break; }
        uint8_t id = pl[0];
        if (pn >= 3) {
            uint16_t red; memcpy(&red, pl + 1, 2);
            if (red >= 5 && red <= 200) { effect_speed_red = red; settings_dirty = true; }
        }
        // 0xFF — «эффект не трогать». Ползунок красной точки в приложении
        // шлёт именно его: пересылка текущего id заново запускала бы эффект,
        // а тот ждёт освобождения буфера кадра и гасит ленту на это время.
        if (id == 0xFF) { sendRsp(op, seq, ST_OK); break; }
        if (id != EFF_NONE && !effectValid(id)) { sendRsp(op, seq, ST_BAD_ARG); break; }
        slideshowActive = false;
        pendingFilePath = "";
        if (id == EFF_NONE) {
            force_stop_display = true;
        } else {
            force_stop_display = false;
            currentDisplayFile = "";
        }
        pending_effect_play = (id != EFF_NONE);   // до pending_effect: см. effects.h
        pending_effect = (int8_t)id;
        xSemaphoreGive(fileLoaderSemaphore);
        settings_dirty = true;
        pov_state_version++;
        sendRsp(op, seq, ST_OK);
        break;
    }

    case OP_TEXT_STYLE: {
        if (pn < sizeof(PovTextStyle)) { sendRsp(op, seq, ST_BAD_ARG); break; }
        PovTextStyle st; memcpy(&st, pl, sizeof(st));
        effectsTextSetStyle(st.mode, st.r, st.g, st.b, st.speed);
        // Во флеш — отложенно, вместе с настройками (flushSettings()), и только
        // пока лента не светится: цвет крутят ползунком, записей было бы десятки.
        settings_dirty = true;
        sendRsp(op, seq, ST_OK);
        break;
    }

    case OP_TEXT_SET: {
        if (pn < 4) { sendRsp(op, seq, ST_BAD_ARG); break; }
        uint16_t total, off;
        memcpy(&total, pl, 2); memcpy(&off, pl + 2, 2);
        size_t dl = pn - 4;
        if (total == 0 || total > TEXT_RX_CAP || off + dl > total) { sendRsp(op, seq, ST_BAD_ARG); break; }
        if (!text_rx) text_rx = (uint8_t*)ps_malloc(TEXT_RX_CAP);
        if (!text_rx) { sendRsp(op, seq, ST_OOM); break; }
        if (off == 0) { text_rx_total = total; text_rx_len = 0; }
        // Куски строго подряд: потерянный или повторный сбил бы маску молча.
        else if (total != text_rx_total || off != text_rx_len) { sendRsp(op, seq, ST_STATE); break; }
        memcpy(text_rx + off, pl + 4, dl);
        text_rx_len = (uint16_t)(off + dl);
        if (text_rx_len < text_rx_total) { sendRsp(op, seq, ST_OK); break; }
        bool ok = effectsTextSetBlob(text_rx, text_rx_total);
        text_rx_len = 0;
        if (ok) settings_dirty = true;
        sendRsp(op, seq, ok ? ST_OK : ST_CRC);
        break;
    }

    case OP_TEXT_GET: {
        stage_len = 0;
        if (!stage) { sendRsp(op, seq, ST_OOM); break; }
        PovTextStyle st; memset(&st, 0, sizeof(st));
        effectsTextGetStyle(st.mode, st.r, st.g, st.b, st.speed);
        memcpy(stage, &st, sizeof(st));
        uint16_t n = (uint16_t)effectsTextBlob(stage + sizeof(st) + 2, STAGE_CAP - sizeof(st) - 2);
        memcpy(stage + sizeof(st), &n, 2);
        stage_len = sizeof(st) + 2 + n;
        stageRsp(op, seq);
        break;
    }

    case OP_FX_GET: {
        FxParams f; effectsFxGet(f);
        PovFxParams p; memset(&p, 0, sizeof(p));
        p.speed_red = f.speed_red;
        p.rb_speed  = f.rb_speed;
        p.rb_sharp  = f.rb_sharp;
        p.clock.mode = f.clk_mode;
        p.clock.r = f.clk_r; p.clock.g = f.clk_g; p.clock.b = f.clk_b;
        p.clock.speed = f.clk_speed;
        sendRsp(op, seq, ST_OK, &p, sizeof(p));
        break;
    }

    case OP_FX_SET: {
        if (pn < sizeof(PovFxParams)) { sendRsp(op, seq, ST_BAD_ARG); break; }
        PovFxParams p; memcpy(&p, pl, sizeof(p));
        FxParams f;
        f.speed_red = p.speed_red;
        f.rb_speed  = p.rb_speed;
        f.rb_sharp  = p.rb_sharp;
        f.clk_mode  = p.clock.mode;
        f.clk_r = p.clock.r; f.clk_g = p.clock.g; f.clk_b = p.clock.b;
        f.clk_speed = p.clock.speed;
        // Во флеш — отложенно (flushSettings()), только пока лента не светится:
        // ползунки шлют это десятками раз за одно движение.
        effectsFxSet(f);
        // Второй телефон (или второе колесо синхронной группы) перечитает.
        pov_state_version++;
        sendRsp(op, seq, ST_OK);
        break;
    }

    case OP_ALBUM: {
        if (pn < 1) { sendRsp(op, seq, ST_BAD_ARG); break; }
        uint8_t action = pl[0];
        if (action == 0) {
            stopDisplayAndSlideshow();
            webLog("[BLE] Slideshow stop");
            sendRsp(op, seq, ST_OK);
        } else {
            bool changed = false;
            if (pn >= 5) {
                uint32_t ms; memcpy(&ms, pl + 1, 4);
                if (ms >= 1000 && ms <= 300000 && ms != slideInterval) { slideInterval = ms; changed = true; }
            }
            // Необязательный отбор (FEAT_ALBUM_SEL):
            // [u8 mode 0=пропускать 1=играть-только][u16 count]{[u8 len][имя]}[u8 effMask].
            // Короткий пакет — отбор не трогаем (стоп / смена только интервала).
            if (pn >= 8) {
                bool inc = pl[5] != 0;
                uint16_t cnt; memcpy(&cnt, pl + 6, 2);
                std::vector<String> sel;
                size_t o = 8;
                if (cnt == 0xFFFF) {
                    // Имена пришли заранее кусками (OP_ALBUM_LIST) — только целиком.
                    if (album_rx_total == 0 || album_rx.size() != album_rx_total) {
                        sendRsp(op, seq, ST_STATE);
                        break;
                    }
                    sel.swap(album_rx);
                    album_rx_total = 0;
                } else {
                    for (uint16_t i = 0; i < cnt && o < pn; i++) {
                        uint8_t l = pl[o++];
                        if (o + l > pn) break;
                        sel.push_back(String((const char*)(pl + o), (unsigned int)l));
                        o += l;
                    }
                }
                uint8_t effMask = (o < pn) ? pl[o] : 0;   // хвостовой байт маски эффектов
                // За маской — t0 (FEAT_SLIDE_CLOCK): показ по абсолютным часам.
                // Нет поля — обычный показ по своему таймеру (t0 = 0).
                int64_t t0 = 0;
                if (o + 1 + 8 <= pn) memcpy(&t0, pl + o + 1, 8);
                changed |= applySlideList(inc, sel, effMask);
                changed |= slideClockSet(t0);
            }
            if (slideshowActive) {         // уже идёт — интервал и отбор обновили, индекс не трогаем
                if (changed) settings_dirty = true;
                // То же самое расписание ещё раз — это телефон заново вооружает
                // колесо после переподключения, а не человек: таймер простоя не
                // трогаем, иначе каждое переподключение держало бы колесо
                // бодрым лишние минуты.
                else last_web_activity_time = prev_activity;
                slideClockKick();
                sendRsp(op, seq, ST_OK);
                break;
            }
            updateFileList();
            if (savedFiles.size() == 0 && slideEffectMask == 0) { sendRsp(op, seq, ST_NOT_FOUND); break; }
            force_stop_display = false;
            slideshowActive    = true;
            slideCurrentIndex  = -1;
            slideLastSwitch    = 0;
            settings_dirty     = true;
            pov_state_version++;
            webLogf("[BLE] Slideshow start, interval %lus%s", (unsigned long)(slideInterval / 1000),
                    slideClockT0 > 0 ? ", clock-synced" : "");
            slideClockKick();
            sendRsp(op, seq, ST_OK);
        }
        break;
    }

    case OP_ALBUM_LIST: {
        if (pn < 4) { sendRsp(op, seq, ST_BAD_ARG); break; }
        uint16_t first, total;
        memcpy(&first, pl, 2); memcpy(&total, pl + 2, 2);
        if (total == 0 || total > ALBUM_RX_MAX) { sendRsp(op, seq, ST_BAD_ARG); break; }
        if (first == 0) { album_rx.clear(); album_rx_total = total; }
        // Куски строго подряд: потерянный или повторный молча сдвинул бы список.
        else if (total != album_rx_total || first != album_rx.size()) { sendRsp(op, seq, ST_STATE); break; }
        size_t o = 4;
        while (o < pn) {
            uint8_t l = pl[o++];
            if (o + l > pn || album_rx.size() >= album_rx_total) break;
            album_rx.push_back(String((const char*)(pl + o), (unsigned int)l));
            o += l;
        }
        sendRsp(op, seq, ST_OK);
        break;
    }

    case OP_TELE: {
        PovTele t; fillTele(&t);
        sendRsp(op, seq, ST_OK, &t, sizeof(t));
        break;
    }

    case OP_PREVIEW: {
        if (!pn) { sendRsp(op, seq, ST_BAD_ARG); break; }
        String fname((const char*)pl, pn);
        if (!nameOk(fname))       { sendRsp(op, seq, ST_BAD_ARG);  break; }
        if (!buildPreview(fname)) { sendRsp(op, seq, ST_NOT_FOUND); break; }
        stageRsp(op, seq);
        break;
    }

    case OP_PREVIEW_AT: {
        if (pn < 3) { sendRsp(op, seq, ST_BAD_ARG); break; }
        // Чтение кадра с флеша гасит кеш на обоих ядрах — на светящемся ободе это
        // рывок, а клип — это десятки таких чтений подряд. Подождёт, пока колесо
        // погаснет: телефон спросит снова.
        if (power_state == PWR_FULL) { sendRsp(op, seq, ST_BUSY); break; }
        uint16_t fr; memcpy(&fr, pl, 2);
        String fname((const char*)pl + 2, pn - 2);
        if (!nameOk(fname)) { sendRsp(op, seq, ST_BAD_ARG); break; }
        uint8_t st = buildPreviewAt(fname, fr);
        if (st != ST_OK) { sendRsp(op, seq, st); break; }
        stageRsp(op, seq);
        break;
    }

    case OP_SETTIME: {
        if (pn < 8) { sendRsp(op, seq, ST_BAD_ARG); break; }
        uint32_t epoch; int32_t tz;
        memcpy(&epoch, pl, 4); memcpy(&tz, pl + 4, 4);
        povSetTime(epoch, tz);
        sendRsp(op, seq, ST_OK);
        break;
    }

    case OP_FSINFO: {
        PovFsInfo i;
        size_t total = LittleFS.totalBytes(), used = LittleFS.usedBytes();
        i.total        = total;
        i.used         = used;
        // «Свободно» — для анимаций: место под историю лога Холла в него не
        // входит (см. HLOG_FS_RESERVE), приложение по этому числу меряет заливку.
        i.free         = fsFreeForAnimations();
        i.frame_stride = FRAME_STRIDE_PAL;
        // PSRAM-потолок считаем НЕ от «свободно сейчас», а от полного объёма
        // минус постоянный резерв (BLE-буферы ~112 КБ, словарь распаковки 32 КБ,
        // служебное): в любой момент из PSRAM играет ровно ОДНА анимация, а
        // loadFrameFromFile освобождает прежний буфер перед новым — так что один
        // файл может занимать почти весь PSRAM, сколько бы ни было занято тем,
        // что сейчас на ободе.
        // 640 КБ резерва оставляют потолок «пустого» PSRAM примерно там же, где
        // он был у прежнего расчёта от largest_free_block − 256 КБ, но теперь он
        // не проседает, когда что-то играет.
        // + кольцо лога Холла: оно занято с загрузки и под анимацию не отдаётся.
        const uint32_t PS_RESERVE = 640 * 1024 + HLOG_PSRAM_BYTES, FS_RESERVE = 128 * 1024, HDR = 8;
        size_t ps_total = heap_caps_get_total_size(MALLOC_CAP_SPIRAM);
        uint32_t ps_usable = (ps_total > PS_RESERVE) ? (ps_total - PS_RESERVE) : 0;
        i.psram_free = ps_usable;   // приложение показывает это как «доступно под анимацию»
        uint32_t byPs = (ps_usable > HDR) ? (ps_usable - HDR) / FRAME_STRIDE_PAL : 1;
        uint32_t byFs = (i.free > FS_RESERVE + HDR) ? (i.free - FS_RESERVE - HDR) / FRAME_STRIDE_PAL : 1;
        uint32_t mx = byPs < byFs ? byPs : byFs;
        if (mx < 1) mx = 1;
        // Верхний предел — 15 бит: бит 15 поля «число кадров» в заголовке ANI6
        // занят флагом зеркала задней стороны. Физически PSRAM всё равно режет
        // раньше (~480), клап тут — только страховка.
        if (mx > 32767) mx = 32767;
        i.max_frames = (uint16_t)mx;
        sendRsp(op, seq, ST_OK, &i, sizeof(i));
        break;
    }

    case OP_LOGS: {
        uint32_t since = 0;
        if (pn >= 4) memcpy(&since, pl, 4);
        stage_len = povBuildLogs(stage, STAGE_CAP, since);
        stageRsp(op, seq);
        break;
    }

    case OP_UP_BEGIN: {
        if (xfer_mode != XFER_NONE) { sendRsp(op, seq, ST_BUSY); break; }
        if (pn < sizeof(PovUpBegin) + 1) { sendRsp(op, seq, ST_BAD_ARG); break; }
        PovUpBegin b; memcpy(&b, pl, sizeof(b));
        String fname((const char*)(pl + sizeof(b)), pn - sizeof(b));
        if (!nameOk(fname) || b.raw_size == 0) { sendRsp(op, seq, ST_BAD_ARG); break; }

        // Резерв под историю лога Холла анимациям не отдаём (HLOG_FS_RESERVE).
        size_t freeb = fsFreeForAnimations();
        // Перезапись: место, занятое старым файлом, освободится — учитываем его.
        if (LittleFS.exists("/" + fname)) {
            File old = LittleFS.open("/" + fname, "r");
            if (old) { freeb += old.size(); old.close(); }
        }
        if (b.raw_size + 128 * 1024 > freeb) { sendRsp(op, seq, ST_NO_SPACE); break; }

        if (b.comp) {
            tinfl_st  = (tinfl_decompressor*)malloc(sizeof(tinfl_decompressor));
            tinfl_dic = (uint8_t*)ps_malloc(TINFL_LZ_DICT_SIZE);
            if (!tinfl_st || !tinfl_dic) {
                if (tinfl_st)  { free(tinfl_st);  tinfl_st  = nullptr; }
                if (tinfl_dic) { free(tinfl_dic); tinfl_dic = nullptr; }
                sendRsp(op, seq, ST_OOM);
                break;
            }
            tinfl_init(tinfl_st);
        }
        xfer_path = "/" + fname;
        LittleFS.remove(xfer_path);          // освободить блоки ДО выделения новых
        xfer_file = LittleFS.open(xfer_path, "w");
        if (!xfer_file) { xferCleanup(false); sendRsp(op, seq, ST_FAIL); break; }

        xfer_comp = b.comp ? 1 : 0;
        xfer_raw_size = b.raw_size; xfer_comp_size = b.comp_size; xfer_crc_want = b.crc32;
        xfer_crc_calc = 0; xfer_in_recv = 0; xfer_in_done = 0; xfer_out_done = 0;
        dict_ofs = 0; inflate_done = false;
        ring_head = 0; ring_tail = 0;
        xfer_error = false; xfer_status = ST_OK;
        xfer_started_ms = millis();
        xfer_mode = XFER_FILE;

        PovUpReady r;
        r.chunk  = attPayload();
        r.window = RING_CAP - 1024;          // немного меньше кольца — на гонку индексов
        webLogf("[BLE] Upload %s: %lu B%s", fname.c_str(),
                (unsigned long)b.raw_size, b.comp ? " (deflate)" : "");
        sendRsp(op, seq, ST_OK, &r, sizeof(r));
        break;
    }

    case OP_OTA_BEGIN: {
        if (xfer_mode != XFER_NONE) { sendRsp(op, seq, ST_BUSY); break; }
        if (pn < 8) { sendRsp(op, seq, ST_BAD_ARG); break; }
        uint32_t size, crc;
        memcpy(&size, pl, 4); memcpy(&crc, pl + 4, 4);
        if (!size) { sendRsp(op, seq, ST_BAD_ARG); break; }
        // Полный останов, а не два флага. Когда-то здесь он был пропущен, а поднять два флага — это не то же самое:
        // safeOTAShutdown() ещё и дожидается текущей DMA-транзакции, гасит
        // ленту, снимает оба DCDC и РАЗМОНТИРУЕТ LittleFS. Без последнего
        // прошивка пишется поверх смонтированной ФС и рушит библиотеку
        // анимаций — то есть цена пропуска не «мигнёт лента», а потеря файлов.
        // 200 мс ожидания DMA внутри задача хоста NimBLE переживает: тайм-аут
        // супервизии соединения — секунды.
        safeOTAShutdown();
        if (!Update.begin(size, U_FLASH)) {
            // xfer_mode здесь ещё XFER_NONE, поэтому ни OP_UP_ABORT, ни обрыв
            // связи это состояние не разберут — убираем за собой прямо тут.
            otaShutdownUndo();
            sendRsp(op, seq, ST_NO_SPACE);
            break;
        }
        xfer_comp = 0;
        xfer_raw_size = size; xfer_comp_size = size; xfer_crc_want = crc;
        xfer_crc_calc = 0; xfer_in_recv = 0; xfer_in_done = 0; xfer_out_done = 0;
        ring_head = 0; ring_tail = 0;
        xfer_error = false; xfer_status = ST_OK;
        xfer_started_ms = millis();
        xfer_mode = XFER_OTA;
        PovUpReady r; r.chunk = attPayload(); r.window = RING_CAP - 1024;
        webLogf("[BLE] OTA begin: %lu B", (unsigned long)size);
        sendRsp(op, seq, ST_OK, &r, sizeof(r));
        break;
    }

    case OP_UP_END:
    case OP_OTA_END: {
        if (xfer_mode == XFER_NONE) { sendRsp(op, seq, ST_STATE); break; }
        // Дожидаемся, пока задача записи выберет кольцо до конца.
        uint32_t t0 = millis();
        while (!xfer_error && ring_head != ring_tail && (millis() - t0) < 15000) {
            vTaskDelay(pdMS_TO_TICKS(5));
        }
        bool ok = !xfer_error;
        if (ok && xfer_out_done != xfer_raw_size) { xfer_status = ST_BAD_ARG; ok = false; }
        if (ok && xfer_crc_calc != xfer_crc_want) { xfer_status = ST_CRC;     ok = false; }

        bool was_ota = (xfer_mode == XFER_OTA);
        if (ok && was_ota) {
            if (!Update.end(true)) { xfer_status = ST_FAIL; ok = false; }
        }
        uint32_t ms = millis() - xfer_started_ms;
        if (ok) {
            uint32_t kbs = ms ? (xfer_comp_size / ms) : 0;
            webLogf("[BLE] %s done: %lu B in %lu ms (%lu kB/s on air)",
                    was_ota ? "OTA" : "Upload",
                    (unsigned long)xfer_out_done, (unsigned long)ms, (unsigned long)kbs);
        } else {
            webLogf("[BLE] %s FAILED (status %u)", was_ota ? "OTA" : "Upload",
                    (unsigned)xfer_status);
        }
        uint8_t st = ok ? ST_OK : xfer_status;
        xferCleanup(!ok);
        // На успехе дальше перезагрузка — монтировать ФС незачем.
        if (was_ota && !ok) { otaShutdownUndo(); }
        else if (was_ota)   { ota_in_progress = false; }
        // updateFileList() отсюда НЕ вызываем: он чистит и перестраивает
        // savedFiles, а loop() на ядре 1 индексирует тот же вектор в слайдшоу.
        // Список обновится при старте показа.
        else if (ok) { pov_state_version++; pov_file_version++; }
        sendRsp(op, seq, st);
        if (ok && was_ota) { vTaskDelay(pdMS_TO_TICKS(400)); ESP.restart(); }
        break;
    }

    case OP_UP_ABORT: {
        bool was_ota = (xfer_mode == XFER_OTA);
        xferCleanup(true);
        if (was_ota) otaShutdownUndo();
        webLog("[BLE] Upload aborted by client");
        sendRsp(op, seq, ST_OK);
        break;
    }

    case OP_TIME:
        sendTime(op, seq);
        break;

    case OP_TIME_SET: {
        if (pn < 20) { sendRsp(op, seq, ST_BAD_ARG); break; }
        int64_t wall, at; int32_t tz;
        memcpy(&wall, pl, 8); memcpy(&at, pl + 8, 8); memcpy(&tz, pl + 16, 4);
        // Телефон назвал время UTC для момента at по нашим часам esp_timer —
        // переносим его на «сейчас» тем же кварцем.
        int64_t now = esp_timer_get_time();
        povSetTimeUs(wall + (now - at), tz);
        webLogf("[SYS] Clock set precisely (gen %lu)", (unsigned long)hallClockGen());
        sendTime(op, seq);
        break;
    }

    case OP_HALL_LOG: {
        if (pn < 6) { sendRsp(op, seq, ST_BAD_ARG); break; }
        uint32_t from; uint16_t mx;
        memcpy(&from, pl, 4); memcpy(&mx, pl + 4, 2);
        size_t cap = (STAGE_CAP - sizeof(PovHallPage)) / 4;
        if (mx == 0 || mx > cap) mx = (uint16_t)(cap > 0xFFFF ? 0xFFFF : cap);
        // Страница начинается с контрольной точки НЕ ПОЗЖЕ from — до 63 записей
        // назад. Страница короче двух интервалов точек могла бы так и не дойти до
        // from, и телефон спрашивал бы одно и то же место вечно.
        if (mx < 2 * HLOG_CP_EVERY) mx = 2 * HLOG_CP_EVERY;
        PovHallPage pg;
        memset(&pg, 0, sizeof(pg));
        uint32_t* ev = (uint32_t*)(stage + sizeof(PovHallPage));
        pg.n = (uint32_t)hallLogRead(from, ev, mx, &pg.seq0, &pg.t0, &pg.oldest, &pg.head);
        pg.boot_id = hallLogBootId();
        hallGetCal(pg.cal_x100, &pg.arm_reverse);
        pg.dir = rotation_dir;
        memcpy(stage, &pg, sizeof(pg));
        stage_len = sizeof(pg) + pg.n * 4;
        stageRsp(op, seq);
        break;
    }

    case OP_HALL_HIST: {
        if (pn < 7) { sendRsp(op, seq, ST_BAD_ARG); break; }
        // Чтение флеша гасит кеш команд на обоих ядрах — пока лента светится,
        // это рваный кадр на ободе. Телефон спросит снова, когда колесо встанет.
        if (power_state == PWR_FULL) { sendRsp(op, seq, ST_BUSY); break; }
        uint8_t which = pl[0];
        uint32_t off; uint16_t mx;
        memcpy(&off, pl + 1, 4); memcpy(&mx, pl + 5, 2);
        size_t cap = STAGE_CAP - sizeof(PovHallHist);
        if (mx == 0 || mx > cap) mx = (uint16_t)(cap > 0xFFFF ? 0xFFFF : cap);
        PovHallHist hh;
        memset(&hh, 0, sizeof(hh));
        hh.off = off;
        hh.n = (uint32_t)hallLogHistRead(which, off, stage + sizeof(hh), mx,
                                         &hh.size, &hh.key_boot, &hh.key_seq);
        memcpy(stage, &hh, sizeof(hh));
        stage_len = sizeof(hh) + hh.n;
        stageRsp(op, seq);
        break;
    }

    case OP_REBOOT:
        sendRsp(op, seq, ST_OK);
        // Сама перезагрузка — в loop(): перед ней лог Холла дописывается во флеш
        // (иначе пропало бы всё накопленное с последней остановки колеса), а
        // писать флеш из задачи хоста NimBLE нельзя — см. pending_transport_off.
        pending_reboot = true;
        break;

    case OP_POWEROFF:
        sendRsp(op, seq, ST_OK);
        // Транспортный режим. Саму работу делает loop() —
        // enterTransportSleep() гасит ленту, сбрасывает настройки и калибровку
        // во флеш и уходит в сон, из которого будит только удержание кнопки.
        // Здесь нельзя: стирание флеша заморозит задачу хоста NimBLE и оборвёт
        // связь по супервизии соединения.
        webLog("[BLE] Power off requested (transport mode)");
        pending_transport_off = true;
        break;

    case OP_SLEEP:
        sendRsp(op, seq, ST_OK);
        // Сон случится штатным путём из loop(), со всеми его сбросами настроек
        // в NVS. Состарить надо ОБА таймера: порог простоя смотрит и на
        // последнее движение, а колесо, только что снятое с велосипеда, имеет
        // его свежим — по одному таймеру команд устройство бы не уснуло.
        povRequestSleep();
        break;

    default:
        sendRsp(op, seq, ST_BAD_OP);
        break;
    }
}

class CmdCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* c) override {
        NimBLEAttValue v = c->getValue();
        handleCmd(v.data(), v.length());
    }
};

// ---------------------------------------------------------------------
//  Соединение
// ---------------------------------------------------------------------
class SrvCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer* s, ble_gap_conn_desc* desc) override {
        conn_id   = desc->conn_handle;
        connected = true;
        peer_mtu  = 23;
        last_web_activity_time = millis();

        // Всё, что можно попросить у канала ради скорости.
        // Интервал 7.5–15 мс: чаще нельзя по спецификации, реже — теряем
        // пропускную способность впустую.
        // updateConnParams отсюда убран намеренно. Параметрами соединения
        // распоряжается центральный, и телефон уже просит
        // CONNECTION_PRIORITY_HIGH (те же 7.5–15 мс) со своей стороны. А три
        // процедуры канального уровня подряд контроллер не берёт: вторая и
        // третья возвращают «busy», и терялись как раз те, что дают скорость, —
        // 2M PHY и длинный пакет.
        // Data Length Extension: 251 байт на пакет канального уровня вместо 27.
        s->setDataLen(desc->conn_handle, 251);
        // LE 2M PHY — вдвое быстрее радио. Телефон может не согласиться, тогда
        // останется 1M: это запрос, а не требование.
        ble_gap_set_prefered_le_phy(desc->conn_handle,
                                    BLE_GAP_LE_PHY_2M_MASK, BLE_GAP_LE_PHY_2M_MASK, 0);
        webLog("[BLE] Client connected");
    }
    void onDisconnect(NimBLEServer* s, ble_gap_conn_desc* desc) override {
        connected = false;
        conn_id   = 0xFFFF;
        peer_mtu  = 23;
        // Оборванная заливка — файл неполон, удаляем: битый кадр на ободе от
        // целого не отличить, и «загрузилось» было бы ложью.
        if (xfer_mode != XFER_NONE) {
            bool was_ota = (xfer_mode == XFER_OTA);
            xferCleanup(true);
            if (was_ota) otaShutdownUndo();
            webLog("[BLE] Client lost mid-upload, file removed");
        }
        webLog("[BLE] Client disconnected");
        NimBLEDevice::startAdvertising();
    }
    void onMTUChange(uint16_t mtu, ble_gap_conn_desc* desc) override {
        peer_mtu = mtu;
        webLogf("[BLE] MTU %u", (unsigned)mtu);
    }
};

bool bleConnected() { return connected; }

// ---------------------------------------------------------------------
//  Инициализация
// ---------------------------------------------------------------------
void bleReserve() {
    if (stage && ring) return;
    if (!stage) stage = (uint8_t*)ps_malloc(STAGE_CAP);
    if (!ring)  ring  = (uint8_t*)ps_malloc(RING_CAP);
}

bool bleSetup() {
    bleReserve();
    if (!stage || !ring) {
        webLog("[BLE] PSRAM alloc failed, BLE unavailable");
        return false;
    }

    // Имя пользователя, если оно задано, иначе заводское из MAC.
    ble_name = prefs.getString("ble_name", "");
    if (!bleNameOk(ble_name)) ble_name = bleDefaultName();
    const char* nm = ble_name.c_str();

    NimBLEDevice::init(nm);
    NimBLEDevice::setMTU(517);
    NimBLEDevice::setPower(ESP_PWR_LVL_P9);
    // 2M PHY по умолчанию для будущих соединений — на случай, если телефон
    // сам инициирует обновление PHY раньше нашего запроса.
    ble_gap_set_prefered_default_le_phy(BLE_GAP_LE_PHY_2M_MASK, BLE_GAP_LE_PHY_2M_MASK);

    srv = NimBLEDevice::createServer();
    srv->setCallbacks(new SrvCallbacks());
    srv->advertiseOnDisconnect(true);

    NimBLEService* svc = srv->createService(POV_SVC_UUID);
    // 512, а не 600: NimBLEAttValue зажимает max_len до BLE_ATT_ATTR_MAX_LEN,
    // так что 600 — это молчаливые 512, по которым потом и режется входящая
    // запись. Пишем настоящее число, чтобы оно совпадало с attPayload().
    chCmd  = svc->createCharacteristic(POV_CMD_UUID,  NIMBLE_PROPERTY::WRITE,    BLE_ATT_ATTR_MAX_LEN);
    chRsp  = svc->createCharacteristic(POV_RSP_UUID,  NIMBLE_PROPERTY::NOTIFY,   BLE_ATT_ATTR_MAX_LEN);
    chData = svc->createCharacteristic(POV_DATA_UUID, NIMBLE_PROPERTY::WRITE_NR, BLE_ATT_ATTR_MAX_LEN);
    chFlow = svc->createCharacteristic(POV_FLOW_UUID, NIMBLE_PROPERTY::NOTIFY, 32);
    chTele = svc->createCharacteristic(POV_TELE_UUID,
                                       NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY,
                                       sizeof(PovTele));
    chCmd->setCallbacks(new CmdCallbacks());
    chData->setCallbacks(new DataCallbacks());
    svc->start();

    NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
    adv->addServiceUUID(POV_SVC_UUID);
    adv->setScanResponse(true);
    // Интервал адвертайзинга 100–150 мс: телефон находит колесо почти сразу,
    // а средний ток при этом всё равно единицы миллиампер.
    adv->setMinInterval(160);
    adv->setMaxInterval(240);
    NimBLEDevice::startAdvertising();

    xTaskCreatePinnedToCore(bleWriterTask, "bleWriter", 4096, nullptr, 1, nullptr, 0);
    webLogf("[BLE] Advertising as %s", nm);
    return true;
}

// ---------------------------------------------------------------------
//  Телеметрия раз в 500 мс
// ---------------------------------------------------------------------
void bleLoop() {
    // Отложенная запись имени: только когда лента заведомо не светится.
    // Условие то же, что у отложенного сброса настроек в loop().
    if (ble_name_dirty && power_state != PWR_FULL) {
        ble_name_dirty = false;
        prefs.putString("ble_name", ble_name_pending);
        webLogf("[BLE] Name saved: %s", ble_name_pending.c_str());
    }

    if (!chTele || !connected) return;
    static uint32_t last = 0;
    uint32_t now = millis();
    if (now - last < 500) return;
    last = now;
    // Во время заливки радиовремя дороже телеметрии — она подождёт.
    if (xfer_mode != XFER_NONE) return;
    if (chTele->getSubscribedCount() == 0) return;
    PovTele t; fillTele(&t);
    // Значение атрибута тоже обновляем: notify() его не трогает, и клиент,
    // который читает характеристику вместо подписки, получил бы пустоту.
    chTele->setValue((uint8_t*)&t, sizeof(t));
    chTele->notify((uint8_t*)&t, sizeof(t));
}
