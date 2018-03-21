package com.droidlogic.app.tv;

import android.content.Context;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiTvClient;
import android.hardware.hdmi.HdmiTvClient.SelectCallback;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.util.Log;

import android.media.tv.TvInputHardwareInfo;
import android.media.tv.TvInputManager;
import java.util.List;
import java.util.ArrayList;
import android.os.Handler;
import android.os.Message;

public class DroidLogicHdmiCecManager {
    private static final String TAG = "DroidLogicHdmiCecManager";

    private Context mContext;
    private HdmiControlManager mHdmiControlManager;
    private HdmiTvClient mTvClient;
    private int mSelectDeviceId = -1;
    private int mSelectLogicAddr = -1;
    private int mSourceType = 0;

    private final Object mLock = new Object();

    private static DroidLogicHdmiCecManager mInstance = null;
    private TvInputManager mTvInputManager;
    private static boolean DEBUG = false;
    private static final int CALLBACK_HANDLE_FAIL = 1 << 16;
    private static final int DELAYMILIS = 200;
    private static final int HDMI_DEVICE_SELECT = 2 << 16;
    private int DEV_TYPE_AUDIO_SYSTEM = 5;
    private int DEV_TYPE_TUNER = 3;
    private final Handler mHandler = new Handler () {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case CALLBACK_HANDLE_FAIL:
                    Log.d(TAG, "select device fail, onComplete result = " + msg.arg1 + ", mSelectDeviceId = 0");
                    break;
                case HDMI_DEVICE_SELECT:
                    Log.d(TAG, "deviceSelect begin, logicAddr = " + msg.arg1);
                    deviceSelect((int)msg.arg1);
                    break;

            }
        }
    };
    public static synchronized DroidLogicHdmiCecManager getInstance(Context context) {
        if (mInstance == null) {
            Log.d(TAG, "mInstance is null...");
            mInstance = new DroidLogicHdmiCecManager(context);
        }
        return mInstance;
    }

    public DroidLogicHdmiCecManager(Context context) {
        mContext = context;
        mHdmiControlManager = (HdmiControlManager) context.getSystemService(Context.HDMI_CONTROL_SERVICE);

        if (mHdmiControlManager != null)
            mTvClient = mHdmiControlManager.getTvClient();


        if (mTvInputManager == null)
            mTvInputManager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
    }

    /**
     * select hdmi cec device.
     * @param deviceId defined in {@link DroidLogicTvUtils} {@code DEVICE_ID_HDMI1} {@code DEVICE_ID_HDMI2}
     * {@code DEVICE_ID_HDMI3} or 0(TV).
     * @return {@value true} indicates has select device successfully, otherwise {@value false}.
     */
    public boolean selectHdmiDevice(final int deviceId, int logicAddr, int phyAddr) {
        /*invoke case
        * 1. hot plug in the same channel
        * 2. hot plug sub device in the same parent channel
        * 3. alter to tv itself
        */
        getInputSourceType();

        Log.d(TAG, "selectHdmiDevice"
            + ", deviceId = " + deviceId
            + ", mSelectDeviceId = " + mSelectDeviceId
            + ", mSourceType = " + mSourceType);

        boolean cecOption = (Global.getInt(mContext.getContentResolver(), Global.HDMI_CONTROL_ENABLED, 1) == 1);
        if (!cecOption || mTvClient == null || mHdmiControlManager == null) {
            Log.d(TAG, "mTvClient or mHdmiControlManager maybe null,or cec not enable, return");
            return false;
        }
        boolean isSubDevice =  ((phyAddr & 0xfff) != 0) ? true : false;
        if (mSelectDeviceId > 0) {
            if (deviceId > 0) {
                if (mSelectDeviceId == deviceId) {
                    if (!isSubDevice) {
                        if ((mSelectLogicAddr == 0 && logicAddr != 0) || (mSelectLogicAddr != 0 && logicAddr == 0)) {
                            if ((isAvrDevice(deviceId)) || (logicAddr == DEV_TYPE_TUNER && isAvrDevice(deviceId))) {
                                Log.d(TAG, "it is avr, or a compasite avr and no sub device, return");
                                return false;
                            }
                            Log.d(TAG, "hot plug device in the same channel, continue");
                        }
                        if (mSelectLogicAddr == logicAddr) {
                            Log.d(TAG, "logic addr has added in the same channel, return");
                            return false;
                        } else {
                            Log.d(TAG, "logic addr may alter in the same channel , continue");
                        }
                    } else {
                        if (mSelectLogicAddr == logicAddr) {
                            Log.d(TAG, "same sub device has added in the current input, return");
                            return false;
                        }
                        if ((mSelectLogicAddr == 0 && logicAddr != 0) || (mSelectLogicAddr != 0 && logicAddr == 0)) {
                            Log.d(TAG, "hot plug sub evice in the same parent channel, continue");
                        }
                    }
                } else {
                    Log.d(TAG, "not in current channel, do nothing, return");
                    return false;
                }
            } else if (deviceId == 0){
                Log.d(TAG, "change to home, continue");
            } else {
                Log.d(TAG, "deviceId is invalid, return");
                return false;
            }
            synchronized (mLock) {
                mSelectDeviceId = deviceId;
                mSelectLogicAddr = logicAddr;
            }
        } else if(mSelectDeviceId == 0) {
            Log.d(TAG, "It is current at home, do nothing, return");
            return false;
        } else {
            /*if com.droidlogic.tvinput crash and add hdmidevice, should not select device
            * hot plug should not be affected
            */
            if (mSelectDeviceId < 0) {
                Log.d(TAG, "mSelectDeviceId is -1, return");
                return false;
            }
        }
        Log.d(TAG, "mHandler handle logicAddr: " + logicAddr + " device begin");
        mHandler.removeMessages(HDMI_DEVICE_SELECT);
        Message msg = mHandler.obtainMessage(HDMI_DEVICE_SELECT, logicAddr, 0);
        mHandler.sendMessageDelayed(msg, DELAYMILIS);
        return true;
    }

    private void deviceSelect(int logicAddr) {
        mTvClient.deviceSelect(logicAddr, new SelectCallback() {
            @Override
            public void onComplete(int result) {
                if (result != HdmiControlManager.RESULT_SUCCESS)
                    mHandler.obtainMessage(CALLBACK_HANDLE_FAIL, result, 0).sendToTarget();
                else {
                    Log.d(TAG, "select device success, onComplete result = " + result);
                }
            }
        });
    }

    public boolean selectHdmiDevice(final int deviceId) {
        return selectHdmiDevice(deviceId, 0, 0);
    }

    public boolean connectHdmiCec(int deviceId) {
        /* this first enter into channel or focus home icon of avr may be invoke*/
        getInputSourceType();

        Log.d(TAG, "connectHdmiCec"+ ", deviceId = " + deviceId
            + ", mSelectDeviceId = " + mSelectDeviceId+ ", mSourceType = " + mSourceType);

        boolean cecOption = (Global.getInt(mContext.getContentResolver(), Global.HDMI_CONTROL_ENABLED, 1) == 1);
        if (!cecOption || mTvClient == null || mHdmiControlManager == null) {
            Log.d(TAG, "mTvClient or mHdmiControlManager maybe null,or cec not enable, return");
            return false;
        }
        int portId = getPortIdByDeviceId(deviceId);
        if (mSelectDeviceId == deviceId || portId == 0) {
            Log.d(TAG, "connectHdmiCec,but mSelectDeviceId == deviceId or portId = 0, return");
            return false;
        }

        synchronized (mLock) {
            mSelectDeviceId = deviceId;
            mSelectLogicAddr = deviceId;
            if (isAvrDevice(deviceId)) {
                /*for avr, should not portSelect when browse small window
                *when enter channel, selectDecive should do in start tv,because must send SetStream Path
                */
                Log.d(TAG, "it is avr, return");
                return false;
            }
        }
        Log.d(TAG, "TvClient portSelect begin, portId: " + portId);
        mTvClient.portSelect(portId , new SelectCallback() {
            @Override
            public void onComplete(int result) {
                if (result != HdmiControlManager.RESULT_SUCCESS)
                    mHandler.obtainMessage(CALLBACK_HANDLE_FAIL, result, 0).sendToTarget();
                else {
                    Log.d(TAG, "select port success, onComplete result = " + result);
                }

            }
        });

        return true;
    }

    public int getSelectDeviceId(){
        return mSelectDeviceId;
    }

    private void setSelectDeviceId(int deviceId){
        synchronized (mLock) {
            mSelectDeviceId = deviceId ;
        }

    }

    public int getPortIdByDeviceId(int deviceId){
        List<TvInputHardwareInfo> hardwareList = mTvInputManager.getHardwareList();
        if (hardwareList == null || hardwareList.size() == 0)
            return -1;

        for (TvInputHardwareInfo hardwareInfo : hardwareList) {
            if (DEBUG)
                Log.d(TAG, "getPortIdByDeviceId: " + hardwareInfo);
            if (deviceId == hardwareInfo.getDeviceId())
                return hardwareInfo.getHdmiPortId();
        }
        return -1;
    }

    public boolean isAvrDevice(int deviceId) {
        if (deviceId >= DroidLogicTvUtils.DEVICE_ID_HDMI1 && deviceId <= DroidLogicTvUtils.DEVICE_ID_HDMI4) {
            int id = getPortIdByDeviceId(deviceId);
            for (HdmiDeviceInfo info : mTvClient.getDeviceList()) {
                /*this only get firt level device logical addr*/
                if (id == (info.getPhysicalAddress() >> 12) && (info.getLogicalAddress() == DEV_TYPE_AUDIO_SYSTEM)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isCompositeDev(int deviceId) {
        int count = 0;
        /*a devie maybe have different logic addr, but same phy addr*/
        if (deviceId >= DroidLogicTvUtils.DEVICE_ID_HDMI1 && deviceId <= DroidLogicTvUtils.DEVICE_ID_HDMI4) {
            int id = getPortIdByDeviceId(deviceId);
            for (HdmiDeviceInfo info : mTvClient.getDeviceList()) {
                /*this only get firt level device logical addr*/
                if (id == (info.getPhysicalAddress() >> 12) && ((info.getPhysicalAddress() & 0xfff) == 0)) {
                    count++;
                }
            }
        }
        return (count > 1) ? true : false;
    }

    public boolean hasHdmiCecDevice(int deviceId) {
        if (deviceId >= DroidLogicTvUtils.DEVICE_ID_HDMI1 && deviceId <= DroidLogicTvUtils.DEVICE_ID_HDMI4) {
            int id = getPortIdByDeviceId(deviceId);
            for (HdmiDeviceInfo info : mTvClient.getDeviceList()) {
                if (id == (info.getPhysicalAddress() >> 12)) {
                    return true;
                }
            }
        }
        return false;
    }

    public int getInputSourceType() {
        synchronized (mLock) {
            mSourceType = Settings.System.getInt(mContext.getContentResolver(), DroidLogicTvUtils.TV_CURRENT_DEVICE_ID, 0);
            return mSourceType;
        }
    }
}
