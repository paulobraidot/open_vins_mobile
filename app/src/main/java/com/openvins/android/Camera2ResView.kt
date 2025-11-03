package com.openvins.android

import android.annotation.TargetApi
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.util.Log
import android.util.Size
import android.graphics.Bitmap
import android.view.SurfaceHolder
import android.view.SurfaceView
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

interface CameraFrameListener {
    fun onFrame(matAddr: Long, timestampSec: Double)
}

@TargetApi(21)
class Camera2ResView(context: Context?, attrs: AttributeSet?) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val TAG = "Camera2ResView"

    // Store the last frame timestamp (synchronized access)
    @Volatile
    private var lastFrameTimestampSec: Double = 0.0

    // Getter for last frame timestamp
    fun getLastFrameTimestamp(): Double = lastFrameTimestampSec

    private var mImageReader: ImageReader? = null
    private var mCameraDevice: CameraDevice? = null
    private var mCaptureSession: CameraCaptureSession? = null
    private var mPreviewRequestBuilder: CaptureRequest.Builder? = null
    private var mCameraID: String? = null
    private var mPreviewSize: Size = Size(-1, -1)
    private var mBackgroundThread: HandlerThread? = null
    private var mBackgroundHandler: Handler? = null
    private val mPreviewFormat = ImageFormat.YUV_420_888

    private var mFrameListener: CameraFrameListener? = null
    private var mEnabled = false
    private var mCameraPermissionGranted = false
    
    // Temporary display bitmap
    private var mDisplayBitmap: Bitmap? = null
    private val mDisplayLock = Object()

    init {
        holder.addCallback(this)
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS)
    }

    fun setCameraFrameListener(listener: CameraFrameListener?) {
        mFrameListener = listener
    }

    fun setCameraPermissionGranted() {
        mCameraPermissionGranted = true
        checkState()
    }

    fun enableView() {
        mEnabled = true
        checkState()
    }

    fun disableView() {
        mEnabled = false
        checkState()
    }

    private fun checkState() {
        if (mEnabled && mCameraPermissionGranted && holder.surface != null && visibility == VISIBLE) {
            if (mCameraDevice == null) {
                connectCamera()
            }
        } else {
            if (mCameraDevice != null) {
                disconnectCamera()
            }
        }
    }

    private fun startBackgroundThread() {
        stopBackgroundThread()
        mBackgroundThread = HandlerThread("CameraBackground")
        mBackgroundThread!!.start()
        mBackgroundHandler = Handler(mBackgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        if (mBackgroundThread != null) {
            mBackgroundThread!!.quitSafely()
            try {
                mBackgroundThread!!.join()
                mBackgroundThread = null
                mBackgroundHandler = null
            } catch (e: InterruptedException) {
                Log.e(TAG, "stopBackgroundThread", e)
            }
        }
    }

    private fun connectCamera() {
        Log.i(TAG, "connectCamera")
        startBackgroundThread()
        initializeCamera()
    }

    private fun disconnectCamera() {
        Log.i(TAG, "disconnectCamera")
        val handler = mBackgroundHandler
        
        // Close capture session first
        if (mCaptureSession != null) {
            try {
                mCaptureSession!!.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing capture session", e)
            }
            mCaptureSession = null
        }
        
        // Close ImageReader
        if (mImageReader != null) {
            try {
                mImageReader!!.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing ImageReader", e)
            }
            mImageReader = null
        }
        
        // Close camera device - close directly to avoid handler issues
        // The Camera2 framework will call callbacks, but we handle them synchronously
        val camera = mCameraDevice
        synchronized(this) {
            mCameraDevice = null
        }
        
        if (camera != null) {
            try {
                // Close directly - don't use handler to avoid dead thread issues
                // Any callbacks will be handled synchronously in the state callback
                camera.close()
                // Give a moment for any callbacks to process
                Thread.sleep(50)
            } catch (e: Exception) {
                Log.e(TAG, "Error closing camera", e)
            }
        }
        
        // Now safe to stop background thread
        stopBackgroundThread()
    }

    private fun initializeCamera() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val camList = manager.cameraIdList
            if (camList.isEmpty()) {
                Log.e(TAG, "Error: camera isn't detected.")
                return
            }
            // Use back camera by default
            mCameraID = null
            for (cameraID in camList) {
                val characteristics = manager.getCameraCharacteristics(cameraID)
                if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    mCameraID = cameraID
                    break
                }
            }
            if (mCameraID == null) {
                mCameraID = camList[0]
            }
            
            Log.i(TAG, "Opening camera: $mCameraID")
            manager.openCamera(mCameraID!!, mStateCallback, mBackgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "CameraAccessException", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException", e)
        }
    }

    private val mStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            val handler = mBackgroundHandler
            if (handler != null) {
                try {
                    handler.post {
                        if (mCameraDevice == null) { // Only if not already closed
                            mCameraDevice = camera
                            createCameraPreviewSession()
                        }
                    }
                } catch (e: IllegalStateException) {
                    // Handler thread is dead, handle synchronously
                    Log.w(TAG, "Handler thread dead, handling onOpened synchronously")
                    if (mCameraDevice == null) {
                        mCameraDevice = camera
                        createCameraPreviewSession()
                    }
                }
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "Camera disconnected")
            // Handle synchronously to avoid dead thread issues
            synchronized(this@Camera2ResView) {
                if (mCameraDevice == camera) {
                    mCameraDevice = null
                }
            }
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error: $error")
            // Handle synchronously to avoid dead thread issues
            // Don't try to close here as it may cause more callbacks
            synchronized(this@Camera2ResView) {
                if (mCameraDevice == camera) {
                    mCameraDevice = null
                }
            }
        }
    }

    private fun createCameraPreviewSession() {
        val width = width
        val height = height
        
        if (width <= 0 || height <= 0) {
            return
        }
        
        calcPreviewSize(width, height)
        
        val w = mPreviewSize.width
        val h = mPreviewSize.height
        
        Log.d(TAG, "createCameraPreviewSession($w x $h)")
        if (w < 0 || h < 0)
            return
        try {
            if (mCameraDevice == null) {
                Log.e(TAG, "createCameraPreviewSession: camera isn't opened")
                return
            }
            if (mCaptureSession != null) {
                Log.e(TAG, "createCameraPreviewSession: mCaptureSession is already started")
                return
            }

            mImageReader = ImageReader.newInstance(w, h, mPreviewFormat, 2)
            mImageReader!!.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image == null)
                    return@setOnImageAvailableListener

                // Get hardware timestamp from the Image (nanoseconds since boot)
                val frameTimestampNs = image.timestamp
                lastFrameTimestampSec = frameTimestampNs * 1e-9

                // Extract YUV plane data directly from Image
                val planes = image.planes
                val imgWidth = image.width
                val imgHeight = image.height
                
                val yPlane = planes[0].buffer
                val yStride = planes[0].rowStride
                val uPlane = planes[1].buffer
                val uStride = planes[1].rowStride
                val uPixelStride = planes[1].pixelStride
                val vPlane = if (planes.size > 2) planes[2].buffer else null
                val vStride = if (vPlane != null) planes[2].rowStride else 0
                
                // Extract raw byte data - use buffer's actual capacity
                yPlane.rewind()
                val ySize = yPlane.remaining()
                val yBytes = ByteArray(ySize)
                yPlane.get(yBytes)
                
                val uBytes = if (uPlane != null) {
                    uPlane.rewind()
                    val uSize = uPlane.remaining()
                    val bytes = ByteArray(uSize)
                    uPlane.get(bytes)
                    bytes
                } else null
                
                val vBytes = if (vPlane != null) {
                    vPlane.rewind()
                    val vSize = vPlane.remaining()
                    val bytes = ByteArray(vSize)
                    vPlane.get(bytes)
                    bytes
                } else null
                
                // Convert YUV to RGBA in native code - returns Mat address
                val rgbaMatAddr = processYUVToRGBAJNI(
                    yBytes, uBytes, vBytes,
                    imgWidth, imgHeight,
                    yStride, uStride, vStride,
                    uPixelStride
                )
                
                // Notify listener with Mat address and timestamp (for processing)
                mFrameListener?.onFrame(rgbaMatAddr, lastFrameTimestampSec)
                
                // Get display image (raw camera if not running, or viz with overlays if running)
                if (rgbaMatAddr != 0L) {
                    val displayMatAddr = getDisplayImageJNI(rgbaMatAddr)
                    if (displayMatAddr != 0L) {
                        var displayMat: Mat? = null
                        try {
                            displayMat = Mat(displayMatAddr)
                            synchronized(mDisplayLock) {
                                val displayWidth = displayMat.width()
                                val displayHeight = displayMat.height()
                                if (mDisplayBitmap == null || mDisplayBitmap!!.width != displayWidth || mDisplayBitmap!!.height != displayHeight) {
                                    mDisplayBitmap?.recycle()
                                    mDisplayBitmap = Bitmap.createBitmap(displayWidth, displayHeight, Bitmap.Config.ARGB_8888)
                                }
                                // Convert Mat to Bitmap (displayMat is already RGB)
                                Utils.matToBitmap(displayMat, mDisplayBitmap!!)
                                
                                // Draw to surface on UI thread
                                post {
                                    drawFrame()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error displaying frame", e)
                        } finally {
                            // Release the Mat (native memory will be freed)
                            displayMat?.release()
                        }
                    }
                }
                
                image.close()
            }, mBackgroundHandler)
            
            mPreviewRequestBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            mPreviewRequestBuilder!!.addTarget(mImageReader!!.surface)

            // Set focus mode to infinity or fixed for consistent exposure
            try {
                mPreviewRequestBuilder!!.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                mPreviewRequestBuilder!!.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f) // Infinity
                Log.d(TAG, "Focus set to infinity")
            } catch (e: Exception) {
                Log.w(TAG, "Could not set focus to infinity: ${e.message}")
                mPreviewRequestBuilder!!.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

            // Set exposure compensation for better frame rate consistency
            try {
                mPreviewRequestBuilder!!.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                mPreviewRequestBuilder!!.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, -2)
            } catch (e: Exception) {
                Log.w(TAG, "Could not set exposure compensation: ${e.message}")
            }

            mCameraDevice!!.createCaptureSession(
                listOf(mImageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.i(TAG, "createCaptureSession::onConfigured")
                        if (mCameraDevice == null) {
                            return
                        }
                        mCaptureSession = session
                        try {
                            mCaptureSession!!.setRepeatingRequest(
                                mPreviewRequestBuilder!!.build(),
                                null,
                                mBackgroundHandler
                            )
                            Log.i(TAG, "CameraPreviewSession has been started")
                        } catch (e: Exception) {
                            Log.e(TAG, "createCaptureSession failed", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "createCameraPreviewSession failed")
                    }
                },
                null
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "createCameraPreviewSession", e)
        }
    }

    private fun calcPreviewSize(width: Int, height: Int): Boolean {
        if (mCameraID == null) {
            Log.e(TAG, "Camera isn't initialized!")
            return false
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val characteristics = manager.getCameraCharacteristics(mCameraID!!)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map!!.getOutputSizes(ImageReader::class.java)
            var bestSize = sizes[0]
            var bestDiff = Integer.MAX_VALUE
            for (size in sizes) {
                val diff = Math.abs(size.width - width) + Math.abs(size.height - height)
                if (diff < bestDiff && size.width <= 640 && size.height <= 480) {
                    bestDiff = diff
                    bestSize = size
                }
            }
            if (mPreviewSize == bestSize) {
                return false
            }
            mPreviewSize = bestSize
            Log.i(TAG, "Selected preview size: ${mPreviewSize.width}x${mPreviewSize.height}")
            return true
        } catch (e: CameraAccessException) {
            Log.e(TAG, "calcPreviewSize", e)
        }
        return false
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // Do nothing, wait for surfaceChanged
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        checkState()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        checkState()
        synchronized(mDisplayLock) {
            mDisplayBitmap?.recycle()
            mDisplayBitmap = null
        }
    }
    
    private fun drawFrame() {
        val bitmap = synchronized(mDisplayLock) {
            mDisplayBitmap
        }
        
        if (bitmap == null) {
            return
        }
        
        val canvas = holder.lockCanvas()
        if (canvas != null) {
            try {
                // Clear canvas
                canvas.drawColor(android.graphics.Color.BLACK)
                
                // Scale bitmap to fit view while maintaining aspect ratio
                val viewWidth = width.toFloat()
                val viewHeight = height.toFloat()
                val bitmapWidth = bitmap.width.toFloat()
                val bitmapHeight = bitmap.height.toFloat()
                
                val scale = Math.min(viewWidth / bitmapWidth, viewHeight / bitmapHeight)
                val scaledWidth = bitmapWidth * scale
                val scaledHeight = bitmapHeight * scale
                val left = (viewWidth - scaledWidth) / 2f
                val top = (viewHeight - scaledHeight) / 2f
                
                canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + scaledWidth, top + scaledHeight), null)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    companion object {
        init {
            System.loadLibrary("native-lib")
        }
        
        @JvmStatic
        private external fun processYUVToRGBAJNI(
            yData: ByteArray?,
            uData: ByteArray?,
            vData: ByteArray?,
            width: Int,
            height: Int,
            yStride: Int,
            uStride: Int,
            vStride: Int,
            chromaPixelStride: Int
        ): Long  // Returns Mat address
        
        @JvmStatic
        private external fun getDisplayImageJNI(rawCameraMatAddr: Long): Long  // Returns Mat address of display image (RGB), or 0 if error
    }
}
