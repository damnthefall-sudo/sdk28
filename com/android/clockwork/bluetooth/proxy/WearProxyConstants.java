package com.android.clockwork.bluetooth.proxy;

/** Constants for wear proxy modules */
public class WearProxyConstants {
    private WearProxyConstants () { }

    public static final String LOG_TAG = "WearBluetoothProxy";

    public static final String NETWORK_TYPE = "COMPANION_PROXY";

    public static final String WEAR_NETWORK_SUBTYPE_NAME = "";
    public static final int WEAR_NETWORK_SUBTYPE = 0;

    /** Reasons for sysproxy connection or disconnection events */
    public static final class Reason {
        public static final String CLOSABLE = "Closable";
        public static final String SYSPROXY_WAS_CONNECTED = "Sysproxy Previously Connected";
        public static final String START_SYSPROXY = "Sysproxy Starting";
        public static final String STOP_SYSPROXY = "Sysproxy Stopping";
        public static final String SYSPROXY_CONNECTED = "Sysproxy Connected";
        public static final String SYSPROXY_DISCONNECTED = "Sysproxy Disconnected";
        public static final String REQUEST_BT_SOCKET = "Requesting Socket";
        public static final String DELIVER_BT_SOCKET = "Delivering Socket";

    }
}

