package com.openvins.android

import android.content.Context
import android.hardware.Camera
import android.util.AttributeSet
import android.util.Log
import org.opencv.android.JavaCameraView


class JavaCamResView(context: Context?, attrs: AttributeSet?) : JavaCameraView(context, attrs) {

    override fun initializeCamera(width: Int, height: Int): Boolean {

        // Set the max frame size
        super.setMaxFrameSize(640, 480)

        // Initialize our camera
        if (!super.initializeCamera(width, height)) {
            return false
        }

        // Set the focus mode to either infinity or fixed
        val params: Camera.Parameters = mCamera.getParameters()
        
        // Try to set focus to infinity first, then fixed as fallback
        if (params.supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_INFINITY)) {
            params.focusMode = Camera.Parameters.FOCUS_MODE_INFINITY
            Log.d("JavaCamResView", "Focus mode set to INFINITY")
        } else if (params.supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_FIXED)) {
            params.focusMode = Camera.Parameters.FOCUS_MODE_FIXED
            Log.d("JavaCamResView", "Focus mode set to FIXED")
        } else {
            Log.w("JavaCamResView", "Neither INFINITY nor FIXED focus modes are supported. Available modes: ${params.supportedFocusModes}")
            // Keep auto if neither is available (though this shouldn't happen on most cameras)
        }

        // Limit the exposure
        params.setAutoExposureLock(false)
        params.exposureCompensation = -10

        // Finally write our parameters
        mCamera.setParameters(params)
        return true
    }

}