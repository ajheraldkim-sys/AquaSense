package com.magosilom.aquasense

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class StringsTest {
    /** Unit tests run with the module dir (app/) as working dir; fall back to the repo root. */
    private val res: File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    /** name -> all text of a file's string / plurals / string-array values (plural items joined). */
    private fun load(folder: String): Map<String, String> {
        val f = File(res, "$folder/strings.xml")
        assertTrue("missing ${f.path}", f.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val out = LinkedHashMap<String, String>()
        val root = doc.documentElement
        val kids = root.childNodes
        for (i in 0 until kids.length) {
            val e = kids.item(i) as? Element ?: continue
            if (e.tagName !in setOf("string", "plurals", "string-array")) continue
            out[e.getAttribute("name")] = e.textContent
        }
        return out
    }

    private val en by lazy { load("values") }
    private val fil by lazy { load("values-b+fil") }
    private val ceb by lazy { load("values-b+ceb") }

    @Test fun translationsHaveEveryKey() {
        val keys = en.keys - "app_name"
        for ((name, t) in listOf("fil" to fil, "ceb" to ceb)) {
            val missing = keys - t.keys
            assertTrue("$name missing: $missing", missing.isEmpty())
            val extra = t.keys - en.keys
            assertTrue("$name has keys not in values/: $extra", extra.isEmpty())
        }
    }

    @Test fun nowScreenKeysExist() {
        val required = listOf(
            "state_clear", "state_getting_cloudy", "state_cloudy", "sub_cloudiness", "sub_check_soon",
            "tip_cloudy", "live_updated", "connecting", "not_connected", "go_near", "last_seen",
            "sensor_problem", "tell_caretaker", "needs_cleaning", "last_24h"
        )
        val missing = required - en.keys
        assertTrue("values/ missing: $missing", missing.isEmpty())
    }

    @Test fun exactEnglishCopy() {
        assertEquals("Clear", en["state_clear"])
        assertEquals("Getting cloudy", en["state_getting_cloudy"])
        assertEquals("Cloudy — don't drink", en["state_cloudy"]?.replace("\\'", "'"))
        assertEquals("Cloudiness %1\$s (limit is %2\$s)", en["sub_cloudiness"])
        assertEquals("Check again soon", en["sub_check_soon"])
        assertEquals("Use stored or boiled water for now.", en["tip_cloudy"])
        assertEquals("Live · updated %1\$s", en["live_updated"])
        assertEquals("Connecting to the water sensor…", en["connecting"])
        assertEquals("Not connected", en["not_connected"])
        assertEquals("Go near the water station.", en["go_near"])
        assertEquals("Last seen %1\$s: %2\$s", en["last_seen"])
        assertEquals("Sensor not working", en["sensor_problem"])
        assertEquals("Tell the water station caretaker.", en["tell_caretaker"])
        assertEquals("Sensor needs cleaning", en["needs_cleaning"])
        assertEquals("Last 24 hours", en["last_24h"])
    }

    /** Task 11 copy, verbatim from the brief. */
    @Test fun historyAndSensorCareCopy() {
        fun e(k: String) = en[k]?.replace("\\'", "'")
        assertEquals("Clear all day", e("day_clear"))
        assertEquals("Got a bit cloudy", e("day_getting_cloudy"))
        assertEquals("Cloudy for %1\$s (%2\$s–%3\$s)", e("day_cloudy"))
        assertEquals("Sensor calibrated", e("cal_event"))
        assertEquals("No readings yet", e("history_empty"))
        assertEquals("Never calibrated", e("cal_never"))
        assertEquals("Put the sensor in clean bottled water", e("cal_step1"))
        assertEquals("Keep the sensor still in clean water", e("cal_settling"))
        assertEquals("Press the button on the sensor box", e("cal_ready"))
        assertEquals("Calibrated", e("cal_done"))
        assertEquals("The water doesn't look clean. Use fresh bottled water.", e("cal_too_low"))
        assertEquals("Is the sensor in the water?", e("cal_out_of_range"))
        assertEquals("Took too long — try again", e("cal_expired"))
        assertEquals("Calibration needs the sensor's Wi-Fi", e("cal_needs_wifi"))
    }

    /** Residents' History rows say "cloudy", never units; only the share report (report_*) may say NTU. */
    @Test fun historyRowsHaveNoUnits() {
        for ((name, t) in listOf("values" to en, "fil" to fil, "ceb" to ceb)) {
            for ((k, v) in t) {
                if (!(k.startsWith("day_") || k.startsWith("alert_") || k.startsWith("history_"))) continue
                assertFalse("$name/$k mentions NTU: $v", v.contains("NTU"))
            }
        }
    }

    /** Cebuano UI no longer carries English-only "Settings" / "Live". */
    @Test fun cebuanoSettingsAndLiveLocalized() {
        assertNotEquals("Settings", ceb["tab_settings"])
        assertFalse(ceb["live_updated"]!!.startsWith("Live"))
    }

    /** Every resource XML in every values* folder, not only strings.xml. */
    @Test fun neverSaysSafe() {
        val banned = listOf("safe", "ligtas", "luwas")
        val files = res.listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!
            .flatMap { d -> d.listFiles { f -> f.name.endsWith(".xml") }!!.toList() }
        assertTrue("no values*/*.xml found", files.size >= 4)
        for (f in files) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val text = doc.documentElement.textContent
            val hit = banned.firstOrNull { text.contains(it, ignoreCase = true) }
            assertNull("${f.parentFile.name}/${f.name} contains '$hit'", hit)
        }
    }

    /** Cebuano: "labog" means "to throw"; murky water is "lubog". */
    @Test fun cebuanoUsesLubogNotLabog() {
        for ((k, v) in ceb) assertFalse("ceb/$k uses 'labog': $v", v.contains("labog", ignoreCase = true))
        assertTrue(ceb["state_cloudy"]!!.contains("Lubog"))
        assertEquals("Tin-aw", ceb["state_clear"])
    }

    @Test fun nowScreenHasNoJargon() {
        val banned = listOf("NTU", "turbidity", "ESP32", "calibrat")
        fun isNow(k: String) = k.startsWith("state_") || k.startsWith("sub_") ||
            k in setOf("live_updated", "connecting", "not_connected", "sensor_problem", "tip_cloudy")
        for ((name, t) in listOf("values" to en, "fil" to fil, "ceb" to ceb)) {
            for ((k, v) in t) {
                if (!isNow(k)) continue
                val hit = banned.firstOrNull { v.contains(it, ignoreCase = true) }
                assertNull("$name/$k contains '$hit': $v", hit)
            }
        }
    }

    @Test fun placeholdersMatchEnglish() {
        val ph = Regex("%\\d\\$[sd]|%[sd]")
        for ((name, t) in listOf("fil" to fil, "ceb" to ceb)) {
            for ((k, v) in en) {
                val tv = t[k] ?: continue
                assertEquals("$name/$k placeholders", ph.findAll(v).map { it.value }.toSet(), ph.findAll(tv).map { it.value }.toSet())
            }
        }
    }

    @Test fun resolveLanguageUsesSystemOnlyForSupported() {
        assertEquals("fil", resolveLanguage(AppLanguage.SYSTEM, "fil-PH"))
        assertEquals("fil", resolveLanguage(AppLanguage.SYSTEM, "tl"))
        assertEquals("ceb", resolveLanguage(AppLanguage.SYSTEM, "ceb"))
        assertEquals("en", resolveLanguage(AppLanguage.SYSTEM, "ja-JP"))
        assertEquals("ceb", resolveLanguage(AppLanguage.CEB, "en-US"))
        assertEquals("en", resolveLanguage(AppLanguage.EN, "fil"))
        assertEquals("fil", resolveLanguage(AppLanguage.FIL, "en"))
    }
}
