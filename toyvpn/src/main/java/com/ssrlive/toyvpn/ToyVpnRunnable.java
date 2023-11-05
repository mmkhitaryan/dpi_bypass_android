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

    private static final int MAX_PACKET_SIZE = Short.MAX_VALUE;
    private final VpnService mService;
    private PendingIntent mConfigureIntent;
    private OnConnectListener mOnConnectListener;


    public ToyVpnRunnable(final VpnService service) {
        mService = service;
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
        ParcelFileDescriptor iface = null;

        try {
            synchronized (mService) {
                if (mOnConnectListener != null) {
                    mOnConnectListener.onConnectStage(OnConnectListener.Stage.connecting);
                }
            }

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
                // Read the outgoing packet from the input stream (Virtual Interface).
                int length = ifaceIn.read(packet.array());

                if (length > 0) { // TODO: Is it even possible for a packet to be 0 bytes??
                    // Here I need to implement the packet manipulations for DPI bypass
                    ifaceOut.write(packet.array(), 0, length);
                    packet.clear();
                }
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
            } catch (Exception e) {
                Log.e(getTag(), "Unable to close interface", e);
            }
        }
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
        return ToyVpnRunnable.class.getSimpleName();
    }
}
