package it.mareggio.face

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.SurfaceHolder
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// Dati gia' pronti per il disegno (calcolati quando arrivano da Open-Meteo)
private class Modello(
    val cur: JSONObject?,
    val tide: Series?,
    val windSpeed: Series?,
    val windDir: Series?,
    val wave: Series?,
    val waveDir: Series?,
    val pressure: Series?,
    val sunrise: Long?,
    val sunset: Long?
)

private fun JSONObject?.n(key: String): Double? =
    if (this == null || isNull(key)) null else optDouble(key)

class MareggioRenderer(
    private val ctx: Context,
    surfaceHolder: SurfaceHolder,
    watchState: WatchState,
    currentUserStyleRepository: CurrentUserStyleRepository
) : Renderer.CanvasRenderer(
    surfaceHolder,
    currentUserStyleRepository,
    watchState,
    CanvasType.HARDWARE,
    60_000L,
    false
) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var modello: Modello? = null
    private var ultimoSpot: Spot? = null
    private var provato = false

    private val sfondo = Color.parseColor("#0A1A22")
    private val anello = Color.parseColor("#1D3A47")
    private val grigio = Color.parseColor("#8FA9B0")
    private val chiaro = Color.parseColor("#B8D4E0")
    private val oro = Color.parseColor("#FFB300")
    private val ciano = Color.parseColor("#00D8F0")
    private val curva = Color.parseColor("#4FC3E6")
    private val area = Color.parseColor("#143A4A")

    init {
        scope.launch {
            while (isActive) {
                val ok = aggiorna()
                delay(if (ok) 30 * 60_000L else 2 * 60_000L)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------- dati ----------

    @SuppressLint("MissingPermission")
    private suspend fun posizione(): Spot {
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

    private suspend fun aggiorna(): Boolean {
        val d = loadAll(posizione())
        provato = true
        if (d.vuoto) {
            invalidate()
            return false
        }
        val fh = d.f?.optJSONObject("hourly")
        val mh = d.m?.optJSONObject("hourly")
        val daily = d.fd?.optJSONObject("daily")
        modello = Modello(
            cur = d.f?.optJSONObject("current"),
            tide = series(mh, "sea_level_height_msl"),
            windSpeed = series(fh, "wind_speed_10m"),
            windDir = series(fh, "wind_direction_10m"),
            wave = series(mh, "wave_height"),
            waveDir = series(mh, "wave_direction"),
            pressure = series(fh, "pressure_msl"),
            sunrise = parseTime(daily?.optJSONArray("sunrise")?.optString(0)),
            sunset = parseTime(daily?.optJSONArray("sunset")?.optString(0))
        )
        invalidate()
        return !d.incompleto
    }

    // ---------- disegno ----------

    private fun pt(sz: Float, col: Int, bold: Boolean = false, al: Paint.Align = Paint.Align.CENTER): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sz
            color = col
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textAlign = al
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

    // punto sul cerchio: 0 gradi = nord (in alto), senso orario
    private fun px(cx: Float, r: Float, a: Float): Float = cx + r * sin(Math.toRadians(a.toDouble())).toFloat()
    private fun py(cy: Float, r: Float, a: Float): Float = cy - r * cos(Math.toRadians(a.toDouble())).toFloat()

    private fun lunaIlluminata(ms: Long): Double {
        val jd = ms / 86_400_000.0 + 2440587.5
        val fase = (((jd - 2451550.1) / 29.530588853) % 1.0 + 1.0) % 1.0
        return (1 - cos(2 * PI * fase)) / 2
    }

    private fun lunaCresce(ms: Long): Boolean {
        val jd = ms / 86_400_000.0 + 2440587.5
        val fase = (((jd - 2451550.1) / 29.530588853) % 1.0 + 1.0) % 1.0
        return fase < 0.5
    }

    override fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime) {
        val ambient = renderParameters.drawMode == DrawMode.AMBIENT
        canvas.drawColor(if (ambient) Color.BLACK else sfondo)

        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val s = bounds.width() / 450f
        canvas.scale(s, s)

        val loc = Locale.getDefault()
        val data = DateTimeFormatter.ofPattern("EEE d MMM", loc).format(zonedDateTime)
        val ora = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm", loc).format(zonedDateTime)

        if (ambient) {
            canvas.drawText(data, 225f, 170f, pt(24f, grigio))
            canvas.drawText(ora, 225f, 255f, pt(78f, Color.WHITE, true))
            canvas.restore()
            return
        }

        val now = zonedDateTime.toInstant().toEpochMilli()

        // anello esterno
        canvas.drawCircle(225f, 225f, 214f, tratto(3f, anello))

        // data e ora
        canvas.drawText(data, 225f, 46f, pt(21f, chiaro))
        canvas.drawText(ora, 225f, 112f, pt(66f, Color.WHITE, true))

        val m = modello
        if (m == null) {
            val msg = if (provato) "dati non disponibili" else "carico i dati..."
            canvas.drawText(msg, 225f, 250f, pt(20f, grigio))
            canvas.restore()
            return
        }

        disegnaAlto(canvas, m, now)
        disegnaMarea(canvas, m, now)
        disegnaBasso(canvas, m, now)

        canvas.restore()
    }

    // temperatura e pressione + alba/tramonto e luna
    private fun disegnaAlto(c: Canvas, m: Modello, now: Long) {
        val temp = m.cur.n("temperature_2m")
        val um = m.cur.n("relative_humidity_2m")
        val pr = pressureTrend(m.pressure, now, m.cur.n("pressure_msl"))

        // sinistra: temperatura
        val colT = if (temp != null) tempColor(temp) else grigio
        c.drawCircle(82f, 72f, 8f, pieno(colT))
        c.drawText(if (temp != null) temp.roundToInt().toString() + "\u00B0" else "--", 82f, 104f, pt(28f, Color.WHITE, true))
        c.drawText(if (um != null) "umid. " + um.roundToInt() + "%" else "", 82f, 126f, pt(15f, grigio))

        // destra: pressione
        val colP = pr?.color? ?: grigio
        c.drawCircle(368f, 72f, 8f, pieno(colP))
        c.drawText(if (pr != null) pr.p.roundToInt().toString() else "--", 368f, 104f, pt(28f, Color.WHITE, true))
        c.drawText(if (pr != null) "hPa " + pr.word else "", 360f, 126f, pt(12f, grigio))

        // alba e tramonto
        val sole = if (m.sunrise != null && m.sunset != null) "\u2191" + hhmm(m.sunrise) + " \u2193" + hhmm(m.sunset) else ""
        c.drawText(sole, 112f, 160f, pt(16f, Color.parseColor("#F2C94C")))

        // luna
        val illum = lunaIlluminata(now)
        val cresce = lunaCresce(now)
        val cx = 296f
        val cy = 153f
        val r = 9f
        c.drawCircle(cx, cy, r, pieno(Color.parseColor("#E8DFC0")))
        val spostamento = (2 * r * illum).toFloat()
        val sx = if (cresce) cx - spostamento else cx + spostamento
        c.save()
        c.clipRect(cx - r - 1f, cy - r - 1f, cx + r + 1f, cy + r + 1f)
        c.drawCircle(sx, cy, r, pieno(sfondo))
        c.restore()
        c.drawText((illum * 100).roundToInt().toString() + "%", 336f, 160f, pt(19f, Color.WHITE))
    }

    // curva della marea con alte e basse
    private fun disegnaMarea(c: Canvas, m: Modello, now: Long) {
        val inizio = now - 4 * H1
        val fine = now + 10 * H1
        val passo = 15 * 60_000L
        val xs = 48f
        val xe = 402f
        val yAlto = 205f
        val yBasso = 285f
        val base = 305f

        val punti = ArrayList<Pair<Long, Double?>>()
        var t = inizio
        while (t <= fine) {
            punti.add(Pair(t, at(m.tide, t)))
            t += passo
        }
        val vals = punti.mapNotNull { it.second }
        if (vals.isEmpty()) {
            c.drawText("marea n.d.", 225f, 255f, pt(18f, grigio))
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
            val qx = x(p.first)
            val qy = y(v)
            if (primo) {
                linea.moveTo(qx, qy)
                xPrimo = qx
                primo = false
            } else linea.lineTo(qx, qy)
            xUltimo = qx
        }
        val riempi = Path(linea)
        riempi.lineTo(xUltimo, base)
        riempi.lineTo(xPrimo, base)
        riempi.close()
        c.drawPath(riempi, pieno(area))
        c.drawPath(linea, tratto(4f, curva))

        // alte e basse
        for (e in tideExtremes(m.tide, inizio, 8)) {
            if (e.t < inizio || e.t > fine) continue
            val ex = x(e.t)
            val ey = y(e.v)
            if (e.alta) {
                c.drawCircle(ex, ey, 9f, pieno(oro))
                c.drawText(hhmm(e.t), ex, ey - 16f, pt(17f, oro))
            } else {
                c.drawCircle(ex, ey, 9f, pieno(ciano))
                c.drawText(hhmm(e.t), ex, ey + 28f, pt(17f, ciano))
            }
        }

        // adesso
        val nx = x(now)
        val livello = at(m.tide, now)
        val tratteggio = tratto(2f, Color.WHITE).apply { pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f) }
        c.drawLine(nx, 195f, nx, base, tratteggio)
        if (livello != null) c.drawCircle(nx, y(livello), 8f, pieno(Color.WHITE))
    }

    // vento, bussola, onda
    private fun disegnaBasso(c: Canvas, m: Modello, now: Long) {
        val cur = m.cur
        val kn = cur.n("wind_speed_10m") ?: at(m.windSpeed, now)
        val dv = cur.n("wind_direction_10m") ?: nearest(m.windDir, now)
        val onda = at(m.wave, now)
        val dOnda = nearest(m.waveDir, now)

        val colV = if (kn != null) windColor(kn) else grigio
        val colO = if (onda != null) waveColor(onda) else grigio

        // vento (sinistra)
        val vx = 140f
        val vy = 350f
        c.drawCircle(vx, vy, 34f, tratto(3f, anello))
        val lv = tratto(3f, colV)
        c.drawLine(vx - 16f, vy - 6f, vx + 8f, vy - 6f, lv)
        c.drawLine(vx - 16f, vy + 4f, vx + 14f, vy + 4f, lv)
        if (dv != null) {
            val a = ((dv + 180) % 360).toFloat()
            triangolo(c, px(vx, 34f, a), py(vy, 34f, a), 8f, a, colV)
        }
        c.drawText(if (kn != null) kn.roundToInt().toString() + " kn" else "--", vx, 402f, pt(22f, colV))

        // onda (destra)
        val ox = 310f
        val oy = 350f
        c.drawCircle(ox, oy, 34f, tratto(3f, anello))
        val sinus = Path()
        for (i in 0..32) {
            val xx = ox - 18f + i * 36f / 32f
            val yy = oy + 6f * sin(i / 32.0 * 4.0 * PI).toFloat()
            if (i == 0) sinus.moveTo(xx, yy) else sinus.lineTo(xx, yy)
        }
        c.drawPath(sinus, tratto(3f, colO))
        if (dOnda != null) {
            val a = ((dOnda + 180) % 360).toFloat()
            triangolo(c, px(ox, 34f, a), py(oy, 34f, a), 8f, a, colO)
        }
        c.drawText(
            if (onda != null) String.format(Locale.getDefault(), "%.1f m", onda) else "--",
            ox, 402f, pt(22f, colO)
        )

        // bussola (centro): N, ago, punti vento e onda
        val bx = 225f
        val by = 358f
        val br = 32f
        c.drawCircle(bx, by, br, tratto(3f, anello))
        c.drawText("N", bx, by - br + 18f, pt(14f, Color.parseColor("#FF4D6D"), true))
        triangolo(c, bx, by - 2f, 11f, 0f, Color.parseColor("#FF4D6D"))
        triangolo(c, bx, by + 6f, 8f, 180f, grigio)
        if (dv != null) c.drawCircle(px(bx, br, dv.toFloat()), py(by, br, dv.toFloat()), 5f, pieno(colV))
        if (dOnda != null) c.drawCircle(px(bx, br, dOnda.toFloat()), py(by, br, dOnda.toFloat()), 5f, pieno(colO))
    }

    override fun renderHighlightLayer(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime) {
        // nessun elemento evidenziabile
    }
}
