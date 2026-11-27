package it.mareggio.face

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private class Dati(
    val cur: JSONObject?,
    val tide: Series?,
    val windSpeed: Series?,
    val windDir: Series?,
    val wave: Series?,
    val waveDir: Series?,
    val pressure: Series?,
    val sunrise: Long?,
    val sunset: Long?,
    val quando: Long
)

private fun JSONObject?.n(key: String): Double? =
    if (this == null || isNull(key)) null else optDouble(key)

// Disegna la parte bassa del quadrante (meteo, luna, marea, vento, onde) in una immagine.
// Le coordinate sono quelle del quadrante a 450 x 450: l'immagine mostra l'area
// che parte da (OX, OY) ed e' larga W x H.
object Cruscotto {
    const val W = 400
    const val H = 260
    private const val OX = 25f
    private const val OY = 175f

    private var cache: Dati? = null
    private var ultimoSpot: Spot? = null

    private val sfondo = Color.parseColor("#0A1A22")
    private val anello = Color.parseColor("#1D3A47")
    private val grigio = Color.parseColor("#8FA9B0")
    private val oro = Color.parseColor("#FFB300")
    private val ciano = Color.parseColor("#00D8F0")
    private val curva = Color.parseColor("#4FC3E6")
    private val area = Color.parseColor("#143A4A")

    @SuppressLint("MissingPermission")
    private suspend fun posizione(ctx: Context): Spot {
        val ha = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (ha) {
            val s = withTimeoutOrNull(8_000) {
                suspendCancellableCoroutine<Spot?> { c ->
                    try {
                        LocationServices.getFusedLocationProviderClient(ctx).lastLocation
                            .addOnSuccessListener { l ->
                                c.resume(
                                    if (l == null) null
                                    else Spot(
                                        "GPS",
                                        Math.round(l.latitude * 1000) / 1000.0,
                                        Math.round(l.longitude * 1000) / 1000.0
                                    )
                                )
                            }
                            .addOnFailureListener { c.resume(null) }
                    } catch (e: Exception) {
                        c.resume(null)
                    }
                }
            }
            if (s != null) ultimoSpot = s
        }
        return ultimoSpot ?: SPOT_FISSO
    }

    private suspend fun dati(ctx: Context): Dati? {
        val c = cache
        val now = System.currentTimeMillis()
        if (c != null && now - c.quando < 15 * 60_000L) return c
        val d = loadAll(posizione(ctx))
        if (d.vuoto) return c
        val fh = d.f?.optJSONObject("hourly")
        val mh = d.m?.optJSONObject("hourly")
        val daily = d.fd?.optJSONObject("daily")
        val nuovo = Dati(
            cur = d.f?.optJSONObject("current"),
            tide = series(mh, "sea_level_height_msl"),
            windSpeed = series(fh, "wind_speed_10m"),
            windDir = series(fh, "wind_direction_10m"),
            wave = series(mh, "wave_height"),
            waveDir = series(mh, "wave_direction"),
            pressure = series(fh, "pressure_msl"),
            sunrise = parseTime(daily?.optJSONArray("sunrise")?.optString(0)),
            sunset = parseTime(daily?.optJSONArray("sunset")?.optString(0)),
            quando = now
        )
        cache = nuovo
        return nuovo
    }

    // immagine con i dati veri (o con un messaggio se non ci sono)
    suspend fun immagine(ctx: Context): Bitmap {
        val d = dati(ctx)
        return disegna(d, System.currentTimeMillis())
    }

    // immagine di esempio per l'anteprima nella scelta delle complicazioni
    fun anteprima(): Bitmap = disegna(null, System.currentTimeMillis())

    private fun pt(sz: Float, col: Int, bold: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sz
            color = col
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
        }

    private fun tratto(w: Float, col: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w
            color = col
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private fun pieno(col: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = col
        }

    private fun triangolo(c: Canvas, cx: Float, cy: Float, size: Float, angolo: Float, col: Int) {
        val path = Path().apply {
            moveTo(0f, -size)
            lineTo(size * 0.7f, size * 0.6f)
            lineTo(-size * 0.7f, size * 0.6f)
            close()
        }
        c.save()
        c.translate(cx, cy)
        c.rotate(angolo)
        c.drawPath(path, pieno(col))
        c.restore()
    }

    // punto sul cerchio: 0 gradi = nord, senso orario
    private fun qx(cx: Float, r: Float, a: Float): Float = cx + r * sin(Math.toRadians(a.toDouble())).toFloat()
    private fun qy(cy: Float, r: Float, a: Float): Float = cy - r * cos(Math.toRadians(a.toDouble())).toFloat()

    private fun fase(ms: Long): Double {
        val jd = ms / 86_400_000.0 + 2440587.5
        return (((jd - 2451550.1) / 29.530588853) % 1.0 + 1.0) % 1.0
    }

    private fun disegna(m: Dati?, now: Long): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(sfondo)
        c.translate(-OX, -OY)

        if (m == null) {
            c.drawText("carico i dati...", 225f, 300f, pt(20f, grigio))
            return bmp
        }
        alto(c, m, now)
        marea(c, m, now)
        basso(c, m, now)
        return bmp
    }

    // temperatura, alba/tramonto, luna, pressione
    private fun alto(c: Canvas, m: Dati, now: Long) {
        val temp = m.cur.n("temperature_2m")
        val pr = pressureTrend(m.pressure, now, m.cur.n("pressure_msl"))

        val colT = if (temp != null) tempColor(temp) else grigio
        c.drawCircle(76f, 184f, 7f, pieno(colT))
        c.drawText(
            if (temp != null) temp.roundToInt().toString() + "\u00B0" else "--",
            112f, 193f, pt(26f, Color.WHITE, true)
        )
        val sole = if (m.sunrise != null && m.sunset != null) "\u2191" + hhmm(m.sunrise) + " \u2193" + hhmm(m.sunset) else ""
        c.drawText(sole, 112f, 214f, pt(16f, Color.parseColor("#F2C94C")))

        // luna
        val f = fase(now)
        val illum = (1 - cos(2 * PI * f)) / 2
        val cresce = f < 0.5
        val cx = 290f
        val cy = 184f
        val r = 10f
        c.drawCircle(cx, cy, r, pieno(Color.parseColor("#E8DFC0")))
        val sp = (2 * r * illum).toFloat()
        val sx = if (cresce) cx - sp else cx + sp
        c.save()
        c.clipRect(cx - r - 1f, cy - r - 1f, cx + r + 1f, cy + r + 1f)
        c.drawCircle(sx, cy, r, pieno(sfondo))
        c.restore()
        c.drawText((illum * 100).roundToInt().toString() + "%", 336f, 193f, pt(22f, Color.WHITE))

        val colP = pr?.color ?: grigio
        c.drawText(
            if (pr != null) pr.p.roundToInt().toString() + " hPa" else "",
            336f, 214f, pt(16f, colP)
        )
    }

    private fun marea(c: Canvas, m: Dati, now: Long) {
        val inizio = now - 4 * H1
        val fine = now + 10 * H1
        val passo = 15 * 60_000L
        val xs = 48f
        val xe = 402f
        val yAlto = 252f
        val yBasso = 303f
        val base = 318f

        val punti = ArrayList<Pair<Long, Double?>>()
        var t = inizio
        while (t <= fine) {
            punti.add(Pair(t, at(m.tide, t)))
            t += passo
        }
        val vals = punti.mapNotNull { it.second }
        if (vals.isEmpty()) {
            c.drawText("marea n.d.", 225f, 285f, pt(18f, grigio))
            return
        }
        val mn = vals.min()
        val mx = vals.max()
        val span = if (mx - mn < 0.02) 0.02 else mx - mn
        fun x(tt: Long): Float = xs + (xe - xs) * (tt - inizio).toFloat() / (fine - inizio).toFloat()
        fun y(v: Double): Float = yBasso - ((v - mn) / span * (yBasso - yAlto)).toFloat()

        val linea = Path()
        var primo = true
        var xPrimo = xs
        var xUltimo = xs
        for (p in punti) {
            val v = p.second ?: continue
            val ax = x(p.first)
            val ay = y(v)
            if (primo) {
                linea.moveTo(ax, ay)
                xPrimo = ax
                primo = false
            } else linea.lineTo(ax, ay)
            xUltimo = ax
        }
        val riempi = Path(linea)
        riempi.lineTo(xUltimo, base)
        riempi.lineTo(xPrimo, base)
        riempi.close()
        c.drawPath(riempi, pieno(area))
        c.drawPath(linea, tratto(4f, curva))

        for (e in tideExtremes(m.tide, inizio, 8)) {
            if (e.t < inizio || e.t > fine) continue
            val ex = x(e.t)
            val ey = y(e.v)
            if (e.alta) {
                c.drawCircle(ex, ey, 9f, pieno(oro))
                c.drawText(hhmm(e.t), ex, ey - 14f, pt(16f, oro))
            } else {
                c.drawCircle(ex, ey, 9f, pieno(ciano))
                c.drawText(hhmm(e.t), ex, ey + 26f, pt(16f, ciano))
            }
        }

        val nx = x(now)
        val livello = at(m.tide, now)
        val tratteggio = tratto(2f, Color.WHITE).apply { pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f) }
        c.drawLine(nx, 232f, nx, base, tratteggio)
        if (livello != null) c.drawCircle(nx, y(livello), 8f, pieno(Color.WHITE))
    }

    private fun basso(c: Canvas, m: Dati, now: Long) {
        val cur = m.cur
        val kn = cur.n("wind_speed_10m") ?: at(m.windSpeed, now)
        val dv = cur.n("wind_direction_10m") ?: nearest(m.windDir, now)
        val onda = at(m.wave, now)
        val dOnda = nearest(m.waveDir, now)

        val colV = if (kn != null) windColor(kn) else grigio
        val colO = if (onda != null) waveColor(onda) else grigio

        // vento
        val vx = 150f
        val vy = 376f
        c.drawCircle(vx, vy, 30f, tratto(3f, anello))
        val lv = tratto(3f, colV)
        c.drawLine(vx - 14f, vy - 5f, vx + 7f, vy - 5f, lv)
        c.drawLine(vx - 14f, vy + 4f, vx + 12f, vy + 4f, lv)
        if (dv != null) {
            val a = ((dv + 180) % 360).toFloat()
            triangolo(c, qx(vx, 30f, a), qy(vy, 30f, a), 7f, a, colV)
        }
        c.drawText(if (kn != null) kn.roundToInt().toString() + " kn" else "--", vx, 427f, pt(20f, colV))

        // onda
        val ox = 300f
        val oy = 376f
        c.drawCircle(ox, oy, 30f, tratto(3f, anello))
        val sinus = Path()
        for (i in 0..32) {
            val xx = ox - 16f + i * 32f / 32f
            val yy = oy + 5f * sin(i / 32.0 * 4.0 * PI).toFloat()
            if (i == 0) sinus.moveTo(xx, yy) else sinus.lineTo(xx, yy)
        }
        c.drawPath(sinus, tratto(3f, colO))
        if (dOnda != null) {
            val a = ((dOnda + 180) % 360).toFloat()
            triangolo(c, qx(ox, 30f, a), qy(oy, 30f, a), 7f, a, colO)
        }
        c.drawText(
            if (onda != null) String.format(Locale.getDefault(), "%.1f m", onda) else "--",
            ox, 427f, pt(20f, colO)
        )

        // bussola
        val bx = 225f
        val by = 380f
        val br = 28f
        c.drawCircle(bx, by, br, tratto(3f, anello))
        c.drawText("N", bx, by - br + 16f, pt(12f, Color.parseColor("#FF4D6D"), true))
        triangolo(c, bx, by - 1f, 10f, 0f, Color.parseColor("#FF4D6D"))
        triangolo(c, bx, by + 6f, 7f, 180f, grigio)
        if (dv != null) c.drawCircle(qx(bx, br, dv.toFloat()), qy(by, br, dv.toFloat()), 5f, pieno(colV))
        if (dOnda != null) c.drawCircle(qx(bx, br, dOnda.toFloat()), qy(by, br, dOnda.toFloat()), 5f, pieno(colO))
    }
}
