package com.poolaim.overlay;

import android.media.Image;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java computer vision engine.
 * Detects: table (green felt), balls (color blobs), pockets, cue direction.
 * No OpenCV dependency - keeps APK small and fast.
 */
public class ScreenAnalyzer {

    private static final int C_NONE = -1;
    private static final int C_FELT = 9;

    private int outW, outH;
    private byte[] ballMask;
    private boolean[] feltMask;
    private boolean[] whiteMask;
    private float lastCueAngle = Float.NaN;

    public GameState analyze(Image image, GameState state) {
        int imgW = image.getWidth();
        int imgH = image.getHeight();
        outW = 320;
        outH = Math.max(1, imgH * outW / imgW);
        state.analyzeH = outH;
        int total = outW * outH;
        if (ballMask == null || ballMask.length != total) {
            ballMask = new byte[total];
            feltMask = new boolean[total];
            whiteMask = new boolean[total];
        }

        // --- 1. Downsample + classify pixels ---
        Image.Plane planeY = image.getPlanes()[0];
        Image.Plane planeU = image.getPlanes()[1];
        Image.Plane planeV = image.getPlanes()[2];
        ByteBuffer by = planeY.getBuffer();
        ByteBuffer bu = planeU.getBuffer();
        ByteBuffer bv = planeV.getBuffer();
        int sy = planeY.getRowStride(), su = planeU.getRowStride(), sv = planeV.getRowStride();
        int psy = planeY.getPixelStride(), psu = planeU.getPixelStride(), psv = planeV.getPixelStride();

        for (int oy = 0; oy < outH; oy++) {
            int srcY = oy * imgH / outH;
            int oi = oy * outW;
            for (int ox = 0; ox < outW; ox++) {
                int srcX = ox * imgW / outW;
                int Y = (by.get(srcY * sy + srcX * psy) & 0xff);
                int U = (bu.get((srcY / 2) * su + (srcX / 2) * psu) & 0xff) - 128;
                int V = (bv.get((srcY / 2) * sv + (srcX / 2) * psv) & 0xff) - 128;
                float r = clamp(Y + 1.402f * V);
                float g = clamp(Y - 0.344f * U - 0.714f * V);
                float b = clamp(Y + 1.772f * U);
                int c = classify(r, g, b);
                feltMask[oi] = (c == C_FELT);
                whiteMask[oi] = (c == GameState.C_WHITE);
                ballMask[oi] = (c >= 0 && c <= 8) ? (byte) (c + 1) : 0;
                oi++;
            }
        }

        // --- 2. Table detection via PCA on felt pixels ---
        double cx = 0, cy = 0;
        long n = 0;
        for (int i = 0; i < total; i += 2) {
            if (feltMask[i]) { cx += i % outW; cy += i / outW; n++; }
        }
        if (n < 400) {
            state.tableFound = false;
            state.balls.clear();
            return state;
        }
        cx /= n; cy /= n;

        double sxx = 0, syy = 0, sxy = 0;
        for (int i = 0; i < total; i += 2) {
            if (feltMask[i]) {
                double dx = (i % outW) - cx, dy = (i / outW) - cy;
                sxx += dx * dx; syy += dy * dy; sxy += dx * dy;
            }
        }
        double theta = 0.5 * Math.atan2(2 * sxy, sxx - syy);
        double cosT = Math.cos(theta), sinT = Math.sin(theta);

        double minU = 1e9, maxU = -1e9, minV = 1e9, maxV = -1e9;
        for (int i = 0; i < total; i += 4) {
            if (feltMask[i]) {
                double dx = (i % outW) - cx, dy = (i / outW) - cy;
                double u = dx * cosT + dy * sinT;
                double v = -dx * sinT + dy * cosT;
                if (u < minU) minU = u; if (u > maxU) maxU = u;
                if (v < minV) minV = v; if (v > maxV) maxV = v;
            }
        }

        float[][] corners = new float[4][2];
        corners[0] = new float[]{(float) (cx + minU * cosT - minV * sinT), (float) (cy + minU * sinT + minV * cosT)};
        corners[1] = new float[]{(float) (cx + maxU * cosT - minV * sinT), (float) (cy + maxU * sinT + minV * cosT)};
        corners[2] = new float[]{(float) (cx + maxU * cosT + maxV * sinT), (float) (cy + maxU * sinT + maxV * cosT)};
        corners[3] = new float[]{(float) (cx + minU * cosT + maxV * sinT), (float) (cy + minU * sinT + maxV * cosT)};

        // --- 3. Pockets = 4 corners + midpoints of the 2 longer edges ---
        float[] e = new float[4];
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            e[i] = (float) Math.hypot(corners[j][0] - corners[i][0], corners[j][1] - corners[i][1]);
        }
        int longA = 0, longB = 0;
        if (e[0] > e[1]) { longA = 0; } else { longA = 1; }
        longB = (longA + 2) % 4;
        for (int i = 0; i < 4; i++) {
            state.pockets[i][0] = corners[i][0];
            state.pockets[i][1] = corners[i][1];
        }
        state.pockets[4][0] = (corners[longA][0] + corners[(longA + 1) % 4][0]) / 2;
        state.pockets[4][1] = (corners[longA][1] + corners[(longA + 1) % 4][1]) / 2;
        state.pockets[5][0] = (corners[longB][0] + corners[(longB + 1) % 4][0]) / 2;
        state.pockets[5][1] = (corners[longB][1] + corners[(longB + 1) % 4][1]) / 2;

        float tableW = (float) (maxU - minU);
        float tableH = (float) (maxV - minV);
        float ballR = tableW * 0.0112f; // ball diameter ~2.25% of table length
        if (ballR < 1f) ballR = 1f;

        state.tableL = Math.min(corners[0][0], Math.min(corners[1][0], Math.min(corners[2][0], corners[3][0])));
        state.tableT = Math.min(corners[0][1], Math.min(corners[1][1], Math.min(corners[2][1], corners[3][1])));
        state.tableR = Math.max(corners[0][0], Math.max(corners[1][0], Math.max(corners[2][0], corners[3][0])));
        state.tableB = Math.max(corners[0][1], Math.max(corners[1][1], Math.max(corners[2][1], corners[3][1])));
        state.tableFound = true;

        // --- 4. Ball detection: flood fill on ball mask ---
        List<Cluster> clusters = new ArrayList<>();
        int[] stack = new int[total];
        for (int i = 0; i < total; i++) {
            if (ballMask[i] > 0) {
                int color = ballMask[i] - 1;
                int sp = 0;
                stack[sp++] = i;
                ballMask[i] = 0;
                long sumX = 0, sumY = 0;
                int cnt = 0, minX = outW, minY = outH, maxX = 0, maxY = 0;
                while (sp > 0) {
                    int p = stack[--sp];
                    int px = p % outW, py = p / outW;
                    sumX += px; sumY += py; cnt++;
                    if (px < minX) minX = px; if (px > maxX) maxX = px;
                    if (py < minY) minY = py; if (py > maxY) maxY = py;
                    if (px > 0 && ballMask[p - 1] > 0) { stack[sp++] = p - 1; ballMask[p - 1] = 0; }
                    if (px < outW - 1 && ballMask[p + 1] > 0) { stack[sp++] = p + 1; ballMask[p + 1] = 0; }
                    if (py > 0 && ballMask[p - outW] > 0) { stack[sp++] = p - outW; ballMask[p - outW] = 0; }
                    if (py < outH - 1 && ballMask[p + outW] > 0) { stack[sp++] = p + outW; ballMask[p + outW] = 0; }
                }
                if (cnt > 4) {
                    clusters.add(new Cluster(color, (float) sumX / cnt, (float) sumY / cnt, cnt, minX, maxX, minY, maxY));
                }
            }
        }

        float expArea = (float) (Math.PI * ballR * ballR);
        state.balls.clear();
        GameState.Ball largestWhite = null;
        for (Cluster cl : clusters) {
            if (cl.area < expArea * 0.35f || cl.area > expArea * 4f) continue;
            float cw = cl.maxX - cl.minX, ch = cl.maxY - cl.minY;
            if (cw > ballR * 5 || ch > ballR * 5) continue;
            float r = (float) Math.sqrt(cl.area / Math.PI);
            GameState.Ball b = new GameState.Ball(cl.x, cl.y, r, cl.color);
            state.balls.add(b);
            if (cl.color == GameState.C_WHITE && (largestWhite == null || cl.area > largestWhite.r * largestWhite.r)) {
                largestWhite = b;
            }
        }
        state.cueBall = largestWhite;

        // --- 5. Cue direction from the game's dashed white guide line ---
        float cueAngle = Float.NaN;
        if (state.cueBall != null) {
            cueAngle = detectCueAngle(state.cueBall, ballR);
        }
        state.cueAngle = Float.isNaN(cueAngle) ? lastCueAngle : cueAngle;
        lastCueAngle = state.cueAngle;

        // --- 6. Compute lines ---
        computeLines(state, ballR);

        return state;
    }

    private float detectCueAngle(GameState.Ball cue, float ballR) {
        float cxb = cue.x, cyb = cue.y;
        float searchR = ballR * 28f;
        int minX = Math.max(0, (int) (cxb - searchR)), maxX = Math.min(outW, (int) (cxb + searchR));
        int minY = Math.max(0, (int) (cyb - searchR)), maxY = Math.min(outH, (int) (cyb + searchR));

        // Primary: white dashed guide line pixels near cue ball
        long wx = 0, wy = 0;
        int wn = 0;
        for (int yy = minY; yy < maxY; yy++) {
            for (int xx = minX; xx < maxX; xx++) {
                int i = yy * outW + xx;
                if (whiteMask[i]) {
                    float dx = xx - cxb, dy = yy - cyb;
                    float d2 = dx * dx + dy * dy;
                    if (d2 > ballR * ballR * 4f && d2 < searchR * searchR) {
                        // weight: closer pixels matter more
                        float w = 1f / (1f + (float) Math.sqrt(d2) / ballR);
                        wx += xx * w; wy += yy * w; wn++;
                    }
                }
            }
        }
        if (wn >= 8) {
            float mx = wx / wn, my = wy / wn;
            float dx = mx - cxb, dy = my - cyb;
            if (dx * dx + dy * dy > 0.1f) {
                return (float) Math.atan2(dy, dx);
            }
        }

        // Fallback: none (stick fallback unreliable at this resolution)
        return Float.NaN;
    }

    private void computeLines(GameState state, float ballR) {
        state.staticLines.clear();
        state.dynamicLines.clear();
        if (state.balls.isEmpty()) return;

        // Static: every ball -> best (unblocked) pocket
        for (GameState.Ball b : state.balls) {
            if (b == state.cueBall) continue;
            float[] pocket = bestPocket(state, b, ballR);
            if (pocket == null) continue;
            state.staticLines.add(new GameState.Line(
                    b.x, b.y, pocket[0], pocket[1],
                    0x6618FFFF, 1.6f, false));
        }

        // Dynamic: cue ray -> first hit ball -> ghost -> pocket
        if (state.cueBall != null && !Float.isNaN(state.cueAngle)) {
            float dx = (float) Math.cos(state.cueAngle);
            float dy = (float) Math.sin(state.cueAngle);
            float cxb = state.cueBall.x, cyb = state.cueBall.y;

            GameState.Ball hit = null;
            float tMin = Float.MAX_VALUE;
            for (GameState.Ball b : state.balls) {
                if (b == state.cueBall) continue;
                float bx = b.x - cxb, by = b.y - cyb;
                float proj = bx * dx + by * dy;
                if (proj <= 0) continue;
                float perp2 = bx * bx + by * by - proj * proj;
                float d = ballR * 2f;
                if (perp2 <= d * d) {
                    float t = proj - (float) Math.sqrt(Math.max(0, d * d - perp2));
                    if (t < tMin) { tMin = t; hit = b; }
                }
            }

            // Cue ray visual (to ghost or full length)
            float rayLen = (hit != null) ? Math.max(tMin, ballR * 4) : (state.tableR - state.tableL);
            float endX = cxb + dx * rayLen;
            float endY = cyb + dy * rayLen;
            state.dynamicLines.add(new GameState.Line(
                    cxb, cyb, endX, endY,
                    0xCCFFE000, 2.5f, true));

            if (hit != null) {
                float gx = cxb + dx * tMin;
                float gy = cyb + dy * tMin;
                state.ghostX = gx;
                state.ghostY = gy;
                state.targetBall = hit;
                // target ball -> best pocket
                float[] pocket = bestPocket(state, hit, ballR);
                if (pocket != null) {
                    state.dynamicLines.add(new GameState.Line(
                            hit.x, hit.y, pocket[0], pocket[1],
                            0xCC00FF88, 3f, false));
                }
            } else {
                state.ghostX = -1;
                state.ghostY = -1;
                state.targetBall = null;
            }
            state.aiming = true;
        } else {
            state.aiming = false;
            state.ghostX = -1;
            state.ghostY = -1;
            state.targetBall = null;
        }
    }

    private float[] bestPocket(GameState state, GameState.Ball b, float ballR) {
        float bestDist = Float.MAX_VALUE;
        float[] best = null;
        for (int i = 0; i < 6; i++) {
            float px = state.pockets[i][0], py = state.pockets[i][1];
            float dist = (float) Math.hypot(px - b.x, py - b.y);
            if (dist < bestDist) {
                bestDist = dist;
                best = state.pockets[i];
            }
        }
        return best;
    }

    private static int classify(float r, float g, float b) {
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;
        float h, s = 0, v = max;
        if (d > 0.0001f) {
            s = d / max;
            if (max == r) h = 60f * (((g - b) / d) % 6f);
            else if (max == g) h = 60f * ((b - r) / d + 2f);
            else h = 60f * ((r - g) / d + 4f);
            if (h < 0) h += 360f;
        } else {
            h = 0;
        }

        // Felt green (distinctive dark-medium green)
        if (s > 0.22f && s < 0.75f && v > 0.16f && v < 0.62f && h >= 85f && h <= 150f) return C_FELT;

        if (v > 0.82f && s < 0.32f) return GameState.C_WHITE;
        if (v < 0.20f) return GameState.C_BLACK;
        if (s < 0.28f) return C_NONE;

        if (h >= 210f && h <= 255f && v > 0.30f) return GameState.C_BLUE;
        if ((h <= 12f || h >= 345f) && v > 0.35f && s > 0.45f) return GameState.C_RED;
        if (h >= 255f && h <= 300f && v > 0.30f) return GameState.C_PURPLE;
        if (h >= 35f && h <= 80f && v > 0.55f && s > 0.40f) return GameState.C_YELLOW;
        if (h >= 15f && h < 35f) {
            if (v > 0.50f && s > 0.50f) return GameState.C_ORANGE;
            if (v >= 0.25f && v <= 0.55f) return GameState.C_BROWN;
        }
        if (h >= 80f && h < 90f && v > 0.35f) return GameState.C_GREEN;
        return C_NONE;
    }

    private static float clamp(float v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return v;
    }

    private static class Cluster {
        int color;
        float x, y;
        int area;
        int minX, maxX, minY, maxY;
        Cluster(int color, float x, float y, int area, int minX, int maxX, int minY, int maxY) {
            this.color = color; this.x = x; this.y = y; this.area = area;
            this.minX = minX; this.maxX = maxX; this.minY = minY; this.maxY = maxY;
        }
    }
}
