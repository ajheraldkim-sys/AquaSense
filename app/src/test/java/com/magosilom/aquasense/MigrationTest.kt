package com.magosilom.aquasense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationTest {
    @Test fun validItemsMap() {
        val json = """[{"id":"a","sourceId":"s","ts":1000,"turb":2.5,"samples":1,"origin":"esp"},
            {"id":"b","sourceId":"s","ts":2000,"turb":3.0,"ph":7.1}]"""
        assertEquals(listOf(Point(1000L, 2.5), Point(2000L, 3.0)), v2ToPoints(json))
    }

    @Test fun itemMissingTurbIsSkipped() {
        val json = """[{"ts":1000},{"ts":2000,"turb":1.5}]"""
        assertEquals(listOf(Point(2000L, 1.5)), v2ToPoints(json))
    }

    @Test fun malformedJsonIsEmpty() {
        assertTrue(v2ToPoints("not json").isEmpty())
        assertTrue(v2ToPoints("").isEmpty())
    }
}
