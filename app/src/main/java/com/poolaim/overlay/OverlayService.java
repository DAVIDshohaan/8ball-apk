package com.poolaim.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.os.Build;
import android.os.IBinder;
import android.view.*;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

public class OverlayService extends Service {
    private WindowManager windowManager;
    private OverlayView overlayView;

    // Line colors
    private static final int LINE_COLOR_YELLOW = Color.argb(180, 255, 255, 0);
    private static final int LINE_COLOR_CYAN = Color.argb(180, 0, 255, 255);
    private static final int LINE_COLOR_WHITE = Color.argb(200, 255, 255, 255);
    private static final int LINE_COLOR_RED = Color.argb(120, 255, 0, 0);

    private static final String CHANNEL_ID = "pool_aim_overlay_channel";
    private static final float[][] POCKETS = {
        {0.07f, 0.07f}, {0.50f, 0.03f}, {0.93f, 0.07f},
        {0.07f, 0.93f}, {0.50f, 0.97f}, {0.93f, 0.93f}
    };

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        overlayView = new OverlayView(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = createNotification();
        startForeground(1, notification);
        showOverlay();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        hideOverlay();
        super.onDestroy();
    }

    private void showOverlay() {
        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        );

        windowManager.addView(overlayView, params);
    }

    private void hideOverlay() {
        try {
            windowManager.removeView(overlayView);
        } catch (Exception e) {
            // View not attached
        }
    }

    private class OverlayView extends View {
        private Paint paint;
        private Paint ballPaint;
        private Paint textPaint;
        private Paint tablePaint;
        private RectF tableBounds;
        private float ballRadius;
        private boolean hasTouch = false;
        private float touchX, touchY;

        // Demo ball positions
        private float cueBallX, cueBallY;
        private float[][] targetBalls;
        private int targetCount = 0;
        private float angle = 0;
        private long lastTime = System.nanoTime();

        public OverlayView(Context context) {
            super(context);

            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);

            ballPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            ballPaint.setStyle(Paint.Style.FILL);

            textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(24f);
            textPaint.setFakeBoldText(true);

            tablePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            tablePaint.setStyle(Paint.Style.STROKE);
            tablePaint.setColor(Color.argb(80, 255, 255, 255));
            tablePaint.setStrokeWidth(2f);

            tableBounds = new RectF();
            targetBalls = new float[15][2];

            setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            float margin = Math.min(w, h) * 0.05f;
            float tableW = w - margin * 2;
            float tableH = tableW * 0.55f;
            float top = (h - tableH) / 2;
            tableBounds.set(margin, top, margin + tableW, top + tableH);
            ballRadius = tableBounds.width() * 0.012f;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            if (getWidth() == 0 || getHeight() == 0) return;

            long now = System.nanoTime();
            float dt = (now - lastTime) / 1000000000f;
            lastTime = now;

            updateDemoPositions(dt);
            drawTable(canvas);
            drawBalls(canvas);
            drawAimLines(canvas);

            invalidate();
        }

        private void updateDemoPositions(float dt) {
            angle += dt * 0.3f;
            float cx = tableBounds.centerX();
            float cy = tableBounds.centerY();

            // Cue ball at bottom
            cueBallX = cx;
            cueBallY = tableBounds.bottom - tableBounds.height() * 0.25f;

            // Target balls in rack pattern
            targetCount = 0;
            int rows = 5;
            for (int row = 0; row < rows && targetCount < 15; row++) {
                for (int col = 0; col <= row && targetCount < 15; col++) {
                    float bx = cx - row * ballRadius * 2.5f * 0.5f + col * ballRadius * 2.5f;
                    float by = tableBounds.top + tableBounds.height() * 0.25f + row * ballRadius * 2.2f;
                    if (bx >= tableBounds.left && bx <= tableBounds.right &&
                        by >= tableBounds.top && by <= tableBounds.bottom) {
                        targetBalls[targetCount][0] = bx;
                        targetBalls[targetCount][1] = by;
                        targetCount++;
                    }
                }
            }
        }

        private void drawTable(Canvas canvas) {
            tablePaint.setColor(Color.argb(60, 139, 69, 19));
            canvas.drawRoundRect(tableBounds, 20f, 20f, tablePaint);

            for (float[] p : POCKETS) {
                float px = tableBounds.left + p[0] * tableBounds.width();
                float py = tableBounds.top + p[1] * tableBounds.height();
                paint.setColor(Color.argb(80, 0, 0, 0));
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(px, py, ballRadius * 1.8f, paint);
                paint.setStyle(Paint.Style.STROKE);
            }
        }

        private void drawBalls(Canvas canvas) {
            // Cue ball
            ballPaint.setColor(Color.argb(180, 255, 255, 255));
            ballPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cueBallX, cueBallY, ballRadius, ballPaint);
            paint.setColor(Color.argb(200, 0, 255, 0));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            canvas.drawCircle(cueBallX, cueBallY, ballRadius, paint);

            // Target balls
            for (int i = 0; i < targetCount; i++) {
                float bx = targetBalls[i][0];
                float by = targetBalls[i][1];
                float dist = (float) Math.hypot(bx - cueBallX, by - cueBallY);
                if (dist > ballRadius * 3) continue;

                ballPaint.setStyle(Paint.Style.FILL);
                int red = (int)(155 + (bx * 5) % 100);
                ballPaint.setColor(Color.argb(150, 255, red, 100));
                canvas.drawCircle(bx, by, ballRadius, ballPaint);
            }
        }

        private void drawAimLines(Canvas canvas) {
            if (targetCount == 0) return;

            for (int i = 0; i < targetCount; i++) {
                float bx = targetBalls[i][0];
                float by = targetBalls[i][1];
                float dx = bx - cueBallX;
                float dy = by - cueBallY;
                float dist = (float) Math.hypot(dx, dy);

                if (dist < ballRadius * 2 || dist > tableBounds.width() * 0.8f) continue;

                float nx = dx / dist;
                float ny = dy / dist;

                // Find best pocket
                float bestDist = Float.MAX_VALUE;
                float[] bestPocket = POCKETS[0];
                for (float[] p : POCKETS) {
                    float px = tableBounds.left + p[0] * tableBounds.width();
                    float py = tableBounds.top + p[1] * tableBounds.height();
                    float pd = (float) Math.hypot(bx - px, by - py);
                    if (pd < bestDist) {
                        bestDist = pd;
                        bestPocket = p;
                    }
                }

                float pocketX = tableBounds.left + bestPocket[0] * tableBounds.width();
                float pocketY = tableBounds.top + bestPocket[1] * tableBounds.height();

                // Ghost ball
                float ghostX = bx - nx * ballRadius * 2.2f;
                float ghostY = by - ny * ballRadius * 2.2f;

                // Line 1: cue ball -> ghost ball (yellow)
                paint.setColor(LINE_COLOR_YELLOW);
                paint.setStrokeWidth(3f);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(cueBallX, cueBallY, ghostX, ghostY, paint);

                // Line 2: ball -> pocket (cyan)
                paint.setColor(LINE_COLOR_CYAN);
                canvas.drawLine(bx, by, pocketX, pocketY, paint);

                // Ghost ball circle
                paint.setColor(LINE_COLOR_WHITE);
                paint.setStrokeWidth(2f);
                canvas.drawCircle(ghostX, ghostY, ballRadius * 0.8f, paint);
            }

            // Touch aim line
            if (hasTouch) {
                paint.setColor(LINE_COLOR_YELLOW);
                paint.setStrokeWidth(4f);
                float angle = (float) Math.atan2(touchY - cueBallY, touchX - cueBallX);
                float endX = cueBallX + (float) Math.cos(angle) * tableBounds.width();
                float endY = cueBallY + (float) Math.sin(angle) * tableBounds.height();
                canvas.drawLine(cueBallX, cueBallY, endX, endY, paint);

                // Power bar
                float power = (float) Math.hypot(touchX - cueBallX, touchY - cueBallY) / tableBounds.width();
                paint.setColor(Color.argb((int) (power * 255), 255, 0, 0));
                paint.setStyle(Paint.Style.FILL);
                float barY = tableBounds.bottom + 60;
                canvas.drawRect(tableBounds.left, barY, tableBounds.left + power * tableBounds.width(), barY + 20, paint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    hasTouch = true;
                    touchX = event.getX();
                    touchY = event.getY();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    hasTouch = false;
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(event);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Overlay Service", NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Pool Aim Overlay")
                .setContentText("Aiming guide is running")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build();
        } else {
            return new Notification.Builder(this)
                .setContentTitle("Pool Aim Overlay")
                .setContentText("Aiming guide is running")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build();
        }
    }
}
