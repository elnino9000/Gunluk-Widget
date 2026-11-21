package com.gunluk.widget

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

        fun go(ctx: Context, force: Boolean, pending: BroadcastReceiver.PendingResult?) {
            val app = ctx.applicationContext
            thread {
                try {
                    updateAll(app, force)
                } catch (e: Exception) {
                    // sessizce geç; dokununca tekrar denenir
                } finally {
                    pending?.finish()
                }
            }
        }

        /**
         * İnternet ve konum SADECE widget'a dokunulunca (force) kullanılır.
         * Diğer zamanlarda yalnızca telefondaki kayıtlı veriyle yeniden çizilir (pil harcamaz).
         * Tek istisna: hiç veri yoksa ya da uygulama yeni güncellendiyse bir kez çekilir.
         */
        fun updateAll(ctx: Context, force: Boolean) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, WidgetProvider::class.java))
            if (ids.isEmpty()) return
            push(ctx, mgr, ids) // kayıtlı veriyle çiz
            val st = Data.load(ctx)
            if (!force && st.weather != null && !Data.needsMigration(ctx)) return
            val moved = try { Loc.update(ctx) } catch (e: Exception) { false }
            if (Data.refresh(ctx, true) || moved) push(ctx, mgr, ids)
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
        go(ctx, false, goAsync())
    }

    override fun onAppWidgetOptionsChanged(
        ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle
    ) {
        go(ctx, false, goAsync())
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH -> go(ctx, true, goAsync())
            Intent.ACTION_BOOT_COMPLETED -> go(ctx, false, goAsync())
            else -> super.onReceive(ctx, intent)
        }
    }
}
