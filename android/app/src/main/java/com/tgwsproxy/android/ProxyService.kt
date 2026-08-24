package com.tgwsproxy.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject

/**
 * Foreground-сервис, внутри которого живёт Python-ядро прокси.
 *
 * Почему foreground: прокси держит слушающий сокет и пул WebSocket-соединений.
 * Обычный фоновый процесс Android убьёт за минуты, в Doze — быстрее.
 *
 * Почему тип specialUse: у dataSync на Android 15 суточный лимит 6 часов, для
 * постоянно работающего прокси это не годится; connectedDevice не подходит по
 * смыслу — внешнего устройства нет.
 */
class ProxyService : Service() {

    companion object {
        const val ACTION_START = "com.tgwsproxy.android.START"
        const val ACTION_STOP = "com.tgwsproxy.android.STOP"

        private const val TAG = "TgWsProxy"
        private const val CHANNEL_ID = "proxy_status"
        private const val NOTIF_ID = 1
        private const val NOTIF_REFRESH_MS = 5000L

        /**
         * Срок, на который берётся wake lock. Продлевается тикером вдвое чаще,
         * поэтому разрыва не возникает, а система не видит «вечного» лока.
         */
        private const val WAKELOCK_TTL_MS = 10 * 60 * 1000L

        /** Как часто продлевать — вдвое чаще срока, чтобы не было разрыва. */
        private const val WAKELOCK_RENEW_MS = 4 * 60 * 1000L

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val ui = Handler(android.os.Looper.getMainLooper())
    private var notifTicker: Runnable? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetEvent = 0L
    private var lastVpn = false
    private var lastWakeLockAcquire = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    /** Уведомление сервиса должно быть на том же языке, что и интерфейс. */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("proxy-control").also { it.start() }
        workerHandler = Handler(worker!!.looper)
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ProxyConfigStore.setShouldRun(this, false)
            Restarter.cancelWatchdog(this)
            stopNotifTicker()
            stopForegroundCompat()
            workerHandler?.post {
                stopProxy()
                ui.post { stopSelf() }
            }
            return START_NOT_STICKY
        }

        // startForeground обязан быть вызван в первые секунды после старта,
        // поэтому уведомление показываем до того, как трогаем Python.
        startForegroundCompat(buildNotification(getString(R.string.notif_starting)))

        ProxyConfigStore.setShouldRun(this, true)
        Restarter.scheduleWatchdog(this)
        // Лок берём на каждый onStartCommand, а не только при первом запуске:
        // сюда приходит и сторожевой будильник, и это шанс вернуть лок, если
        // его снял менеджер питания прошивки.
        acquireWakeLock()

        // Конфиг всегда берём из хранилища: при рестарте по START_STICKY intent
        // приходит пустым, и раньше это приводило к генерации нового секрета.
        ProxyConfigStore.migrateLogIfNeeded(this)
        val cfg = ProxyConfigStore.load(this)
        workerHandler?.post { startProxy(cfg) }
        return START_STICKY
    }

    /**
     * Смахивание карточки из «Недавних» на многих прошивках убивает процесс
     * вместе с foreground-сервисом. Ставим будильник, который поднимет прокси
     * обратно — но только если пользователь не выключал его сам.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (ProxyConfigStore.shouldRun(this)) {
            Log.i(TAG, "Задача удалена из «Недавних» — планируем подъём")
            Restarter.scheduleKick(this)
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun startProxy(cfg: ProxyConfig) {
        if (isRunning) return
        acquireWakeLock()
        try {
            val status = PythonBridge.start(this, cfg.toJson())
            val json = JSONObject(status)
            val state = json.optString("state")
            isRunning = state == "running"

            if (isRunning) {
                Log.i(TAG, "Прокси запущен на ${cfg.host}:${cfg.port}")
                ui.post {
                    updateNotification(
                        getString(R.string.notif_listening, cfg.host, cfg.port)
                    )
                    startNotifTicker()
                }
            } else {
                val err = json.optString("error").ifEmpty { state }
                Log.e(TAG, "Прокси не запустился: $err")
                releaseWakeLock()
                ui.post { updateNotification(err) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Ошибка запуска прокси", t)
            isRunning = false
            releaseWakeLock()
            ui.post { updateNotification(t.message ?: "ошибка") }
        }
    }

    private fun stopProxy() {
        try {
            PythonBridge.stop()
        } catch (t: Throwable) {
            Log.e(TAG, "Ошибка остановки прокси", t)
        } finally {
            isRunning = false
            releaseWakeLock()
        }
    }

    override fun onDestroy() {
        stopNotifTicker()
        unregisterNetworkCallback()
        stopProxy()
        worker?.quitSafely()
        worker = null
        workerHandler = null
        super.onDestroy()
    }

    // смена сети

    /**
     * Переключение Wi-Fi ↔ мобильная сеть, а также подъём и падение VPN
     * (например, ByeDPI) рвут все установленные сокеты. Ядро запоминает эти
     * отказы и потом до часа обходит WebSocket стороной. Сообщаем ему, что
     * сеть новая и прошлые отказы больше не актуальны.
     */
    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = notifyChange("available")
            override fun onLost(network: Network) = notifyChange("lost")
            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities,
            ) {
                val vpn = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                if (vpn != lastVpn) {
                    lastVpn = vpn
                    notifyChange(if (vpn) "vpn_up" else "vpn_down")
                }
            }
        }
        netCallback = cb
        try {
            // ВАЖНО: именно registerDefaultNetworkCallback, а не собственный
            // NetworkRequest. NetworkRequest.Builder() по умолчанию добавляет
            // NET_CAPABILITY_NOT_VPN, из-за чего подъём VPN (ByeDPI и любой
            // другой) под фильтр НЕ подпадает и остаётся незамеченным —
            // проверено на устройстве. Дефолтный колбэк следует за той сетью,
            // которой реально пользуется приложение, включая VPN.
            cm.registerDefaultNetworkCallback(cb)
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось подписаться на смену сети", t)
            netCallback = null
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = netCallback ?: return
        netCallback = null
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(cb)
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось отписаться от смены сети", t)
        }
    }

    private fun notifyChange(what: String) {
        if (!isRunning) return
        val now = System.currentTimeMillis()
        // Система сыпет событиями пачками, поэтому пауза нужна. Но подъём и
        // падение VPN идут вплотную друг за другом (выключил ByeDPI — включил),
        // и пауза съедала второе событие: пул оставался с сокетами, убитыми
        // сменой маршрута, а клиент молча ждал ответа. Поэтому смену состояния
        // VPN пропускаем всегда, паузу применяем только к рядовым событиям.
        val isVpnTransition = what.startsWith("vpn_")
        if (!isVpnTransition && now - lastNetEvent < 3000) return
        lastNetEvent = now
        Log.i(TAG, "Смена сети: $what")
        workerHandler?.post {
            runCatching { PythonBridge.onNetworkChange(what) }
        }
    }

    // живое уведомление

    private fun startNotifTicker() {
        stopNotifTicker()
        val r = object : Runnable {
            override fun run() {
                if (!isRunning) return
                renewWakeLock()
                val statsRaw = runCatching { PythonBridge.stats(this@ProxyService) }.getOrNull()
                val stats = statsRaw?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (stats != null) {
                    updateNotification(
                        getString(R.string.stats_traffic,
                            human(stats.optLong("bytes_up")),
                            human(stats.optLong("bytes_down"))) +
                            " · ${stats.optInt("active")}/${stats.optInt("total")}"
                    )
                }
                ui.postDelayed(this, NOTIF_REFRESH_MS)
            }
        }
        notifTicker = r
        ui.postDelayed(r, NOTIF_REFRESH_MS)
    }

    private fun stopNotifTicker() {
        notifTicker?.let { ui.removeCallbacks(it) }
        notifTicker = null
    }

    private fun human(n: Long): String {
        var v = n.toDouble()
        for (unit in listOf("Б", "КБ", "МБ", "ГБ")) {
            if (kotlin.math.abs(v) < 1024) return "%.1f %s".format(v, unit)
            v /= 1024
        }
        return "%.1f ТБ".format(v)
    }

    // wake lock

    /**
     * Wake lock с обновлением по таймауту вместо бессрочного.
     *
     * Бессрочный `acquire()` Android помечает флагом LONG, и менеджеры питания
     * прошивок (на Infinix — Phone Master) такие локи снимают. По логам это
     * выглядело так: `REL TgWsProxy::proxy`, дальше процессор засыпает, прокси
     * замирает на минуты, в очереди приёма копятся соединения Telegram, и всё
     * оживает только при следующем ACQ. Моменты REL/ACQ совпадали с провалами
     * в логе прокси один в один.
     *
     * Поэтому берём лок с ограниченным сроком и продлеваем его тикером: с точки
     * зрения системы это обычное поведение, а не «висящий вечно» лок.
     */
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = wakeLock ?: pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "TgWsProxy::proxy"
        ).also {
            it.setReferenceCounted(false)
            wakeLock = it
        }
        try {
            lock.acquire(WAKELOCK_TTL_MS)
            lastWakeLockAcquire = System.currentTimeMillis()
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось взять wake lock", t)
        }
    }

    /** Проверяет, что лок всё ещё наш, и продлевает. Зовётся из тикера. */
    private fun renewWakeLock() {
        if (!isRunning) return
        val lock = wakeLock
        val lost = lock == null || !lock.isHeld
        if (lost) {
            Log.w(TAG, "Wake lock не держится — берём заново")
        } else if (System.currentTimeMillis() - lastWakeLockAcquire < WAKELOCK_RENEW_MS) {
            return
        }
        acquireWakeLock()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // уведомление

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_LOW: уведомление постоянное, звенеть оно не должно.
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(getColor(R.color.tg_blue))
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        this, R.drawable.ic_notification
                    ),
                    getString(R.string.notif_stop),
                    stopIntent,
                ).build()
            )
            .build()
    }
}
