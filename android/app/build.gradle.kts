import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// Ядро протокола живёт в proxy/ в корне репозитория и НЕ дублируется сюда.
// Перед сборкой оно синхронизируется в build/pythonCore, а Chaquopy забирает
// эту папку как дополнительный источник Python-кода. Так остаётся ровно один
// источник истины по протоколу.
val pythonCoreDir = layout.buildDirectory.dir("pythonCore")

val repoRoot = rootProject.projectDir.parentFile

val syncPythonCore = tasks.register<Sync>("syncPythonCore") {
    description = "Копирует proxy/ и utils/ из корня репозитория в build/pythonCore"
    from(repoRoot.resolve("proxy")) {
        into("proxy")
        include("**/*.py")
    }
    // utils/logging_setup.py даёт ротацию лога с уже выверенным инвариантом
    // (backupCount >= 1, иначе RotatingFileHandler молча не ротирует).
    // Переписывать это на Kotlin незачем. Десктопных зависимостей здесь нет:
    // utils/__init__.py тянет только update_check, а тот — urllib и proxy.utils.
    from(repoRoot.resolve("utils")) {
        into("utils")
        include("**/*.py")
        exclude("win32_theme.py", "tray_common.py", "default_config.py")
    }
    into(pythonCoreDir)
}

/**
 * Проверка синтаксиса Python перед сборкой.
 *
 * Chaquopy кладёт .py в APK как есть и НЕ проверяет их: файл с синтаксической
 * ошибкой спокойно уезжает на устройство, а падает уже там — при импорте,
 * то есть в момент запуска прокси. Отлаживать это на телефоне дорого, поэтому
 * ловим здесь. Если подходящего Python на машине сборки нет, задача молча
 * пропускается: она страхует, а не блокирует.
 */
val checkPythonSyntax = tasks.register("checkPythonSyntax") {
    dependsOn(syncPythonCore)
    val sources = listOf(
        file("src/main/python"),
        pythonCoreDir.get().asFile,
    )
    val explicit = findProperty("chaquopy.buildPython") as String?
    doLast {
        val candidates = listOfNotNull(explicit, "python", "python3", "py")
        for (exe in candidates) {
            val cmd = (if (exe == "py") listOf(exe, "-3") else listOf(exe)) +
                listOf("-m", "compileall", "-q", "-f") +
                sources.map { it.absolutePath }
            val proc = runCatching {
                ProcessBuilder(cmd).redirectErrorStream(true).start()
            }.getOrNull() ?: continue

            val text = proc.inputStream.bufferedReader().readText().trim()
            val code = proc.waitFor()
            if (code != 0) {
                throw GradleException("Синтаксическая ошибка в Python-исходниках:\n$text")
            }
            logger.lifecycle("Синтаксис Python проверен ($exe)")
            return@doLast
        }
        logger.warn("Python для проверки синтаксиса не найден — проверка пропущена")
    }
}

tasks.named("preBuild") {
    dependsOn(syncPythonCore, checkPythonSyntax)
}

// Chaquopy собирает Python-исходники в merge<Variant>PythonSources. Gradle требует
// явной связи с задачей, которая эту папку производит, иначе валидация падает.
tasks.matching { it.name.matches(Regex("merge.+PythonSources")) }.configureEach {
    dependsOn(syncPythonCore)
}

/**
 * Параметры подписи читаются из android/keystore.properties — файла, которого
 * нет в git (см. android/.gitignore). Само хранилище ключей лежит вне
 * репозитория. Если файла нет, релиз собирается неподписанным, и сборка из
 * чужой копии репозитория не падает.
 */
/**
 * Версия ядра протокола — читается прямо из `proxy/__init__.py`.
 *
 * Она отличается от версии приложения: ядро приходит из оригинала
 * Flowseal/tg-ws-proxy и живёт по своей нумерации, а APK нумеруется отдельно,
 * потому что Android-обвязка меняется независимо от протокола. Читаем из файла,
 * а не дублируем строкой, чтобы значения не разъехались при обновлении ядра.
 */
val coreVersion: String = Regex("""__version__\s*=\s*["']([^"']+)["']""")
    .find(repoRoot.resolve("proxy/__init__.py").readText())
    ?.groupValues?.get(1)
    ?: error("не удалось прочитать __version__ из proxy/__init__.py")

val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasSigning = keystoreProperties.getProperty("storeFile")?.let { file(it).exists() } == true

android {
    namespace = "com.tgwsproxy.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tgwsproxy.android"
        minSdk = 24
        targetSdk = 35
        // Версия APK своя, не совпадает с версией ядра. Префикс «A» —
        // Android, дальше поколение обвязки и порядковый номер сборки.
        versionCode = 2
        versionName = "A.1.002"

        // Версия ядра доступна из ресурсов, чтобы её можно было показать
        // в подписи на главном экране рядом с версией APK.
        resValue("string", "core_version", coreVersion)

        ndk {
            // Только arm64-v8a.
            //
            // Chaquopy отдаёт Python 3.13 лишь для arm64-v8a и x86_64
            // («Python 3.13 is not available for the ABI armeabi-v7a»), а 3.13
            // нужен ради свежих колёс cryptography. Ради 32-битного ARM
            // пришлось бы откатывать Python, и ради малого: arm64 — стандарт
            // с 2015 года, Google Play требует 64 бита с 2019, а minSdk у нас
            // 24 (Android 7, 2016). x86_64 нужен только эмулятору.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            // Код и ресурсы ужимаем; proguard-rules.pro держит то, до чего
            // Chaquopy добирается через отражение.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    sourceSets {
        getByName("main") {
            // Переводы вынесены в отдельную папку app/localization, чтобы не
            // смешиваться с версткой, цветами и иконками. Android требует
            // раскладку values/ и values-<язык>/, поэтому внутри она сохранена:
            //   localization/values/strings.xml     — английский (по умолчанию)
            //   localization/values-ru/strings.xml  — русский
            // Добавить язык = добавить папку values-<код> с strings.xml.
            res.srcDirs("src/main/res", "localization")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

chaquopy {
    defaultConfig {
        // cryptography 42.0.8 в репозитории Chaquopy собрана под cp310–cp313,
        // поэтому 3.13 — верхняя доступная версия. Chaquopy требует, чтобы на
        // машине сборки была та же minor-версия Python (для pip); при желании
        // путь к ней задаётся свойством chaquopy.buildPython.
        version = "3.13"
        (findProperty("chaquopy.buildPython") as String?)?.let { buildPython(it) }
        pip {
            install("cryptography")
            install("certifi")
        }
    }

    sourceSets {
        getByName("main") {
            srcDir(pythonCoreDir)
        }
    }
}
