package com.magosilom.aquasense

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** HTTP 404 from a v3 endpoint: the ESP32 runs v2 firmware. */
class Unsupported : IOException("Endpoint not supported by this firmware")

class EspClient(
    private val baseUrl: String = ESP_BASE,
    private val network: () -> Network?,
) {
    suspend fun readings(): LiveReading = parseReadings(request("GET", "/readings"))

    suspend fun postTime(epochS: Long): Boolean {
        val j = org.json.JSONObject(request("POST", "/time?epoch=$epochS"))
        return j.optBoolean("accepted", false)
    }

    suspend fun log(since: Long, max: Int = 100): LogPage =
        parseLog(request("GET", "/log?since=$since&max=$max"))

    suspend fun calStart() { request("POST", "/cal/start") }

    suspend fun cal(): CalStatus = parseCal(request("GET", "/cal"))

    suspend fun setStation(name: String) {
        request("POST", "/station?name=" + URLEncoder.encode(name, "UTF-8"))
    }

    private suspend fun request(method: String, path: String): String = withContext(Dispatchers.IO) {
        val u = URL(baseUrl + path)
        val net = network()
        val c = (net?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
        try {
            c.connectTimeout = 1500
            c.readTimeout = 1500
            c.useCaches = false
            c.setRequestProperty("Connection", "close")
            c.requestMethod = method
            if (method == "POST") {
                c.doOutput = true
                c.setFixedLengthStreamingMode(0)
                c.outputStream.close()
            }
            val code = c.responseCode
            if (code == 404) throw Unsupported()
            if (code != 200) throw IOException("ESP32 answered HTTP $code")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
