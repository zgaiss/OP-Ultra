package com.shilapi.xcertplay

/**
 * AMap calibration: the captured 664-square secondary frame places the vehicle and
 * current-road label at x ~= 0.75. On the 1328x664 canvas the phone relayout moves
 * that anchor to x ~= 876 (measured from a live frame). Take a 664-square centered
 * there, keeping vertical field and pixel density unchanged.
 * This is not an assumption about other navigation applications.
 */
internal object P7InstrumentFraming {
    data class Crop(val left: Int, val top: Int, val width: Int, val height: Int) {
        fun writeUvMatrix(sourceWidth: Int, sourceHeight: Int, out: FloatArray) {
            require(sourceWidth > 0 && sourceHeight > 0 && out.size >= 16)
            out.fill(0f)
            out[0] = width.toFloat() / sourceWidth
            out[5] = height.toFloat() / sourceHeight
            out[10] = 1f
            out[12] = left.toFloat() / sourceWidth
            out[13] = top.toFloat() / sourceHeight
            out[15] = 1f
        }
    }

    fun crop(sourceWidth: Int, sourceHeight: Int, amapNavigating: Boolean = false): Crop {
        require(sourceWidth > 0 && sourceHeight > 0)
        // Never crop a square fallback or an unexpected decoder geometry.
        return if (sourceWidth == 1328 && sourceHeight == 664) {
            Crop(if (amapNavigating) 544 else 332, 0, 664, 664)
        } else {
            Crop(0, 0, sourceWidth, sourceHeight)
        }
    }
    /** Wide Ultra uses its own phone layout. Real 1920×720 AMap frames place the
     * navigation anchor about115px left of the center. Translate sampling only;
     * retain the full vertical view and apply only the existing AMap-active rule.
     * OES CLAMP_TO_EDGE fills the newly exposed strip; other geometries stay raw. */
    fun ultraCrop(sourceWidth: Int, sourceHeight: Int, amapNavigating: Boolean): Crop {
        require(sourceWidth > 0 && sourceHeight > 0)
        return if (amapNavigating && sourceWidth == 1920 && sourceHeight == 720)
            // 2026-10-27 live 1920x720: road-plate center964.5 versus center959.5
            // with the previous -120 sampling origin: move the image left5px.
            Crop(-115, 0, sourceWidth, sourceHeight)
        else Crop(0, 0, sourceWidth, sourceHeight)
    }

}
