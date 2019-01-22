package com.example.webrtc.android

import android.hardware.Camera
import android.hardware.Camera.PictureCallback
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Toast
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

class VideoCapture : AppCompatActivity(), SurfaceHolder.Callback {

  var camera: Camera? = null
  private lateinit var surfaceView: SurfaceView
  private lateinit var surfaceHolder: SurfaceHolder
  private lateinit var jpegCallback: PictureCallback

  /** Called when the activity is first created.  */
  public override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setContentView(R.layout.camera_view)

    surfaceView = findViewById<View>(R.id.surfaceView) as SurfaceView
    surfaceHolder = surfaceView.holder

    // Install a SurfaceHolder.Callback so we get notified when the
    // underlying surface is created and destroyed.
    surfaceHolder.addCallback(this)

    // deprecated setting, but required on Android versions prior to 3.0
    surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS)

    jpegCallback = PictureCallback { data, camera ->
      val outStream: FileOutputStream
      try {
        outStream = FileOutputStream(String.format("/sdcard/%d.jpg", System.currentTimeMillis()))
        outStream.write(data)
        outStream.close()
        Log.d("Log", "onPictureTaken - wrote bytes: " + data.size)
      } catch (e: FileNotFoundException) {
        e.printStackTrace()
      } catch (e: IOException) {
        e.printStackTrace()
      } finally {
      }
      Toast.makeText(this@VideoCapture.applicationContext, "Picture Saved", Toast.LENGTH_LONG).show()
      this@VideoCapture.refreshCamera()
    }

  }

  fun captureImage(v: View) {
    //take the picture
    camera!!.takePicture(null, null, jpegCallback)
  }

  private fun refreshCamera() {
    if (surfaceHolder.surface == null) {
      // preview surface does not exist
      return
    }

    // stop preview before making changes
    try {
      camera!!.stopPreview()
    } catch (e: Exception) {
      // ignore: tried to stop a non-existent preview
    }

    // set preview size and make any resize, rotate or
    // reformatting changes here
    // start preview with new settings
    try {
      camera!!.setPreviewDisplay(surfaceHolder)
      camera!!.startPreview()
    } catch (e: Exception) {

    }

  }

  override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
    // Now that the size is known, set up the camera parameters and begin
    // the preview.
    refreshCamera()
  }

  override fun surfaceCreated(holder: SurfaceHolder) {

    Log.d("No of cameras", Camera.getNumberOfCameras().toString() + "")
    for (camNo in 0 until Camera.getNumberOfCameras()) {
      val camInfo = Camera.CameraInfo()
      Camera.getCameraInfo(camNo, camInfo)
      if (camInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
        camera = Camera.open(camNo)
        break
      }
    }

    if (camera == null) {
      try {
        // open the camera
        camera = Camera.open()
      } catch (e: RuntimeException) {
        // check for exceptions
        e.printStackTrace()
        return
      }

    }

    val param = camera!!.parameters

    // modify parameter
    camera!!.setDisplayOrientation(90)
    param.setPreviewSize(352, 288)
    camera!!.parameters = param
    try {
      // The Surface has been created, now tell the camera where to draw
      // the preview.
      camera!!.setPreviewDisplay(surfaceHolder)
      camera!!.startPreview()
    } catch (e: Exception) {
      // check for exceptions
      e.printStackTrace()
    }

  }

  override fun surfaceDestroyed(holder: SurfaceHolder) {
    // stop preview and release camera
    camera!!.stopPreview()
    camera!!.release()
    camera = null
  }
}