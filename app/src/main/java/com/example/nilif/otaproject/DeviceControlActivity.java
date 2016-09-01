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
import android.widget.ExpandableListView;
import android.widget.SimpleExpandableListAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * @author nilif
 * @date 2016/9/1 03:16
 */
public class DeviceControlActivity extends Activity {
    private final static String TAG = DeviceControlActivity.class.getSimpleName();

    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";
    private static final int BLOCKS_PER_CONNECTION = 20; // May sent up to four blocks per connection
    private static final int FILE_BUFFER_SIZE = 0x40000;

    private static final int OAD_BLOCK_SIZE = 16;
    private static final int OAD_BUFFER_SIZE = 2 + OAD_BLOCK_SIZE;
    private static final int HAL_FLASH_WORD_SIZE = 4;

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
    private BluetoothGattService mTestService;
    private List<BluetoothGattCharacteristic> mCharListOad;
    private List<BluetoothGattCharacteristic> mCharListCc;
    private BluetoothGattCharacteristic mCharIdentify = null;
    private BluetoothGattCharacteristic mCharBlock = null;
    private BluetoothGattCharacteristic mCharConnReq = null;
    private BluetoothGattCharacteristic mTestResult = null;
    private BluetoothLeService mLeService;

    private final byte[] mFileBuffer = new byte[FILE_BUFFER_SIZE];
    private final byte[] mOadBuffer = new byte[OAD_BUFFER_SIZE];
    private ImgHdr mFileImgHdr;
    private ImgHdr mTargImgHdr;
    private Timer mTimer = null;
    private ProgInfo mProgInfo = new ProgInfo();
    private TimerTask mTimerTask = null;
    private float firmwareRevision;
    private String internalFWFilename;

    public DeviceControlActivity() {
        // 获取OTA服务
        mOTAService = mBluetoothLeService.getOTAService();
        // 获取连接控制服务
        mConnControlService = mBluetoothLeService.getConControlService();
        // 获取相应的特征字
        mCharListOad = mOTAService.getCharacteristics();
        mCharListCc = mConnControlService.getCharacteristics();
    }

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
                updateConnectionState(R.string.connected);
                invalidateOptionsMenu();
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                mConnected = false;
                updateConnectionState(R.string.disconnected);
                invalidateOptionsMenu();
                clearUI();
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Show all the supported services and characteristics on the user interface.
                displayGattServices(mBluetoothLeService.getSupportedGattServices());
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                displayData(intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
            } else if (BluetoothLeService.ACTION_DATA_NOTIFY.equals(action)) {
                byte[] value = intent.getByteArrayExtra(BluetoothLeService.EXTRA_DATA);
                String uuidStr = intent.getStringExtra(BluetoothLeService.EXTRA_UUID);
                if (uuidStr.equals(mCharIdentify.getUuid().toString())) {

                }
                if (uuidStr.equals(mCharBlock.getUuid().toString())) {
                    String block = String.format("%02x%02x", value[1], value[0]);
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

            }
        }
    };

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

    // ota升级方法
    private void programBlock() {
        if (!mProgramming)
            return;

        if (mProgInfo.iBlocks < mProgInfo.nBlocks) {
            mProgramming = true;
            String msg = new String();

            // Prepare block
            mOadBuffer[0] = Conversion.loUint16(mProgInfo.iBlocks);
            mOadBuffer[1] = Conversion.hiUint16(mProgInfo.iBlocks);

            // 字节拼接后得到的数组为18个字节
            System.arraycopy(mFileBuffer, mProgInfo.iBytes, mOadBuffer, 2, OAD_BLOCK_SIZE);


            // Send block
            mCharBlock.setValue(mOadBuffer);
            boolean success = mLeService.writeCharacteristicNonBlock(mCharBlock);
            //Log.d("FwUpdateActivity_CC26xx","Sent block :" + mProgInfo.iBlocks);
            if (success) {
                // Update stats
                packetsSent++;
                mProgInfo.iBlocks++;
                mProgInfo.iBytes += OAD_BLOCK_SIZE; // 一个block有16个字节，故每写完一个block，iBytes都会加16
                mProgressBar.setProgress((mProgInfo.iBlocks * 100) / mProgInfo.nBlocks);
                if (mProgInfo.iBlocks == mProgInfo.nBlocks) {
                    //Set preference to reload device cache when reconnecting later.
                    PreferenceWR p = new PreferenceWR(mLeService.getConnectedDeviceAddress(), mDeviceActivity);
                    p.setBooleanPreference(PreferenceWR.PREFERENCEWR_NEEDS_REFRESH, true);

                    AlertDialog.Builder b = new AlertDialog.Builder(this);

                    b.setMessage(R.string.oad_dialog_programming_finished);
                    b.setTitle("Programming finished");
                    b.setPositiveButton("OK", null);

                    AlertDialog d = b.create();
                    d.show();
                    mProgramming = false;
                    mLog.append("Programming finished at block " + (mProgInfo.iBlocks + 1) + "\n");
                }
            } else {
                mProgramming = false;
                msg = "GATT writeCharacteristic failed\n";
            }
            if (!success) {
                mLog.append(msg);
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

        // Sets up UI references.
        ((TextView) findViewById(R.id.device_address)).setText(mDeviceAddress);
        mGattServicesList = (ExpandableListView) findViewById(R.id.gatt_services_list);
        mGattServicesList.setOnChildClickListener(servicesListClickListner);
        mConnectionState = (TextView) findViewById(R.id.connection_state);
        mDataField = (TextView) findViewById(R.id.data_value);

        getActionBar().setTitle(mDeviceName);
        getActionBar().setDisplayHomeAsUpEnabled(true);
        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        if (mBluetoothLeService != null) {
            final boolean result = mBluetoothLeService.connect(mDeviceAddress);
            Log.d(TAG, "Connect request result=" + result);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mGattUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(mServiceConnection);
        mBluetoothLeService = null;
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
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        // write操作
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_WRITE);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_NOTIFY);
        return intentFilter;
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
}
