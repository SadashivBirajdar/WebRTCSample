/*
 *  Copyright 2015 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.appspot.apprtc.ui;

import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager.LayoutParams;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.appspot.apprtc.AppRTCAudioManager;
import org.appspot.apprtc.AppRTCAudioManager.AudioDevice;
import org.appspot.apprtc.AppRTCClient;
import org.appspot.apprtc.AppRTCClient.RoomConnectionParameters;
import org.appspot.apprtc.AppRTCClient.SignalingParameters;
import org.appspot.apprtc.PeerConnectionClient;
import org.appspot.apprtc.PeerConnectionClient.DataChannelParameters;
import org.appspot.apprtc.PeerConnectionClient.PeerConnectionParameters;
import org.appspot.apprtc.R;
import org.appspot.apprtc.UnhandledExceptionHandler;
import org.appspot.apprtc.WebSocketRTCClient;
import org.webrtc.Camera1Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.EglBase;
import org.webrtc.FileVideoCapturer;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RendererCommon.ScalingType;
import org.webrtc.SessionDescription;
import org.webrtc.StatsReport;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFileRenderer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

/**
 * Activity for peer connection call setup, call waiting
 * and call view.
 */
public class CallActivity extends AppCompatActivity implements AppRTCClient.SignalingEvents, PeerConnectionClient.PeerConnectionEvents, CallFragment.OnCallEvents {
  private static final String TAG = "CallRTCClient";

  public static final String EXTRA_ROOMID = "org.appspot.apprtc.ROOMID";
  public static final String EXTRA_URLPARAMETERS = "org.appspot.apprtc.URLPARAMETERS";
  public static final String EXTRA_VIDEO_CALL = "org.appspot.apprtc.VIDEO_CALL";
  public static final String EXTRA_VIDEO_BITRATE = "org.appspot.apprtc.VIDEO_BITRATE";
  public static final String EXTRA_VIDEOCODEC = "org.appspot.apprtc.VIDEOCODEC";
  public static final String EXTRA_AUDIO_BITRATE = "org.appspot.apprtc.AUDIO_BITRATE";
  public static final String EXTRA_AUDIOCODEC = "org.appspot.apprtc.AUDIOCODEC";
  public static final String EXTRA_SAVE_INPUT_AUDIO_TO_FILE_ENABLED = "org.appspot.apprtc.SAVE_INPUT_AUDIO_TO_FILE";
  public static final String EXTRA_VIDEO_FILE_AS_CAMERA = "org.appspot.apprtc.VIDEO_FILE_AS_CAMERA";
  public static final String EXTRA_SAVE_REMOTE_VIDEO_TO_FILE = "org.appspot.apprtc.SAVE_REMOTE_VIDEO_TO_FILE";
  public static final String EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_WIDTH = "org.appspot.apprtc.SAVE_REMOTE_VIDEO_TO_FILE_WIDTH";
  public static final String EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_HEIGHT = "org.appspot.apprtc.SAVE_REMOTE_VIDEO_TO_FILE_HEIGHT";
  public static final String EXTRA_DATA_CHANNEL_ENABLED = "org.appspot.apprtc.DATA_CHANNEL_ENABLED";
  public static final String EXTRA_ORDERED = "org.appspot.apprtc.ORDERED";
  public static final String EXTRA_PROTOCOL = "org.appspot.apprtc.PROTOCOL";

  private static final int CAPTURE_PERMISSION_REQUEST_CODE = 1;

  // List of mandatory application permissions.
  private static final String[] MANDATORY_PERMISSIONS = {
      "android.permission.MODIFY_AUDIO_SETTINGS", "android.permission.RECORD_AUDIO", "android.permission.INTERNET", "android.permission.CAMERA"
  };

  // Peer connection statistics callback period in ms.
  private static final int STAT_CALLBACK_PERIOD = 1000;

  private static class ProxyVideoSink implements VideoSink {
    private VideoSink target;

    @Override
    synchronized public void onFrame(VideoFrame frame) {
      if (target == null) {
        Logging.d(TAG, "Dropping frame in proxy because target is null.");
        return;
      }

      target.onFrame(frame);
    }

    synchronized public void setTarget(VideoSink target) {
      this.target = target;
    }
  }

  private final ProxyVideoSink remoteProxyRenderer = new ProxyVideoSink();
  private final ProxyVideoSink localProxyVideoSink = new ProxyVideoSink();
  @Nullable
  private PeerConnectionClient peerConnectionClient = null;
  @Nullable
  private AppRTCClient appRtcClient;
  @Nullable
  private SignalingParameters signalingParameters;
  @Nullable
  private AppRTCAudioManager audioManager = null;
  @Nullable
  private SurfaceViewRenderer pipRenderer;
  @Nullable
  private SurfaceViewRenderer fullscreenRenderer;
  @Nullable
  private VideoFileRenderer videoFileRenderer;
  private final List<VideoSink> remoteSinks = new ArrayList<>();
  private Toast logToast;
  private boolean activityRunning;
  private RoomConnectionParameters roomConnectionParameters;
  @Nullable
  private PeerConnectionParameters peerConnectionParameters;
  private boolean iceConnected;
  private boolean isError;
  private boolean callControlFragmentVisible = true;
  private long callStartedTimeMs = 0;
  private boolean micEnabled = true;
  // True if local view is in the fullscreen renderer.
  private boolean isSwappedFeeds;

  // Controls
  private CallFragment callFragment;

  /*PiP mode*/
  /** A {@link BroadcastReceiver} to receive action item events from Picture-in-Picture mode. */
  private BroadcastReceiver mReceiver;
  /** Intent action for video call controls from Picture-in-Picture mode. */
  private static final String ACTION_VIDEO_CALL_CONTROL = "video_control";
  /** Intent extra for video call controls from Picture-in-Picture mode. */
  private static final String EXTRA_CONTROL_TYPE = "control_type";
  /** The request code for disconnect call action PendingIntent. */
  private static final int REQUEST_DISCONNECT = 1;
  /** The intent extra value for disconnect action. */
  private static final int CONTROL_TYPE_DISCONNECT = 1;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Thread.setDefaultUncaughtExceptionHandler(new UnhandledExceptionHandler(this));

    // Set window styles for fullscreen-window size. Needs to be done before
    // adding content.
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    getWindow().addFlags(LayoutParams.FLAG_FULLSCREEN | LayoutParams.FLAG_KEEP_SCREEN_ON | LayoutParams.FLAG_SHOW_WHEN_LOCKED | LayoutParams.FLAG_TURN_SCREEN_ON);
    getWindow().getDecorView().setSystemUiVisibility(getSystemUiVisibility());
    setContentView(R.layout.activity_call);

    iceConnected = false;
    signalingParameters = null;

    // Create UI controls.
    pipRenderer = findViewById(R.id.pip_video_view);
    fullscreenRenderer = findViewById(R.id.fullscreen_video_view);
    callFragment = new CallFragment();

    // Show/hide call control fragment on view click.
    View.OnClickListener listener = view -> toggleCallControlFragmentVisibility();

    // Swap feeds on pip view click.
    pipRenderer.setOnClickListener(view -> setSwappedFeeds(!isSwappedFeeds));

    fullscreenRenderer.setOnClickListener(listener);
    remoteSinks.add(remoteProxyRenderer);

    final Intent intent = getIntent();
    final EglBase eglBase = EglBase.create();

    // Create video renderers.
    pipRenderer.init(eglBase.getEglBaseContext(), null);
    pipRenderer.setScalingType(ScalingType.SCALE_ASPECT_FIT);
    String saveRemoteVideoToFile = intent.getStringExtra(EXTRA_SAVE_REMOTE_VIDEO_TO_FILE);

    // When saveRemoteVideoToFile is set we save the video from the remote to a file.
    if (saveRemoteVideoToFile != null) {
      int videoOutWidth = intent.getIntExtra(EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_WIDTH, 0);
      int videoOutHeight = intent.getIntExtra(EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_HEIGHT, 0);
      try {
        videoFileRenderer = new VideoFileRenderer(saveRemoteVideoToFile, videoOutWidth, videoOutHeight, eglBase.getEglBaseContext());
        remoteSinks.add(videoFileRenderer);
      } catch (IOException e) {
        throw new RuntimeException("Failed to open video file for output: " + saveRemoteVideoToFile, e);
      }
    }
    fullscreenRenderer.init(eglBase.getEglBaseContext(), null);
    fullscreenRenderer.setScalingType(ScalingType.SCALE_ASPECT_FILL);

    pipRenderer.setZOrderMediaOverlay(true);
    pipRenderer.setEnableHardwareScaler(true);
    fullscreenRenderer.setEnableHardwareScaler(true);
    // Start with local feed in fullscreen and swap it to the pip when the call is connected.
    setSwappedFeeds(true);

    // Check for mandatory permissions.
    for (String permission : MANDATORY_PERMISSIONS) {
      if (checkCallingOrSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
        logAndToast("Permission " + permission + " is not granted");
        setResult(RESULT_CANCELED);
        finish();
        return;
      }
    }

    Uri roomUri = intent.getData();
    if (roomUri == null) {
      logAndToast(getString(org.appspot.apprtc.R.string.missing_url));
      Log.e(TAG, "Didn't get any URL in intent!");
      setResult(RESULT_CANCELED);
      finish();
      return;
    }

    // Get Intent parameters.
    String roomId = intent.getStringExtra(EXTRA_ROOMID);
    Log.d(TAG, "Room ID: " + roomId);
    if (roomId == null || roomId.length() == 0) {
      logAndToast(getString(org.appspot.apprtc.R.string.missing_url));
      Log.e(TAG, "Incorrect room ID in intent!");
      setResult(RESULT_CANCELED);
      finish();
      return;
    }

    DataChannelParameters dataChannelParameters = null;
    if (intent.getBooleanExtra(EXTRA_DATA_CHANNEL_ENABLED, false)) {
      dataChannelParameters = new DataChannelParameters(intent.getBooleanExtra(EXTRA_ORDERED, true), -1, -1, intent.getStringExtra(EXTRA_PROTOCOL), false, -1);
    }
    peerConnectionParameters =
        new PeerConnectionParameters(intent.getBooleanExtra(EXTRA_VIDEO_CALL, true), false, false, 0, 0, 0, intent.getIntExtra(EXTRA_VIDEO_BITRATE, 0), intent.getStringExtra(EXTRA_VIDEOCODEC), true,
            false, intent.getIntExtra(EXTRA_AUDIO_BITRATE, 0), intent.getStringExtra(EXTRA_AUDIOCODEC), false, false, intent.getBooleanExtra(EXTRA_SAVE_INPUT_AUDIO_TO_FILE_ENABLED, false), false,
            false, false, false, false, false, false, dataChannelParameters);

    Log.d(TAG, "VIDEO_FILE: '" + intent.getStringExtra(EXTRA_VIDEO_FILE_AS_CAMERA) + "'");

    // Create connection client.
    appRtcClient = new WebSocketRTCClient(this);
    // Create connection parameters.
    String urlParameters = intent.getStringExtra(EXTRA_URLPARAMETERS);
    roomConnectionParameters = new RoomConnectionParameters(roomUri.toString(), roomId, urlParameters);

    // Send intent arguments to fragments.
    callFragment.setArguments(intent.getExtras());
    // Activate call fragments and start the call.
    FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
    ft.add(R.id.call_fragment_container, callFragment);
    ft.commit();

    // Create peer connection client.
    peerConnectionClient = new PeerConnectionClient(getApplicationContext(), eglBase, peerConnectionParameters, CallActivity.this);
    PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
    peerConnectionClient.createPeerConnectionFactory(options);

    startCall();
  }

  @TargetApi(19)
  private static int getSystemUiVisibility() {
    int flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
    }
    return flags;
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent data) {
    if (requestCode != CAPTURE_PERMISSION_REQUEST_CODE) return;
    startCall();
  }

  private @Nullable
  VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
    final String[] deviceNames = enumerator.getDeviceNames();

    // First, try to find front facing camera
    Logging.d(TAG, "Looking for front facing cameras.");
    for (String deviceName : deviceNames) {
      if (enumerator.isFrontFacing(deviceName)) {
        Logging.d(TAG, "Creating front facing camera capturer.");
        VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

        if (videoCapturer != null) {
          return videoCapturer;
        }
      }
    }

    // Front facing camera not found, try something else
    Logging.d(TAG, "Looking for other cameras.");
    for (String deviceName : deviceNames) {
      if (!enumerator.isFrontFacing(deviceName)) {
        Logging.d(TAG, "Creating other camera capturer.");
        VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

        if (videoCapturer != null) {
          return videoCapturer;
        }
      }
    }

    return null;
  }

  // Activity interfaces
  @Override
  public void onStop() {
    super.onStop();
    activityRunning = false;
    // Don't stop the video when using screencapture to allow user to show other apps to the remote
    // end.
    if (peerConnectionClient != null) {
      peerConnectionClient.stopVideoSource();
    }
  }

  @Override
  public void onStart() {
    super.onStart();
    activityRunning = true;
    if (peerConnectionClient != null) {
      peerConnectionClient.startVideoSource();
    }
  }

  @Override
  protected void onDestroy() {
    Thread.setDefaultUncaughtExceptionHandler(null);
    disconnect();
    if (logToast != null) {
      logToast.cancel();
    }
    activityRunning = false;
    super.onDestroy();
  }

  // CallFragment.OnCallEvents interface implementation.
  @Override
  public void onCallHangUp() {
    disconnect();
  }

  @Override
  public void onCameraSwitch() {
    if (peerConnectionClient != null) {
      peerConnectionClient.switchCamera();
    }
  }

  @Override
  public void onVideoScalingSwitch(ScalingType scalingType) {
    fullscreenRenderer.setScalingType(scalingType);
  }

  @Override
  public boolean onToggleMic() {
    if (peerConnectionClient != null) {
      micEnabled = !micEnabled;
      peerConnectionClient.setAudioEnabled(micEnabled);
    }
    return micEnabled;
  }

  // Helper functions.
  private void toggleCallControlFragmentVisibility() {
    if (!iceConnected || !callFragment.isAdded()) {
      return;
    }
    // Show/hide call control fragment
    callControlFragmentVisible = !callControlFragmentVisible;
    FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
    if (callControlFragmentVisible) {
      ft.show(callFragment);
    } else {
      ft.hide(callFragment);
    }
    ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
    ft.commit();
  }

  private void startCall() {
    if (appRtcClient == null) {
      Log.e(TAG, "AppRTC client is not allocated for a call.");
      return;
    }
    callStartedTimeMs = System.currentTimeMillis();

    // Start room connection.
    logAndToast(getString(org.appspot.apprtc.R.string.connecting_to, roomConnectionParameters.roomUrl));
    appRtcClient.connectToRoom(roomConnectionParameters);

    // Create and audio manager that will take care of audio routing,
    // audio modes, audio device enumeration etc.
    audioManager = AppRTCAudioManager.create(getApplicationContext());
    // Store existing audio settings and change audio mode to
    // MODE_IN_COMMUNICATION for best possible VoIP performance.
    Log.d(TAG, "Starting the audio manager...");
    // This method will be called each time the number of available audio
    // devices has changed.
    audioManager.start((audioDevice, availableAudioDevices) -> onAudioManagerDevicesChanged(audioDevice, availableAudioDevices));
  }

  // Should be called from UI thread
  private void callConnected() {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;
    Log.i(TAG, "Call connected: delay=" + delta + "ms");
    if (peerConnectionClient == null || isError) {
      Log.w(TAG, "Call is connected in closed or error state");
      return;
    }
    // Enable statistics callback.
    peerConnectionClient.enableStatsEvents(true, STAT_CALLBACK_PERIOD);
    setSwappedFeeds(false /* isSwappedFeeds */);
  }

  // This method is called when the audio manager reports audio device change,
  // e.g. from wired headset to speakerphone.
  private void onAudioManagerDevicesChanged(final AudioDevice device, final Set<AudioDevice> availableDevices) {
    Log.d(TAG, "onAudioManagerDevicesChanged: " + availableDevices + ", " + "selected: " + device);
    // TODO(henrika): add callback handler.
  }

  // Disconnect from remote resources, dispose of local resources, and exit.
  private void disconnect() {
    activityRunning = false;
    remoteProxyRenderer.setTarget(null);
    localProxyVideoSink.setTarget(null);
    if (appRtcClient != null) {
      appRtcClient.disconnectFromRoom();
      appRtcClient = null;
    }
    if (pipRenderer != null) {
      pipRenderer.release();
      pipRenderer = null;
    }
    if (videoFileRenderer != null) {
      videoFileRenderer.release();
      videoFileRenderer = null;
    }
    if (fullscreenRenderer != null) {
      fullscreenRenderer.release();
      fullscreenRenderer = null;
    }
    if (peerConnectionClient != null) {
      peerConnectionClient.close();
      peerConnectionClient = null;
    }
    if (audioManager != null) {
      audioManager.stop();
      audioManager = null;
    }
    if (iceConnected && !isError) {
      setResult(RESULT_OK);
    } else {
      setResult(RESULT_CANCELED);
    }
    finish();
  }

  private void disconnectWithErrorMessage(final String errorMessage) {
    if (!activityRunning) {
      Log.e(TAG, "Critical error: " + errorMessage);
      disconnect();
    } else {
      new AlertDialog.Builder(this).setTitle(getText(org.appspot.apprtc.R.string.channel_error_title))
          .setMessage(errorMessage)
          .setCancelable(false)
          .setNeutralButton(org.appspot.apprtc.R.string.ok, (dialog, id) -> {
            dialog.cancel();
            disconnect();
          })
          .create()
          .show();
    }
  }

  // Log |msg| and Toast about it.
  private void logAndToast(String msg) {
    Log.d(TAG, msg);
    if (logToast != null) {
      logToast.cancel();
    }
    logToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
    logToast.show();
  }

  private void reportError(final String description) {
    runOnUiThread(() -> {
      if (!isError) {
        isError = true;
        disconnectWithErrorMessage(description);
      }
    });
  }

  private @Nullable
  VideoCapturer createVideoCapturer() {
    final VideoCapturer videoCapturer;
    String videoFileAsCamera = getIntent().getStringExtra(EXTRA_VIDEO_FILE_AS_CAMERA);
    if (videoFileAsCamera != null) {
      try {
        videoCapturer = new FileVideoCapturer(videoFileAsCamera);
      } catch (IOException e) {
        reportError("Failed to open video file for emulated camera");
        return null;
      }
    } else {
      Logging.d(TAG, "Creating capturer using camera1 API.");
      videoCapturer = createCameraCapturer(new Camera1Enumerator(false));
    }
    if (videoCapturer == null) {
      reportError("Failed to open camera");
      return null;
    }
    return videoCapturer;
  }

  private void setSwappedFeeds(boolean isSwappedFeeds) {
    Logging.d(TAG, "setSwappedFeeds: " + isSwappedFeeds);
    this.isSwappedFeeds = isSwappedFeeds;
    localProxyVideoSink.setTarget(isSwappedFeeds ? fullscreenRenderer : pipRenderer);
    remoteProxyRenderer.setTarget(isSwappedFeeds ? pipRenderer : fullscreenRenderer);
    fullscreenRenderer.setMirror(isSwappedFeeds);
    pipRenderer.setMirror(!isSwappedFeeds);
  }

  // -----Implementation of AppRTCClient.AppRTCSignalingEvents ---------------
  // All callbacks are invoked from websocket signaling looper thread and
  // are routed to UI thread.
  private void onConnectedToRoomInternal(final SignalingParameters params) {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;

    signalingParameters = params;
    logAndToast("Creating peer connection, delay=" + delta + "ms");
    VideoCapturer videoCapturer = null;
    if (peerConnectionParameters.videoCallEnabled) {
      videoCapturer = createVideoCapturer();
    }
    peerConnectionClient.createPeerConnection(localProxyVideoSink, remoteSinks, videoCapturer, signalingParameters);

    if (signalingParameters.initiator) {
      logAndToast("Creating OFFER...");
      // Create offer. Offer SDP will be sent to answering client in
      // PeerConnectionEvents.onLocalDescription event.
      peerConnectionClient.createOffer();
    } else {
      if (params.offerSdp != null) {
        peerConnectionClient.setRemoteDescription(params.offerSdp);
        logAndToast("Creating ANSWER...");
        // Create answer. Answer SDP will be sent to offering client in
        // PeerConnectionEvents.onLocalDescription event.
        peerConnectionClient.createAnswer();
      }
      if (params.iceCandidates != null) {
        // Add remote ICE candidates from room.
        for (IceCandidate iceCandidate : params.iceCandidates) {
          peerConnectionClient.addRemoteIceCandidate(iceCandidate);
        }
      }
    }
  }

  @Override
  public void onConnectedToRoom(final SignalingParameters params) {
    runOnUiThread(() -> onConnectedToRoomInternal(params));
  }

  @Override
  public void onRemoteDescription(final SessionDescription sdp) {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;
    runOnUiThread(() -> {
      if (peerConnectionClient == null) {
        Log.e(TAG, "Received remote SDP for non-initilized peer connection.");
        return;
      }
      logAndToast("Received remote " + sdp.type + ", delay=" + delta + "ms");
      peerConnectionClient.setRemoteDescription(sdp);
      if (!signalingParameters.initiator) {
        logAndToast("Creating ANSWER...");
        // Create answer. Answer SDP will be sent to offering client in
        // PeerConnectionEvents.onLocalDescription event.
        peerConnectionClient.createAnswer();
      }
    });
  }

  @Override
  public void onRemoteIceCandidate(final IceCandidate candidate) {
    runOnUiThread(() -> {
      if (peerConnectionClient == null) {
        Log.e(TAG, "Received ICE candidate for a non-initialized peer connection.");
        return;
      }
      peerConnectionClient.addRemoteIceCandidate(candidate);
    });
  }

  @Override
  public void onRemoteIceCandidatesRemoved(final IceCandidate[] candidates) {
    runOnUiThread(() -> {
      if (peerConnectionClient == null) {
        Log.e(TAG, "Received ICE candidate removals for a non-initialized peer connection.");
        return;
      }
      peerConnectionClient.removeRemoteIceCandidates(candidates);
    });
  }

  @Override
  public void onChannelClose() {
    runOnUiThread(() -> {
      logAndToast("Remote end hung up; dropping PeerConnection");
      disconnect();
    });
  }

  @Override
  public void onChannelError(final String description) {
    reportError(description);
  }

  // -----Implementation of PeerConnectionClient.PeerConnectionEvents.---------
  // Send local peer connection SDP and ICE candidates to remote party.
  // All callbacks are invoked from peer connection client looper thread and
  // are routed to UI thread.
  @Override
  public void onLocalDescription(final SessionDescription sdp) {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;
    runOnUiThread(() -> {
      if (appRtcClient != null) {
        logAndToast("Sending " + sdp.type + ", delay=" + delta + "ms");
        if (signalingParameters.initiator) {
          appRtcClient.sendOfferSdp(sdp);
        } else {
          appRtcClient.sendAnswerSdp(sdp);
        }
      }
      if (peerConnectionParameters.videoMaxBitrate > 0) {
        Log.d(TAG, "Set video maximum bitrate: " + peerConnectionParameters.videoMaxBitrate);
        peerConnectionClient.setVideoMaxBitrate(peerConnectionParameters.videoMaxBitrate);
      }
    });
  }

  @Override
  public void onIceCandidate(final IceCandidate candidate) {
    runOnUiThread(() -> {
      if (appRtcClient != null) {
        appRtcClient.sendLocalIceCandidate(candidate);
      }
    });
  }

  @Override
  public void onIceCandidatesRemoved(final IceCandidate[] candidates) {
    runOnUiThread(() -> {
      if (appRtcClient != null) {
        appRtcClient.sendLocalIceCandidateRemovals(candidates);
      }
    });
  }

  @Override
  public void onIceConnected() {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;
    runOnUiThread(() -> {
      logAndToast("ICE connected, delay=" + delta + "ms");
      iceConnected = true;
      callConnected();
    });
  }

  @Override
  public void onIceDisconnected() {
    runOnUiThread(() -> {
      logAndToast("ICE disconnected");
      iceConnected = false;
      disconnect();
    });
  }

  @Override
  public void onPeerConnectionClosed() {
  }

  @Override
  public void onPeerConnectionStatsReady(final StatsReport[] reports) {
    runOnUiThread(() -> {
      if (!isError && iceConnected) {
        Log.d(TAG, Arrays.toString(reports));
      }
    });
  }

  @Override
  public void onPeerConnectionError(final String description) {
    reportError(description);
  }

  @Override
  protected void onUserLeaveHint() {
    super.onUserLeaveHint();
    enterPipMode(R.drawable.disconnect, getString(R.string.disconnect_call), CONTROL_TYPE_DISCONNECT, REQUEST_DISCONNECT);
  }

  @Override
  public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
    if (isInPictureInPictureMode) {

      toggleUi(View.GONE);
      // Starts receiving events from action items in PiP mode.
      mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          if (intent == null || !ACTION_VIDEO_CALL_CONTROL.equals(intent.getAction())) {
            return;
          }

          // This is where we are called back from Picture-in-Picture action
          // items.
          final int controlType = intent.getIntExtra(EXTRA_CONTROL_TYPE, 0);
          switch (controlType) {
            case CONTROL_TYPE_DISCONNECT:
              onCallHangUp();
              break;
          }
        }
      };
      registerReceiver(mReceiver, new IntentFilter(ACTION_VIDEO_CALL_CONTROL));
    } else {

      toggleUi(View.VISIBLE);
      // We are out of PiP mode. We can stop receiving events from it.
      unregisterReceiver(mReceiver);
      mReceiver = null;
      // Show the video controls if the video is not playing
    }
  }

  /**
   * @param iconId The icon to be used.
   * @param title The title text.
   * @param controlType The type of the action. {@link #CONTROL_TYPE_DISCONNECT}
   * @param requestCode The request code for the {@link PendingIntent}.
   */
  @TargetApi(Build.VERSION_CODES.O)
  void enterPipMode(int iconId, String title, int controlType, int requestCode) {

    PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();

    final ArrayList<RemoteAction> actions = new ArrayList<>();

    Intent videoControlIntent = new Intent(ACTION_VIDEO_CALL_CONTROL);
    videoControlIntent.putExtra(EXTRA_CONTROL_TYPE, controlType);
    // This is the PendingIntent that is invoked when a user clicks on the action item.
    final PendingIntent intent = PendingIntent.getBroadcast(CallActivity.this, requestCode, videoControlIntent, 0);
    final Icon icon = Icon.createWithResource(CallActivity.this, iconId);
    actions.add(new RemoteAction(icon, title, title, intent));

    builder.setActions(actions);
    PictureInPictureParams pictureParams = builder.build();
    setPictureInPictureParams(pictureParams);
    enterPictureInPictureMode(pictureParams);
  }

  private void toggleUi(int visibility) {
    if (pipRenderer != null) {
      pipRenderer.setVisibility(visibility);
    }
    if (callFragment != null) {
      callFragment.toggleContainerVisibility(visibility);
    }
  }
}
