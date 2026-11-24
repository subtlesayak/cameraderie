package app.cameraderie.core

import org.junit.Assert.*
import org.junit.Test

class CameraProfilesTest {
    @Test fun onlyPhysicallyTestedModelCanAuthorizeControls() {
        assertEquals(listOf(0xFF55),CameraProfiles.all.filter { it.controlsEnabled }.map { it.id })
        assertFalse(CameraProfiles.find(0xFF33)!!.controlsEnabled)
        assertFalse(CameraProfiles.find(0xFF44)!!.controlsEnabled)
        assertFalse(CameraProfiles.find(0xFF66)!!.controlsEnabled)
    }
    @Test fun unknownModelDoesNotInheritActionCapabilities() {
        assertNull(CameraProfiles.find(0x1234));assertNull(CameraProfiles.find(0xFF330000))
        assertEquals(0x38,CameraProfiles.find(0xFF66)!!.videoMode);assertEquals(0x3F,CameraProfiles.find(0xFF66)!!.photoMode)
    }
    @Test fun observedAckAliasIsScopedToAction6() {
        assertEquals(setOf(3),CameraProfiles.find(0xFF55)!!.modeAckAliases)
        assertTrue(CameraProfiles.all.filter { it.id != 0xFF55 }.all { it.modeAckAliases.isEmpty() })
    }
    @Test fun disabledKnownModelsNeverCompleteAuthorization() {
        for(model in listOf(0xFF33L,0xFF44L,0xFF66L,0x1234L)) {
            val sent=mutableListOf<DjiProtocol.Frame>()
            val s=DjiSession(123,ByteArray(6),{100L},{b,done->sent+=DjiProtocol.decode(b)!!;done(true)},{},{})
            s.begin(false,1234);s.receive(DjiProtocol.Frame(2,40,0,0x19,DjiProtocol.connection(model,ByteArray(6),2,0)))
            assertEquals(DjiSession.Auth.FAILED,s.auth)
            assertEquals(1,sent.size);assertNull(s.profile)
        }
    }
}
