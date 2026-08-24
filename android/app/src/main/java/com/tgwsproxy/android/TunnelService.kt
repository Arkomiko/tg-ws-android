package com.tgwsproxy.android

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * Пустой туннель, поднимаемый только ради живучести процесса.
 *
 * Зачем. Штатных средств не хватило: foreground-сервис типа specialUse,
 * продлеваемый wake lock, исключение из Doze, сторожевой будильник — и всё
 * равно прошивки Transsion и Xiaomi усыпляют процесс на минуты. Приложения
 * вроде Happ и ByeDPI это переживают, и отличаются они одним: у них поднят
 * VpnService. Процесс с активным туннелем система держит в другом состоянии
 * и замораживает куда неохотнее.
 *
 * Что здесь НЕ происходит. Трафик не перехватывается и никуда не
 * перенаправляется. Telegram приходит к нам на 127.0.0.1, а локальные
 * соединения через tun не ходят — заворачивать нечего. Поэтому маршрут
 * прописан ровно один, на заведомо пустой адрес внутри собственной подсети:
 * туда никто не обращается, и ни один пакет чужого приложения в туннель не
 * попадает. Это осознанно «пустой» VPN, а не средство защиты трафика.
 *
 * Соседство с чужими обходчиками. Активным может быть только один VpnService
 * в системе. Если ByeDPI или любой другой VPN уже работает, свой не поднимаем:
 * процесс и так под защитой чужого туннеля, а вытеснять чужой обходчик ради
 * собственной живучести — плохой размен. Если чужой VPN поднимется поверх
 * нашего, система заберёт туннель и позовёт onRevoke.
 */
class TunnelService : VpnService() {

    companion object {
        const val ACTION_START = "com.tgwsproxy.android.TUNNEL_START"
        const val ACTION_STOP = "com.tgwsproxy.android.TUNNEL_STOP"

        private const val TAG = "TgWsProxy"

        /**
         * Адрес интерфейса и единственный маршрут. Подсеть из диапазона
         * 10/8, выбрана редкая, чтобы не пересечься с домашними сетями и с
         * туннелями других приложений. Маршрут ведёт на соседний адрес той же
         * подсети — он никому не принадлежит, обращений к нему не бывает.
         */
        private const val TUN_ADDRESS = "10.215.173.1"
        private const val TUN_ROUTE = "10.215.173.2"

        /** Минимально допустимый MTU: гонять по туннелю всё равно нечего. */
        private const val TUN_MTU = 1280

        /** Поднят ли наш собственный туннель прямо сейчас. */
        @Volatile
        var isActive: Boolean = false
            private set

        /**
         * Активен ли VPN, поднятый не нами.
         *
         * Проверяется по дефолтной сети: у VPN-сети нет NET_CAPABILITY_NOT_VPN.
         * Собственный туннель под это условие подпадает точно так же, поэтому
         * из результата он исключается явно.
         */
        fun foreignVpnActive(context: Context): Boolean {
            if (isActive) return false
            return anyVpnActive(context)
        }

        /** Есть ли вообще активный VPN — свой или чужой. */
        fun anyVpnActive(context: Context): Boolean {
            return try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as ConnectivityManager
                val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            } catch (t: Throwable) {
                Log.w(TAG, "Не удалось определить состояние VPN", t)
                false
            }
        }

        /**
         * Согласие пользователя на туннель уже получено?
         *
         * VpnService.prepare возвращает Intent, если согласия ещё нет; его
         * нужно показать из Activity. null означает, что можно поднимать.
         */
        fun consentGranted(context: Context): Boolean =
            runCatching { VpnService.prepare(context) == null }.getOrDefault(false)

        /** В каком режиме живучести мы находимся — для показа в интерфейсе. */
        enum class Mode {
            /** Пользователь не включал туннель. */
            DISABLED,

            /** Включён, но системного согласия ещё нет. */
            NO_CONSENT,

            /** Работает чужой VPN — свой намеренно не поднимаем. */
            FOREIGN,

            /** Поднят собственный туннель. */
            OWN,

            /** Включён и согласие есть, но туннель ещё не поднят. */
            PENDING,
        }

        fun mode(context: Context): Mode = when {
            !ProxyConfigStore.tunnelEnabled(context) -> Mode.DISABLED
            isActive -> Mode.OWN
            anyVpnActive(context) -> Mode.FOREIGN
            !consentGranted(context) -> Mode.NO_CONSENT
            else -> Mode.PENDING
        }

        fun start(context: Context) {
            val intent = Intent(context, TunnelService::class.java).setAction(ACTION_START)
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "Не удалось запустить туннель", it) }
        }

        fun stop(context: Context) {
            if (!isActive) return
            val intent = Intent(context, TunnelService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "Не удалось остановить туннель", it) }
        }
    }

    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            closeTun()
            stopSelf()
            return START_NOT_STICKY
        }
        establish()
        // Туннель существует ради живучести и сам по себе смысла не имеет:
        // если система убила сервис, поднимать его обратно должен ProxyService
        // вместе с прокси, иначе получим VPN без работающего прокси.
        return START_NOT_STICKY
    }

    private fun establish() {
        if (tun != null) return
        try {
            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .addAddress(TUN_ADDRESS, 32)
                .addRoute(TUN_ROUTE, 32)
                .setMtu(TUN_MTU)

            // Собственный трафик в туннель не пускаем ни при каких условиях:
            // прокси должен ходить в интернет напрямую. Маршрут и так ведёт в
            // пустоту, но это дешёвая страховка от собственной ошибки.
            runCatching { builder.addDisallowedApplication(packageName) }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // Туннель не тарифицируется: через него ничего не идёт.
                builder.setMetered(false)
            }

            tun = builder.establish()
            isActive = tun != null
            if (isActive) {
                Log.i(TAG, "Туннель поднят")
            } else {
                // establish возвращает null, когда согласие не выдано или его
                // отозвали. Это не ошибка — прокси продолжает работать без
                // туннеля, просто без защиты от засыпания.
                Log.w(TAG, "Туннель не поднят: нет согласия пользователя")
                stopSelf()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Не удалось поднять туннель", t)
            isActive = false
            tun = null
            stopSelf()
        }
    }

    /**
     * Система забрала туннель — обычно потому, что пользователь запустил
     * другой VPN. Спорить не с чем: чужой обходчик важнее нашей живучести,
     * и его туннель точно так же не даёт процессу заснуть.
     */
    override fun onRevoke() {
        Log.i(TAG, "Туннель отозван системой — вероятно, поднялся другой VPN")
        closeTun()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTun()
        super.onDestroy()
    }

    private fun closeTun() {
        isActive = false
        val fd = tun ?: return
        tun = null
        runCatching { fd.close() }
            .onFailure { Log.w(TAG, "Не удалось закрыть туннель", it) }
        Log.i(TAG, "Туннель закрыт")
    }
}
