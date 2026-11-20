package com.gunluk.widget

import android.app.AlarmManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import android.util.TypedValue
import android.widget.RemoteViews
import kotlin.concurrent.thread

class WidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.gunluk.widget.REFRESH"
        const val ACTION_TICK = "com.gunluk.widget.TICK"
        private const val TICK_MS = 10 * 60_000L
        private const val JOB_ID = 4711

        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setInexactRepeating(
                AlarmManager.RTC,
                System.currentTimeMillis() + TICK_MS,
                TICK_MS,
                tickIntent(ctx)
            )
        }

        fun cancel(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(tickIntent(ctx))
        }

        /** Veri gelmediyse bir dakika sonra yeniden dene */
        private fun retrySoon(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(
                AlarmManager.RTC,
                System.currentTimeMillis() + 60_000L,
                retryIntent(ctx)
            )
        }

        private fun tickIntent(ctx: Context): PendingIntent {
            val i = Intent(ctx, WidgetProvider::class.java).setAction(ACTION_TICK)
            return PendingIntent.getBroadcast(
                ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        private fun retryIntent(ctx: Context): PendingIntent {
            val i = Intent(ctx, WidgetProvider::class.java).setAction(ACTION_TICK)
            return PendingIntent.getBroadcast(
                ctx, 5, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        fun go(ctx: Context, force: Boolean, pending: BroadcastReceiver.PendingResult?) {
            val app = ctx.applicationContext
            thread {
                try {
                    updateAll(app, force)
                } catch (e: Exception) {
                    // sessizce geç; bir sonraki turda tekrar denenir
                } finally {
                    pending?.finish()
                }
            }
        }

        /** Telefon uyurken bile sistemin düzenli çalıştırdığı iş (15 dk); alarm tek başına yetmiyor */
        private fun ensureJob(ctx: Context) {
            try {
                val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                if (js.getPendingJob(JOB_ID) != null) return
                js.schedule(
                    JobInfo.Builder(JOB_ID, ComponentName(ctx, RefreshJob::class.java))
                        .setPeriodic(15 * 60_000L)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPersisted(true)
                        .build()
                )
            } catch (e: Exception) {
                // iş kurulamazsa alarm yedek olarak çalışır
            }
        }

        fun updateAll(ctx: Context, force: Boolean) {
            ensureJob(ctx)
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, WidgetProvider::class.java))
            if (ids.isEmpty()) return
            push(ctx, mgr, ids) // önce önbellekle hızlıca çiz
            val moved = try { Loc.update(ctx) } catch (e: Exception) { false } // yer değiştiyse yeni konum
            if (Data.refresh(ctx, force || moved)) push(ctx, mgr, ids)
            else if (moved) push(ctx, mgr, ids)
            if (Data.load(ctx).weather == null) retrySoon(ctx)
        }

        private fun push(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val st = Data.load(ctx)
            val dens = ctx.resources.displayMetrics.density
            for (id in ids) {
                val o = mgr.getAppWidgetOptions(id)
                var wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                var hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
                if (wDp <= 0) wDp = 320
                if (hDp <= 0) hDp = 140

                var wPx = (wDp * dens).toInt().coerceAtLeast(200)
                var hPx = (hDp * dens).toInt().coerceAtLeast(100)
                val sc = Renderer.scaleForPixels(wPx, hPx)
                wPx = (wPx * sc).toInt()
                hPx = (hPx * sc).toInt()

                val bmp = Renderer.render(wPx, hPx, dens * sc, st)
                val rv = RemoteViews(ctx.packageName, R.layout.widget)
                rv.setImageViewBitmap(R.id.bg, bmp)

                // tasarım tuvalinin görünümdeki yeri (bitmap ile aynı oranlar)
                val vw = (wDp * dens).toInt().coerceAtLeast(200)
                val vh = (hDp * dens).toInt().coerceAtLeast(100)
                val m = Renderer.metrics(vw, vh)
                val u = m.u
                val th = Renderer.themeOf(st, System.currentTimeMillis())

                // saat ve tarih canlı TextClock; konumları tasarımdaki yazı tabanına göre
                val asc = 0.93f // yazı tipi yukarı payı (em)
                val timePx = Renderer.TIME_SIZE * u
                val datePx = Renderer.DATE_SIZE * u
                rv.setTextViewTextSize(R.id.time, TypedValue.COMPLEX_UNIT_PX, timePx)
                rv.setTextViewTextSize(R.id.date, TypedValue.COMPLEX_UNIT_PX, datePx)
                rv.setTextColor(R.id.time, th.text)
                rv.setTextColor(R.id.date, th.accent)

                // dokunma alanı geniş olsun diye yazının çevresine boşluk; kutudan düşülür
                val pad = (7f * u).toInt()
                rv.setViewPadding(R.id.time, pad, pad, pad, pad)
                rv.setViewPadding(R.id.date, pad, pad, pad, pad)
                val timeL = (m.ox + Renderer.TIME_X * u).toInt() - pad
                val timeT = (m.oy + (Renderer.TIME_BASE - asc * Renderer.TIME_SIZE) * u).toInt() - pad
                val dateL = (m.ox + Renderer.DATE_X * u).toInt() - pad
                val dateT = (m.oy + (Renderer.DATE_BASE - asc * Renderer.DATE_SIZE) * u).toInt() - pad
                rv.setViewPadding(R.id.time_box, timeL.coerceAtLeast(0), timeT.coerceAtLeast(0), 0, 0)
                rv.setViewPadding(R.id.date_box, dateL.coerceAtLeast(0), dateT.coerceAtLeast(0), 0, 0)

                // dokunma: saat -> saat/alarmlar, tarih -> takvim, diğer yerler -> yenile
                val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                val refreshPi = PendingIntent.getBroadcast(
                    ctx, 1,
                    Intent(ctx, WidgetProvider::class.java).setAction(ACTION_REFRESH),
                    flags
                )
                val clockPi = PendingIntent.getActivity(
                    ctx, 2,
                    Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    flags
                )
                val calPi = PendingIntent.getActivity(
                    ctx, 3,
                    Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    flags
                )
                rv.setOnClickPendingIntent(R.id.bg, refreshPi)
                rv.setOnClickPendingIntent(R.id.time, clockPi)
                rv.setOnClickPendingIntent(R.id.date, calPi)

                mgr.updateAppWidget(id, rv)
            }
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        schedule(ctx)
        go(ctx, false, goAsync())
    }

    override fun onAppWidgetOptionsChanged(
        ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle
    ) {
        go(ctx, false, goAsync())
    }

    override fun onEnabled(ctx: Context) {
        schedule(ctx)
    }

    override fun onDisabled(ctx: Context) {
        cancel(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH -> go(ctx, true, goAsync())
            ACTION_TICK -> go(ctx, false, goAsync())
            Intent.ACTION_BOOT_COMPLETED -> {
                schedule(ctx)
                go(ctx, false, goAsync())
            }
            else -> super.onReceive(ctx, intent)
        }
    }
}
