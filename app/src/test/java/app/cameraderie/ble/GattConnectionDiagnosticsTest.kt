package app.cameraderie.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GattConnectionDiagnosticsTest {
    @Test fun convertsGattUnitsAndRejectsInvalidSamples() {
        assertEquals(
            "interval_us=37500 peripheral_latency_events=4 supervision_timeout_ms=4000",
            GattConnectionDiagnostics.parameters(30, 4, 400),
        )
        assertNull(GattConnectionDiagnostics.parameters(5, 4, 400))
        assertNull(GattConnectionDiagnostics.parameters(30, 500, 400))
        assertNull(GattConnectionDiagnostics.parameters(30, 4, 9))
    }
}
