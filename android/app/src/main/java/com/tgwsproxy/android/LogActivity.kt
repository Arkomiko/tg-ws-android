package com.tgwsproxy.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Просмотр лога прокси — аналог пункта «Открыть логи» в трее Windows.
 *
 * На десктопе файл просто открывается системным редактором. На Android файл
 * лежит в приватном каталоге приложения, и добраться до него иначе нельзя,
 * поэтому показываем содержимое здесь же и даём скопировать.
 */
class LogActivity : ThemedActivity() {

    private lateinit var body: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.AppTheme)
        setTitle(R.string.log_title)

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(getColor(R.color.bg))
        }

        root.addView(TextView(this).apply {
            setText(R.string.log_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, 0, 0, dp(12))
        })

        body = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            setTextColor(getColor(R.color.text_secondary))
            setTextIsSelectable(true)
        }
        // Лог длинный, поэтому горизонтальная прокрутка отдельно от вертикальной:
        // иначе длинные строки переносятся и читать сложнее.
        val hScroll = android.widget.HorizontalScrollView(this).apply { addView(body) }
        root.addView(ScrollView(this).apply { addView(hScroll) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        row.addView(button(getString(R.string.log_copy)) { copy() },
            LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(button(getString(R.string.log_clear)) { clear() },
            LinearLayout.LayoutParams(0, dp(48), 1f).also { it.leftMargin = dp(10) })
        root.addView(row)

        setContentView(root)
        load()
    }

    private fun logFile() = File(filesDir, "proxy.log")

    private fun load() {
        val f = logFile()
        val text = if (f.isFile && f.length() > 0) {
            // Показываем хвост: лог до 2 МБ, целиком в TextView он не нужен.
            val all = runCatching { f.readLines() }.getOrDefault(emptyList())
            all.takeLast(600).joinToString(System.lineSeparator())
        } else {
            getString(R.string.log_empty)
        }
        body.text = text
    }

    private fun copy() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("tg-ws-proxy log", body.text))
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun clear() {
        runCatching { logFile().writeText("") }
        load()
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        isAllCaps = false
        background = getDrawable(R.drawable.bg_button_secondary)
        stateListAnimator = null
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, LogActivity::class.java))
        }
    }
}
