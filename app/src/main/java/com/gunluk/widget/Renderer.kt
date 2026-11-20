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
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
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

object Renderer {

    // ---- Gün / gece hesabı ----
    fun phaseOf(w: Weather?, nowMs: Long): Phase {
        val now = nowMs / 1000
        var sr: Long
        var ss: Long
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
        1 -> "Çoğunlukla açık"
        2 -> "Parçalı bulutlu"
        3 -> "Kapalı"
        45, 48 -> "Sisli"
        in 51..57 -> "Çise"
        in 61..65 -> "Yağmurlu"
        66, 67 -> "Dondurucu yağmur"
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

    private fun sourceColor(src: String): Int = when (src) {
        "AA" -> 0xFFFF8A80.toInt()
        "TRT" -> 0xFF8AD1FF.toInt()
        "BBC" -> 0xFFFFD98A.toInt()
        else -> 0xFFFFFFFF.toInt()
    }

    private fun drawCloud(c: Canvas, x: Float, y: Float, s: Float, p: Paint) {
        c.drawCircle(x - s * 0.62f, y, s * 0.5f, p)
        c.drawCircle(x, y - s * 0.28f, s * 0.68f, p)
        c.drawCircle(x + s * 0.68f, y, s * 0.5f, p)
        c.drawRoundRect(RectF(x - s * 1.1f, y - s * 0.02f, x + s * 1.18f, y + s * 0.5f), s * 0.25f, s * 0.25f, p)
    }

    private fun drawDrop(c: Canvas, x: Float, top: Float, h: Float, p: Paint) {
        val bottom = top + h
        val path = Path()
        path.moveTo(x, top)
        path.cubicTo(x + h * 0.55f, top + h * 0.55f, x + h * 0.5f, bottom, x, bottom)
        path.cubicTo(x - h * 0.5f, bottom, x - h * 0.55f, top + h * 0.55f, x, top)
        path.close()
        c.drawPath(path, p)
    }

    // ---- Ana çizim ----
    fun render(w: Int, h: Int, dens: Float, s: State): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val wf = w.toFloat()
        val hf = h.toFloat()

        val ph = phaseOf(s.weather, System.currentTimeMillis())
        val alt = ph.alt
        val skyH = hf * 0.76f
        val hy = skyH * 0.84f          // ufuk çizgisi
        val cx = wf / 2f
        val rx = wf * 0.42f
        val ry = skyH * 0.68f

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
                    val y = skyH * (0.30f + 0.10f * (i % 2))
                    drawCloud(c, x, y, skyH * (0.075f + 0.012f * i), p)
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
            val r = skyH * 0.058f
            val warm = ((0.5f - alt) / 0.5f).coerceIn(0f, 1f) // ufka yakınken turuncu
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
            val r = skyH * 0.052f
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

        // 7) ufuk: tepeler (güneş/ay buraya batar)
        val g = Path()
        g.moveTo(0f, hy + skyH * 0.02f)
        g.cubicTo(wf * 0.25f, hy - skyH * 0.05f, wf * 0.45f, hy + skyH * 0.04f, wf * 0.65f, hy)
        g.cubicTo(wf * 0.80f, hy - skyH * 0.03f, wf * 0.92f, hy + skyH * 0.02f, wf, hy - skyH * 0.01f)
        g.lineTo(wf, hf)
        g.lineTo(0f, hf)
        g.close()
        p.shader = LinearGradient(
            0f, hy - skyH * 0.05f, 0f, hf,
            0x59000000, 0x99000000.toInt(), Shader.TileMode.CLAMP
        )
        c.drawPath(g, p)
        p.shader = null

        // 8) gün doğumu / batımı saatleri (küçük)
        val fmt = SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val small = (skyH * 0.085f).coerceIn(8f * dens, 11.5f * dens)
        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            alpha = 185
            textSize = small
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val ly = hy + skyH * 0.115f
        c.drawText(fmt.format(Date(ph.sunrise * 1000)), cx - rx, ly, lp)
        c.drawText(fmt.format(Date(ph.sunset * 1000)), cx + rx, ly, lp)

        // 9) hava: sol üst
        val pad = min(wf * 0.045f, 16f * dens)
        if (weather != null) {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = (skyH * 0.20f).coerceIn(16f * dens, 34f * dens)
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                setShadowLayer(4f * dens, 0f, 1f * dens, 0x66000000)
            }
            val tBase = pad + tp.textSize * 0.82f
            c.drawText("${weather.temp}°", pad, tBase, tp)

            val rs = (skyH * 0.095f).coerceIn(9f * dens, 13f * dens)
            val rp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                alpha = 225
                textSize = rs
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setShadowLayer(3f * dens, 0f, 1f * dens, 0x66000000)
            }
            val rowY = tBase + rs * 1.55f
            val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8FD3FF.toInt() }
            drawDrop(c, pad + rs * 0.32f, rowY - rs * 0.95f, rs * 1.05f, dp)
            c.drawText("%${weather.rainProb}", pad + rs * 0.85f, rowY, rp)

            // sağ üst: durum
            val cond = conditionText(weather.code)
            if (cond.isNotEmpty()) {
                rp.textAlign = Paint.Align.RIGHT
                rp.alpha = 205
                c.drawText(cond, wf - pad, pad + rs * 1.0f, rp)
            }
        }

        // 10) haber şeridi
        val regionH = hf - skyH
        val mv = regionH * 0.13f
        val strip = RectF(pad * 0.8f, skyH + mv, wf - pad * 0.8f, hf - mv)
        val stripH = strip.height()
        val sc = stripH * 0.38f
        p.color = 0x40000000
        c.drawRoundRect(strip, sc, sc, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * dens
        p.color = 0x28FFFFFF
        c.drawRoundRect(strip, sc, sc, p)
        p.style = Paint.Style.FILL

        val ts = (hf * 0.072f).coerceIn(9f * dens, 13f * dens)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = ts
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val inner = stripH * 0.38f
        val hl = s.headline
        val labelText = hl?.source ?: ""
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (hl != null) sourceColor(hl.source) else 0xFFFFFFFF.toInt()
            textSize = ts * 0.82f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val labelW = if (labelText.isEmpty()) 0f else labelPaint.measureText(labelText) + inner * 0.7f
        val textX = strip.left + inner + labelW
        val textW = (strip.right - inner - textX).toInt().coerceAtLeast(40)
        val text = hl?.title ?: "Haberler yükleniyor…"
        val lineH = ts * 1.22f
        val maxLines = if (stripH * 0.92f >= lineH * 2f) 2 else 1
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, textW)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
        val ty = strip.top + (stripH - layout.height) / 2f
        c.save()
        c.translate(textX, ty)
        layout.draw(c)
        c.restore()
        if (labelText.isNotEmpty()) {
            c.drawText(labelText, strip.left + inner, ty + layout.getLineBaseline(0), labelPaint)
        }

        return bmp
    }

    fun scaleForPixels(w: Int, h: Int, maxPixels: Float = 1_100_000f): Float {
        val area = w.toFloat() * h
        return if (area > maxPixels) sqrt(maxPixels / area) else 1f
    }
}
