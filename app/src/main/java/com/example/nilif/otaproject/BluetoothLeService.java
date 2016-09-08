/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.example.nilif.otaproject;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import org.xml.sax.SAXNotRecognizedException;

import java.util.LinkedList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
public class BluetoothLeService extends Service {
    private final static String TAG = BluetoothLeService.class.getSimpleName();


    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private BluetoothGatt mBluetoothGatt;
    private int mConnectionState = STATE_DISCONNECTED;
    private boolean writeFlag = false;

    public Timer disconnectionTimer;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    public final static String ACTION_GATT_CONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED =
            "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_AVAILABLE =
            "com.example.bluetooth.le.ACTION_DATA_AVAILABLE";
    public final static String EXTRA_DATA =
            "com.example.bluetooth.le.EXTRA_DATA";
    public final static String ACTION_DATA_NOTIFY = "com.example.bluetooth.le.ACTION_DATA_NOTIFY";

    public final static String ACTION_DATA_WRITE = "com.example.bluetooth.le.ACTION_DATA_WRITE";

    public final static String EXTRA_UUID = "com.example.bluetooth.le.EXTRA_UUID";

    public final static UUID UUID_HEART_RATE_MEASUREMENT =
            UUID.fromString(SampleGattAttributes.HEART_RATE_MEASUREMENT);
    public final static UUID UUID_OAD_SERVICE = UUID.fromString(SampleGattAttributes.OAD_SERVICE);
    public final static UUID UUID_OAD_IMAGE_IDENTIFY = UUID.fromString(SampleGattAttributes.OAD_IMAGE_IDENTIFY);
    public final static UUID UUID_OAD_IMAGE_BLOCK = UUID.fromString(SampleGattAttributes.OAD_IMAGE_BLOCK);


    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String intentAction;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                intentAction = ACTION_GATT_CONNECTED;
                mConnectionState = STATE_CONNECTED;
                broadcastUpdate(intentAction);
                Log.i(TAG, "Connected to GATT server.");
                // Attempts to discover services after successful connection.
                Log.i(TAG, "Attempting to start service discovery:" +
                        mBluetoothGatt.discoverServices());

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                intentAction = ACTION_GATT_DISCONNECTED;
                mConnectionState = STATE_DISCONNECTED;
                Log.i(TAG, "Disconnected from GATT server.");
                broadcastUpdate(intentAction);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
            }
        }


        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                writeFlag = true;
//                broadcastUpdate(ACTION_DATA_WRITE, characteristic);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            Log.e(TAG, "onCharacteristicChanged:");
            broadcastUpdate(ACTION_DATA_NOTIFY, characteristic);
        }
    };

    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);

        sendBroadcast(intent);
    }

    private void broadcastUpdate(final String action,
                                 final BluetoothGattCharacteristic characteristic) {
        final Intent intent = new Intent(action);
        intent.putExtra(EXTRA_UUID, characteristic.getUuid().toString());
        // This is special handling for the Heart Rate Measurement profile.  Data parsing is
        // carried out as per profile specifications:
        // http://developer.bluetooth.org/gatt/characteristics/Pages/CharacteristicViewer.aspx?u=org.bluetooth.characteristic.heart_rate_measurement.xml
        if (UUID_HEART_RATE_MEASUREMENT.equals(characteristic.getUuid())) {
            int flag = characteristic.getProperties();
            int format = -1;
            if ((flag & 0x01) != 0) {
                format = BluetoothGattCharacteristic.FORMAT_UINT16;
                Log.d(TAG, "Heart rate format UINT16.");
            } else {
                format = BluetoothGattCharacteristic.FORMAT_UINT8;
                Log.d(TAG, "Heart rate format UINT8.");
            }
            final int heartRate = characteristic.getIntValue(format, 1);
            Log.d(TAG, String.format("Received heart rate: %d", heartRate));
            intent.putExtra(EXTRA_DATA, String.valueOf(heartRate));
        } else if (UUID_OAD_IMAGE_IDENTIFY.equals(characteristic.getUuid()) ||
                UUID_OAD_IMAGE_BLOCK.equals(characteristic.getUuid())) {
            Log.e(TAG, "OAD characteristic is changed");
            intent.putExtra(EXTRA_DATA, characteristic.getValue());
        } else {
            // For all other profiles, writes the data formatted in HEX.
//            final byte[] data = characteristic.getValue();
//            if (data != null && data.length > 0) {
//                final StringBuilder stringBuilder = new StringBuilder(data.length);
//                for(byte byteChar : data)
//                    stringBuilder.append(String.format("%02X ", byteChar));
//                intent.putExtra(EXTRA_DATA, new String(data) + "\n" + stringBuilder.toString());
//            }
        }
        sendBroadcast(intent);
    }

    public BluetoothGattService getOTAService() {
        if (mBluetoothGatt == null) {
            Log.e(TAG, "BluetoothGatt is not initialized");
            return null;
        }
        return mBluetoothGatt.getService(UUID.fromString(SampleGattAttributes.OAD_SERVICE));
    }

    public BluetoothGattService getConControlService() {
        if (mBluetoothGatt == null) {
            Log.e(TAG, "BluetoothGatt is not initialized");
            return null;
        }
        return mBluetoothGatt.getService(UUID.fromString(SampleGattAttributes.CONNECT_CONTROL_SERVICE));
    }

    public BluetoothGattCharacteristic getFirmWorkVersion() {
        if (mBluetoothGatt == null) {
            Log.e(TAG, "BluetoothGatt is not initialized");
            return null;
        }
        return mBluetoothGatt.getService(UUID.fromString(SampleGattAttributes.dISService_UUID))
                .getCharacteristic(UUID.fromString(SampleGattAttributes.dISFirmwareREV_UUID));
    }

    public void timedDisconnect() {
        disconnectTimerTask disconnectionTimerTask;
        this.disconnectionTimer = new Timer();
        disconnectionTimerTask = new disconnectTimerTask(this);
        this.disconnectionTimer.schedule(disconnectionTimerTask, 20000);
    }

    public void abortTimedDisconnect() {
//        if (this.disconnectionTimer != null) {
//            this.disconnectionTimer.cancel();
//        }
        return;
    }

    public void writeCharacteristic(BluetoothGattCharacteristic mCharacteristic) {
        if (mBluetoothGatt == null) {
            Log.e(TAG, "BluetoothGatt is not initialized");
            return;
        }
        mBluetoothGatt.writeCharacteristic(mCharacteristic);
    }

//    public boolean writeCharacteristicNonBlock(BluetoothGattCharacteristic mCharacteristic) {
//        writeFlag = false;
//        if (mBluetoothGatt == null) {
//            Log.e(TAG, "BluetoothGatt is not initialized");
//            return false;
//        }
//        writeCharacteristic(mCharacteristic);
//        Log.e(TAG, "writeCharacteristicNonBlock: ");
//        return true;
//    }

    public boolean writeCharacteristicNonBlock(BluetoothGattCharacteristic characteristic) {
        bleRequest req = new bleRequest();
        req.status = bleRequestStatus.not_queued;
        req.characteristic = characteristic;
        req.operation = bleRequestOperation.wr;
        addRequestToQueue(req);
        return true;
    }

    private final Lock lock = new ReentrantLock();
    private volatile LinkedList<bleRequest> procQueue;
    private volatile LinkedList<bleRequest> nonBlockQueue;
    private volatile bleRequest curBleRequest = null;
    public final static int GATT_TIMEOUT = 150;

    public boolean addRequestToQueue(bleRequest req) {
        lock.lock();
        if (procQueue.peekLast() != null) {
            req.id = procQueue.peek().id++;
        } else {
            req.id = 0;
            procQueue.add(req);
        }
        lock.unlock();
        return true;
    }

    public class bleRequest {
        public int id;
        public BluetoothGattCharacteristic characteristic;
        public bleRequestOperation operation;
        public volatile bleRequestStatus status;
        public int timeout;
        public int curTimeout;
        public boolean notifyenable;
    }

    public enum bleRequestStatus {
        not_queued,
        queued,
        processing,
        timeout,
        done,
        no_such_request,
        failed,
    }

    public enum bleRequestOperation {
        wrBlocking,
        wr,
        rdBlocking,
        rd,
        nsBlocking,
    }

    private void executeQueue() {
        // Everything here is done on the queue
        lock.lock();
        if (curBleRequest != null) {
            Log.d(TAG, "executeQueue, curBleRequest running");
            try {
                curBleRequest.curTimeout++;
                if (curBleRequest.curTimeout > GATT_TIMEOUT) {
                    curBleRequest.status = bleRequestStatus.timeout;
                    curBleRequest = null;
                }
                Thread.sleep(10, 0);
            } catch (Exception e) {
                e.printStackTrace();
            }
            lock.unlock();
            return;
        }
        if (procQueue == null) {
            lock.unlock();
            return;
        }
        if (procQueue.size() == 0) {
            lock.unlock();
            return;
        }
        bleRequest procReq = procQueue.removeFirst();

        switch (procReq.operation) {
            case wr:
                //Write, do non blocking write (Ex: OAD)
                nonBlockQueue.add(procReq);
                sendNonBlockingWriteRequest(procReq);
                break;
        }
        lock.unlock();
    }

    public int sendNonBlockingWriteRequest(bleRequest request) {
        request.status = bleRequestStatus.processing;
        if (mBluetoothGatt == null) {
            request.status = bleRequestStatus.failed;
            return -2;
        }
        mBluetoothGatt.writeCharacteristic(request.characteristic);
        return 0;
    }


    class disconnectTimerTask extends TimerTask {
        BluetoothLeService param;

        public disconnectTimerTask(final BluetoothLeService param) {
            this.param = param;
        }

        @Override
        public void run() {
            this.param.disconnect();
        }
    }
    public class LocalBinder extends Binder {
        BluetoothLeService getService() {
            return BluetoothLeService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // After using a given device, you should make sure that BluetoothGatt.close() is called
        // such that resources are cleaned up properly.  In this particular example, close() is
        // invoked when the UI is disconnected from the Service.
        close();
        return super.onUnbind(intent);
    }

    private final IBinder mBinder = new LocalBinder();

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    public boolean initialize() {
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }

        return true;
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     *
     * @return Return true if the connection is initiated successfully. The connection result
     *         is reported asynchronously through the
     *         {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     *         callback.
     */
    public boolean connect(final String address) {
        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        // Previously connected device.  Try to reconnect.
        if (mBluetoothDeviceAddress != null && address.equals(mBluetoothDeviceAddress)
                && mBluetoothGatt != null) {
            Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
            if (mBluetoothGatt.connect()) {
                mConnectionState = STATE_CONNECTING;
                return true;
            } else {
                return false;
            }
        }

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.");
            return false;
        }
        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
        Log.d(TAG, "Trying to create a new connection.");
        mBluetoothDeviceAddress = address;
        mConnectionState = STATE_CONNECTING;
        return true;
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.disconnect();
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close() {
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    /**
     * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
     * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
     * callback.
     *
     * @param characteristic The characteristic to read from.
     */
    public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(characteristic);
    }

    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled If true, enable notification.  False otherwise.
     */
    public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic,
                                              boolean enabled) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.setCharacteristicNotification(characteristic, enabled);
        // This is specific to Heart Rate Measurement.
//        if (UUID_OAD_IMAGE_IDENTIFY.equals(characteristic.getUuid()) ||
//                UUID_OAD_IMAGE_BLOCK.equals(characteristic.getUuid())) {
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                    SampleGattAttributes.CLIENT_CHARACTERISTIC_CONFIG);
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            mBluetoothGatt.writeDescriptor(descriptor);

//        }
    }

    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after {@code BluetoothGatt#discoverServices()} completes successfully.
     *
     * @return A {@code List} of supported services.
     */
    public List<BluetoothGattService> getSupportedGattServices() {
        if (mBluetoothGatt == null) return null;

        return mBluetoothGatt.getServices();
    }
}
