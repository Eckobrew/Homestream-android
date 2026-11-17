package com.homestream.app

import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceVideoProfilesTest {
    @Before fun reset() { ShadowMediaCodecList.reset() }

    private fun decoder(name: String, mime: String, profile: Int, level: Int, hardware: Boolean) {
        val capabilities = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat.createVideoFormat(mime, 3840, 2160))
            .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible))
            .setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply {
                this.profile = profile; this.level = level
            })).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName(name)
            .setIsEncoder(false).setIsHardwareAccelerated(hardware).setIsSoftwareOnly(!hardware)
            .setCapabilities(capabilities).build())
    }

    @Test fun softwareAv1IsNotAdvertisedAsHardwareDecodeSupport() {
        decoder("c2.android.av1.decoder", "video/av01", MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8,
            MediaCodecInfo.CodecProfileLevel.AV1Level53, false)
        val profiles = deviceVideoProfiles()
        assertFalse((0 until profiles.length()).any { profiles.getJSONObject(it).getString("codec") == "av1" })
    }

    @Test fun googleNamedHardwareAv1IsReportedFromApiFlags() {
        decoder("c2.google.av1.decoder", "video/av01", MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8,
            MediaCodecInfo.CodecProfileLevel.AV1Level53, true)
        val profiles = deviceVideoProfiles()
        val av1 = (0 until profiles.length()).map { profiles.getJSONObject(it) }.first { it.getString("codec") == "av1" }
        assertEquals(3840, av1.getInt("width"))
        assertEquals(2160, av1.getInt("height"))
    }

    @Test fun softwareHigh10DoesNotExpandHardwareH264Profiles() {
        decoder("c2.vendor.avc.decoder", "video/avc", MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
            MediaCodecInfo.CodecProfileLevel.AVCLevel51, true)
        decoder("c2.android.avc.decoder", "video/avc", MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10,
            MediaCodecInfo.CodecProfileLevel.AVCLevel51, false)
        val profiles = deviceVideoProfiles()
        val h264 = (0 until profiles.length()).map { profiles.getJSONObject(it) }.first { it.getString("codec") == "h264" }
        assertEquals("[\"high\"]", h264.getJSONArray("profiles").toString())
    }
}
