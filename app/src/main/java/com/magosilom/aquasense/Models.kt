package com.magosilom.aquasense

import java.util.UUID

const val ORIGIN_ESP = "ESP32"

data class Source(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val location: String,
    val type: String
)

/** One stored measurement: the median turbidity of several raw samples. */
data class Reading(
    val id: String = UUID.randomUUID().toString(),
    val sourceId: String,
    val ts: Long,
    val turbidity: Double,
    val samples: Int = 1,
    val origin: String = ORIGIN_ESP
)

/** One raw sample straight from the ESP32. */
data class Sample(val turbidity: Double, val ageMs: Long? = null)

/** Configurable limit. Default: turbidity max 5 NTU. */
data class Limits(val turbMax: Double = 5.0)

data class AlertItem(
    val id: String = UUID.randomUUID().toString(),
    val sourceId: String,
    val key: String,
    val message: String,
    val ts: Long,
    val resolved: Boolean = false
)

data class EspConfig(val url: String = "http://192.168.4.1/readings")

/** Deliberately no "SAFE" level: a turbidity sensor alone can never certify drinking water. */
enum class Level(val label: String) {
    WITHIN("Within limits"),
    CAUTION("Check again"),
    UNSAFE("Don't drink")
}

data class Issue(val key: String, val short: String, val text: String, val tip: String)

data class Assessment(val level: Level, val headline: String, val issue: Issue?)

/** Connection state of the ESP32 link. */
enum class Link { IDLE, CONNECTING, ONLINE, OFFLINE }
