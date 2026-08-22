import com.android.build.api.variant.impl.VariantOutputImpl
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Отметка времени сборки — она же уходит в имя APK. Берётся один раз на
// конфигурацию, чтобы все выходы одной сборки назывались одинаково.
// Импорты обязательны: в скрипте Kotlin DSL идентификатор java занят
// расширением Gradle для Java-плагина, и java.time.* оттуда не разрешается.
val buildStamp: String = LocalDateTime.now()
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm"))

android {
    namespace = "com.povwheel.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.povwheel.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
