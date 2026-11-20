package com.gunluk.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class Phase(val day: Boolean, val p: Float, val sunrise: Long, val sunset: Long) {
    /** -1 (gece yarısı) ... 0 (ufuk) ... +1 (öğle) */
    val alt: Float = if (day) sin(PI * p).toFloat() else -sin(PI * p).toFloat()
}

/** Zaman çizelgesindeki bir işaret: hava olayının başladığı/bittiği saat */
class Marker(
    val label: String,
    val temp: Int,
    val code: Int,
    val night: Boolean,
    val notable: Boolean
)

object Renderer {

    /** Gökyüzü (kubbe) kısmının yüksekliğe oranı; altı tepeler + zaman çizelgesi */
    const val SKY_FRACTION = 0.68f

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

    // ---- Gökyüzü renkleri ----
    private val ALTS = floatArrayOf(-1f, -0.3f, -0.1f, 0f, 0.12f, 0.35f, 1f)
    private val TOPS = intArrayOf(
        0xFF0A1030.toInt(), 0xFF111A44.toInt(), 0xFF1F2A66.toInt(), 0xFF34508F.toInt(),
        0xFF3A62A8.toInt(), 0xFF2F68B8.toInt(), 0xFF2A63B5.toInt()
    )
    private val BOTS = intArrayOf(
        0xFF182352.toInt(), 0xFF2A2F6E.toInt(), 0xFF5B3E86.toInt(), 0xFFF08A5D.toInt(),
        0xFFF2B07A.toInt(), 0xFF6AA6DE.toInt(), 0xFF5E9BDB.toInt()
    )

    private fun lerp(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * u).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun skyAt(alt: Float): Pair<Int, Int> {
        val a = alt.coerceIn(-1f, 1f)
        for (i in 0 until ALTS.size - 1) {
            if (a <= ALTS[i + 1]) {
                val t = (a - ALTS[i]) / (ALTS[i + 1] - ALTS[i])
                return lerp(TOPS[i], TOPS[i + 1], t) to lerp(BOTS[i], BOTS[i + 1], t)
            }
        }
        return TOPS.last() to BOTS.last()
    }

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

    private fun cloudCount(code: Int): Int = when (code) {
        0, 1 -> 0
        2 -> 1
        else -> 3
    }

    // ---- Zaman çizelgesi: önemli hava olayları ----
    /** 0 açık, 1 bulutlu, 2 kapalı, 3 sis, 4 yağmur, 5 kar, 6 fırtına */
    private fun cat(code: Int): Int = when (code) {
        0, 1 -> 0
        2 -> 1
        3 -> 2
        45, 48 -> 3
        in 51..67, in 80..82 -> 4
        in 71..77, 85, 86 -> 5
        in 95..99 -> 6
        else -> 0
    }

    private fun isNight(epoch: Long, ph: Phase): Boolean {
        val k = Math.floorMod(epoch - ph.sunrise, 86400L)
        return k >= (ph.sunset - ph.sunrise)
    }

    fun markers(w: Weather, ph: Phase, nowSec: Long): List<Marker> {
        val hrs = w.hours.filter { it.epoch >= nowSec - 3599 && it.epoch <= nowSec + 20 * 3600 }
        if (hrs.isEmpty()) return emptyList()
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        fun label(s: Slot) = fmt.format(Date(s.epoch * 1000))

        val out = ArrayList<Marker>()
        out.add(Marker("Şimdi", hrs[0].temp, hrs[0].code, isNight(nowSec, ph), cat(hrs[0].code) >= 3))
        var prev = cat(hrs[0].code)
        for (i in 1 until hrs.size) {
            val h = hrs[i]
            val cur = cat(h.code)
            val imp = cur >= 3
            val prevImp = prev >= 3
            if (imp && cur != prev) {
                out.add(Marker(label(h), h.temp, h.code, isNight(h.epoch, ph), true))   // olay başlıyor
            } else if (!imp && prevImp) {
                out.add(Marker(label(h), h.temp, h.code, isNight(h.epoch, ph), false))  // olay bitiyor
            }
            prev = cur
        }
        // Önemli bir olay yoksa gün boyu seyri göster
        if (out.size == 1) {
            for (off in intArrayOf(5, 10, 15)) {
                if (off < hrs.size) {
                    val h = hrs[off]
                    out.add(Marker(label(h), h.temp, h.code, isNight(h.epoch, ph), false))
                }
            }
        }
        return out.take(5)
    }

    // ---- İkonlar ----
    private fun drawCloud(c: Canvas, x: Float, y: Float, s: Float, p: Paint) {
        c.drawCircle(x - s * 0.62f, y, s * 0.5f, p)
        c.drawCircle(x, y - s * 0.28f, s * 0.68f, p)
        c.drawCircle(x + s * 0.68f, y, s * 0.5f, p)
        c.drawRoundRect(RectF(x - s * 1.1f, y - s * 0.02f, x + s * 1.18f, y + s * 0.5f), s * 0.25f, s * 0.25f, p)
    }

    private fun drawSun(c: Canvas, x: Float, y: Float, r: Float, a: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFFFFD36B.toInt()
        p.alpha = a
        c.drawCircle(x, y, r * 0.5f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.16f
        p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 8) {
            val t = i * PI / 4
            c.drawLine(
                x + (r * 0.72f * cos(t)).toFloat(), y + (r * 0.72f * sin(t)).toFloat(),
                x + (r * 0.98f * cos(t)).toFloat(), y + (r * 0.98f * sin(t)).toFloat(), p
            )
        }
    }

    private fun drawMoonIcon(c: Canvas, x: Float, y: Float, r: Float, a: Int) {
        val layer = c.saveLayer(RectF(x - r, y - r, x + r, y + r), null)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFFF3F1E4.toInt()
        p.alpha = a
        c.drawCircle(x, y, r * 0.62f, p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        c.drawCircle(x + r * 0.34f, y - r * 0.14f, r * 0.55f, p)
        p.xfermode = null
        c.restoreToCount(layer)
    }

    private fun drawCloudIcon(c: Canvas, x: Float, y: Float, r: Float, a: Int, dark: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = if (dark) 0xFFC9D2E0.toInt() else 0xFFFFFFFF.toInt()
        p.alpha = a
        drawCloud(c, x, y, r * 0.5f, p)
    }

    /** (x, y) ikonun merkezi, r yarıçapı */
    private fun drawWeatherIcon(c: Canvas, code: Int, night: Boolean, x: Float, y: Float, r: Float, a: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (code) {
            0, 1 -> if (night) drawMoonIcon(c, x, y, r, a) else drawSun(c, x, y, r, a)
            2 -> {
                if (night) drawMoonIcon(c, x - r * 0.25f, y - r * 0.25f, r * 0.9f, a)
                else drawSun(c, x - r * 0.25f, y - r * 0.25f, r * 0.85f, a)
                drawCloudIcon(c, x + r * 0.12f, y + r * 0.22f, r, a, false)
            }
            3 -> drawCloudIcon(c, x, y, r, a, true)
            45, 48 -> {
                p.color = 0xFFDDE3EC.toInt(); p.alpha = a
                p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.16f; p.strokeCap = Paint.Cap.ROUND
                for (i in -1..1) c.drawLine(x - r * 0.8f, y + i * r * 0.42f, x + r * 0.8f, y + i * r * 0.42f, p)
            }
            in 71..77, 85, 86 -> {
                drawCloudIcon(c, x, y - r * 0.18f, r, a, true)
                p.color = 0xFFFFFFFF.toInt(); p.alpha = a
                for (i in -1..1) c.drawCircle(x + i * r * 0.5f, y + r * 0.72f, r * 0.10f, p)
            }
            in 95..99 -> {
                drawCloudIcon(c, x, y - r * 0.22f, r, a, true)
                p.color = 0xFFFFD36B.toInt(); p.alpha = a
                p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.16f; p.strokeCap = Paint.Cap.ROUND
                c.drawLine(x + r * 0.1f, y + r * 0.2f, x - r * 0.1f, y + r * 0.55f, p)
                c.drawLine(x - r * 0.1f, y + r * 0.55f, x + r * 0.15f, y + r * 0.6f, p)
                c.drawLine(x + r * 0.15f, y + r * 0.6f, x - r * 0.05f, y + r * 0.95f, p)
            }
            else -> { // yağmur, çise, sağanak
                drawCloudIcon(c, x, y - r * 0.18f, r, a, true)
                p.color = 0xFF8FD3FF.toInt(); p.alpha = a
                p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.16f; p.strokeCap = Paint.Cap.ROUND
                for (i in -1..1) {
                    c.drawLine(
                        x + i * r * 0.5f + r * 0.1f, y + r * 0.48f,
                        x + i * r * 0.5f - r * 0.1f, y + r * 0.88f, p
                    )
                }
            }
        }
    }

    /** Yazıyı verilen genişliğe sığana kadar küçült */
    private fun fitText(p: Paint, text: String, maxW: Float, startSize: Float, minSize: Float) {
        var s = startSize
        p.textSize = s
        while (p.measureText(text) > maxW && s > minSize) {
            s -= 0.5f
            p.textSize = s
        }
    }

    // ---- Ana çizim ----
    fun render(w: Int, h: Int, dens: Float, s: State): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val wf = w.toFloat()
        val hf = h.toFloat()
        val nowMs = System.currentTimeMillis()

        val ph = phaseOf(s.weather, nowMs)
        val alt = ph.alt
        val hy = hf * SKY_FRACTION     // ufuk çizgisi
        val cx = wf / 2f
        val rx = wf * 0.42f
        val ry = hf * 0.60f

        val corner = min(hf * 0.16f, 28f * dens)
        c.clipPath(Path().apply {
            addRoundRect(RectF(0f, 0f, wf, hf), corner, corner, Path.Direction.CW)
        })

        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1) gökyüzü
        val (top, bot) = skyAt(alt)
        p.shader = LinearGradient(0f, 0f, 0f, hf, top, bot, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, wf, hf, p)
        p.shader = null

        // güneş / ay konumu
        val ang = Math.toRadians((180.0 + 180.0 * ph.p))
        val bx = cx + rx * cos(ang).toFloat()
        val by = hy + ry * sin(ang).toFloat()

        // 2) alacakaranlık ışıltısı
        val tw = (1f - abs(alt) / 0.28f).coerceIn(0f, 1f)
        if (tw > 0f) {
            val glowColor = if (ph.day) 0xFFFFA45B.toInt() else 0xFFE0707A.toInt()
            p.shader = RadialGradient(
                bx, hy, wf * 0.55f,
                intArrayOf((glowColor and 0x00FFFFFF) or ((tw * 150).toInt() shl 24), glowColor and 0x00FFFFFF),
                null, Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, wf, hf, p)
            p.shader = null
        }

        // 3) yıldızlar
        val nf = ((-alt - 0.05f) / 0.3f).coerceIn(0f, 1f)
        if (nf > 0f) {
            val rng = Random(11)
            p.color = 0xFFFFFFFF.toInt()
            repeat(48) {
                val x = rng.nextFloat() * wf
                val y = rng.nextFloat() * hy * 0.92f
                val r = (0.5f + rng.nextFloat() * 1.0f) * dens
                val a = nf * (0.35f + 0.65f * rng.nextFloat())
                p.alpha = (a * 255).toInt()
                c.drawCircle(x, y, r, p)
            }
            p.alpha = 255
        }

        // 4) bulutlar
        val weather = s.weather
        if (weather != null) {
            val n = cloudCount(weather.code)
            if (n > 0) {
                val dayF = ((alt + 0.2f) / 0.6f).coerceIn(0f, 1f)
                p.color = 0xFFFFFFFF.toInt()
                p.alpha = ((0.10f + 0.14f * dayF) * 255).toInt()
                for (i in 0 until n) {
                    val x = wf * (0.22f + 0.30f * i)
                    val y = hy * (0.34f + 0.10f * (i % 2))
                    drawCloud(c, x, y, hf * (0.06f + 0.010f * i), p)
                }
                p.alpha = 255
            }
        }

        // 5) yay
        val oval = RectF(cx - rx, hy - ry, cx + rx, hy + ry)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 1.6f * dens
        p.color = 0xFFFFFFFF.toInt()
        p.alpha = 90
        p.pathEffect = DashPathEffect(floatArrayOf(2f * dens, 6f * dens), 0f)
        c.drawArc(oval, 180f, 180f, false, p)
        p.pathEffect = null
        p.strokeWidth = 2.4f * dens
        p.alpha = if (ph.day) 215 else 130
        if (ph.p > 0.003f) c.drawArc(oval, 180f, 180f * ph.p, false, p)
        p.alpha = 255
        p.style = Paint.Style.FILL

        // 6) güneş veya ay
        if (ph.day) {
            val r = hf * 0.048f
            val warm = ((0.5f - alt) / 0.5f).coerceIn(0f, 1f)
            val glowC = lerp(0xFFFFE9A8.toInt(), 0xFFFF9A4D.toInt(), warm)
            p.shader = RadialGradient(
                bx, by, r * 4.6f,
                intArrayOf((glowC and 0x00FFFFFF) or (0x88 shl 24), glowC and 0x00FFFFFF),
                null, Shader.TileMode.CLAMP
            )
            c.drawCircle(bx, by, r * 4.6f, p)
            p.shader = RadialGradient(
                bx - r * 0.3f, by - r * 0.3f, r * 1.5f,
                lerp(0xFFFFFFE8.toInt(), 0xFFFFE9B0.toInt(), warm),
                lerp(0xFFFFD36B.toInt(), 0xFFFF9A4D.toInt(), warm),
                Shader.TileMode.CLAMP
            )
            c.drawCircle(bx, by, r, p)
            p.shader = null
        } else {
            val r = hf * 0.044f
            p.shader = RadialGradient(
                bx, by, r * 4f,
                intArrayOf(0x44CFE0FF, 0x00CFE0FF),
                null, Shader.TileMode.CLAMP
            )
            c.drawCircle(bx, by, r * 4f, p)
            p.shader = null
            val layer = c.saveLayer(RectF(bx - r * 2, by - r * 2, bx + r * 2, by + r * 2), null)
            p.color = 0xFFF3F1E4.toInt()
            c.drawCircle(bx, by, r, p)
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            c.drawCircle(bx + r * 0.58f, by - r * 0.22f, r * 0.9f, p)
            p.xfermode = null
            c.restoreToCount(layer)
        }

        // 7) ufuk: tepeler (güneş/ay buraya batar), altı zaman çizelgesi zemini
        val g = Path()
        g.moveTo(0f, hy + hf * 0.015f)
        g.cubicTo(wf * 0.25f, hy - hf * 0.035f, wf * 0.45f, hy + hf * 0.03f, wf * 0.65f, hy)
        g.cubicTo(wf * 0.80f, hy - hf * 0.02f, wf * 0.92f, hy + hf * 0.015f, wf, hy - hf * 0.008f)
        g.lineTo(wf, hf)
        g.lineTo(0f, hf)
        g.close()
        p.shader = LinearGradient(
            0f, hy - hf * 0.04f, 0f, hf,
            0x59000000, 0xA6000000.toInt(), Shader.TileMode.CLAMP
        )
        c.drawPath(g, p)
        p.shader = null

        // 8) gün doğumu / batımı saatleri (iki uçta)
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val small = (hf * 0.062f).coerceIn(8f * dens, 11.5f * dens)
        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            alpha = 190
            textSize = small
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val rowA = hy + hf * 0.085f
        c.drawText(fmt.format(Date(ph.sunrise * 1000)), cx - rx, rowA, lp)
        c.drawText(fmt.format(Date(ph.sunset * 1000)), cx + rx, rowA, lp)

        val pad = min(wf * 0.045f, 16f * dens)

        if (weather != null) {
            // 9) şu anki hava: sol üst, büyük
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = (hf * 0.19f).coerceIn(18f * dens, 42f * dens)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setShadowLayer(4f * dens, 0f, 1f * dens, 0x66000000)
            }
            val tBase = pad + tp.textSize * 0.80f
            c.drawText("${weather.temp}°", pad, tBase, tp)

            val maxW = wf * 0.215f - pad
            val cp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                alpha = 235
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setShadowLayer(3f * dens, 0f, 1f * dens, 0x66000000)
            }
            val cond = conditionText(weather.code)
            if (cond.isNotEmpty()) {
                fitText(cp, cond, maxW, (hf * 0.082f).coerceIn(10f * dens, 14f * dens), 8f * dens)
                c.drawText(cond, pad, tBase + cp.textSize * 1.25f, cp)
            }

            // sağ üst: bugünün en yüksek / en düşük derecesi
            val win = weather.hours.filter {
                it.epoch >= nowMs / 1000 - 3599 && it.epoch <= nowMs / 1000 + 18 * 3600
            }
            if (win.isNotEmpty()) {
                val hi = win.maxOf { it.temp }
                val lo = win.minOf { it.temp }
                val hp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFFFFFFF.toInt()
                    alpha = 235
                    textAlign = Paint.Align.RIGHT
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setShadowLayer(3f * dens, 0f, 1f * dens, 0x66000000)
                }
                fitText(hp, "↑$hi°", maxW, (hf * 0.095f).coerceIn(10f * dens, 15f * dens), 8f * dens)
                val y1 = pad + hp.textSize * 0.85f
                c.drawText("↑$hi°", wf - pad, y1, hp)
                c.drawText("↓$lo°", wf - pad, y1 + hp.textSize * 1.3f, hp)
            }

            // 10) zaman çizelgesi: önemli hava olayları küçük saatiyle
            val mk = markers(weather, ph, nowMs / 1000)
            if (mk.isNotEmpty()) {
                val ir = (hf * 0.055f).coerceIn(6f * dens, 10f * dens)
                val tiny = (hf * 0.052f).coerceIn(7f * dens, 10f * dens)
                val tempSize = (hf * 0.07f).coerceIn(8f * dens, 12.5f * dens)
                val cy = hy + hf * 0.185f
                val labelY = hy + hf * 0.285f
                val cellW = wf * 0.80f / 5f
                val startX = cx - mk.size * cellW / 2f

                val tmp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFFFFFFF.toInt()
                    textSize = tempSize
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
                val hp2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFFFFFFF.toInt()
                    textSize = tiny
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
                val pill = Paint(Paint.ANTI_ALIAS_FLAG)

                for ((i, m) in mk.withIndex()) {
                    val xc = startX + cellW * (i + 0.5f)
                    if (m.notable) {
                        pill.color = 0x2EFFFFFF
                        c.drawRoundRect(
                            RectF(xc - cellW * 0.47f, cy - ir * 1.45f, xc + cellW * 0.47f, labelY + tiny * 0.55f),
                            ir * 0.9f, ir * 0.9f, pill
                        )
                    }
                    val label = "${m.temp}°"
                    val tw2 = tmp.measureText(label)
                    val total = ir * 2f + ir * 0.45f + tw2
                    val x0 = xc - total / 2f
                    drawWeatherIcon(c, m.code, m.night, x0 + ir, cy, ir, 255)
                    tmp.alpha = 255
                    c.drawText(label, x0 + ir * 2.45f, cy + tempSize * 0.35f, tmp)
                    hp2.alpha = if (m.notable) 255 else 200
                    c.drawText(m.label, xc, labelY, hp2)
                }
            }
        }

        return bmp
    }

    fun scaleForPixels(w: Int, h: Int, maxPixels: Float = 1_100_000f): Float {
        val area = w.toFloat() * h
        return if (area > maxPixels) sqrt(maxPixels / area) else 1f
    }
}
