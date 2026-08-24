package com.tgwsproxy.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Возврат прокси к жизни после того, как система его выгрузила.
 *
 * Почему это вообще нужно. Диагностика на устройстве показала три способа,
 * которыми процесс умирает (`dumpsys activity exit-info`):
 *   * REMOVE TASK — пользователь смахнул карточку из «Недавних». На многих
 *     прошивках это убивает и foreground-сервис;
 *   * STOP APP — встроенный «ускоритель»/менеджер батареи прошивки;
 *   * LOW MEMORY — вытеснение под нагрузкой.
 *
 * Первый и третий случай лечатся перезапуском. Второй — нет: Android после
 * принудительной остановки держит приложение в stopped-состоянии, и ни
 * будильники, ни broadcast-приёмники не срабатывают, пока пользователь сам не
 * откроет приложение. Это гарантия ОС, обходить её нельзя и не нужно —
 * правильный ответ там лежит в настройках прошивки (закрепить приложение
 * в «Недавних», разрешить автозапуск).
 */
object Restarter {

    private const val TAG = "TgWsProxy"
    private const val REQ_WATCHDOG = 1001
    private const val REQ_KICK = 1002

    /**
     * Периодичность сторожевого будильника.
     *
     * 10 минут — это практический пол: в Doze система не даёт неточным
     * будильникам `setAndAllowWhileIdle` срабатывать чаще примерно раза в
     * 9 минут, чаще ставить бессмысленно.
     */
    private const val WATCHDOG_INTERVAL_MS = 10 * 60 * 1000L

    fun scheduleWatchdog(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, REQ_WATCHDOG, WatchdogReceiver.ACTION_CHECK)
        val at = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось поставить сторожевой будильник", t)
        }
    }

    fun cancelWatchdog(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            am.cancel(pendingIntent(context, REQ_WATCHDOG, WatchdogReceiver.ACTION_CHECK))
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось снять сторожевой будильник", t)
        }
    }

    /**
     * Подъём после смахивания из «Недавних».
     *
     * Сознательно неточный будильник (`setAndAllowWhileIdle`): точные на
     * Android 12+ требуют разрешения SCHEDULE_EXACT_ALARM, которое для прокси
     * получить нельзя и не нужно. Задержка в минуту-другую здесь приемлема —
     * это несравнимо лучше, чем не подняться совсем.
     */
    fun scheduleKick(context: Context, delayMs: Long = 2000L) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, REQ_KICK, WatchdogReceiver.ACTION_CHECK)
        val at = System.currentTimeMillis() + delayMs
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось поставить будильник на подъём", t)
        }
    }

    fun startProxyService(context: Context) {
        val intent = Intent(context, ProxyService::class.java)
            .setAction(ProxyService.ACTION_START)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (t: Throwable) {
            // На Android 12+ фоновый запуск foreground-сервиса запрещён вне
            // разрешённых окон. Будильник даёт такое окно, но если система
            // всё-таки отказала — просто ждём следующего срабатывания.
            Log.w(TAG, "Запуск сервиса отклонён системой: ${t.message}")
        }
    }

    private fun pendingIntent(context: Context, req: Int, action: String): PendingIntent {
        val intent = Intent(context, WatchdogReceiver::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, req, intent, flags)
    }
}

/**
 * Будильник живучести.
 *
 * Делает две вещи, и вторая важнее первой:
 *
 * 1. Поднимает прокси, если сервис не работает, а пользователь его не выключал.
 * 2. **Будит замерший процесс.** На прошивках Transsion наблюдалось: сервис
 *    числится foreground, wake lock держится, `oom_score_adj=0`, системная
 *    заморозка выключена — и всё равно потоки перестают исполняться. Признак:
 *    в очереди приёма на порту копятся десятки непринятых соединений, Telegram
 *    показывает бесконечное «подключение прокси», а стоит открыть приложение —
 *    очередь мгновенно разбирается. Доставка широковещательного сообщения
 *    планирует процесс к исполнению, и этого достаточно, чтобы он ожил.
 *    Поэтому здесь намеренно дёргается Python, даже когда всё «хорошо».
 */
class WatchdogReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CHECK = "com.tgwsproxy.android.WATCHDOG_CHECK"
        private const val TAG = "TgWsProxy"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Restarter.scheduleWatchdog(context)
        if (!ProxyConfigStore.shouldRun(context)) return

        val pending = goAsync()
        Thread {
            try {
                if (!ProxyService.isRunning) {
                    Log.i(TAG, "Сторож: прокси не работает, поднимаем")
                    Restarter.startProxyService(context)
                    return@Thread
                }
                // Обращение к Python заставляет его потоки получить процессорное
                // время. Заодно проверяем, что мост вообще отвечает.
                val status = runCatching { PythonBridge.status(context) }.getOrNull()
                if (status == null || !status.contains("\"state\": \"running\"")) {
                    Log.w(TAG, "Сторож: ядро не отвечает как работающее, перезапускаем")
                    Restarter.startProxyService(context)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Сторож: ошибка проверки", t)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/** Подъём после перезагрузки телефона и после обновления приложения. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // Два условия: пользователь не выключал прокси сам И не снял галочку
        // «Запускать после включения телефона» в настройках.
        if (!ProxyConfigStore.shouldRun(context)) return
        if (!ProxyConfigStore.autostart(context)) return
        Log.i("TgWsProxy", "Загрузка/обновление: поднимаем прокси")
        Restarter.startProxyService(context)
        Restarter.scheduleWatchdog(context)
    }
}
