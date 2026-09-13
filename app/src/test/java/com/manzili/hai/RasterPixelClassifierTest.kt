package com.manzili.hai

import com.manzili.hai.engine.RasterPixelClassifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RasterPixelClassifierTest {
    @Test
    fun mediumArchitecturalBlueIsRecognizedEvenWhenNotDarkEnough() {
        val wallBlue = 0xFF5068A0.toInt()

        assertTrue(RasterPixelClassifier.isBlueprintBlue(wallBlue))
        assertFalse(RasterPixelClassifier.isDarkInk(wallBlue))
    }

    @Test
    fun labelsAndUiAccentColorsAreNotPromotedToBlueprintWalls() {
        val redLabel = 0xFF9A5C57.toInt()
        val greenDimension = 0xFF5C9A72.toInt()
        val cyanUi = 0xFF4FD8D0.toInt()
        val lightGray = 0xFFB8B8B0.toInt()

        assertFalse(RasterPixelClassifier.isBlueprintBlue(redLabel))
        assertFalse(RasterPixelClassifier.isBlueprintBlue(greenDimension))
        assertFalse(RasterPixelClassifier.isBlueprintBlue(cyanUi))
        assertFalse(RasterPixelClassifier.isBlueprintBlue(lightGray))
    }

    @Test
    fun traditionalBlackWallInkStillUsesDarkChannel() {
        val black = 0xFF181818.toInt()
        assertTrue(RasterPixelClassifier.isDarkInk(black))
    }
}
