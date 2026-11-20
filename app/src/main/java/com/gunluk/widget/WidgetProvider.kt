package com.gunluk.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.widget.RemoteViews
import kotlin.concurrent.thread
import kotlin.math.min

class WidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.gunluk.widget.REFRESH"
        const val ACTION_TICK = "com.gunluk.widget.TICK"
        private const val TICK_MS = 10 * 60_000L

        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = tickIntent(ctx)
            am.setInexactRepeating(
                AlarmManager.RTC,
                System.currentTimeMillis() + TICK_MS,
                TICK_MS,
                pi
            )
        }

        fun cancel(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(tickIntent(ctx))
        }

        private fun tickIntent(ctx: Context): PendingIntent {
            val i = Intent(ctx, WidgetProvider::class.java).setAction(ACTION_TICK)
            return PendingIntent.getBroadcast(
                ctx, 0, i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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

        private fun updateAll(ctx: Context, force: Boolean) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, WidgetProvider::class.java))
            if (ids.isEmpty()) return
            push(ctx, mgr, ids) // önce önbellekle hızlıca çiz
            if (Data.refresh(ctx, force)) push(ctx, mgr, ids)
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

                // saat ve tarih boyutları widget boyutuna göre
                val skyDp = hDp * Renderer.SKY_FRACTION
                val timeDp = min(skyDp * 0.38f, wDp * 0.21f)
                val dateDp = (skyDp * 0.115f).coerceIn(11f, 17f)
                rv.setTextViewTextSize(R.id.time, TypedValue.COMPLEX_UNIT_DIP, timeDp)
                rv.setTextViewTextSize(R.id.date, TypedValue.COMPLEX_UNIT_DIP, dateDp)
                rv.setViewPadding(R.id.top_area, 0, 0, 0, (skyDp * 0.20f * dens).toInt())

                // üst alan: yenile
                val refreshPi = PendingIntent.getBroadcast(
                    ctx, 1,
                    Intent(ctx, WidgetProvider::class.java).setAction(ACTION_REFRESH),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                rv.setOnClickPendingIntent(R.id.top_area, refreshPi)

                // alt şerit: haberi aç
                val link = st.headline?.link
                val newsPi = if (link != null) {
                    PendingIntent.getActivity(
                        ctx, 2,
                        Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                } else {
                    PendingIntent.getActivity(
                        ctx, 3,
                        Intent(ctx, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                }
                rv.setOnClickPendingIntent(R.id.news_hit, newsPi)

                mgr.updateAppWidget(id, rv)
            }
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
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
            ACTION_REFRESH -> {
                Data.bumpNews(ctx)
                go(ctx, true, goAsync())
            }
            ACTION_TICK -> go(ctx, false, goAsync())
            Intent.ACTION_BOOT_COMPLETED -> {
                schedule(ctx)
                go(ctx, false, goAsync())
            }
            else -> super.onReceive(ctx, intent)
        }
    }
}
