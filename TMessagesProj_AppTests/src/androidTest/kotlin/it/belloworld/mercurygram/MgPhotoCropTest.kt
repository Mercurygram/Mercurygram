package it.belloworld.mercurygram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.telegram.messenger.MediaController

class MgPhotoCropTest {

    private fun cropState(pw: Float, ph: Float, rotation: Int = 0) = MediaController.CropState().apply {
        cropPw = pw
        cropPh = ph
        transformRotation = rotation
    }

    private val noMemoryLimit = Long.MAX_VALUE

    @Test
    fun samplesDownOnlyAsFarAsTheCropAllows() {
        // 8000x6000 cropped to a quarter of each side -> 2000x1500 of source pixels for a
        // 2560 target, so the source must be decoded at full resolution
        assertEquals(1, MgPhotoCrop.computeSampleSize(8000, 6000, cropState(0.25f, 0.25f), 0, 2560, noMemoryLimit))
        // same source, uncropped: 8000 / 2 = 4000 still above 2560, 8000 / 4 = 2000 is not
        assertEquals(2, MgPhotoCrop.computeSampleSize(8000, 6000, cropState(1f, 1f), 0, 2560, noMemoryLimit))
    }

    @Test
    fun countsTheLongSideAfterRotation() {
        // the crop percentages apply to the rotated frame, so a quarter turn moves them
        // onto the other axis: 8000x2000 keeping the full width and half the height gives
        // an 8000 px long side upright, but only 4000 px once rotated
        assertEquals(4, MgPhotoCrop.computeSampleSize(8000, 2000, cropState(1f, 0.5f), 0, 1280, noMemoryLimit))
        assertEquals(2, MgPhotoCrop.computeSampleSize(8000, 2000, cropState(1f, 0.5f), 90, 1280, noMemoryLimit))
    }

    @Test
    fun raisesTheSampleSizeUntilTheDecodeFitsInBudget() {
        val budget = 32L * 1024 * 1024
        val sample = MgPhotoCrop.computeSampleSize(12000, 9000, cropState(0.25f, 0.25f), 0, 2560, budget)
        assertTrue("sample must stay a power of two", sample > 0 && (sample and (sample - 1)) == 0)
        assertEquals(1, MgPhotoCrop.computeSampleSize(12000, 9000, cropState(0.25f, 0.25f), 0, 2560, noMemoryLimit))
        assertTrue("budget must force a coarser decode", sample > 1)
        assertTrue(
            "source and cropped copy together must fit the budget",
            MgPhotoCrop.decodedBytes(12000, 9000, 3000f, 2250f, sample) <= budget
        )
    }

    @Test
    fun refusesADegenerateCrop() {
        assertEquals(0, MgPhotoCrop.computeSampleSize(4000, 3000, cropState(0f, 0f), 0, 1280, noMemoryLimit))
    }
}
