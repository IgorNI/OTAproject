package com.example.nilif.otaproject;

import java.util.HashMap;
import java.util.UUID;

/**
 * @author nilif
 * @date 2016/9/1 03:15
 */
public class SampleGattAttributes {
    private static HashMap<String, String> attributes = new HashMap();
    public static String HEART_RATE_MEASUREMENT = "00002a37-0000-1000-8000-00805f9b34fb";
    // OADservice
    public static String OAD_SERVICE = "F000FFC0-0451-4000-B000-000000000000";
    // identify 的uuid
    public static final String OAD_IMAGE_IDENTIFY = "f000ffc1-0451-4000-b000-000000000000";
    // block 的uuid
    public static final String OAD_IMAGE_BLOCK = "f000ffc2-0451-4000-b000-000000000000";
    public static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");


    static {
        // Sample Services.
        attributes.put("0000180d-0000-1000-8000-00805f9b34fb", "Heart Rate Service");
        attributes.put("0000180a-0000-1000-8000-00805f9b34fb", "Device Information Service");
        // Sample Characteristics.
        attributes.put(HEART_RATE_MEASUREMENT, "Heart Rate Measurement");
        attributes.put("00002a29-0000-1000-8000-00805f9b34fb", "Manufacturer Name String");
    }

    public static String lookup(String uuid, String defaultName) {
        String name = attributes.get(uuid);
        return name == null ? defaultName : name;
    }
}