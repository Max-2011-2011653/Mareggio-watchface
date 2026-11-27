package it.mareggio.face

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Spot(val nome: String, val lat: Double, val lng: Double)

// Posizione di ripiego se il GPS non risponde
val SPOT_FISSO = Spot("Bari", 41.1171, 16.8719)

class Data(
    val f: JSONObject?,   // meteo corrente + orario
    val m: JSONObject?,   // marino orario
    val fd: JSONObject?,  // meteo giornaliero
    val md: JSONObject?,  // marino giornaliero
    val now: Long
) {
    val vuoto: Boolean get() = f == null && m == null
    val incompleto: Boolean get() = f == null || m == null || fd == null || md == null
}

private fun getJson(url: String): JSONObject? {
    return try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        val s = c.inputStream.bufferedReader().use { it.readText() }
        JSONObject(s)
    } catch (e: Exception) {
        null
    }
}

suspend fun loadAll(s: Spot): Data = withContext(Dispatchers.IO) {
    val pos = "latitude=${s.lat}&longitude=${s.lng}&timezone=auto&wind_speed_unit=kn"
    val fw = "https://api.open-meteo.com/v1/forecast?$pos"
    val mw = "https://marine-api.open-meteo.com/v1/marine?latitude=${s.lat}&longitude=${s.lng}&timezone=auto"
    coroutineScope {
        val f = async {
            getJson(
                "$fw&current=temperature_2m,relative_humidity_2m,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m" +
                    "&hourly=pressure_msl,wind_speed_10m,wind_direction_10m,relative_humidity_2m&past_days=1&forecast_days=3"
            )
        }
        val m = async {
            getJson("$mw&hourly=wave_height,wave_direction,sea_level_height_msl&past_days=1&forecast_days=3")
        }
        val fd = async {
            getJson(
                "$fw&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max," +
                    "precipitation_sum,wind_speed_10m_max,wind_gusts_10m_max,sunrise,sunset&hourly=relative_humidity_2m&forecast_days=7"
            )
        }
        val md = async { getJson("$mw&daily=wave_height_max&forecast_days=7") }
        Data(f.await(), m.await(), fd.await(), md.await(), System.currentTimeMillis())
    }
}
