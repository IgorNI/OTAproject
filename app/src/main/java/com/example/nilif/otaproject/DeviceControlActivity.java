package com.example.nilif.otaproject;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ExpandableListView;
import android.widget.SimpleExpandableListAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * @author nilif
 * @date 2016/9/1 03:16
 */
public class DeviceControlActivity extends Activity implements View.OnClickListener {
    private final static String TAG = DeviceControlActivity.class.getSimpleName();

    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";
    private static final String FW_FILE_V2 = "new_oad_img_e_v2.bin"; // V2的文件
    private static final String FW_FILE_V3 = "new_oad_img_e_v3.bin"; // V3的文件
    private static final int BLOCKS_PER_CONNECTION = 20; // May sent up to four blocks per connection
    private static final int FILE_BUFFER_SIZE = 0x40000;

    private static final int OAD_BLOCK_SIZE = 16;
    private static final int OAD_BUFFER_SIZE = 2 + OAD_BLOCK_SIZE;
    private static final int HAL_FLASH_WORD_SIZE = 4;
    private static final long TIMER_INTERVAL = 1000;

    private TextView mConnectionState;
    private TextView mDataField;
    private String mDeviceName;
    private String mDeviceAddress;
    private ExpandableListView mGattServicesList;
    private BluetoothLeService mBluetoothLeService;
    private ArrayList<ArrayList<BluetoothGattCharacteristic>> mGattCharacteristics =
            new ArrayList<ArrayList<BluetoothGattCharacteristic>>();
    private boolean mConnected = false;
    private BluetoothGattCharacteristic mNotifyCharacteristic;

    private final String LIST_NAME = "NAME";
    private final String LIST_UUID = "UUID";

    // 定义ota所需的变量
    private BluetoothGattService mOTAService;
    private BluetoothGattService mConnControlService;
    private List<BluetoothGattCharacteristic> mCharListOad;
    private List<BluetoothGattCharacteristic> mCharListCc;
    private BluetoothGattCharacteristic mCharIdentify = null;
    private BluetoothGattCharacteristic mCharBlock = null;


    // Housekeeping
    private boolean mServiceOk = false;
    private boolean mProgramming = false;
    private boolean mTestOK = false;
    private static IntentFilter mIntentFilter;

    private final byte[] mFileBuffer = new byte[FILE_BUFFER_SIZE];
    private final byte[] mOadBuffer = new byte[OAD_BUFFER_SIZE];
    private List<FirmwareEntry> fwEntries;
    private AlertDialog.Builder testFailedAlertDialog;
    private DeviceControlActivity.ProgInfo mProgInfo = new ProgInfo();
    private Timer mTimer = null;
    private TimerTask mTimerTask = null;



    // Code to manage Service lifecycle.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finish();
            }
            // Automatically connects to the device upon successful start-up initialization.
            mBluetoothLeService.connect(mDeviceAddress);

        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };
    private boolean slowAlgo = true;
    private int packetsSent = 0;
    private int fastAlgoMaxPackets = BLOCKS_PER_CONNECTION;
    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read
    //                        or notification operations.
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mConnected = true;
                Log.e(TAG, "Ble connected");
                updateConnectionState(R.string.connected);
                invalidateOptionsMenu();
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                mConnected = false;
                updateConnectionState(R.string.disconnected);
                Log.e(TAG, "Ble disconnected");
                invalidateOptionsMenu();
                clearUI();
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Show all the supported services and characteristics on the user interface.
                displayGattServices(mBluetoothLeService.getSupportedGattServices());
                Log.e(TAG, "services discovered");
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                Log.e(TAG, "characteristic read");
                displayData(intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
            } else if (BluetoothLeService.ACTION_DATA_NOTIFY.equals(action)) {
                Log.e(TAG, "Service notify");
                byte[] value = intent.getByteArrayExtra(BluetoothLeService.EXTRA_DATA);
                String uuidStr = intent.getStringExtra(BluetoothLeService.EXTRA_UUID);
                if (uuidStr.equals(mCharIdentify.getUuid().toString())) {
                    Log.e(TAG, "mCharIdentify notify");
                    // TODO: 2016/9/8 验证是否合理 
                    // TODO: 2016/9/8 是，执行ProgramBlock（） 
                }
                if (uuidStr.equals(mCharBlock.getUuid().toString())) {
                    Log.e(TAG, "Block notify");
                    String block = String.format("%02x%02x", value[1], value[0]);
                    Log.e("FwUpdateActivity_CC26xx :", "Received block req: " + block);
                    if (slowAlgo == true) {
                        // 进行ota升级
                        programBlock();
                    } else {
                        if (packetsSent != 0) packetsSent--;
                        if (packetsSent > 10) return;
                        while (packetsSent < fastAlgoMaxPackets) {
                            waitABit();
                            programBlock();
                        }
                    }
                }
            } else if (BluetoothLeService.ACTION_DATA_WRITE.equals(action)) {
                Log.e(TAG, "Service Write");
//                byte[] value = intent.getByteArrayExtra(BluetoothLeService.EXTRA_DATA);
//                String uuidStr = intent.getStringExtra(BluetoothLeService.EXTRA_UUID);
//                if (uuidStr.equals(mCharIdentify.getUuid().toString())) {
//                    Log.e(TAG, "Identify write" );
//                }
////                if (uuidStr.equals(mCharBlock.getUuid().toString())) {
////                    String block = String.format("%02x%02x", value[1], value[0]);
////                    Log.e("FwUpdateActivity_CC26xx :", "Received block req: " + block);
//                    if (slowAlgo == true) {
//                        // 进行ota升级
//                        programBlock();
//                    } else {
//                        if (packetsSent != 0) packetsSent--;
//                        if (packetsSent > 10) return;
//                        while (packetsSent < fastAlgoMaxPackets) {
//                            waitABit();
//                            programBlock();
//                        }
//                    }
            }
//            }
        }
    };

    private void programBlock() {
        if (!mProgramming)
            return;

        if (mProgInfo.iBlocks < mProgInfo.nBlocks) {
            mProgramming = true;
            String msg = new String();

            // Prepare block,包的序号
            mOadBuffer[0] = Conversion.loUint16(mProgInfo.iBlocks);
            mOadBuffer[1] = Conversion.hiUint16(mProgInfo.iBlocks);

            // 字节拼接后得到的数组为18个字节，序号+内容
            System.arraycopy(mFileBuffer, mProgInfo.iBytes, mOadBuffer, 2, OAD_BLOCK_SIZE);


            // Send block
            mCharBlock.setValue(mOadBuffer);
            boolean success = mBluetoothLeService.writeCharacteristicNonBlock(mCharBlock);
            //Log.d("FwUpdateActivity_CC26xx","Sent block :" + mProgInfo.iBlocks);
            if (success) {
                // Update stats
                packetsSent++;
                mProgInfo.iBlocks++;
                mProgInfo.iBytes += OAD_BLOCK_SIZE; // 一个block有16个字节，故每写完一个block，iBytes都会加16
                if (mProgInfo.iBlocks == mProgInfo.nBlocks) {
                    //Set preference to reload device cache when reconnecting later.
//                    PreferenceWR p = new PreferenceWR(mBluetoothLeService.getConnectedDeviceAddress(),mDeviceActivity);
//                    p.setBooleanPreference(PreferenceWR.PREFERENCEWR_NEEDS_REFRESH,true);

                    AlertDialog.Builder b = new AlertDialog.Builder(this);

                    b.setMessage(R.string.oad_dialog_programming_finished);
                    b.setTitle("Programming finished");
                    b.setPositiveButton("OK", null);

                    AlertDialog d = b.create();
                    d.show();
                    mProgramming = false;
                    Log.e(TAG, "Programming finished at block " + (mProgInfo.iBlocks + 1) + "\n");
                }
            } else {
                mProgramming = false;
                msg = "GATT writeCharacteristic failed\n";
            }
            if (!success) {
                Log.e(TAG, msg);
            }
        } else {
            mProgramming = false;
        }
        if ((mProgInfo.iBlocks % 100) == 0) {
            // Display statistics each 100th block
            runOnUiThread(new Runnable() {
                public void run() {
                    displayStats();
                }
            });
        }

        if (!mProgramming) {
            runOnUiThread(new Runnable() {
                public void run() {
                    displayStats();
                    stopProgramming();
                }
            });
        }
    }

    private float firmwareVersion;
    private ImgHdr mFileImgHdr;

    // wait a bit
    private void waitABit() {
        int waitTimeout = 20;
        while ((waitTimeout -= 10) > 0) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    // If a given GATT characteristic is selected, check for supported features.  This sample
    // demonstrates 'Read' and 'Notify' features.  See
    // http://d.android.com/reference/android/bluetooth/BluetoothGatt.html for the complete
    // list of supported characteristic features.
    private final ExpandableListView.OnChildClickListener servicesListClickListner =
            new ExpandableListView.OnChildClickListener() {
                @Override
                public boolean onChildClick(ExpandableListView parent, View v, int groupPosition,
                                            int childPosition, long id) {
                    if (mGattCharacteristics != null) {
                        final BluetoothGattCharacteristic characteristic =
                                mGattCharacteristics.get(groupPosition).get(childPosition);
                        final int charaProp = characteristic.getProperties();
                        if ((charaProp | BluetoothGattCharacteristic.PROPERTY_READ) > 0) {
                            // If there is an active notification on a characteristic, clear
                            // it first so it doesn't update the data field on the user interface.
                            if (mNotifyCharacteristic != null) {
                                mBluetoothLeService.setCharacteristicNotification(
                                        mNotifyCharacteristic, false);
                                mNotifyCharacteristic = null;
                            }
                            mBluetoothLeService.readCharacteristic(characteristic);
                        }
                        if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                            mNotifyCharacteristic = characteristic;
                            mBluetoothLeService.setCharacteristicNotification(
                                    characteristic, true);
                        }
                        return true;
                    }
                    return false;
                }
            };

    private void clearUI() {
        mGattServicesList.setAdapter((SimpleExpandableListAdapter) null);
        mDataField.setText(R.string.no_data);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.gatt_services_characteristics);
        final Intent intent = getIntent();
        mDeviceName = intent.getStringExtra(EXTRAS_DEVICE_NAME);
        mDeviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);
//        makeGattUpdateIntentFilter();
        // Sets up UI references.
        ((TextView) findViewById(R.id.device_address)).setText(mDeviceAddress);
        mGattServicesList = (ExpandableListView) findViewById(R.id.gatt_services_list);
        mGattServicesList.setOnChildClickListener(servicesListClickListner);
        mConnectionState = (TextView) findViewById(R.id.connection_state);
        mDataField = (TextView) findViewById(R.id.data_value);
        Button btn_v2 = (Button) findViewById(R.id.btn_OTA_v2);
        Button btn_v3 = (Button) findViewById(R.id.btn_OTA_v3);
        btn_v2.setOnClickListener(this);
        btn_v3.setOnClickListener(this);
        getActionBar().setTitle(mDeviceName);
        getActionBar().setDisplayHomeAsUpEnabled(true);

        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    }


    // 开始OTA升级
    private void startUpdate(String fileName, boolean isAsset) {
        init();
        loadFile(fileName, isAsset);
        startProgramming();


    }

    private boolean loadFile(String fileName, boolean isAsset) {
        boolean fSuccess = false;
        int readLen = 0;
        // Load binary file
        try {
            // Read the file raw into a buffer
            InputStream stream;
            if (isAsset) {
                stream = getAssets().open(fileName);
            } else {
                File f = new File(fileName);
                stream = new FileInputStream(f);
            }
            readLen = stream.read(mFileBuffer, 0, mFileBuffer.length);
            stream.close();
            Log.e(TAG, "loadFile success");
        } catch (IOException e) {
            // Handle exceptions here
            Log.e(TAG, "File open failed: " + fileName + "\n");
            return false;
        }

        if (!isAsset) {
            Log.e(TAG, fileName);
        }

        //Always enable button on CC26xx
        mFileImgHdr = new ImgHdr(mFileBuffer, readLen);

        // Expected duration
        displayStats();

        Log.e(TAG, "Programming image : " + fileName);
        Log.e(TAG, "File size : " + readLen + " bytes (" + (readLen / 16) + ") blocks\n");
        Log.e(TAG, "Ready to program device!\n");
        return fSuccess;
    }

    private void displayStats() {
    }

    public void onStart(View v) {
        if (mProgramming) {
            stopProgramming();
        } else {
            startProgramming();
        }
    }

    private void stopProgramming() {
        mTimer.cancel();
        mTimer.purge();
        mTimerTask.cancel();
        mTimerTask = null;

        mProgramming = false;
        mBluetoothLeService.setCharacteristicNotification(mCharBlock, false);
        if (mProgInfo.iBlocks == mProgInfo.nBlocks) {
            Log.e(TAG, "Programming complete!");
        } else {
            Log.e(TAG, "Programming cancelled");
        }
    }

    private void startProgramming() {
        Log.e(TAG, "programming start");
        mProgramming = true;
        packetsSent = 0;
        // 写metadata
        mCharIdentify.setValue(mFileImgHdr.getRequest());
        mBluetoothLeService.writeCharacteristic(mCharIdentify);

        mProgInfo.reset();
        mTimer = new Timer();
        mTimerTask = new ProgTimerTask();
        mTimer.scheduleAtFixedRate(mTimerTask, 0, TIMER_INTERVAL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.e(TAG, "onResume: ");
        getApplicationContext().registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
//        mBluetoothLeService.abortTimedDisconnect();
    }


    private void getTargetImageInfo() {
        // 使能Identify的通知
//        mCharIdentify.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
//        mBluetoothLeService.setCharacteristicNotification(mCharIdentify, true);
        // Prepare data for request (try image A and B respectively, only one of
        // them will give a notification with the image info)
//        int count = 0;
//        int ok = 1;
//        while (ok !=0 && count < 5) {
//            count ++;
//            ok = mBluetoothLeService.writeCharacteristic(mCharIdentify, (byte) 0);
//            if (ok == 0)
//                ok = mBluetoothLeService.writeCharacteristic(mCharIdentify, (byte) 1);
//        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        getApplicationContext().unregisterReceiver(mGattUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(mServiceConnection);
        mBluetoothLeService = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    public void init() {

        // 获取OTA服务
        mOTAService = mBluetoothLeService.getOTAService();
        // 获取连接控制服务
//        mConnControlService = mBluetoothLeService.getConControlService();
        // 获取相应的特征字
        mCharListOad = mOTAService.getCharacteristics();
//        mCharListCc = mConnControlService.getCharacteristics();
        mServiceOk = mCharListOad.size() == 3 /*&& mCharListCc.size() >= 3*/;
        if (mServiceOk) {
            mCharIdentify = mCharListOad.get(0);
            mCharBlock = mCharListOad.get(1);
            getTargetImageInfo();
            mCharBlock.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            // 使能Block的通知
            mBluetoothLeService.setCharacteristicNotification(mCharBlock, true);
        }
        try {
            // firmversion 有问题
            BluetoothGattCharacteristic version = mBluetoothLeService.getFirmWorkVersion();
            String s = getValueSafe(version);
            String revNum = s.substring(0, s.indexOf(" "));
            firmwareVersion = Float.parseFloat(revNum);
            Log.e(TAG, "version is " + firmwareVersion);
            // TODO: 2016/9/2 增加一个对version的判断 但是vesion有问题啊

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getValueSafe(BluetoothGattCharacteristic c) {
        byte b[] = c.getValue();
        if (b == null) {
            b = "N/A".getBytes(Charset.forName("UTF-8"));
        }
        try {
            return new String(b, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return "";
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.gatt_services, menu);
        if (mConnected) {
            menu.findItem(R.id.menu_connect).setVisible(false);
            menu.findItem(R.id.menu_disconnect).setVisible(true);
        } else {
            menu.findItem(R.id.menu_connect).setVisible(true);
            menu.findItem(R.id.menu_disconnect).setVisible(false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_connect:
                mBluetoothLeService.connect(mDeviceAddress);
                return true;
            case R.id.menu_disconnect:
                mBluetoothLeService.disconnect();
                return true;
            case android.R.id.home:
                onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateConnectionState(final int resourceId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mConnectionState.setText(resourceId);
            }
        });
    }

    private void displayData(String data) {
        if (data != null) {
            mDataField.setText(data);
        }
    }

    // Demonstrates how to iterate through the supported GATT Services/Characteristics.
    // In this sample, we populate the data structure that is bound to the ExpandableListView
    // on the UI.
    private void displayGattServices(List<BluetoothGattService> gattServices) {
        if (gattServices == null) return;
        String uuid = null;
        String unknownServiceString = getResources().getString(R.string.unknown_service);
        String unknownCharaString = getResources().getString(R.string.unknown_characteristic);
        ArrayList<HashMap<String, String>> gattServiceData = new ArrayList<HashMap<String, String>>();
        ArrayList<ArrayList<HashMap<String, String>>> gattCharacteristicData
                = new ArrayList<ArrayList<HashMap<String, String>>>();
        mGattCharacteristics = new ArrayList<ArrayList<BluetoothGattCharacteristic>>();

        // Loops through available GATT Services.
        for (BluetoothGattService gattService : gattServices) {
            HashMap<String, String> currentServiceData = new HashMap<String, String>();
            uuid = gattService.getUuid().toString();
            currentServiceData.put(
                    LIST_NAME, SampleGattAttributes.lookup(uuid, unknownServiceString));
            currentServiceData.put(LIST_UUID, uuid);
            gattServiceData.add(currentServiceData);

            ArrayList<HashMap<String, String>> gattCharacteristicGroupData =
                    new ArrayList<HashMap<String, String>>();
            List<BluetoothGattCharacteristic> gattCharacteristics =
                    gattService.getCharacteristics();
            ArrayList<BluetoothGattCharacteristic> charas =
                    new ArrayList<BluetoothGattCharacteristic>();

            // Loops through available Characteristics.
            for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                charas.add(gattCharacteristic);
                HashMap<String, String> currentCharaData = new HashMap<String, String>();
                uuid = gattCharacteristic.getUuid().toString();
                currentCharaData.put(
                        LIST_NAME, SampleGattAttributes.lookup(uuid, unknownCharaString));
                currentCharaData.put(LIST_UUID, uuid);
                gattCharacteristicGroupData.add(currentCharaData);
            }
            mGattCharacteristics.add(charas);
            gattCharacteristicData.add(gattCharacteristicGroupData);
        }

        SimpleExpandableListAdapter gattServiceAdapter = new SimpleExpandableListAdapter(
                this,
                gattServiceData,
                android.R.layout.simple_expandable_list_item_2,
                new String[]{LIST_NAME, LIST_UUID},
                new int[]{android.R.id.text1, android.R.id.text2},
                gattCharacteristicData,
                android.R.layout.simple_expandable_list_item_2,
                new String[]{LIST_NAME, LIST_UUID},
                new int[]{android.R.id.text1, android.R.id.text2}
        );
        mGattServicesList.setAdapter(gattServiceAdapter);
    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_NOTIFY);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_WRITE);
        return intentFilter;

        // write操作
//        intentFilter.addAction(BluetoothLeService.ACTION_DATA_WRITE);
//        intentFilter.addAction(BluetoothLeService.ACTION_DATA_NOTIFY);
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        switch (id) {
            case R.id.btn_OTA_v2:
                startUpdate(FW_FILE_V2, true);

                break;
            case R.id.btn_OTA_v3:
                startUpdate(FW_FILE_V3, true);

                break;
        }
    }

    private class ProgTimerTask extends TimerTask {
        @Override
        public void run() {
            mProgInfo.iTimeElapsed += TIMER_INTERVAL;
        }
    }

    private class ProgInfo {
        int iBytes = 0; // Number of bytes programmed
        short iBlocks = 0; // Number of blocks programmed
        short nBlocks = 0; // Total number of blocks
        int iTimeElapsed = 0; // Time elapsed in milliseconds

        void reset() {
            iBytes = 0;
            iBlocks = 0;
            iTimeElapsed = 0;
            nBlocks = (short) (mFileImgHdr.len / (OAD_BLOCK_SIZE / HAL_FLASH_WORD_SIZE));
        }
    }

    private class ImgHdr {
        short crc0;
        short crc1;
        short ver;
        int len;
        byte[] uid = new byte[4];
        short addr;
        byte imgType;

        ImgHdr(byte[] buf, int fileLen) {
            this.len = (fileLen / (16 / 4));
            this.ver = 0;
            this.uid[0] = this.uid[1] = this.uid[2] = this.uid[3] = 'E'; // 9-12字节表示版本ID，固定为EEEE这四个字符
            this.addr = 0;
            this.imgType = 1; //EFL_OAD_IMG_TYPE_APP
            this.crc0 = calcImageCRC((int) 0, buf);
            crc1 = (short) 0xFFFF;
            Log.d("FwUpdateActivity_CC26xx", "ImgHdr.len = " + this.len);
            Log.d("FwUpdateActivity_CC26xx", "ImgHdr.ver = " + this.ver);
            Log.d("FwUpdateActivity_CC26xx", String.format("ImgHdr.uid = %02x%02x%02x%02x", this.uid[0], this.uid[1], this.uid[2], this.uid[3]));
            Log.d("FwUpdateActivity_CC26xx", "ImgHdr.addr = " + this.addr);
            Log.d("FwUpdateActivity_CC26xx", "ImgHdr.imgType = " + this.imgType);
            Log.d("FwUpdateActivity_CC26xx", String.format("ImgHdr.crc0 = %04x", this.crc0));
        }

        // metadata的数据
        byte[] getRequest() {
            byte[] tmp = new byte[16];
            tmp[0] = Conversion.loUint16((short) this.crc0); // 1-2字节表示CRC校验值，16位
            tmp[1] = Conversion.hiUint16((short) this.crc0);
            tmp[2] = Conversion.loUint16((short) this.crc1); // 3-4字节表示CRC掩码，16位
            tmp[3] = Conversion.hiUint16((short) this.crc1);
            tmp[4] = Conversion.loUint16(this.ver);
            tmp[5] = Conversion.hiUint16(this.ver);
            tmp[6] = Conversion.loUint16((short) this.len); // 7-8字节表示固件大小
            tmp[7] = Conversion.hiUint16((short) this.len);
            tmp[8] = tmp[9] = tmp[10] = tmp[11] = this.uid[0]; // 9-12字节表示版本ID，固定为EEEE这四个字符
            tmp[12] = Conversion.loUint16(this.addr); // 13-14字节表示固件起始地址
            tmp[13] = Conversion.hiUint16(this.addr);
            tmp[14] = imgType; // 固件类型
            tmp[15] = (byte) 0xFF; // 固件拷贝标志位
            return tmp;
        }

        short calcImageCRC(int page, byte[] buf) {
            short crc = 0;
            long addr = page * 0x1000;

            byte pageBeg = (byte) page;
            byte pageEnd = (byte) (this.len / (0x1000 / 4));
            int osetEnd = ((this.len - (pageEnd * (0x1000 / 4))) * 4);

            pageEnd += pageBeg;


            while (true) {
                int oset;

                for (oset = 0; oset < 0x1000; oset++) {
                    if ((page == pageBeg) && (oset == 0x00)) {
                        //Skip the CRC and shadow.
                        //Note: this increments by 3 because oset is incremented by 1 in each pass
                        //through the loop
                        oset += 3;
                    } else if ((page == pageEnd) && (oset == osetEnd)) {
                        crc = this.crc16(crc, (byte) 0x00);
                        crc = this.crc16(crc, (byte) 0x00);

                        return crc;
                    } else {
                        crc = this.crc16(crc, buf[(int) (addr + oset)]);
                    }
                }
                page += 1;
                addr = page * 0x1000;
            }


        }

        short crc16(short crc, byte val) {
            final int poly = 0x1021;
            byte cnt;
            for (cnt = 0; cnt < 8; cnt++, val <<= 1) {
                byte msb;
                if ((crc & 0x8000) == 0x8000) {
                    msb = 1;
                } else msb = 0;

                crc <<= 1;
                if ((val & 0x80) == 0x80) {
                    crc |= 0x0001;
                }
                if (msb == 1) {
                    crc ^= poly;
                }
            }

            return crc;
        }

    }


}
