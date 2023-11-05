/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.ssrlive.toyvpn;

import static java.nio.charset.StandardCharsets.US_ASCII;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.pm.PackageManager;
import android.net.ProxyInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class ToyVpnRunnable implements Runnable {
    /**
     * Callback interface to let the {@link ToyVpnService} know about new connections
     * and update the foreground notification with connection status.
     */
    public interface OnConnectListener {
        public enum Stage {
            taskLaunch, connecting, establish, disconnected, taskTerminate;
        }
        void onConnectStage(Stage stage);
    }

    /** Maximum packet size is constrained by the MTU, which is given as a signed short. */
    private static final int MAX_PACKET_SIZE = Short.MAX_VALUE;

    /** Time to wait in between losing the connection and retrying. */
    private static final long RECONNECT_WAIT_MS = TimeUnit.SECONDS.toMillis(3);

    /** Time between keepalives if there is no traffic at the moment.
     *
     * TODO: don't do this; it's much better to let the connection die and then reconnect when
     *       necessary instead of keeping the network hardware up for hours on end in between.
     **/
    private static final long KEEPALIVE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(15);

    /** Time to wait without receiving any response before assuming the server is gone. */
    private static final long RECEIVE_TIMEOUT_MS = KEEPALIVE_INTERVAL_MS * 3;

    /**
     * Time between polling the VPN interface for new traffic, since it's non-blocking.
     *
     * TODO: really don't do this; a blocking read on another thread is much cleaner.
     */
    private static final long IDLE_INTERVAL_MS = TimeUnit.MILLISECONDS.toMillis(100);

    /**
     * Number of periods of length {@IDLE_INTERVAL_MS} to wait before declaring the handshake a
     * complete and abject failure.
     *
     * TODO: use a higher-level protocol; hand-rolling is a fun but pointless exercise.
     */
    private static final int MAX_HANDSHAKE_ATTEMPTS = 50;

    private final VpnService mService;
    private final int mConnectionId;

    private final String mServerName;
    private final int mServerPort;
    private final byte[] mSharedSecret;

    private PendingIntent mConfigureIntent;
    private OnConnectListener mOnConnectListener;

    // Proxy settings
    private String mProxyHostName;
    private int mProxyHostPort;

    // Allowed/Disallowed packages for VPN usage
    private final boolean mAllow;
    private final Set<String> mPackages;

    public ToyVpnRunnable(final VpnService service, final int connectionId,
                          final String serverName, final int serverPort, final byte[] sharedSecret,
                          final String proxyHostName, final int proxyHostPort, boolean allow,
                          final Set<String> packages) {
        mService = service;
        mConnectionId = connectionId;

        mServerName = serverName;
        mServerPort= serverPort;
        mSharedSecret = sharedSecret;

        if (!TextUtils.isEmpty(proxyHostName)) {
            mProxyHostName = proxyHostName;
        }
        if (proxyHostPort > 0) {
            // The port value is always an integer due to the configured inputType.
            mProxyHostPort = proxyHostPort;
        }
        mAllow = allow;
        mPackages = packages;
    }

    /**
     * Optionally, set an intent to configure the VPN. This is {@code null} by default.
     */
    public void setConfigureIntent(PendingIntent intent) {
        mConfigureIntent = intent;
    }

    public void setOnConnectListener(OnConnectListener listener) {
        mOnConnectListener = listener;
    }

    @Override
    public void run() {
        try {
            Log.i(getTag(), "Thread starting");
            synchronized (mService) {
                if (mOnConnectListener != null) {
                    mOnConnectListener.onConnectStage(OnConnectListener.Stage.taskLaunch);
                }
            }

            // If anything needs to be obtained using the network, get it now.
            // This greatly reduces the complexity of seamless handover, which
            // tries to recreate the tunnel without shutting down everything.
            // In this demo, all we need to know is the server address.
            final SocketAddress serverAddress = new InetSocketAddress(mServerName, mServerPort);

            // We try to create the tunnel several times.
            // TODO: The better way is to work with ConnectivityManager, trying only when the
            //       network is available.
            // Here we just use a counter to keep things simple.
            for (int attempt = 0; attempt < 10; ++attempt) {
                // Reset the counter if we were connected.
                if (run(serverAddress)) {
                    attempt = 0;
                }

                // Sleep for a while. This also checks if we got interrupted.
                Thread.sleep(3000);
            }
            Log.i(getTag(), "Giving up");
        } catch (InterruptedException | IllegalArgumentException | IllegalStateException e) {
            Log.e(getTag(), "Connection failed, exiting", e);
        } finally {
            Log.i(getTag(), "Thread dying");
            synchronized (mService) {
                if (mOnConnectListener != null) {
                    mOnConnectListener.onConnectStage(OnConnectListener.Stage.taskTerminate);
                }
            }
        }
    }

    private boolean run(SocketAddress server)
            throws InterruptedException, IllegalArgumentException, IllegalStateException {
        DatagramChannel tunnel = null;
        ParcelFileDescriptor iface = null;

        try {
            synchronized (mService) {
                if (mOnConnectListener != null) {
                    mOnConnectListener.onConnectStage(OnConnectListener.Stage.connecting);
                }
            }

            // Create a DatagramChannel as the VPN tunnel.
            tunnel = DatagramChannel.open();

            // Protect the tunnel before connecting to avoid loopback.
            if (!mService.protect(tunnel.socket())) {
                throw new IllegalStateException("Cannot protect the tunnel");
            }

            // Connect to the server.
            // tunnel.connect(server);

            // For simplicity, we use the same thread for both reading and
            // writing. Here we put the tunnel into non-blocking mode.
            // tunnel.configureBlocking(false);

            // Authenticate with server and configure the virtual network interface.
            // String parameters = handshakeServer(tunnel);
            iface = configureVirtualInterface();

            synchronized (mService) {
                if (mOnConnectListener != null) {
                    mOnConnectListener.onConnectStage(OnConnectListener.Stage.establish);
                }
            }


            // Packets to be sent are queued in this input stream.
            FileInputStream ifaceIn = new FileInputStream(iface.getFileDescriptor());

            // Packets received need to be written to this output stream.
            FileOutputStream ifaceOut = new FileOutputStream(iface.getFileDescriptor());

            // Allocate the buffer for a single packet.
            ByteBuffer packet = ByteBuffer.allocate(MAX_PACKET_SIZE);

            // We keep forwarding packets till something goes wrong.
            //noinspection InfiniteLoopStatement
            while (true) {
                // Assume that we did not make any progress in this iteration.

                // Read the outgoing packet from the input stream (Virtual Interface).
                int length = ifaceIn.read(packet.array());

                StringBuilder hexString = new StringBuilder();
                for (byte b : packet.array()) {
                    hexString.append(String.format("%02X ", b)); // Convert each byte to a two-character hexadecimal representation
                }

                String logMessage = hexString.toString();
                Log.i("YourTag", logMessage);

                if (length > 0) {
                    // Write the outgoing packet to the tunnel (server).
                    packet.limit(length);
//                     tunnel.write(packet);
                    packet.clear();

                    // There might be more outgoing packets.
                }

                ifaceOut.write(packet.array(), 0, length);


            }
        } catch (IOException e) {
            Log.e(getTag(), "Cannot use socket", e);
        } finally {

            synchronized (mService) {
                if (mOnConnectListener != null) {
                    mOnConnectListener.onConnectStage(OnConnectListener.Stage.disconnected);
                }
            }

            try {
                if (iface != null) {
                    iface.close();
                }
                if (tunnel != null) {
                    tunnel.disconnect();
                    tunnel.close();
                }
            } catch (Exception e) {
                Log.e(getTag(), "Unable to close interface", e);
            }
        }
        return true;
    }

    private ParcelFileDescriptor configureVirtualInterface()
            throws IllegalArgumentException {
        // Configure a builder while parsing the parameters.
        VpnService.Builder builder = mService.new Builder();

        builder.setSession("MyVPNService");

        // Set the VPN interface address (e.g., "10.0.0.1")
        builder.addAddress("10.0.0.1", 32);

        // Set the DNS server address (e.g., "8.8.8.8")
        builder.addDnsServer("8.8.8.8");

        // Set the VPN interface routes (e.g., "0.0.0.0" to route all traffic)
        builder.addRoute("0.0.0.0", 0);

        // Set the VPN interface's MTU (Maximum Transmission Unit)
        builder.setMtu(1500);

        // Create a new interface using the builder and save the parameters.
        return builder.establish();
    }

    private String getTag() {
        return ToyVpnRunnable.class.getSimpleName() + "[" + mConnectionId + "]";
    }
}
