package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class P7InstrumentFramingTest {
    @Test fun vehicleAndRoadNameAnchorMovesToCenterWithoutBlankPixels() {
        val crop = P7InstrumentFraming.crop(1328, 664, true)
        assertEquals(0.5, (876.0 - crop.left) / crop.width, 0.00001)
        assertTrue(crop.left >= 0 && crop.left + crop.width <= 1328)
        assertEquals(0, crop.top)
        assertEquals(664, crop.height)
        assertEquals(crop.width, crop.height)
    }

    @Test fun uvMappingCoversMeasuredAnchorAndPreservesVerticalField() {
        val matrix = FloatArray(16)
        P7InstrumentFraming.crop(1328, 664, true).writeUvMatrix(1328, 664, matrix)
        assertEquals(544f / 1328, matrix[12], 0.00001f)
        assertEquals(876f / 1328, matrix[0] * 0.5f + matrix[12], 0.00001f)
        assertEquals(1208f / 1328, matrix[0] + matrix[12], 0.00001f)
        assertEquals(0f, matrix[13], 0.00001f)
        assertEquals(1f, matrix[5] + matrix[13], 0.00001f)
    }

    @Test fun unexpectedGeometryUsesWholeFrame() {
        for ((w,h) in listOf(664 to 664, 660 to 660, 1600 to 600, 664 to 1328)) {
            for (active in listOf(false,true)) {
            val crop = P7InstrumentFraming.crop(w,h,active)
            assertEquals(P7InstrumentFraming.Crop(0,0,w,h), crop)
            val matrix = FloatArray(16)
            crop.writeUvMatrix(w,h,matrix)
            assertEquals(1f, matrix[0], 0f)
            assertEquals(1f, matrix[5], 0f)
            assertEquals(0f, matrix[12], 0f)
            }
        }
    }

    @Test fun defaultAndInactiveStatesHaveZeroOffsetAndTheSameCropSize() {
        val centered=P7InstrumentFraming.crop(1328,664)
        val amap=P7InstrumentFraming.crop(1328,664,true)
        assertEquals(P7InstrumentFraming.Crop(332,0,664,664),centered)
        assertEquals(centered.width,amap.width);assertEquals(centered.height,amap.height)
        assertEquals(0.5,(664.0-centered.left)/centered.width,0.00001)
    }
    @Test fun ultraWideAppliesOnlyActiveAmapCorrectionWithoutZoomOrVerticalCrop() {
        assertEquals(P7InstrumentFraming.Crop(-115,0,1920,720),P7InstrumentFraming.ultraCrop(1920,720,true))
        assertEquals(P7InstrumentFraming.Crop(0,0,1920,720),P7InstrumentFraming.ultraCrop(1920,720,false))
        assertEquals(P7InstrumentFraming.Crop(0,0,1328,664),P7InstrumentFraming.ultraCrop(1328,664,true))
        assertEquals(P7InstrumentFraming.Crop(0,0,664,664),P7InstrumentFraming.ultraCrop(664,664,true))
        val matrix=FloatArray(16);P7InstrumentFraming.ultraCrop(1920,720,true).writeUvMatrix(1920,720,matrix)
        assertEquals(-115f/1920,matrix[12],0.00001f);assertEquals(1f,matrix[0],0.00001f);assertEquals(1f,matrix[5],0.00001f)
    }

}
