<#
.SYNOPSIS
    Понижает частоту кадров видео с POV-дисплея, склеивая "лишние" кадры в один — без пересвета.
    Может подстраивать число склеиваемых кадров автоматически под реальную скорость вращения
    колеса, определяя прорисовки по звуковым тикам синхро-датчика на аудиодорожке (-AudioSync).

.DESCRIPTION
    Обычный режим: делит исходное видео на группы по N последовательных кадров
    (N = fps_входа / fps_выхода) и сводит каждую группу в один кадр наложением в режиме
    "lighten" (поканальный максимум, как при съёмке световых следов длинной выдержкой):
    тёмный фон не суммируется, а место, засветившееся хотя бы в одном кадре группы,
    остаётся засвеченным без прибавления яркости. За счёт этого на итоговом кадре видны
    все сектора POV-дисплея, а не только те, что попали в один-единственный "выживший"
    кадр при обычном прореживании.

    Режим -AudioSync: колесо крутится не с постоянной скоростью, поэтому фиксированное N
    либо не хватает, чтобы дисплей прорисовался целиком (остаются разрывы), либо склеивает
    больше одной прорисовки (наложение). Вместо фиксированного N скрипт берёт границы
    прорисовок со звуковой дорожки: прошивка пищит пьезо (18 кГц, 5 мс) на каждом
    срабатывании датчика Холла, пока лента светится, — то есть когда каждый из шести лучей
    проходит мимо магнита, 6 раз за оборот (-BeepsPerRev).

    Лучей шесть, и каждый красит свой сектор в 60 градусов одновременно с остальными, так
    что полный круг картинки готов ровно за 1/6 оборота — ровно за интервал между двумя
    соседними тиками. Поэтому каждый такой интервал склеивается в свой кадр. Склеивать
    больше нельзя: за целый оборот каждый луч проходит весь круг, и в кадр легли бы шесть
    прорисовок подряд — шесть секторов, наложенных друг на друга (на анимации это сразу
    видно). Длиннее интервал (медленное вращение) — больше кадров в склейке, короче
    (быстрое) — меньше. Результат — видео той же родной частоты кадров, что и исходник, где
    каждая прорисовка представлена своей засветкой, повторённой на столько кадров, сколько
    она реально заняла по времени.

    Тики на записи тихие (10-20 дБ над фоном), их громкость гуляет в пределах оборота
    (пьезо стоит на вращающейся плате) и после каждого комната звенит отражениями, поэтому
    детектор не пороговый: согласованный фильтр на тон, локальный фон, проверка
    тональности, период по автокорреляции и сетка тиков по фазе, которая достраивает
    пропущенные тики и не замечает случайных (подробно — в комментарии перед $PovSource).

    Пока тиков нет (колесо ещё не рисует, пауза, уже остановилось), кадры идут как есть, в
    исходной частоте — снижать её там незачем, на видео обычное вращение. И ни один
    склеенный кадр не держится дольше 1/-MinFps (10 к/с): более долгая прорисовка делится на
    части. Весь обрабатываемый отрезок попадает в результат, звук тоже.

.PARAMETER InputFile
    Путь к исходному видео (например, запись POV-дисплея на 200 к/с).

.PARAMETER TargetFps
    Желаемая частота кадров на выходе, можно дробную (например 2.5). Если не указана —
    скрипт спросит её интерактивно. Реальная частота может немного отличаться от запрошенной,
    т.к. размер группы N округляется до целого — итог выводится в консоль.
    Не используется в режиме -AudioSync (там частота определяется оборотами).

.PARAMETER OutputFile
    Путь к результату. Если не задан — рядом с исходным файлом, с суффиксом _blendN_XXfps
    (или _sync_NNsweeps в режиме -AudioSync, NN — число прорисовок).

.PARAMETER Mode
    Режим наложения кадров внутри группы (ffmpeg blend mode). По умолчанию "lighten"
    (поканальный максимум — то, что нужно для склейки без пересвета). Можно попробовать,
    например, "screen" (немного ярче, но уже реально пересвечивает при перекрытии) или
    "average" (среднее — соседние кадры гаснут, а не остаются на полной яркости).
    В режиме -AudioSync — только эти три (склеивает сам скрипт, а не tblend).

.PARAMETER BlackLevel
    Порог отсечки шума (0-255) перед склейкой. У реальной камеры "чёрный" фон никогда не
    бывает идеальным (0,0,0) — там всегда есть шум матрицы/сжатия, и он у каждого канала
    (R,G,B) свой в каждом кадре. Честный максимум по каналам за много кадров эту шумовую
    "рябь" накапливает: у фона всплывает то один канал, то другой — фон одновременно
    светлеет и приобретает случайный цветной оттенок, что и выглядит как пересвет с
    искажением цвета, тем сильнее, чем больше кадров склеено. Всё, что не превышает порог,
    обнуляется ДО склейки (в самой картинке на дисплее — это яркие светодиоды на тёмном
    фоне, порог их не тронет), поэтому шуму просто нечего накапливать. По умолчанию 16;
    поставьте 0, чтобы вернуть поведение без отсечки.

.PARAMETER Crf
    Качество кодирования H.264: 0 = без потерь, меньше — лучше и тяжелее файл. По умолчанию 16.

.PARAMETER Start
    Время начала обработки в секундах — можно обрезать ролик перед склейкой. По умолчанию с начала.

.PARAMETER Duration
    Длительность обрабатываемого фрагмента в секундах. По умолчанию — до конца файла.

.PARAMETER AudioSync
    Включает режим автоматической синхронизации по звуку (см. описание выше) вместо
    ручного -TargetFps. Требует аудиодорожку с тиками синхро-датчика (по умолчанию
    18 кГц, 6 на оборот).

.PARAMETER SlowMo
    Замедленная съёмка (slow motion): во сколько раз файл медленнее реального времени.
    По умолчанию auto. Смартфон сохраняет slow motion, растягивая и видео, и звук: при
    замедлении в 4 раза кадры, снятые на 120 к/с, идут как 30 к/с, а тон тика 18 кГц
    опускается до 4.5 кГц и длится 20 мс. В режиме auto скрипт ищет тики при замедлении
    1, 4, 8 и 2 (первым — то, что подсказывают метаданные файла, если подсказывают) и
    берёт то, при котором они нашлись. Можно задать число явно, например -SlowMo 4.
    Результат всегда в реальном времени: замедление снимается и с видео, и со звука
    (звук возвращается к исходной высоте). -Start/-Duration — по шкале исходного файла.

.PARAMETER BeepFreq
    Частота тика синхро-датчика, Гц. По умолчанию 18000: пьезо прошивки (PIEZO_FREQ_HZ);
    если тиков на ней нет, скрипт пробует и 17000 (записи с прежней прошивкой). Заданная
    явно частота — единственная, без перебора.

.PARAMETER BeepMs
    Длительность тика, мс (PIEZO_BEEP_US). По умолчанию 5 — это длина окна согласованного
    фильтра: оно даёт наилучшее отношение сигнал/шум именно для тона такой длины.

.PARAMETER BeepSnrDb
    Насколько пик тона должен быть выше локального фона (медиана за ±250 мс), дБ. По
    умолчанию 8. На test17khz.mp4 тики — 10-35 дБ над фоном, шум — до ~12. Выше — меньше
    ложных кандидатов, но больше пропусков (их достраивает трекер); ниже — наоборот.

.PARAMETER BeepTonalDb
    Насколько тон должен быть громче соседних полос (±700 Гц), дБ. По умолчанию 6: тик —
    узкий тон, а щелчки механики и шум широкополосные и в соседних полосах так же громки.

.PARAMETER MinRpm
    Нижняя граница оборотов, при которых колесо рисует, об/мин. По умолчанию 90 (прошивка
    гасит ленту ниже RPM_RENDER_OFF = 100 и тогда не тикает). Интервал между тиками
    длиннее, чем прорисовка на этих оборотах, — это уже пауза, а не прорисовка.

.PARAMETER MinFps
    Нижний предел частоты кадров, к/с. По умолчанию 10: склеенный кадр не держится дольше
    1/10 с — прорисовка длиннее (обороты у порога отрисовки) делится на части.

.PARAMETER CheckerMode
    Как рассеять шесть нестыковок секторов (картинка, сдвинувшаяся за 1/6 оборота:
    анимация, прорисовка, не уложившаяся в целое число кадров). Берутся шесть окон длиной
    в прорисовку, сдвинутых друг относительно друга (-CheckerSpread), у каждого стык под
    своим углом:
      blend   — каждый пиксель — среднее всех шести окон (по умолчанию): шума нет,
                нестыковка становится мягким переходом, пропуск — лишь чуть темнее;
      ordered — клетки берут окна по упорядоченному растру: каждый пиксель — один проход
                луча, смесь читается как ровный мелкий полутон;
      random  — клетки берут окна вперемешку случайно: мелкий шум.
    Каждое окно — склейка целых кадров: ни сдвигов изображения, ни дорисовки. Где точку
    колеса проходит луч во всех шести окнах, все они дают одно и то же — там картинка
    такая же чёткая, как при обычной склейке.

.PARAMETER CheckerSpread
    Разнос шести окон, доля прорисовки (0..1). По умолчанию 1 (окна сдвинуты на 0..5/6
    прорисовки — нестыковка рассеивается по всему экрану). Меньше — рассеивание только в
    полосах у бывших стыков, остальное как при обычной склейке; 0 — обычная склейка. На
    быстром вращении, когда прорисовка короче нескольких кадров, малый разнос округляется
    до нуля.

.PARAMETER CheckerCell
    Размер клетки в пикселях для -CheckerMode ordered и random. По умолчанию 2. Мельче
    клетка — мельче узор, но файл тяжелее. 0 — обычная склейка, все пиксели одним окном.

.PARAMETER LegacyClickDetect
    Старый детектор — порог по громкости в полосе (silencedetect) вместо детектора тиков.
    Для старых записей без пьезо, где слышен только щелчок датчика Холла раз в оборот
    (см. .EXAMPLE). Только для него действуют -BeepBandwidth, -BeepThresholdDb и
    -BeepMinDurationMs.

.PARAMETER BeepBandwidth
    Только с -LegacyClickDetect. Полная ширина полосы вокруг -BeepFreq, Гц. По умолчанию
    400. Передаётся ffmpeg явно в герцах: по умолчанию bandpass понимает ширину как
    добротность, и прежние версии скрипта с 500 на 2800 Гц на деле фильтровали полосой
    около 5.6 Гц.

.PARAMETER BeepThresholdDb
    Только с -LegacyClickDetect. Порог громкости после полосового фильтра, дБ
    относительно полной шкалы. По умолчанию -50.

.PARAMETER BeepMinDurationMs
    Только с -LegacyClickDetect. Минимальная пауза (тишина в полосе) перед щелчком, мс.
    По умолчанию 8.

.PARAMETER MinRevMs
    Минимально правдоподобная длительность одного оборота, мс. Интервал между тиками
    короче MinRevMs/BeepsPerRev считается повторным срабатыванием того же тика и
    отбрасывается (точно так же настоящая прошивка отбрасывает физически невозможные
    обороты). По умолчанию 80 — потолок 750 об/мин.

.PARAMETER BeepsPerRev
    Сколько тиков приходится на один оборот. По умолчанию 6 — тик на каждый луч. 1 — для
    старых записей со щелчком датчика раз в оборот. Нужен для пересчёта в об/мин и для
    защиты от дребезга, а вместе с -Arms — чтобы понять, сколько прорисовок в интервале.

.PARAMETER Arms
    Число лучей колеса — прорисовок картинки за оборот. По умолчанию 6. Каждый интервал
    между тиками делится на Arms/BeepsPerRev равных окон, каждое склеивается в свой кадр:
    при 6/6 это одно окно на интервал, при старых записях (-BeepsPerRev 1) — шесть.

.PARAMETER DetectOnly
    Только найти тики на аудиодорожке и вывести участки отрисовки и интервалы между
    тиками — без обработки видео. Проверьте, что участки совпадают с тем, когда на видео
    горит картинка.

.PARAMETER Force
    В режиме -AudioSync (без -DetectOnly) скрипт по умолчанию сначала показывает список
    найденных интервалов и спрашивает подтверждение, прежде чем начать долгую обработку —
    ровно то же самое, для чего нужен -DetectOnly, но без отдельного запуска. -Force
    пропускает этот вопрос и запускает обработку сразу (для автоматизации/скриптов).

.EXAMPLE
    .\POV-BlendFPS.ps1 -InputFile "C:\video\pov_200fps.mp4" -TargetFps 5

.EXAMPLE
    .\POV-BlendFPS.ps1 "C:\video\pov_200fps.mp4" 1 -Start 3 -Duration 10 -Crf 12

.EXAMPLE
    .\POV-BlendFPS.ps1 -InputFile "C:\video\wheel.mp4" -AudioSync -DetectOnly

.EXAMPLE
    .\POV-BlendFPS.ps1 -InputFile "C:\video\wheel.mp4" -AudioSync -Force

.EXAMPLE
    Старая запись без пьезо — щелчок датчика Холла раз в оборот, детекция как раньше
    (проверено на 120.mp4: те же обороты до миллисекунды):
    .\POV-BlendFPS.ps1 -InputFile "C:\video\old.mp4" -AudioSync -LegacyClickDetect -BeepFreq 2800 -BeepBandwidth 5.6 -BeepMinDurationMs 10 -BeepsPerRev 1
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$InputFile,

    [Parameter(Position = 1)]
    [string]$TargetFps = "",

    [string]$OutputFile = "",
    [string]$Mode = "lighten",
    [int]$BlackLevel = 16,
    [int]$Crf = 16,
    [string]$Start = "",
    [string]$Duration = "",

    [switch]$AudioSync,
    [string]$SlowMo = "auto",
    [double]$BeepFreq = 18000,
    [double]$BeepMs = 5,
    [double]$BeepSnrDb = 8,
    [double]$BeepTonalDb = 6,
    [double]$MinRevMs = 80,
    [double]$MinRpm = 90,
    [double]$MinFps = 10,
    [int]$CheckerCell = 2,
    [double]$CheckerSpread = 1,
    [ValidateSet('random', 'ordered', 'blend')]
    [string]$CheckerMode = 'blend',
    [int]$BeepsPerRev = 6,
    [int]$Arms = 6,
    [switch]$LegacyClickDetect,
    [double]$BeepBandwidth = 400,
    [double]$BeepThresholdDb = -50,
    [double]$BeepMinDurationMs = 8,
    [switch]$DetectOnly,
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$Inv = [System.Globalization.CultureInfo]::InvariantCulture

# Все числа — только с точкой как разделителем, независимо от региональных настроек Windows.
# Иначе при русской локали 2.985 превратится при подстановке в "2,985", и ffmpeg не сможет
# разобрать аргумент -r.
[System.Threading.Thread]::CurrentThread.CurrentCulture = $Inv

function Parse-Num {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    return [double]::Parse($Value.Replace(',', '.'), $Inv)
}

function Num([double]$Value) { return $Value.ToString($Inv) }

function Resolve-FFTool {
    param([Parameter(Mandatory = $true)][string]$Name)

    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    # ffmpeg мог быть только что установлен через winget — текущее окно PowerShell
    # ещё не подхватило обновлённый PATH, поэтому ищем известное место установки напрямую.
    $candidates = New-Object System.Collections.Generic.List[string]
    $candidates.Add((Join-Path $PSScriptRoot "ffmpeg\bin\$Name.exe"))
    $candidates.Add((Join-Path $PSScriptRoot "$Name.exe"))

    $wingetPattern = Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Packages\Gyan.FFmpeg_*\ffmpeg-*-full_build\bin\$Name.exe"
    $found = Get-ChildItem -Path $wingetPattern -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) { $candidates.Add($found.FullName) }

    foreach ($c in $candidates) {
        if (Test-Path -LiteralPath $c) { return (Resolve-Path -LiteralPath $c).Path }
    }

    throw "Не найден $Name.exe. ffmpeg устанавливался через winget: закройте это окно PowerShell, откройте новое и запустите скрипт заново (winget обновляет PATH только для новых окон). Если не поможет: winget install --id Gyan.FFmpeg -e"
}

# ffprobe/ffmpeg — не cmdlet-ы, их успех или неудача определяется кодом возврата, а не
# самим фактом, что они что-то написали в stderr (баннер, прогресс, статистикаx264,
# служебные сообщения фильтров вроде silencedetect). При ErrorActionPreference=Stop
# PowerShell 5.1 иначе превращает этот вывод в останавливающее исключение
# (NativeCommandError), поэтому на время вызова эту настройку отключаем.
function Invoke-NativeCapture {
    param([string]$Path, [string[]]$ArgList)
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $rawLines = & $Path @ArgList 2>&1
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $prevEAP
    return [PSCustomObject]@{ Text = ($rawLines | Out-String); ExitCode = $exitCode }
}

function Invoke-NativePassthrough {
    param([string]$Path, [string[]]$ArgList)
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & $Path @ArgList
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $prevEAP
    return $exitCode
}

function Get-VideoFps {
    param([string]$FFprobePath, [string]$File)
    $r = Invoke-NativeCapture -Path $FFprobePath -ArgList @('-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=r_frame_rate', '-of', 'csv=p=0', '--', $File)
    $raw = $r.Text.Trim()
    if ($r.ExitCode -ne 0 -or -not $raw) { throw "Не удалось определить частоту кадров входного файла (нет видеодорожки?): $File" }
    $parts = $raw.Split('/')
    if ($parts.Length -eq 2 -and [double]$parts[1] -ne 0) {
        return [double]$parts[0] / [double]$parts[1]
    }
    return [double]$parts[0]
}

# Общее ядро склейки: format=gbrp (см. ниже почему не rgb24) + отсечка шума + цепочка
# tblend=lighten. Переиспользуется и обычным режимом (группы по N кадров, повторяющиеся
# по всему потоку), и -AudioSync (ровно один раз на каждый оборот, кадры уже выделены
# вызывающим кодом через select/trim).
#
# lighten (поканальный максимум) без искажений цвета работает только в RGB. В YUV каналы
# цветности (U/V) центрированы на 128, и поканальный максимум с "нейтральным" фоном (128)
# может исказить цвет там, где составляющая цветности реального пикселя ниже 128.
# В RGB чёрный фон — это (0,0,0), то есть минимум по определению, поэтому
# max(фон, светодиод) всегда честно даёт цвет светодиода, каким он был в кадре.
#
# Именно gbrp, а не rgb24 — это не стилистический выбор. Проверено на реальных числах:
# цепочка из двух и более tblend поверх УПАКОВАННОГО rgb24 (R,G,B перемешаны в одной
# плоскости) молча портит один из каналов — например, чистый (255,0,0) после склейки
# 3+ кадров превращался в (252,0,75), синий канал брался из ниоткуда. Один-единственный
# tblend на rgb24 при этом безобиден — ломается только цепочка от двух и более, что и
# объясняет жалобу "искажение при склейке от 2 кадров". На ПЛАНАРНОМ gbrp (раздельные
# плоскости R/G/B, как и положено tblend/blend, которые исторически рассчитаны на
# планарные форматы вроде YUV) та же цепочка даёт математически честный результат:
# проверено вплоть до N=200 (не только на паре кадров).
function Get-BlendCoreFilter {
    param([int]$FrameCount, [string]$BlendMode, [int]$BlackLvl)

    $parts = New-Object System.Collections.Generic.List[string]
    $parts.Add("format=gbrp")

    if ($FrameCount -gt 1) {
        if ($BlackLvl -gt 0) {
            # Обнуляем шум ДО склейки, а не после: honest max() за N кадров иначе
            # накапливает шумовую "рябь" фона (разную по R/G/B в каждом кадре) в
            # засвеченный цветной фон — см. .PARAMETER BlackLevel. Настоящие светодиоды
            # на тёмном фоне намного ярче этого порога, так что саму картинку он не тронет.
            $parts.Add("lutrgb=r='if(gt(val\,$BlackLvl)\,val\,0)':g='if(gt(val\,$BlackLvl)\,val\,0)':b='if(gt(val\,$BlackLvl)\,val\,0)'")
        }
        # tblend не пропускает самый первый кадр потока как есть — он его "съедает"
        # (проверено: 10 кадров на входе -> 9 после одного tblend), т.е. k-й кадр
        # после цепочки — это max(вход[k] .. вход[k+N-1]), окно смотрит ВПЕРЁД от k,
        # а не назад. max ассоциативен, поэтому (N-1) стадий tblend=lighten подряд
        # как раз и дают такое окно шириной N.
        $parts.Add(((@("tblend=all_mode=$BlendMode") * ($FrameCount - 1)) -join ","))
    }

    return ($parts -join ",")
}

# --- Старый детектор щелчков (-LegacyClickDetect) ---
#
# Порог по громкости в полосе: полосовой фильтр вырезает окрестность -BeepFreq,
# silencedetect находит моменты, где отфильтрованный сигнал поднимается выше
# -BeepThresholdDb, — событие "конец тишины" (silence_end) и есть начало щелчка. Годится
# для громкого щелчка датчика Холла на старых записях; для тихих тиков пьезо на
# вращающейся плате — нет (см. детектор тиков ниже).
#
# Ширина полосы — явно в герцах (t=h). По умолчанию bandpass в ffmpeg понимает ширину как
# добротность (t=q), и прежний вызов с w=500 на 2800 Гц на деле давал полосу около 6 Гц.
# Такой узкий фильтр звенит десятки миллисекунд и размазал бы 5-мс тик целиком, а полоса
# 400 Гц откликается за ~1 мс.
function Get-BandFilter {
    param([double]$Freq, [double]$Bandwidth)
    return "bandpass=f=$(Num $Freq):t=h:w=$(Num $Bandwidth)"
}

function Get-BeepTimestamps {
    param([string]$FFmpegPath, [string]$File, [double]$Freq, [double]$Bandwidth, [double]$ThresholdDb, [double]$MinSilenceMs, [string]$Pre = "")

    $af = "$Pre$(Get-BandFilter $Freq $Bandwidth),silencedetect=noise=$(Num $ThresholdDb)dB:duration=$(Num ($MinSilenceMs / 1000.0))"
    $r = Invoke-NativeCapture -Path $FFmpegPath -ArgList @('-i', $File, '-vn', '-af', $af, '-f', 'null', '-')
    if ($r.ExitCode -ne 0) { throw "ffmpeg не смог прочитать аудиодорожку (код $($r.ExitCode)). Есть ли звук в файле?" }

    # Засчитываем только тот конец тишины, за которым тишина снова началась, — то есть
    # тик, который закончился. Если файл кончается тишиной, silencedetect выдаёт ещё один
    # silence_end на самом последнем сэмпле — это конец файла, а не тик, и он давал
    # лишний короткий «интервал» в хвосте. Цена правила — тик, оборванный концом файла,
    # тоже не засчитывается; он и так не замыкает ни одной целой прорисовки.
    $events = [regex]::Matches($r.Text, 'silence_(start|end):\s*([0-9.]+)')
    $timestamps = New-Object System.Collections.Generic.List[double]
    $pendingEnd = $null
    foreach ($ev in $events) {
        $t = [double]::Parse($ev.Groups[2].Value, $Inv)
        if ($ev.Groups[1].Value -eq 'end') {
            $pendingEnd = $t
        } elseif ($null -ne $pendingEnd) {
            $timestamps.Add($pendingEnd)
            $pendingEnd = $null
        }
    }
    return ($timestamps | Sort-Object)
}

# Пик и средний уровень в полосе детектора, дБ. Без этих двух чисел порог приходится
# подбирать вслепую: его ставят между ними, ближе к пику. Тик занимает около десятой
# доли времени, так что среднее лежит заметно ниже громкости самого тика.
function Get-BandLevels {
    param([string]$FFmpegPath, [string]$File, [double]$Freq, [double]$Bandwidth, [string]$Pre = "")

    $af = "$Pre$(Get-BandFilter $Freq $Bandwidth),volumedetect"
    $r = Invoke-NativeCapture -Path $FFmpegPath -ArgList @('-i', $File, '-vn', '-af', $af, '-f', 'null', '-')
    $mx = [regex]::Match($r.Text, 'max_volume:\s*(\S+) dB')
    $mn = [regex]::Match($r.Text, 'mean_volume:\s*(\S+) dB')
    return [PSCustomObject]@{
        Max  = if ($mx.Success) { $mx.Groups[1].Value } else { "?" }
        Mean = if ($mn.Success) { $mn.Groups[1].Value } else { "?" }
    }
}

function Get-Median {
    param([double[]]$Values)
    if ($Values.Count -eq 0) { return 0.0 }
    $s = $Values | Sort-Object
    $m = [int][Math]::Floor($s.Count / 2)
    if ($s.Count % 2 -eq 1) { return [double]$s[$m] }
    return ([double]$s[$m - 1] + [double]$s[$m]) / 2.0
}

# --- Детектор тиков синхро-датчика и рендер (для -AudioSync) ---
#
# Прошивка пищит пьезо (PIEZO_FREQ_HZ = 18 кГц, прежде 17, 5 мс) на каждом принятом событии датчика
# Холла, пока лента светится, — то есть когда очередной луч проходит мимо магнита,
# -BeepsPerRev раз за оборот. На реальной записи (test17khz.mp4) тик — это всего 10-20 дБ
# над фоном, его громкость гуляет в пределах оборота (пьезо стоит на вращающейся плате и
# то смотрит в микрофон, то отвёрнуто — часть тиков почти не слышна), а после каждого
# тика комната ещё 20-40 мс звенит отражениями. Порог по громкости в полосе (silencedetect)
# на такой записи находил 100 «тиков» из ~460, вперемешку с отражениями. Поэтому детектор
# свой, в четыре шага (код на C# ниже, PovTicks):
#   1. Согласованный фильтр: демодуляция на -BeepFreq (и ±50 Гц — тон стоит не ровно на
#      номинале, а пьезо вращается) с окном ровно в длину тика (-BeepMs) — наилучшее
#      отношение сигнал/шум для 5-мс тона.
#   2. Кандидат — локальный пик огибающей выше ЛОКАЛЬНОГО фона (25-й процентиль за
#      ±250 мс) на -BeepSnrDb и громче соседних полос (±700 Гц) на -BeepTonalDb: тик —
#      узкий тон, а щелчки механики широкополосные и в соседних полосах так же громки.
#   3. Период — по автокорреляции огибающей: её дают все тики вместе, и тихие тоже,
#      поэтому пропуски и лишние кандидаты на неё почти не влияют, а удвоенного периода
#      она не выбирает. В пределах ±0.55 периода остаётся сильнейший кандидат — так уходят
#      хвосты отражений.
#   4. Сетка по фазе: φ(t) = ∫dt/T — накопленное число периодов; каждый кандидат голосует
#      своей дробной фазой за сдвиг сетки. Где голоса согласны (круговое среднее ≥ 0.5 в
#      окне ±0.25 с) — идёт отрисовка, где случайны — шум. Тики — где фаза со сдвигом
#      проходит целое число; края участка — где 5 из 6 тиков подтверждены кандидатами.
#      Сетка гладкая по построению: окна склейки получаются ровно по 60° поворота, хотя
#      датчики стоят не идеально через 60°.
#   Трекер «от кандидата к кандидату», как и пороговый детектор, на этой записи рвался на
#   быстром вращении (тики через 23 мс, половина тихие) и цеплялся за двойной период.
#   Сетка по фазе устойчива во всей полосе 16 950 - 17 100 Гц: на test17khz.mp4 одна
#   отрисовка 3.248-18.39 с, на записях без тиков (200.MP4, 120.mp4, 100 100.MP4) — ни одной.
#
# Рендер — тоже свой (PovRender): ffmpeg декодирует ролик ОДИН раз в поток кадров
# постоянной частоты, C# склеивает окна и пропускает кадры вне отрисовки как есть,
# второй ffmpeg кодирует и добавляет звук. Прежняя схема — два процесса ffmpeg на каждую
# склейку — при прорисовке на каждый тик давала сотни запусков с декодированием HEVC от
# ключевого кадра на каждый.
$PovSource = @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;

public static class PovTicks {
    public static double[] Envelope(float[] x, int sr, double f, int win, int hop) {
        int n = x.Length;
        int m = n / hop;
        double[] env = new double[m];
        double w = 2.0 * Math.PI * f / sr;
        double cr = 1, ci = 0, dr = Math.Cos(w), di = -Math.Sin(w);
        double[] re = new double[win], im = new double[win];
        double sr_ = 0, si = 0;
        int half = win / 2;
        for (int i = 0; i < n; i++) {
            double vr = x[i] * cr, vi = x[i] * ci;
            int k = i % win;
            sr_ += vr - re[k]; si += vi - im[k];
            re[k] = vr; im[k] = vi;
            double t = cr * dr - ci * di; ci = cr * di + ci * dr; cr = t;
            if ((i & 1023) == 0) { double nn = Math.Sqrt(cr * cr + ci * ci); cr /= nn; ci /= nn; }
            int c = i - half;
            if (c >= 0 && c % hop == 0) { int j = c / hop; if (j < m) env[j] = 2.0 * Math.Sqrt(sr_ * sr_ + si * si) / win; }
        }
        return env;
    }

    static double Db(double v) { return 20.0 * Math.Log10(Math.Max(v, 1e-12)); }

    // Фон — 25-й процентиль огибающей за ±250 мс, а не медиана: на быстром вращении
    // тики идут через 23 мс, отражения каждого звенят 20-40 мс, и огибающая почти не
    // опускается до настоящего шума — медиана мерила хвосты самих тиков и занижала их
    // превышение над фоном. Нижний процентиль берёт провалы между ними.
    const double FloorPct = 0.25;
    // Самый длинный провал согласия, который сращивается в одну отрисовку, с.
    const double BridgeSec = 2.0;
    // Огибающая и период последнего Detect — для StartOf и Grid.
    static double[] A_e0, A_per;
    static double A_pStep, A_hs;
    static int A_win, A_sr;

    // Начало тика по пику огибающей i. Огибающая тона после окна его же длины —
    // треугольник: подъём от половины до вершины занимает половину длины тона, и
    // полувысота на подъёме — это начало тона. Ищем ближайшее пересечение половины
    // назад от вершины, но не дальше половины тона + 1.5 мс: при частых тиках (через
    // 23 мс на 400 об/мин) раньше этого лежит звенящий хвост предыдущего тика, и
    // «самое раннее пересечение за 10 мс», как было, прилипало к нему — ошибка до
    // полупериода, и сетка на быстром вращении разваливалась. Не нашли — вершина
    // минус половина тона.
    static double StartOf(int i) {
        double[] e0 = A_e0;
        int back = Math.Max(1, (int)Math.Round((A_win / 2.0 / A_sr + 0.0015) / A_hs));
        double halfAmp = e0[i] * 0.5;
        for (int k = i; k > Math.Max(0, i - back); k--) {
            if (e0[k - 1] < halfAmp) {
                double prev = e0[k - 1];
                double frac = (e0[k] - prev) > 0 ? (halfAmp - prev) / (e0[k] - prev) : 0;
                if (frac < 0) frac = 0; if (frac > 1) frac = 1;
                return (k - 1 + frac) * A_hs;
            }
        }
        return i * A_hs - A_win / 2.0 / A_sr;
    }

    // Тройки [t, snr, T]: t — начало тика, с от первого сэмпла; snr — пик над
    // локальным фоном, дБ; T — локальный период по автокорреляции, с (0 — не виден).
    public static double[] Detect(float[] x, int sr, double f0, double sideHz, double tickMs,
                                  double snrDb, double tonalDb, double minPeriod, double maxPeriod) {
        int hop = Math.Max(1, sr / 4000);
        double hs = (double)hop / sr;
        int win = Math.Max(8, (int)Math.Round(sr * tickMs / 1000.0));
        // Три канала через 50 Гц, берём наибольший: тон на записи стоит не ровно на
        // PIEZO_FREQ_HZ (делитель LEDC дробный, пьезо вращается — доплер), а расстройка
        // на 50-75 Гц уже стоит согласованному фильтру 1-2 дБ — на тихом участке этого
        // хватало, чтобы трек рвался. Главный лепесток окна 5 мс — ±200 Гц, так что три
        // канала покрывают ±125 Гц почти без потерь.
        double[] e0 = Envelope(x, sr, f0, win, hop);
        double[] eA = Envelope(x, sr, f0 - 50, win, hop);
        double[] eB = Envelope(x, sr, f0 + 50, win, hop);
        for (int i = 0; i < e0.Length; i++) e0[i] = Math.Max(e0[i], Math.Max(eA[i], eB[i]));
        eA = null; eB = null;
        double[] eL = Envelope(x, sr, f0 - sideHz, win, hop);
        double[] eH = Envelope(x, sr, f0 + sideHz, win, hop);
        int m = e0.Length;
        if (m < 16) return new double[0];
        double[] d0 = new double[m], ds = new double[m];
        for (int i = 0; i < m; i++) { d0[i] = Db(e0[i]); ds[i] = Db(Math.Max(eL[i], eH[i])); }

        int step = Math.Max(1, (int)Math.Round(0.010 / hs));
        int halfW = Math.Max(1, (int)(0.25 / hs));
        int sub = Math.Max(1, (int)Math.Round(0.002 / hs));
        int g = m / step + 1;
        double[] floorC = new double[g];
        List<double> tmp = new List<double>();
        for (int q = 0; q < g; q++) {
            int c = q * step, a = Math.Max(0, c - halfW), b = Math.Min(m - 1, c + halfW);
            tmp.Clear();
            for (int j = a; j <= b; j += sub) tmp.Add(d0[j]);
            tmp.Sort();
            floorC[q] = tmp.Count > 0 ? tmp[(int)(tmp.Count * FloorPct)] : -240;
        }

        A_e0 = e0; A_win = win; A_sr = sr; A_hs = hs;

        int per1 = Math.Max(1, (int)Math.Round(0.001 / hs));
        int m1 = m / per1;
        double[] ex = new double[m1];
        for (int k = 0; k < m1; k++) {
            double mx = 0;
            for (int j = k * per1; j < (k + 1) * per1; j++) {
                double v = d0[j] - floorC[Math.Min(g - 1, j / step)] - 3.0;
                if (v > mx) mx = v;
            }
            ex[k] = mx;
        }

        // Период ищем с запасом на 25 % сверх прорисовки на -MinRpm: прошивка гасит
        // ленту не мгновенно (выдержка 400 мс), и последние тики перед остановкой
        // приходят чуть реже; без запаса конец отрисовки обрезался.
        int minLag = Math.Max(2, (int)Math.Floor(minPeriod * 1000));
        int maxLag = Math.Max(minLag + 2, (int)Math.Ceiling(maxPeriod * 1250));
        int pStep = 100, pHalf = 300;
        int pg = m1 / pStep + 1;
        double[] perC = new double[pg];
        double[] R = new double[maxLag + 2];
        for (int q = 0; q < pg; q++) {
            int c = q * pStep, a = Math.Max(0, c - pHalf), b = Math.Min(m1 - 1, c + pHalf);
            double e2 = 0;
            for (int i = a; i <= b; i++) e2 += ex[i] * ex[i];
            perC[q] = 0;
            if (e2 < 1e-9 || b - a < 2 * maxLag) continue;
            double rmax = 0;
            for (int L = minLag - 1; L <= maxLag + 1; L++) {
                double s = 0;
                for (int i = a; i + L <= b; i++) s += ex[i] * ex[i + L];
                R[L] = s / e2;
                if (L >= minLag && L <= maxLag && R[L] > rmax) rmax = R[L];
            }
            for (int L = minLag; L <= maxLag; L++) {
                if (R[L] >= 0.65 * rmax && R[L] >= R[L - 1] && R[L] >= R[L + 1]) {
                    // Парабола по трём точкам — период точнее миллисекундного шага
                    // лага: по нему дальше накапливается фаза сетки тиков.
                    double den = R[L - 1] - 2 * R[L] + R[L + 1];
                    double dl = den < 0 ? 0.5 * (R[L - 1] - R[L + 1]) / den : 0;
                    if (dl < -0.5) dl = -0.5; if (dl > 0.5) dl = 0.5;
                    perC[q] = (L + dl) / 1000.0;
                    break;
                }
            }
        }
        A_per = perC; A_pStep = pStep / 1000.0;

        int r = Math.Max(1, (int)(minPeriod / 2 / hs));
        List<double> ct = new List<double>(), cs = new List<double>(), cp = new List<double>();
        for (int i = 1; i < m - 1; i++) {
            double v = d0[i];
            double fl = floorC[Math.Min(g - 1, i / step)];
            if (v - fl < snrDb) continue;
            if (v - ds[i] < tonalDb) continue;
            bool isMax = true;
            int lo = Math.Max(0, i - r), hi = Math.Min(m - 1, i + r);
            for (int j = lo; j <= hi; j++) { if (d0[j] > v || (d0[j] == v && j < i)) { isMax = false; break; } }
            if (!isMax) continue;
            ct.Add(StartOf(i)); cs.Add(v - fl);
            cp.Add(perC[Math.Min(pg - 1, (int)Math.Round(i * hs * 1000 / pStep))]);
        }

        int n = ct.Count;
        int[] ord = new int[n];
        for (int i = 0; i < n; i++) ord[i] = i;
        double[] csa = cs.ToArray();
        Array.Sort(ord, (a, b) => csa[b].CompareTo(csa[a]));
        List<double> acc = new List<double>();
        bool[] keep = new bool[n];
        foreach (int i in ord) {
            double T = cp[i] > 0 ? cp[i] : maxPeriod;
            double rad = 0.55 * T;
            int pos = acc.BinarySearch(ct[i]);
            if (pos < 0) pos = ~pos;
            bool ok = true;
            if (pos < acc.Count && acc[pos] - ct[i] < rad) ok = false;
            if (pos > 0 && ct[i] - acc[pos - 1] < rad) ok = false;
            if (ok) { acc.Insert(pos, ct[i]); keep[i] = true; }
        }
        List<double> kt = new List<double>(), ks = new List<double>(), kp = new List<double>();
        for (int i = 0; i < n; i++) if (keep[i]) { kt.Add(ct[i]); ks.Add(cs[i]); kp.Add(cp[i]); }

        bool changed = true;
        while (changed) {
            changed = false;
            for (int j = 1; j < kt.Count; j++) {
                double T = 0.5 * (kp[j] + kp[j - 1]);
                if (T <= 0) continue;
                if (kt[j] - kt[j - 1] >= 0.8 * T) continue;
                double eDropJ = 9, eDropPrev = 9;
                if (j + 1 < kt.Count) { double q = (kt[j + 1] - kt[j - 1]) / T; eDropJ = Math.Abs(q - Math.Round(q)); if (Math.Round(q) < 1) eDropJ = 9; }
                if (j - 2 >= 0) { double q = (kt[j] - kt[j - 2]) / T; eDropPrev = Math.Abs(q - Math.Round(q)); if (Math.Round(q) < 1) eDropPrev = 9; }
                if (Math.Min(eDropJ, eDropPrev) > 0.25) continue;
                int del = eDropJ <= eDropPrev ? j : j - 1;
                kt.RemoveAt(del); ks.RemoveAt(del); kp.RemoveAt(del);
                changed = true;
                break;
            }
        }

        List<double> outL = new List<double>();
        for (int i = 0; i < kt.Count; i++) { outL.Add(kt[i]); outL.Add(ks[i]); outL.Add(kp[i]); }
        return outL.ToArray();
    }

    static double Median(List<double> v) {
        if (v.Count == 0) return 0;
        v.Sort();
        return v[v.Count / 2];
    }

    // Сетка тиков по фазе. Период T(t) — из автокорреляции (последний Detect): она
    // держит его по всем тикам сразу, и тихим тоже, так что пропуски и лишние
    // кандидаты на него почти не влияют, а двойного периода она не выбирает. Фаза
    // φ(t) = ∫dt/T — накопленное число периодов. Каждый кандидат голосует за сдвиг
    // сетки своей дробной фазой; круговое среднее голосов в окне ±win даёт сдвиг θ(t),
    // а длина среднего вектора R — насколько голоса согласны. У настоящих тиков фазы
    // кучные (R ~ 0.7-0.9), у шума — случайные (R ~ 1/√n), так что R ≥ minR и есть
    // признак отрисовки. Тики — где φ(t) − θ(t) проходит целое число.
    // Возвращает пары [t, есть кандидат рядом 1 / достроен 0], треки через [-1, -1],
    // и в конце — диагностику R на сетке 50 мс: [-2, n, R0, R1, ...].
    public static double[] Grid(double[] trip, double minPeriod, double maxPeriod,
                                double winSec, double minR, int minVotes, double minTrackSec, int minTicks) {
        List<double> res = new List<double>();
        if (A_per == null || A_per.Length < 2) return res.ToArray();
        int nc = trip.Length / 3;
        double[] ct = new double[nc], cw = new double[nc];
        for (int i = 0; i < nc; i++) { ct[i] = trip[3 * i]; cw[i] = Math.Min(20.0, Math.Max(1.0, trip[3 * i + 1] - 4.0)); }

        // Период на сетке A_pStep: медиана по пяти соседним (одиночный выброс
        // автокорреляции — удвоенный период — не должен сбить фазу), нули — нет периода.
        int pg = A_per.Length;
        double[] per = new double[pg];
        List<double> tmp = new List<double>();
        for (int q = 0; q < pg; q++) {
            tmp.Clear();
            for (int j = Math.Max(0, q - 2); j <= Math.Min(pg - 1, q + 2); j++) if (A_per[j] > 0) tmp.Add(A_per[j]);
            per[q] = tmp.Count >= 3 ? Median(tmp) : 0;
        }

        double dt = 0.001;
        double tEnd = pg * A_pStep;
        int nf = (int)(tEnd / dt) + 1;
        double[] phi = new double[nf];
        bool[] valid = new bool[nf];
        Func<double, double> Phi = (double t) => {
            double kf = t / dt;
            int k0 = (int)Math.Floor(kf);
            if (k0 < 0) return phi[0];
            if (k0 >= nf - 1) return phi[nf - 1];
            return phi[k0] + (phi[k0 + 1] - phi[k0]) * (kf - k0);
        };
        double[] vc = new double[nc], vs = new double[nc];
        double gStep = 0.05;
        int ng = (int)(tEnd / gStep) + 1;
        double[] th = new double[ng], Rg = new double[ng];
        int[] cnt = new int[ng];
        bool[] good = new bool[ng], on = new bool[ng];
        Func<int, double> PerAt = (int q) => per[Math.Min(pg - 1, (int)Math.Round(q * gStep / A_pStep))];
        int holeMax = (int)Math.Round(BridgeSec / gStep);

        // Два прохода: во втором — с периодом, исправленным в провалах (см. ниже).
        for (int pass = 0; pass < 2; pass++) {
            // Фаза на сетке 1 мс.
            for (int k = 1; k < nf; k++) {
                double t = k * dt;
                double qf = t / A_pStep;
                int q0 = Math.Min(pg - 1, (int)Math.Floor(qf)), q1 = Math.Min(pg - 1, q0 + 1);
                double fr = qf - q0;
                double T;
                if (per[q0] > 0 && per[q1] > 0) T = per[q0] + (per[q1] - per[q0]) * fr;
                else T = per[q0] > 0 ? per[q0] : per[q1];
                valid[k] = T > 0;
                if (T <= 0) T = maxPeriod;
                phi[k] = phi[k - 1] + dt / T;
            }

            // Голоса кандидатов: дробная фаза как угол.
            for (int i = 0; i < nc; i++) {
                double a = 2 * Math.PI * Phi(ct[i]);
                vc[i] = cw[i] * Math.Cos(a); vs[i] = cw[i] * Math.Sin(a);
            }
            // Круговое среднее на сетке 50 мс. Окно — не меньше 3.5 периодов, чтобы и на
            // медленном вращении (100 мс между тиками) в него попадало 7 голосов.
            for (int q = 0; q < ng; q++) {
                double tc = q * gStep;
                int pq = Math.Min(pg - 1, (int)Math.Round(tc / A_pStep));
                double w = Math.Max(winSec, 3.5 * per[pq]);
                int lo = Array.BinarySearch(ct, tc - w); if (lo < 0) lo = ~lo;
                int hi = Array.BinarySearch(ct, tc + w); if (hi < 0) hi = ~hi; else hi++;
                double sc = 0, ss = 0, sw = 0;
                for (int i = lo; i < hi; i++) { sc += vc[i]; ss += vs[i]; sw += cw[i]; }
                cnt[q] = hi - lo;
                Rg[q] = sw > 0 ? Math.Sqrt(sc * sc + ss * ss) / sw : 0;
                th[q] = Math.Atan2(ss, sc);
            }

            // Маска отрисовки на сетке: согласие голосов, их число и наличие периода.
            for (int q = 0; q < ng; q++) {
                int k = Math.Min(nf - 1, (int)Math.Round(q * gStep / dt));
                good[q] = Rg[q] >= minR && cnt[q] >= minVotes && valid[k];
                on[q] = good[q];
            }
            if (pass == 1) break;

            // Провал согласия, где тики утонули в шуме (телефон далеко, возня с ним в руках):
            // автокорреляция там случайная, и период внутри провала скачет, хотя колесо
            // крутится как крутилось. Если обороты на краях провала совпадают (±15 %), а сам
            // он не длиннее BridgeSec, период внутри заменяется прямой между краями, и всё
            // считается заново. Окажись это настоящей паузой (лента гаснет на время
            // загрузки файла), вреда нет: склеиваются те же тёмные кадры.
            // Обороты на краях — медиана периода по уверенным узлам (согласие ≥ 0.7) в
            // секунде до провала и после: у самого края узлы ещё «включены», но
            // автокорреляция там уже ошибается (на 18khz.mp4 — 36 мс вместо 46). Такие узлы
            // с явно чужим периодом присоединяются к провалу и исправляются вместе с ним.
            Func<int, int, double> EdgePer = (int from, int to) => {
                List<double> pv = new List<double>();
                for (int j = Math.Max(0, from); j <= Math.Min(ng - 1, to); j++)
                    if (good[j] && Rg[j] >= 0.7 && PerAt(j) > 0) pv.Add(PerAt(j));
                return pv.Count >= 3 ? Median(pv) : 0;
            };
            int edgeN = (int)Math.Round(1.0 / gStep), extN = (int)Math.Round(0.5 / gStep);
            bool patched = false;
            for (int q = 0; q < ng; ) {
                if (on[q]) { q++; continue; }
                int a = q; while (q < ng && !on[q]) q++;
                if (a == 0 || q >= ng || q - a > holeMax) continue;
                double pa = EdgePer(a - edgeN, a - 1), pb = EdgePer(q, q + edgeN - 1);
                if (pa <= 0) pa = PerAt(a - 1);
                if (pb <= 0) pb = PerAt(q);
                if (pa <= 0 || pb <= 0 || Math.Abs(pa / pb - 1) > 0.15) continue;
                int a2 = a, q2 = q;
                while (a2 > 1 && a - a2 < extN && Math.Abs(PerAt(a2 - 1) / pa - 1) > 0.15) a2--;
                while (q2 < ng - 1 && q2 - q < extN && Math.Abs(PerAt(q2) / pb - 1) > 0.15) q2++;
                if (q2 - a2 > holeMax) continue;
                int ia = Math.Min(pg - 1, (int)Math.Round((a2 - 1) * gStep / A_pStep));
                int ib = Math.Min(pg - 1, (int)Math.Round(q2 * gStep / A_pStep));
                for (int j = ia + 1; j < ib; j++) { per[j] = pa + (pb - pa) * (j - ia) / (double)(ib - ia); patched = true; }
            }
            if (!patched) break;
        }
        // Сращивание провалов: не длиннее BridgeSec, период внутри непрерывен (±15 % от
        // краёв) — после исправления выше это все провалы с совпадающими краями. Настоящую
        // долгую паузу так не срастить: без тиков период на её краях разный или отсутствует.
        for (int q = 0; q < ng; ) {
            if (on[q]) { q++; continue; }
            int a = q; while (q < ng && !on[q]) q++;
            if (a == 0 || q >= ng || q - a > holeMax) continue;
            double pa = PerAt(a - 1), pb = PerAt(q);
            if (pa <= 0 || pb <= 0 || Math.Abs(pa / pb - 1) > 0.15) continue;
            double pm = 0.5 * (pa + pb);
            bool cont = true;
            for (int j = a; j < q; j++) { double pj = PerAt(j); if (pj <= 0 || Math.Abs(pj / pm - 1) > 0.15) { cont = false; break; } }
            if (cont) for (int j = a; j < q; j++) on[j] = true;
        }
        for (int q = 0; q < ng; ) {
            if (!on[q]) { q++; continue; }
            int a = q; while (q < ng && on[q]) q++;
            int b = q - 1;
            double t0 = a * gStep, t1 = b * gStep;
            if (t1 - t0 < minTrackSec) continue;
            // Сдвиг сетки θ — развёрнутый вдоль участка по надёжным узлам (согласие
            // голосов выше порога), в сращенных провалах — по прямой между краями.
            double[] thu = new double[b - a + 1];
            int lastGood = -1;
            for (int j = 0; j < thu.Length; j++) {
                if (!good[a + j]) continue;
                if (lastGood < 0) { thu[j] = th[a + j]; }
                else {
                    double d = th[a + j] - th[a + lastGood];
                    while (d > Math.PI) d -= 2 * Math.PI;
                    while (d < -Math.PI) d += 2 * Math.PI;
                    thu[j] = thu[lastGood] + d;
                    for (int u = lastGood + 1; u < j; u++) thu[u] = thu[lastGood] + d * (u - lastGood) / (j - lastGood);
                }
                lastGood = j;
            }
            if (lastGood < 0) continue;
            for (int j = lastGood + 1; j < thu.Length; j++) thu[j] = thu[lastGood];
            Func<double, double> Theta = (double t) => {
                double qf = (t - t0) / gStep;
                if (qf <= 0) return thu[0];
                if (qf >= thu.Length - 1) return thu[thu.Length - 1];
                int q0 = (int)Math.Floor(qf);
                return thu[q0] + (thu[q0 + 1] - thu[q0]) * (qf - q0);
            };
            // Тики — пересечения целых ψ(t) = φ(t) − θ(t)/2π, с расширением на полсекунды
            // за края маски: окно голосования размывает их, настоящие края уточняются ниже.
            List<double> tk = new List<double>();
            double ts = Math.Max(0, t0 - winSec), te = Math.Min(tEnd, t1 + winSec);
            double prevPsi = Phi(ts) - Theta(ts) / (2 * Math.PI);
            for (double t = ts + dt; t <= te; t += dt) {
                double psi = Phi(t) - Theta(t) / (2 * Math.PI);
                if (Math.Floor(psi) > Math.Floor(prevPsi)) {
                    double target = Math.Floor(psi);
                    double f = (target - prevPsi) / (psi - prevPsi);
                    tk.Add(t - dt + f * dt);
                }
                prevPsi = psi;
            }
            if (tk.Count < 2) continue;
            // У каждого тика — есть ли кандидат в ±15 % периода.
            bool[] hit = new bool[tk.Count];
            for (int j = 0; j < tk.Count; j++) {
                double Tj = j + 1 < tk.Count ? tk[j + 1] - tk[j] : tk[j] - tk[j - 1];
                int pos = Array.BinarySearch(ct, tk[j]);
                if (pos < 0) pos = ~pos;
                for (int c = Math.Max(0, pos - 1); c <= Math.Min(nc - 1, pos); c++) if (Math.Abs(ct[c] - tk[j]) <= 0.15 * Tj) hit[j] = true;
            }
            // Края: отрисовка начинается там, где из шести тиков подряд хотя бы пять
            // подтверждены кандидатами, а первые три из них — подряд (так же и конец):
            // одиночный шумовой кандидат за настоящим концом не продлевает трек.
            int s0 = -1, s1 = -1;
            for (int j = 0; j + 6 <= tk.Count; j++) {
                int h = 0; for (int u = j; u < j + 6; u++) if (hit[u]) h++;
                if (h < 5) continue;
                for (int u = j; u + 2 < tk.Count; u++) if (hit[u] && hit[u + 1] && hit[u + 2]) { s0 = u; break; }
                break;
            }
            for (int j = tk.Count - 6; j >= 0; j--) {
                int h = 0; for (int u = j; u < j + 6; u++) if (hit[u]) h++;
                if (h < 5) continue;
                for (int u = j + 5; u - 2 >= 0; u--) if (hit[u] && hit[u - 1] && hit[u - 2]) { s1 = u; break; }
                break;
            }
            // Совсем короткий «трек» — случайное совпадение в шуме, а не отрисовка.
            if (s0 < 0 || s1 - s0 < minTicks) continue;
            for (int j = s0; j <= s1; j++) { res.Add(tk[j]); res.Add(hit[j] ? 1 : 0); }
            res.Add(-1); res.Add(-1);
        }
        res.Add(-2); res.Add(ng);
        for (int q = 0; q < ng; q++) res.Add(Rg[q]);
        return res.ToArray();
    }
}

public static class PovRender {
    static StringBuilder errDec = new StringBuilder(), errEnc = new StringBuilder();
    public static string DecoderErrors { get { return errDec.ToString(); } }
    public static string EncoderErrors { get { return errEnc.ToString(); } }

    static Process Start(string exe, string args, bool readOut, bool writeIn, StringBuilder err) {
        ProcessStartInfo psi = new ProcessStartInfo(exe, args);
        psi.UseShellExecute = false;
        psi.CreateNoWindow = true;
        psi.RedirectStandardOutput = readOut;
        psi.RedirectStandardInput = writeIn;
        psi.RedirectStandardError = true;
        Process p = new Process();
        p.StartInfo = psi;
        p.ErrorDataReceived += (s, e) => { if (e.Data != null) lock (err) { if (err.Length < 20000) err.AppendLine(e.Data); } };
        p.Start();
        p.BeginErrorReadLine();
        return p;
    }

    static bool ReadFrame(Stream s, byte[] buf, int size) {
        int got = 0;
        while (got < size) {
            int r = s.Read(buf, got, size - got);
            if (r <= 0) return false;
            got += r;
        }
        return true;
    }

    // kinds[i]: 0 — counts[i] исходных кадров как есть; 1 — counts[i] кадров склеиваются
    // в один (mode: 0 lighten = поканальный максимум, 1 average, 2 screen), и он
    // повторяется counts[i] раз, чтобы время не поменялось; 2 — то же, но окно — ровно одна
    // прорисовка, и склейка шахматная (cell > 0, см. ниже). Отсечка шума blackLevel —
    // только в склейках больше одного кадра (одному кадру копить нечего).
    //
    // Шахматная склейка. В обычной все пиксели берут одно окно времени длиной в
    // прорисовку: каждый луч красит свой сектор от начала окна до конца, и на шести стыках
    // секторов встречаются начало и конец окна — картинка, сдвинувшаяся за 1/6 оборота
    // (анимация, недолёт/нахлёст целых кадров), собирается в шесть чётких нестыковок.
    // Здесь шесть окон той же длины, сдвинутых друг относительно друга (разнос — spread
    // прорисовки), и у каждого стык под своим углом:
    //   chk 0 — клетки cell×cell берут окна вперемешку случайно;
    //   chk 1 — то же, но по упорядоченному растру (Байер): окна чередуются равномерно, без
    //           комков, и смесь читается как ровный полутон, а не как зерно;
    //   chk 2 — каждый пиксель — среднее всех шести окон: шума нет вовсе, нестыковка
    //           превращается в мягкий переход.
    // Где проход луча через точку есть во всех шести окнах (дальше от стыка, чем разнос),
    // все окна дают одно и то же — там картинка такая же чёткая, как в обычной склейке.
    // Каждое окно — склейка целых кадров: ни сдвигов изображения, ни дорисовки.
    // Возвращает записанное число кадров; -1 — кодер завершился с ошибкой.
    public static long Run(string ffmpeg, string decArgs, string encArgs, int w, int h,
                           int[] kinds, int[] counts, double[] segT0, double[] segT,
                           int blackLevel, int mode, double fps,
                           int cell, double spread, int chk) {
        errDec.Clear(); errEnc.Clear();
        int px = w * h, fs = px * 3;
        long total = 0;
        foreach (int c in counts) total += c;
        bool checker = cell > 0 && spread > 0;
        // Нужный запас кадров вокруг окна — под самые дальние сдвиги.
        int cap = 4;
        for (int si = 0; si < kinds.Length; si++) {
            int span = counts[si];
            if (kinds[si] == 2) span = Math.Max(span, (int)Math.Ceiling(segT[si] * (1 + (checker ? spread : 0) * 5 / 6.0)) + 4);
            cap = Math.Max(cap, span + 4);
        }
        byte[] cmap = checker && chk != 2 ? CellMap(w, h, cell, chk == 1) : null;
        Process dec = Start(ffmpeg, decArgs, true, false, errDec);
        Process enc = Start(ffmpeg, encArgs, false, true, errEnc);
        Stream din = dec.StandardOutput.BaseStream;
        Stream eout = enc.StandardInput.BaseStream;
        byte[][] ring = new byte[cap][];
        byte[] acc = new byte[fs];
        // Элементарные отрезки: границы двенадцати окон (шесть сдвигов, у каждого два
        // соседних размещения — см. Windows) делят кадры не более чем на 24 куска, склейка
        // каждого считается один раз, окно — объединение нескольких кусков.
        byte[][] seg = new byte[24][];
        int[][] segSum = new int[24][];
        long written = 0, nextReport = 0, loaded = 0, pos = 0;
        long reportStep = Math.Max(1, (long)Math.Round(fps * 2));
        bool eof = false;
        byte[] lut = new byte[256];
        for (int v = 0; v < 256; v++) lut[v] = (byte)(v > blackLevel ? v : 0);
        int bands = Math.Max(1, Math.Min(256, h));
        int bandRows = (h + bands - 1) / bands;
        try {
            for (int si = 0; si < kinds.Length; si++) {
                int n = counts[si];
                if (n <= 0) continue;
                long a = pos, b = pos + n - 1;
                pos += n;
                // Окна: у прорисовки — от её точного (дробного) времени, по два размещения на
                // каждый из шести сдвигов, с весами (Windows); у части длинной прорисовки — само
                // окно [a, b].
                long[] wlo = new long[12], whi = new long[12];
                double[] wwt = new double[12];
                if (kinds[si] == 2) Windows(segT0[si], segT[si], checker ? spread : 0, wlo, whi, wwt);
                else for (int k = 0; k < 12; k++) { wlo[k] = a; whi[k] = b; wwt[k] = (k & 1) == 0 ? 1 : 0; }
                long need = b;
                for (int k = 0; k < 12; k++) if (wwt[k] > 0) need = Math.Max(need, whi[k]);
                // Дочитать кадры до нужного (с запасом вперёд для сдвинутых окон).
                while (loaded <= need && !eof) {
                    int slot = (int)(loaded % cap);
                    if (ring[slot] == null) ring[slot] = new byte[fs];
                    if (!ReadFrame(din, ring[slot], fs)) { eof = true; break; }
                    loaded++;
                }
                if (a >= loaded) break;
                if (b >= loaded) b = loaded - 1;
                int got = (int)(b - a + 1);
                if (kinds[si] == 0) {
                    for (long i = a; i <= b; i++) { eout.Write(ring[(int)(i % cap)], 0, fs); written++; }
                } else {
                    // Окна, обрезанные по доступным кадрам.
                    long first = Math.Max(0, loaded - cap);
                    long[] lo = new long[12], hi = new long[12];
                    for (int k = 0; k < 12; k++) {
                        lo[k] = Math.Max(Math.Max(first, 0), wlo[k]);
                        hi[k] = Math.Min(loaded - 1, whi[k]);
                        if (hi[k] < lo[k]) { lo[k] = a; hi[k] = b; }
                    }
                    // Границы элементарных отрезков.
                    List<long> bnd = new List<long>();
                    for (int k = 0; k < 12; k++) { bnd.Add(lo[k]); bnd.Add(hi[k] + 1); }
                    bnd.Sort();
                    List<long> ub = new List<long>();
                    foreach (long x in bnd) if (ub.Count == 0 || ub[ub.Count - 1] != x) ub.Add(x);
                    int ns = ub.Count - 1;
                    int[] sa = new int[12], sb = new int[12];
                    for (int k = 0; k < 12; k++) { sa[k] = ub.IndexOf(lo[k]); sb[k] = ub.IndexOf(hi[k] + 1) - 1; }
                    int[] segCnt = new int[ns];
                    for (int i = 0; i < ns; i++) {
                        segCnt[i] = (int)(ub[i + 1] - ub[i]);
                        if (mode == 1) { if (segSum[i] == null) segSum[i] = new int[fs]; }
                        else if (seg[i] == null) seg[i] = new byte[fs];
                    }
                    int[] winCnt = new int[12];
                    for (int k = 0; k < 12; k++) for (int i = sa[k]; i <= sb[k]; i++) winCnt[k] += segCnt[i];
                    bool useLut = n > 1 && blackLevel > 0;
                    long u0 = ub[0];
                    System.Threading.Tasks.Parallel.For(0, bands, bi => {
                        int r0 = bi * bandRows, r1 = Math.Min(h, r0 + bandRows);
                        if (r1 <= r0) return;
                        int q0 = r0 * w * 3, q1 = r1 * w * 3;
                        // Склейка каждого элементарного отрезка.
                        for (int i = 0; i < ns; i++) {
                            if (mode == 1) Array.Clear(segSum[i], q0, q1 - q0); else Array.Clear(seg[i], q0, q1 - q0);
                            for (long j = ub[i]; j < ub[i + 1]; j++) {
                                byte[] f = ring[(int)(j % cap)];
                                if (mode == 1) { int[] sm = segSum[i]; for (int q = q0; q < q1; q++) sm[q] += useLut ? lut[f[q]] : f[q]; }
                                else {
                                    byte[] sg = seg[i];
                                    for (int q = q0; q < q1; q++) {
                                        int x = useLut ? lut[f[q]] : f[q];
                                        if (mode == 2) sg[q] = (byte)(255 - (255 - sg[q]) * (255 - x) / 255);
                                        else if (x > sg[q]) sg[q] = (byte)x;
                                    }
                                }
                            }
                        }
                        // Окно пикселя (или все шесть — при смешивании).
                        for (int q = q0; q < q1; q++) {
                            int kFrom, kTo;
                            if (cmap != null) { kFrom = kTo = cmap[q / 3]; }
                            else if (chk == 2 && kinds[si] == 2 && checker) { kFrom = 0; kTo = 5; }
                            else { kFrom = kTo = 0; }
                            double total2 = 0;
                            for (int g = kFrom; g <= kTo; g++)
                                for (int k = 2 * g; k <= 2 * g + 1; k++) {
                                    if (wwt[k] <= 0) continue;
                                    int val;
                                    if (mode == 1) {
                                        int s = 0;
                                        for (int i = sa[k]; i <= sb[k]; i++) s += segSum[i][q];
                                        val = (s + winCnt[k] / 2) / winCnt[k];
                                    } else {
                                        val = 0;
                                        for (int i = sa[k]; i <= sb[k]; i++) {
                                            int x = seg[i][q];
                                            if (mode == 2) val = 255 - (255 - val) * (255 - x) / 255;
                                            else if (x > val) val = x;
                                        }
                                    }
                                    total2 += wwt[k] * val;
                                }
                            int nk = kTo - kFrom + 1;
                            acc[q] = (byte)Math.Min(255, (int)(total2 / nk + 0.5));
                        }
                    });
                    for (int i = 0; i < got; i++) { eout.Write(acc, 0, fs); written++; }
                }
                if (written >= nextReport) {
                    Console.WriteLine(string.Format("  кадров {0}/{1} ({2:0}%)", written, total, 100.0 * written / Math.Max(1, total)));
                    nextReport = written + reportStep;
                }
                if (got < n) break;
            }
        } catch (IOException) {
            // Кодер закрыл вход раньше времени — причина будет в его выводе ошибок.
        }
        try { eout.Close(); } catch (IOException) { }
        enc.WaitForExit();
        if (!dec.HasExited) { try { dec.Kill(); } catch (Exception) { } }
        dec.WaitForExit();
        return enc.ExitCode == 0 ? written : -1;
    }

    // Дробный номер кадра для момента t: кадр j начинается в pts[j] и длится до pts[j+1]
    // (pts — настоящие метки времени, по возрастанию). До первого и после последнего —
    // по крайнему интервалу.
    public static double FrameIndex(double[] pts, double t) {
        int n = pts.Length;
        if (n == 0) return 0;
        if (n == 1) return t >= pts[0] ? 0.5 : 0;
        if (t <= pts[0]) return (t - pts[0]) / (pts[1] - pts[0]);
        if (t >= pts[n - 1]) return n - 1 + (t - pts[n - 1]) / (pts[n - 1] - pts[n - 2]);
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) { int m = (lo + hi) / 2; if (pts[m] <= t) lo = m; else hi = m; }
        return lo + (t - pts[lo]) / Math.Max(1e-9, pts[lo + 1] - pts[lo]);
    }

    // Шесть окон прорисовки, в номерах кадров (кадр j занимает [j, j+1)). t0 — начало
    // прорисовки, T — её длина, оба в кадрах и дробные. Окна сдвинуты равномерно в пределах
    // spread прорисовки, симметрично вокруг неё (spread = 1 — на 0..5/6, k/6).
    // Длина окна — целое число кадров, а прорисовка дробная (скажем, 2.72 кадра): окно по
    // floor(T) кадров не дотягивает до соседнего сектора (тёмные щели), по ceil(T) — заходит
    // на него (нахлёст). Пока длина выходила из округления границ каждой прорисовки,
    // склейки чередовались: одна с нахлёстом, следующая со щелями; смесь длинных и коротких
    // окон в одной склейке не помогла — щели и нахлёсты просто переезжали с места на место
    // от склейки к склейке. Поэтому длина одна для всех окон и всех склеек — не короче
    // прорисовки (ceil): щелей нет вовсе, остаётся небольшой постоянный нахлёст. Если
    // прорисовка лишь чуть длиннее целого числа кадров (дробная часть меньше ShortFrac),
    // берётся floor: щель в сотые доли кадра не видна, а лишнее окно почти в целый кадр
    // давало бы заметный нахлёст. Меняется длина только вместе с оборотами — разово.
    //
    // Начало окна тоже дробное, а окно из целых кадров можно начать только на целом кадре.
    // Округление до ближайшего давало ошибку до ±0.5 кадра (на 420 об/мин — ±10° поворота),
    // и она ходила циклом биения частоты кадров с частотой прорисовок: стыки секторов от
    // склейки к склейке прыгали по кругу туда-обратно, а с ними и нахлёст с затемнением на
    // краях. Поэтому каждое окно — смесь двух соседних размещений: с начала на кадре
    // floor(s) с весом 1 − β и на следующем с весом β (β — дробная часть начала s). Оба
    // окна полные (длиной не меньше прорисовки), картинка не сдвигается и не
    // дорисовывается — меняется только вес двух честных склеек, и стык стоит на месте.
    // На выходе 12 окон: пары (2k, 2k+1) для сдвигов k = 0..5, веса пары в сумме 1.
    const double ShortFrac = 0.15;
    static void Windows(double t0, double T, double spread, long[] lo, long[] hi, double[] wt) {
        int fl = (int)Math.Floor(T + 1e-9);
        int L = (T - fl < ShortFrac) ? fl : fl + 1;
        if (L < 1) L = 1;
        for (int k = 0; k < 6; k++) {
            double c = t0 + T / 2 + (k - 2.5) / 6.0 * spread * T;
            double s = c - L / 2.0;
            long f0 = (long)Math.Floor(s + 1e-9);
            double beta = Math.Max(0, Math.Min(1, s - f0));
            lo[2 * k] = f0; hi[2 * k] = f0 + L - 1; wt[2 * k] = 1 - beta;
            lo[2 * k + 1] = f0 + 1; hi[2 * k + 1] = f0 + L; wt[2 * k + 1] = beta;
        }
    }

    // Номер окна (0..5) для каждого пикселя по клеткам cell×cell, один и тот же в каждом
    // кадре — узор неподвижен, не мерцает. ordered — упорядоченный растр Байера 8×8: окна
    // чередуются равномерно, соседние клетки почти всегда разные и без комков; иначе —
    // случайно.
    static byte[] CellMap(int w, int h, int cell, bool ordered) {
        int[] bayer = new int[64];
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++) {
                int v = 0;
                for (int bit = 0; bit < 3; bit++)
                    v = (v << 2) | ((((x ^ y) >> bit) & 1) << 1) | ((y >> bit) & 1);
                bayer[y * 8 + x] = v;
            }
        byte[] m = new byte[w * h];
        for (int y = 0; y < h; y++) {
            int cy = y / cell;
            for (int x = 0; x < w; x++) {
                int cx = x / cell;
                if (ordered) m[y * w + x] = (byte)(bayer[(cy & 7) * 8 + (cx & 7)] * 6 / 64);
                else {
                    uint v = (uint)(cx * 73856093) ^ (uint)(cy * 19349663);
                    v ^= v >> 13; v *= 0x5bd1e995; v ^= v >> 15;
                    m[y * w + x] = (byte)(v % 6);
                }
            }
        }
        return m;
    }
}
'@

function Initialize-PovCode {
    if (-not ('PovTicks' -as [type])) { Add-Type -TypeDefinition $PovSource -Language CSharp }
}

# Аргумент командной строки для внешнего процесса: в кавычках, если есть пробел.
function Quote-Arg([string]$a) {
    if ($a -match '[\s"]') { return '"' + ($a -replace '"', '\"') + '"' }
    return $a
}

function Get-StreamStart {
    param([string]$FFprobePath, [string]$File, [string]$Stream)
    $r = Invoke-NativeCapture -Path $FFprobePath -ArgList @('-v', 'error', '-select_streams', $Stream, '-show_entries', 'stream=start_time', '-of', 'csv=p=0', '--', $File)
    $d = 0.0
    $v = ($r.Text -split "`r?`n" | Where-Object { $_.Trim() } | Select-Object -First 1)
    if ($v -and [double]::TryParse($v.Trim().TrimEnd(','), [Globalization.NumberStyles]::Float, $Inv, [ref]$d)) { return $d }
    return 0.0
}

# Размер кадра на выходе декодера: ширина/высота потока с учётом поворота из метаданных
# (телефон пишет вертикальное видео как горизонтальное + поворот, а ffmpeg по умолчанию
# кадры поворачивает — сырой поток придёт уже повёрнутым) и длительность видео.
function Get-VideoGeometry {
    param([string]$FFprobePath, [string]$File)
    $r = Invoke-NativeCapture -Path $FFprobePath -ArgList @('-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=width,height,duration:stream_side_data=rotation:format=duration', '-of', 'default=nw=1', '--', $File)
    $w = [regex]::Match($r.Text, '(?m)^width=(\d+)').Groups[1].Value
    $h = [regex]::Match($r.Text, '(?m)^height=(\d+)').Groups[1].Value
    $rot = [regex]::Match($r.Text, '(?m)^rotation=(-?\d+)').Groups[1].Value
    $durs = [regex]::Matches($r.Text, '(?m)^duration=([0-9.]+)') | ForEach-Object { [double]::Parse($_.Groups[1].Value, $Inv) }
    if (-not $w -or -not $h) { throw "Не удалось определить размер кадра: $File" }
    $W = [int]$w; $H = [int]$h
    if ($rot -and ([Math]::Abs([int]$rot) % 180) -eq 90) { $t = $W; $W = $H; $H = $t }
    $dur = if ($durs) { ($durs | Measure-Object -Maximum).Maximum } else { 0 }
    return [PSCustomObject]@{ Width = $W; Height = $H; Duration = $dur }
}

# Фильтр, возвращающий замедленному в Speed раз звуку реальную скорость и высоту: сэмплы
# объявляются идущими в Speed раз чаще (asetrate) и пересчитываются в 48 кГц. Тон 18 кГц,
# опущенный замедлением до 18/Speed, снова 18 кГц. Именно 48 кГц, а не частота файла:
# Samsung хранит замедленный в 4 раза звук как 12 кГц (те же сэмплы, что были на 48 кГц,
# только помечены медленнее), и пересчёт обратно в 12 кГц срезал бы всё выше 6 кГц —
# вместе с тоном 18 кГц.
function Get-SpeedUpFilter {
    param([double]$Speed, [int]$SampleRate)
    if ($Speed -le 1) { return "" }
    return "asetrate=$([int][Math]::Round($SampleRate * $Speed)),aresample=48000,"
}

function Get-AudioRate {
    param([string]$FFprobePath, [string]$File)
    $r = Invoke-NativeCapture -Path $FFprobePath -ArgList @('-v', 'error', '-select_streams', 'a:0', '-show_entries', 'stream=sample_rate', '-of', 'csv=p=0', '--', $File)
    $v = 0
    $line = ($r.Text -split "`r?`n" | Where-Object { $_.Trim() } | Select-Object -First 1)
    if ($line -and [int]::TryParse($line.Trim().TrimEnd(','), [ref]$v) -and $v -gt 0) { return $v }
    return 48000
}

# Подсказка о замедлении из метаданных: частота съёмки сенсора (Android пишет
# com.android.capture.fps, приложение Blackmagic — sensorFPS) против частоты кадров файла.
# 0 — подсказки нет.
function Get-SlowMoHint {
    param([string]$FFprobePath, [string]$File, [double]$FileFps)
    $r = Invoke-NativeCapture -Path $FFprobePath -ArgList @('-v', 'error', '-show_entries', 'format_tags:stream_tags', '-of', 'default=nw=1', '--', $File)
    foreach ($m in [regex]::Matches($r.Text, '(?im)^TAG:[^=]*(capture[._]?fps|sensorfps)[^=]*=\s*([0-9.]+)')) {
        $cap = 0.0
        if ([double]::TryParse($m.Groups[2].Value, [Globalization.NumberStyles]::Float, $Inv, [ref]$cap) -and $FileFps -gt 0) {
            $k = [Math]::Round($cap / $FileFps)
            if ($k -ge 2) { return $k }
        }
    }
    return 0
}

# Тики по звуку: декодируем дорожку в моно 48 кГц float и отдаём детектору и трекеру.
# Времена переводятся на шкалу видео: у телефонной записи звук начинается не вровень с
# видео (test17khz.mp4 — на 13 мс позже), а сырой поток сэмплов об этом не помнит.
# Замедленная съёмка (Speed > 1): звук сначала разгоняется до реальной скорости, тики
# ищутся с обычными параметрами (тон 18 кГц, 5 мс), а найденные времена растягиваются
# обратно в Speed раз — на шкалу файла.
# Возвращает { Candidates; Tracks = @( @{ Times = double[]; Real = bool[] } ) }.
function Get-ToneTracks {
    param([string]$FFmpegPath, [string]$FFprobePath, [string]$File, [string]$TempDir,
          [double]$Freq, [double]$TickMs, [double]$SnrDb, [double]$TonalDb,
          [double]$MinPeriod, [double]$MaxPeriod, [int]$MinTicks,
          [double]$Speed = 1, [int]$SampleRate = 48000)

    # Декодированный звук — один на каждое замедление: при переборе частот тона он тот же.
    $pcm = Join-Path $TempDir ("audio_x{0}.f32" -f (Num $Speed))
    if (-not (Test-Path -LiteralPath $pcm)) {
        $dargs = @('-hide_banner', '-loglevel', 'error', '-y', '-i', $File, '-vn')
        $pre = Get-SpeedUpFilter $Speed $SampleRate
        if ($pre) { $dargs += @('-af', $pre.TrimEnd(',')) }
        $dargs += @('-ac', '1', '-ar', '48000', '-f', 'f32le', $pcm)
        $r = Invoke-NativeCapture -Path $FFmpegPath -ArgList $dargs
        if ($r.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $pcm)) { throw "ffmpeg не смог прочитать аудиодорожку (код $($r.ExitCode)). Есть ли звук в файле?" }
    }
    $bytes = [IO.File]::ReadAllBytes($pcm)
    $x = New-Object float[] ([int]($bytes.Length / 4))
    [Buffer]::BlockCopy($bytes, 0, $x, 0, $x.Length * 4)
    $bytes = $null

    $offset = (Get-StreamStart $FFprobePath $File 'a:0') - (Get-StreamStart $FFprobePath $File 'v:0')
    $cand = [PovTicks]::Detect($x, 48000, $Freq, 700, $TickMs, $SnrDb, $TonalDb, $MinPeriod, $MaxPeriod)
    # Окно голосования ±0.25 с (не меньше 3.5 периодов), согласие ≥ 0.5, не меньше 5
    # голосов, участок маски ≥ 0.5 с, трек ≥ MinTicks интервалов.
    $tr = [PovTicks]::Grid($cand, $MinPeriod, $MaxPeriod, 0.25, 0.5, 5, 0.5, $MinTicks)

    $tracks = New-Object System.Collections.Generic.List[object]
    $ct = New-Object System.Collections.Generic.List[double]
    $cr = New-Object System.Collections.Generic.List[bool]
    for ($i = 0; $i -lt $tr.Length; $i += 2) {
        if ($tr[$i] -eq -2) { break }          # дальше — диагностика согласия, она не нужна
        if ($tr[$i] -lt 0) {
            $tracks.Add([PSCustomObject]@{ Times = $ct.ToArray(); Real = $cr.ToArray() })
            $ct = New-Object System.Collections.Generic.List[double]
            $cr = New-Object System.Collections.Generic.List[bool]
        } else {
            $ct.Add($tr[$i] * $Speed + $offset); $cr.Add($tr[$i + 1] -gt 0)
        }
    }
    return [PSCustomObject]@{ Candidates = [int]($cand.Length / 3); Tracks = $tracks }
}

if (-not (Test-Path -LiteralPath $InputFile)) {
    throw "Файл не найден: $InputFile"
}
$InputFile = (Resolve-Path -LiteralPath $InputFile).Path

$startVal = Parse-Num $Start
$durationVal = Parse-Num $Duration

$ffmpeg = Resolve-FFTool -Name "ffmpeg"
$ffprobe = Resolve-FFTool -Name "ffprobe"

# ============================== Режим -AudioSync ==============================
if ($AudioSync) {
    if ($BeepsPerRev -lt 1 -or $Arms -lt 1 -or ($Arms % $BeepsPerRev) -ne 0) {
        throw "-Arms ($Arms) должно быть кратно -BeepsPerRev ($BeepsPerRev): каждый интервал между тиками делится на Arms/BeepsPerRev прорисовок."
    }
    if ($MinFps -le 0) { throw "-MinFps должно быть больше нуля." }
    if ($MinRpm -le 0) { throw "-MinRpm должно быть больше нуля." }
    $modeId = switch ($Mode) { 'lighten' { 0 } 'average' { 1 } 'screen' { 2 } default { -1 } }
    if ($modeId -lt 0) { throw "В режиме -AudioSync -Mode может быть lighten, average или screen (склейку делает сам скрипт, а не tblend)." }

    # Прорисовок (кадров на выходе) на один интервал между тиками: при тике на каждый луч — одна.
    $perBeep = [int]($Arms / $BeepsPerRev)
    # Длительность одной прорисовки (интервал между тиками), с. Снизу — защита от дребезга;
    # сверху — порог отрисовки -MinRpm: медленнее прошивка ленту гасит и не тикает, и
    # более длинный интервал — это уже не прорисовка, а пауза.
    $minPeriod = $MinRevMs / 1000.0 / $BeepsPerRev
    $maxPeriod = 60.0 / ($MinRpm * $BeepsPerRev)

    Write-Host "Определяю параметры видео..."
    $inputFps = Get-VideoFps -FFprobePath $ffprobe -File $InputFile
    $geo = Get-VideoGeometry -FFprobePath $ffprobe -File $InputFile
    Write-Host ("Исходное видео: {0}x{1}, {2:0.###} к/с, {3:0.###} с" -f $geo.Width, $geo.Height, $inputFps, $geo.Duration)

    # Замедление: число из -SlowMo или перебор (auto). Подсказка метаданных идёт первой.
    $slowFixed = 0.0
    if ($SlowMo -and $SlowMo.Trim().ToLower() -ne 'auto') {
        if (-not [double]::TryParse($SlowMo.Trim().Replace(',', '.'), [Globalization.NumberStyles]::Float, $Inv, [ref]$slowFixed)) { $slowFixed = 0 }
        if ($slowFixed -lt 1) { throw "-SlowMo: auto или число не меньше 1 (во сколько раз файл медленнее реального времени), а не '$SlowMo'." }
    }
    $slowHint = Get-SlowMoHint -FFprobePath $ffprobe -File $InputFile -FileFps $inputFps
    $audioRate = Get-AudioRate -FFprobePath $ffprobe -File $InputFile
    $slowTry = New-Object System.Collections.Generic.List[double]
    if ($slowFixed -gt 0) { $slowTry.Add($slowFixed) }
    else { foreach ($k in @($slowHint, 1, 4, 8, 2)) { if ($k -ge 1 -and -not $slowTry.Contains([double]$k)) { $slowTry.Add([double]$k) } } }
    $slow = $slowTry[0]

    # Обрабатываемый отрезок: весь ролик или -Start/-Duration. Он весь попадает в результат —
    # участки без тиков идут в исходной частоте кадров, а не выбрасываются.
    $coverStart = if ($null -ne $startVal) { [Math]::Max(0.0, $startVal) } else { 0.0 }
    $coverEnd = if ($null -ne $durationVal) { $coverStart + $durationVal } else { $geo.Duration }
    if ($geo.Duration -gt 0 -and $coverEnd -gt $geo.Duration) { $coverEnd = $geo.Duration }
    if ($coverEnd -le $coverStart) { throw "Пустой диапазон: -Start $(Num $coverStart), конец $(Num $coverEnd)." }

    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("povsync_" + [System.Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $tempDir | Out-Null
    try {
        $tracks = New-Object System.Collections.Generic.List[object]
        if ($LegacyClickDetect) {
            # Старый детектор: порог по громкости в полосе (silencedetect). Годится для
            # щелчка датчика Холла на старых записях — там нет тона, и детектору тиков не за
            # что зацепиться. Пропуски восстанавливаются так же, как раньше (по медиане
            # соседей), интервал длиннее прорисовки на -MinRpm разрывает трек.
            # Перебора замедлений здесь нет: -SlowMo числом или подсказка метаданных, иначе 1.
            # Времена щелчков — в реальном времени (звук разогнан), на шкалу файла — в конце.
            $slow = if ($slowFixed -gt 0) { $slowFixed } elseif ($slowHint -ge 2) { [double]$slowHint } else { 1.0 }
            $pre = Get-SpeedUpFilter $slow $audioRate
            Write-Host "Ищу щелчки (старый детектор): полоса $(Num $BeepFreq)±$(Num ($BeepBandwidth/2)) Гц, порог $(Num $BeepThresholdDb) дБ, $BeepsPerRev на оборот$(if ($slow -gt 1) { ", замедление ×$(Num $slow)" })..."
            $raw = @(Get-BeepTimestamps -FFmpegPath $ffmpeg -File $InputFile -Freq $BeepFreq -Bandwidth $BeepBandwidth -ThresholdDb $BeepThresholdDb -MinSilenceMs $BeepMinDurationMs -Pre $pre)
            $candCount = $raw.Count
            if ($raw.Count -eq 0) {
                $lv = Get-BandLevels -FFmpegPath $ffmpeg -File $InputFile -Freq $BeepFreq -Bandwidth $BeepBandwidth -Pre $pre
                Write-Host "Уровень в полосе детектора: пик $($lv.Max) дБ, средний $($lv.Mean) дБ; порог сейчас $(Num $BeepThresholdDb) дБ."
            }
            $ts = New-Object System.Collections.Generic.List[double]
            foreach ($t in $raw) { if ($ts.Count -eq 0 -or ($t - $ts[$ts.Count - 1]) -ge $minPeriod) { $ts.Add($t) } }
            $gc = $ts.Count - 1
            $curT = New-Object System.Collections.Generic.List[double]
            $curR = New-Object System.Collections.Generic.List[bool]
            if ($ts.Count -gt 0) { $curT.Add($ts[0]); $curR.Add($true) }
            for ($i = 0; $i -lt $gc; $i++) {
                $g = $ts[$i + 1] - $ts[$i]
                $nb = New-Object System.Collections.Generic.List[double]
                for ($j = [Math]::Max(0, $i - 6); $j -le [Math]::Min($gc - 1, $i + 6); $j++) {
                    if ($j -ne $i) { $nb.Add($ts[$j + 1] - $ts[$j]) }
                }
                $mult = 1
                if ($nb.Count -ge 2) {
                    $ref = Get-Median $nb.ToArray()
                    if ($ref -gt 0) {
                        $ratio = $g / $ref; $k = [int][Math]::Round($ratio)
                        if ($k -ge 2 -and $k -le 2 * $BeepsPerRev -and [Math]::Abs($ratio - $k) -le 0.35) { $mult = $k }
                    }
                }
                if ($g / $mult -gt 1.3 * $maxPeriod) {
                    if ($curT.Count -ge 2) { $tracks.Add([PSCustomObject]@{ Times = $curT.ToArray(); Real = $curR.ToArray() }) }
                    $curT = New-Object System.Collections.Generic.List[double]
                    $curR = New-Object System.Collections.Generic.List[bool]
                } else {
                    for ($m = 1; $m -lt $mult; $m++) { $curT.Add($ts[$i] + $g * $m / $mult); $curR.Add($false) }
                }
                $curT.Add($ts[$i + 1]); $curR.Add($true)
            }
            if ($curT.Count -ge 2) { $tracks.Add([PSCustomObject]@{ Times = $curT.ToArray(); Real = $curR.ToArray() }) }
            if ($slow -gt 1) {
                foreach ($tr in $tracks) { for ($i = 0; $i -lt $tr.Times.Count; $i++) { $tr.Times[$i] *= $slow } }
            }
        } else {
            Initialize-PovCode
            # Частота тона: заданная -BeepFreq или, по умолчанию, 18 кГц (нынешняя прошивка),
            # а если тиков на ней нет — 17 кГц (записи с прежней прошивкой).
            $freqTry = if ($PSBoundParameters.ContainsKey('BeepFreq')) { @($BeepFreq) } else { @($BeepFreq, 17000) }
            Write-Host "Ищу тики синхро-датчика: тон $(($freqTry | ForEach-Object { Num $_ }) -join ' или ') Гц по $(Num $BeepMs) мс, $BeepsPerRev на оборот, прорисовка от $(Num ([Math]::Round($minPeriod * 1000, 1))) до $(Num ([Math]::Round($maxPeriod * 1000, 1))) мс..."
            if ($slowFixed -eq 0 -and $slowHint -ge 2) { Write-Host "  метаданные: частота съёмки выше частоты кадров файла — похоже на замедленную съёмку ×$slowHint" }
            # Перебор замедлений (или одно заданное) и частот тона: звук разгоняется до
            # реальной скорости, тики ищутся с обычными параметрами. Берётся первое сочетание,
            # при котором нашлось хотя бы 4 оборота отрисовки; если такого нет — лучшее.
            $det = $null; $best = -1; $foundFreq = $freqTry[0]
            $tried = New-Object System.Collections.Generic.List[string]
            foreach ($k in $slowTry) {
                foreach ($fq in $freqTry) {
                    $d = Get-ToneTracks -FFmpegPath $ffmpeg -FFprobePath $ffprobe -File $InputFile -TempDir $tempDir `
                        -Freq $fq -TickMs $BeepMs -SnrDb $BeepSnrDb -TonalDb $BeepTonalDb `
                        -MinPeriod $minPeriod -MaxPeriod $maxPeriod -MinTicks (2 * $BeepsPerRev) `
                        -Speed $k -SampleRate $audioRate
                    $iv = 0
                    foreach ($tr in $d.Tracks) { $iv += $tr.Times.Count - 1 }
                    $tried.Add(("  тон {0} Гц, замедление ×{1} (в файле {2:0} Гц, тик {3:0.#} мс): интервалов между тиками {4}" -f (Num $fq), $k, ($fq / $k), ($BeepMs * $k), $iv))
                    if ($iv -gt $best) { $best = $iv; $det = $d; $slow = $k; $foundFreq = $fq }
                    if ($iv -ge 4 * $BeepsPerRev) { break }
                }
                if ($best -ge 4 * $BeepsPerRev) { break }
            }
            # Перебор показываем, только если первой попытки не хватило.
            if ($tried.Count -gt 1) { foreach ($l in $tried) { Write-Host $l } }
            if ($best -gt 0 -and $foundFreq -ne $freqTry[0]) { Write-Host "Тики найдены на $(Num $foundFreq) Гц — запись с прежней прошивкой." }
            $candCount = $det.Candidates
            foreach ($tr in $det.Tracks) { $tracks.Add($tr) }
        }
        if ($slow -gt 1) {
            Write-Host ("Замедленная съёмка ×{0}: обороты и интервалы ниже — реальные, времена участков — по шкале исходного файла; результат будет в реальном времени." -f (Num $slow))
        }

        # Треки — только в пределах обрабатываемого отрезка.
        $clipped = New-Object System.Collections.Generic.List[object]
        foreach ($tr in $tracks) {
            $tt = New-Object System.Collections.Generic.List[double]
            $rr = New-Object System.Collections.Generic.List[bool]
            for ($i = 0; $i -lt $tr.Times.Count; $i++) {
                if ($tr.Times[$i] -ge $coverStart -and $tr.Times[$i] -le $coverEnd) { $tt.Add($tr.Times[$i]); $rr.Add($tr.Real[$i]) }
            }
            if ($tt.Count -ge 2) { $clipped.Add([PSCustomObject]@{ Times = $tt.ToArray(); Real = $rr.ToArray() }) }
        }
        $tracks = $clipped

        # Разметка времени: отрисовка (между соседними тиками трека — склейка) и всё остальное
        # (исходные кадры как есть: пока колесо не рисует, снижать частоту незачем — на видео
        # обычное вращение, а не картинка). Прорисовка длиннее 1/-MinFps делится на части,
        # чтобы ни один склеенный кадр не держался дольше: ниже -MinFps частота не падает.
        $segs = New-Object System.Collections.Generic.List[object]
        $cursor = $coverStart
        $sweeps = 0; $fpsSplit = 0; $filled = 0
        foreach ($tr in $tracks) {
            $ts = $tr.Times
            if ($ts[0] -gt $cursor) { $segs.Add([PSCustomObject]@{ Kind = 'native'; T0 = $cursor; T1 = $ts[0]; Wins = 0 }) }
            for ($k = 0; $k -lt $ts.Count - 1; $k++) {
                $dur = $ts[$k + 1] - $ts[$k]
                $wins = [Math]::Max($perBeep, [int][Math]::Ceiling($dur / $slow * $MinFps - 1e-6))
                if ($wins -gt $perBeep) { $fpsSplit++ }
                $sweeps += $wins
                $segs.Add([PSCustomObject]@{ Kind = 'sync'; T0 = $ts[$k]; T1 = $ts[$k + 1]; Wins = $wins })
            }
            foreach ($rv in $tr.Real) { if (-not $rv) { $filled++ } }
            $cursor = $ts[$ts.Count - 1]
        }
        if ($coverEnd -gt $cursor) { $segs.Add([PSCustomObject]@{ Kind = 'native'; T0 = $cursor; T1 = $coverEnd; Wins = 0 }) }

        Write-Host ""
        Write-Host "Кандидатов в тики: $candCount; участков отрисовки: $($tracks.Count)"
        $ti = 0
        foreach ($tr in $tracks) {
            $ti++
            $ts = $tr.Times
            $rp = for ($k = 0; $k -lt $ts.Count - 1; $k++) { 60.0 / (($ts[$k + 1] - $ts[$k]) / $slow * $BeepsPerRev) }
            $nf = @($tr.Real | Where-Object { -not $_ }).Count
            Write-Host ("  отрисовка {0}: {1:0.000}-{2:0.000} с, интервалов между тиками {3} (из них по достроенным тикам: {4}), {5:0}..{6:0} об/мин" -f $ti, $ts[0], $ts[$ts.Count - 1], ($ts.Count - 1), $nf, ($rp | Measure-Object -Minimum).Minimum, ($rp | Measure-Object -Maximum).Maximum)
        }
        $natives = @($segs | Where-Object { $_.Kind -eq 'native' -and ($_.T1 - $_.T0) -gt 0 })
        if ($natives.Count -gt 0) {
            Write-Host ("  без тиков, исходная частота кадров: " + (($natives | ForEach-Object { "{0:0.000}-{1:0.000} с" -f $_.T0, $_.T1 }) -join ', '))
        }
        Write-Host ("Склеенных кадров (прорисовок): {0}" -f $sweeps) -NoNewline
        if ($fpsSplit -gt 0) { Write-Host (" — из них {0} интервалов длиннее 1/{1} с разбиты, чтобы частота не падала ниже {1} к/с" -f $fpsSplit, (Num $MinFps)) } else { Write-Host "" }

        $printGapList = {
            $ti = 0
            foreach ($tr in $tracks) {
                $ti++
                Write-Host ""
                Write-Host "Отрисовка ${ti}: интервалы между тиками, мс (* — тик достроен: пропущен на записи):"
                $line = New-Object System.Text.StringBuilder
                for ($k = 1; $k -lt $tr.Times.Count; $k++) {
                    [void]$line.Append(("{0,4:0}{1}" -f (($tr.Times[$k] - $tr.Times[$k - 1]) / $slow * 1000.0), $(if ($tr.Real[$k]) { ' ' } else { '*' })))
                    if ($k % 20 -eq 0) { Write-Host ("   " + $line.ToString()); [void]$line.Clear() }
                }
                if ($line.Length -gt 0) { Write-Host ("   " + $line.ToString()) }
            }
        }

        if ($tracks.Count -eq 0) {
            Write-Host ""
            Write-Host "Отрисовки не найдено — тиков синхро-датчика на звуке нет (пьезо тикает только пока лента светится)."
            if (-not $DetectOnly) { Write-Host "Склеивать нечего: видео не меняется. Если тики на записи есть, попробуйте снизить -BeepSnrDb (сейчас $(Num $BeepSnrDb) дБ)." }
            return
        }

        if ($DetectOnly) {
            & $printGapList
            Write-Host ""
            Write-Host "Это только предпросмотр (-DetectOnly) — видео не обрабатывалось. Если участки отрисовки совпадают с тем, когда на видео горит картинка, уберите -DetectOnly и запустите снова."
            return
        }

        if (-not $Force) {
            & $printGapList
            Write-Host ""
            $answer = Read-Host "Список выглядит правдоподобно? Продолжить обработку (Y/n)"
            if ($answer -and $answer.Trim().ToLower() -notin @('y', 'yes', 'д', 'да', '')) {
                Write-Host "Отменено. Проверьте тики через -DetectOnly (-BeepSnrDb, -BeepFreq), либо запустите с -Force, чтобы пропустить этот вопрос."
                return
            }
        }

        if (-not $OutputFile) {
            $dir = Split-Path -Parent $InputFile
            $base = [System.IO.Path]::GetFileNameWithoutExtension($InputFile)
            $OutputFile = Join-Path $dir "${base}_sync_${sweeps}sweeps.mp4"
        }

        # Кадры — с их настоящими метками времени, без приведения к постоянной частоте.
        # Камера пишет не ровно заявленные 120 к/с, а, например, 119.08, и фильтр fps=120
        # раз в секунду дублировал кадр: склейка, куда попадал дубль, недосчитывалась
        # куска прорисовки и мелькала тёмными секторами. Метки берутся из пакетов потока
        # (ffprobe, без декодирования), кадр n декодера — n-я метка не раньше coverStart.
        # Границы окон считаются от общих моментов времени, поэтому соседние отрезки
        # стыкуются без дыр и нахлёста, а сумма кадров равна числу кадров отрезка.
        Initialize-PovCode
        $vStart = Get-StreamStart $ffprobe $InputFile 'v:0'
        $ptsRaw = Invoke-NativeCapture -Path $ffprobe -ArgList @('-v', 'error', '-select_streams', 'v:0', '-show_entries', 'packet=pts_time', '-of', 'csv=p=0', '--', $InputFile)
        $ptsList = New-Object System.Collections.Generic.List[double]
        foreach ($line in ($ptsRaw.Text -split "`r?`n")) {
            $v = 0.0
            if ([double]::TryParse($line.Trim().TrimEnd(','), [Globalization.NumberStyles]::Float, $Inv, [ref]$v)) {
                $v -= $vStart
                if ($v -ge $coverStart - 1e-6 -and $v -lt $coverEnd - 1e-6) { $ptsList.Add($v) }
            }
        }
        $ptsList.Sort()
        $pts = $ptsList.ToArray()
        if ($pts.Length -lt 2) { throw "Не удалось прочитать метки времени кадров: $InputFile" }
        # Средняя частота отрезка — с ней кодируется результат: кадров столько же, длина та же.
        $outFps = ($pts.Length - 1) / ($pts[$pts.Length - 1] - $pts[0])
        $frameIdx = { param([double]$t) [PovRender]::FrameIndex($pts, $t) }
        $frameAt = { param([double]$t) [Math]::Max(0, [Math]::Min($pts.Length, [int][Math]::Round((& $frameIdx $t)))) }
        $kinds = New-Object System.Collections.Generic.List[int]
        $counts = New-Object System.Collections.Generic.List[int]
        # Точное начало и длина каждого окна в кадрах (дробные) — по ним шахматная склейка
        # строит свои шесть окон, а не по уже округлённым границам.
        $segT0 = New-Object System.Collections.Generic.List[double]
        $segT = New-Object System.Collections.Generic.List[double]
        $emptySlices = 0
        foreach ($sg in $segs) {
            if ($sg.Kind -eq 'native') {
                $n = (& $frameAt $sg.T1) - (& $frameAt $sg.T0)
                if ($n -gt 0) { $kinds.Add(0); $counts.Add($n); $segT0.Add(0); $segT.Add(0) }
                continue
            }
            for ($w = 0; $w -lt $sg.Wins; $w++) {
                $ta = if ($w -eq 0) { $sg.T0 } else { $sg.T0 + ($sg.T1 - $sg.T0) * $w / $sg.Wins }
                $tb = if ($w -eq $sg.Wins - 1) { $sg.T1 } else { $sg.T0 + ($sg.T1 - $sg.T0) * ($w + 1) / $sg.Wins }
                $n = (& $frameAt $tb) - (& $frameAt $ta)
                # Окно короче кадра (очень быстрое вращение при низкой частоте съёмки) — его
                # время целиком досталось соседнему при округлении границ; время не теряется.
                if ($n -lt 1) { $emptySlices++; continue }
                # Окно ровно в одну прорисовку — шахматная склейка (вид 2); часть длинной
                # прорисовки (разбитой ради -MinFps) — простая склейка (вид 1).
                $kinds.Add($(if ($sg.Wins -eq $perBeep) { 2 } else { 1 })); $counts.Add($n)
                $fa = & $frameIdx $ta
                $segT0.Add($fa); $segT.Add((& $frameIdx $tb) - $fa)
            }
        }
        $plannedFrames = 0
        foreach ($c in $counts) { $plannedFrames += $c }

        $span = $coverEnd - $coverStart
        $decArgs = @('-hide_banner', '-loglevel', 'error', '-nostdin')
        if ($coverStart -gt 0) { $decArgs += @('-ss', (Num $coverStart)) }
        $decArgs += @('-i', $InputFile, '-t', (Num $span), '-an', '-sn', '-dn',
                      '-vf', 'format=rgb24', '-fps_mode', 'passthrough', '-enc_time_base', 'demux',
                      '-f', 'rawvideo', '-pix_fmt', 'rgb24', 'pipe:1')
        # Замедленная съёмка: кадры идут в slow раз чаще, звук разгоняется до исходной высоты,
        # длина результата — реальная (span / slow).
        $encArgs = @('-hide_banner', '-loglevel', 'error', '-y',
                     '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', "$($geo.Width)x$($geo.Height)", '-r', (Num ($outFps * $slow)), '-i', 'pipe:0')
        if ($coverStart -gt 0) { $encArgs += @('-ss', (Num $coverStart)) }
        $encArgs += @('-i', $InputFile, '-map', '0:v', '-map', '1:a?', '-t', (Num ($span / $slow)))
        if ($slow -gt 1) { $encArgs += @('-af', (Get-SpeedUpFilter $slow $audioRate).TrimEnd(',')) }
        $encArgs += @('-c:v', 'libx264', '-preset', 'medium', '-crf', $Crf.ToString(), '-pix_fmt', 'yuv420p',
                      '-c:a', 'aac', $OutputFile)
        # Без -shortest: звук в телефонной записи бывает на десятки миллисекунд короче
        # видео, и -shortest отрезал по нему хвост кадров. Пусть лучше кончится звук.

        Write-Host ""
        Write-Host ("Обрабатываю {0:0.###} с видео ({1} кадров) за один проход: склейка — поканальный {2}, отсечка шума {3}..." -f $span, $plannedFrames, $Mode, $BlackLevel)
        if ($slow -gt 1) { Write-Host ("  результат — в реальном времени: {0:0.###} с, {1:0.##} к/с" -f ($span / $slow), ($outFps * $slow)) }
        $chkModeId = switch ($CheckerMode) { "ordered" { 1 } "blend" { 2 } default { 0 } }
        $written = [PovRender]::Run($ffmpeg, (($decArgs | ForEach-Object { Quote-Arg $_ }) -join ' '),
                                    (($encArgs | ForEach-Object { Quote-Arg $_ }) -join ' '),
                                    $geo.Width, $geo.Height, $kinds.ToArray(), $counts.ToArray(),
                                    $segT0.ToArray(), $segT.ToArray(),
                                    $BlackLevel, $modeId, $outFps, [Math]::Max(0, $CheckerCell),
                                    [Math]::Max(0.0, [Math]::Min(1.0, $CheckerSpread)),
                                    $chkModeId)
        $decErr = [PovRender]::DecoderErrors.Trim()
        if ($decErr) { Write-Warning "Декодер: $decErr" }
        if ($written -lt 0) {
            throw "ffmpeg не смог закодировать результат: $([PovRender]::EncoderErrors.Trim())"
        }
        if ($written -lt $plannedFrames) {
            Write-Host ("Декодер отдал на {0} кадров меньше расчёта — в файле физически меньше кадров, чем по длительности из метаданных; хвост короче на {1:0.###} с." -f ($plannedFrames - $written), (($plannedFrames - $written) / ($outFps * $slow)))
        }
    }
    finally {
        Remove-Item -LiteralPath $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    }

    Write-Host ""
    if ($emptySlices -gt 0) {
        Write-Host "Окон короче одного кадра: $emptySlices — вращение слишком быстрое для этой частоты съёмки, чтобы каждая прорисовка получила хотя бы кадр (время при этом не теряется, оно досталось соседним окнам)."
    }
    Write-Host "Готово: $OutputFile"
    return
}

# ============================== Обычный режим (фиксированный N) ==============================

# Кириллица в .bat-файле с chcp 65001 ненадёжна (переключение кодовой страницы
# не всегда успевает подействовать на первые же строки, и текст рвётся на середине
# слова) — поэтому batch-обёртка кириллицу не выводит и не читает вовсе, а ввод
# частоты кадров сделан здесь: консоль PowerShell корректно показывает кириллицу
# из этого файла (сохранён в UTF-8 с BOM) независимо от системной кодовой страницы.
$targetFpsVal = Parse-Num $TargetFps
while (-not $targetFpsVal -or $targetFpsVal -le 0) {
    $TargetFps = Read-Host "Введите желаемую частоту кадров на выходе (например 5)"
    $targetFpsVal = Parse-Num $TargetFps
    if (-not $targetFpsVal -or $targetFpsVal -le 0) {
        Write-Host "Нужно положительное число, например 5 или 2.5"
    }
}

Write-Host "Определяю частоту кадров исходного файла..."
$inputFps = Get-VideoFps -FFprobePath $ffprobe -File $InputFile
Write-Host ("Исходное видео: {0:0.###} к/с" -f $inputFps)

$N = [int][Math]::Round($inputFps / $targetFpsVal)
if ($N -lt 1) { $N = 1 }
$actualOutFps = $inputFps / $N

if ($N -eq 1) {
    Write-Warning "Запрошенный fps не ниже исходного — склеивать нечего, файл будет просто перекодирован."
}

if (-not $OutputFile) {
    $dir = Split-Path -Parent $InputFile
    $base = [System.IO.Path]::GetFileNameWithoutExtension($InputFile)
    $fpsTag = ("{0:0.##}" -f $actualOutFps) -replace '\.', '_'
    $OutputFile = Join-Path $dir "${base}_blend${N}_${fpsTag}fps.mp4"
}

$core = Get-BlendCoreFilter -FrameCount $N -BlendMode $Mode -BlackLvl $BlackLevel
$parts = New-Object System.Collections.Generic.List[string]
$parts.Add($core)
if ($N -gt 1) {
    # Непересекающиеся группы кадров 0..N-1, N..2N-1, ... после цепочки tblend лежат
    # на кадрах k = 0, N, 2N, ... (подробности — в комментарии Get-BlendCoreFilter).
    $parts.Add("select=eq(mod(n\,$N)\,0)")
    $parts.Add("setpts=PTS-STARTPTS")
}
$parts.Add("format=yuv420p")
$filter = $parts -join ","

$ffArgs = New-Object System.Collections.Generic.List[string]
$ffArgs.Add("-y")
if ($null -ne $startVal) { $ffArgs.Add("-ss"); $ffArgs.Add((Num $startVal)) }
$ffArgs.Add("-i"); $ffArgs.Add($InputFile)
if ($null -ne $durationVal) { $ffArgs.Add("-t"); $ffArgs.Add((Num $durationVal)) }
$ffArgs.Add("-map"); $ffArgs.Add("0:v:0")
$ffArgs.Add("-map"); $ffArgs.Add("0:a:0?")
$ffArgs.Add("-vf"); $ffArgs.Add($filter)
$ffArgs.Add("-r"); $ffArgs.Add((Num $actualOutFps))
$ffArgs.Add("-fps_mode"); $ffArgs.Add("cfr")
$ffArgs.Add("-c:v"); $ffArgs.Add("libx264")
$ffArgs.Add("-preset"); $ffArgs.Add("medium")
$ffArgs.Add("-crf"); $ffArgs.Add($Crf.ToString())
$ffArgs.Add("-pix_fmt"); $ffArgs.Add("yuv420p")
$ffArgs.Add("-c:a"); $ffArgs.Add("copy")
$ffArgs.Add($OutputFile)

Write-Host ""
Write-Host "Склеиваю по $N кадров в один (режим наложения: $Mode)."
Write-Host ("Итоговая частота кадров: {0:0.###} к/с (запрошено {1})" -f $actualOutFps, $targetFpsVal)
if ($N -gt 1) {
    $tailFrames = $N - 1
    Write-Host "Если общее число кадров не делится на $N нацело, в конце может быть отброшено до $tailFrames кадров (меньше одного выходного кадра по времени)."
    if ($BlackLevel -gt 0) {
        Write-Host "Отсечка шума перед склейкой: $BlackLevel (0-255). Если картинка тускнеет там, где не должна — уменьшите (-BlackLevel), если фон всё ещё засвечивается — увеличьте."
    }
}
Write-Host "Результат: $OutputFile"
Write-Host ""

$ffExitCode = Invoke-NativePassthrough -Path $ffmpeg -ArgList $ffArgs
if ($ffExitCode -ne 0) {
    throw "ffmpeg завершился с ошибкой (код $ffExitCode)."
}

Write-Host ""
Write-Host "Готово: $OutputFile"
