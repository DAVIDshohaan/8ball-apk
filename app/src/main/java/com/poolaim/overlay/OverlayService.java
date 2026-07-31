package com.poolaim.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;

public class OverlayService extends Service {

    private static final String CHANNEL_ID = "pool_aim_overlay_channel";
    private static final int ANALYZE_W = 320;

    private WindowManager windowManager;
    private MediaProjection mediaProjection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private HandlerThread analyzeThread;
    private Handler analyzeHandler;
    private OverlayView overlayView;

    private final ScreenAnalyzer analyzer = new ScreenAnalyzer();
    private final GameState state = new GameState();
    private boolean running = false;

    private int screenW, screenH;
    private long frameCount = 0;
    private long fpsWindowStart = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_STICKY;
        if (intent == null || !intent.hasExtra("resultCode")) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent data = intent.getParcelableExtra("data");
        if (resultCode == 0 || data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, createNotification());
        }

        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        int capW = Math.max(1, screenW / 2);
        int capH = Math.max(1, screenH / 2);

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = mpm.getMediaProjection(resultCode, data);
        if (mediaProjection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                super.onStop();
                stopSelf();
            }
        }, null);

        imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);

        analyzeThread = new HandlerThread("poolaim-analyzer");
        analyzeThread.start();
        analyzeHandler = new Handler(analyzeThread.getLooper());

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "poolaim-capture", capW, capH, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, analyzeHandler);

        imageReader.setOnImageAvailableListener(frameListener, analyzeHandler);

        overlayView = new OverlayView(this);
        showOverlay();
        running = true;
        return START_STICKY;
    }

    private final ImageReader.OnImageAvailableListener frameListener = reader -> {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        try {
            synchronized (state) {
                analyzer.analyze(image, state);
                frameCount++;
                long now = System.currentTimeMillis();
                if (fpsWindowStart == 0) fpsWindowStart = now;
                long dt = now - fpsWindowStart;
                if (dt >= 1000) {
                    state.fps = (int) (frameCount * 1000f / dt);
                    frameCount = 0;
                    fpsWindowStart = now;
                }
            }
        } finally {
            image.close();
        }
        if (overlayView != null) {
            overlayView.postInvalidate();
        }
    };

    private void showOverlay() {
        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);

        windowManager.addView(overlayView, params);
    }

    private void hideOverlay() {
        try {
            windowManager.removeView(overlayView);
        } catch (Exception e) {
            // not attached
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        hideOverlay();
        if (imageReader != null) imageReader.setOnImageAvailableListener(null, null);
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (analyzeThread != null) {
            analyzeThread.quitSafely();
            analyzeThread = null;
        }
        super.onDestroy();
    }

    private class OverlayView extends View {
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF tmp = new RectF();

        OverlayView(Context context) {
            super(context);
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(28f);
            textPaint.setShadowLayer(4f, 1f, 1f, Color.BLACK);
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (getWidth() == 0 || getHeight() == 0) return;

            GameState s;
            synchronized (state) {
                s = state;
            }

            float sx = getWidth() / 320f;
            float sy = getHeight() / s.analyzeH;

            if (!s.tableFound) {
                drawHint(canvas, "Waiting for game table...");
                invalidate();
                return;
            }

            // Table frame
            fillPaint.setStyle(Paint.Style.STROKE);
            fillPaint.setColor(Color.argb(160, 255, 255, 255));
            fillPaint.setStrokeWidth(2.5f);
            tmp.set(s.tableL * sx, s.tableT * sy, s.tableR * sx, s.tableB * sy);
            canvas.drawRoundRect(tmp, 12f, 12f, fillPaint);

            // Pockets
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(Color.argb(120, 0, 0, 0));
            float pocketR = (s.tableR - s.tableL) * sx * 0.018f;
            for (float[] p : s.pockets) {
                canvas.drawCircle(p[0] * sx, p[1] * sy, pocketR, fillPaint);
            }

            // Static lines: every ball -> best pocket
            for (GameState.Line l : s.staticLines) {
                linePaint.setColor(l.argb);
                linePaint.setStrokeWidth(Math.max(1f, l.width * sx));
                linePaint.setStyle(Paint.Style.STROKE);
                linePaint.setPathEffect(l.blocked ? new DashPathEffect(new float[]{8f, 8f}, 0) : null);
                canvas.drawLine(l.x1 * sx, l.y1 * sy, l.x2 * sx, l.y2 * sy, linePaint);
                linePaint.setPathEffect(null);
            }

            // Dynamic lines: cue ray + target path + bank reflections
            for (GameState.Line l : s.dynamicLines) {
                linePaint.setColor(l.argb);
                linePaint.setStrokeWidth(Math.max(1.5f, l.width * sx));
                linePaint.setStyle(Paint.Style.STROKE);
                linePaint.setPathEffect(l.dashed || l.blocked ? new DashPathEffect(new float[]{20f, 14f}, 0) : null);
                canvas.drawLine(l.x1 * sx, l.y1 * sy, l.x2 * sx, l.y2 * sy, linePaint);
                linePaint.setPathEffect(null);
            }

            // Ball markers & Stripe indicators
            for (GameState.Ball b : s.balls) {
                if (b == s.cueBall) continue;
                if (b.isStripe) {
                    fillPaint.setStyle(Paint.Style.STROKE);
                    fillPaint.setColor(Color.argb(220, 255, 255, 255));
                    fillPaint.setStrokeWidth(1.8f);
                    canvas.drawCircle(b.x * sx, b.y * sy, b.r * sx, fillPaint);
                }
            }

            // Ghost ball
            if (s.ghostX >= 0) {
                float gr = s.tableR - s.tableL;
                gr = gr * sx * 0.0112f;
                linePaint.setStyle(Paint.Style.STROKE);
                linePaint.setColor(Color.WHITE);
                linePaint.setStrokeWidth(2f);
                canvas.drawCircle(s.ghostX * sx, s.ghostY * sy, gr, linePaint);
            }

            // Target ball highlight
            if (s.targetBall != null) {
                float br = s.targetBall.r * sx;
                linePaint.setStyle(Paint.Style.STROKE);
                linePaint.setColor(s.targetBall.isStripe ? Color.argb(255, 255, 180, 0) : Color.argb(255, 0, 255, 136));
                linePaint.setStrokeWidth(3.5f);
                canvas.drawCircle(s.targetBall.x * sx, s.targetBall.y * sy, br + 3f, linePaint);
            }

            // Cue ball ring
            if (s.cueBall != null) {
                linePaint.setStyle(Paint.Style.STROKE);
                linePaint.setColor(Color.argb(200, 255, 220, 0));
                linePaint.setStrokeWidth(2f);
                canvas.drawCircle(s.cueBall.x * sx, s.cueBall.y * sy, s.cueBall.r * sx + 2f, linePaint);
            }

            // HUD text (small, top-left, does not block table)
            drawHint(canvas,
                    String.format("fps:%d balls:%d %s",
                            s.fps, s.balls.size(), s.aiming ? "AIM" : "IDLE"));

            invalidate();
        }

        private void drawHint(Canvas canvas, String msg) {
            textPaint.setTextAlign(Paint.Align.LEFT);
            float tw = textPaint.measureText(msg);
            float ty = getHeight() - 24f;
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(Color.argb(150, 0, 0, 0));
            canvas.drawRoundRect(16f, ty - 44f, 16f + tw + 24f, ty + 12f, 8f, 8f, fillPaint);
            canvas.drawText(msg, 24f, ty, textPaint);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Overlay Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Pool Aim Overlay")
                    .setContentText("Screen capture running")
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .build();
        } else {
            return new Notification.Builder(this)
                    .setContentTitle("Pool Aim Overlay")
                    .setContentText("Screen capture running")
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .build();
        }
    }
}
