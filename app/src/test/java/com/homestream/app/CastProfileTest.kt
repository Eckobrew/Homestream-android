package com.homestream.app

import org.junit.Assert.*
import org.junit.Test

class CastProfileTest {
    @Test fun googleTvHdAllowsHevcButCapsResolutionAt1080p() {
        val profile = detectedCastProfile("Chromecast with Google TV (HD)")!!
        assertTrue(profile.hevc)
        assertEquals(1920, profile.width)
        assertEquals(1080, profile.height)
    }
    @Test fun genericNamesAreNotMistakenForAnExactHardwareModel() {
        assertNull(detectedCastProfile("Chromecast"))
        assertNull(detectedCastProfile("Chromecast with Google TV"))
        assertEquals(2160, detectedCastProfile("Chromecast with Google TV (4K)")!!.height)
        assertFalse(castProfiles.first { it.key == "legacy" }.hevc)
    }
    @Test fun unknownReceiversAreProbedAndCanFallBackAutomatically() {
        assertEquals("auto-probe", automaticCastProfile("Chromecast", false).key)
        assertFalse(automaticCastProfile("Chromecast", false).hevc)
        assertFalse(automaticCastProfile("Chromecast", true).hevc)
        assertEquals(1080, automaticCastProfile("Chromecast", true).height)
    }
}
