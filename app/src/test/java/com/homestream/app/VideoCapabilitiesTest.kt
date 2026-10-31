package com.homestream.app

import org.junit.Assert.*
import org.junit.Test

class VideoCapabilitiesTest {
    @Test fun advertisesOnlyReportedDecodersAndDeduplicatesAliases() {
        assertEquals("h264,hevc,av1", videoCodecsForMimeTypes(listOf("video/avc", "video/hevc", "video/av01", "VIDEO/HEVC", "video/unknown")))
        assertEquals("h264", videoCodecsForMimeTypes(emptyList()))
        assertEquals("vp9", videoCodecsForMimeTypes(listOf("video/x-vnd.on2.vp9")))
    }
}
