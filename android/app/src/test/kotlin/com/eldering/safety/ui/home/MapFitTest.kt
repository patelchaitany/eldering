package com.eldering.safety.ui.home

import com.eldering.safety.ui.trip.TripViewModel
import com.eldering.safety.network.IncidentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapFitTest {
    @Test
    fun `fits once, again when someone new appears, never for moves or departures`() {
        val first = MapFit.decide(null, setOf("me"))
        assertTrue(first.refit)
        assertFalse("same people", MapFit.decide(first.fitted, setOf("me")).refit)
        // E2E: B switched to Live while Home was open.
        val withB = MapFit.decide(first.fitted, setOf("me", "b"))
        assertTrue(withB.refit)
        val bGone = MapFit.decide(withB.fitted, setOf("me"))
        assertFalse(bGone.refit)
        assertTrue("B coming back is new again", MapFit.decide(bGone.fitted, setOf("me", "b")).refit)
        assertFalse("nobody on the map", MapFit.decide(null, emptySet()).refit)
    }

    @Test
    fun `own incident kinds parse from their wire names`() {
        assertEquals(IncidentKind.VEHICLE_CRASH, TripViewModel.parseKind("vehicle_crash"))
        assertEquals(IncidentKind.SOS, TripViewModel.parseKind("sos"))
        assertNull(TripViewModel.parseKind("meteor"))
    }
}
