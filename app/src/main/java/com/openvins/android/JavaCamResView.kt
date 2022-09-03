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
//        if (params.supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_FIXED))
//            params.focusMode = Camera.Parameters.FOCUS_MODE_FIXED
//        else if (params.supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_INFINITY))
//            params.focusMode = Camera.Parameters.FOCUS_MODE_INFINITY

        // Limit the exposure
        params.setAutoExposureLock(false)
        params.exposureCompensation = -10
        //params.autoExposureLock = true
        //params.focusMode = Camera.Parameters.FOCUS_MODE_FIXED
        params.focusMode = Camera.Parameters.FOCUS_MODE_AUTO

        // Finally write our parameters
        mCamera.setParameters(params)
        return true
    }

}