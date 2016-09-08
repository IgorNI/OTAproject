package com.example.nilif.otaproject;

/**
 * @author nilif
 * @date 2016/9/2 11:21
 */
public class FirmwareEntry {
    public final String Filename;
    public final boolean Custom;
    public final String WirelessStandard;
    public final String Type;
    public final int OADAlgo;
    public final String BoardType;
    public final float RequiredVersionRev;
    public final boolean SafeMode;
    public final float Version;
    public boolean compatible;
    public final String DevPack;

    public FirmwareEntry(String Filename, boolean Custom, String WirelessStandard, String Type,
                         int OADAlgo, String BoardType, float RequiredVersionRev, boolean SafeMode,
                         float Version, String DevPack) {
        this.Filename = Filename;
        this.Custom = Custom;
        this.WirelessStandard = WirelessStandard;
        this.Type = Type;
        this.OADAlgo = OADAlgo;
        this.BoardType = BoardType;
        this.RequiredVersionRev = RequiredVersionRev;
        this.SafeMode = SafeMode;
        this.Version = Version;
        this.compatible = true;
        this.DevPack = DevPack;
    }
}
