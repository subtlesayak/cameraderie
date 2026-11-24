package app.cameraderie.core

/** Local camera shortcut, separate from protocol authorization and OS bonding. */
data class CameraBookmark(val address: String, val name: String) {
    companion object {
        fun create(address: String?, name: String?): CameraBookmark? {
            if (address == null || !Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}").matches(address)) return null
            val label = name.orEmpty().filter { !it.isISOControl() }.trim().take(64).ifBlank { "Saved camera" }
            return CameraBookmark(address.uppercase(java.util.Locale.ROOT),label)
        }
    }
}
interface CameraBookmarkStore {
    fun read(): CameraBookmark?
    fun migrationDone(): Boolean
    fun authorizedAddresses(): List<String>
    fun save(camera: CameraBookmark?, migrated: Boolean = true): Boolean
}
class RememberedCamera(private val store: CameraBookmarkStore) {
    init {
        if (!store.migrationDone()) {
            val existing = valid(store.read())
            val history = store.authorizedAddresses().mapNotNull { CameraBookmark.create(it,"Saved camera") }.distinctBy { it.address }
            // Never pick between multiple previously authorized cameras on the user's behalf.
            store.save(existing ?: history.singleOrNull())
        }
    }
    private fun valid(value: CameraBookmark?) = value?.let { CameraBookmark.create(it.address,it.name) }
    val current: CameraBookmark? get() = valid(store.read())
    fun rememberAuthorized(address: String, name: String): Boolean {
        val camera = CameraBookmark.create(address,name) ?: return false
        return store.save(camera)
    }
    /** Removing a shortcut must not delete Bluetooth bonds or cause migration to resurrect it. */
    fun forget(): Boolean = store.save(null)
}
