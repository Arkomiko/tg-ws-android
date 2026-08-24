package com.tgwsproxy.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Конфигурация прокси, переживающая перезапуск процесса.
 *
 * Набор полей повторяет окно настроек десктопной версии
 * (`ui/ctk_tray_ui.py:install_tray_config_form`), кроме тех, что на Android
 * не имеют смысла: автозапуск Windows заменён на автозапуск после включения
 * телефона, проверка обновлений с GitHub убрана.
 *
 * Зачем отдельным объектом: сервис объявлен START_STICKY, и система может
 * поднять его заново с пустым intent. Раньше в этом случае в Python уходил
 * пустой конфиг, ядро генерировало НОВЫЙ случайный секрет, и ссылка, уже
 * прописанная в Telegram, переставала подходить — прокси «переставал работать»
 * без единой ошибки в логе. Теперь конфиг всегда читается отсюда.
 */
data class ProxyConfig(
    val host: String,
    val port: Int,
    val secret: String,
    val dcIp: List<String>,
    val poolSize: Int,
    val bufKb: Int,
    val logMaxMb: Int,
    val verbose: Boolean,
    val cfproxy: Boolean,
    val cfproxyUserDomainEnabled: Boolean,
    val cfproxyUserDomains: List<String>,
    val cfWorkerEnabled: Boolean,
    val cfWorkerDomains: List<String>,
    val forceTestDc: Boolean,
    val logPath: String,
) {
    fun link(): String = "tg://proxy?server=$host&port=$port&secret=dd$secret"

    fun toJson(): String = JSONObject().apply {
        put("host", host)
        put("port", port)
        put("secret", secret)
        put("dc_ip", JSONArray(dcIp))
        put("pool_size", poolSize)
        put("buf_kb", bufKb)
        put("log_max_mb", logMaxMb)
        put("verbose", verbose)
        put("cfproxy", cfproxy)
        // Флаги «включено» отделены от самих списков, как в десктопной версии:
        // домены сохраняются, даже когда пользователь их временно отключил.
        put("cfproxy_user_domain",
            JSONArray(if (cfproxyUserDomainEnabled) cfproxyUserDomains else emptyList<String>()))
        put("cfproxy_worker_domain",
            JSONArray(if (cfWorkerEnabled) cfWorkerDomains else emptyList<String>()))
        put("force_test_dc", forceTestDc)
        put("log_path", logPath)
    }.toString()
}

object ProxyConfigStore {

    const val PREFS = "tgwsproxy"

    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_SECRET = "secret"
    private const val KEY_DC_IP = "dc_ip"
    private const val KEY_POOL = "pool_size"
    private const val KEY_BUF = "buf_kb"
    private const val KEY_LOG_MB = "log_max_mb"
    private const val KEY_VERBOSE = "verbose"
    private const val KEY_CFPROXY = "cfproxy"
    private const val KEY_CF_DOMAINS = "cfproxy_user_domain"
    private const val KEY_CF_ENABLED = "cfproxy_user_domain_enabled"
    private const val KEY_CF_WORKER = "cfproxy_worker_domain"
    private const val KEY_CF_WORKER_ENABLED = "cfproxy_worker_enabled"
    private const val KEY_FORCE_TEST_DC = "force_test_dc"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_THEME = "theme"
    private const val KEY_AUTOSTART = "autostart"
    private const val KEY_SHOULD_RUN = "should_run"

    // Значения по умолчанию — те же, что в utils/default_config.py.
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 1443
    const val DEFAULT_POOL = 4
    const val DEFAULT_BUF_KB = 256
    const val DEFAULT_LOG_MB = 5
    val DEFAULT_DC_IP = listOf("2:149.154.167.220", "4:149.154.167.220")

    fun load(context: Context): ProxyConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var secret = p.getString(KEY_SECRET, null)
        if (secret == null || !isValidSecret(secret)) {
            secret = generateSecret()
            p.edit().putString(KEY_SECRET, secret).apply()
        }
        return ProxyConfig(
            host = p.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST,
            port = p.getInt(KEY_PORT, DEFAULT_PORT),
            secret = secret,
            dcIp = splitLines(p.getString(KEY_DC_IP, null)) .ifEmpty { DEFAULT_DC_IP },
            poolSize = p.getInt(KEY_POOL, DEFAULT_POOL),
            bufKb = p.getInt(KEY_BUF, DEFAULT_BUF_KB),
            logMaxMb = p.getInt(KEY_LOG_MB, DEFAULT_LOG_MB),
            verbose = p.getBoolean(KEY_VERBOSE, true),
            cfproxy = p.getBoolean(KEY_CFPROXY, true),
            cfproxyUserDomainEnabled = p.getBoolean(KEY_CF_ENABLED, false),
            cfproxyUserDomains = splitList(p.getString(KEY_CF_DOMAINS, null)),
            cfWorkerEnabled = p.getBoolean(KEY_CF_WORKER_ENABLED, false),
            cfWorkerDomains = splitList(p.getString(KEY_CF_WORKER, null)),
            forceTestDc = p.getBoolean(KEY_FORCE_TEST_DC, false),
            logPath = java.io.File(context.filesDir, "proxy.log").absolutePath,
        )
    }

    /** Сохраняет форму настроек. Возвращает текст ошибки или null, если всё верно. */
    fun save(
        context: Context,
        host: String,
        port: String,
        secret: String,
        dcIp: String,
        poolSize: String,
        bufKb: String,
        logMaxMb: String,
        verbose: Boolean,
        cfproxy: Boolean,
        cfDomainsEnabled: Boolean,
        cfDomains: String,
        cfWorkerEnabled: Boolean,
        cfWorker: String,
        forceTestDc: Boolean,
    ): String? {
        val portNum = port.trim().toIntOrNull()
            ?: return context.getString(R.string.err_port)
        if (portNum !in 1..65535) return context.getString(R.string.err_port)
        // Порты ниже 1024 на Android недоступны без root — предупреждаем сразу,
        // иначе пользователь получит невнятную ошибку привязки сокета.
        if (portNum < 1024) return context.getString(R.string.err_port_privileged)

        val secretClean = secret.trim().removePrefix("dd")
        if (!isValidSecret(secretClean)) return context.getString(R.string.err_secret)

        val hostClean = host.trim().ifEmpty { DEFAULT_HOST }

        val dcLines = splitLines(dcIp)
        for (line in dcLines) {
            if (!Regex("^\\d+:(\\d{1,3}\\.){3}\\d{1,3}$").matches(line)) {
                return context.getString(R.string.err_dc, line)
            }
        }

        val pool = poolSize.trim().toIntOrNull() ?: return context.getString(R.string.err_number)
        val buf = bufKb.trim().toIntOrNull() ?: return context.getString(R.string.err_number)
        val logMb = logMaxMb.trim().toIntOrNull() ?: return context.getString(R.string.err_number)
        if (pool < 0 || buf < 4 || logMb < 1) return context.getString(R.string.err_number)

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOST, hostClean)
            .putInt(KEY_PORT, portNum)
            .putString(KEY_SECRET, secretClean)
            .putString(KEY_DC_IP, dcLines.joinToString("\n"))
            .putInt(KEY_POOL, pool)
            .putInt(KEY_BUF, buf)
            .putInt(KEY_LOG_MB, logMb)
            .putBoolean(KEY_VERBOSE, verbose)
            .putBoolean(KEY_CFPROXY, cfproxy)
            .putBoolean(KEY_CF_ENABLED, cfDomainsEnabled)
            .putString(KEY_CF_DOMAINS, splitList(cfDomains).joinToString(","))
            .putBoolean(KEY_CF_WORKER_ENABLED, cfWorkerEnabled)
            .putString(KEY_CF_WORKER, splitList(cfWorker).joinToString(","))
            .putBoolean(KEY_FORCE_TEST_DC, forceTestDc)
            .apply()
        return null
    }

    fun regenerateSecret(context: Context): String {
        val s = generateSecret()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SECRET, s).apply()
        return s
    }

    /**
     * "ru" | "en", как поле language десктопного конфига. Пустая строка
     * означает «ещё не выбирали» — тогда AppLocale подберёт системный язык,
     * как это делает detect_system_language() в ui/i18n.
     */
    fun language(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, "") ?: ""

    fun setLanguage(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, value).apply()
    }

    /** "auto" | "light" | "dark" — как поле appearance десктопной версии. */
    fun theme(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, "auto") ?: "auto"

    fun setTheme(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, value).apply()
    }

    fun autostart(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTOSTART, true)

    fun setAutostart(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTOSTART, value).apply()
    }

    /**
     * Намерение пользователя, а не фактическое состояние. Все автоматические
     * перезапуски — после смахивания из «Недавних», после перезагрузки, по
     * сторожевому будильнику — смотрят сюда, чтобы не поднимать прокси,
     * который пользователь выключил сам.
     */
    fun shouldRun(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOULD_RUN, false)

    fun setShouldRun(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOULD_RUN, value).apply()
    }

    // helpers

    private fun isValidSecret(s: String): Boolean =
        s.length == 32 && s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

    private fun generateSecret(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun splitLines(raw: String?): List<String> =
        raw.orEmpty().split('\n', ',', ';', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun splitList(raw: String?): List<String> =
        raw.orEmpty().split(',', ';', ' ', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
