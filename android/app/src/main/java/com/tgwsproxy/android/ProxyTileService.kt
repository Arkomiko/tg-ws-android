package com.tgwsproxy.android

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * Плитка в шторке быстрых настроек — рядом с Wi-Fi, Bluetooth, фонариком.
 *
 * Короткое нажатие включает и выключает прокси, долгое открывает приложение
 * (для этого у MainActivity объявлен intent-filter QS_TILE_PREFERENCES).
 *
 * Плитка полезна не только для удобства: на прошивках, которые усыпляют
 * фоновые процессы, самый быстрый способ вернуть прокси к жизни — тронуть его
 * из шторки, не разыскивая иконку приложения.
 */
class ProxyTileService : TileService() {

    private companion object {
        const val TAG = "TgWsProxy"
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()

        val running = ProxyService.isRunning
        val intent = Intent(this, ProxyService::class.java).setAction(
            if (running) ProxyService.ACTION_STOP else ProxyService.ACTION_START
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Плитка: не удалось переключить прокси", t)
        }

        // Состояние прокси меняется не мгновенно (старт Python занимает
        // секунду-другую), поэтому сразу показываем промежуточное состояние,
        // а через паузу перечитываем настоящее.
        setTileState(if (running) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE, running)
        qsTile?.let { tile ->
            tile.subtitleCompat(if (running) null else getString(R.string.title_starting))
            tile.updateTile()
        }

        android.os.Handler(mainLooper).postDelayed({ refreshTile() }, 3000)
    }

    private fun refreshTile() {
        setTileState(
            if (ProxyService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE,
            ProxyService.isRunning,
        )
    }

    private fun setTileState(state: Int, wasRunning: Boolean) {
        val tile = qsTile ?: return
        tile.state = state
        tile.label = getString(R.string.app_name)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        tile.subtitleCompat(
            if (state == Tile.STATE_ACTIVE) getString(R.string.tile_on)
            else getString(R.string.tile_off)
        )
        tile.updateTile()
    }

    /** Подзаголовок плитки появился в Android 10; ниже просто игнорируем. */
    private fun Tile.subtitleCompat(text: CharSequence?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            subtitle = text
        }
    }
}

/**
 * Активность-заглушка для долгого нажатия по плитке.
 *
 * Система шлёт QS_TILE_PREFERENCES при долгом удержании плитки; перенаправляем
 * на главный экран. Отдельная активность нужна потому, что MainActivity
 * объявлена как LAUNCHER, и вешать на неё второй фильтр без побочных эффектов
 * не всегда получается на сторонних лаунчерах.
 */
class TilePreferencesActivity : ThemedActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }
}
