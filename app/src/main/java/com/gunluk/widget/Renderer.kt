package com.gunluk.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class Phase(val day: Boolean, val p: Float, val sunrise: Long, val sunset: Long)

/** Hava simgesi türleri */
enum class Kind { SUN, MOON, PARTLY, CLOUD, FOG, RAIN, SNOW, STORM, HOT, COLD }

/** Renk takımı: gündüz krem zemin, gece siyah zemin */
class Theme(val night: Boolean) {
    val bg: Int = if (night) 0xFF111111.toInt() else 0xFFF4F1EA.toInt()
    val text: Int = if (night) 0xFFF4EDE4.toInt() else 0xFF111111.toInt()
    val accent: Int = if (night) 0xFFE8283E.toInt() else 0xFFC8102E.toInt()
    /** Alttaki saat yazıları: koyu zeminde parlak sarı, açık zeminde koyu turuncu */
    val hour: Int = if (night) 0xFFFFC107.toInt() else 0xFFD35400.toInt()
}

/** Tasarım 340x200 birimlik bir tuval; u = bir birimin piksel karşılığı */
class Metrics(val u: Float, val ox: Float, val oy: Float)

/** Çizilecek bilgilerin özeti */
class Info(
    val kind: Kind,
    val warn: String,
    val sub: String,
    val hi: Int?,
    val lo: Int?,
    val slots: List<Slot>
)

object Renderer {

    const val DW = 340f
    const val DH = 200f

    // Tasarım birimleriyle saat ve tarih konumu (TextClock bunlara göre yerleştirilir)
    const val TIME_X = 12f
    const val TIME_BASE = 86f
    const val TIME_SIZE = 62f
    const val DATE_X = 14f
    const val DATE_BASE = 22f
    const val DATE_SIZE = 15f

    private val TR = Locale("tr", "TR")

    fun metrics(w: Int, h: Int): Metrics {
        val u = min(w / DW, h / DH)
        return Metrics(u, (w - DW * u) / 2f, (h - DH * u) / 2f)
    }

    // ---- Gün / gece hesabı ----
    fun phaseOf(w: Weather?, nowMs: Long): Phase {
        val now = nowMs / 1000
        val sr: Long
        val ss: Long
        if (w != null && w.sunset > w.sunrise) {
            sr = w.sunrise
            ss = w.sunset
        } else {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 7); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
            sr = cal.timeInMillis / 1000
            cal.set(Calendar.HOUR_OF_DAY, 17)
            ss = cal.timeInMillis / 1000
        }
        val k = Math.floorDiv(now - sr, 86400L)
        val s0 = sr + k * 86400L
        val e0 = ss + k * 86400L
        return if (now < e0) {
            Phase(true, ((now - s0).toFloat() / (e0 - s0)).coerceIn(0f, 1f), s0, e0)
        } else {
            Phase(false, ((now - e0).toFloat() / (s0 + 86400L - e0)).coerceIn(0f, 1f), s0, e0)
        }
    }

    fun themeOf(s: State, nowMs: Long): Theme = when (s.themeMode) {
        1 -> Theme(false)
        2 -> Theme(!phaseOf(s.weather, nowMs).day)
        else -> Theme(true)
    }

    private fun isNight(epoch: Long, ph: Phase): Boolean {
        val k = Math.floorMod(epoch - ph.sunrise, 86400L)
        return k >= (ph.sunset - ph.sunrise)
    }

    // ---- Hava kodu -> simge / metin ----
    private fun conditionText(code: Int): String = when (code) {
        0 -> "Açık"
        1 -> "Az bulutlu"
        2 -> "Bulutlu"
        3 -> "Kapalı"
        45, 48 -> "Sisli"
        in 51..57 -> "Çiseleme"
        in 61..65 -> "Yağmurlu"
        66, 67 -> "Buzlu yağmur"
        in 71..77 -> "Karlı"
        in 80..82 -> "Sağanak"
        85, 86 -> "Kar sağanağı"
        in 95..99 -> "Fırtına"
        else -> ""
    }

    private fun kindOfCode(code: Int, night: Boolean): Kind = when (code) {
        0, 1 -> if (night) Kind.MOON else Kind.SUN
        2 -> if (night) Kind.CLOUD else Kind.PARTLY
        3 -> Kind.CLOUD
        45, 48 -> Kind.FOG
        in 51..67, in 80..82 -> Kind.RAIN
        in 71..77, 85, 86 -> Kind.SNOW
        in 95..99 -> Kind.STORM
        else -> if (night) Kind.MOON else Kind.SUN
    }

    private fun precip(k: Kind) = k == Kind.RAIN || k == Kind.SNOW || k == Kind.STORM

    private fun severity(sl: Slot, ph: Phase): Int = when (kindOfCode(sl.code, isNight(sl.epoch, ph))) {
        Kind.STORM -> 3
        Kind.SNOW -> 2
        Kind.RAIN -> 1
        else -> 0
    }

    private fun eventName(k: Kind) = when (k) {
        Kind.SNOW -> "KAR"
        Kind.STORM -> "FIRTINA"
        else -> "YAĞMUR"
    }

    private fun hourLabel(epoch: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epoch * 1000))

    /** Şimdi + sonraki 5 saat: ana simge, uyarı yazısı, yüksek/düşük */
    fun analyze(w: Weather?, ph: Phase, nowMs: Long): Info {
        if (w == null) {
            return Info(Kind.CLOUD, "HAVA BEKLENİYOR", "Dokununca yenilenir", null, null, emptyList())
        }
        val nowSec = nowMs / 1000
        val night = !ph.day
        // en az 45 dk sonrası; 2'şer saatlik 5 kutu = sonraki 10 saat
        val upcoming = w.hours.filter { it.epoch >= nowSec + 45 * 60 }.sortedBy { it.epoch }.take(10)
        val slots = ArrayList<Slot>()
        var i = 0
        while (i < upcoming.size && slots.size < 5) {
            val a = upcoming[i]
            val b = upcoming.getOrNull(i + 1)
            // atlanan saatte yağış varsa kaçmasın: iki saatin kötü olanının simgesi
            val worse = if (b != null && severity(b, ph) > severity(a, ph)) b else a
            slots.add(Slot(a.epoch, a.temp, worse.code))
            i += 2
        }

        val curKind = kindOfCode(w.code, night)
        val curPrecip = precip(curKind)
        val ev = upcoming.filter { precip(kindOfCode(it.code, isNight(it.epoch, ph))) }

        val main = when {
            curPrecip -> curKind
            ev.isNotEmpty() -> {
                val kinds = ev.map { kindOfCode(it.code, isNight(it.epoch, ph)) }
                when {
                    kinds.contains(Kind.SNOW) -> Kind.SNOW
                    kinds.contains(Kind.STORM) -> Kind.STORM
                    else -> Kind.RAIN
                }
            }
            w.temp >= 30 -> Kind.HOT
            w.temp <= 5 -> Kind.COLD
            else -> curKind
        }

        val warn = when {
            curPrecip -> "ŞU AN ${eventName(curKind)}"
            ev.isNotEmpty() -> {
                val k = kindOfCode(ev[0].code, isNight(ev[0].epoch, ph))
                "${hourLabel(ev[0].epoch)}'DE ${eventName(k)}"
            }
            w.temp >= 30 -> "ÇOK SICAK · SU İÇ"
            w.temp <= 5 -> "SOĞUK · MONT GİY"
            w.code == 0 -> "AÇIK HAVA"
            else -> conditionText(w.code).uppercase(TR)
        }

        val cond = conditionText(w.code).lowercase(TR)
        val base = if (cond.isEmpty()) "Şimdi ${w.temp}°C" else "Şimdi ${w.temp}°C · $cond"
        // servisin bildirdiği anlık yağış miktarı olduğu gibi gösterilir (yorum katılmaz)
        val sub = if (w.precip >= 0) "$base · ${String.format(Locale.US, "%.1f", w.precip)} mm" else base

        // günün en yüksek / en düşük sıcaklığı (bugünün saatlik verisi + şimdiki)
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis / 1000
        val today = w.hours.filter { it.epoch >= start && it.epoch < start + 86400 }.map { it.temp }
        val all = today + w.temp
        return Info(main, warn, sub, all.max(), all.min(), slots)
    }

    // ---- Çizim yardımcıları ----
    private fun tp(size: Float, color: Int, bold: Boolean, align: Paint.Align, ls: Float = 0f): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            textAlign = align
            letterSpacing = ls
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    /** Yazıyı verilen genişliğe sığana kadar küçült */
    private fun fitText(p: Paint, text: String, maxW: Float, startSize: Float, minSize: Float) {
        var s = startSize
        p.textSize = s
        while (p.measureText(text) > maxW && s > minSize) {
            s -= 0.25f
            p.textSize = s
        }
    }

    private fun drawPin(c: Canvas, x: Float, y: Float, th: Theme) {
        c.save()
        c.translate(x, y)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = th.accent
        val path = Path()
        path.addCircle(0f, -2f, 4f, Path.Direction.CW)
        path.moveTo(-3.7f, 0.6f)
        path.lineTo(0f, 6f)
        path.lineTo(3.7f, 0.6f)
        path.close()
        c.drawPath(path, p)
        p.color = th.bg
        c.drawCircle(0f, -2f, 1.4f, p)
        c.restore()
    }

    // ---- Simgeler (9 birimlik yarıçaplı, merkez 0,0) ----
    private fun drawSun(c: Canvas, th: Theme) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = th.accent
        c.drawCircle(0f, 0f, 5f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.8f
        p.pathEffect = DashPathEffect(floatArrayOf(2f, 3.2f), 0f)
        c.drawCircle(0f, 0f, 8.5f, p)
    }

    private fun drawCloud(c: Canvas, th: Theme) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = th.text
        c.drawOval(RectF(-8f, -3f, 8f, 7f), p)
        c.drawCircle(-3.5f, -1.5f, 4.6f, p)
        c.drawCircle(3f, -3f, 5.4f, p)
    }

    private fun drawMoon(c: Canvas, th: Theme) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = th.text
        c.drawCircle(0f, 0f, 6f, p)
        p.color = th.bg
        c.drawCircle(3f, -2f, 5.4f, p)
    }

    private fun drawUmbrella(c: Canvas, th: Theme) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val canopy = Path()
        canopy.moveTo(-9f, 0f)
        canopy.arcTo(RectF(-9f, -9f, 9f, 9f), 180f, 180f, false)
        for (i in 0 until 4) canopy.rQuadTo(-2.25f, 2.4f, -4.5f, 0f)
        canopy.close()
        p.color = th.accent
        c.drawPath(canopy, p)

        p.style = Paint.Style.STROKE
        p.color = th.bg
        p.alpha = 140
        p.strokeWidth = 0.6f
        c.drawLine(0f, -9f, 0f, 0f, p)
        val rib = Path()
        rib.moveTo(-4.5f, -7.2f); rib.quadTo(-5.5f, -3f, -4.5f, 0f)
        rib.moveTo(4.5f, -7.2f); rib.quadTo(5.5f, -3f, 4.5f, 0f)
        c.drawPath(rib, p)

        p.color = th.text
        p.alpha = 255
        p.strokeWidth = 1.5f
        p.strokeCap = Paint.Cap.ROUND
        val handle = Path()
        handle.moveTo(0f, -9f); handle.lineTo(0f, -11f)
        handle.moveTo(0f, 0f); handle.lineTo(0f, 8f); handle.quadTo(0f, 11f, -3f, 11f)
        c.drawPath(handle, p)
    }

    private fun drawSnow(c: Canvas, th: Theme) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        p.color = th.text
        p.strokeWidth = 1.4f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        c.drawLine(0f, -9f, 0f, 9f, p)
        c.drawLine(-7.8f, -4.5f, 7.8f, 4.5f, p)
        c.drawLine(-7.8f, 4.5f, 7.8f, -4.5f, p)
        val ticks = Path()
        ticks.moveTo(-2f, -7f); ticks.lineTo(0f, -5f); ticks.lineTo(2f, -7f)
        ticks.moveTo(-2f, 7f); ticks.lineTo(0f, 5f); ticks.lineTo(2f, 7f)
        c.drawPath(ticks, p)
        p.style = Paint.Style.FILL
        p.color = th.accent
        c.drawCircle(0f, 0f, 1.7f, p)
    }

    private fun drawHot(c: Canvas, th: Theme) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = th.accent
        c.drawCircle(0f, 0f, 4.5f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.6f
        p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 8) {
            val t = i * PI / 4
            c.drawLine(
                (8 * cos(t)).toFloat(), (8 * sin(t)).toFloat(),
                (11 * cos(t)).toFloat(), (11 * sin(t)).toFloat(), p
            )
        }
        p.color = th.text
        p.strokeWidth = 1.2f
        val wave = Path()
        wave.moveTo(-8f, 15f)
        wave.rQuadTo(2f, -2f, 4f, 0f)
        wave.rQuadTo(2f, 2f, 4f, 0f)
        wave.rQuadTo(2f, -2f, 4f, 0f)
        wave.rQuadTo(2f, 2f, 4f, 0f)
        c.drawPath(wave, p)
    }

    private fun drawCold(c: Canvas, th: Theme) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.FILL
        p.color = th.bg
        c.drawRoundRect(RectF(-1.8f, -10f, 1.8f, 2f), 1.8f, 1.8f, p)
        p.style = Paint.Style.STROKE
        p.color = th.text
        p.strokeWidth = 1f
        c.drawRoundRect(RectF(-1.8f, -10f, 1.8f, 2f), 1.8f, 1.8f, p)
        p.style = Paint.Style.FILL
        c.drawCircle(0f, 4f, 3.6f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.8f
        c.drawLine(0f, 0f, 0f, 3f, p)
        p.color = th.accent
        p.strokeWidth = 1.1f
        p.strokeCap = Paint.Cap.ROUND
        c.drawLine(7f, -9f, 7f, -3f, p)
        c.drawLine(4.4f, -7.5f, 9.6f, -4.5f, p)
        c.drawLine(4.4f, -4.5f, 9.6f, -7.5f, p)
    }

    private fun drawFog(c: Canvas, th: Theme) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        p.color = th.text
        p.strokeWidth = 1.6f
        p.strokeCap = Paint.Cap.ROUND
        for (i in -1..1) c.drawLine(-8f, i * 4.5f, 8f, i * 4.5f, p)
    }

    private fun drawStorm(c: Canvas, th: Theme) {
        c.save()
        c.translate(0f, -3f)
        drawCloud(c, th)
        c.restore()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        p.color = th.accent
        p.strokeWidth = 1.6f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        val bolt = Path()
        bolt.moveTo(1f, 4f); bolt.lineTo(-1.5f, 9f); bolt.lineTo(1.5f, 9.5f); bolt.lineTo(-0.5f, 14f)
        c.drawPath(bolt, p)
    }

    private fun drawKind(c: Canvas, k: Kind, x: Float, y: Float, sc: Float, th: Theme) {
        c.save()
        c.translate(x, y)
        c.scale(sc, sc)
        when (k) {
            Kind.SUN -> drawSun(c, th)
            Kind.MOON -> drawMoon(c, th)
            Kind.CLOUD -> drawCloud(c, th)
            Kind.PARTLY -> {
                c.save(); c.translate(-3f, -3f); c.scale(0.75f, 0.75f); drawSun(c, th); c.restore()
                c.save(); c.translate(3f, 3f); c.scale(0.9f, 0.9f); drawCloud(c, th); c.restore()
            }
            Kind.FOG -> drawFog(c, th)
            Kind.RAIN -> drawUmbrella(c, th)
            Kind.SNOW -> drawSnow(c, th)
            Kind.STORM -> drawStorm(c, th)
            Kind.HOT -> drawHot(c, th)
            Kind.COLD -> drawCold(c, th)
        }
        c.restore()
    }

    // ---- Ana çizim ----
    fun render(w: Int, h: Int, dens: Float, s: State): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val nowMs = System.currentTimeMillis()
        val ph = phaseOf(s.weather, nowMs)
        val th = themeOf(s, nowMs)
        val info = analyze(s.weather, ph, nowMs)
        val m = metrics(w, h)
        val u = m.u

        // zemin
        val corner = min(18f * u, 28f * dens)
        val bgp = Paint(Paint.ANTI_ALIAS_FLAG)
        bgp.color = th.bg
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), corner, corner, bgp)
        if (th.night) {
            val fr = Paint(Paint.ANTI_ALIAS_FLAG)
            fr.style = Paint.Style.STROKE
            fr.color = th.accent
            fr.alpha = 170
            fr.strokeWidth = 0.8f * u
            c.drawRoundRect(
                RectF(0.5f * u, 0.5f * u, w - 0.5f * u, h - 0.5f * u),
                corner, corner, fr
            )
        }

        // bundan sonrası 340x200 tasarım birimleriyle
        c.save()
        c.translate(m.ox, m.oy)
        c.scale(u, u)

        // konum: işaret yazının SOLUNDA, yazının gerçek genişliğine göre
        val place = s.place?.trim()?.uppercase(TR)
        if (!place.isNullOrEmpty()) {
            val pp = tp(10f, th.text, true, Paint.Align.RIGHT, 0.1f)
            fitText(pp, place, 118f, 10f, 6f)
            val tw = pp.measureText(place)
            c.drawText(place, 326f, 17f, pp)
            drawPin(c, 326f - tw - 9f, 13f, th)
        }

        // verinin ne zaman alındığı (eski veri hemen anlaşılsın)
        if (s.fetchedAt > 0L) {
            val up = tp(8f, th.text, false, Paint.Align.RIGHT)
            up.alpha = 150
            c.drawText("güncelleme ${hourLabel(s.fetchedAt / 1000)}", 326f, 29f, up)
        }

        // ana simge ve dev derece
        val big = s.weather?.temp?.let { "$it°" } ?: "--°"
        val k = info.kind
        when (k) {
            Kind.HOT -> drawKind(c, k, 232f, 48f, 2.0f, th)
            else -> drawKind(c, k, 232f, 52f, 2.3f, th)
        }
        val tpBig = tp(42f, th.text, false, Paint.Align.LEFT)
        fitText(tpBig, big, 326f - 258f, 42f, 24f)
        c.drawText(big, 258f, 68f, tpBig)

        // uyarı (solda), yüksek / düşük (sağda)
        val warn = tp(13f, th.accent, true, Paint.Align.LEFT)
        fitText(warn, info.warn, 168f, 13f, 7f)
        c.drawText(info.warn, 14f, 118f, warn)
        if (info.hi != null && info.lo != null) {
            val hiT = "↑ ${info.hi}° YÜKSEK"
            val loT = "↓ ${info.lo}° DÜŞÜK"
            val hp = tp(12f, th.text, true, Paint.Align.LEFT)
            fitText(hp, hiT, 136f, 12f, 7f)
            c.drawText(hiT, 190f, 108f, hp)
            val lp = tp(12f, th.text, true, Paint.Align.LEFT)
            fitText(lp, loT, 136f, 12f, 7f)
            c.drawText(loT, 190f, 126f, lp)
        }

        // ayırıcı çizgi
        val line = Paint(Paint.ANTI_ALIAS_FLAG)
        line.color = th.text
        line.alpha = 200
        line.strokeWidth = 0.8f
        c.drawLine(14f, 138f, 326f, 138f, line)

        // sonraki 10 saat (2'şer saatlik 5 kutu)
        val cxs = floatArrayOf(45f, 107f, 170f, 232f, 295f)
        for ((i, sl) in info.slots.withIndex()) {
            if (i >= cxs.size) break
            val cx = cxs[i]
            val sk = kindOfCode(sl.code, isNight(sl.epoch, ph))
            val hot = precip(sk)
            val hp = tp(10f, th.hour, true, Paint.Align.CENTER)
            c.drawText(hourLabel(sl.epoch), cx, 161f, hp)
            drawKind(c, sk, cx - 14f, 177f, 1.1f, th)
            val tpT = tp(13f, if (hot) th.accent else th.text, true, Paint.Align.LEFT)
            c.drawText("${sl.temp}°", cx - 1f, 182f, tpT)
        }

        c.restore()
        return bmp
    }

    fun scaleForPixels(w: Int, h: Int, maxPixels: Float = 1_100_000f): Float {
        val area = w.toFloat() * h
        return if (area > maxPixels) sqrt(maxPixels / area) else 1f
    }
}
