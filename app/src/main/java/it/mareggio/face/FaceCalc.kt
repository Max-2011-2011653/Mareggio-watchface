package it.mareggio.face

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

const val H1 = 3_600_000L

const val C_TEAL = 0xFF4FD1B5.toInt()
const val C_YELLOW = 0xFFF2E04B.toInt()
const val C_ORANGE = 0xFFF2A03B.toInt()
const val C_RED = 0xFFE8573F.toInt()
const val C_PURPLE = 0xFFB23CD6.toInt()
const val C_BLUE = 0xFF5AA9D6.toInt()
const val C_WHITE = 0xFFE6F0EE.toInt()

private fun pad2(n: Int) = if (n < 10) "0$n" else "$n"

fun hhmm(ms: Long): String {
    val d = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
    return pad2(d.hour) + ":" + pad2(d.minute)
}

fun parseTime(s: String?): Long? {
    if (s == null) return null
    return try {
        LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }
}

class Series(val t: LongArray, val v: Array<Double?>)

fun series(h: JSONObject?, key: String): Series? {
    if (h == null) return null
    val ta = h.optJSONArray("time") ?: return null
    val va = h.optJSONArray(key) ?: return null
    val n = minOf(ta.length(), va.length())
    val t = LongArray(n) { parseTime(ta.optString(it)) ?: Long.MIN_VALUE }
    val v = Array<Double?>(n) { if (va.isNull(it)) null else va.optDouble(it) }
    return Series(t, v)
}

fun at(s: Series?, t: Long): Double? {
    if (s == null) return null
    val n = s.t.size
    if (n < 2 || t < s.t[0] || t > s.t[n - 1]) return null
    var i = 0
    while (i < n - 2 && s.t[i + 1] <= t) i++
    val a = s.v[i] ?: return null
    val b = s.v[i + 1] ?: return null
    return a + (b - a) * (t - s.t[i]).toDouble() / (s.t[i + 1] - s.t[i]).toDouble()
}

fun nearest(s: Series?, t: Long): Double? {
    if (s == null) return null
    var best = -1
    var bd = Long.MAX_VALUE
    for (i in s.t.indices) {
        val d = abs(s.t[i] - t)
        if (d < bd) {
            bd = d
            best = i
        }
    }
    if (best < 0 || bd > 2 * H1) return null
    return s.v[best]
}

fun windColor(k: Double): Int = when {
    k < 8 -> C_TEAL
    k < 14 -> C_YELLOW
    k < 20 -> C_ORANGE
    k < 27 -> C_RED
    else -> C_PURPLE
}

fun waveColor(m: Double): Int = when {
    m < 0.5 -> C_TEAL
    m < 1.0 -> C_YELLOW
    m < 1.5 -> C_ORANGE
    m < 2.5 -> C_RED
    else -> C_PURPLE
}

fun tempColor(c: Double): Int = when {
    c < 8 -> C_BLUE
    c < 16 -> C_TEAL
    c < 24 -> C_YELLOW
    c < 30 -> C_ORANGE
    else -> C_RED
}

class PTrend(val p: Double, val word: String, val color: Int)

fun pressureTrend(s: Series?, now: Long, current: Double?): PTrend? {
    val p = current ?: at(s, now) ?: return null
    val p0 = at(s, now - 3 * H1)
    var word = "dati incompleti"
    var color = C_WHITE
    if (p0 != null) {
        val d = p - p0
        word = "stabile"
        when {
            d <= -3 -> { word = "in forte calo"; color = C_RED }
            d <= -1 -> { word = "in calo"; color = C_ORANGE }
            d >= 3 -> { word = "in forte salita"; color = C_YELLOW }
            d >= 1 -> { word = "in salita"; color = C_TEAL }
        }
    }
    return PTrend(p, word, color)
}

class Estremo(val alta: Boolean, val t: Long, val v: Double)

fun tideExtremes(s: Series?, now: Long, count: Int): List<Estremo> {
    val out = ArrayList<Estremo>()
    if (s == null) return out
    for (i in 1 until s.t.size - 1) {
        if (s.t[i] <= now) continue
        val a = s.v[i - 1] ?: continue
        val b = s.v[i] ?: continue
        val c = s.v[i + 1] ?: continue
        if (b >= a && b >= c && (b > a || b > c)) out.add(Estremo(true, s.t[i], b))
        else if (b <= a && b <= c && (b < a || b < c)) out.add(Estremo(false, s.t[i], b))
        if (out.size >= count) break
    }
    return out
}
