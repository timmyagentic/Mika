package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayOrientationTest {
    @Test fun landscapeLetterboxTurnsPortraitDisplay() {
        assertEquals(1, DisplayOrientation.rotation(720, 1600, 0, 720, 324))
    }
    @Test fun portraitLetterboxTurnsItBack() {
        assertEquals(0, DisplayOrientation.rotation(720, 1600, 1, 324, 720))
    }
    @Test fun alreadyMatchingAxesLeaveSystemRotationAlone() {
        for (rotation in listOf(1, 3)) assertNull(DisplayOrientation.rotation(720, 1600, rotation, 1600, 720))
        for (rotation in listOf(0, 2)) assertNull(DisplayOrientation.rotation(720, 1600, rotation, 720, 1600))
    }
    @Test fun naturalLandscapeAndInvalidWindowsAreHandled() {
        assertEquals(1, DisplayOrientation.rotation(1600, 720, 0, 324, 720))
        assertNull(DisplayOrientation.rotation(720, 1600, 0, -1, -1))
        assertNull(DisplayOrientation.rotation(720, 1600, 0, 500, 500))
    }
}
