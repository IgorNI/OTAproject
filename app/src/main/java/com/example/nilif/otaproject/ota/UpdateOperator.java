package com.example.nilif.otaproject.ota;

/**
 * @author nilif
 * @date 2016/9/13 01:09
 */

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.content.Context;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import com.example.nilif.otasdk.Conversion;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

/**
 * @author nilif
 * @date 2016/9/12 14:43
 */
public class UpdateOperator {

    public final static UUID UUID_OAD_SERVICE = UUID.fromString("F000FFC0-0451-4000-B000-000000000000");
    public static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int OAD_IDENTIFY_SIZE = 16; // 写入identify的字节数
    private static final int OAD_BLOCK_SIZE = 16;
    private static final int OAD_BUFFER_SIZE = 2 + OAD_BLOCK_SIZE; // 需要发送的block的大小
    private static final int HAL_FLASH_WORD_SIZE = 4;
    private static final long TIMER_INTERVAL = 1000;
    private static final String TAG = "OTAUpdate";

    private Context context;
    private static final String FILE_NAME = "update.bin";
    private boolean isAsset;
    private int packetsSent = 0;
    private static byte[] mFileBuffer;
    private final byte[] mOadBuffer = new byte[OAD_BUFFER_SIZE];
    private ProgInfo mProgInfo = new ProgInfo();
    private int mFileLength;
    private Timer mTimer = null;
    private TimerTask mTimerTask = null;
    private BluetoothGatt mBluetoothGatt;
    private BluetoothGattCharacteristic mCharBlock;
    private BluetoothGattCharacteristic mCharIdentify;
    private boolean mProgramming = false;

    private long time;


    public UpdateOperator(Context context, boolean isAsset, BluetoothGatt mBluetoothGatt
            , BluetoothGattCharacteristic mCharIdentify, BluetoothGattCharacteristic mCharBlock, long time) {
        this.context = context;
        this.isAsset = isAsset;
        this.mBluetoothGatt = mBluetoothGatt;
        this.mCharIdentify = mCharIdentify;
        this.mCharBlock = mCharBlock;
        this.time = time;
    }


    public void startUpdate() {
        init();
        // 读取文件
        loadFile(context, FILE_NAME, isAsset);
        // 开始发送数据
        startProgramming();

    }

    // 初始化操作，使能Image Identify和Image Block；
    private void init() {
        try {
            mCharIdentify.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            setCharacteristicNotification(mCharIdentify, true);
            mCharBlock.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            setCharacteristicNotification(mCharBlock, true);
        } catch (NullPointerException e) {
            e.printStackTrace();
        }

    }

    // 特征字使能操作
    private void setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enabled) {
        if (mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter or mBluetoothGatt not initialized");
            return;
        }
        mBluetoothGatt.setCharacteristicNotification(characteristic, enabled);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                CLIENT_CHARACTERISTIC_CONFIG);
        if (UUID_OAD_SERVICE.equals(characteristic.getService().getUuid())) {
            if (enabled) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                mBluetoothGatt.writeDescriptor(descriptor);
            } else {
                descriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
            }

        }
    }

    // 写操作
    private void writeCharacteristic(BluetoothGattCharacteristic mCharacteristic) {
        if (mBluetoothGatt == null) {
            Log.e(TAG, "BluetoothGatt is not initialized");
            return;
        }
        mBluetoothGatt.writeCharacteristic(mCharacteristic);
    }

    public boolean writeCharacteristicNonBlock(BluetoothGattCharacteristic mCharacteristic) {
        if (mBluetoothGatt == null) {
            Log.e(TAG, "BluetoothGatt is not initialized");
            return false;
        }
        writeCharacteristic(mCharacteristic);
        return true;
    }

    public BluetoothGattCharacteristic getImageIdentify() {
        return mCharIdentify;
    }

    public BluetoothGattCharacteristic getImageBlock() {
        return mCharBlock;
    }

    private void loadFile(Context context, String fileName, boolean isAsset) {
        int readLen = 0;
        File file = null;
        // Load binary file
        try {
            // Read the file raw into a buffer
            InputStream stream;
            file = new File(Environment.getExternalStorageDirectory(), fileName);
            stream = new FileInputStream(file);
            readLen = stream.read(mFileBuffer = new byte[stream.available()], 0, mFileBuffer.length);
            stream.close();
            Log.e(TAG, "loadFile success");
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "loadFile failed besause FileName is null");
            e.printStackTrace();
            Toast.makeText(context, "文件为空", Toast.LENGTH_SHORT).show();
            return;
        } catch (FileNotFoundException e) {
            Log.e(TAG, "loadFile failed besause" + "File " + fileName + "not found" + "\n");
            Log.e(TAG, "loadFile: failed" + file.getAbsolutePath());
            Toast.makeText(context, "找不到文件", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
            return;
        } catch (NullPointerException e) {
            Log.e(TAG, "loadFile failed");
            Toast.makeText(context, "加载文件失败", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
            return;
        } catch (IOException e) {
            Log.e(TAG, "File open failed: " + fileName + "\n");
            e.printStackTrace();
            return;
        }
        if (!isAsset) {
            Log.e(TAG, fileName);
        }
        mFileLength = readLen;
    }

    private void startProgramming() {
        try {
            Log.e(TAG, "programming start");
            mProgramming = true;
            packetsSent = 0;
            byte[] identifyData = new byte[OAD_IDENTIFY_SIZE];
            // 复制文件的头16字节至字节数组中，
            System.arraycopy(mFileBuffer, 0, identifyData, 0, OAD_IDENTIFY_SIZE);

            // 向Identify中写metadata。如果写成功，会在广播接收器mGattUpdateReceiver中接收到设备返回的信息
            mCharIdentify.setValue(identifyData);
            writeCharacteristic(mCharIdentify);
            mProgInfo.reset();
            mTimer = new Timer();
            mTimerTask = new ProgTimerTask();
            mTimer.scheduleAtFixedRate(mTimerTask, 0, TIMER_INTERVAL);
        } catch (NullPointerException e) {
            Log.e(TAG, "更新失败");
            e.printStackTrace();
        }

    }

    private class ProgTimerTask extends TimerTask {
        @Override
        public void run() {
            mProgInfo.iTimeElapsed += TIMER_INTERVAL;
        }
    }

    /**
     * 停止发送数据
     */
    private void stopProgramming() {
        mTimer.cancel();
        mTimer.purge();
        mTimerTask.cancel();
        mTimerTask = null;

        mProgramming = false;
        setCharacteristicNotification(mCharBlock, false);
        if (mProgInfo.iBlocks == mProgInfo.nBlocks) {
            Log.e(TAG, "Programming complete!");
        } else {
            Log.e(TAG, "Programming cancelled");
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
            nBlocks = (short) (mFileLength / (OAD_BLOCK_SIZE / HAL_FLASH_WORD_SIZE));
        }
    }

    /**
     * 向block中发送数据
     */
    public void programBlock() {
        if (!mProgramming)
            return;
        if (mProgInfo.iBlocks < mProgInfo.nBlocks) {
            mProgramming = true;
            String msg = new String();

            // Prepare block包的序号
            mOadBuffer[0] = com.example.nilif.otasdk.Conversion.loUint16(mProgInfo.iBlocks);
            mOadBuffer[1] = com.example.nilif.otasdk.Conversion.hiUint16(mProgInfo.iBlocks);

            // 字节拼接后得到的数组为18个字节，序号+内容
            System.arraycopy(mFileBuffer, mProgInfo.iBytes, mOadBuffer, 2, OAD_BLOCK_SIZE);
            Log.e(TAG, "programBlock: " + Conversion.BytetohexString(mOadBuffer, mOadBuffer.length));
            // Send block
            mCharBlock.setValue(mOadBuffer);
            boolean success = writeCharacteristicNonBlock(mCharBlock);
            if (success) {
                // Update stats
                packetsSent++;
                mProgInfo.iBlocks++;
                mProgInfo.iBytes += OAD_BLOCK_SIZE; // 一个block有16个字节，故每写完一个block，iBytes都会加16
                if (mProgInfo.iBytes >= mFileLength) {
                    mProgramming = false;
                    Log.e(TAG, "Programming finished at block " + (mProgInfo.iBlocks + 1) + "\n");
                    long endTime = System.currentTimeMillis();
                    long dur = endTime - time;
                    Toast.makeText(context, "升级完成，时间为" + dur / 1000 + "秒", Toast.LENGTH_LONG).show();
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
        if (!mProgramming) {
            new Thread(new Runnable() {
                public void run() {
                    stopProgramming();
                }
            });
        }
    }
}
