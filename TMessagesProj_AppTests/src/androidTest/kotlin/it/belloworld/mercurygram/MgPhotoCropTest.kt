package it.belloworld.mercurygram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.telegram.messenger.MediaController

class MgPhotoCropTest {

    private fun cropState(pw: Float, ph: Float, rotation: Int = 0, scale: Float = 1f) = MediaController.CropState().apply {
        cropPw = pw
        cropPh = ph
        cropScale = scale
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
        // onto the other axis: 8000x4000 keeping a quarter of the width and half the
        // height gives a 2000 px long side upright, and 4000 px once rotated
        assertEquals(4, MgPhotoCrop.computeSampleSize(8000, 4000, cropState(0.25f, 0.5f), 0, 500, noMemoryLimit))
        assertEquals(8, MgPhotoCrop.computeSampleSize(8000, 4000, cropState(0.25f, 0.5f), 90, 500, noMemoryLimit))
    }

    @Test
    fun neverSamplesCoarserThanTheUpstreamPath() {
        // upstream scales the source to cover the target box, so its short side lands on
        // the target: 8000x2000 for a 1280 target is already at that floor and must be
        // decoded whole, however little of it the crop keeps
        assertEquals(1, MgPhotoCrop.computeSampleSize(8000, 2000, cropState(1f, 0.5f), 0, 1280, noMemoryLimit))
        assertEquals(1, MgPhotoCrop.computeSampleSize(8000, 2000, cropState(1f, 0.5f), 0, 1280, 128L * 1024 * 1024))
    }

    @Test
    fun countsTheMagnificationOfAZoomedCrop() {
        // half the frame at 2x zoom is fed by a quarter of the source pixels, so the
        // decode has to stay one step finer than the crop percentages alone suggest
        assertEquals(4, MgPhotoCrop.computeSampleSize(16000, 16000, cropState(0.5f, 0.5f), 0, 1280, noMemoryLimit))
        assertEquals(2, MgPhotoCrop.computeSampleSize(16000, 16000, cropState(0.5f, 0.5f, scale = 2f), 0, 1280, noMemoryLimit))
    }

    @Test
    fun fallsBackWhenTheDecodeDoesNotFit() {
        // 4000x3000 cropped in half needs the whole source to beat upstream, which is
        // 60 MB with the cropped copy; when that does not fit there is no coarser decode
        // left that still beats upstream, so the caller takes the upstream path instead
        // of getting a worse crop
        assertEquals(1, MgPhotoCrop.computeSampleSize(4000, 3000, cropState(0.5f, 0.5f), 0, 2560, noMemoryLimit))
        assertEquals(0, MgPhotoCrop.computeSampleSize(4000, 3000, cropState(0.5f, 0.5f), 0, 2560, 32L * 1024 * 1024))
        assertTrue(
            "the accepted decode must fit the budget it was accepted under",
            MgPhotoCrop.decodedBytes(4000, 3000, 2000f, 1500f, 1) <= 64L * 1024 * 1024
        )
    }

    @Test
    fun refusesADegenerateCrop() {
        assertEquals(0, MgPhotoCrop.computeSampleSize(4000, 3000, cropState(0f, 0f), 0, 1280, noMemoryLimit))
    }
}
