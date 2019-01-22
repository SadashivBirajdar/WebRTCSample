package com.example.webrtc.android

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.webkit.URLUtil
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import org.appspot.apprtc.ui.CallActivity
import java.util.*

/**
 * Handles the initial setup where the user selects which room to join.
 */
class ConnectActivity : AppCompatActivity() {

  private var startCallIntent: Intent? = null
  private val TAG = "ConnectActivity"
  private val CONNECTION_REQUEST = 1
  private val PERMISSIONS_START_CALL = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.INTERNET, Manifest.permission.MODIFY_AUDIO_SETTINGS)
  private val PERMISSIONS_REQUEST_START_CALL = 101

  public override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_connect)

    val roomId = findViewById<EditText>(R.id.roomID)
    val audioCall = findViewById<Button>(R.id.audioCall)
    val videoCall = findViewById<Button>(R.id.videoCall)
    val startCapture = findViewById<Button>(R.id.startCapture)
    audioCall.setOnClickListener {
      var room = roomId.text.toString()
      // roomId is random for loopback.
      if (room.isEmpty()) {
        room = Integer.toString(Random().nextInt(100000000))
      }
      connectToRoom(room, false)
    }

    videoCall.setOnClickListener {
      var room = roomId.text.toString()
      if (room.isEmpty()) {
        room = Integer.toString(Random().nextInt(100000000))
      }
      connectToRoom(room, true)
    }

    startCapture.setOnClickListener {
      startActivity(Intent(applicationContext, VideoCapture::class.java))
    }
  }

  public override fun onPause() {
    super.onPause()
  }

  public override fun onResume() {
    super.onResume()
  }

  private fun connectToRoom(roomId: String, videoCallEnabled: Boolean) {

    val roomUrl = getString(org.appspot.apprtc.R.string.pref_room_server_url_default)

    // Get default codecs.
    val videoCodec = "VP8"
    val audioCodec = "OPUS"

    val saveInputAudioToFile = false

    // Get video and audio start bitrate.
    val videoStartBitrate = 0

    val audioStartBitrate = 0

    // Get datachannel options
    val dataChannelEnabled = true
    val ordered = true
    val protocol = ""

    // Start AppRTCMobile activity.
    Log.d(TAG, "Connecting to room $roomId at URL $roomUrl")
    if (validateUrl(roomUrl)) {
      val uri = Uri.parse(roomUrl)
      val intent = Intent(this, CallActivity::class.java)
      intent.data = uri
      intent.putExtra(CallActivity.EXTRA_ROOMID, roomId)
      intent.putExtra(CallActivity.EXTRA_VIDEO_CALL, videoCallEnabled)
      intent.putExtra(CallActivity.EXTRA_VIDEO_BITRATE, videoStartBitrate)
      intent.putExtra(CallActivity.EXTRA_VIDEOCODEC, videoCodec)
      intent.putExtra(CallActivity.EXTRA_SAVE_INPUT_AUDIO_TO_FILE_ENABLED, saveInputAudioToFile)
      intent.putExtra(CallActivity.EXTRA_AUDIO_BITRATE, audioStartBitrate)
      intent.putExtra(CallActivity.EXTRA_AUDIOCODEC, audioCodec)

      intent.putExtra(CallActivity.EXTRA_DATA_CHANNEL_ENABLED, dataChannelEnabled)

      intent.putExtra(CallActivity.EXTRA_ORDERED, ordered)
      intent.putExtra(CallActivity.EXTRA_PROTOCOL, protocol)

      startCallActivity(intent)
    }
  }

  private fun startCallActivity(intent: Intent) {
    if (!hasPermissions(this, *PERMISSIONS_START_CALL)) {
      startCallIntent = intent
      ActivityCompat.requestPermissions(this, PERMISSIONS_START_CALL, PERMISSIONS_REQUEST_START_CALL)
      return
    }
    startActivityForResult(intent, CONNECTION_REQUEST)
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)

    when (requestCode) {
      PERMISSIONS_REQUEST_START_CALL -> {
        if (hasPermissions(this, *PERMISSIONS_START_CALL)) {
          // permission was granted, yay!
          if (startCallIntent != null) startActivityForResult(startCallIntent, CONNECTION_REQUEST)
        } else {
          Toast.makeText(this, "Required permissions denied.", Toast.LENGTH_LONG).show()
        }
      }
    }
  }

  override fun onRestoreInstanceState(savedInstanceState: Bundle) {
    super.onRestoreInstanceState(savedInstanceState)
    startCallIntent = savedInstanceState.getParcelable("startCallIntent")
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putParcelable("startCallIntent", startCallIntent)
  }

  private fun validateUrl(url: String): Boolean {
    if (URLUtil.isHttpsUrl(url) || URLUtil.isHttpUrl(url)) {
      return true
    }

    AlertDialog.Builder(this).setTitle(getText(org.appspot.apprtc.R.string.invalid_url_title))
        .setMessage(getString(org.appspot.apprtc.R.string.invalid_url_text, url))
        .setCancelable(false)
        .setNeutralButton(org.appspot.apprtc.R.string.ok) { dialog, id -> dialog.cancel() }
        .create()
        .show()
    return false
  }

  private fun hasPermissions(context: Context?, vararg permissions: String): Boolean {
    if (context != null) {
      for (permission in permissions) {
        if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
          return false
        }
      }
    }
    return true
  }
}
