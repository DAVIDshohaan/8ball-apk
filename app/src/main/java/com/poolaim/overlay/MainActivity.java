package com.poolaim.overlay;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_OVERLAY = 1001;
    private static final int REQ_CAPTURE = 1002;
    private static final int REQ_NOTIF = 1003;

    private Button btnStart;
    private Button btnStop;
    private TextView tvStatus;
    private boolean overlayActive = false;

    private void trace(String msg) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "trace.txt");
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(System.currentTimeMillis() + " " + msg + "\n");
            w.close();
            Log.i("PoolAim", msg);
        } catch (Exception e) { Log.e("PoolAim", "trace FAILED " + e); }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        trace("onCreate");
        // The overlay targets the game (always landscape). Forcing landscape
        // here guarantees the capture buffer matches the game's orientation
        // when the service starts, on ALL Android versions (Android 16 forbids
        // recreating the VirtualDisplay on rotation, so we must start correct).
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.activity_main);
        overlayActive = OverlayService.serviceAlive;

        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        tvStatus = findViewById(R.id.tvStatus);

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startFlow();
            }
        });

        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopOverlay();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        trace("onResume");
        updateUI();
    }

    @Override
    protected void onDestroy() {
        trace("onDestroy");
        super.onDestroy();
    }

    private void startFlow() {
        trace("startFlow entered; canDrawOverlays=" + Settings.canDrawOverlays(this));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.i("PoolAim", "startFlow: overlay permission missing, showing grant dialog");
            new AlertDialog.Builder(this)
                .setTitle("Overlay Permission Required")
                .setMessage("This app needs overlay permission to draw aiming lines on top of 8 Ball Pool.")
                .setPositiveButton("Grant", (dialog, which) -> {
                    Log.i("PoolAim", "startFlow: user tapped Grant, opening settings");
                    Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                    );
                    startActivity(intent);
                })
                .setNegativeButton("Cancel", null)
                .show();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                   ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                       != PackageManager.PERMISSION_GRANTED) {
            Log.i("PoolAim", "startFlow: notif permission missing, requesting");
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        } else {
            Log.i("PoolAim", "startFlow: all perms OK, calling requestCapture");
            requestCapture();
        }
    }

    private void requestCapture() {
        trace("requestCapture called");
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
            Log.i("PoolAim", "requestCapture: intent launched");
        } catch (Exception e) {
            Log.e("PoolAim", "requestCapture FAILED: " + e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE) {
                trace("onActivityResult REQ_CAPTURE result=" + resultCode + " data=" + (data != null));
            if (resultCode == RESULT_OK && data != null) {
                Intent svc = new Intent(this, OverlayService.class);
                svc.putExtra("resultCode", resultCode);
                svc.putExtra("data", data);
                trace("starting OverlayService resultCode=" + resultCode);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc);
                    trace("startForegroundService called");
                } else {
                    startService(svc);
                    trace("startService called");
                }
                overlayActive = true;
                updateUI();
                moveTaskToBack(true);
                finish();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) {
            requestCapture();
        }
    }

    private void stopOverlay() {
        stopService(new Intent(this, OverlayService.class));
        overlayActive = false;
        updateUI();
    }

    private void updateUI() {
        btnStart.setVisibility(overlayActive ? View.GONE : View.VISIBLE);
        btnStop.setVisibility(overlayActive ? View.VISIBLE : View.GONE);
        tvStatus.setText(overlayActive
            ? "Status: Capturing - open 8 Ball Pool and play a game"
            : "Status: Idle - tap Start and allow screen capture");
    }
}
