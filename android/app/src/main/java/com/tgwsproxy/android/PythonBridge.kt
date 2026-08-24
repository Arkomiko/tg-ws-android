package com.tgwsproxy.android

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Единственная точка входа в Python. Инициализация CPython делается один раз
 * и потокобезопасно; дальше все вызовы идут в модуль android_bridge.
 */
object PythonBridge {

    private const val MODULE = "android_bridge"

    @Volatile
    private var module: PyObject? = null

    @Synchronized
    fun ensureStarted(context: Context): PyObject {
        module?.let { return it }
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        val mod = Python.getInstance().getModule(MODULE)
        module = mod
        return mod
    }

    fun start(context: Context, configJson: String): String =
        ensureStarted(context).callAttr("start", configJson).toString()

    fun stop(): String =
        module?.callAttr("stop")?.toString() ?: "{\"state\":\"stopped\"}"

    fun status(context: Context): String =
        ensureStarted(context).callAttr("status_json").toString()

    fun stats(context: Context): String =
        ensureStarted(context).callAttr("stats_json").toString()

    /**
     * Сообщает ядру, что сеть сменилась (Wi-Fi ↔ мобильная, подъём/падение VPN).
     * Без этого накопленные отказы держат WebSocket выключенным до часа.
     */
    fun onNetworkChange(description: String): String =
        module?.callAttr("on_network_change", description)?.toString() ?: ""

    /** Диагностика окружения: версия ядра, AES, cryptography, доступность certifi. */
    fun selftest(context: Context): String =
        ensureStarted(context).callAttr("selftest").toString()

    /** Полный срез состояния: счётчики, чёрные списки, живость потоков и loop. */
    fun diagnose(context: Context): String =
        ensureStarted(context).callAttr("diagnose").toString()

    /** Стеки всех Python-потоков — для разбора залипаний. Пишется и в лог. */
    fun dumpStacks(context: Context): String =
        ensureStarted(context).callAttr("dump_stacks").toString()
}
