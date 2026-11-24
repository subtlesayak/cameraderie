package app.cameraderie.telemetry

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import app.cameraderie.gallery.media.edit.VerifiedVideo
import app.cameraderie.ui.CameraderieActivity
import app.cameraderie.ui.UiKit
import java.time.Instant
import java.util.concurrent.Executors

class TelemetryActivity : CameraderieActivity() {
    private lateinit var model: TelemetryModel
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::attach)
    }
    private val saveAlignment = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { model.export(it, true) }
    }
    private val saveGpx = registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        uri?.let { model.export(it, false) }
    }
    private val saveCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { model.export(it, false) }
    }
    private val saveJson = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { model.export(it, false) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hash = intent.getStringExtra(HASH)
        val duration = intent.getLongExtra(DURATION, 0)
        if (hash == null || duration <= 0) { finish(); return }
        val playhead = intent.getLongExtra(PLAYHEAD, 0).coerceIn(0, duration)
        val detected = intent.getStringExtra(CAPTURE)?.let(Instant::parse)
        model = ViewModelProvider(this)[TelemetryModel::class.java]
        model.open(hash, detected)
        val ui = UiKit(this)
        val (scroll, body) = ui.page()
        scroll.setBackgroundColor(ui.tone.background)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        ui.add(body, ui.text("Ride telemetry", UiKit.Type.title, bold = true).apply { isAccessibilityHeading = true }, 20)
        ui.add(body, ui.text(intent.getStringExtra(NAME).orEmpty(), secondary = true), 4)
        val import = ui.action("Choose GPX, CSV or JSON", primary = true) { picker.launch(arrayOf("*/*")) }
        ui.add(body, import, 12)
        val alignment = ui.card(body, "Alignment")
        val track = ui.text("", secondary = true)
        ui.add(alignment, track)
        val captureTime = EditText(this).apply {
            id = app.cameraderie.R.id.capture_time_input
            isSingleLine = true
            hint = "Capture time in ISO 8601"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        ui.add(alignment, ui.text("Clip start time (UTC)").apply { labelFor = captureTime.id }, 12)
        ui.add(alignment, captureTime)
        val offset = EditText(this).apply {
            id = app.cameraderie.R.id.telemetry_offset_input
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        ui.add(alignment, ui.text("Ride offset in seconds").apply { labelFor = offset.id }, 12)
        ui.add(alignment, offset)
        ui.add(alignment, ui.text("A positive offset moves the ride samples later on the clip.", UiKit.Type.small, secondary = true), 4)
        val save = ui.action("Save alignment") { model.adjust(captureTime.text.toString(), offset.text.toString()) }
        ui.add(alignment, save, 12)
        val markers = ui.text("", secondary = true)
        ui.add(alignment, markers, 12)
        val mark = ui.action("Mark playhead at %,.3f s".format(playhead / 1000.0)) { model.mark(playhead) }
        ui.add(alignment, mark, 8)
        val clear = ui.action("Clear highlight markers") { model.clearMarkers() }
        ui.add(alignment, clear, 8)
        val original = ui.action("Save ride file") {
            val ride = model.state.value?.ride ?: return@action
            when (ride.format) {
                RideFormat.GPX -> saveGpx.launch(ride.name)
                RideFormat.CSV -> saveCsv.launch(ride.name)
                RideFormat.JSON -> saveJson.launch(ride.name)
            }
        }
        val export = ui.action("Save alignment JSON") { saveAlignment.launch("Cameraderie-alignment.json") }
        ui.add(alignment, original, 12)
        ui.add(alignment, export, 8)
        val status = ui.text("", secondary = true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        ui.add(body, status, 12)
        setContentView(scroll)
        var displayed: Triple<String, Instant?, Long>? = null
        model.state.observe(this) { state ->
            import.isEnabled = !state.busy
            status.text = state.message
            val ride = state.ride
            alignment.visibility = if (ride == null) View.GONE else View.VISIBLE
            if (ride != null) {
                val identity = Triple(ride.hash, ride.capture, ride.offsetMs)
                if (displayed != identity) {
                    captureTime.setText(ride.capture?.toString().orEmpty())
                    offset.setText(java.math.BigDecimal.valueOf(ride.offsetMs, 3).stripTrailingZeros().toPlainString())
                    displayed = identity
                }
                val overlap = ride.capture?.let { ride.timeline.overlap(it, duration, ride.offsetMs) }
                val match = when {
                    ride.capture == null -> "Enter a clip start time to compare timestamps."
                    overlap == null -> "These timestamps do not overlap. Check the capture time and offset."
                    else -> "Telemetry overlaps the clip from %,.3f to %,.3f s.".format(overlap.start / 1000.0, overlap.endInclusive / 1000.0)
                }
                track.text = "${ride.name} · ${ride.timeline.points} timed samples\n${ride.timeline.first} to ${ride.timeline.last}\n$match"
                markers.text = if (ride.markers.isEmpty()) "No highlight markers" else ride.markers.joinToString(prefix = "Highlights · ") { "%,.3f s".format(it / 1000.0) }
                listOf(save, mark, original, export).forEach { it.isEnabled = !state.busy }
                clear.isEnabled = !state.busy && ride.markers.isNotEmpty()
            }
        }
    }

    companion object {
        private const val HASH = "original_hash"
        private const val NAME = "original_name"
        private const val DURATION = "duration_ms"
        private const val PLAYHEAD = "playhead_ms"
        private const val CAPTURE = "capture_start"
        internal fun intent(context: Context, source: VerifiedVideo, playhead: Long) = Intent(context, TelemetryActivity::class.java)
            .putExtra(HASH, source.entry.receipt?.sha256).putExtra(NAME, source.name)
            .putExtra(DURATION, source.durationMs).putExtra(PLAYHEAD, playhead)
            .putExtra(CAPTURE, source.captureStart?.toString())
    }
}

internal class TelemetryModel(application: Application) : AndroidViewModel(application) {
    data class State(val ride: RideStore.Ride? = null, val message: String = "", val busy: Boolean = false)
    val state = MutableLiveData(State())
    private val worker = Executors.newSingleThreadExecutor()
    private var store: RideStore? = null
    private var capture: Instant? = null

    fun open(hash: String, detected: Instant?) {
        if (store != null) return
        capture = detected
        store = RideStore(getApplication(), hash)
        run("Loading ride telemetry…") { saved -> State(saved.get()) }
    }

    fun attach(uri: Uri) = run("Reading timestamps…") { saved ->
        State(saved.attach(uri, capture), "Ride file saved with this video.")
    }

    fun adjust(instant: String, seconds: String) {
        val time = if (instant.isBlank()) null else TelemetryTrack.instant(instant)
        val offset = runCatching { seconds.toBigDecimal().movePointRight(3).longValueExact() }.getOrNull()
        if ((instant.isNotBlank() && time == null) || offset == null || offset !in -604_800_000..604_800_000) {
            state.value = state.value?.copy(message = "Enter an ISO 8601 capture time and an offset within seven days, with up to three decimal places.")
            return
        }
        update("Alignment saved.") { it.copy(capture = time, offsetMs = offset) }
    }

    fun mark(playhead: Long) = update("Highlight saved.") { ride ->
        val markers = (ride.markers + playhead).distinct().sorted()
        check(markers.size <= 100) { "Save up to 100 highlight markers." }
        ride.copy(markers = markers)
    }

    fun clearMarkers() = update("Highlights cleared.") { it.copy(markers = emptyList()) }

    private fun update(message: String, change: (RideStore.Ride) -> RideStore.Ride) {
        val ride = state.value?.ride ?: return
        run("Saving alignment…") { saved ->
            val next = change(ride)
            saved.save(next)
            State(next, message)
        }
    }

    fun export(uri: Uri, alignment: Boolean) {
        val ride = state.value?.ride ?: return
        run("Saving file…") { saved ->
            saved.export(ride, uri, alignment)
            State(ride, "File saved.")
        }
    }

    private fun run(message: String, action: (RideStore) -> State) {
        val saved = store ?: return
        val previous = state.value ?: return
        if (previous.busy) return
        state.value = previous.copy(message = message, busy = true)
        worker.execute {
            val result = runCatching { action(saved) }.getOrElse {
                previous.copy(message = "Couldn’t complete the ride action. Check file access and timestamp format.", busy = false)
            }
            state.postValue(result)
        }
    }

    override fun onCleared() { worker.shutdownNow() }
}
