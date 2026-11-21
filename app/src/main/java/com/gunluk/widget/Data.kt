package com.gunluk.widget

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.roundToInt

/** Saatlik tahminden bir saat: zaman (epoch saniye), derece, hava kodu */
data class Slot(val epoch: Long, val temp: Int, val code: Int)

data class Weather(
    val temp: Int,
    val code: Int,
    val sunrise: Long, // bugünün gün doğumu (epoch saniye)
    val sunset: Long,  // bugünün gün batımı (epoch saniye)
    val hours: List<Slot>
)

data class State(
    val weather: Weather?,
    val place: String?,
    val themeMode: Int = 0,   // 0 koyu, 1 açık, 2 otomatik (gündüz açık / gece koyu)
    val fetchedAt: Long = 0L  // hava verisinin son alındığı an (ms)
)

object Data {
    private const val PREFS = "gunluk"
    private const val VERSION = 4
    private const val DEFAULT_LAT = 41.0082 // konum yoksa İstanbul
    private const val DEFAULT_LON = 28.9784

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- Konum ----
    fun coords(ctx: Context): Pair<Double, Double> {
        val p = prefs(ctx)
        val lat = p.getString("lat", null)?.toDoubleOrNull() ?: DEFAULT_LAT
        val lon = p.getString("lon", null)?.toDoubleOrNull() ?: DEFAULT_LON
        return lat to lon
    }

    fun hasCoords(ctx: Context): Boolean = prefs(ctx).getString("lat", null) != null

    fun setCoords(ctx: Context, lat: Double, lon: Double) {
        prefs(ctx).edit()
            .putString("lat", lat.toString())
            .putString("lon", lon.toString())
            .putLong("wAt", 0L) // hava durumunu yeniden çekmeye zorla
            .apply()
    }

    fun setPlace(ctx: Context, name: String?) {
        prefs(ctx).edit().putString("place", name).apply()
    }

    fun place(ctx: Context): String? = prefs(ctx).getString("place", null)

    fun theme(ctx: Context): Int = prefs(ctx).getInt("theme", 0)

    fun setTheme(ctx: Context, mode: Int) {
        prefs(ctx).edit().putInt("theme", mode).apply()
    }

    fun lastLocTry(ctx: Context): Long = prefs(ctx).getLong("locTry", 0L)

    fun markLocTry(ctx: Context, t: Long) {
        prefs(ctx).edit().putLong("locTry", t).apply()
    }

    // ---- Okuma (önbellekten) ----
    fun load(ctx: Context): State {
        val p = prefs(ctx)
        var w = p.getString("wJson", null)?.let { parseWeather(it) }
        val at = p.getLong("wAt", 0L)
        // veri eskiyse (artık kendiliğinden yenilenmiyor) şimdiki derece/simge saatlik tahminden alınır
        if (w != null && System.currentTimeMillis() - at > 20 * 60_000L) {
            val nowSec = System.currentTimeMillis() / 1000
            val near = w.hours.minByOrNull { Math.abs(it.epoch - nowSec) }
            if (near != null && Math.abs(near.epoch - nowSec) <= 90 * 60L) {
                w = w.copy(temp = near.temp, code = near.code)
            }
        }
        return State(w, p.getString("place", null), p.getInt("theme", 0), at)
    }

    /** Önbellek eski biçimdeyse (uygulama güncellendi) bir kez yeniden çekilir */
    fun needsMigration(ctx: Context): Boolean = prefs(ctx).getInt("wVer", 0) < VERSION

    // ---- İnternetten çekme ----
    @Synchronized
    fun refresh(ctx: Context, force: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val p = prefs(ctx)
        val need = force || p.getInt("wVer", 0) < VERSION || now - p.getLong("wAt", 0L) > 15 * 60_000L
        if (!need) return false

        val (lat, lon) = coords(ctx)
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code" +
            "&daily=sunrise,sunset" +
            "&hourly=temperature_2m,weather_code" +
            "&timezone=auto&forecast_days=3"
        val json = httpGet(url) ?: return false
        if (parseWeather(json) == null) return false
        p.edit().putString("wJson", json).putLong("wAt", now).putInt("wVer", VERSION).apply()
        return true
    }

    private fun httpGet(u: String): String? {
        return try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) GunlukWidget/1.0")
            if (c.responseCode !in 200..299) null
            else c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseWeather(json: String): Weather? {
        return try {
            val o = JSONObject(json)
            val zone = ZoneOffset.ofTotalSeconds(o.getInt("utc_offset_seconds"))
            val cur = o.getJSONObject("current")
            val d = o.getJSONObject("daily")
            val sr = LocalDateTime.parse(d.getJSONArray("sunrise").getString(0)).toEpochSecond(zone)
            val ss = LocalDateTime.parse(d.getJSONArray("sunset").getString(0)).toEpochSecond(zone)

            val hours = ArrayList<Slot>()
            val hr = o.optJSONObject("hourly")
            if (hr != null) {
                val times = hr.getJSONArray("time")
                val temps = hr.getJSONArray("temperature_2m")
                val codes = hr.getJSONArray("weather_code")
                val n = minOf(times.length(), temps.length(), codes.length())
                for (i in 0 until n) {
                    if (temps.isNull(i) || codes.isNull(i)) continue
                    val ep = LocalDateTime.parse(times.getString(i)).toEpochSecond(zone)
                    hours.add(Slot(ep, temps.getDouble(i).roundToInt(), codes.getInt(i)))
                }
            }
            Weather(
                cur.getDouble("temperature_2m").roundToInt(),
                cur.getInt("weather_code"),
                sr,
                ss,
                hours
            )
        } catch (e: Exception) {
            null
        }
    }
}
