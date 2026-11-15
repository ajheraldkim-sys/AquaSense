package com.magosilom.aquasense

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
fun fmtTime(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))

/** "Today 10:42", "Yesterday 08:10", "12 Nov 17:30". */
fun friendlyTime(ts: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val dayDiff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dayDiff == 0 -> "Today $hm"
        sameYear && dayDiff == 1 -> "Yesterday $hm"
        else -> SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(ts))
    }
}

/** "just now", "12 s ago", "3 min ago", "2 h ago". */
fun ago(ts: Long, now: Long = System.currentTimeMillis()): String {
    val s = ((now - ts) / 1000).coerceAtLeast(0)
    return when {
        s < 5 -> "just now"
        s < 60 -> "$s s ago"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}

const val DISCLAIMER =
    "AquaSense compares the water's turbidity (cloudiness) with the limit set in this app. It is " +
        "not a test of drinking-water safety: clear water can still contain germs, chemicals or " +
        "heavy metals. Only laboratory testing and the local health authority (RHU / Municipal " +
        "Health Office) can confirm that water is safe to drink."

/** Turbidity above 80 % of the limit is "Check again"; above the limit is "Don't drink". */
const val NEAR_FRACTION = 0.8

fun assess(r: Reading, l: Limits): Assessment {
    val t = r.turbidity
    return when {
        t > l.turbMax -> {
            val issue = Issue("turb_high", "Cloudy (${f1(t)} NTU)",
                "Turbidity ${f2(t)} NTU is above the maximum of ${f1(l.turbMax)} NTU.",
                "Check for runoff, sediment or a damaged intake, and wipe the sensor lens.")
            Assessment(Level.UNSAFE, issue.short, issue)
        }
        t > l.turbMax * NEAR_FRACTION -> Assessment(Level.CAUTION, "Close to the limit · measure again", null)
        else -> Assessment(Level.WITHIN, "Clear · below the limit", null)
    }
}

// ---------------------------------------------------------------- sensors

private fun JSONObject.num(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key).takeUnless { it.isNaN() || it.isInfinite() }

/**
 * ESP32 over Wi-Fi. The ESP32 answers HTTP GET at [url] with JSON:
 *   {"turbidity": 1.8, "age_ms": 300}
 * Extra fields are ignored. Routes over Wi-Fi even when mobile data is on.
 */
class Esp32Sensor(private val url: String, private val context: Context? = null) {
    suspend fun read(): Sample = withContext(Dispatchers.IO) {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        @Suppress("DEPRECATION")
        val wifiNet = cm?.allNetworks?.firstOrNull { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val u = URL(url)
        val c = (wifiNet?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
        try {
            c.connectTimeout = 3000
            c.readTimeout = 3000
            c.useCaches = false
            if (c.responseCode != 200) throw IOException("ESP32 answered HTTP ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val turb = j.num("turbidity") ?: throw IOException("No turbidity value")
            if (turb < 0.0 || turb > 3000.0) throw IOException("Turbidity out of sensor range")
            Sample(turb, if (j.has("age_ms") && !j.isNull("age_ms")) j.optLong("age_ms") else null)
        } finally {
            c.disconnect()
        }
    }
}

/** Data older than this (ESP32 sampling stopped / sensor hung) is rejected. */
const val MAX_SAMPLE_AGE_MS = 10_000L

fun median(v: List<Double>): Double {
    val s = v.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
}

/** Combine raw samples into one robust value (the median rejects single spikes). */
fun combine(samples: List<Sample>): Sample = Sample(median(samples.map { it.turbidity }))
