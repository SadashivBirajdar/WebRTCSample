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

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.appspot.apprtc.R;
import org.webrtc.RendererCommon.ScalingType;

/**
 * Fragment for call control.
 */
public class CallFragment extends Fragment {
  private TextView contactView;
  private ImageView cameraSwitchButton;
  private ImageView toggleMuteButton;
  private LinearLayout mButtonContainer;
  private OnCallEvents callEvents;
  private boolean videoCallEnabled = true;
  //  private ImageButton videoScalingButton;
  // private ScalingType scalingType;

  /**
   * Call control interface for container activity.
   */
  public interface OnCallEvents {
    void onCallHangUp();

    void onCameraSwitch();

    void onVideoScalingSwitch(ScalingType scalingType);

    boolean onToggleMic();
  }

  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    View controlView = inflater.inflate(R.layout.fragment_call, container, false);

    // Create UI controls.
    contactView = controlView.findViewById(R.id.contact_name_call);
    mButtonContainer = controlView.findViewById(R.id.buttons_call_container);
    ImageView disconnectButton = controlView.findViewById(R.id.button_call_disconnect);
    cameraSwitchButton = controlView.findViewById(R.id.button_call_switch_camera);
    toggleMuteButton = controlView.findViewById(R.id.button_call_toggle_mic);

    // Add buttons click events.
    disconnectButton.setOnClickListener(view -> callEvents.onCallHangUp());

    cameraSwitchButton.setOnClickListener(view -> callEvents.onCameraSwitch());

   /* videoScalingButton = controlView.findViewById(R.id.button_call_scaling_mode);
    videoScalingButton.setOnClickListener(view -> {
      if (scalingType == ScalingType.SCALE_ASPECT_FILL) {
        videoScalingButton.setBackgroundResource(R.drawable.ic_action_full_screen);
        scalingType = ScalingType.SCALE_ASPECT_FIT;
      } else {
        videoScalingButton.setBackgroundResource(R.drawable.ic_action_return_from_full_screen);
        scalingType = ScalingType.SCALE_ASPECT_FILL;
      }
      callEvents.onVideoScalingSwitch(scalingType);
    });
    scalingType = ScalingType.SCALE_ASPECT_FILL; */

    toggleMuteButton.setOnClickListener(view -> {
      boolean enabled = callEvents.onToggleMic();
      toggleMuteButton.setAlpha(enabled ? 1.0f : 0.3f);
    });

    return controlView;
  }

  @Override
  public void onStart() {
    super.onStart();
    Bundle args = getArguments();
    if (args != null) {
      String contactName = args.getString(CallActivity.EXTRA_ROOMID);
      contactView.setText(contactName);
      videoCallEnabled = args.getBoolean(CallActivity.EXTRA_VIDEO_CALL, true);
    }
    if (!videoCallEnabled) {
      cameraSwitchButton.setVisibility(View.GONE);
    }
  }

  @Override
  public void onAttach(Context context) {
    super.onAttach(context);
    callEvents = (OnCallEvents) context;
  }

  void toggleContainerVisibility(int visibility) {
    mButtonContainer.setVisibility(visibility);
  }
}
