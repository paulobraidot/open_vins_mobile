package com.openvins.android

import android.Manifest
import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.opencv.android.BaseLoaderCallback
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2
import org.opencv.android.LoaderCallbackInterface
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.File


class MainActivity : AppCompatActivity(), CvCameraViewListener2, SensorEventListener {

    private var mOpenCvCameraView: JavaCamResView? = null
    private var mGrayMat: Mat? = null
    private var isRecording: Boolean = false
    private var hasRecordFolder: Boolean = false
    private var recordFolder: String = ""

    private lateinit var sensorManager: SensorManager
    private var sensorAccel: Sensor? = null
    private var sensorGyro: Sensor? = null
    private var eventAccel: SensorEvent? = null
    private var eventGyro: SensorEvent? = null

    private val mLoaderCallback = object : BaseLoaderCallback(this) {
        override fun onManagerConnected(status: Int) {
            when (status) {
                LoaderCallbackInterface.SUCCESS -> {

                    // Load native library after(!) OpenCV initialization
                    Log.i(TAG, "Loading native library")
                    System.loadLibrary("native-lib")

                    // We are good, so activate our camera feed
                    Log.i(TAG, "OpenCV loaded successfully")
                    mOpenCvCameraView!!.enableView()

                }
                else -> {
                    super.onManagerConnected(status)
                }
            }
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Permissions for Android 6+
        ActivityCompat.requestPermissions(
            this@MainActivity,
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ),
            PERMISSION_REQUEST
        )

        // Setup our camera
        setContentView(R.layout.activity_main)
        mOpenCvCameraView = findViewById<View>(R.id.test_view) as JavaCamResView
        mOpenCvCameraView!!.setCvCameraViewListener(this)
        //mOpenCvCameraView!!.setMaxFrameSize(640,480)
        //mOpenCvCameraView!!.setFocusMode(this, Camera.Parameters.FOCUS_MODE_INFINITY)

        // Check that we have our accelerometer and gyroscope sensors
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorAccel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorGyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if(sensorAccel == null) {
            Toast.makeText(applicationContext,
                "ERROR: unable to open accelerometer", Toast.LENGTH_LONG).show()
        }
        if(sensorGyro == null) {
            Toast.makeText(applicationContext,
                "ERROR: unable to open accelerometer", Toast.LENGTH_LONG).show()
        }

        // Our open folder button
        recordFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).toString()+"/openvins/"
        val fab_folder = findViewById(R.id.open_folder) as FloatingActionButton
        fab_folder.setOnClickListener {
            val builder: AlertDialog.Builder = AlertDialog.Builder(this)
            builder.setTitle("Desired Save Folder")
            val input = EditText(this)
            input.setInputType(InputType.TYPE_CLASS_TEXT)
            input.setText(recordFolder)
            builder.setView(input)
            // Set up the buttons
            builder.setPositiveButton("OK", object : DialogInterface.OnClickListener {
                override fun onClick(dialog: DialogInterface?, which: Int) {
                    val file = File(input.getText().toString())
                    recordFolder = file.toString()
                    if((!file.isDirectory && !file.mkdirs()) || file.isFile) {
                        Toast.makeText(applicationContext,
                            "ERROR: unable to create directory. ${file.toString()}", Toast.LENGTH_LONG).show()
                    } else {
                        hasRecordFolder = true
                        setAppFolderJNI(recordFolder)
                    }
                }
            })
            builder.setNegativeButton("Cancel", object : DialogInterface.OnClickListener {
                override fun onClick(dialog: DialogInterface, which: Int) {
                    dialog.cancel()
                }
            })
            builder.show()

        }
        fab_folder.callOnClick()

        // Our record button
        val fab = findViewById(R.id.toggle_record) as FloatingActionButton
        fab.setOnClickListener {
            if(!hasRecordFolder) {
                Toast.makeText(this, "ERROR: select record folder first!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            isRecording = if(isRecording) {
                fab.setImageResource(R.drawable.ic_baseline_play_arrow_24)
                false
            } else {
                fab.setImageResource(R.drawable.ic_baseline_close_24)
                true
            }
            setRecordStateJNI(isRecording)
        }

    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    mOpenCvCameraView!!.setCameraPermissionGranted()
                } else {
                    Log.e(TAG, "Camera permission was not granted")
                    Toast.makeText(this, "Camera permission was not granted", Toast.LENGTH_LONG).show()
                }
            }
            else -> {
                Log.e(TAG, "Unexpected permission request")
            }
        }
    }

    public override fun onPause() {
        super.onPause()
        if (mOpenCvCameraView != null) mOpenCvCameraView!!.disableView()
        sensorManager.unregisterListener(this)
    }

    public override fun onResume() {
        super.onResume()
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "Internal OpenCV library not found. Using OpenCV Manager for initialization")
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION, this, mLoaderCallback)
        } else {
            Log.d(TAG, "OpenCV library found inside package. Using it!")
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS)
        }
        sensorAccel?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        }
        sensorGyro?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    public override fun onDestroy() {
        super.onDestroy()
        if (mOpenCvCameraView != null) mOpenCvCameraView!!.disableView()
    }

    override fun onCameraViewStarted(width: Int, height: Int) {
        mGrayMat = Mat(height, width, CvType.CV_8UC1)
    }

    override fun onCameraViewStopped() {
        mGrayMat!!.release()
    }

    override fun onCameraFrame(inputFrame: CvCameraViewFrame): Mat {
        // get current camera frame as OpenCV Mat object
        val mat = inputFrame.rgba()

        // native call to process current camera frame
        processImageJNI(mat.nativeObjAddr)

        // return processed frame for live preview
        return mat
    }

    override fun onSensorChanged(event: SensorEvent?) {

        // First check if we have any new events
        when (event?.sensor?.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                //Log.e(TAG, "[acc]: ${event.values[0]}, ${event.values[1]}, ${event.values[2]}")
                eventAccel = event
            }
            Sensor.TYPE_GYROSCOPE -> {
                //Log.e(TAG, "[gyro]: ${event.values[0]}, ${event.values[1]}, ${event.values[2]}")
                eventGyro = event
            }
        }

        // Next wait till we have both gyroscope and accelerometer
        // TODO: we should try to be smarter about this selection as they could be
        // TODO: out of sync and we should never know this...
        if(eventAccel != null && eventGyro != null) {
            processInertialJNI(
                eventAccel!!.values[0], eventAccel!!.values[1], eventAccel!!.values[2],
                eventGyro!!.values[0], eventGyro!!.values[1], eventGyro!!.values[2]);
            eventAccel = null;
            eventGyro = null;
        }

    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
        Log.d(TAG, "[sensor]: accuracy level of ${p0.toString()} changed to $p1")
    }

    private external fun setAppFolderJNI(dir: String)
    private external fun setRecordStateJNI(state: Boolean)
    private external fun processImageJNI(matAddr: Long)
    private external fun processInertialJNI(ax: Float, ay: Float, az: Float,
                                            gx: Float, gy: Float, gz: Float)

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST = 1
    }

    init {
        Log.i(TAG, "Instantiated new " + this.javaClass)
    }
}