package com.gunluk.widget

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * d).toInt(), (64 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF0A1030.toInt(), 0xFF2A2F6E.toInt(), 0xFFF08A5D.toInt())
            )
        }

        val title = TextView(this).apply {
            text = "Günlük Widget"
            textSize = 30f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        val info = TextView(this).apply {
            text = "Ana ekranda boş bir yere uzun bas → Widget'lar → Günlük Widget'ı seç.\n\n" +
                "Hava durumu için konumun gerekir. Her gün farklı ilçeye gidiyorsan, konumun " +
                "kendiliğinden güncellenmesi için iki izni de ver: önce konum, sonra \"her zaman\"."
            textSize = 16f
            setTextColor(0xE6FFFFFF.toInt())
            setPadding(0, (20 * d).toInt(), 0, (20 * d).toInt())
        }
        status = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFFFE9A8.toInt())
            setPadding(0, 0, 0, (16 * d).toInt())
        }
        val locBtn = Button(this).apply {
            text = "Konum izni ver / konumu güncelle"
            setOnClickListener { ensureLocation() }
        }
        val bgBtn = Button(this).apply {
            text = "Her zaman izni ver (otomatik güncelleme)"
            setOnClickListener { askBackground() }
        }
        val refreshBtn = Button(this).apply {
            text = "Widget'ı yenile"
            setOnClickListener {
                sendBroadcast(
                    Intent(this@MainActivity, WidgetProvider::class.java)
                        .setAction(WidgetProvider.ACTION_REFRESH)
                )
                status.text = "Yenileniyor…"
            }
        }

        root.addView(title)
        root.addView(info)
        root.addView(status)
        root.addView(locBtn)
        root.addView(bgBtn)
        root.addView(refreshBtn)
        setContentView(root)

        if (!Loc.hasForeground(this)) ensureLocation() else captureLocation()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val place = Data.place(this)
        status.text = when {
            !Loc.hasForeground(this) -> "Konum izni yok (İstanbul varsayılan)"
            !Loc.hasBackground(this) ->
                "Konum kayıtlı${if (place != null) ": $place" else ""}. Kendiliğinden güncellenmesi için \"Her zaman\" izni ver."
            else -> "Konum otomatik güncelleniyor ✓${if (place != null) " ($place)" else ""}"
        }
    }

    private fun ensureLocation() {
        if (!Loc.hasForeground(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 7)
            return
        }
        captureLocation()
    }

    private fun askBackground() {
        if (!Loc.hasForeground(this)) {
            ensureLocation()
            return
        }
        if (Loc.hasBackground(this)) {
            Toast.makeText(this, "Zaten verilmiş ✓", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT == 29) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 8)
        } else {
            // Android 11 ve üstü: izin ayar sayfasından verilir
            Toast.makeText(this, "Konum → \"Her zaman izin ver\" seçeneğini seç", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 7 && Loc.hasForeground(this)) {
            captureLocation()
            if (!Loc.hasBackground(this)) askBackground()
        }
        updateStatus()
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun captureLocation() {
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            var best: Location? = null
            for (p in lm.getProviders(true)) {
                val l = try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null }
                if (l != null && (best == null || l.time > best.time)) best = l
            }
            if (best != null) {
                save(best)
                return
            }
            status.text = "Konum aranıyor…"
            val provider = when {
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> null
            }
            if (provider == null) {
                status.text = "Telefonun konumu kapalı görünüyor; açıp tekrar dene"
                return
            }
            lm.requestSingleUpdate(provider, object : LocationListener {
                override fun onLocationChanged(l: Location) { save(l) }
                override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
            }, mainLooper)
        } catch (e: Exception) {
            status.text = "Konum alınamadı, İstanbul kullanılacak"
        }
    }

    private fun save(l: Location) {
        thread {
            Loc.apply(this, l)
            sendBroadcast(
                Intent(this, WidgetProvider::class.java).setAction(WidgetProvider.ACTION_REFRESH)
            )
            runOnUiThread { updateStatus() }
        }
    }
}
