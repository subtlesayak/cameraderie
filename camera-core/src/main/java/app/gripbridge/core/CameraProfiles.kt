package app.gripbridge.core

/** Model identity is the authorization ID, never the legacy product/version text. */
data class CameraProfile(val id: Int, val name: String, val controlsEnabled: Boolean, val videoMode: Int, val photoMode: Int, val modeAckAliases: Set<Int> = emptySet())
object CameraProfiles {
    val all: List<CameraProfile> = listOf(
        CameraProfile(0xFF33,"Osmo Action 4",false,1,5),
        CameraProfile(0xFF44,"Osmo Action 5 Pro",false,1,5),
        CameraProfile(0xFF55,"Osmo Action 6",true,1,5,setOf(3)),
        CameraProfile(0xFF66,"Osmo 360",false,0x38,0x3F),
    )
    fun find(deviceId: Long): CameraProfile? = all.firstOrNull { it.id == (deviceId.toInt() and 65535) }
}
