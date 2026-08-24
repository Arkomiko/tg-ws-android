package com.tgwsproxy.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * Главный экран. Компоновка повторяет окно первого запуска десктопной версии,
 * палитра и формулировки взяты оттуда же (ui/ctk_theme.py, ui/i18n/ru.json).
 */
class MainActivity : ThemedActivity() {

    private companion object {
        /** Сколько отказов рукопожатия считать поводом показать подсказку. */
        const val BAD_HANDSHAKE_HINT_THRESHOLD = 5

        /** Код запроса системного согласия на туннель. */
        const val REQ_TUNNEL_CONSENT = 1001
    }

    private lateinit var titleView: TextView
    private lateinit var statsLine: TextView
    private lateinit var statsTraffic: TextView
    private lateinit var tunnelLine: TextView
    private lateinit var linkView: TextView
    private lateinit var mtprotoView: TextView
    private lateinit var secretView: TextView
    private lateinit var diagView: TextView
    private lateinit var toggleButton: Button

    private val ui = Handler(Looper.getMainLooper())
    private var poll: Runnable? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.AppTheme)
        setContentView(R.layout.activity_main)

        titleView = findViewById(R.id.title)
        statsLine = findViewById(R.id.stats_line)
        statsTraffic = findViewById(R.id.stats_traffic)
        tunnelLine = findViewById(R.id.tunnel_line)
        linkView = findViewById(R.id.link)
        mtprotoView = findViewById(R.id.manual_mtproto)
        secretView = findViewById(R.id.manual_secret)
        diagView = findViewById(R.id.diag)
        toggleButton = findViewById(R.id.btn_toggle)

        toggleButton.setOnClickListener { onToggle() }
        // Долгое нажатие на блок диагностики — полный срез состояния и стеки
        // потоков. Нужно, когда прокси «жив, но ничего не делает».
        diagView.setOnLongClickListener {
            showDeepDiagnostics()
            true
        }
        findViewById<Button>(R.id.btn_open_telegram).setOnClickListener { openInTelegram() }
        findViewById<Button>(R.id.btn_copy).setOnClickListener { copyLink() }
        findViewById<Button>(R.id.btn_settings).setOnClickListener { SettingsActivity.open(this) }
        findViewById<Button>(R.id.btn_logs).setOnClickListener { LogActivity.open(this) }
        findViewById<Button>(R.id.btn_restart).setOnClickListener { restartProxy() }

        fillConnectionInfo()
        fillSignature()
        requestNotificationPermissionIfNeeded()
        runDiagnostics()
        maybeOfferBatteryExemption()
    }

    override fun onResume() {
        super.onResume()
        startPolling()
        maybeAskTunnelConsent()
    }

    /**
     * Спрашивает согласие на туннель, если оно нужно и сейчас безопасно.
     *
     * Согласие нельзя запрашивать при работающем чужом VPN: VpnService.prepare
     * отбирает роль VPN у активного приложения и рвёт его туннель — проверено
     * на устройстве, ByeDPI падал от одного вызова. Поэтому в настройках при
     * чужом VPN галочку принимаем молча, а спрашиваем здесь — при возвращении
     * на главный экран, когда чужого туннеля уже нет.
     */
    private fun maybeAskTunnelConsent() {
        if (!ProxyConfigStore.tunnelEnabled(this)) return
        if (TunnelService.isActive) return
        if (TunnelService.foreignVpnActive(this)) return
        if (TunnelService.consentGranted(this)) return

        val intent = runCatching { android.net.VpnService.prepare(this) }.getOrNull() ?: return
        runCatching { startActivityForResult(intent, REQ_TUNNEL_CONSENT) }
    }

    @Deprecated("onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TUNNEL_CONSENT) return
        if (resultCode != Activity.RESULT_OK) {
            // Отказ — снимаем настройку, иначе она обещала бы то, чего нет.
            ProxyConfigStore.setTunnelEnabled(this, false)
            Toast.makeText(this, R.string.tunnel_consent_denied, Toast.LENGTH_LONG).show()
        }
        refresh()
    }

    override fun onPause() {
        stopPolling()
        super.onPause()
    }

    // подключение

    private fun fillConnectionInfo() {
        val cfg = ProxyConfigStore.load(this)
        linkView.text = cfg.link()
        mtprotoView.text = getString(R.string.manual_mtproto, cfg.host, cfg.port)
        secretView.text = getString(R.string.manual_secret, cfg.secret)
    }

    private fun onToggle() {
        if (busy) return
        busy = true
        toggleButton.isEnabled = false

        val running = ProxyService.isRunning
        val intent = Intent(this, ProxyService::class.java)
        if (running) {
            intent.action = ProxyService.ACTION_STOP
            startService(intent)
            titleView.setText(R.string.title_stopped)
        } else {
            intent.action = ProxyService.ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            titleView.setText(R.string.title_starting)
        }

        // Состояние читаем из реального состояния Python-потока, а не из флага,
        // выставленного заранее: иначе кнопка врёт, если сервис не поднялся.
        ui.postDelayed({
            busy = false
            toggleButton.isEnabled = true
            refresh()
        }, 2500)
    }

    /** Аналог пункта «Перезапустить прокси» из меню трея десктопной версии. */
    private fun restartProxy() {
        if (!ProxyService.isRunning) { onToggle(); return }
        Toast.makeText(this, R.string.restarting, Toast.LENGTH_SHORT).show()
        titleView.setText(R.string.title_starting)
        ProxyRestart.restart(this)
        ui.postDelayed({ refresh() }, 4000)
    }

    // подпись

    /**
     * Подпись внизу экрана: на что опирается приложение и кто адаптировал.
     *
     * Кликабельными делаются отдельные слова, а не строка целиком, поэтому
     * разметка идёт кодом через ClickableSpan — в XML такого нет, а autoLink
     * подсвечивает только сами URL.
     */
    private fun fillSignature() {
        val based = findViewById<TextView>(R.id.signature_based)
        val by = findViewById<TextView>(R.id.signature_by)

        // Две разные версии: ядро приходит из оригинала и живёт по своей
        // нумерации (подставляется сборкой из proxy/__init__.py), APK
        // нумеруется отдельно — обвязка меняется независимо от протокола.
        val line = getString(R.string.sig_based) + System.lineSeparator() +
            getString(
                R.string.sig_versions,
                getString(R.string.core_version),
                BuildConfigCompat.versionName(this),
            )
        val first = SpannableString(line)
        // Ссылки в первой строке остаются серыми, как весь блок; что они
        // кликабельны, подсказывает подчёркивание.
        link(first, "tg-ws-proxy", "https://github.com/Flowseal/tg-ws-proxy",
            R.color.signature, underline = true)
        link(first, "Flowseal", "https://github.com/Flowseal",
            R.color.signature, underline = true)
        based.text = first
        based.movementMethod = LinkMovementMethod.getInstance()

        val second = SpannableString(getString(R.string.sig_adapted))
        link(second, "Arkomiko", "https://github.com/Arkomiko",
            R.color.signature_link, underline = false)
        by.text = second
        by.movementMethod = LinkMovementMethod.getInstance()
    }

    /** Делает фрагмент текста ссылкой на url. Если фрагмента нет — ничего не делает. */
    private fun link(
        text: SpannableString,
        fragment: String,
        url: String,
        colorRes: Int,
        underline: Boolean,
    ) {
        val start = text.indexOf(fragment)
        if (start < 0) return
        val end = start + fragment.length
        val color = getColor(colorRes)

        text.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }

            override fun updateDrawState(ds: android.text.TextPaint) {
                ds.color = color
                ds.isUnderlineText = underline
            }
        }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        text.setSpan(ForegroundColorSpan(color), start, end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun copyLink() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("tg proxy", ProxyConfigStore.load(this).link()))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun openInTelegram() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ProxyConfigStore.load(this).link())))
        } catch (t: Throwable) {
            Toast.makeText(this, R.string.telegram_missing, Toast.LENGTH_LONG).show()
        }
    }

    // статус

    private fun startPolling() {
        stopPolling()
        val r = object : Runnable {
            override fun run() {
                refresh()
                ui.postDelayed(this, 2000)
            }
        }
        poll = r
        ui.post(r)
    }

    private fun stopPolling() {
        poll?.let { ui.removeCallbacks(it) }
        poll = null
    }

    /**
     * Строка о режиме живучести. Показывается только при работающем прокси:
     * туннель существует ради него и без него не поднимается.
     *
     * Режим «работает другой VPN» — не отказ, а штатное поведение: под чужим
     * туннелем процесс защищён так же, поэтому свой намеренно не поднимаем.
     */
    private fun showTunnelMode(running: Boolean) {
        if (!running || !ProxyConfigStore.tunnelEnabled(this)) {
            tunnelLine.visibility = View.GONE
            return
        }
        val res = when (TunnelService.mode(this)) {
            TunnelService.Companion.Mode.OWN -> R.string.tunnel_own
            TunnelService.Companion.Mode.FOREIGN -> R.string.tunnel_foreign
            TunnelService.Companion.Mode.NO_CONSENT -> R.string.tunnel_no_consent
            TunnelService.Companion.Mode.PENDING -> R.string.tunnel_pending
            TunnelService.Companion.Mode.DISABLED -> {
                tunnelLine.visibility = View.GONE
                return
            }
        }
        tunnelLine.setText(res)
        tunnelLine.visibility = View.VISIBLE
    }

    private fun refresh() {
        Thread {
            val statusRaw = runCatching { PythonBridge.status(this) }.getOrNull()
            val statsRaw = runCatching { PythonBridge.stats(this) }.getOrNull()
            ui.post { applyStatus(statusRaw, statsRaw) }
        }.start()
    }

    private fun applyStatus(statusRaw: String?, statsRaw: String?) {
        val status = statusRaw?.let { runCatching { JSONObject(it) }.getOrNull() }
        val state = status?.optString("state") ?: "stopped"
        val running = state == "running" && ProxyService.isRunning

        if (!busy) {
            toggleButton.setText(if (running) R.string.btn_stop else R.string.btn_start)
        }

        when {
            running -> titleView.setText(R.string.title_running)
            state == "error" -> titleView.setText(R.string.title_error)
            busy -> titleView.setText(R.string.title_starting)
            else -> titleView.setText(R.string.title_stopped)
        }

        val err = status?.optString("error").orEmpty()
        if (state == "error" && err.isNotEmpty()) {
            statsLine.text = err
            statsTraffic.visibility = View.GONE
            return
        }

        showTunnelMode(running)

        val stats = statsRaw?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (!running || stats == null) {
            statsLine.setText(R.string.stats_idle)
            statsTraffic.visibility = View.GONE
            return
        }

        statsLine.text = getString(
            R.string.stats_line,
            stats.optInt("uptime"),
            stats.optInt("total"),
            stats.optInt("active"),
            stats.optInt("ws"),
        )

        // Показываем восстановления и чёрный список: если прокси начнёт
        // деградировать, это видно сразу, а не только по «Telegram не грузит».
        val extra = buildString {
            append(getString(
                R.string.stats_traffic,
                human(stats.optLong("bytes_up")),
                human(stats.optLong("bytes_down")),
            ))
            val soft = stats.optInt("recover_soft")
            val hard = stats.optInt("recover_hard")
            if (soft > 0 || hard > 0) append(" · восстановлений $soft/$hard")
            val bl = stats.optJSONArray("blacklist")
            if (bl != null && bl.length() > 0) append(" · WS-блок: $bl")
            if (stats.optBoolean("stuck")) append(" · ЗАЛИПАНИЕ")
        }
        // Неудачные рукопожатия почти всегда означают одно: в Telegram
        // прописан другой секрет — например, после переустановки приложения,
        // которая стирает конфиг и генерирует новый.
        //
        // Порог, а не «ws == 0»: прежнее условие показывало подсказку только
        // когда не работало вообще ничего. На практике бывает иначе — часть
        // соединений уходит по старому секрету и отбивается, часть по новому
        // работает: наблюдалось 290 отказов при 96 живых WS-сессиях, и
        // подсказка не показывалась, хотя Telegram висел на «Соединение…».
        // Порт слушается на loopback, посторонних клиентов там не бывает,
        // поэтому несколько отказов — уже достоверный признак.
        val bad = stats.optInt("bad")
        statsTraffic.text = if (bad >= BAD_HANDSHAKE_HINT_THRESHOLD) {
            getString(R.string.warn_bad_secret)
        } else {
            extra
        }
        statsTraffic.visibility = View.VISIBLE
    }

    private fun human(n: Long): String {
        var v = n.toDouble()
        for (unit in listOf("Б", "КБ", "МБ", "ГБ")) {
            if (kotlin.math.abs(v) < 1024) return "%.1f %s".format(v, unit)
            v /= 1024
        }
        return "%.1f ТБ".format(v)
    }

    // диагностика

    private fun runDiagnostics() {
        Thread {
            val raw = runCatching { PythonBridge.selftest(this) }.getOrNull()
            ui.post {
                val o = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
                diagView.text = if (o == null) {
                    raw ?: "—"
                } else {
                    "Python ${o.optString("python")} · ядро ${o.optString("proxy_version")}\n" +
                        "cryptography ${o.optString("cryptography", "—")} · AES ${o.optString("aes")}\n" +
                        "certifi: ${if (o.optBoolean("certifi_readable")) "ok" else "НЕДОСТУПЕН"}"
                }
            }
        }.start()
    }

    private fun showDeepDiagnostics() {
        Toast.makeText(this, "Собираю состояние…", Toast.LENGTH_SHORT).show()
        Thread {
            val diag = runCatching { PythonBridge.diagnose(this) }.getOrElse { it.toString() }
            val stacks = runCatching { PythonBridge.dumpStacks(this) }.getOrElse { it.toString() }
            ui.post {
                diagView.text = listOf(diag, stacks).joinToString(
                    separator = System.lineSeparator() + System.lineSeparator()
                )
                Toast.makeText(this, "Записано и в файл лога", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    // разрешения и батарея

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
    }

    /**
     * Прошивки Transsion/Xiaomi/Huawei выгружают фоновые сервисы агрессивнее стока.
     * Освобождение от батарейной оптимизации — единственное, что тут помогает,
     * но навязывать его нельзя: спрашиваем один раз.
     */
    private fun maybeOfferBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val prefs = getSharedPreferences("tgwsproxy", Context.MODE_PRIVATE)
        if (prefs.getBoolean("battery_asked", false)) return

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        prefs.edit().putBoolean("battery_asked", true).apply()
        AlertDialog.Builder(this)
            .setTitle(R.string.battery_title)
            .setMessage(R.string.battery_text)
            .setPositiveButton(R.string.battery_open) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    )
                }
            }
            .setNegativeButton(R.string.battery_later, null)
            .show()
    }
}
