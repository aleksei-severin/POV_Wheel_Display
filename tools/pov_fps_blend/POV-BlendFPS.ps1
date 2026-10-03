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
    прорисовок со звуковой дорожки: прошивка подаёт на пьезо чирп (свип 15 -> 20 кГц за
    15 мс, на заднем колесе — 20 -> 15) на каждом срабатывании датчика Холла, пока запитаны
    все шесть лучей, — то есть когда каждый из лучей проходит мимо магнита, 6 раз за оборот
    (-BeepsPerRev).

    Лучей шесть, и каждый красит свой сектор в 60 градусов одновременно с остальными, так
    что полный круг картинки готов ровно за 1/6 оборота — ровно за интервал между двумя
    соседними тиками. Поэтому каждый такой интервал склеивается в свой кадр. Склеивать
    больше нельзя: за целый оборот каждый луч проходит весь круг, и в кадр легли бы шесть
    прорисовок подряд — шесть секторов, наложенных друг на друга (на анимации это сразу
    видно). Длиннее интервал (медленное вращение) — больше кадров в склейке, короче
    (быстрое) — меньше. Результат — видео той же родной частоты кадров, что и исходник, где
    каждая прорисовка представлена своей засветкой, повторённой на столько кадров, сколько
    она реально заняла по времени.

    Чирпы на записи тихие, их громкость гуляет в пределах оборота (пьезо стоит на
    вращающейся плате, и два из шести за оборот почти не слышны), после каждого комната
    звенит отражениями, а щелчки механики громче самих чирпов. Поэтому детектор не
    пороговый: согласованный фильтр по форме чирпа в пяти полосах, трекер, который ищет
    сразу всю серию тиков с гладким периодом и достраивает пропущенные, и сглаживание
    (подробно — в комментарии перед $PovSource).

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
    ручного -TargetFps. Требует аудиодорожку с чирпами синхро-датчика (15-20 кГц, 15 мс,
    6 на оборот).

.PARAMETER SlowMo
    Замедленная съёмка (slow motion): во сколько раз файл медленнее реального времени.
    По умолчанию auto. Смартфон сохраняет slow motion, растягивая и видео, и звук: при
    замедлении в 4 раза кадры, снятые на 120 к/с, идут как 30 к/с, а чирп 15-20 кГц
    опускается до 3.75-5 кГц и длится 60 мс. В режиме auto скрипт ищет тики при замедлении
    1, 4, 8 и 2 (первым — то, что подсказывают метаданные файла, если подсказывают) и
    берёт то, при котором они нашлись. Можно задать число явно, например -SlowMo 4.
    Результат всегда в реальном времени: замедление снимается и с видео, и со звука
    (звук возвращается к исходной высоте). -Start/-Duration — по шкале исходного файла.

.PARAMETER ChirpLoHz
    Нижняя частота чирпа синхро-датчика, Гц (PIEZO_CHIRP_F_LO_HZ прошивки). По умолчанию 15000.

.PARAMETER ChirpHiHz
    Верхняя частота чирпа, Гц (PIEZO_CHIRP_F_HI_HZ). По умолчанию 20000. Свип идёт вверх или
    вниз — детектор ищет оба направления сам.

.PARAMETER ChirpMs
    Длительность чирпа, мс (PIEZO_CHIRP_US / 1000). По умолчанию 15.

.PARAMETER BeepFreq
    Только с -LegacyClickDetect: центр полосы, где искать щелчок, Гц. По умолчанию 2800.

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

.PARAMETER MinWindowFrames
    Окно склейки не короче стольких кадров, даже если прорисовка короче. По умолчанию 4.
    Камера пишет кадр не всё время между кадрами (выдержка короче 1/fps), и на медленной
    съёмке — 60 к/с, прорисовка в 1.8 кадра — склейка одной прорисовки остаётся с
    провалами между клиньями. Изображение колеса неподвижно, поэтому окно в несколько
    прорисовок закрывает их кадрами с другой фазой выдержки. Цена — анимация и движение
    камеры смазываются сильнее. На съёмке 120-240 к/с прорисовка и так длиннее, и
    параметр ни на что не влияет; 1 — склеивать строго по прорисовке.

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
    [double]$ChirpLoHz = 15000,
    [double]$ChirpHiHz = 20000,
    [double]$ChirpMs = 15,
    [double]$BeepFreq = 2800,
    [double]$MinRevMs = 80,
    [double]$MinRpm = 90,
    [double]$MinFps = 10,
    [int]$CheckerCell = 2,
    [int]$MinWindowFrames = 4,
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

# --- Детектор чирпов синхро-датчика и рендер (для -AudioSync) ---
#
# Прошивка подаёт на пьезо линейный чирп (PIEZO_CHIRP_*: 15 -> 20 кГц за 15 мс, плавные края
# по 1.5 мс; при rotation_dir < 0 — 20 -> 15) на каждом событии датчика Холла, пока запитаны
# все шесть лучей, — -BeepsPerRev раз за оборот. Что видно на записях (1_sweep.mp4,
# 2_sweep.mp4), и что из этого следует для детектора (код на C# ниже, PovChirp):
#   * У пьезо два резонанса, ~17.5 и ~19.5 кГц: почти вся энергия чирпа приходит двумя
#     горбами по ~2 мс, остальная часть свипа на 10-20 дБ тише, а фаза между горбами
#     зависит от отражений. Поэтому согласованный фильтр не один на весь чирп, а пять — по
#     куску на полосу в 1 кГц, и складываются их энергии: когерентный фильтр расщеплял пик
#     на лепестки через 3-7 мс, и время тика прыгало между ними.
#   * Шум перед фильтром отбеливается по частотам (20-й перцентиль за ±0.5 с — чирп проходит
#     каждую полосу за доли своего периода), полосы взвешиваются: поровну, «где полоса
#     звучит» (частота всплесков) и, вторым проходом, по найденным тикам. Срезанная
#     микрофоном или кодеком полоса получает вес 0 и не разбавляет остальные шумом.
#   * Отражения от стола и стен приходят через 4-7 мс: статистика сглаживается окном 4 мс,
#     прямой звук и эхо сливаются в один пик.
#   * Обратный шаблон (свип в другую сторону) отвечает и на настоящий чирп (30-40 %), а
#     щелчки — удары, хлопки, трещотка втулки — дают отклик обоим. Отсюда вычитание доли
#     обратного отклика и ослабление там, где есть энергия ниже полосы чирпа (6-13 кГц):
#     пьезо там молчит, широкополосный щелчок — нет.
#   * Два из шести чирпов за оборот почти не слышны (пьезо вращается и часть оборота
#     смотрит от камеры). Порог по каждому тику тут бессилен, поэтому трекер ищет сразу всю
#     серию: динамическое программирование по кандидатам с априорной гладкостью интервалов
#     (колесо не меняет скорость скачком), явными пропусками и штрафом за энергию на 1/2,
#     1/3 и 2/3 интервала — иначе ряд «каждый третий тик» без единого пропуска выигрывал у
#     полного.
#   * Трекер не должен протягивать серию через шум. На 4_sweep.mp4 (заднее колесо на ходу)
#     чирпы тонут в сплошном широкополосном шуме цепи и покрышки ~25 с из 35: слабые
#     кандидаты там находятся на любом интервале, и DP вёл ряд, начатый на чистом участке,
#     дальше, а период уползал с 40 до 130 мс. Тот же ряд вытеснял из луча правильный ряд
#     следующего чистого участка — склейка «работала в начале и переставала». Поэтому
#     (а) луч держит не 12 лучших состояний, а 12 лучших с разными периодами (BeamDiv:
#     ближе 3 % — дубль); (б) каждый трек проверяется на периодичность: автокорреляция
#     превышения статистики над порогом на лаге, равном локальному интервалу самого трека
#     (лаг идёт за скоростью — постоянный резал 1_sweep на разгоне), окно ±0.75 с.
#     Участок, где она ниже 0.2 дольше 1 с внутри трека (0.5 с на краю), вырезается, края
#     дочищаются до плотности настоящих тиков 3 из 6. Где чирпа не слышно, кадры идут без
#     склейки: это честнее, чем склейка по выдуманному периоду.
#   * Итоговые тики — локальная квадратичная регрессия по ±6 тикам: окно склейки должно
#     покрывать ровно 1/6 оборота, а это гладкая функция времени; разброс отдельных тиков
#     (эхо, неровная расстановка датчиков) — шум.
# Проверено на 1_sweep.mp4 / 2_sweep.mp4 / 3_sweep.mp4 (тики по всей длине отрисовки) и на
# 1/2_sweep с подмешанным белым шумом: +10 дБ к фону полосы — 44-69 % тиков на месте;
# +20 дБ — трека нет, но и ложного нет. Срез полосы выше 17 кГц — трек находится по
# остатку 15-17 кГц, хотя и не целиком. 4_sweep.mp4 (замедленная ×4) — три трека по чистым
# участкам (1.3-5.4, 18.4-21.1, 27.0-29.1 с реального времени), остальное без склейки.
#
# Рендер — тоже свой (PovRender): ffmpeg декодирует ролик ОДИН раз в поток кадров
# постоянной частоты, C# склеивает окна и пропускает кадры вне отрисовки как есть (потоком,
# по одному кадру — копить в памяти весь отрезок без тиков на 4K не хватит никакой ОЗУ),
# второй ffmpeg кодирует и добавляет звук. Прежняя схема — два процесса ffmpeg на каждую
# склейку — при прорисовке на каждый тик давала сотни запусков с декодированием HEVC от
# ключевого кадра на каждый.
$PovSource = @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;

// Детектор чирпов синхро-датчика (прошивка: include/config.h, PIEZO_CHIRP_*): согласованный
// фильтр по форме чирпа + трекер серии тиков + сглаживание. Перенесён в Android строка в строку:
// android/…/povvideo/PovChirp.kt — правки делать в обоих.
public static class PovChirp {
    // ---- сигнал прошивки ----
    public static double FLo = 15000, FHi = 20000, Dur = 0.015, Fade = 0.0015;

    // ---- настройки ----
    public static int Seg = 5;                  // кусков шаблона (полос по (FHi−FLo)/Seg)
    public static double SmoothStatSec = 0.004; // сглаживание статистики (прямой звук + эхо)
    public static double NmsSec = 0.005;        // радиус подавления соседних пиков
    public static double EvRef = 2.5;           // уровень статистики, при котором тик «нейтрален» для трекера
    public static double OppThr = 5.0, OppK = 0.5; // обратный отклик выше OppThr — вычитается его доля OppK
    public static double ClickLo = 6000, ClickHi = 13000, ClickThr = 4.0;
    public static double MissPen = 0.4;         // штраф за пропущенный тик
    public static double StartPen = 2.0;        // штраф за начало трека
    public static double TransBonus = 0.4;      // за переход (уравнивает полную частоту с кратной)
    public static double IgnoreTolSec = 0.0015, IgnoreK = 1.0;
    public static int MaxSkip = 8;              // пропусков подряд внутри трека
    public static int Beam = 12;                // состояний трекера на кандидата (по одному на период)
    public static double BeamDiv = 0.03;        // интервалы ближе 3 % — один период
    public static double JitterSec = 0.0005;    // разброс времени тика
    public static double AccTyp = 6.0;          // типичное угловое ускорение колеса, рад/с²
    public static double HallSpread = 0.012;    // разброс интервалов от расстановки датчиков
    public static double MinScore = 10.0;       // минимальный счёт трека
    public static double OppDirK = 4.0;         // трек обратного направления — счёт ≥ OppDirK·MinScore
    public static int SmoothHalf = 6;           // сглаживание тиков: ±тиков
    public static double BridgeSec = 2.0;       // сращивание соседних треков

    public class Track {
        public double[] Times; public bool[] Real; public int Dir;
        public double Score; public double SnrDb; public int Detected; public double ResidMs;
    }
    public class Result {
        public List<Track> Tracks = new List<Track>();
        public int Candidates, Detected;
        public double[] Weights;      // веса полос снизу вверх
        public string Hyp = "";       // какие веса выиграли
        public double BandLo, BandHi; // где чирп слышен (вес ≥ 0.1), Гц
        public string Diag = "";
    }

    // ================================================================ FFT
    static void Fft(double[] re, double[] im, int n, bool inverse) {
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
            int h = len >> 1;
            for (int k = 0; k < h; k++) {
                double cr = Math.Cos(ang * k), ci = Math.Sin(ang * k);
                for (int i = k; i < n; i += len) {
                    int b = i + h;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[i] - xr; im[b] = im[i] - xi; re[i] += xr; im[i] += xi;
                }
            }
        }
        if (inverse) { double s = 1.0 / n; for (int i = 0; i < n; i++) { re[i] *= s; im[i] *= s; } }
    }

    // ================================================================ базовая полоса
    // Полоса чирпа сдвигается к нулю, фильтруется и прореживается до ~8 кГц: дальше всё
    // считается на комплексном сигнале в 6 раз реже исходного.
    class Band {
        public int Sr, D, L, Ns, Hs; public double Fc, Fsb, HiEff, T0, WinPow; public double[] H, Win;
    }

    static Band MakeBand(int sr) {
        var b = new Band();
        b.Sr = sr;
        b.HiEff = Math.Min(FHi, 0.47 * sr);
        if (b.HiEff - FLo < 1000) return null;
        b.Fc = 0.5 * (FLo + b.HiEff);
        double half = 0.5 * (b.HiEff - FLo) + 500;
        b.D = Math.Max(1, (int)Math.Floor(sr / (2 * half + 2000)));
        b.Fsb = (double)sr / b.D;
        // ФНЧ до прореживания: пропускание ±half, задерживание с Fsb − half (там уже наложение)
        double tw = Math.Max(500, b.Fsb - 2 * half);
        int L = (int)Math.Ceiling(3.5 * sr / tw) | 1;
        double fcut = 0.5 * b.Fsb / sr;
        b.L = L; b.H = new double[L];
        double sum = 0;
        for (int k = 0; k < L; k++) {
            double m = k - (L - 1) / 2.0;
            double sinc = m == 0 ? 2 * fcut : Math.Sin(2 * Math.PI * fcut * m) / (Math.PI * m);
            b.H[k] = sinc * (0.54 - 0.46 * Math.Cos(2 * Math.PI * k / (L - 1)));
            sum += b.H[k];
        }
        for (int k = 0; k < L; k++) b.H[k] /= sum;
        b.T0 = (L - 1) / 2.0 / sr;
        // короткое окно (~2 мс) для оценки шума по частотам
        int ns = 8; while (ns * 2 <= b.Fsb * 0.0025) ns *= 2;
        b.Ns = ns; b.Hs = ns / 2; b.Win = new double[ns]; b.WinPow = 0;
        for (int k = 0; k < ns; k++) { b.Win[k] = 0.5 - 0.5 * Math.Cos(2 * Math.PI * (k + 0.5) / ns); b.WinPow += b.Win[k] * b.Win[k]; }
        return b;
    }

    // x — nch каналов вперемешку, берётся канал ch.
    static void Demod(float[] x, int nch, int ch, Band b, out float[] zr, out float[] zi) {
        int n = x.Length / nch;
        int m = (n - b.L) / b.D;
        if (m < 1) { zr = new float[0]; zi = new float[0]; return; }
        zr = new float[m]; zi = new float[m];
        double w = 2 * Math.PI * b.Fc / b.Sr;
        int chunk = 1 << 16;
        var mr = new double[chunk + b.L]; var mi = new double[chunk + b.L];
        for (int o = 0; o < m; ) {
            int cnt = Math.Min(chunk / b.D, m - o);
            int s0 = o * b.D, len = (cnt - 1) * b.D + b.L;
            for (int k = 0; k < len; k++) {
                int idx = s0 + k; double v = x[idx * nch + ch]; double ph = w * idx;
                mr[k] = v * Math.Cos(ph); mi[k] = -v * Math.Sin(ph);
            }
            for (int q = 0; q < cnt; q++) {
                double ar = 0, ai = 0; int bs = q * b.D;
                for (int k = 0; k < b.L; k++) { double h = b.H[k]; ar += h * mr[bs + k]; ai += h * mi[bs + k]; }
                zr[o + q] = (float)(2 * ar); zi[o + q] = (float)(2 * ai);
            }
            o += cnt;
        }
    }

    // ================================================================ шум по частотам
    // Фон по бинам короткого окна: 20-й перцентиль мощности за ±0.5 с на сетке ~64 мс.
    // Чирп проходит каждый бин за доли его периода, поэтому нижний перцентиль — шум.
    class Noise { public int Bins, GridStep, GridN; public double[] Floor; }

    static Noise MeasureNoise(float[] zr, float[] zi, Band b) {
        var nz = new Noise();
        int ns = b.Ns, hs = b.Hs;
        int frames = Math.Max(0, (zr.Length - ns) / hs + 1);
        nz.Bins = ns;
        var P = new float[frames * ns];
        var re = new double[ns]; var im = new double[ns];
        for (int f = 0; f < frames; f++) {
            int o = f * hs;
            for (int k = 0; k < ns; k++) { re[k] = zr[o + k] * b.Win[k]; im[k] = zi[o + k] * b.Win[k]; }
            Fft(re, im, ns, false);
            for (int k = 0; k < ns; k++) P[f * ns + k] = (float)(re[k] * re[k] + im[k] * im[k]);
        }
        double frameSec = hs / b.Fsb;
        int win = (int)Math.Round(0.5 / frameSec);
        nz.GridStep = Math.Max(1, (int)Math.Round(0.064 / frameSec));
        nz.GridN = frames / nz.GridStep + 1;
        nz.Floor = new double[nz.GridN * ns];
        var tmp = new List<float>();
        for (int g = 0; g < nz.GridN; g++) {
            int c = g * nz.GridStep, a = Math.Max(0, c - win), e = Math.Min(frames - 1, c + win);
            for (int k = 0; k < ns; k++) {
                tmp.Clear();
                for (int f = a; f <= e; f++) tmp.Add(P[f * ns + k]);
                double v = 0;
                if (tmp.Count > 0) { tmp.Sort(); v = tmp[(int)(0.2 * (tmp.Count - 1))]; }
                nz.Floor[g * ns + k] = v / 0.223 / b.WinPow;   // σ² на отсчёт (20-й перцентиль экспоненты — 0.223 среднего)
            }
        }
        return nz;
    }

    // ================================================================ шаблон
    static double Envelope(double t) {
        if (t <= 0 || t >= Dur) return 0;
        if (t < Fade) return 0.5 - 0.5 * Math.Cos(Math.PI * t / Fade);
        if (t > Dur - Fade) return 0.5 - 0.5 * Math.Cos(Math.PI * (Dur - t) / Fade);
        return 1;
    }

    // Комплексный шаблон в базовой полосе; фаза — как в прошивке: φ(t) = f0·t + c·t²/2.
    static void Template(Band b, bool up, out double[] tr, out double[] ti) {
        int lt = (int)Math.Ceiling(Dur * b.Fsb) + 1;
        tr = new double[lt]; ti = new double[lt];
        double f0 = up ? FLo : FHi, c = (up ? 1 : -1) * (FHi - FLo) / Dur;
        for (int n = 0; n < lt; n++) {
            double t = n / b.Fsb;
            double ph = 2 * Math.PI * ((f0 - b.Fc) * t + 0.5 * c * t * t);
            double a = (f0 + c * t) > b.HiEff ? 0 : Envelope(t);
            tr[n] = a * Math.Cos(ph); ti[n] = a * Math.Sin(ph);
        }
    }

    // Полоса j (снизу вверх) — абсолютные частоты.
    static void BandOf(int j, out double fa, out double fb) {
        double w = (FHi - FLo) / Seg; fa = FLo + j * w; fb = fa + w;
    }

    // ================================================================ согласованный фильтр по кускам
    // Шаблон режется по времени на Seg кусков — у линейного чирпа это полосы по (FHi−FLo)/Seg.
    // Каждый кусок — свой согласованный фильтр на отбелённом сигнале, а складываются ЭНЕРГИИ
    // кусков, не комплексные отклики. Причина — пьезо: почти вся энергия чирпа приходит двумя
    // узкими «горбами» у его резонансов (~17.5 и ~19.5 кГц), и фаза между ними зависит от
    // отражений в комнате. Когерентный фильтр по всему чирпу складывал их то в плюс, то в
    // минус — пик расщеплялся на лепестки через 3–7 мс, и тик «прыгал» между ними.
    // Результат — out[dir·Seg + j] (dir 0 — вверх, j — полоса снизу вверх): энергия куска,
    // нормированная к шуму (чистый шум — в среднем 1); null — полоса выше записи.
    static void MatchSeg(float[] zr, float[] zi, Band b, Noise nz, float[][] acc) {
        int n = zr.Length;
        double[] t0r, t0i, t1r, t1i;
        Template(b, true, out t0r, out t0i);
        Template(b, false, out t1r, out t1i);
        int lt = t0r.Length;
        int N = 1024; while (N < 4 * lt) N *= 2;
        int B = N - lt + 1;
        var Tr = new double[2 * Seg][]; var Ti = new double[2 * Seg][];
        for (int dir = 0; dir < 2; dir++) {
            double[] sr = dir == 0 ? t0r : t1r, si = dir == 0 ? t0i : t1i;
            for (int m = 0; m < Seg; m++) {
                int j = dir == 0 ? m : Seg - 1 - m;     // кусок m по времени — полоса j по частоте
                int q = dir * Seg + j;
                double fa, fb; BandOf(j, out fa, out fb);
                if (fb > b.HiEff + 1) continue;
                Tr[q] = new double[N]; Ti[q] = new double[N];
                int a = (int)Math.Round(m * (double)lt / Seg), e = (int)Math.Round((m + 1) * (double)lt / Seg);
                for (int k = a; k < e; k++) { Tr[q][k] = sr[k]; Ti[q][k] = si[k]; }
                Fft(Tr[q], Ti[q], N, false);
                if (acc[q] == null) acc[q] = new float[n];
            }
        }
        int ns = nz.Bins;
        var u0 = new int[N]; var uf = new double[N];
        for (int k = 0; k < N; k++) {
            double f = (k < N / 2 ? k : k - N) * b.Fsb / N;
            double u = f / b.Fsb * ns; if (u < 0) u += ns;
            int a = (int)Math.Floor(u); uf[k] = u - a; u0[k] = a % ns;
        }
        var Zr = new double[N]; var Zi = new double[N]; var Yr = new double[N]; var Yi = new double[N];
        var G = new double[N]; var sig = new double[ns]; var med = new double[ns];
        double frameSec = b.Hs / b.Fsb;
        for (int s = 0; s < n; s += B) {
            int cnt = Math.Min(B, n - s);
            Array.Clear(Zr, 0, N); Array.Clear(Zi, 0, N);
            for (int k = 0; k < N && s + k < n; k++) { Zr[k] = zr[s + k]; Zi[k] = zi[s + k]; }
            Fft(Zr, Zi, N, false);
            // шум в середине блока, с полом (цифровая тишина там, где кодек срезал полосу)
            int g = (int)Math.Round((s + cnt / 2.0) / b.Fsb / frameSec / nz.GridStep);
            g = Math.Max(0, Math.Min(nz.GridN - 1, g));
            for (int k = 0; k < ns; k++) { sig[k] = nz.Floor[g * ns + k]; med[k] = sig[k]; }
            Array.Sort(med);
            double floorMin = Math.Max(1e-30, med[ns / 2] * 1e-3);
            for (int k = 0; k < ns; k++) if (sig[k] < floorMin) sig[k] = floorMin;
            for (int k = 0; k < N; k++) {
                int a = u0[k], c = (a + 1) % ns;
                G[k] = 1.0 / (sig[a] * (1 - uf[k]) + sig[c] * uf[k]);
            }
            for (int q = 0; q < 2 * Seg; q++) {
                if (Tr[q] == null) continue;
                double[] tr = Tr[q], ti = Ti[q];
                double norm = 0;
                for (int k = 0; k < N; k++) {
                    // Z · conj(T) · G; шум на выходе — Σ|T|²·G / N
                    Yr[k] = (Zr[k] * tr[k] + Zi[k] * ti[k]) * G[k];
                    Yi[k] = (Zi[k] * tr[k] - Zr[k] * ti[k]) * G[k];
                    norm += (tr[k] * tr[k] + ti[k] * ti[k]) * G[k];
                }
                norm /= N;
                if (norm <= 0) continue;
                Fft(Yr, Yi, N, true);
                float[] dst = acc[q];
                double inv = 1.0 / norm;
                for (int k = 0; k < cnt; k++) dst[s + k] += (float)((Yr[k] * Yr[k] + Yi[k] * Yi[k]) * inv);
            }
        }
    }

    // ================================================================ щелчки
    // Удар, хлопок, стук стойки, трещотка втулки — широкополосные: дают скачок энергии и НИЖЕ
    // полосы чирпа, где пьезо не звучит вовсе. Отношение энергии ClickLo–ClickHi к её фону
    // (20-й перцентиль за ±0.5 с), максимум по всей длине возможного чирпа — на сетке отсчётов
    // базовой полосы. Где оно выше ClickThr, отклик фильтра делится на него.
    static double[] ClickSpan(float[] x, int nch, int sr, Band b, int nOut) {
        if (ClickHi > 0.45 * sr) return null;
        int frame = Math.Max(1, (int)Math.Round(0.001 * sr));      // кадр 1 мс
        int nf = x.Length / nch / frame;
        if (nf < 10) return null;
        var en = new double[nf];
        double a1 = Math.Exp(-2 * Math.PI * ClickHi / sr), a0 = Math.Exp(-2 * Math.PI * ClickLo / sr);
        for (int ch = 0; ch < nch; ch++) {
            double h1 = 0, h2 = 0, l1 = 0, l2 = 0;
            for (int f = 0; f < nf; f++) {
                double s = 0;
                for (int k = 0; k < frame; k++) {
                    double v = x[(f * frame + k) * nch + ch];
                    h1 = a1 * h1 + (1 - a1) * v; h2 = a1 * h2 + (1 - a1) * h1;   // ФНЧ ClickHi (2 порядок)
                    l1 = a0 * l1 + (1 - a0) * v; l2 = a0 * l2 + (1 - a0) * l1;   // ФНЧ ClickLo
                    double bp = h2 - l2;
                    s += bp * bp;
                }
                en[f] += s;
            }
        }
        int win = 500, step = 64, gn = nf / step + 1;
        var floor = new double[gn]; var tmp = new List<double>();
        for (int g = 0; g < gn; g++) {
            int c = g * step; tmp.Clear();
            for (int f = Math.Max(0, c - win); f <= Math.Min(nf - 1, c + win); f += 2) tmp.Add(en[f]);
            tmp.Sort(); floor[g] = Math.Max(1e-30, tmp[(int)(0.2 * (tmp.Count - 1))] / 0.223);
        }
        var ratio = new float[nf];
        for (int f = 0; f < nf; f++) ratio[f] = (float)(en[f] / floor[Math.Min(gn - 1, f / step)]);
        int span = (int)Math.Ceiling(Dur * 1000) + 4;
        double[] mx = SlidingMax(ratio, span / 2 + 1);
        var res = new double[nOut];
        for (int i = 0; i < nOut; i++) {
            double t = i / b.Fsb + b.T0;
            int f = (int)Math.Round(t * 1000 + span / 2.0 - 2);
            res[i] = mx[Math.Max(0, Math.Min(nf - 1, f))];
        }
        return res;
    }

    // Скользящий максимум в окне ±w.
    static double[] SlidingMax(float[] a, int w) {
        int n = a.Length; var res = new double[n];
        var dq = new int[n]; int h = 0, tl = 0, next = 0;
        for (int i = 0; i < n; i++) {
            int hi = Math.Min(n - 1, i + w);
            while (next <= hi) { while (tl > h && a[dq[tl - 1]] <= a[next]) tl--; dq[tl++] = next; next++; }
            while (dq[h] < i - w) h++;
            res[i] = a[dq[h]];
        }
        return res;
    }

    // ================================================================ статистика направления
    // Взвешенная сумма энергий полос (веса по полосам снизу вверх): шум — в среднем 1.
    static float[] Combine(float[][] seg, double[] w, int dir) {
        float[] res = null; double ws = 0;
        for (int j = 0; j < Seg; j++) {
            float[] a = seg[(dir > 0 ? 0 : Seg) + j];
            if (a == null || w[j] <= 0) continue;
            if (res == null) res = new float[a.Length];
            float wj = (float)w[j];
            for (int i = 0; i < a.Length; i++) res[i] += wj * a[i];
            ws += w[j];
        }
        if (res == null) return null;
        // Шум взвешенной суммы тем «хвостатее», чем меньше полос реально участвует:
        // M_eff = (Σw)²/Σw². Отклонение от среднего пересчитывается к случаю равных весов по
        // всем полосам, чтобы один порог EvRef значил одно и то же при любых весах — иначе
        // гипотеза с весом на одной полосе выигрывала бы за счёт шумовых пиков.
        double w2 = 0; int nAll = 0;
        for (int j = 0; j < Seg; j++) { if (seg[(dir > 0 ? 0 : Seg) + j] == null) continue; nAll++; if (w[j] > 0) w2 += w[j] * w[j]; }
        double meff = ws * ws / w2;
        float k = (float)Math.Sqrt(meff / Math.Max(1, nAll));
        float inv = (float)(1 / ws);
        for (int i = 0; i < res.Length; i++) res[i] = 1 + (res[i] * inv - 1) * k;
        return res;
    }

    // Статистика для трекера: прямой отклик минус часть обратного (из-за двух резонансов пьезо
    // и эха обратный шаблон отвечает и на настоящий чирп — 30–40 % прямого в пределах длины
    // чирпа, и из этой «тени» трекер обратного направления собирал целые ложные треки),
    // ослабленная на щелчках и сглаженная окном SmoothStatSec — прямой звук и отражения от
    // стола и стен (4–7 мс позже) сливаются в один пик.
    // Порог «нейтрального» тика EvRef общий: статистика нормирована к шуму. Подстраивать его по
    // самой записи пробовал — медиана + k·MAD, медиана пиков: шум реальных записей далёк от
    // гауссова, тики сдвигают оценку, и порог то душил настоящие тики, то пропускал треки из
    // чистого шума.
    static double[] Statistic(float[] z, float[] opp, double[] click, Band b) {
        int n = z.Length;
        int ow = (int)Math.Ceiling(Dur * b.Fsb);
        double[] om = SlidingMax(opp, ow);
        var e = new double[n];
        for (int i = 0; i < n; i++) {
            e[i] = z[i] - OppK * Math.Max(0, om[i] - OppThr);
            if (click != null) { double c = click[i]; if (c > ClickThr) e[i] *= ClickThr / c; }
        }
        int hw = Math.Max(0, (int)Math.Round(0.5 * SmoothStatSec * b.Fsb));
        var es = new double[n];
        double acc = 0; int cnt = 0;
        for (int i = 0; i < Math.Min(n, hw); i++) { acc += e[i]; cnt++; }
        for (int i = 0; i < n; i++) {
            if (i + hw < n) { acc += e[i + hw]; cnt++; }
            if (i - hw - 1 >= 0) { acc -= e[i - hw - 1]; cnt--; }
            es[i] = acc / cnt;
        }
        return es;
    }

    // ================================================================ кандидаты
    class Cand { public double T, E, V; public int Idx; }

    static double Ev(double e, double evRef) { return Math.Max(-1.5, Math.Min(4.0, Math.Log(Math.Max(e, 1e-9) / evRef))); }

    static List<Cand> Candidates(double[] es, Band b, double evRef) {
        int n = es.Length;
        int r = Math.Max(1, (int)Math.Round(NmsSec * b.Fsb));
        double thr = 0.8 * evRef;
        var res = new List<Cand>();
        for (int i = 1; i < n - 1; i++) {
            double v = es[i];
            if (v < thr || v < es[i - 1] || v < es[i + 1]) continue;
            bool mx = true;
            for (int j = Math.Max(0, i - r); j <= Math.Min(n - 1, i + r); j++) {
                if (es[j] > v || (es[j] == v && j < i)) { mx = false; break; }
            }
            if (!mx) continue;
            double den = es[i - 1] - 2 * v + es[i + 1];
            double d = den < 0 ? 0.5 * (es[i - 1] - es[i + 1]) / den : 0;
            if (d < -0.5) d = -0.5; if (d > 0.5) d = 0.5;
            var c = new Cand();
            c.T = (i + d) / b.Fsb + b.T0; c.E = v / evRef; c.Idx = i; c.V = Ev(v, evRef);
            res.Add(c);
        }
        return res;
    }

    // ================================================================ трекер
    // Динамическое программирование по кандидатам: путь максимизирует сумму свидетельств тиков
    // плюс априорную гладкость интервалов (колесо не меняет скорость скачком). Состояние —
    // (тик, предыдущий тик), пропуски тиков — явные переходы со штрафом. Счёт не ниже нуля в
    // начале (StartPen) — треки сами находят начало и конец; лучший трек вынимается, его время
    // занимается, и всё повторяется.
    class St { public int Cand, Prev, Skip, Ticks; public double I, S; }

    static double Sigma(double I, int m) {
        // разброс отношения соседних интервалов: время тиков + ускорение колеса + датчики
        double sj = 1.41 * JitterSec / (I * (m + 1));
        double sa = AccTyp * I * I * (m + 1) / (Math.PI / 3);
        return Math.Sqrt(sj * sj + sa * sa + HallSpread * HallSpread);
    }

    static double EvAt(double[] es, Band b, double evRef, double t) {
        int c = (int)Math.Round((t - b.T0) * b.Fsb), r = (int)Math.Round(IgnoreTolSec * b.Fsb);
        double mx = 0;
        for (int i = Math.Max(0, c - r); i <= Math.Min(es.Length - 1, c + r); i++) if (es[i] > mx) mx = es[i];
        return Ev(mx, evRef);
    }

    // Энергия, которую переход t0 → t0 + (m+1)·I оставил без внимания: тик-подобные пики на
    // 1/2, 1/3 и 2/3 каждого подынтервала. Без этого трекер охотно брал кратный период: два
    // из шести чирпов за оборот приходят заметно тише (пьезо крутится вместе с колесом и часть
    // оборота смотрит от камеры), и ряд «каждый третий тик» выглядел безупречным — ни одного
    // пропуска, — тогда как полный ряд платил за два пропуска на оборот.
    static double Ignored(double[] es, Band b, double evRef, double t0, double I, int m) {
        double s = 0;
        for (int k = 0; k <= m; k++) {
            double bs = t0 + k * I;
            s += Math.Max(0, EvAt(es, b, evRef, bs + I / 2)) + Math.Max(0, EvAt(es, b, evRef, bs + I / 3))
               + Math.Max(0, EvAt(es, b, evRef, bs + 2 * I / 3));
        }
        return IgnoreK * s;
    }

    static List<int[]> TrackAll(List<Cand> cs, double[] es, Band b, double evRef, double minP, double maxP, int minTicks, out List<double> scores) {
        var paths = new List<int[]>();
        scores = new List<double>();
        int K = cs.Count;
        var alive = new bool[K];
        for (int i = 0; i < K; i++) alive[i] = true;
        for (int iter = 0; iter < 64; iter++) {
            var states = new List<St>();
            var beam = new List<int>[K];
            int best = -1; double bestS = double.NegativeInfinity;
            for (int j = 0; j < K; j++) {
                beam[j] = new List<int>();
                if (!alive[j]) continue;
                var cand = new List<St>();
                var st0 = new St(); st0.Cand = j; st0.Prev = -1; st0.Ticks = 1; st0.S = cs[j].V - StartPen;
                cand.Add(st0);
                for (int i = j - 1; i >= 0; i--) {
                    double dt = cs[j].T - cs[i].T;
                    if (dt > (MaxSkip + 1) * maxP) break;
                    if (!alive[i] || dt < 0.8 * minP) continue;
                    foreach (int si in beam[i]) {
                        St p = states[si];
                        if (p.I == 0) {
                            if (dt < minP || dt > maxP) continue;
                            var ns = new St(); ns.Cand = j; ns.Prev = si; ns.Ticks = p.Ticks + 1; ns.I = dt;
                            ns.S = p.S + cs[j].V - 1.0 - Ignored(es, b, evRef, cs[i].T, dt, 0);
                            cand.Add(ns);
                            continue;
                        }
                        int m = (int)Math.Round(dt / p.I) - 1;
                        if (m < 0 || m > MaxSkip) continue;
                        double I = dt / (m + 1);
                        if (I < minP || I > maxP * 1.25) continue;
                        double q = Math.Log(I / p.I) / Sigma(p.I, m);
                        if (Math.Abs(q) > 8) continue;
                        var s2 = new St(); s2.Cand = j; s2.Prev = si; s2.Skip = m; s2.Ticks = p.Ticks + m + 1; s2.I = I;
                        s2.S = p.S + cs[j].V + TransBonus - m * MissPen - 1.5 * Math.Log(1 + q * q / 3)
                             - Ignored(es, b, evRef, cs[i].T, I, m);
                        cand.Add(s2);
                    }
                }
                // порядок однозначный и при равном счёте — тот же, что у порта на Kotlin
                cand.Sort((p1, p2) => p2.S != p1.S ? p2.S.CompareTo(p1.S) : p1.Prev != p2.Prev ? p1.Prev.CompareTo(p2.Prev) : p1.Skip.CompareTo(p2.Skip));
                // Луч — лучшее состояние на каждый период (интервалы ближе BeamDiv считаются одним),
                // а не просто Beam лучших. Иначе продолжения уже набравшего очки ряда — пусть
                // мусорного, с чужим периодом — занимали все места, и новый ряд с настоящим периодом
                // выбрасывался на старте: на 4_sweep.mp4 чистый участок 18–21 с (48 мс) шёл трекером
                // через ~80 мс, продолжая шум с 15.7 с, хотя сам по себе давал вдвое больший счёт.
                int taken = 0;
                for (int q = 0; q < cand.Count && taken < Beam; q++) {
                    St s = cand[q];
                    bool dup = false;
                    if (s.I > 0) foreach (int si in beam[j]) { double qi = states[si].I; if (qi > 0 && Math.Abs(Math.Log(s.I / qi)) < BeamDiv) { dup = true; break; } }
                    if (dup) continue;
                    states.Add(s);
                    beam[j].Add(states.Count - 1);
                    taken++;
                    if (s.S > bestS) { bestS = s.S; best = states.Count - 1; }
                }
            }
            if (best < 0 || bestS < MinScore) break;
            var path = new List<int>();
            for (int si = best; si >= 0; si = states[si].Prev) path.Add(si);
            path.Reverse();
            var tickIdx = new List<int>();
            foreach (int si in path) { tickIdx.Add(states[si].Cand); tickIdx.Add(states[si].Skip); }
            int first = states[path[0]].Cand, last = states[path[path.Count - 1]].Cand;
            double ta = cs[first].T - 0.5 * minP, tb = cs[last].T + 0.5 * minP;
            for (int i = 0; i < K; i++) if (cs[i].T >= ta && cs[i].T <= tb) alive[i] = false;
            if (states[best].Ticks - 1 < minTicks) continue;
            paths.Add(tickIdx.ToArray());
            scores.Add(bestS);
        }
        return paths;
    }

    // ================================================================ сглаживание
    // Путь трекера → равномерная по углу сетка тиков: локальная взвешенная квадратичная
    // регрессия времени по номеру тика (±SmoothHalf тиков) с отсевом выбросов. Окно склейки
    // должно покрывать ровно 1/6 оборота, а это гладкая функция времени: разброс отдельных
    // тиков (эхо, неровная расстановка датчиков) — шум, а не сигнал.
    class RawTrack { public int[] N, Idx; public double[] T, W, E; public int Dir; public double Score; }

    static RawTrack FromPath(int[] path, List<Cand> cs, int dir, double score) {
        var rt = new RawTrack(); int k = path.Length / 2;
        rt.N = new int[k]; rt.T = new double[k]; rt.W = new double[k]; rt.E = new double[k]; rt.Idx = new int[k];
        int n = 0;
        for (int i = 0; i < k; i++) {
            if (i > 0) n += path[2 * i + 1] + 1;
            Cand c = cs[path[2 * i]];
            rt.N[i] = n; rt.T[i] = c.T; rt.E[i] = c.E; rt.W[i] = Math.Min(c.E, 40.0); rt.Idx[i] = c.Idx;
        }
        rt.Dir = dir; rt.Score = score;
        return rt;
    }

    static double[] Fit(RawTrack rt, double[] rw, int n) {
        int H = SmoothHalf;
        double s0 = 0, s1 = 0, s2 = 0, s3 = 0, s4 = 0, y0 = 0, y1 = 0, y2 = 0; int cnt = 0;
        for (int k = 0; k < rt.N.Length; k++) {
            int d = rt.N[k] - n;
            if (d < -H || d > H) continue;
            double u = Math.Abs(d) / (H + 1.0), tc = 1 - u * u * u, w = tc * tc * tc * rt.W[k] * rw[k];
            if (w <= 0) continue;
            double y = rt.T[k];
            s0 += w; s1 += w * d; s2 += w * d * d; s3 += w * d * d * d; s4 += w * d * d * d * d;
            y0 += w * y; y1 += w * d * y; y2 += w * d * d * y; cnt++;
        }
        if (cnt >= 4) {
            double det = s0 * (s2 * s4 - s3 * s3) - s1 * (s1 * s4 - s3 * s2) + s2 * (s1 * s3 - s2 * s2);
            if (Math.Abs(det) > 1e-12 * Math.Max(1, s0 * s2 * s4)) {
                double a = (y0 * (s2 * s4 - s3 * s3) - s1 * (y1 * s4 - s3 * y2) + s2 * (y1 * s3 - s2 * y2)) / det;
                return new double[] { a };
            }
        }
        if (cnt >= 2) {
            double det = s0 * s2 - s1 * s1;
            if (det > 1e-12) return new double[] { (y0 * s2 - s1 * y1) / det };
        }
        return null;
    }

    static Track Smooth(RawTrack rt) {
        int k = rt.N.Length;
        var rw = new double[k];
        for (int i = 0; i < k; i++) rw[i] = 1;
        for (int it = 0; it < 3; it++) {
            var ab = new double[k];
            for (int i = 0; i < k; i++) { double[] f = Fit(rt, rw, rt.N[i]); ab[i] = f == null ? 0 : Math.Abs(rt.T[i] - f[0]); }
            var srt = (double[])ab.Clone(); Array.Sort(srt);
            double s = Math.Max(1.4826 * srt[k / 2], JitterSec);
            for (int i = 0; i < k; i++) { double u = ab[i] / (6 * s); rw[i] = u >= 1 ? 0 : (1 - u * u) * (1 - u * u); }
        }
        int n0 = rt.N[0], n1 = rt.N[k - 1];
        var tr = new Track();
        tr.Times = new double[n1 - n0 + 1]; tr.Real = new bool[n1 - n0 + 1];
        int ki = 0;
        for (int n = n0; n <= n1; n++) {
            double[] f = Fit(rt, rw, n);
            double t;
            if (f != null) t = f[0];
            else {
                while (ki + 1 < k && rt.N[ki + 1] <= n) ki++;
                int kb = Math.Min(k - 1, ki + 1);
                t = rt.N[kb] == rt.N[ki] ? rt.T[ki] : rt.T[ki] + (rt.T[kb] - rt.T[ki]) * (n - rt.N[ki]) / (double)(rt.N[kb] - rt.N[ki]);
            }
            tr.Times[n - n0] = t;
        }
        for (int i = 1; i < tr.Times.Length; i++) if (tr.Times[i] <= tr.Times[i - 1]) tr.Times[i] = tr.Times[i - 1] + 1e-4;
        double esum = 0, rs = 0; int det = 0;
        for (int i = 0; i < k; i++) {
            if (rw[i] <= 0) continue;
            tr.Real[rt.N[i] - n0] = true; esum += rt.E[i]; det++;
            double d = rt.T[i] - tr.Times[rt.N[i] - n0]; rs += d * d;
        }
        tr.Dir = rt.Dir; tr.Score = rt.Score; tr.Detected = det;
        tr.SnrDb = det > 0 ? 10 * Math.Log10(esum / det) : 0;
        tr.ResidMs = det > 0 ? Math.Sqrt(rs / det) * 1000 : 0;
        return tr;
    }

    // ================================================================ проверка периодичностью
    // Трекер находит ряд и в чистом шуме: кандидатов там десятки в секунду, и цепочку,
    // подходящую под плавный период, из них собрать можно, а слабые шумовые пики дают свой
    // небольшой плюс к счёту. На 4_sweep.mp4 (заднее колесо: цепь и шина заглушили чирпы на
    // ~25 с из 35) он так тянул трек через 10 с шума, период по дороге уплывал от 40 до
    // 130 мс, и склейка шла с чужими окнами. Ни сила отдельных тиков, ни их плотность настоящий
    // слабый ряд от такого не отличают: на быстром вращении (1–3_sweep.mp4, 30–35 мс) тики так
    // же слабы, а найдено их так же 40–55 %. Отличает периодичность самого сигнала:
    // автокорреляция статистики (окно ±PerWinSec) на собственном интервале трека — у
    // настоящего ряда 0.3–0.7, у шума −0.3…0.16. Участок, где она ниже PerMin дольше
    // PerCutSec, вырезается; такие же края длиннее PerEdgeSec обрезаются.
    public static double PerMin = 0.2, PerWinSec = 0.75, PerStepSec = 0.25, PerCutSec = 1.0, PerEdgeSec = 0.5;

    static double[] Excess(double[] es, Band b) {
        int step = Math.Max(1, (int)Math.Round(b.Fsb / 1000));
        int m = es.Length / step;
        var v = new double[m];
        for (int i = 0; i < m; i++) { double mx = 0; for (int k = 0; k < step; k++) mx = Math.Max(mx, es[i * step + k]); v[i] = Math.Max(0, mx - 1); }
        return v;
    }

    static List<Track> Validate(Track t, double[] v, Band b, int minTicks) {
        var res = new List<Track>();
        int nt = t.Times.Length, m = v.Length, w = (int)Math.Round(PerWinSec * 1000);
        double ta = t.Times[0], tb = t.Times[nt - 1];
        // Шаг корреляции — местный интервал трека в каждой точке, а не один на всё окно: колесо
        // разгоняется и тормозит (1_sweep.mp4, 7.5–8.7 с: 54 → 41 мс), и фиксированный шаг
        // смазывал корреляцию настоящего ряда до уровня шума.
        var lag = new int[m];
        int kk = 0;
        for (int i = 0; i < m; i++) {
            double ti = i / 1000.0 + b.T0;
            while (kk + 2 < nt && t.Times[kk + 1] <= ti) kk++;
            lag[i] = (int)Math.Round((t.Times[kk + 1] - t.Times[kk]) * 1000);
        }
        int ng = (int)Math.Floor((tb - ta) / PerStepSec) + 1;
        var good = new bool[ng];
        for (int g = 0; g < ng; g++) {
            double tc = ta + g * PerStepSec;
            int c = (int)Math.Round((tc - b.T0) * 1000);
            int a0 = Math.Max(0, c - w), a1 = Math.Min(m, c + w);
            while (a1 > a0 && a1 - 1 + lag[a1 - 1] + 2 >= m) a1--;
            if (a1 - a0 < 4 * lag[Math.Max(0, Math.Min(m - 1, c))]) { good[g] = true; continue; }
            double mean = 0; for (int i = a0; i < a1; i++) mean += v[i]; mean /= (a1 - a0);
            double den = 0; for (int i = a0; i < a1; i++) den += (v[i] - mean) * (v[i] - mean);
            double best = -1;
            for (int dl = -1; dl <= 1; dl++) {
                double s = 0; for (int i = a0; i < a1; i++) s += (v[i] - mean) * (v[i + lag[i] + dl] - mean);
                best = Math.Max(best, s / Math.Max(1e-12, den));
            }
            good[g] = best >= PerMin;
        }
        // плохие отрезки: внутри — от PerCutSec, по краям — от PerEdgeSec
        var keep = new bool[ng];
        for (int g = 0; g < ng; g++) keep[g] = true;
        for (int g = 0; g < ng; ) {
            if (good[g]) { g++; continue; }
            int a = g; while (g < ng && !good[g]) g++;
            double len = (g - a) * PerStepSec;
            bool edge = a == 0 || g == ng;
            if (len >= (edge ? PerEdgeSec : PerCutSec)) for (int q = a; q < g; q++) keep[q] = false;
        }
        // куски трека по сохранённым узлам
        int p = 0;
        while (p < nt) {
            int gp = Math.Min(ng - 1, (int)Math.Round((t.Times[p] - ta) / PerStepSec));
            if (!keep[gp]) { p++; continue; }
            int q = p, pn = p;
            while (q + 1 < nt && keep[Math.Min(ng - 1, (int)Math.Round((t.Times[q + 1] - ta) / PerStepSec))]) q++;
            pn = q + 1;
            // Края — до первых (последних) EdgeWin позиций, где найдено хотя бы EdgeMin тиков:
            // короткий хвост в шуме окно корреляции не замечает, оно захватывает соседний сигнал.
            while (p + EdgeWin - 1 <= q && CountReal(t.Real, p, p + EdgeWin - 1) < EdgeMin) p++;
            while (p <= q && !t.Real[p]) p++;
            while (q - EdgeWin + 1 >= p && CountReal(t.Real, q - EdgeWin + 1, q) < EdgeMin) q--;
            while (q >= p && !t.Real[q]) q--;
            if (q - p >= minTicks) {
                var nt2 = new Track(); nt2.Dir = t.Dir; nt2.SnrDb = t.SnrDb; nt2.ResidMs = t.ResidMs;
                nt2.Times = new double[q - p + 1]; nt2.Real = new bool[q - p + 1];
                Array.Copy(t.Times, p, nt2.Times, 0, q - p + 1); Array.Copy(t.Real, p, nt2.Real, 0, q - p + 1);
                foreach (bool r in nt2.Real) if (r) nt2.Detected++;
                nt2.Score = t.Score * (q - p + 1) / nt;
                res.Add(nt2);
            }
            p = pn;
        }
        return res;
    }

    public static int EdgeWin = 6, EdgeMin = 3;
    static int CountReal(bool[] r, int a, int b) { int c = 0; for (int i = a; i <= b; i++) if (r[i]) c++; return c; }

    // ================================================================ сборка треков
    // Сильные треки первыми, пересечения с уже принятыми отрезаются. Основное направление — по
    // сумме счёта; обратное (колесо крутили назад, заднее колесо в кадре) допускается, но только
    // отчётливое: слабый трек обратного направления — почти всегда отклик обратного шаблона на
    // шум или на эхо настоящих чирпов.
    static List<Track> Resolve(List<Track> all, int minTicks) {
        all.Sort((p1, p2) => p2.Score != p1.Score ? p2.Score.CompareTo(p1.Score) : p1.Times[0].CompareTo(p2.Times[0]));
        double su = 0, sdn = 0;
        foreach (Track t in all) { if (t.Dir > 0) su += t.Score; else sdn += t.Score; }
        int mainDir = su >= sdn ? 1 : -1;
        all.RemoveAll(t => t.Dir != mainDir && t.Score < OppDirK * MinScore);
        var acc = new List<Track>();
        foreach (Track t in all) {
            int bestA = -1, bestB = -1, a = -1;
            for (int i = 0; i <= t.Times.Length; i++) {
                bool free = i < t.Times.Length;
                if (free) foreach (Track q in acc) if (t.Times[i] >= q.Times[0] - 0.005 && t.Times[i] <= q.Times[q.Times.Length - 1] + 0.005) { free = false; break; }
                if (free) { if (a < 0) a = i; }
                else if (a >= 0) { if (i - a > bestB - bestA) { bestA = a; bestB = i; } a = -1; }
            }
            if (bestA < 0 || bestB - bestA - 1 < minTicks) continue;
            if (bestA > 0 || bestB < t.Times.Length) {
                var nt = new Track(); nt.Dir = t.Dir; nt.Score = t.Score; nt.SnrDb = t.SnrDb; nt.ResidMs = t.ResidMs;
                nt.Times = new double[bestB - bestA]; nt.Real = new bool[bestB - bestA];
                Array.Copy(t.Times, bestA, nt.Times, 0, bestB - bestA); Array.Copy(t.Real, bestA, nt.Real, 0, bestB - bestA);
                foreach (bool r in nt.Real) if (r) nt.Detected++;
                acc.Add(nt);
            } else acc.Add(t);
        }
        acc.Sort((p1, p2) => p1.Times[0].CompareTo(p2.Times[0]));
        return acc;
    }

    // Сращивание соседних треков одного направления через паузу ≤ BridgeSec с тем же периодом.
    static List<Track> Bridge(List<Track> ts, double maxP) {
        var res = new List<Track>();
        foreach (Track t in ts) {
            if (res.Count > 0) {
                Track p = res[res.Count - 1];
                int np = p.Times.Length;
                double g = t.Times[0] - p.Times[np - 1];
                double ia = p.Times[np - 1] - p.Times[np - 2], ib = t.Times[1] - t.Times[0];
                if (p.Dir == t.Dir && g > 0 && g <= BridgeSec && Math.Abs(ia / ib - 1) <= 0.15) {
                    int n = Math.Max(1, (int)Math.Round(g / (0.5 * (ia + ib))));
                    if (g / n <= 1.25 * maxP) {
                        var tt = new List<double>(p.Times); var rr = new List<bool>(p.Real);
                        double sumI = 0; var iv = new double[n];
                        for (int i = 0; i < n; i++) { iv[i] = ia + (ib - ia) * (i + 0.5) / n; sumI += iv[i]; }
                        double acc = p.Times[np - 1];
                        for (int i = 0; i < n - 1; i++) { acc += iv[i] * g / sumI; tt.Add(acc); rr.Add(false); }
                        tt.AddRange(t.Times); rr.AddRange(t.Real);
                        var j = new Track(); j.Dir = p.Dir; j.Score = p.Score + t.Score; j.Detected = p.Detected + t.Detected;
                        j.SnrDb = Math.Max(p.SnrDb, t.SnrDb); j.ResidMs = Math.Max(p.ResidMs, t.ResidMs);
                        j.Times = tt.ToArray(); j.Real = rr.ToArray();
                        res[res.Count - 1] = j;
                        continue;
                    }
                }
            }
            res.Add(t);
        }
        return res;
    }

    // ================================================================ веса полос
    // Где полоса реально звучит — без тиков: частота всплесков энергии полосы (выше 5 средних
    // шума) сверх шумовой. Срезанная микрофоном или кодеком полоса всплесков не даёт и получает
    // вес 0 — её шум больше не разбавляет остальные.
    static double[] PresenceWeights(float[][] seg, double[] click, int nch) {
        var w = new double[Seg]; double mx = 0;
        // доля отсчётов выше 5 у шума: Γ(nch, 1/nch) — для 1 канала e^-5, для 2 — 11·e^-10
        double noise = nch >= 2 ? 11 * Math.Exp(-10) : Math.Exp(-5);
        for (int j = 0; j < Seg; j++) {
            float[] a = seg[j], c = seg[Seg + j];
            if (a == null) continue;
            int hit = 0, tot = 0;
            for (int i = 0; i < a.Length; i++) {
                if (click != null && click[i] > ClickThr) continue;
                tot++;
                if (Math.Max(a[i], c == null ? 0 : c[i]) > 5) hit++;
            }
            double rate = tot > 0 ? (double)hit / tot : 0;
            w[j] = Math.Max(0, rate - 6 * noise);
            mx = Math.Max(mx, w[j]);
        }
        if (mx <= 0) return null;
        for (int j = 0; j < Seg; j++) w[j] /= mx;
        return w;
    }

    // По уверенным тикам: отношение сигнал/шум каждой полосы (среднее энергии куска около тика
    // минус 1). Вес полосы ∝ ему — лучшее сложение энергий при слабом сигнале.
    static double[] LearnWeights(float[][] seg, List<RawTrack> raws) {
        var sum = new double[Seg]; var cnt = new int[Seg];
        foreach (RawTrack rt in raws) {
            int off = rt.Dir > 0 ? 0 : Seg;
            for (int i = 0; i < rt.N.Length; i++) {
                if (rt.E[i] < 2) continue;
                for (int j = 0; j < Seg; j++) {
                    float[] a = seg[off + j];
                    if (a == null) continue;
                    int c = rt.Idx[i], r = 16;
                    double s = 0; int n = 0;
                    for (int k = Math.Max(0, c - r); k <= Math.Min(a.Length - 1, c + r); k++) { s += a[k]; n++; }
                    if (n > 0) { sum[j] += s / n - 1; cnt[j]++; }
                }
            }
        }
        var w = new double[Seg]; double mx = 0;
        for (int j = 0; j < Seg; j++) { w[j] = cnt[j] > 0 ? Math.Max(0, sum[j] / cnt[j]) : 0; mx = Math.Max(mx, w[j]); }
        if (mx <= 0) return null;
        for (int j = 0; j < Seg; j++) w[j] /= mx;
        return w;
    }

    // ================================================================ один прогон с весами
    static Result Detect(float[][] seg, double[] w, double[] click, Band b, double minP, double maxP, int minTicks, List<RawTrack> rawsOut) {
        var r = new Result(); r.Weights = w;
        var all = new List<Track>();
        float[] zu = Combine(seg, w, 1), zd = Combine(seg, w, -1);
        if (zu == null || zd == null) return r;
        for (int dir = 1; dir >= -1; dir -= 2) {
            double evRef = EvRef;
            double[] es = dir > 0 ? Statistic(zu, zd, click, b) : Statistic(zd, zu, click, b);
            List<Cand> cs = Candidates(es, b, evRef);
            r.Candidates += cs.Count;
            List<double> sc;
            List<int[]> paths = TrackAll(cs, es, b, evRef, minP, maxP, minTicks, out sc);
            double[] v = Excess(es, b);
            for (int i = 0; i < paths.Count; i++) {
                RawTrack rt = FromPath(paths[i], cs, dir, sc[i]);
                List<Track> ok = Validate(Smooth(rt), v, b, minTicks);
                if (ok.Count > 0 && rawsOut != null) rawsOut.Add(rt);
                all.AddRange(ok);
            }
        }
        r.Tracks = Bridge(Resolve(all, minTicks), maxP);
        foreach (Track t in r.Tracks) r.Detected += t.Detected;
        return r;
    }

    static double TotalScore(Result r) { double s = 0; foreach (Track t in r.Tracks) s += t.Score; return s; }

    static bool Better(Result a, Result b) {
        if (b == null) return true;
        if (a.Detected != b.Detected) return a.Detected > b.Detected;
        return TotalScore(a) > TotalScore(b);
    }

    // ================================================================ вход
    // x — звук, nch каналов вперемешку (стерео обрабатывается раздельно и складывается по
    // энергиям: два микрофона телефона в противофазе на 15-20 кГц гасили бы друг друга в моно);
    // minP/maxP — допустимый интервал между тиками, с; minTicks — минимум интервалов в треке.
    public static Result Run(float[] x, int nch, int sr, double minP, double maxP, int minTicks) {
        Band b = MakeBand(sr);
        if (b == null) { var e = new Result(); e.Diag = "audio band too narrow for the chirp"; return e; }
        var seg = new float[2 * Seg][];
        int n = 0;
        for (int ch = 0; ch < nch; ch++) {
            float[] zr, zi;
            Demod(x, nch, ch, b, out zr, out zi);
            n = zr.Length;
            Noise nz = MeasureNoise(zr, zi, b);
            MatchSeg(zr, zi, b, nz, seg);
        }
        for (int q = 0; q < 2 * Seg; q++) if (seg[q] != null) { float inv = 1f / nch; float[] a = seg[q]; for (int i = 0; i < a.Length; i++) a[i] *= inv; }
        double[] click = ClickSpan(x, nch, sr, b, n);

        // Первый проход — две гипотезы весов: все полосы поровну и «где полоса звучит».
        var eq = new double[Seg];
        for (int j = 0; j < Seg; j++) eq[j] = seg[j] != null ? 1 : 0;
        Result best = null; List<RawTrack> bestRaws = null;
        var hyps = new List<double[]>(); var names = new List<string>();
        hyps.Add(eq); names.Add("equal");
        double[] pw = PresenceWeights(seg, click, nch);
        if (pw != null) { hyps.Add(pw); names.Add("presence"); }
        for (int h = 0; h < hyps.Count; h++) {
            var raws = new List<RawTrack>();
            Result r = Detect(seg, hyps[h], click, b, minP, maxP, minTicks, raws);
            r.Hyp = names[h];
            if (Better(r, best)) { best = r; bestRaws = raws; }
        }
        // Второй проход — веса из найденных тиков.
        if (bestRaws != null && bestRaws.Count > 0) {
            double[] lw = LearnWeights(seg, bestRaws);
            if (lw != null) {
                Result r = Detect(seg, lw, click, b, minP, maxP, minTicks, null);
                r.Hyp = "learned";
                if (Better(r, best)) best = r;
            }
        }
        if (best.Weights != null) {
            for (int j = 0; j < Seg; j++) {
                if (best.Weights[j] < 0.1) continue;
                double fa, fb; BandOf(j, out fa, out fb);
                if (best.BandLo == 0) best.BandLo = fa;
                best.BandHi = fb;
            }
        }
        return best;
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
                           int cell, double spread, int chk, int minWin) {
        errDec.Clear(); errEnc.Clear();
        int px = w * h, fs = px * 3;
        long total = 0;
        foreach (int c in counts) total += c;
        bool checker = cell > 0 && spread > 0;
        // Нужный запас кадров вокруг окна — под самые дальние сдвиги.
        // Кадры как есть (вид 0) идут потоком, по одному — им запас не нужен: на 4K
        // минута без тиков — это ~1500 кадров по 25 МБ, и кольцо под весь отрезок
        // съедало всю память, а декодер умирал на полпути.
        int cap = 4;
        for (int si = 0; si < kinds.Length; si++) {
            int span = kinds[si] == 0 ? 1 : counts[si];
            if (kinds[si] == 2) span = Math.Max(span, Math.Max((int)Math.Ceiling(segT[si]), minWin) + (int)Math.Ceiling(segT[si] * (checker ? spread : 0) * 5 / 6.0) + 4);
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
                if (kinds[si] == 2) Windows(segT0[si], segT[si], checker ? spread : 0, minWin, wlo, whi, wwt);
                else for (int k = 0; k < 12; k++) { wlo[k] = a; whi[k] = b; wwt[k] = (k & 1) == 0 ? 1 : 0; }
                // Вид 0 — только первый кадр, остальные дочитываются по одному ниже.
                long need = kinds[si] == 0 ? a : b;
                if (kinds[si] != 0) for (int k = 0; k < 12; k++) if (wwt[k] > 0) need = Math.Max(need, whi[k]);
                // Дочитать кадры до нужного (с запасом вперёд для сдвинутых окон).
                while (loaded <= need && !eof) {
                    int slot = (int)(loaded % cap);
                    if (ring[slot] == null) ring[slot] = new byte[fs];
                    if (!ReadFrame(din, ring[slot], fs)) { eof = true; break; }
                    loaded++;
                }
                if (a >= loaded) break;
                int got;
                if (kinds[si] == 0) {
                    // Потоком: кадр прочитан — тут же записан; часть могла прийти раньше,
                    // как запас вперёд для окон прошлой прорисовки.
                    long i = a;
                    for (; i <= b; i++) {
                        if (i >= loaded) {
                            int slot = (int)(loaded % cap);
                            if (ring[slot] == null) ring[slot] = new byte[fs];
                            if (!ReadFrame(din, ring[slot], fs)) { eof = true; break; }
                            loaded++;
                        }
                        eout.Write(ring[(int)(i % cap)], 0, fs); written++;
                        if (written >= nextReport) {
                            Console.WriteLine(string.Format("  кадров {0}/{1} ({2:0}%)", written, total, 100.0 * written / Math.Max(1, total)));
                            nextReport = written + reportStep;
                        }
                    }
                    got = (int)(i - a);
                } else {
                    if (b >= loaded) b = loaded - 1;
                    got = (int)(b - a + 1);
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
    //
    // Окно не короче minWin кадров, даже если прорисовка короче. Камера пишет кадр не всё
    // время между кадрами: при 60 к/с и выдержке ~12 мс (3_sweep.mp4) кадр ловит ~70 %
    // поворота, и прорисовка в 1.8 кадра — это два кадра с провалами между клиньями: круг
    // не закрывается никогда. Картинка колеса неподвижна в пространстве, поэтому соседние
    // прорисовки дают те же точки, только под другой фазой выдержки, — окно в несколько
    // прорисовок закрывает провалы (на 3_sweep.mp4 к четырём кадрам круг почти целый).
    // Цена — анимация и движение камеры за это время смазываются, поэтому окно растёт только
    // там, где прорисовка короче minWin кадров: на съёмке 240 к/с оно не меняется.
    const double ShortFrac = 0.15;
    static void Windows(double t0, double T, double spread, int minWin, long[] lo, long[] hi, double[] wt) {
        int fl = (int)Math.Floor(T + 1e-9);
        int L = (T - fl < ShortFrac) ? fl : fl + 1;
        if (L < minWin) L = minWin;
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
    if (-not ('PovChirp' -as [type])) { Add-Type -TypeDefinition $PovSource -Language CSharp }
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
# объявляются идущими в Speed раз чаще (asetrate) и пересчитываются в 48 кГц. Чирп 15-20 кГц,
# опущенный замедлением до 15/Speed-20/Speed, снова 15-20 кГц. Именно 48 кГц, а не частота
# файла: Samsung хранит замедленный в 4 раза звук как 12 кГц (те же сэмплы, что были на
# 48 кГц, только помечены медленнее), и пересчёт обратно в 12 кГц срезал бы всё выше 6 кГц —
# вместе с чирпом.
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

function Get-AudioChannels {
    param([string]$FFprobePath, [string]$File)
    $r = Invoke-NativeCapture -Path $FFprobePath -ArgList @('-v', 'error', '-select_streams', 'a:0', '-show_entries', 'stream=channels', '-of', 'csv=p=0', '--', $File)
    $v = 0
    $line = ($r.Text -split "`r?`n" | Where-Object { $_.Trim() } | Select-Object -First 1)
    if ($line -and [int]::TryParse($line.Trim().TrimEnd(','), [ref]$v) -and $v -gt 0) { return $v }
    return 1
}

# Тики по звуку: дорожка декодируется в 48 кГц float, стерео — двумя каналами: детектор
# складывает их по энергиям (два микрофона телефона на 15-20 кГц бывают в противофазе, и
# сведение в моно гасило бы чирп). Времена переводятся на шкалу видео: у телефонной записи
# звук начинается не вровень с видео (test17khz.mp4 — на 13 мс позже), а сырой поток
# сэмплов об этом не помнит. Замедленная съёмка (Speed > 1): звук сначала разгоняется до
# реальной скорости, чирпы ищутся с обычными параметрами, а найденные времена
# растягиваются обратно в Speed раз — на шкалу файла.
# Возвращает { Candidates; Tracks = @( @{ Times; Real; Dir } ); Info = [PovChirp+Result] }.
function Get-ChirpTracks {
    param([string]$FFmpegPath, [string]$FFprobePath, [string]$File, [string]$TempDir,
          [double]$MinPeriod, [double]$MaxPeriod, [int]$MinTicks,
          [double]$Speed = 1, [int]$SampleRate = 48000, [int]$Channels = 1)

    $nch = [Math]::Max(1, [Math]::Min(2, $Channels))
    $pcm = Join-Path $TempDir ("audio_x{0}.f32" -f (Num $Speed))
    if (-not (Test-Path -LiteralPath $pcm)) {
        $dargs = @('-hide_banner', '-loglevel', 'error', '-y', '-i', $File, '-vn')
        $pre = Get-SpeedUpFilter $Speed $SampleRate
        if ($pre) { $dargs += @('-af', $pre.TrimEnd(',')) }
        $dargs += @('-ac', "$nch", '-ar', '48000', '-f', 'f32le', $pcm)
        $r = Invoke-NativeCapture -Path $FFmpegPath -ArgList $dargs
        if ($r.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $pcm)) { throw "ffmpeg не смог прочитать аудиодорожку (код $($r.ExitCode)). Есть ли звук в файле?" }
    }
    $bytes = [IO.File]::ReadAllBytes($pcm)
    $x = New-Object float[] ([int]($bytes.Length / 4))
    [Buffer]::BlockCopy($bytes, 0, $x, 0, $x.Length * 4)
    $bytes = $null

    $offset = (Get-StreamStart $FFprobePath $File 'a:0') - (Get-StreamStart $FFprobePath $File 'v:0')
    $res = [PovChirp]::Run($x, $nch, 48000, $MinPeriod, $MaxPeriod, $MinTicks)

    $tracks = New-Object System.Collections.Generic.List[object]
    foreach ($t in $res.Tracks) {
        $ts = New-Object double[] $t.Times.Length
        for ($i = 0; $i -lt $ts.Length; $i++) { $ts[$i] = $t.Times[$i] * $Speed + $offset }
        $tracks.Add([PSCustomObject]@{ Times = $ts; Real = $t.Real; Dir = $t.Dir })
    }
    return [PSCustomObject]@{ Candidates = $res.Candidates; Tracks = $tracks; Info = $res }
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
        $chirpInfo = $null
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
            [PovChirp]::FLo = $ChirpLoHz; [PovChirp]::FHi = $ChirpHiHz; [PovChirp]::Dur = $ChirpMs / 1000.0
            $audioCh = Get-AudioChannels -FFprobePath $ffprobe -File $InputFile
            Write-Host "Ищу чирпы синхро-датчика: $(Num ($ChirpLoHz / 1000))-$(Num ($ChirpHiHz / 1000)) кГц, $(Num $ChirpMs) мс, $BeepsPerRev на оборот, прорисовка от $(Num ([Math]::Round($minPeriod * 1000, 1))) до $(Num ([Math]::Round($maxPeriod * 1000, 1))) мс..."
            if ($slowFixed -eq 0 -and $slowHint -ge 2) { Write-Host "  метаданные: частота съёмки выше частоты кадров файла — похоже на замедленную съёмку ×$slowHint" }
            # Перебор замедлений (или одно заданное): звук разгоняется до реальной скорости,
            # чирпы ищутся с обычными параметрами. Берётся первое замедление, при котором
            # нашлось хотя бы 4 оборота отрисовки; если такого нет — лучшее.
            $det = $null; $best = -1
            $tried = New-Object System.Collections.Generic.List[string]
            foreach ($k in $slowTry) {
                $d = Get-ChirpTracks -FFmpegPath $ffmpeg -FFprobePath $ffprobe -File $InputFile -TempDir $tempDir `
                    -MinPeriod $minPeriod -MaxPeriod $maxPeriod -MinTicks (2 * $BeepsPerRev) `
                    -Speed $k -SampleRate $audioRate -Channels $audioCh
                $iv = 0
                foreach ($tr in $d.Tracks) { $iv += $tr.Times.Count - 1 }
                $tried.Add(("  замедление ×{0} (в файле {1:0.##}-{2:0.##} кГц, чирп {3:0.#} мс): интервалов между тиками {4}" -f $k, ($ChirpLoHz / $k / 1000), ($ChirpHiHz / $k / 1000), ($ChirpMs * $k), $iv))
                if ($iv -gt $best) { $best = $iv; $det = $d; $slow = $k }
                if ($iv -ge 4 * $BeepsPerRev) { break }
            }
            # Перебор показываем, только если первой попытки не хватило.
            if ($tried.Count -gt 1) { foreach ($l in $tried) { Write-Host $l } }
            $candCount = $det.Candidates
            $chirpInfo = $det.Info
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
            if ($tt.Count -ge 2) { $clipped.Add([PSCustomObject]@{ Times = $tt.ToArray(); Real = $rr.ToArray(); Dir = $tr.Dir }) }
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
        if ($chirpInfo -and $chirpInfo.BandLo -gt 0) {
            $hypName = switch ($chirpInfo.Hyp) { 'equal' { 'все полосы поровну' } 'presence' { 'по тому, где полоса звучит' } 'learned' { 'по найденным тикам' } default { $chirpInfo.Hyp } }
            Write-Host ("  чирп слышен в {0:0.#}-{1:0.#} кГц; веса полос {2} ({3})" -f ($chirpInfo.BandLo / 1000), ($chirpInfo.BandHi / 1000), (($chirpInfo.Weights | ForEach-Object { "{0:0.00}" -f $_ }) -join ' '), $hypName)
        }
        $ti = 0
        foreach ($tr in $tracks) {
            $ti++
            $ts = $tr.Times
            $rp = for ($k = 0; $k -lt $ts.Count - 1; $k++) { 60.0 / (($ts[$k + 1] - $ts[$k]) / $slow * $BeepsPerRev) }
            $nf = @($tr.Real | Where-Object { -not $_ }).Count
            $dirTxt = if ($null -eq $tr.Dir) { "" } elseif ($tr.Dir -lt 0) { ", свип вниз (заднее колесо или вращение назад)" } else { ", свип вверх" }
            Write-Host ("  отрисовка {0}: {1:0.000}-{2:0.000} с, интервалов между тиками {3} (из них по достроенным тикам: {4}), {5:0}..{6:0} об/мин{7}" -f $ti, $ts[0], $ts[$ts.Count - 1], ($ts.Count - 1), $nf, ($rp | Measure-Object -Minimum).Minimum, ($rp | Measure-Object -Maximum).Maximum, $dirTxt)
        }
        $natives = @($segs | Where-Object { $_.Kind -eq 'native' -and ($_.T1 - $_.T0) -gt 0 })
        if ($natives.Count -gt 0) {
            Write-Host ("  без тиков, исходная частота кадров: " + (($natives | ForEach-Object { "{0:0.000}-{1:0.000} с" -f $_.T0, $_.T1 }) -join ', '))
        }
        Write-Host ("Склеенных кадров (прорисовок): {0}" -f $sweeps) -NoNewline
        if ($fpsSplit -gt 0) { Write-Host (" — из них {0} интервалов длиннее 1/{1} с разбиты, чтобы частота не падала ниже {1} к/с" -f $fpsSplit, (Num $MinFps)) } else { Write-Host "" }
        # Медленная съёмка: прорисовка короче -MinWindowFrames кадров — окно склейки шире её.
        $allIv = New-Object System.Collections.Generic.List[double]
        foreach ($tr in $tracks) { for ($k = 1; $k -lt $tr.Times.Count; $k++) { $allIv.Add($tr.Times[$k] - $tr.Times[$k - 1]) } }
        if ($allIv.Count -gt 0 -and $inputFps -gt 0) {
            $framesPerSweep = (Get-Median $allIv.ToArray()) * $inputFps
            if ($framesPerSweep -lt $MinWindowFrames) {
                Write-Host ("  прорисовка — около {0:0.#} кадра: окно склейки расширено до {1} кадров (~{2:0.#} прорисовки), иначе выдержка камеры оставляет провалы между клиньями. Для чёткой склейки снимайте 120-240 к/с." -f $framesPerSweep, $MinWindowFrames, ($MinWindowFrames / $framesPerSweep))
            }
        }

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
            Write-Host "Отрисовки не найдено — чирпов синхро-датчика на звуке нет (пьезо звучит, только пока запитаны все шесть лучей)."
            if (-not $DetectOnly) { Write-Host "Склеивать нечего: видео не меняется. Если чирпы на записи есть, проверьте, что -ChirpLoHz/-ChirpHiHz/-ChirpMs совпадают с PIEZO_CHIRP_* прошивки." }
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
                Write-Host "Отменено. Проверьте тики через -DetectOnly, либо запустите с -Force, чтобы пропустить этот вопрос."
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
                                    $chkModeId, [Math]::Max(1, $MinWindowFrames))
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
