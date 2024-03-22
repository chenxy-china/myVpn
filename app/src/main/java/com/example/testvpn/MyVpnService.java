package com.example.testvpn;

import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.InetAddresses;
import android.net.IpPrefix;
import android.net.Network;
//import android.net.NetworkUtils;
import android.net.ProxyInfo;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

//import com.android.modules.utils.build.SdkLevel;
//import com.android.networkstack.apishim.VpnServiceBuilderShimImpl;
//import com.android.networkstack.apishim.common.UnsupportedApiLevelException;
//import com.android.networkstack.apishim.common.VpnServiceBuilderShim;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class MyVpnService extends VpnService {

    private static String TAG = "MyVpnService";
    private static int MTU = 1799;

    public static final String ACTION_ESTABLISHED = "com.android.cts.net.hostside.ESTABNLISHED";
    public static final String EXTRA_ALWAYS_ON = "is-always-on";
    public static final String EXTRA_LOCKDOWN_ENABLED = "is-lockdown-enabled";
    public static final String CMD_CONNECT = "connect";
    public static final String CMD_DISCONNECT = "disconnect";
    public static final String CMD_UPDATE_UNDERLYING_NETWORKS = "update_underlying_networks";

    private ParcelFileDescriptor mFd = null;
//    private PacketReflector mPacketReflector = null;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG ,"onStartCommand start");

        String packageName = getPackageName();
        String cmd = intent.getStringExtra(packageName + ".cmd");
        if (CMD_DISCONNECT.equals(cmd)) {
            stop();
        } else if (CMD_CONNECT.equals(cmd)) {
            start(packageName, intent);
        } else if (CMD_UPDATE_UNDERLYING_NETWORKS.equals(cmd)) {
            updateUnderlyingNetworks(packageName, intent);
        }

        return START_NOT_STICKY;
    }

    /**
     * Utility method to parse strings such as "192.0.2.5/24" or "2001:db8::cafe:d00d/64".
     * @hide
     */
    public static Pair<InetAddress, Integer> parseIpAndMask(String ipAndMaskString) {
        InetAddress address = null;
        int prefixLength = -1;
        try {
            String[] pieces = ipAndMaskString.split("/", 2);
            prefixLength = Integer.parseInt(pieces[1]);
            address = InetAddresses.parseNumericAddress(pieces[0]);
        } catch (NullPointerException e) {            // Null string.
        } catch (ArrayIndexOutOfBoundsException e) {  // No prefix length.
        } catch (NumberFormatException e) {           // Non-numeric prefix.
        } catch (IllegalArgumentException e) {        // Invalid IP address.
        }

        if (address == null || prefixLength == -1) {
            throw new IllegalArgumentException("Invalid IP address and mask " + ipAndMaskString);
        }

        return new Pair<InetAddress, Integer>(address, prefixLength);
    }

    private void updateUnderlyingNetworks(String packageName, Intent intent) {
        final ArrayList<Network> underlyingNetworks =
                intent.getParcelableArrayListExtra(packageName + ".underlyingNetworks");
        setUnderlyingNetworks(
                (underlyingNetworks != null) ? underlyingNetworks.toArray(new Network[0]) : null);
    }

    private String parseIpAndMaskListArgument(String packageName, Intent intent, String argName,
                                              BiConsumer<InetAddress, Integer> consumer) {
        final String addresses = intent.getStringExtra(packageName + "." + argName);
        Log.i(TAG ,"parseIpAndMaskListArgument addresses " + addresses);

        if (TextUtils.isEmpty(addresses)) {
            return null;
        }

        final String[] addressesArray = addresses.split(",");
        for (String address : addressesArray) {
            final Pair<InetAddress, Integer> ipAndMask = parseIpAndMask(address);
            consumer.accept(ipAndMask.first, ipAndMask.second);
        }

        return addresses;
    }

    private String parseIpPrefixListArgument(String packageName, Intent intent, String argName,
                                             Consumer<IpPrefix> consumer) {
        return parseIpAndMaskListArgument(packageName, intent, argName,
                (inetAddress, prefixLength) -> consumer.accept(
                        new IpPrefix(inetAddress, prefixLength)));
    }

    private void start(String packageName, Intent intent) {
        Log.i(TAG ,"start");
        Builder builder = new Builder();
//        VpnServiceBuilderShim vpnServiceBuilderShim = VpnServiceBuilderShimImpl.newInstance();

        final String addresses = parseIpAndMaskListArgument(packageName, intent, "addresses",
                builder::addAddress);

        String addedRoutes = parseIpAndMaskListArgument(packageName, intent, "routes",
                    builder::addRoute);

        String excludedRoutes = null;
//        if (SdkLevel.isAtLeastT()) {
//            excludedRoutes = parseIpPrefixListArgument(packageName, intent, "excludedRoutes",
//                    new Consumer<IpPrefix>() {
//                        @Override
//                        public void accept(IpPrefix prefix) {
//                            try {
//                                vpnServiceBuilderShim.excludeRoute(builder, prefix);
//                            } catch (UnsupportedApiLevelException e) {
//                                throw new RuntimeException(e);
//                            }
//                        }
//                    });
//        }

        //////////////////
        excludedRoutes = intent.getStringExtra(packageName + ".excludedRoutes");

        if (!TextUtils.isEmpty(excludedRoutes)) {
            final String[] addressesArray = excludedRoutes.split(",");
            for (String address : addressesArray) {
                final Pair<InetAddress, Integer> ipAndMask = parseIpAndMask(address);
                IpPrefix prefix = new IpPrefix(ipAndMask.first, ipAndMask.second);
                builder.excludeRoute(prefix);
            }
        }

        /////////////////

        String allowed = intent.getStringExtra(packageName + ".allowedapplications");
        if (allowed != null) {
            String[] packageArray = allowed.split(",");
            for (int i = 0; i < packageArray.length; i++) {
                String allowedPackage = packageArray[i];
                if (!TextUtils.isEmpty(allowedPackage)) {
                    try {
                        builder.addAllowedApplication(allowedPackage);
                    } catch(NameNotFoundException e) {
                        continue;
                    }
                }
            }
        }

        String disallowed = intent.getStringExtra(packageName + ".disallowedapplications");
        if (disallowed != null) {
            String[] packageArray = disallowed.split(",");
            for (int i = 0; i < packageArray.length; i++) {
                String disallowedPackage = packageArray[i];
                if (!TextUtils.isEmpty(disallowedPackage)) {
                    try {
                        builder.addDisallowedApplication(disallowedPackage);
                    } catch(NameNotFoundException e) {
                        continue;
                    }
                }
            }
        }

        ArrayList<Network> underlyingNetworks =
                intent.getParcelableArrayListExtra(packageName + ".underlyingNetworks");
        if (underlyingNetworks == null) {
            // VPN tracks default network
            builder.setUnderlyingNetworks(null);
        } else {
            builder.setUnderlyingNetworks(underlyingNetworks.toArray(new Network[0]));
        }

        boolean isAlwaysMetered = intent.getBooleanExtra(packageName + ".isAlwaysMetered", false);
        builder.setMetered(isAlwaysMetered);

        ProxyInfo vpnProxy = intent.getParcelableExtra(packageName + ".httpProxy");
        builder.setHttpProxy(vpnProxy);
        builder.setMtu(MTU);
        builder.setBlocking(true);
        builder.setSession("MyVpnService");

        Log.i(TAG, "Establishing VPN,"
                + " addresses=" + addresses
                + " addedRoutes=" + addedRoutes
                + " excludedRoutes=" + excludedRoutes
                + " allowedApplications=" + allowed
                + " disallowedApplications=" + disallowed);

        mFd = builder.establish();
        Log.i(TAG, "Established, fd=" + (mFd == null ? "null" : mFd.getFd()));

        broadcastEstablished();

//        mPacketReflector = new PacketReflector(mFd.getFileDescriptor(), MTU);
//        mPacketReflector.start();
    }

    private void broadcastEstablished() {
        final Intent bcIntent = new Intent(ACTION_ESTABLISHED);
        bcIntent.putExtra(EXTRA_ALWAYS_ON, isAlwaysOn());
        bcIntent.putExtra(EXTRA_LOCKDOWN_ENABLED, isLockdownEnabled());
        sendBroadcast(bcIntent);
    }

    private void stop() {
//        if (mPacketReflector != null) {
//            mPacketReflector.interrupt();
//            mPacketReflector = null;
//        }
        try {
            if (mFd != null) {
                Log.i(TAG, "Closing filedescriptor");
                mFd.close();
            }
        } catch(IOException e) {
        } finally {
            mFd = null;
        }
    }

    @Override
    public void onDestroy() {
        stop();
        super.onDestroy();
    }
}
