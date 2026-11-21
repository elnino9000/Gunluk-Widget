package com.gunluk.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Konumu kendiliğinden alma: arka planda, telefonun bilinen son konumundan */
object Loc {
    private const val COARSE = "android.permission.ACCESS_COARSE_LOCATION"
    private const val FINE = "android.permission.ACCESS_FINE_LOCATION"
    private const val BACKGROUND = "android.permission.ACCESS_BACKGROUND_LOCATION"
    private const val MOVE_METERS = 2000f          // bu kadar uzaklaşırsa "yeni yer" say
    private const val FRESH_MS = 30 * 60_000L      // bundan eski konum bayat sayılır
    private const val TRY_EVERY_MS = 30 * 60_000L  // taze konum isteğini en çok bu sıklıkta dene

    fun hasForeground(ctx: Context): Boolean =
        ctx.checkSelfPermission(COARSE) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(FINE) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 || ctx.checkSelfPermission(BACKGROUND) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun lastKnown(ctx: Context): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (p in lm.getProviders(true)) {
            val l = try { lm.getLastKnownLocation(p) } catch (e: Exception) { null }
            if (l != null && (best == null || l.time > best.time)) best = l
        }
        return best
    }

    @SuppressLint("MissingPermission")
    private fun fresh(ctx: Context): Location? {
        if (Build.VERSION.SDK_INT < 30) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // sırayla dene: ağ konumu, GPS; biri boş dönerse diğerine geç
        for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
            try {
                if (!lm.isProviderEnabled(provider)) continue
                val latch = CountDownLatch(1)
                var result: Location? = null
                val ex = Executors.newSingleThreadExecutor()
                lm.getCurrentLocation(provider, null, ex) { loc ->
                    result = loc
                    latch.countDown()
                }
                latch.await(if (provider == LocationManager.GPS_PROVIDER) 6 else 5, TimeUnit.SECONDS)
                ex.shutdown()
                if (result != null) return result
            } catch (e: Exception) {
                // bu sağlayıcı olmadı, sonrakini dene
            }
        }
        return null
    }

    /** Yeni konumu kaydet ve ilçe adını bul */
    fun apply(ctx: Context, l: Location) {
        Data.setCoords(ctx, l.latitude, l.longitude)
        var name: String? = null
        try {
            @Suppress("DEPRECATION")
            val list = Geocoder(ctx, Locale("tr", "TR")).getFromLocation(l.latitude, l.longitude, 1)
            val a = list?.firstOrNull()
            // önce mahalle (Şemikler), yoksa ilçe/şehir
            val hood = a?.subLocality?.trim()
                ?.removeSuffix(" Mahallesi")?.removeSuffix(" Mah.")?.removeSuffix(" Mah")
                ?.takeIf { it.isNotEmpty() }
            name = hood ?: a?.subAdminArea ?: a?.locality ?: a?.adminArea
        } catch (e: Exception) {
            // ilçe adı bulunamadı; koordinat yine de kayıtlı
        }
        Data.setPlace(ctx, name)
    }

    /** Arka plan turunda çağrılır. Yer değiştiyse true döner (hava yenilensin). */
    fun update(ctx: Context, force: Boolean = false): Boolean {
        if (!hasForeground(ctx) || !hasBackground(ctx)) return false
        val now = System.currentTimeMillis()
        var loc = lastKnown(ctx)
        val stale = loc == null || now - loc.time > FRESH_MS
        // kullanıcı dokunduysa her zaman taze konum istenir (30 dk sınırı yok)
        if (stale && (force || now - Data.lastLocTry(ctx) > TRY_EVERY_MS)) {
            Data.markLocTry(ctx, now)
            fresh(ctx)?.let { loc = it }
        }
        val l = loc ?: return false
        if (Data.placeVersion(ctx) < 2) {
            Data.setPlaceVersion(ctx, 2)
            apply(ctx, l)
            return true
        }
        if (Data.hasCoords(ctx)) {
            val (lat, lon) = Data.coords(ctx)
            val out = FloatArray(1)
            Location.distanceBetween(lat, lon, l.latitude, l.longitude, out)
            if (out[0] < MOVE_METERS) return false
        }
        apply(ctx, l)
        return true
    }
}
