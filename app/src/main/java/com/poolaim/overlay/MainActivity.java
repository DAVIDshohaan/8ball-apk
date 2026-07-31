package com.poolaim.overlay;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private Button btnStart;
    private Button btnStop;
    private TextView tvStatus;
    private boolean overlayActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        tvStatus = findViewById(R.id.tvStatus);

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestOverlayPermission();
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
        updateUI();
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                .setTitle("Overlay Permission Required")
                .setMessage("This app needs overlay permission to draw aiming lines on top of 8 Ball Pool.")
                .setPositiveButton("Grant", (dialog, which) -> {
                    Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                    );
                    startActivity(intent);
                })
                .setNegativeButton("Cancel", null)
                .show();
        } else {
            startOverlay();
        }
    }

    private void startOverlay() {
        Intent intent = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        overlayActive = true;
        updateUI();
    }

    private void stopOverlay() {
        Intent intent = new Intent(this, OverlayService.class);
        stopService(intent);
        overlayActive = false;
        updateUI();
    }

    private void updateUI() {
        btnStart.setVisibility(overlayActive ? View.GONE : View.VISIBLE);
        btnStop.setVisibility(overlayActive ? View.VISIBLE : View.GONE);
        tvStatus.setText(overlayActive ? "Status: Overlay Active" : "Status: Idle");
    }
}
