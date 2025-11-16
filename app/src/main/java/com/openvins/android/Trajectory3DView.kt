package com.openvins.android

import android.content.Context
import android.opengl.GLSurfaceView
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class Trajectory3DView : GLSurfaceView {
    
    private var renderer: TrajectoryRenderer? = null
    private var hasTrajectoryData = false
    
    constructor(context: Context) : super(context) {
        initRenderer()
    }
    
    constructor(context: Context, attrs: android.util.AttributeSet?) : super(context, attrs) {
        initRenderer()
    }
    
    private fun initRenderer() {
        setEGLContextClientVersion(2)
        // Make the surface transparent so camera view shows through
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        // Use setZOrderOnTop to ensure we're above the camera view
        setZOrderOnTop(true)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        renderer = TrajectoryRenderer()
        setRenderer(renderer)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }
    
    fun updateTrajectory(positions: FloatArray, quaternions: FloatArray, currentPos: FloatArray, currentQuat: FloatArray) {
        hasTrajectoryData = positions.isNotEmpty()
        renderer?.updateTrajectory(positions, quaternions, currentPos, currentQuat)
        requestRender()
    }
    
    // Clear trajectory data
    fun clearTrajectory() {
        hasTrajectoryData = false
        renderer?.updateTrajectory(floatArrayOf(), floatArrayOf(), floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 0f, 0f, 0f))
        requestRender()
    }
    
    // Force render even without trajectory data (for axes/grid visibility)
    fun forceRender() {
        requestRender()
    }
    
    fun setCameraControls(elevation: Float, azimuth: Float, zoom: Float, translateX: Float, translateY: Float) {
        renderer?.setCameraControls(elevation, azimuth, zoom, translateX, translateY)
        requestRender()
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Always allow interaction with 3D view (axes and grid are always visible)
        // Extract touch data on UI thread, then pass to render thread
        val action = event.action and MotionEvent.ACTION_MASK
        val pointerCount = event.pointerCount
        val x0 = if (pointerCount > 0) event.getX(0) else 0f
        val y0 = if (pointerCount > 0) event.getY(0) else 0f
        val x1 = if (pointerCount > 1) event.getX(1) else 0f
        val y1 = if (pointerCount > 1) event.getY(1) else 0f
        
        queueEvent {
            renderer?.handleTouchData(action, pointerCount, x0, y0, x1, y1)
        }
        requestRender() // Update view immediately
        return true
    }
}

class TrajectoryRenderer : GLSurfaceView.Renderer {
    
    // Frame counter for logging
    private var frameCount = 0
    
    // Trajectory data
    private var trajectoryPositions: FloatArray = floatArrayOf()
    private var trajectoryQuaternions: FloatArray = floatArrayOf()
    private var currentPosition: FloatArray = floatArrayOf(0f, 0f, 0f)
    private var currentQuaternion: FloatArray = floatArrayOf(1f, 0f, 0f, 0f)
    
    // Camera controls - start with good view of grid
    // elevation: angle from vertical (0 = straight down from above, 90 = horizontal) - constrained to 0-90
    // azimuth: rotation around vertical axis (full 360 degrees allowed)
    // zoom: zoom factor: 1.0 = default view, >1.0 = zoomed out, <1.0 = zoomed in
    // translateX/Y: translation offset for panning
    private var elevation = 55f  // Look down at 35 degrees from vertical
    private var azimuth = -60f   // Rotation around vertical axis
    private var zoom = 2.0f      // Zoom factor: 1.0 = default view, >1.0 = zoomed out, <1.0 = zoomed in
    private var translateX = 0f  // Translation in X
    private var translateY = 0f  // Translation in Z (forward/back in OpenGL)
    
    // Touch handling
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastTouchDistance = 0f
    private var isZooming = false
    private var isRotating = false
    
    // Shader programs
    private var trajectoryProgram = 0
    private var frustumProgram = 0
    private var axisProgram = 0
    private var gridProgram = 0
    
    // Matrices
    private val mvpMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    
    // Orthographic projection parameters
    private var baseOrthoHeight = 5f  // Base orthographic height in meters (scaled by zoom)
    private var baseOrthoWidth = 60f
    private var screenAspectRatio = 1f
    
    // Grid parameters (in meters)
    private var gridSize = 8f      // Grid extends from -gridSize/2 to +gridSize/2 in both X and Y directions
    private var gridSpacing = 1.0f   // Spacing between grid lines in meters
    
    // Trajectory parameters
    private var trajectoryLineWidth = 10f  // Line width for trajectory rendering
    
    // Axis parameters
    private var axisLength = 1f  // Length of coordinate axes in meters
    
    // Trajectory line buffers
    private var trajectoryBuffer: FloatBuffer? = null
    private var trajectoryColorBuffer: FloatBuffer? = null
    private var trajectoryPointCount = 0
    
    // Frustum buffers
    private var frustumBuffer: FloatBuffer? = null
    private var frustumColorBuffer: FloatBuffer? = null
    
    // Axis buffers
    private var axisBuffer: FloatBuffer? = null
    private var axisColorBuffer: FloatBuffer? = null
    
    // Grid buffers
    private var gridBuffer: FloatBuffer? = null
    private var gridColorBuffer: FloatBuffer? = null
    private var gridLineCount = 0
    
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        android.util.Log.d("Trajectory3D", "Surface created")
        // Fully transparent background
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        
        trajectoryProgram = createShaderProgram(trajectoryVertexShader, trajectoryFragmentShader)
        frustumProgram = createShaderProgram(frustumVertexShader, frustumFragmentShader)
        axisProgram = createShaderProgram(axisVertexShader, axisFragmentShader)
        gridProgram = createShaderProgram(gridVertexShader, gridFragmentShader)
        
        android.util.Log.d("Trajectory3D", "Programs created: trajectory=$trajectoryProgram, frustum=$frustumProgram, axis=$axisProgram, grid=$gridProgram")
        
        // Check for shader compilation errors
        if (trajectoryProgram == 0) android.util.Log.e("Trajectory3D", "Failed to create trajectory program!")
        if (frustumProgram == 0) android.util.Log.e("Trajectory3D", "Failed to create frustum program!")
        if (axisProgram == 0) android.util.Log.e("Trajectory3D", "Failed to create axis program!")
        if (gridProgram == 0) android.util.Log.e("Trajectory3D", "Failed to create grid program!")
        
        initializeAxis()
        initializeGrid()
        
        android.util.Log.d("Trajectory3D", "Axis and grid initialized. Grid line count: $gridLineCount")
    }
    
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        // Calculate aspect ratio of the screen
        screenAspectRatio = width.toFloat() / height.toFloat()
        // Store base orthographic size (will be scaled by zoom in onDrawFrame)
        baseOrthoWidth = baseOrthoHeight * screenAspectRatio  // Scale width to match aspect ratio
        // Projection matrix will be recalculated each frame based on zoom
        android.util.Log.d("Trajectory3D", "Surface changed: ${width}x${height}, aspect=$screenAspectRatio, base ortho=${baseOrthoWidth}x${baseOrthoHeight}")
    }
    
    override fun onDrawFrame(gl: GL10?) {
        // Fully transparent background
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        
        // Check for OpenGL errors
        var error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            android.util.Log.e("Trajectory3D", "OpenGL error before drawing: $error")
        }
        
        // Log rendering state periodically (every 60 frames)
        frameCount++
        if (frameCount % 60 == 0) {
            android.util.Log.d("Trajectory3D", "Rendering frame: grid=$gridLineCount lines, axes=ready, trajectory=$trajectoryPointCount points")
        }
        
        // Constrain elevation to prevent going below XY plane (looking upward)
        // elevation: 0 = straight down from above, 90 = horizontal (camera at same height as look-at)
        elevation = elevation.coerceIn(0f, 90f)
        
        // Update view matrix based on camera controls
        // OpenGL uses left-handed coordinate system
        // OpenGL: X=right, Y=up, Z=forward (into screen, positive Z is away)
        // Right-handed trajectory: X=right, Y=forward, Z=up
        // Conversion already done: OpenGL_X = Right_X, OpenGL_Y = Right_Z, OpenGL_Z = -Right_Y
        
        Matrix.setIdentityM(viewMatrix, 0)
        
        // Calculate center point to look at (origin or trajectory center)
        var lookAtX = 0f
        var lookAtY = 0f  // OpenGL Y (up) - should stay at 0 for grid plane
        var lookAtZ = 0f  // OpenGL Z
        
        // If we have trajectory data, center on trajectory
        if (trajectoryPointCount > 0 && trajectoryBuffer != null) {
            val buffer = trajectoryBuffer!!
            // trajectoryPointCount is calculated from buffer capacity, so it should be safe
            for (i in 0 until trajectoryPointCount) {
                val baseIdx = i * 3
                lookAtX += buffer.get(baseIdx)
                lookAtY += buffer.get(baseIdx + 1)
                lookAtZ += buffer.get(baseIdx + 2)
            }
            lookAtX /= trajectoryPointCount
            lookAtY /= trajectoryPointCount
            lookAtZ /= trajectoryPointCount
        }
        
        // Apply translation
        lookAtX += translateX
        lookAtZ += translateY
        
        // Update orthographic projection based on zoom
        // For orthographic, zoom scales the bounds: zoom > 1 = zoomed out, zoom < 1 = zoomed in
        val orthoHeight = baseOrthoHeight / zoom
        val orthoWidth = baseOrthoWidth / zoom
        Matrix.orthoM(projectionMatrix, 0, -orthoWidth, orthoWidth, -orthoHeight, orthoHeight, 0.1f, 100f)
        
        // Calculate camera position using spherical coordinates
        // Fixed distance from look-at point (orthographic doesn't use this for projection, but needed for view)
        val fixedCameraDistance = 50f
        
        // Convert angles to radians
        val elevationRad = Math.toRadians(elevation.toDouble())
        val azimuthRad = Math.toRadians(azimuth.toDouble())
        
        // Spherical to cartesian conversion
        // Elevation: angle from vertical (+Y axis)
        //   - elevation = 0: camera is straight above at (0, distance, 0), looking straight down
        //   - elevation = 90: camera is in horizontal plane at Y=0, looking horizontally
        // Azimuth: rotation around Y axis (vertical)
        //   - azimuth = 0: camera looking along -Z direction
        //   - azimuth = 90: camera looking along -X direction
        val cosElev = kotlin.math.cos(elevationRad).toFloat()
        val sinElev = kotlin.math.sin(elevationRad).toFloat()
        val cosAzim = kotlin.math.cos(azimuthRad).toFloat()
        val sinAzim = kotlin.math.sin(azimuthRad).toFloat()
        
        // Camera position in spherical coordinates
        // When elevation=0 (straight down), camera is at (0, distance, 0)
        // When elevation=90 (horizontal), camera is at distance from origin in horizontal plane (Y=0)
        // We ensure camera Y is always >= 0 (never below grid plane) since elevation in [0, 90]
        val cameraRelX = fixedCameraDistance * sinElev * cosAzim
        val cameraRelY = fixedCameraDistance * cosElev  // Always >= 0 since elevation in [0, 90]
        val cameraRelZ = fixedCameraDistance * sinElev * sinAzim
        
        // World-space camera position
        val eyeX = lookAtX + cameraRelX
        val eyeY = lookAtY + cameraRelY  // Always >= lookAtY, so camera never below grid
        val eyeZ = lookAtZ + cameraRelZ
        
        // Look-at point
        val centerX = lookAtX
        val centerY = lookAtY
        val centerZ = lookAtZ
        
        // Up vector: always points up (positive Y) in world space
        // This ensures the view doesn't flip when rotating around
        val upX = 0f
        val upY = 1f
        val upZ = 0f
        
        // Create view matrix
        Matrix.setLookAtM(viewMatrix, 0,
            eyeX, eyeY, eyeZ,      // Camera position
            centerX, centerY, centerZ,  // Look-at point
            upX, upY, upZ)         // Up vector (always +Y)
        
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, mvpMatrix, 0, modelMatrix, 0)
        
        // ALWAYS draw grid and axes - these should always be visible
        drawGrid()
        drawAxes()
        
        // Draw trajectory and frustum only if we have data
        if (trajectoryPointCount > 0) {
            drawTrajectory()
            drawCameraFrustum()
        }
    }
    
    private fun drawTrajectory() {
        if (trajectoryPointCount < 2 || trajectoryBuffer == null) return
        
        // trajectoryPointCount is calculated from buffer capacity, so it should be safe
        GLES20.glUseProgram(trajectoryProgram)
        
        val positionHandle = GLES20.glGetAttribLocation(trajectoryProgram, "vPosition")
        val colorHandle = GLES20.glGetAttribLocation(trajectoryProgram, "vColor")
        val mvpHandle = GLES20.glGetUniformLocation(trajectoryProgram, "uMVPMatrix")
        
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, trajectoryBuffer)
        
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 0, trajectoryColorBuffer)
        
        GLES20.glLineWidth(trajectoryLineWidth)
        GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, trajectoryPointCount)
        
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }
    
    private fun drawCameraFrustum() {
        if (frustumBuffer == null) return
        
        GLES20.glUseProgram(frustumProgram)
        
        val positionHandle = GLES20.glGetAttribLocation(frustumProgram, "vPosition")
        val colorHandle = GLES20.glGetAttribLocation(frustumProgram, "vColor")
        val mvpHandle = GLES20.glGetUniformLocation(frustumProgram, "uMVPMatrix")
        
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, frustumBuffer)
        
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 0, frustumColorBuffer)
        
        GLES20.glLineWidth(2f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 24) // Camera frustum has 24 lines
        
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }
    
    private fun drawAxes() {
        if (axisBuffer == null) {
            android.util.Log.w("Trajectory3D", "Axis buffer not initialized")
            return
        }
        
        if (axisProgram == 0) {
            android.util.Log.e("Trajectory3D", "Axis program not initialized!")
            return
        }
        
        GLES20.glUseProgram(axisProgram)
        
        val positionHandle = GLES20.glGetAttribLocation(axisProgram, "vPosition")
        val colorHandle = GLES20.glGetAttribLocation(axisProgram, "vColor")
        val mvpHandle = GLES20.glGetUniformLocation(axisProgram, "uMVPMatrix")
        
        if (positionHandle == -1 || colorHandle == -1 || mvpHandle == -1) {
            android.util.Log.e("Trajectory3D", "Invalid axis shader handles: pos=$positionHandle, color=$colorHandle, mvp=$mvpHandle")
            return
        }
        
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, axisBuffer)
        
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 0, axisColorBuffer)
        
        GLES20.glLineWidth(5f) // Make axes very thick for maximum visibility
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 6)
        
        var error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            android.util.Log.e("Trajectory3D", "OpenGL error after drawing axes: $error")
        }
        
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }
    
    fun updateTrajectory(positions: FloatArray, quaternions: FloatArray, 
                        currentPos: FloatArray, currentQuat: FloatArray) {
        synchronized(this) {
            if (positions.isEmpty()) {
                // Reset trajectory data when empty
                trajectoryPointCount = 0
                trajectoryBuffer = null
                trajectoryColorBuffer = null
            } else {
                // Validate that positions array is a multiple of 3 (each point has x, y, z)
                if (positions.size % 3 != 0) {
                    android.util.Log.e("Trajectory3D", "Invalid positions array size: ${positions.size} (must be multiple of 3)")
                    trajectoryPointCount = 0
                    return
                }
                
                trajectoryPositions = positions
                trajectoryQuaternions = quaternions
                
                // Convert positions from right-handed to OpenGL left-handed
                // Right-handed: (X, Y, Z) where Z is up
                // OpenGL: (X, -Z, Y) where Y is up
                val convertedPositions = FloatArray(positions.size)
                for (i in 0 until positions.size step 3) {
                    val x = positions[i]      // X stays same
                    val y = positions[i + 1]  // Right-handed Y (forward) -> OpenGL -Z
                    val z = positions[i + 2]  // Right-handed Z (up) -> OpenGL Y
                    convertedPositions[i] = x
                    convertedPositions[i + 1] = z   // Z -> Y (up)
                    convertedPositions[i + 2] = -y  // Y -> -Z (forward into screen)
                }
                
                // Create trajectory buffer
                val bb = ByteBuffer.allocateDirect(convertedPositions.size * 4)
                bb.order(ByteOrder.nativeOrder())
                trajectoryBuffer = bb.asFloatBuffer()
                trajectoryBuffer!!.put(convertedPositions)
                trajectoryBuffer!!.position(0)
                
                // Calculate point count from actual buffer capacity to ensure they match
                // This is the source of truth - buffer capacity / 3
                trajectoryPointCount = trajectoryBuffer!!.capacity() / 3
                if (trajectoryPointCount * 3 != trajectoryBuffer!!.capacity()) {
                    android.util.Log.e("Trajectory3D", "Buffer capacity ${trajectoryBuffer!!.capacity()} is not a multiple of 3!")
                    trajectoryPointCount = 0
                    return
                }
                
                // Create gradient colors for trajectory (blue to green to red)
                val colors = FloatArray(convertedPositions.size / 3 * 4)
                for (i in colors.indices step 4) {
                    val idx = i / 4
                    val progress = idx.toFloat() / (colors.size / 4 - 1).coerceAtLeast(1)
                    colors[i] = if (progress < 0.5f) 0f else (progress - 0.5f) * 2f // R
                    colors[i + 1] = if (progress < 0.5f) progress * 2f else 1f - (progress - 0.5f) * 2f // G
                    colors[i + 2] = if (progress < 0.5f) 1f - progress * 2f else 0f // B
                    colors[i + 3] = 1f // A
                }
                
                val colorBB = ByteBuffer.allocateDirect(colors.size * 4)
                colorBB.order(ByteOrder.nativeOrder())
                trajectoryColorBuffer = colorBB.asFloatBuffer()
                trajectoryColorBuffer!!.put(colors)
                trajectoryColorBuffer!!.position(0)
            }
            
            // Convert current position from right-handed to OpenGL
            if (currentPos.size >= 3) {
                currentPosition = floatArrayOf(
                    currentPos[0],     // X
                    currentPos[2],    // Z -> Y (up)
                    -currentPos[1]    // Y -> -Z (forward)
                )
            } else {
                currentPosition = currentPos.clone()
            }
            
            // Store quaternion - already in Hamilton format [qw, qx, qy, qz] from native code
            // JPL q_GtoC has same xyzw as Hamilton q_CtoG, native code converts component order
            // For transforming points from camera to global, we need q_CtoG (which we have)
            currentQuaternion = currentQuat.clone()
            
            updateFrustum()
        }
    }
    
    private fun updateFrustum() {
        // Create camera frustum at current position/orientation
        // Frustum dimensions - 3x smaller than before, default to ~20cm size
        val near = 0.067f  // ~6.7cm (was 0.1f = 10cm)
        val far = 0.2f     // 20cm (was 2.0f = 2m)
        val fov = 60f * Math.PI.toFloat() / 180f
        val aspect = 1f
        val nearHeight = 2 * near * kotlin.math.tan(fov / 2)
        val nearWidth = nearHeight * aspect
        val farHeight = 2 * far * kotlin.math.tan(fov / 2)
        val farWidth = farHeight * aspect
        
        // Frustum corners in camera space (camera looks down +Z in camera frame)
        val corners = floatArrayOf(
            // Near plane
            -nearWidth/2, -nearHeight/2, near,  // bottom-left
            nearWidth/2, -nearHeight/2, near,   // bottom-right
            nearWidth/2, nearHeight/2, near,    // top-right
            -nearWidth/2, nearHeight/2, near,   // top-left
            // Far plane
            -farWidth/2, -farHeight/2, far,
            farWidth/2, -farHeight/2, far,
            farWidth/2, farHeight/2, far,
            -farWidth/2, farHeight/2, far
        )
        
        // Transform corners from camera space to OpenVINS global space, then convert to OpenGL
        // q represents camera orientation (Hamilton q_CtoG format: [qw, qx, qy, qz])
        val q = currentQuaternion
        val p_ogl = currentPosition  // Already in OpenGL coordinates
        
        // Hamilton quaternion to rotation matrix (R_CtoG in OpenVINS coordinates)
        // q = [qw, qx, qy, qz] where qw is scalar, [qx, qy, qz] is vector
        val qw = q[0]
        val qx = q[1]
        val qy = q[2]
        val qz = q[3]
        
        val transformedCorners = FloatArray(24) // 8 corners * 3
        
        for (i in 0 until 8) {
            val x_cam = corners[i * 3]      // Camera space X
            val y_cam = corners[i * 3 + 1]  // Camera space Y
            val z_cam = corners[i * 3 + 2]  // Camera space Z
            
            // Rotate from camera space to OpenVINS global space using R_CtoG
            // q = [qw, qx, qy, qz] represents rotation (JPL q_GtoC = Hamilton q_CtoG)
            // Standard Hamilton quaternion to rotation matrix formula for R_CtoG
            // This rotates vectors from camera frame to global frame
            val rx_ov = (1 - 2*qy*qy - 2*qz*qz) * x_cam + 2*(qx*qy - qz*qw) * y_cam + 2*(qx*qz + qy*qw) * z_cam
            val ry_ov = 2*(qx*qy + qz*qw) * x_cam + (1 - 2*qx*qx - 2*qz*qz) * y_cam + 2*(qy*qz - qx*qw) * z_cam
            val rz_ov = 2*(qx*qz - qy*qw) * x_cam + 2*(qy*qz + qx*qw) * y_cam + (1 - 2*qx*qx - 2*qy*qy) * z_cam
            
            // Get camera position in OpenVINS coordinates (convert from OpenGL)
            // OpenGL -> OpenVINS: (X, Y, Z) -> (X, -Z, Y)
            val px_ov = p_ogl[0]   // X stays same
            val py_ov = -p_ogl[2]  // OpenGL Z -> OpenVINS -Y
            val pz_ov = p_ogl[1]    // OpenGL Y -> OpenVINS Z
            
            // Translate to camera position in OpenVINS coordinates
            val rx_ov_world = rx_ov + px_ov
            val ry_ov_world = ry_ov + py_ov
            val rz_ov_world = rz_ov + pz_ov
            
            // Convert from OpenVINS to OpenGL coordinates
            // OpenVINS -> OpenGL: (X, Y, Z) -> (X, Z, -Y)
            transformedCorners[i * 3] = rx_ov_world        // X stays same
            transformedCorners[i * 3 + 1] = rz_ov_world    // OpenVINS Z -> OpenGL Y (up)
            transformedCorners[i * 3 + 2] = -ry_ov_world   // OpenVINS Y -> OpenGL -Z (forward)
        }
        
        // Create frustum lines (12 edges: 4 near, 4 far, 4 connecting)
        val frustumLines = FloatArray(72) // 24 line endpoints * 3 coords
        var idx = 0
        
        // Near plane rectangle (4 lines)
        // bottom-left to bottom-right
        frustumLines[idx++] = transformedCorners[0]; frustumLines[idx++] = transformedCorners[1]; frustumLines[idx++] = transformedCorners[2]
        frustumLines[idx++] = transformedCorners[3]; frustumLines[idx++] = transformedCorners[4]; frustumLines[idx++] = transformedCorners[5]
        // bottom-right to top-right
        frustumLines[idx++] = transformedCorners[3]; frustumLines[idx++] = transformedCorners[4]; frustumLines[idx++] = transformedCorners[5]
        frustumLines[idx++] = transformedCorners[6]; frustumLines[idx++] = transformedCorners[7]; frustumLines[idx++] = transformedCorners[8]
        // top-right to top-left
        frustumLines[idx++] = transformedCorners[6]; frustumLines[idx++] = transformedCorners[7]; frustumLines[idx++] = transformedCorners[8]
        frustumLines[idx++] = transformedCorners[9]; frustumLines[idx++] = transformedCorners[10]; frustumLines[idx++] = transformedCorners[11]
        // top-left to bottom-left
        frustumLines[idx++] = transformedCorners[9]; frustumLines[idx++] = transformedCorners[10]; frustumLines[idx++] = transformedCorners[11]
        frustumLines[idx++] = transformedCorners[0]; frustumLines[idx++] = transformedCorners[1]; frustumLines[idx++] = transformedCorners[2]
        
        // Far plane rectangle (4 lines)
        // bottom-left to bottom-right
        frustumLines[idx++] = transformedCorners[12]; frustumLines[idx++] = transformedCorners[13]; frustumLines[idx++] = transformedCorners[14]
        frustumLines[idx++] = transformedCorners[15]; frustumLines[idx++] = transformedCorners[16]; frustumLines[idx++] = transformedCorners[17]
        // bottom-right to top-right
        frustumLines[idx++] = transformedCorners[15]; frustumLines[idx++] = transformedCorners[16]; frustumLines[idx++] = transformedCorners[17]
        frustumLines[idx++] = transformedCorners[18]; frustumLines[idx++] = transformedCorners[19]; frustumLines[idx++] = transformedCorners[20]
        // top-right to top-left
        frustumLines[idx++] = transformedCorners[18]; frustumLines[idx++] = transformedCorners[19]; frustumLines[idx++] = transformedCorners[20]
        frustumLines[idx++] = transformedCorners[21]; frustumLines[idx++] = transformedCorners[22]; frustumLines[idx++] = transformedCorners[23]
        // top-left to bottom-left
        frustumLines[idx++] = transformedCorners[21]; frustumLines[idx++] = transformedCorners[22]; frustumLines[idx++] = transformedCorners[23]
        frustumLines[idx++] = transformedCorners[12]; frustumLines[idx++] = transformedCorners[13]; frustumLines[idx++] = transformedCorners[14]
        
        // Connecting lines (4 lines from near to far)
        frustumLines[idx++] = transformedCorners[0]; frustumLines[idx++] = transformedCorners[1]; frustumLines[idx++] = transformedCorners[2]
        frustumLines[idx++] = transformedCorners[12]; frustumLines[idx++] = transformedCorners[13]; frustumLines[idx++] = transformedCorners[14]
        frustumLines[idx++] = transformedCorners[3]; frustumLines[idx++] = transformedCorners[4]; frustumLines[idx++] = transformedCorners[5]
        frustumLines[idx++] = transformedCorners[15]; frustumLines[idx++] = transformedCorners[16]; frustumLines[idx++] = transformedCorners[17]
        frustumLines[idx++] = transformedCorners[6]; frustumLines[idx++] = transformedCorners[7]; frustumLines[idx++] = transformedCorners[8]
        frustumLines[idx++] = transformedCorners[18]; frustumLines[idx++] = transformedCorners[19]; frustumLines[idx++] = transformedCorners[20]
        frustumLines[idx++] = transformedCorners[9]; frustumLines[idx++] = transformedCorners[10]; frustumLines[idx++] = transformedCorners[11]
        frustumLines[idx++] = transformedCorners[21]; frustumLines[idx++] = transformedCorners[22]; frustumLines[idx++] = transformedCorners[23]
        
        val bb = ByteBuffer.allocateDirect(frustumLines.size * 4)
        bb.order(ByteOrder.nativeOrder())
        frustumBuffer = bb.asFloatBuffer()
        frustumBuffer!!.put(frustumLines)
        frustumBuffer!!.position(0)
        
        // Frustum color (yellow)
        val frustumColors = FloatArray(24 * 4)
        for (i in frustumColors.indices step 4) {
            frustumColors[i] = 1f     // R
            frustumColors[i + 1] = 1f // G
            frustumColors[i + 2] = 0f // B
            frustumColors[i + 3] = 1f // A
        }
        
        val colorBB = ByteBuffer.allocateDirect(frustumColors.size * 4)
        colorBB.order(ByteOrder.nativeOrder())
        frustumColorBuffer = colorBB.asFloatBuffer()
        frustumColorBuffer!!.put(frustumColors)
        frustumColorBuffer!!.position(0)
    }
    
    private fun initializeAxis() {
        // Draw coordinate axes at origin
        // Right-handed coordinate system: X=right, Y=forward, Z=up
        // OpenGL left-handed conversion: X->X, Y->-Z, Z->Y
        val axisVertices = floatArrayOf(
            // X axis (red) - right, no conversion needed
            0f, 0f, 0f, axisLength, 0f, 0f,
            // Y axis (green) - forward in right-handed -> -Z in OpenGL (pointing into screen)
            0f, 0f, 0f, 0f, 0f, -axisLength,
            // Z axis (blue) - up in right-handed -> +Y in OpenGL
            0f, 0f, 0f, 0f, axisLength, 0f
        )
        
        val axisColors = floatArrayOf(
            1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, // X axis red (right)
            0f, 1f, 0f, 1f, 0f, 1f, 0f, 1f, // Y axis green (forward)
            0f, 0f, 1f, 1f, 0f, 0f, 1f, 1f  // Z axis blue (up)
        )
        
        val bb = ByteBuffer.allocateDirect(axisVertices.size * 4)
        bb.order(ByteOrder.nativeOrder())
        axisBuffer = bb.asFloatBuffer()
        axisBuffer!!.put(axisVertices)
        axisBuffer!!.position(0)
        
        val colorBB = ByteBuffer.allocateDirect(axisColors.size * 4)
        colorBB.order(ByteOrder.nativeOrder())
        axisColorBuffer = colorBB.asFloatBuffer()
        axisColorBuffer!!.put(axisColors)
        axisColorBuffer!!.position(0)
    }
    
    private fun initializeGrid() {
        // Create a grid on the X-Y plane (Z=0 in OpenVINS right-handed coordinates)
        // In OpenVINS: X-Y plane means Z=0, which is the ground/gravity-aligned plane
        // In OpenGL conversion: (X, Y, Z) -> (X, Z, -Y)
        // So X-Y plane in OpenVINS (Z=0) becomes X-Z plane in OpenGL (Y=0 in OpenGL)
        val gridHalfSize = gridSize / 2f
        
        val gridVertices = mutableListOf<Float>()
        val gridColors = mutableListOf<Float>()
        
        // Grid color - bright white with 0.2 opacity
        val gridColor = floatArrayOf(1.0f, 1.0f, 1.0f, 0.2f)
        val gridColorList = gridColor.toList()
        
        // Draw lines parallel to X axis (varying Y in OpenVINS coordinates)
        // OpenVINS: (X, Y, Z=0) -> OpenGL: (X, Y=0, -Y)
        var y = -gridHalfSize
        while (y <= gridHalfSize + 0.1f) {
            // Start and end points in OpenVINS coordinates: (X, Y, Z=0)
            val x1_ov = -gridHalfSize
            val y1_ov = y
            val z1_ov = 0f
            val x2_ov = gridHalfSize
            val y2_ov = y
            val z2_ov = 0f
            
            // Convert to OpenGL coordinates: (X, Y, Z) -> (X, Z, -Y)
            val x1_ogl = x1_ov
            val y1_ogl = z1_ov  // OpenVINS Z -> OpenGL Y
            val z1_ogl = -y1_ov // OpenVINS Y -> OpenGL -Z
            val x2_ogl = x2_ov
            val y2_ogl = z2_ov  // OpenVINS Z -> OpenGL Y
            val z2_ogl = -y2_ov // OpenVINS Y -> OpenGL -Z
            
            gridVertices.add(x1_ogl)
            gridVertices.add(y1_ogl)
            gridVertices.add(z1_ogl)
            gridVertices.add(x2_ogl)
            gridVertices.add(y2_ogl)
            gridVertices.add(z2_ogl)
            gridColors.addAll(gridColorList)
            gridColors.addAll(gridColorList)
            y += gridSpacing
        }
        
        // Draw lines parallel to Y axis (varying X in OpenVINS coordinates)
        // OpenVINS: (X, Y, Z=0) -> OpenGL: (X, Y=0, -Y)
        var x = -gridHalfSize
        while (x <= gridHalfSize + 0.1f) {
            // Start and end points in OpenVINS coordinates: (X, Y, Z=0)
            val x1_ov = x
            val y1_ov = -gridHalfSize
            val z1_ov = 0f
            val x2_ov = x
            val y2_ov = gridHalfSize
            val z2_ov = 0f
            
            // Convert to OpenGL coordinates: (X, Y, Z) -> (X, Z, -Y)
            val x1_ogl = x1_ov
            val y1_ogl = z1_ov  // OpenVINS Z -> OpenGL Y
            val z1_ogl = -y1_ov // OpenVINS Y -> OpenGL -Z
            val x2_ogl = x2_ov
            val y2_ogl = z2_ov  // OpenVINS Z -> OpenGL Y
            val z2_ogl = -y2_ov // OpenVINS Y -> OpenGL -Z
            
            gridVertices.add(x1_ogl)
            gridVertices.add(y1_ogl)
            gridVertices.add(z1_ogl)
            gridVertices.add(x2_ogl)
            gridVertices.add(y2_ogl)
            gridVertices.add(z2_ogl)
            gridColors.addAll(gridColorList)
            gridColors.addAll(gridColorList)
            x += gridSpacing
        }
        
        gridLineCount = gridVertices.size / 3 // Total number of vertices (for GL_LINES draw call)
        
        val bb = ByteBuffer.allocateDirect(gridVertices.size * 4)
        bb.order(ByteOrder.nativeOrder())
        gridBuffer = bb.asFloatBuffer()
        gridBuffer!!.put(gridVertices.toFloatArray())
        gridBuffer!!.position(0)
        
        val colorBB = ByteBuffer.allocateDirect(gridColors.size * 4)
        colorBB.order(ByteOrder.nativeOrder())
        gridColorBuffer = colorBB.asFloatBuffer()
        gridColorBuffer!!.put(gridColors.toFloatArray())
        gridColorBuffer!!.position(0)
    }
    
    private fun drawGrid() {
        if (gridBuffer == null || gridLineCount == 0) {
            android.util.Log.w("Trajectory3D", "Grid not initialized: buffer=${gridBuffer != null}, count=$gridLineCount")
            return
        }
        
        if (gridProgram == 0) {
            android.util.Log.e("Trajectory3D", "Grid program not initialized!")
            return
        }
        
        GLES20.glUseProgram(gridProgram)
        
        val positionHandle = GLES20.glGetAttribLocation(gridProgram, "vPosition")
        val colorHandle = GLES20.glGetAttribLocation(gridProgram, "vColor")
        val mvpHandle = GLES20.glGetUniformLocation(gridProgram, "uMVPMatrix")
        
        if (positionHandle == -1 || colorHandle == -1 || mvpHandle == -1) {
            android.util.Log.e("Trajectory3D", "Invalid shader handles: pos=$positionHandle, color=$colorHandle, mvp=$mvpHandle")
            return
        }
        
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, gridBuffer)
        
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 0, gridColorBuffer)
        
        GLES20.glLineWidth(3f) // Make lines thicker for better visibility
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridLineCount)
        
        val gridError = GLES20.glGetError()
        if (gridError != GLES20.GL_NO_ERROR) {
            android.util.Log.e("Trajectory3D", "OpenGL error after drawing grid: $gridError")
        }
        
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }
    
    fun setCameraControls(elev: Float, azim: Float, z: Float, tx: Float, ty: Float) {
        synchronized(this) {
            elevation = elev
            azimuth = azim
            zoom = z
            translateX = tx
            translateY = ty
        }
    }
    
    fun handleTouchData(action: Int, pointerCount: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
        synchronized(this) {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = x0
                    lastTouchY = y0
                    isRotating = true
                    isZooming = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    isZooming = true
                    isRotating = false
                    val dx = x0 - x1
                    val dy = y0 - y1
                    lastTouchDistance = kotlin.math.sqrt(dx * dx + dy * dy)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isZooming && pointerCount == 2) {
                        val dx = x0 - x1
                        val dy = y0 - y1
                        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                        // Pinching (decreasing distance) should zoom out
                        val delta = (distance - lastTouchDistance) * 0.01f
                        zoom = (zoom + delta).coerceIn(0.5f, 50f)
                        lastTouchDistance = distance
                    } else if (isRotating && pointerCount == 1) {
                        val dx = x0 - lastTouchX
                        val dy = y0 - lastTouchY
                        // In landscape mode:
                        // - Horizontal touch movement (dx, along long edge) rotates azimuth (spin around vertical axis)
                        // - Vertical touch movement (dy, along short edge) changes elevation
                        //   - Dragging up (dy < 0): increase elevation -> look more horizontal
                        //   - Dragging down (dy > 0): decrease elevation -> look more straight down
                        azimuth += dx * 0.5f  // Horizontal drag -> rotate around vertical axis
                        elevation -= dy * 0.5f  // Vertical drag -> change elevation
                        // Constrain elevation to prevent looking below XY plane (looking upward)
                        // elevation: 0 = straight down, 90 = horizontal
                        elevation = elevation.coerceIn(0f, 90f)
                        lastTouchX = x0
                        lastTouchY = y0
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    isRotating = false
                    isZooming = false
                }
            }
        }
    }
    
    private fun createShaderProgram(vertexCode: String, fragmentCode: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode)
        if (vertexShader == 0) {
            android.util.Log.e("Trajectory3D", "Failed to load vertex shader!")
            return 0
        }
        
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)
        if (fragmentShader == 0) {
            android.util.Log.e("Trajectory3D", "Failed to load fragment shader!")
            GLES20.glDeleteShader(vertexShader)
            return 0
        }
        
        val program = GLES20.glCreateProgram()
        if (program == 0) {
            android.util.Log.e("Trajectory3D", "Failed to create shader program!")
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            return 0
        }
        
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        
        // Check linking status
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val infoLog = GLES20.glGetProgramInfoLog(program)
            android.util.Log.e("Trajectory3D", "Shader program linking failed: $infoLog")
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            return 0
        }
        
        // Clean up shaders after linking
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        
        return program
    }
    
    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            android.util.Log.e("Trajectory3D", "Failed to create shader!")
            return 0
        }
        
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        
        // Check compilation status
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val infoLog = GLES20.glGetShaderInfoLog(shader)
            android.util.Log.e("Trajectory3D", "Shader compilation failed: $infoLog")
            android.util.Log.e("Trajectory3D", "Shader code:\n$shaderCode")
            GLES20.glDeleteShader(shader)
            return 0
        }
        
        return shader
    }
    
    companion object {
        private val trajectoryVertexShader = """
            uniform mat4 uMVPMatrix;
            attribute vec4 vPosition;
            attribute vec4 vColor;
            varying vec4 vColorOut;
            void main() {
                gl_Position = uMVPMatrix * vPosition;
                vColorOut = vColor;
            }
        """.trimIndent()
        
        private val trajectoryFragmentShader = """
            precision mediump float;
            varying vec4 vColorOut;
            void main() {
                gl_FragColor = vColorOut;
            }
        """.trimIndent()
        
        private val frustumVertexShader = trajectoryVertexShader
        private val frustumFragmentShader = trajectoryFragmentShader
        
        private val axisVertexShader = trajectoryVertexShader
        private val axisFragmentShader = trajectoryFragmentShader
        
        private val gridVertexShader = trajectoryVertexShader
        private val gridFragmentShader = trajectoryFragmentShader
    }
}

