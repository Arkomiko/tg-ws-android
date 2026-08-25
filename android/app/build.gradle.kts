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
    // Переписывать это на Kotlin незачем. Десктопные модули из utils/ удалены
    // из репозитория вместе с треем, поэтому исключать здесь больше нечего.
    from(repoRoot.resolve("utils")) {
        into("utils")
        include("**/*.py")
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
            // 64- и 32-битный ARM.
            //
            // Список ABI определяется не вкусом, а тем, под что существуют
            // колёса cryptography в репозитории Chaquopy: для cp313 это только
            // arm64_v8a и x86_64, а для cp311 — все четыре. Поэтому охват
            // 32-битных устройств и версия Python связаны жёстко, см. блок
            // chaquopy ниже.
            //
            // x86 и x86_64 не включены: они нужны эмулятору, а не живым
            // телефонам, и стоили бы ещё двух копий интерпретатора в APK.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Отдельный APK на каждую архитектуру плюс универсальный.
    //
    // С двумя ABI универсальный APK весит около 30 МБ, из которых половина
    // мертва для любого конкретного телефона: внутри лежат два интерпретатора
    // Python. Разделение даёт файлы вдвое меньше, а универсальный остаётся для
    // тех, кто не знает свою архитектуру и не должен её знать.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
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
        // Python 3.11, а не более свежий, — сознательный размен ради охвата.
        //
        // Колёса cryptography 42.0.8 в репозитории Chaquopy собраны под
        // cp310–cp313, но не под все ABI: для cp313 доступны только
        // arm64_v8a и x86_64, а для cp311 — все четыре, включая armeabi_v7a.
        // Значит 32-битные телефоны достижимы лишь на 3.11. Цена — интерпретатор
        // на две минорные версии старше; ядро proxy/ на нём работает без правок,
        // ничего новее оно не требует.
        //
        // Chaquopy нужна та же minor-версия Python на машине сборки (для pip).
        // Путь можно задать свойством chaquopy.buildPython, иначе ищем
        // стандартные места установки.
        version = "3.11"
        val buildPy = (findProperty("chaquopy.buildPython") as String?)
            ?: listOf(
                "E:/Python/Python311/python.exe",
                System.getProperty("user.home") + "/AppData/Local/Programs/Python/Python311/python.exe",
            ).firstOrNull { file(it).exists() }
        buildPy?.let { buildPython(it) }
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
