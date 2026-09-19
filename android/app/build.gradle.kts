import com.android.build.api.variant.impl.VariantOutputImpl
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Отметка времени сборки — она же уходит в имя APK. Берётся один раз на
// конфигурацию, чтобы все выходы одной сборки назывались одинаково.
// Импорты обязательны: в скрипте Kotlin DSL идентификатор java занят
// расширением Gradle для Java-плагина, и java.time.* оттуда не разрешается.
val buildTime: LocalDateTime = LocalDateTime.now()
val buildStamp: String = buildTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm"))

/** Короткий хеш HEAD, плюс «-dirty», если в дереве есть незакоммиченные правки —
 *  тогда APK не соответствует ровно ни одному коммиту, и это стоит видеть сразу,
 *  а не гадать при жалобе на баг, который уже могли починить. "nogit" — если
 *  сборка идёт вне git-репозитория (например, распакованный архив исходников). */
fun gitDescribe(): String {
    fun run(vararg cmd: String): String {
        val out = ByteArrayOutputStream()
        val result = project.exec {
            commandLine(*cmd)
            standardOutput = out
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        return if (result.exitValue == 0) out.toString().trim() else ""
    }
    val hash = run("git", "rev-parse", "--short=8", "HEAD").ifEmpty { return "nogit" }
    // Пathspec ".." — это android/ (скрипт выполняется из android/app): грязный
    // флаг должен отражать несохранённые правки самого приложения, а не любую
    // незакоммиченную мелочь в прошивке где-то в остальном репозитории.
    val dirty = run("git", "status", "--porcelain", "--", "..").isNotEmpty()
    return hash + (if (dirty) "-dirty" else "")
}
val gitStamp: String = gitDescribe()

// android.versionName — то самое поле, которое Android показывает в «Инфо о
// приложении» как версию. Раньше это была статичная «1.0» на все сборки —
// после установки поверх новой не было способа отличить её от предыдущей, не
// сверяя дату файла APK (которая теряется при пересылке). Формат — дата-время
// сборки (тот же buildStamp, что и в имени APK, — так эти два места нельзя
// перепутать) плюс короткий git-хеш: по нему сразу видно, какой коммит внутри.
val appVersionName: String = buildStamp + "+" + gitStamp

// android.versionCode обязан расти от сборки к сборке (Android иначе не даёт
// поставить APK поверх более новой версии через штатный апдейт). Минуты от
// фиксированной эпохи — простое монотонное число, влезающее в Int (переполнит
// Int32 нескоро: 2^31 минут — это больше четырёх тысяч лет).
val versionEpoch: LocalDateTime = LocalDateTime.of(2024, 1, 1, 0, 0)
val appVersionCode: Int = ChronoUnit.MINUTES.between(versionEpoch, buildTime).toInt()

android {
    namespace = "com.povwheel.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.povwheel.app"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

// Имя APK с датой и временем сборки: app-release-2026-08-22-17-49.apk.
// Иначе на телефоне и в папке загрузок лежит десяток одинаковых
// «app-release.apk», и понять, какой из них свежий, можно только по дате
// файла — которая теряется при первой же пересылке.
//
// Побочный эффект намеренный: имя меняется каждую сборку, поэтому упаковка
// никогда не считается UP-TO-DATE и APK создаётся заново даже когда код не
// менялся. Так и нужно — файл со штампом обязан существовать под своим именем.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            (output as? VariantOutputImpl)?.outputFileName?.set(
                "app-" + variant.name + "-" + buildStamp + ".apk"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    // exifinterface нужен: снимок с камеры приезжает с флагом поворота, и без
    // его учёта картинка легла бы на обод боком.
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Выброшены как неиспользуемые (сборка идёт без R8, так что мёртвый код
    // уезжает в APK целиком):
    //   material-icons-extended  — интерфейс рисует значки текстом («▶», «✕»),
    //                              а библиотека тянет несколько мегабайт;
    //   documentfile             — DocumentFile нигде не создаётся;
    //   lifecycle-viewmodel-compose — ViewModel берётся через by viewModels().
}
