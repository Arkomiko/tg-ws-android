package com.tgwsproxy.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast

/**
 * Экран настроек — повторяет окно настроек десктопной версии
 * (`ui/ctk_tray_ui.py:install_tray_config_form`): те же разделы, те же поля,
 * те же формулировки из `ui/i18n`.
 *
 * Отличия продиктованы платформой: автозапуск Windows заменён на запуск после
 * включения телефона, проверки обновлений с GitHub нет (на Android нет канала
 * доставки), зато добавлено предупреждение про порты ниже 1024.
 *
 * Форма собирается кодом, а не XML: полей полтора десятка, все однотипные,
 * и helper-функции дают меньше кода и гарантированно одинаковый вид.
 */
class SettingsActivity : ThemedActivity() {

    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var secret: EditText
    private lateinit var dcIp: EditText
    private lateinit var poolSize: EditText
    private lateinit var bufKb: EditText
    private lateinit var logMaxMb: EditText
    private lateinit var verbose: CheckBox
    private lateinit var cfproxy: CheckBox
    private lateinit var cfCustom: CheckBox
    private lateinit var cfDomains: EditText
    private lateinit var cfWorkerEnabled: CheckBox
    private lateinit var cfWorker: EditText
    private lateinit var forceTestDc: CheckBox
    private lateinit var autostart: CheckBox
    private lateinit var tunnel: CheckBox
    private lateinit var themeSpinner: Spinner
    private lateinit var langSpinner: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.AppTheme)
        setContentView(buildForm())
        setTitle(R.string.settings_title)
    }

    // сборка формы

    private fun buildForm(): View {
        val cfg = ProxyConfigStore.load(this)
        val pad = dp(24)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // заголовок
        root.addView(header())

        // Интерфейс
        section(root, R.string.section_interface)
        // Набор совпадает с десктопной версией: там тоже только два языка
        // и нет пункта «как в системе» — при первом запуске язык
        // подбирается автоматически, дальше выбор явный.
        langSpinner = spinner(
            root, R.string.lbl_language,
            listOf("Русский", "English"),
            if (AppLocale.resolveLanguage(this) == AppLocale.LANG_EN) 1 else 0,
        )
        themeSpinner = spinner(
            root, R.string.lbl_theme,
            listOf(
                getString(R.string.theme_auto),
                getString(R.string.theme_light),
                getString(R.string.theme_dark),
            ),
            when (ProxyConfigStore.theme(this)) {
                AppLocale.THEME_LIGHT -> 1
                AppLocale.THEME_DARK -> 2
                else -> 0
            },
        )

        // MTProto
        section(root, R.string.section_mtproto)
        host = field(root, R.string.lbl_host, cfg.host, R.string.tip_host)
        port = field(root, R.string.lbl_port, cfg.port.toString(), R.string.tip_port, numeric = true)
        secret = field(root, R.string.lbl_secret, cfg.secret, R.string.tip_secret)
        root.addView(secondaryButton(getString(R.string.btn_regen)) {
            secret.setText(ProxyConfigStore.regenerateSecret(this))
        })

        // Датацентры
        section(root, R.string.section_dc)
        hint(root, R.string.lbl_dc_hint)
        dcIp = field(
            root, null, cfg.dcIp.joinToString("\n"), R.string.tip_dc, multiline = true
        )

        // Cloudflare Proxy
        section(root, R.string.section_cfproxy)
        cfproxy = check(root, R.string.lbl_cf_enable, cfg.cfproxy, R.string.tip_cfproxy)
        cfCustom = check(
            root, R.string.lbl_cf_custom, cfg.cfproxyUserDomainEnabled, R.string.tip_cf_custom
        )
        cfDomains = field(root, null, cfg.cfproxyUserDomains.joinToString(", "), null)
        cfCustom.setOnCheckedChangeListener { _, v -> cfDomains.isEnabled = v }
        cfDomains.isEnabled = cfCustom.isChecked

        // Cloudflare Worker
        section(root, R.string.section_cfworker)
        cfWorkerEnabled = check(
            root, R.string.lbl_cf_custom, cfg.cfWorkerEnabled, R.string.tip_cfworker
        )
        hint(root, R.string.lbl_cfworker_domains)
        cfWorker = field(root, null, cfg.cfWorkerDomains.joinToString(", "), null)
        cfWorkerEnabled.setOnCheckedChangeListener { _, v -> cfWorker.isEnabled = v }
        cfWorker.isEnabled = cfWorkerEnabled.isChecked

        // Логи и производительность
        section(root, R.string.section_logs)
        verbose = check(root, R.string.lbl_verbose, cfg.verbose, R.string.tip_verbose)
        bufKb = field(root, R.string.lbl_buf_kb, cfg.bufKb.toString(), null, numeric = true)
        poolSize = field(root, R.string.lbl_pool_size, cfg.poolSize.toString(), null, numeric = true)
        logMaxMb = field(root, R.string.lbl_log_max_mb, cfg.logMaxMb.toString(), null, numeric = true)

        // Дополнительно
        section(root, R.string.section_extra)
        autostart = check(root, R.string.lbl_autostart, ProxyConfigStore.autostart(this), null)
        tunnel = check(
            root, R.string.lbl_tunnel, ProxyConfigStore.tunnelEnabled(this), R.string.tip_tunnel
        )
        // Согласие на VPN система спрашивает только из Activity, поэтому просим
        // его сразу при включении галочки, а не при первом подъёме туннеля из
        // сервиса — там показать диалог уже негде.
        tunnel.setOnCheckedChangeListener { _, checked ->
            if (!checked) return@setOnCheckedChangeListener
            if (TunnelService.foreignVpnActive(this)) {
                // Спрашивать согласие прямо сейчас нельзя: VpnService.prepare
                // отбирает роль VPN у активного приложения и рвёт его туннель.
                // Настройку принимаем, согласие спросим позже — при следующем
                // открытии главного экрана без чужого VPN.
                Toast.makeText(this, R.string.tunnel_wait_foreign, Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            requestTunnelConsent()
        }
        forceTestDc = check(
            root, R.string.lbl_force_test_dc, cfg.forceTestDc, R.string.tip_force_test_dc
        )

        // кнопки
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                .also { it.topMargin = dp(20); it.bottomMargin = dp(18) }
            setBackgroundColor(getColor(R.color.field_border))
        })
        root.addView(primaryButton(getString(R.string.btn_save)) { onSave() })
        root.addView(secondaryButton(getString(R.string.btn_cancel)) { finish() })

        root.addView(footer())

        return ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.bg))
            isFillViewport = true
            addView(root)
        }
    }

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(4), dp(34))
                .also { it.rightMargin = dp(12) }
            background = getDrawable(R.drawable.bg_accent_bar)
        })
        addView(TextView(context).apply {
            setText(R.string.settings_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
        })
    }

    /** Версия приложения — как в заголовке окна настроек десктопной версии. */
    private fun footer(): View = TextView(this).apply {
        text = "v" + BuildConfigCompat.versionName(this@SettingsActivity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(getColor(R.color.text_secondary))
        gravity = Gravity.CENTER
        setPadding(0, dp(24), 0, dp(4))
    }

    // элементы

    private fun section(parent: LinearLayout, titleRes: Int) {
        parent.addView(TextView(this).apply {
            setText(titleRes)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, dp(22), 0, dp(8))
        })
    }

    private fun hint(parent: LinearLayout, textRes: Int) {
        parent.addView(TextView(this).apply {
            setText(textRes)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, 0, 0, dp(4))
        })
    }

    private fun field(
        parent: LinearLayout,
        labelRes: Int?,
        value: String,
        tipRes: Int?,
        numeric: Boolean = false,
        multiline: Boolean = false,
    ): EditText {
        if (labelRes != null) {
            parent.addView(TextView(this).apply {
                setText(labelRes)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(8), 0, dp(2))
            })
        }
        val edit = EditText(this).apply {
            setText(value)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.text_primary))
            background = getDrawable(R.drawable.bg_button_secondary)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = when {
                numeric -> InputType.TYPE_CLASS_NUMBER
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multiline) {
                setSingleLine(false)
                minLines = 2
                gravity = Gravity.TOP or Gravity.START
            }
        }
        parent.addView(edit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        if (tipRes != null) tip(parent, tipRes)
        return edit
    }

    private fun check(
        parent: LinearLayout, labelRes: Int, value: Boolean, tipRes: Int?,
    ): CheckBox {
        val cb = CheckBox(this).apply {
            setText(labelRes)
            isChecked = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.text_primary))
            minHeight = dp(48)
        }
        parent.addView(cb)
        if (tipRes != null) tip(parent, tipRes)
        return cb
    }

    private fun tip(parent: LinearLayout, tipRes: Int) {
        parent.addView(TextView(this).apply {
            setText(tipRes)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(2), 0, dp(6))
        })
    }

    private fun spinner(
        parent: LinearLayout, labelRes: Int, items: List<String>, selected: Int,
    ): Spinner {
        parent.addView(TextView(this).apply {
            setText(labelRes)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(8), 0, dp(2))
        })
        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                items,
            )
            setSelection(selected)
            background = getDrawable(R.drawable.bg_button_secondary)
            minimumHeight = dp(48)
        }
        parent.addView(sp, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return sp
    }

    private fun primaryButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setTextColor(getColor(R.color.text_on_blue))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
            isAllCaps = false
            background = getDrawable(R.drawable.bg_button_primary)
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
            )
            setOnClickListener { onClick() }
        }

    private fun secondaryButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setTextColor(getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isAllCaps = false
            background = getDrawable(R.drawable.bg_button_secondary)
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
            ).also { it.topMargin = dp(10) }
            setOnClickListener { onClick() }
        }

    // сохранение

    /**
     * Просит системное согласие на туннель. Если согласие уже выдано, диалога
     * не будет: VpnService.prepare вернёт null.
     */
    private fun requestTunnelConsent() {
        val intent = runCatching { android.net.VpnService.prepare(this) }.getOrNull()
        if (intent == null) return
        runCatching { startActivityForResult(intent, REQ_TUNNEL_CONSENT) }
            .onFailure {
                Toast.makeText(this, R.string.tunnel_consent_denied, Toast.LENGTH_LONG).show()
                tunnel.isChecked = false
            }
    }

    @Deprecated("onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TUNNEL_CONSENT) return
        if (resultCode != Activity.RESULT_OK) {
            // Отказ — снимаем галочку, иначе настройка врала бы о состоянии.
            tunnel.isChecked = false
            Toast.makeText(this, R.string.tunnel_consent_denied, Toast.LENGTH_LONG).show()
        }
    }

    private fun onSave() {
        val error = ProxyConfigStore.save(
            context = this,
            host = host.text.toString(),
            port = port.text.toString(),
            secret = secret.text.toString(),
            dcIp = dcIp.text.toString(),
            poolSize = poolSize.text.toString(),
            bufKb = bufKb.text.toString(),
            logMaxMb = logMaxMb.text.toString(),
            verbose = verbose.isChecked,
            cfproxy = cfproxy.isChecked,
            cfDomainsEnabled = cfCustom.isChecked,
            cfDomains = cfDomains.text.toString(),
            cfWorkerEnabled = cfWorkerEnabled.isChecked,
            cfWorker = cfWorker.text.toString(),
            forceTestDc = forceTestDc.isChecked,
        )
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            return
        }

        ProxyConfigStore.setAutostart(this, autostart.isChecked)
        ProxyConfigStore.setTunnelEnabled(this, tunnel.isChecked)

        val newLang = if (langSpinner.selectedItemPosition == 1) {
            AppLocale.LANG_EN
        } else {
            AppLocale.LANG_RU
        }
        val newTheme = when (themeSpinner.selectedItemPosition) {
            1 -> AppLocale.THEME_LIGHT
            2 -> AppLocale.THEME_DARK
            else -> AppLocale.THEME_AUTO
        }
        val appearanceChanged =
            newLang != AppLocale.resolveLanguage(this) ||
                newTheme != ProxyConfigStore.theme(this)
        ProxyConfigStore.setLanguage(this, newLang)
        ProxyConfigStore.setTheme(this, newTheme)

        // Настройки применяются к уже запущенному ядру только перезапуском:
        // proxy_config читается один раз при старте _run().
        if (ProxyService.isRunning) {
            ProxyRestart.restart(this)
            Toast.makeText(this, R.string.saved, Toast.LENGTH_LONG).show()
        }

        // Язык и тема подставляются в attachBaseContext, то есть влияют
        // только на вновь создаваемые экраны. Уже открытые нужно пересоздать,
        // иначе изменение выглядит как «не сработало».
        if (appearanceChanged) {
            val restart = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(restart)
        }
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Код запроса системного согласия на туннель. */
        private const val REQ_TUNNEL_CONSENT = 1001

        fun open(context: Context) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }
}

/** Версия приложения без генерации BuildConfig. */
object BuildConfigCompat {
    fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (t: Throwable) {
        "?"
    }
}

/** Перезапуск прокси: остановить и поднять заново с новым конфигом. */
object ProxyRestart {
    fun restart(context: Context) {
        val stop = Intent(context, ProxyService::class.java)
            .setAction(ProxyService.ACTION_STOP)
        context.startService(stop)
        android.os.Handler(context.mainLooper).postDelayed({
            val start = Intent(context, ProxyService::class.java)
                .setAction(ProxyService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(start)
            } else {
                context.startService(start)
            }
        }, 1500)
    }
}
