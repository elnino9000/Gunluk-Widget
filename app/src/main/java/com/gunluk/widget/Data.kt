package com.gunluk.widget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.roundToInt

data class Headline(val source: String, val title: String, val link: String)

data class Weather(
    val temp: Int,
    val code: Int,
    val rainProb: Int,
    val sunrise: Long, // epoch saniye (bugünün gün doğumu)
    val sunset: Long   // epoch saniye (bugünün gün batımı)
)

data class State(val weather: Weather?, val headline: Headline?)

object Data {
    private const val PREFS = "gunluk"
    private const val DEFAULT_LAT = 41.0082 // konum yoksa İstanbul
    private const val DEFAULT_LON = 28.9784

    // Her kaynak için sırayla denenecek adresler (ilk çalışan kullanılır)
    private val FEEDS: List<Pair<String, List<String>>> = listOf(
        "AA" to listOf(
            "https://www.aa.com.tr/tr/rss/default?cat=guncel",
            "https://www.aa.com.tr/tr/rss/default?cat=live"
        ),
        "TRT" to listOf(
            "https://www.trthaber.com/sondakika_articles.rss",
            "https://www.trthaber.com/manset_articles.rss"
        ),
        "BBC" to listOf(
            "https://feeds.bbci.co.uk/turkce/rss.xml"
        )
    )

    // Tıklama tuzağı olan başlıkları ele
    private val BAD_WORDS = listOf(
        "şok", "inanılmaz", "izle", "ağızları açık", "bakın", "kimse beklemiyordu",
        "gören", "olay yarattı", "olay oldu", "mutlaka", "sakın", "dehşet"
    )

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

    fun bumpNews(ctx: Context) {
        val p = prefs(ctx)
        p.edit().putInt("newsOffset", p.getInt("newsOffset", 0) + 1).apply()
    }

    // ---- Okuma (önbellekten) ----
    fun load(ctx: Context): State {
        val p = prefs(ctx)
        val weather = p.getString("wJson", null)?.let { parseWeather(it) }
        val list = headlines(ctx)
        val headline = if (list.isEmpty()) null else {
            val slot = System.currentTimeMillis() / (10 * 60_000L)
            val idx = ((slot + p.getInt("newsOffset", 0)) % list.size).toInt()
            list[idx]
        }
        return State(weather, headline)
    }

    private fun headlines(ctx: Context): List<Headline> {
        val p = prefs(ctx)
        val lists = FEEDS.map { (src, _) ->
            val arr = try {
                JSONArray(p.getString("news_$src", "[]"))
            } catch (e: Exception) {
                JSONArray()
            }
            val l = ArrayList<Headline>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                l.add(Headline(src, o.optString("t"), o.optString("l")))
            }
            l
        }
        val out = ArrayList<Headline>()
        var i = 0
        while (true) {
            var added = false
            for (l in lists) {
                if (i < l.size) {
                    out.add(l[i])
                    added = true
                }
            }
            if (!added) break
            i++
        }
        return out
    }

    // ---- İnternetten çekme ----
    @Synchronized
    fun refresh(ctx: Context, force: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val p = prefs(ctx)
        val needW = force || now - p.getLong("wAt", 0L) > 30 * 60_000L
        val needN = force || now - p.getLong("nAt", 0L) > 12 * 60_000L
        if (!needW && !needN) return false

        val (lat, lon) = coords(ctx)
        val results = ConcurrentHashMap<String, String>()
        val threads = ArrayList<Thread>()

        if (needW) {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code" +
                "&daily=sunrise,sunset,precipitation_probability_max" +
                "&timezone=auto&forecast_days=1"
            threads.add(thread(isDaemon = true) {
                httpGet(url)?.let { results["w"] = it }
            })
        }
        if (needN) {
            for ((src, urls) in FEEDS) {
                threads.add(thread(isDaemon = true) {
                    for (u in urls) {
                        val body = httpGet(u)
                        if (body != null && parseRss(body, src).isNotEmpty()) {
                            results["n_$src"] = body
                            break
                        }
                    }
                })
            }
        }

        val deadline = System.currentTimeMillis() + 10_000L
        for (t in threads) {
            val left = deadline - System.currentTimeMillis()
            if (left > 0) t.join(left)
        }

        var changed = false
        val edit = p.edit()

        results["w"]?.let { json ->
            if (parseWeather(json) != null) {
                edit.putString("wJson", json).putLong("wAt", now)
                changed = true
            }
        }
        if (needN) {
            var any = false
            for ((src, _) in FEEDS) {
                val body = results["n_$src"] ?: continue
                val items = parseRss(body, src)
                if (items.isEmpty()) continue
                val arr = JSONArray()
                for (h in items) arr.put(JSONObject().put("t", h.title).put("l", h.link))
                edit.putString("news_$src", arr.toString())
                any = true
            }
            if (any) {
                edit.putLong("nAt", now)
                changed = true
            }
        }
        edit.apply()
        return changed
    }

    private fun httpGet(u: String): String? {
        return try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) GunlukWidget/1.0")
            c.setRequestProperty("Accept", "application/rss+xml, application/xml, application/json, */*")
            if (c.responseCode !in 200..299) null
            else c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseWeather(json: String): Weather? {
        return try {
            val o = JSONObject(json)
            val off = o.getInt("utc_offset_seconds")
            val cur = o.getJSONObject("current")
            val d = o.getJSONObject("daily")
            val zone = ZoneOffset.ofTotalSeconds(off)
            val sr = LocalDateTime.parse(d.getJSONArray("sunrise").getString(0)).toEpochSecond(zone)
            val ss = LocalDateTime.parse(d.getJSONArray("sunset").getString(0)).toEpochSecond(zone)
            val rain = d.optJSONArray("precipitation_probability_max")?.optInt(0, 0) ?: 0
            Weather(
                cur.getDouble("temperature_2m").roundToInt(),
                cur.getInt("weather_code"),
                rain,
                sr,
                ss
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun titleOk(title: String): Boolean {
        if (title.length < 15 || title.length > 200) return false
        if (title.contains("!")) return false
        val low = title.lowercase(Locale("tr", "TR"))
        return BAD_WORDS.none { low.contains(it) }
    }

    private fun parseRss(xml: String, source: String, max: Int = 8): List<Headline> {
        val out = ArrayList<Headline>()
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(xml))
            var ev = parser.eventType
            var inItem = false
            var title = ""
            var link = ""
            while (ev != XmlPullParser.END_DOCUMENT && out.size < max) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "item", "entry" -> {
                            inItem = true
                            title = ""
                            link = ""
                        }
                        "title" -> if (inItem) title = parser.nextText().trim()
                        "link" -> if (inItem) {
                            val href = parser.getAttributeValue(null, "href")
                            link = if (!href.isNullOrBlank()) href else parser.nextText().trim()
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "item" || parser.name == "entry") {
                        inItem = false
                        if (link.startsWith("http") && titleOk(title)) {
                            out.add(Headline(source, title, link))
                        }
                    }
                }
                ev = parser.next()
            }
        } catch (e: Exception) {
            // bozuk akış: elimizdekiyle yetin
        }
        return out
    }
}
