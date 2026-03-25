package com.remoteaccess.educational.advanced;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.telephony.TelephonyManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/**
 * NETWORK SNIFFER - Advanced Network Analysis
 * 
 * ⚠️ EDUCATIONAL PURPOSE ONLY
 * 
 * FEATURES:
 * - Network traffic monitoring
 * - WiFi information
 * - IP address detection
 * - Network capabilities
 * - Connection analysis
 * 
 * USE CASES:
 * - Network diagnostics
 * - Security analysis
 * - Connection monitoring
 * - Educational research
 */
public class NetworkSniffer {

    private Context context;
    private ConnectivityManager connectivityManager;
    private WifiManager wifiManager;
    private TelephonyManager telephonyManager;

    public NetworkSniffer(Context context) {
        this.context = context;
        this.connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        this.telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
    }

    /**
     * Get complete network information
     */
    public JSONObject getNetworkInfo() {
        JSONObject result = new JSONObject();
        
        try {
            result.put("success", true);
            result.put("wifi", getWiFiInfo());
            result.put("cellular", getCellularInfo());
            result.put("interfaces", getNetworkInterfaces());
            result.put("capabilities", getNetworkCapabilities());
            result.put("activeConnection", getActiveConnection());
            
        } catch (Exception e) {
            try {
                result.put("success", false);
                result.put("error", e.getMessage());
            } catch (JSONException ex) {
                ex.printStackTrace();
            }
        }
        
        return result;
    }

    /**
     * Get WiFi information
     */
    public JSONObject getWiFiInfo() {
        JSONObject wifi = new JSONObject();
        
        try {
            if (wifiManager.isWifiEnabled()) {
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                
                wifi.put("enabled", true);
                wifi.put("ssid", wifiInfo.getSSID().replace("\"", ""));
                wifi.put("bssid", wifiInfo.getBSSID());
                wifi.put("ipAddress", intToIp(wifiInfo.getIpAddress()));
                wifi.put("macAddress", wifiInfo.getMacAddress());
                wifi.put("linkSpeed", wifiInfo.getLinkSpeed() + " Mbps");
                wifi.put("rssi", wifiInfo.getRssi() + " dBm");
                wifi.put("frequency", wifiInfo.getFrequency() + " MHz");
                wifi.put("networkId", wifiInfo.getNetworkId());
                
                // Signal strength
                int rssi = wifiInfo.getRssi();
                int level = WifiManager.calculateSignalLevel(rssi, 5);
                wifi.put("signalLevel", level + "/4");
                wifi.put("signalStrength", getSignalStrength(rssi));
                
            } else {
                wifi.put("enabled", false);
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return wifi;
    }

    /**
     * Get cellular information
     */
    public JSONObject getCellularInfo() {
        JSONObject cellular = new JSONObject();
        
        try {
            cellular.put("networkOperator", telephonyManager.getNetworkOperatorName());
            cellular.put("networkType", getNetworkTypeName(telephonyManager.getNetworkType()));
            cellular.put("phoneType", getPhoneTypeName(telephonyManager.getPhoneType()));
            cellular.put("simState", getSimStateName(telephonyManager.getSimState()));
            cellular.put("simOperator", telephonyManager.getSimOperatorName());
            cellular.put("simCountry", telephonyManager.getSimCountryIso().toUpperCase());
            cellular.put("networkCountry", telephonyManager.getNetworkCountryIso().toUpperCase());
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cellular.put("dataEnabled", telephonyManager.isDataEnabled());
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return cellular;
    }

    /**
     * Get all network interfaces
     */
    public JSONArray getNetworkInterfaces() {
        JSONArray interfaces = new JSONArray();
        
        try {
            List<NetworkInterface> networkInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            
            for (NetworkInterface networkInterface : networkInterfaces) {
                JSONObject iface = new JSONObject();
                
                iface.put("name", networkInterface.getName());
                iface.put("displayName", networkInterface.getDisplayName());
                iface.put("isUp", networkInterface.isUp());
                iface.put("isLoopback", networkInterface.isLoopback());
                iface.put("isVirtual", networkInterface.isVirtual());
                iface.put("mtu", networkInterface.getMTU());
                
                // Get IP addresses
                JSONArray addresses = new JSONArray();
                List<InetAddress> inetAddresses = Collections.list(networkInterface.getInetAddresses());
                for (InetAddress address : inetAddresses) {
                    addresses.put(address.getHostAddress());
                }
                iface.put("addresses", addresses);
                
                interfaces.put(iface);
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return interfaces;
    }

    /**
     * Get network capabilities
     */
    public JSONObject getNetworkCapabilities() {
        JSONObject capabilities = new JSONObject();
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = connectivityManager.getActiveNetwork();
                if (network != null) {
                    NetworkCapabilities networkCapabilities = connectivityManager.getNetworkCapabilities(network);
                    
                    if (networkCapabilities != null) {
                        capabilities.put("hasInternet", networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
                        capabilities.put("hasWifi", networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
                        capabilities.put("hasCellular", networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
                        capabilities.put("hasEthernet", networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
                        capabilities.put("hasVPN", networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            capabilities.put("downloadBandwidth", networkCapabilities.getLinkDownstreamBandwidthKbps() + " Kbps");
                            capabilities.put("uploadBandwidth", networkCapabilities.getLinkUpstreamBandwidthKbps() + " Kbps");
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return capabilities;
    }

    /**
     * Get active connection info
     */
    public JSONObject getActiveConnection() {
        JSONObject connection = new JSONObject();
        
        try {
            NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
            
            if (activeNetwork != null) {
                connection.put("connected", activeNetwork.isConnected());
                connection.put("type", activeNetwork.getTypeName());
                connection.put("subtype", activeNetwork.getSubtypeName());
                connection.put("state", activeNetwork.getState().toString());
                connection.put("reason", activeNetwork.getReason());
                connection.put("extraInfo", activeNetwork.getExtraInfo());
                connection.put("isRoaming", activeNetwork.isRoaming());
                connection.put("isAvailable", activeNetwork.isAvailable());
            } else {
                connection.put("connected", false);
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return connection;
    }

    /**
     * Convert int IP to string
     */
    private String intToIp(int ip) {
        return (ip & 0xFF) + "." +
               ((ip >> 8) & 0xFF) + "." +
               ((ip >> 16) & 0xFF) + "." +
               ((ip >> 24) & 0xFF);
    }

    /**
     * Get signal strength description
     */
    private String getSignalStrength(int rssi) {
        if (rssi >= -50) return "Excellent";
        if (rssi >= -60) return "Good";
        if (rssi >= -70) return "Fair";
        if (rssi >= -80) return "Weak";
        return "Very Weak";
    }

    /**
     * Get network type name
     */
    private String getNetworkTypeName(int type) {
        switch (type) {
            case TelephonyManager.NETWORK_TYPE_GPRS: return "GPRS";
            case TelephonyManager.NETWORK_TYPE_EDGE: return "EDGE";
            case TelephonyManager.NETWORK_TYPE_UMTS: return "UMTS";
            case TelephonyManager.NETWORK_TYPE_HSDPA: return "HSDPA";
            case TelephonyManager.NETWORK_TYPE_HSUPA: return "HSUPA";
            case TelephonyManager.NETWORK_TYPE_HSPA: return "HSPA";
            case TelephonyManager.NETWORK_TYPE_CDMA: return "CDMA";
            case TelephonyManager.NETWORK_TYPE_EVDO_0: return "EVDO 0";
            case TelephonyManager.NETWORK_TYPE_EVDO_A: return "EVDO A";
            case TelephonyManager.NETWORK_TYPE_1xRTT: return "1xRTT";
            case TelephonyManager.NETWORK_TYPE_LTE: return "LTE (4G)";
            case TelephonyManager.NETWORK_TYPE_EHRPD: return "eHRPD";
            case TelephonyManager.NETWORK_TYPE_HSPAP: return "HSPA+";
            default: return "Unknown";
        }
    }

    /**
     * Get phone type name
     */
    private String getPhoneTypeName(int type) {
        switch (type) {
            case TelephonyManager.PHONE_TYPE_GSM: return "GSM";
            case TelephonyManager.PHONE_TYPE_CDMA: return "CDMA";
            case TelephonyManager.PHONE_TYPE_SIP: return "SIP";
            default: return "None";
        }
    }

    /**
     * Get SIM state name
     */
    private String getSimStateName(int state) {
        switch (state) {
            case TelephonyManager.SIM_STATE_ABSENT: return "Absent";
            case TelephonyManager.SIM_STATE_PIN_REQUIRED: return "PIN Required";
            case TelephonyManager.SIM_STATE_PUK_REQUIRED: return "PUK Required";
            case TelephonyManager.SIM_STATE_NETWORK_LOCKED: return "Network Locked";
            case TelephonyManager.SIM_STATE_READY: return "Ready";
            default: return "Unknown";
        }
    }
}
