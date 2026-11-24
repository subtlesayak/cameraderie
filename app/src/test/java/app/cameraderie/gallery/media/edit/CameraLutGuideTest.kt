package app.cameraderie.gallery.media.edit

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class CameraLutGuideTest {
    @Test fun derivesSonyModelAndProfileFromMovieMetadata() {
        val tags=listOf(
            "com.apple.quicktime.make" to "Sony",
            "com.apple.quicktime.model" to "ILCE-7M4",
            "com.sony.camera.gamma" to "S-Log3"
        )
        val keys=box("keys",int(0)+int(tags.size)+tags.fold(byteArrayOf()) { all,(name,_) ->
            all+box("mdta",name.toByteArray())
        })
        val values=box("ilst",tags.foldIndexed(byteArrayOf()) { index,all,(_,value) ->
            all+box(int(index+1),box("data",int(1)+int(0)+value.toByteArray()))
        })
        val clip=File.createTempFile("opengrip-camera-metadata",".mov")
        try {
            clip.writeBytes(box("moov",box("meta",int(0)+keys+values)))
            val guide=CameraLutGuide.read(clip,"Another camera")
            assertEquals("Sony ILCE-7M4",guide.camera)
            assertEquals("S-Log3",guide.profile)
            assertTrue(guide.recommendation.contains("S-Log3 to Rec.709"))
            assertTrue(guide.score("Sony_ILCE-7M4_SLog3_to_Rec709.cube") >
                guide.score("S-Log3_to_Rec709.cube"))
            assertEquals(0,guide.score("Sony_ILCE-7M4_S-Log2.cube"))
        } finally {
            clip.delete()
        }
    }

    @Test fun missingMetadataKeepsTheImportModelWithoutAssumingALogProfile() {
        val clip=File.createTempFile("opengrip-no-metadata",".mp4")
        try {
            val guide=CameraLutGuide.read(clip,"Panasonic GH6")
            assertEquals("Panasonic GH6",guide.camera)
            assertNull(guide.profile)
            assertTrue(guide.score("Panasonic GH6 colour.cube")>0)
            assertEquals(0,guide.score("V-Log to Rec709.cube"))
        } finally {
            clip.delete()
        }
    }

    @Test fun ranksSpecificProfilesAcrossBrandsWithoutMixingSimilarLogNames() {
        for((profile,name) in listOf("D-Log M" to "DJI_DLOG-M_Rec-709.cube",
            "F-Log2" to "Fuji_FLog2_to_Rec709.cube", "C-Log3" to "CanonLog3_BT709.cube",
            "Apple Log 2" to "AppleLog2_to_Rec709.cube", "GP-Log" to "GoPro_GP_Log.cube")) {
            assertEquals(profile,CameraLutGuide.detectProfile(name))
            assertTrue(CameraLutGuide(null,profile).score(name)>0)
        }
        assertEquals(0,CameraLutGuide("DJI Osmo Action 6","D-Log M").score("DJI Osmo Action 6 D-Log.cube"))
    }

    private fun int(value:Int)=ByteBuffer.allocate(4).putInt(value).array()
    private fun box(name:String,data:ByteArray)=box(name.toByteArray(),data)
    private fun box(name:ByteArray,data:ByteArray)=int(data.size+8)+name+data
}
