package app.cameraderie.core

import app.gripbridge.core.DjiGattSetup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DjiGattSetupTest {
    @Test fun staleCallbacksCannotAuthorizeTheReplacementConnection() {
        val setup = DjiGattSetup<Any>()
        val old = Any()
        val current = Any()
        setup.open(old)
        assertTrue(setup.beginSubscription(old))
        assertSame(old, setup.close())
        setup.open(current)
        assertTrue(setup.beginSubscription(current))

        assertFalse(setup.subscriptionCompleted(old))
        assertFalse(setup.markReady(old))
        assertFalse(setup.markReady(current))
        assertFalse(setup.ready)

        assertTrue(setup.subscriptionCompleted(current))
        assertTrue(setup.markReady(current))
        assertFalse(setup.markReady(current))
        assertTrue(setup.ready)
        assertSame(current, setup.close())
        assertFalse(setup.ready)
    }
}
