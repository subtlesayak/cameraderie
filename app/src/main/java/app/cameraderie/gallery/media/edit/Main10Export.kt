package app.cameraderie.gallery.media.edit

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.metrics.LogSessionId
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import androidx.media3.transformer.Codec
import androidx.media3.transformer.DefaultCodec
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.EncoderUtil
import java.io.File
import java.io.IOException

internal enum class VideoOutput(val label: String, val videoMime: String) {
    AVC("H.264 · 8-bit", MimeTypes.VIDEO_H264),
    MAIN10("HEVC · 10-bit Rec.709", MimeTypes.VIDEO_H265)
}

@OptIn(UnstableApi::class)
internal object Main10Export {
    private const val MIME = MimeTypes.VIDEO_H265
    private const val PROFILE = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
    private const val COLOR_FORMAT = MediaCodecInfo.CodecCapabilities.COLOR_Format32bitABGR2101010
    private const val TAG = "CameraderieMain10"

    private data class Encoder(
        val name: String,
        val caps: MediaCodecInfo.VideoCapabilities,
        val level: Int
    )

    private fun encoders() = EncoderUtil.getSupportedEncoders(MIME).mapNotNull { encoder ->
        val caps = encoder.getCapabilitiesForType(MIME)
        val video = caps.videoCapabilities ?: return@mapNotNull null
        val level = caps.profileLevels.filter { it.profile == PROFILE }.maxOfOrNull { it.level }
        if (level == null || COLOR_FORMAT !in caps.colorFormats) null
        else Encoder(encoder.name, video, level)
    }

    fun available(): Boolean = encoders().isNotEmpty()

    fun encoderFactory(context: Context): Codec.EncoderFactory {
        val defaults = DefaultEncoderFactory.Builder(context).build()
        val candidates = encoders()
        return object : Codec.EncoderFactory by defaults {
            override fun videoNeedsEncoding() = true

            override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec {
                val frameRate = format.frameRate.takeIf { it > 0f } ?: 30f
                val selected = candidates.firstOrNull {
                    it.caps.areSizeAndRateSupported(format.width, format.height, frameRate.toDouble())
                } ?: throw ExportException.createForAssetLoader(
                    IllegalArgumentException("No 10-bit encoder supports these dimensions and frame rate."),
                    ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED
                )
                val bitrate = selected.caps.bitrateRange.clamp(
                    format.averageBitrate.takeIf { it > 0 }
                        ?: (format.width.toDouble() * format.height * frameRate * 0.14).toInt()
                )
                val configuration = format.buildUpon().setAverageBitrate(bitrate).setFrameRate(frameRate).build()
                val media = MediaFormatUtil.createMediaFormatFromFormat(configuration).apply {
                    setInteger(MediaFormat.KEY_PROFILE, PROFILE)
                    setInteger(MediaFormat.KEY_LEVEL, selected.level)
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FORMAT)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                    setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 1f)
                }
                return DefaultCodec(context, configuration, media, selected.name, false, null)
            }
        }
    }

    /** A configured profile alone cannot prove what the hardware actually wrote. */
    fun verify(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).asSequence().map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME) == MIME }
            if (track == null || !isMain10Rec709(track)) {
                throw IOException("The encoder didn’t produce 10-bit Rec.709. Try H.264 or a different crop.")
            }
        } finally {
            extractor.release()
        }
    }

    private fun isMain10Rec709(track: MediaFormat): Boolean {
        val sps = (0..2).asSequence()
            .mapNotNull { track.getByteBuffer("csd-$it") }
            .map { buffer -> ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) } }
            .firstNotNullOfOrNull(::readSps) ?: return false
        val formatIsRec709 = track.getInteger(MediaFormat.KEY_COLOR_STANDARD, 0) == MediaFormat.COLOR_STANDARD_BT709 &&
            track.getInteger(MediaFormat.KEY_COLOR_TRANSFER, 0) == MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        val spsIsRec709 = sps.colorSpace == C.COLOR_SPACE_BT709 && sps.colorTransfer == C.COLOR_TRANSFER_SDR
        Log.d(TAG, "Encoded HEVC profile=${sps.profileTierLevel?.generalProfileIdc}, " +
            "luma=${sps.bitDepthLumaMinus8 + 8}, chroma=${sps.bitDepthChromaMinus8 + 8}, " +
            "Rec.709 container=$formatIsRec709, bitstream=$spsIsRec709")
        return sps.profileTierLevel?.generalProfileIdc == 2 &&
            sps.bitDepthLumaMinus8 == 2 && sps.bitDepthChromaMinus8 == 2 &&
            (formatIsRec709 || spsIsRec709)
    }

    private fun readSps(bytes: ByteArray): NalUnitUtil.H265SpsData? {
        var startCode = NalUnitUtil.findNalUnit(bytes, 0, bytes.size, BooleanArray(3))
        while (startCode + 4 < bytes.size) {
            val next = NalUnitUtil.findNalUnit(bytes, startCode + 3, bytes.size, BooleanArray(3))
            if (NalUnitUtil.getH265NalUnitType(bytes, startCode) == NalUnitUtil.H265_NAL_UNIT_TYPE_SPS)
                return NalUnitUtil.parseH265SpsNalUnit(bytes, startCode + 3, next, null)
            startCode = next
        }
        return null
    }
}
