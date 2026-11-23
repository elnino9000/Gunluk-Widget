package com.gunluk.widget

import android.content.Context
import org.json.JSONArray
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
    val hours: List<Slot>,
    val precip: Double = -1.0 // şu an düşen yağış (mm); -1 = bilinmiyor
)

data class State(
    val weather: Weather?,
    val place: String?,
    val themeMode: Int = 0,   // 0 koyu, 1 açık, 2 otomatik (gündüz açık / gece koyu)
    val fetchedAt: Long = 0L  // hava verisinin son alındığı an (ms)
)

object Data {
    private const val PREFS = "gunluk"
    private const val VERSION = 10
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

    /** Konum adı biçimi değişince (ilçe -> mahalle) bir kez yeniden bulunur */
    fun placeVersion(ctx: Context): Int = prefs(ctx).getInt("placeV", 0)

    fun setPlaceVersion(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt("placeV", v).apply()
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
        // yakındaki istasyondan gerçek ölçüm varsa (2 saatten eski değilse) tahminin yerine o kullanılır
        val oAt = p.getLong("oAt", 0L)
        val observed = w != null && oAt > 0 && System.currentTimeMillis() - oAt <= 2 * 3600_000L
        if (observed && w != null) {
            w = w.copy(temp = p.getInt("oTemp", w.temp), code = p.getInt("oCode", w.code))
            return State(w, p.getString("place", null), p.getInt("theme", 0), at)
        }
        // veri eskiyse (artık kendiliğinden yenilenmiyor) şimdiki derece/simge saatlik tahminden alınır
        if (w != null && System.currentTimeMillis() - at > 20 * 60_000L) {
            val nowSec = System.currentTimeMillis() / 1000
            val near = w.hours.minByOrNull { Math.abs(it.epoch - nowSec) }
            if (near != null && Math.abs(near.epoch - nowSec) <= 90 * 60L) {
                w = w.copy(temp = near.temp, code = near.code, precip = -1.0)
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
        val base = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,precipitation" +
            "&daily=sunrise,sunset" +
            "&hourly=temperature_2m,weather_code" +
            "&timezone=auto&forecast_days=3"
        // Önce ECMWF (Avrupa Orta Vadeli Hava Tahmini Merkezi) modeli; olmazsa servisin varsayılan karışımı
        var json = httpGet("$base&models=ecmwf_ifs025")
        if (json == null || parseWeather(json) == null) json = httpGet(base) ?: return false
        if (parseWeather(json) == null) return false
        p.edit().putString("wJson", json).putLong("wAt", now).putInt("wVer", VERSION).apply()
        val ob = try { observe(lat, lon) } catch (e: Exception) { null }
        if (ob != null) {
            p.edit().putLong("oAt", ob.at).putInt("oCode", ob.code).putInt("oTemp", ob.temp).apply()
        } else {
            p.edit().remove("oAt").remove("oCode").remove("oTemp").apply()
        }
        return true
    }

    private class Obs(val at: Long, val code: Int, val temp: Int)

    private fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    /**
     * Yakındaki havaalanı istasyonunun SON GERÇEK ÖLÇÜMÜ (METAR, aviationweather.gov).
     * 30 km içinde ve 90 dakikadan yeni bir rapor yoksa null döner; o zaman tahmin modeli kullanılır.
     */
    private fun observe(lat: Double, lon: Double): Obs? {
        val url = "https://aviationweather.gov/api/data/metar?format=json&hours=2" +
            "&bbox=${lat - 0.3},${lon - 0.4},${lat + 0.3},${lon + 0.4}"
        val body = httpGet(url) ?: return null
        val arr = try { JSONArray(body) } catch (e: Exception) { return null }
        val nowSec = System.currentTimeMillis() / 1000
        var best: Obs? = null
        var bestScore = Double.MAX_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.has("lat") || !o.has("lon") || !o.has("obsTime") || o.isNull("temp")) continue
            val d = km(lat, lon, o.getDouble("lat"), o.getDouble("lon"))
            val ageSec = nowSec - o.getLong("obsTime")
            if (d > 30.0 || ageSec > 90 * 60L || ageSec < -600) continue
            val wx = o.optString("wxString", "")
            var cover = 0 // 0 açık, 1 az bulutlu, 2 bulutlu, 3 kapalı
            val cl = o.optJSONArray("clouds")
            if (cl != null) for (j in 0 until cl.length()) {
                val c = cl.optJSONObject(j)?.optString("cover", "") ?: ""
                cover = maxOf(cover, when (c) { "SCT" -> 1; "BKN" -> 2; "OVC", "OVX", "VV" -> 3; else -> 0 })
            }
            val code = when {
                wx.contains("TS") -> 95
                wx.contains("SN") || wx.contains("SG") -> 73
                wx.contains("RA") -> if (wx.contains("-")) 61 else if (wx.contains("+")) 65 else 63
                wx.contains("DZ") -> 51
                wx.contains("FG") -> 45
                else -> cover
            }
            val score = d + ageSec / 60.0 // yakın ve yeni rapor tercih edilir
            if (score < bestScore) {
                bestScore = score
                best = Obs(o.getLong("obsTime") * 1000L, code, o.getDouble("temp").roundToInt())
            }
        }
        return best
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
                hours,
                if (cur.has("precipitation") && !cur.isNull("precipitation")) cur.getDouble("precipitation") else -1.0
            )
        } catch (e: Exception) {
            null
        }
    }
}
