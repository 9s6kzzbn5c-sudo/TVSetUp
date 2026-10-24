package com.minimal.tvhome

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

data class AppDef(val label: String, val pkgs: List<String>, val key: String? = null)

class MainActivity : Activity() {

    // ---- Change these if you like ----
    private val city = "Dhaka"
    private val lat = 23.8103
    private val lon = 90.4125
    private val apps = listOf(
        AppDef("Netflix", listOf("com.netflix.ninja", "com.netflix.mediaclient"), "netflix"),
        AppDef("Prime Video", listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"), "prime video"),
        AppDef("YouTube", listOf("com.google.android.youtube.tv", "com.google.android.youtube")),
        AppDef("Chorki", emptyList(), "chorki"),
        AppDef("Hoichoi", emptyList(), "hoichoi"),
        AppDef("YouTube Music", listOf("com.google.android.youtube.tvmusic", "com.google.android.apps.youtube.music")),
        AppDef("Spotify", listOf("com.spotify.tv.android", "com.spotify.music"), "spotify")
    )
    // -----------------------------------

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var clockView: TextView
    private lateinit var dateView: TextView
    private lateinit var weatherRow: LinearLayout
    private lateinit var appsRow: LinearLayout
    private lateinit var qrView: ImageView
    private lateinit var qrCaption: TextView
    private val prefs by lazy { getSharedPreferences("wifi", MODE_PRIVATE) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(sp: Float, color: String, tf: Typeface? = null) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Color.parseColor(color))
        if (tf != null) typeface = tf
    }

    private fun focusable(v: View) {
        v.isFocusable = true
        val bg = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#22FFFFFF"))
            setStroke(dp(2), Color.parseColor("#88FFFFFF"))
        }
        v.setOnFocusChangeListener { view, has ->
            view.background = if (has) bg else null
            view.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).setDuration(120).start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(64), dp(40), dp(64), dp(40))
        }

        // Top: clock/date (left) + Wi-Fi QR (right)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clockView = text(96f, "#FFFFFF", Typeface.create("sans-serif-thin", Typeface.NORMAL))
        dateView = text(24f, "#9E9E9E")
        left.addView(clockView)
        left.addView(dateView)
        top.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { editWifi() }
        }
        focusable(right)
        qrView = ImageView(this)
        qrCaption = text(14f, "#9E9E9E").apply { gravity = Gravity.CENTER }
        right.addView(qrView, LinearLayout.LayoutParams(dp(150), dp(150)))
        right.addView(qrCaption)
        top.addView(right)
        root.addView(top)

        // Weather
        val wTitle = text(14f, "#9E9E9E").apply { text = "$city  ·  next 7 days"; setPadding(0, dp(24), 0, dp(8)) }
        root.addView(wTitle)
        weatherRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(weatherRow)

        // Spacer
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // Apps
        appsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        root.addView(appsRow)

        setContentView(root)
        refreshQr()
        loadWeather()
        if (prefs.getString("ssid", "").isNullOrEmpty()) ui.postDelayed({ editWifi() }, 600)
    }

    override fun onResume() {
        super.onResume()
        buildApps()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    @Deprecated("Launcher: ignore Back")
    override fun onBackPressed() { /* stay home */ }

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clockView.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
            dateView.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
            ui.postDelayed(this, 1000)
        }
    }

    // ---------- Apps ----------
    private fun resolve(a: AppDef): Intent? {
        val pm = packageManager
        for (p in a.pkgs) {
            val i = pm.getLeanbackLaunchIntentForPackage(p) ?: pm.getLaunchIntentForPackage(p)
            if (i != null) return i
        }
        val key = a.key ?: return null
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(q, 0)) {
                if (ri.loadLabel(pm).toString().lowercase().contains(key)) {
                    return Intent(Intent.ACTION_MAIN).addCategory(cat)
                        .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                }
            }
        }
        return null
    }

    private fun buildApps() {
        appsRow.removeAllViews()
        var first: View? = null
        for (a in apps) {
            val intent = resolve(a)
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(16), dp(12), dp(12))
                alpha = if (intent != null) 1f else 0.3f
            }
            focusable(tile)
            val icon = ImageView(this)
            val pkg = intent?.component?.packageName
            if (pkg != null) try { icon.setImageDrawable(packageManager.getApplicationIcon(pkg)) } catch (_: Exception) {}
            tile.addView(icon, LinearLayout.LayoutParams(dp(72), dp(72)))
            tile.addView(text(14f, "#FFFFFF").apply { text = a.label; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
            tile.setOnClickListener {
                if (intent != null) startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else Toast.makeText(this, "${a.label} is not installed", Toast.LENGTH_SHORT).show()
            }
            val lp = LinearLayout.LayoutParams(dp(130), LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(8), 0, dp(8), 0) }
            appsRow.addView(tile, lp)
            if (first == null) first = tile
        }
        first?.requestFocus()
    }

    // ---------- Weather ----------
    private fun emoji(c: Int) = when (c) {
        0 -> "☀️"; 1, 2 -> "🌤️"; 3 -> "☁️"; 45, 48 -> "🌫️"
        in 51..57 -> "🌦️"; in 61..67, in 80..82 -> "🌧️"
        in 71..77, 85, 86 -> "❄️"; in 95..99 -> "⛈️"; else -> "🌡️"
    }

    private fun loadWeather() {
        thread {
            var delay = 60_000L
            try {
                val u = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&daily=weathercode,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=7"
                val d = JSONObject(URL(u).readText()).getJSONObject("daily")
                val t = d.getJSONArray("time"); val c = d.getJSONArray("weathercode")
                val hi = d.getJSONArray("temperature_2m_max"); val lo = d.getJSONArray("temperature_2m_min")
                val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val outFmt = SimpleDateFormat("EEE", Locale.getDefault())
                ui.post {
                    weatherRow.removeAllViews()
                    for (i in 0 until t.length()) {
                        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
                        val day = if (i == 0) "Today" else outFmt.format(inFmt.parse(t.getString(i))!!)
                        col.addView(text(14f, "#9E9E9E").apply { text = day })
                        col.addView(text(30f, "#FFFFFF").apply { text = emoji(c.getInt(i)) })
                        col.addView(text(18f, "#FFFFFF").apply { text = "${Math.round(hi.getDouble(i))}°" })
                        col.addView(text(14f, "#9E9E9E").apply { text = "${Math.round(lo.getDouble(i))}°" })
                        weatherRow.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    }
                }
                delay = 30 * 60_000L
            } catch (_: Exception) {}
            ui.postDelayed({ loadWeather() }, delay)
        }
    }

    // ---------- Wi-Fi QR ----------
    private fun refreshQr() {
        val ssid = prefs.getString("ssid", "") ?: ""
        if (ssid.isEmpty()) {
            qrView.setImageDrawable(null)
            qrCaption.text = "Press OK to set\nWi-Fi QR"
            return
        }
        val pw = prefs.getString("pw", "") ?: ""
        val esc = { s: String ->
            s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")
        }
        val payload = if (pw.isEmpty()) "WIFI:T:nopass;S:${esc(ssid)};;" else "WIFI:T:WPA;S:${esc(ssid)};P:${esc(pw)};;"
        val size = 600
        val m = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
        val px = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
        qrView.setImageBitmap(Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888))
        qrCaption.text = "Scan to join\n$ssid"
    }

    private fun editWifi() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val s = EditText(this).apply { hint = "Wi-Fi name (SSID)"; setSingleLine(); setText(prefs.getString("ssid", "")) }
        val p = EditText(this).apply { hint = "Password (empty if open)"; setSingleLine(); setText(prefs.getString("pw", "")) }
        box.addView(s); box.addView(p)
        AlertDialog.Builder(this)
            .setTitle("Wi-Fi QR")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("ssid", s.text.toString().trim()).putString("pw", p.text.toString()).apply()
                refreshQr()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
