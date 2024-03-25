package com.example.testvpn;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.ProxyInfo;
import android.net.VpnService;
import android.os.Bundle;
import android.app.KeyguardManager;
import android.content.Intent;

import android.os.Process;
import android.system.OsConstants;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import android.util.Log;
import android.widget.Button;

public class MainActivity extends AppCompatActivity {
    public static String TAG = "VpnTest";
    public static int TIMEOUT_MS = 3 * 1000;
    public static int SOCKET_TIMEOUT_MS = 100;
    private String mPackageName;
    private ConnectivityManager mCM;

    Network mNetwork;
    ConnectivityManager.NetworkCallback mCallback;
    final Object mLock = new Object();

    private final LinkedBlockingQueue<Integer> mResult = new LinkedBlockingQueue<>(1);
    private ActivityResultLauncher requestDataLauncher;
    private Runnable r1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        // Dismiss the keyguard so that the tests can click on the VPN confirmation dialog.
        // FLAG_DISMISS_KEYGUARD is not sufficient to do this because as soon as the dialog appears,
        // this activity goes into the background and the keyguard reappears.
        getSystemService(KeyguardManager.class).requestDismissKeyguard(this, null /* callback */);

        setContentView(R.layout.activity_main);

        try {
            setUp();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }


        Button button=findViewById(R.id.button);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
//                Toast.makeText(ButtonActivity.this,"按钮被点击了",Toast.LENGTH_SHORT).show();
                try {
                    new Thread(new Runnable(){
                        @Override
                        public void run() {
                            try {
                                testAppDisallowed();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }).start();

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });


    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        Log.i(TAG, "Got old onActivityResult resultCode =" + resultCode);
        if (mResult.offer(resultCode) == false) {
            throw new RuntimeException("Queue is full! This should never happen");
        }
    }


    public Integer getResult(int timeoutMs) throws InterruptedException {
        return mResult.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private void prepareVpn() throws Exception {
        final int REQUEST_ID = 42;

        // Attempt to prepare.
        Log.i(TAG, "Preparing VPN");
        Intent intent = VpnService.prepare(this);

        requestDataLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), new ActivityResultCallback<ActivityResult>() {
            @Override
            public void onActivityResult(ActivityResult result) {
                Intent data = result.getData();
                int resultCode = result.getResultCode();

                Log.i(TAG, "Got new onActivityResult resultCode =" + resultCode);
                if (resultCode != RESULT_OK) {
                    return;
                }

                String disallowedApps  = null;
                disallowedApps  = mPackageName;
                Log.i(TAG, "Append shell app to disallowedApps: " + disallowedApps);

                try {
                    startVpn(new String[] {"192.0.2.2/32", "2001:db8:1:2::ffe/128"},
                            new String[] {"192.0.2.0/24", "2001:db8::/32"},
                            new String[0] /* excludedRoutes */,
                            "", disallowedApps,
                            null /*proxyInfo*/,
                            null /* underlyingNetworks */,
                            false /* isAlwaysMetered */,
                            false /* addRoutesByIpPrefix */);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });

        if (intent != null) {
            Log.i(TAG, "Start the confirmation dialog VPN");
            // Start the confirmation dialog and click OK.
//            startActivityForResult(intent, REQUEST_ID);
            requestDataLauncher.launch(intent);
        }
    }

    public void setUp() throws Exception {
        mNetwork = null;
        mCallback = null;

        mPackageName = getPackageName();
        mCM = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        prepareVpn();
    }


    private void startVpn(String[] addresses, String[] routes, String[] excludedRoutes,
                              String allowedApplications, String disallowedApplications,
                              @Nullable ProxyInfo proxyInfo, @Nullable ArrayList<Network> underlyingNetworks,
                              boolean isAlwaysMetered, boolean addRoutesByIpPrefix)
            throws Exception {
        final Intent intent = new Intent(this, MyVpnService.class)
                .putExtra(mPackageName + ".cmd", MyVpnService.CMD_CONNECT)
                .putExtra(mPackageName + ".addresses", TextUtils.join(",", addresses))
                .putExtra(mPackageName + ".routes", TextUtils.join(",", routes))
                .putExtra(mPackageName + ".excludedRoutes", TextUtils.join(",", excludedRoutes))
                .putExtra(mPackageName + ".allowedapplications", allowedApplications)
                .putExtra(mPackageName + ".disallowedapplications", disallowedApplications)
                .putExtra(mPackageName + ".httpProxy", proxyInfo)
                .putParcelableArrayListExtra(
                        mPackageName + ".underlyingNetworks", underlyingNetworks)
                .putExtra(mPackageName + ".isAlwaysMetered", isAlwaysMetered)
                .putExtra(mPackageName + ".addRoutesByIpPrefix", addRoutesByIpPrefix);
        this.startService(intent);
        Log.i(TAG, "establishVpn over");
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException e) {
            }
        }
    }
    private void checkTcpReflection(String to, String expectedFrom) throws IOException {
        // Exercise TCP over the VPN by "connecting to ourselves". We open a server socket and a
        // client socket, and connect the client socket to a remote host, with the port of the
        // server socket. The PacketReflector reflects the packets, changing the source addresses
        // but not the ports, so our client socket is connected to our server socket, though both
        // sockets think their peers are on the "remote" IP address.

        // Open a listening socket.
        ServerSocket listen = new ServerSocket(0, 10, InetAddress.getByName("::"));

        // Connect the client socket to it.
        InetAddress toAddr = InetAddress.getByName(to);
        Socket client = new Socket();
        try {
            Log.i(TAG, "checkTcpReflection client.connect to :"+ to + ",port : "+ listen.getLocalPort()+"...chenxyxy...");
            client.connect(new InetSocketAddress(toAddr, listen.getLocalPort()), SOCKET_TIMEOUT_MS);
            if (expectedFrom == null) {
                closeQuietly(listen);
                closeQuietly(client);
                Log.i(TAG,"Expected connection to fail, but it succeeded.");
                return;
            }
        } catch (IOException e) {
            if (expectedFrom != null) {
                closeQuietly(listen);
                Log.i(TAG,"Expected connection to succeed, but it failed.");
                return;
            } else {
                Log.i(TAG,"We expected the connection to fail, and it did, so there's nothing more to test..");
                // We expected the connection to fail, and it did, so there's nothing more to test.
                return;
            }
        }

        // The connection succeeded, and we expected it to succeed. Send some data; if things are
        // working, the data will be sent to the VPN, reflected by the PacketReflector, and arrive
        // at our server socket. For good measure, send some data in the other direction.
        Socket server = null;
        try {
            // Accept the connection on the server side.
            listen.setSoTimeout(SOCKET_TIMEOUT_MS);
            server = listen.accept();
            checkConnectionOwnerUidTcp(client);
            checkConnectionOwnerUidTcp(server);
            // Check that the source and peer addresses are as expected.
            Log.i(TAG,"expectedFrom "+ expectedFrom + "client.getLocalAddress().getHostAddress() " + client.getLocalAddress().getHostAddress());
            Log.i(TAG,"expectedFrom "+ expectedFrom + "server.getLocalAddress().getHostAddress() " + server.getLocalAddress().getHostAddress());
            Log.i(TAG,"SocketAddress " + new InetSocketAddress(toAddr, client.getLocalPort())
                    + "server SocketAddress " + server.getRemoteSocketAddress());
            Log.i(TAG,"SocketAddress " + new InetSocketAddress(toAddr, server.getLocalPort())
                    + "client SocketAddress " + client.getRemoteSocketAddress());

            // Now write some data.
            final int LENGTH = 32768;
            byte[] data = new byte[LENGTH];
            new Random().nextBytes(data);

            // Make sure our writes don't block or time out, because we're single-threaded and can't
            // read and write at the same time.
            server.setReceiveBufferSize(LENGTH * 2);
            client.setSendBufferSize(LENGTH * 2);
            client.setSoTimeout(SOCKET_TIMEOUT_MS);
            server.setSoTimeout(SOCKET_TIMEOUT_MS);

            // Send some data from client to server, then from server to client.
            writeAndCheckData(client.getOutputStream(), server.getInputStream(), data);
            writeAndCheckData(server.getOutputStream(), client.getInputStream(), data);
        } finally {
            closeQuietly(listen);
            closeQuietly(client);
            closeQuietly(server);
        }
    }

    private static void writeAndCheckData(
            OutputStream out, InputStream in, byte[] data) throws IOException {
        out.write(data, 0, data.length);
        out.flush();

        byte[] read = new byte[data.length];
        int bytesRead = 0, totalRead = 0;
        do {
            bytesRead = in.read(read, totalRead, read.length - totalRead);
            totalRead += bytesRead;
        } while (bytesRead >= 0 && totalRead < data.length);
        Log.i(TAG,"totalRead " + totalRead +" data.length "+ data.length);
        Log.i(TAG,"data " + data  + " read " +read);
    }
    private void checkConnectionOwnerUidTcp(Socket s) {
        final int expectedUid = Process.myUid();
        InetSocketAddress loc = new InetSocketAddress(s.getLocalAddress(), s.getLocalPort());
        InetSocketAddress rem = new InetSocketAddress(s.getInetAddress(), s.getPort());
        int uid = mCM.getConnectionOwnerUid(OsConstants.IPPROTO_TCP, loc, rem);
        Log.i(TAG," expectedUid " +expectedUid +" uid " + uid);
    }

    public void testAppDisallowed() throws Exception {
        Log.i(TAG,"Begin testAppDisallowed ");
        checkTcpReflection("192.0.2.251",null);
        checkTcpReflection("2001:db8:dead:beef::f00",null);
    }

}