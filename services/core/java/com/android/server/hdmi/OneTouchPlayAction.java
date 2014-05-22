/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.hdmi;

import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.hdmi.HdmiCec;
import android.hardware.hdmi.HdmiCecMessage;
import android.os.RemoteException;
import android.util.Slog;

/**
 * Feature action that performs one touch play against TV/Display device.
 *
 * This action is initiated via {@link HdmiControlManager#oneTouchPlay()} from
 * the Android system working as playback device to turn on the TV, and switch the input.
 *
 * <p>Package-private, accessed by {@link HdmiControlService} only.
 */

public final class OneTouchPlayAction extends FeatureAction {
    private static final String TAG = "OneTouchPlayAction";

    // State in which the action is waiting for <Report Power Status>. In normal situation
    // source device can simply send <Text|Image View On> and <Active Source> in succession
    // since the standard requires that the TV/Display should buffer the <Active Source>
    // if the TV is brought of out standby state.
    //
    // But there are TV's that fail to buffer the <Active Source> while getting out of
    // standby mode, and do not accept the command until their power status becomes 'ON'.
    // For a workaround, we send <Give Device Power Status> commands periodically to make sure
    // the device switches its status to 'ON'. Then we send additional <Active Source>.
    private static final int STATE_WAITING_FOR_REPORT_POWER_STATUS = 1;

    // The maximum number of times we send <Give Device Power Status> before we give up.
    // We wait up to RESPONSE_TIMEOUT_MS * LOOP_COUNTER_MAX = 20 seconds.
    private static final int LOOP_COUNTER_MAX = 10;

    private final int mSourcePath;
    private final int mTargetAddress;
    private final IHdmiControlCallback mCallback;

    private int mPowerStatusCounter = 0;

    // Factory method. Ensures arguments are valid.
    static OneTouchPlayAction create(HdmiControlService service, int sourceAddress,
            int sourcePath, int targetAddress, IHdmiControlCallback callback) {
        if (service == null || callback == null) {
            Slog.e(TAG, "Wrong arguments");
            return null;
        }
        return new OneTouchPlayAction(service, sourceAddress, sourcePath, targetAddress, callback);
    }

    private OneTouchPlayAction(HdmiControlService service, int sourceAddress, int sourcePath,
            int targetAddress, IHdmiControlCallback callback) {
        super(service, sourceAddress);
        mSourcePath = sourcePath;
        mTargetAddress = targetAddress;
        mCallback = callback;
    }

    @Override
    boolean start() {
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildTextViewOn(mSourceAddress, mTargetAddress));
        broadcastActiveSource();
        queryDevicePowerStatus();
        mState = STATE_WAITING_FOR_REPORT_POWER_STATUS;
        addTimer(mState, FeatureAction.TIMEOUT_MS);
        return true;
    }

    private void broadcastActiveSource() {
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildActiveSource(mSourceAddress, mSourcePath));
    }

    private void queryDevicePowerStatus() {
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildGiveDevicePowerStatus(mSourceAddress, mTargetAddress));
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (mState != STATE_WAITING_FOR_REPORT_POWER_STATUS) {
            return false;
        }
        if (cmd.getOpcode() == HdmiCec.MESSAGE_REPORT_POWER_STATUS) {
            int status = cmd.getParams()[0];
            if (status == HdmiCec.POWER_STATUS_ON) {
                broadcastActiveSource();
                invokeCallback(HdmiCec.RESULT_SUCCESS);
                finish();
            }
            return true;
        }
        return false;
    }

    @Override
    void handleTimerEvent(int state) {
        if (mState != state) {
            return;
        }
        if (state == STATE_WAITING_FOR_REPORT_POWER_STATUS) {
            if (mPowerStatusCounter++ < LOOP_COUNTER_MAX) {
                queryDevicePowerStatus();
                addTimer(mState, FeatureAction.TIMEOUT_MS);
            } else {
                // Couldn't wake up the TV for whatever reason. Report failure.
                invokeCallback(HdmiCec.RESULT_TIMEOUT);
                finish();
            }
        }
    }

    private void invokeCallback(int result) {
        try {
            mCallback.onComplete(result);
        } catch (RemoteException e) {
            Slog.e(TAG, "Callback failed:" + e);
        }
    }
}
