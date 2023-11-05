/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class ToyVpnClient extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.form);

        findViewById(R.id.connect).setOnClickListener(v -> {

            Intent intent = VpnService.prepare(ToyVpnClient.this);
            if (intent != null) {
                startActivityForResult(intent, 0);
            } else {
                doStartVpnService();
            }
        });
        findViewById(R.id.disconnect).setOnClickListener(v -> {
            startService(getVpnServiceIntent().setAction(ToyVpnService.ACTION_DISCONNECT));
        });
    }

    private void doStartVpnService() {
        startService(getVpnServiceIntent().setAction(ToyVpnService.ACTION_CONNECT));
    }

    private Intent getVpnServiceIntent() {
        return new Intent(this, ToyVpnService.class);
    }

    private boolean checkProxyConfigs(String proxyHost, String proxyPort) {
        final boolean hasIncompleteProxyConfigs = proxyHost.isEmpty() != proxyPort.isEmpty();
        if (hasIncompleteProxyConfigs) {
            Toast.makeText(this, R.string.incomplete_proxy_settings, Toast.LENGTH_SHORT).show();
        }
        return !hasIncompleteProxyConfigs;
    }

    @TargetApi(Build.VERSION_CODES.N)
    private boolean checkPackages(Set<String> packageNames) {
        final boolean hasCorrectPackageNames = packageNames.isEmpty() ||
                getPackageManager().getInstalledPackages(0).stream()
                        .map(pi -> pi.packageName)
                        .collect(Collectors.toSet())
                        .containsAll(packageNames);
        if (!hasCorrectPackageNames) {
            Toast.makeText(this, R.string.unknown_package_names, Toast.LENGTH_SHORT).show();
        }
        return hasCorrectPackageNames;
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (result == RESULT_OK) {
            doStartVpnService();
        }
    }
}
