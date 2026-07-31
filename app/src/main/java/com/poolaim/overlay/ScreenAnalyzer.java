package com.poolaim.overlay;

import android.media.Image;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java computer vision engine.
 * Detects: table (TEAL felt), balls (color blobs), pockets, cue direction.
 * HSV thresholds tuned from real 8 Ball Pool game sprites.
 */
public class ScreenAnalyzer {

    private static final int C_NONE = -1;

    // Felt profiles (two-pass selection picks the dominant one per frame)
    private static final int P_TEAL = 0, P_GREEN = 1, P_GOLD = 2, P_BLUE = 3, P_ADAPT = 4;
    private static final String[] SKIN_NAMES = {"London", "Classic", "GoldenShot", "LuckyShot", "Custom"};
    private int[] feltCounts = new int[5];
    private int[] hueHist = new int[36];

    private int outW, outH;
    private byte[] ballMask;
    private boolean[] feltMask;
    private boolean[] whiteMask;
    private float lastCueAngle = Float.NaN;
    private int cueMissFrames = 0;
    private int[] stackBuf = new int[0];

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
        Image.Plane[] planes = image.getPlanes();
        boolean rgba = planes.length == 1;
        Image.Plane planeY = null, planeU = null, planeV = null;
        ByteBuffer by = null, bu = null, bv = null;
        int sy = 0, su = 0, sv = 0;
        int psy = 0, psu = 0, psv = 0;
        Image.Plane rgbaPlane = null;
        ByteBuffer brgba = null;
        int sr = 0, pr = 0;
        if (rgba) {
            rgbaPlane = planes[0];
            brgba = rgbaPlane.getBuffer();
            sr = rgbaPlane.getRowStride();
            pr = rgbaPlane.getPixelStride();
        } else {
            planeY = planes[0];
            planeU = planes[1];
            planeV = planes[2];
            by = planeY.getBuffer();
            bu = planeU.getBuffer();
            bv = planeV.getBuffer();
            sy = planeY.getRowStride(); su = planeU.getRowStride(); sv = planeV.getRowStride();
            psy = planeY.getPixelStride(); psu = planeU.getPixelStride(); psv = planeV.getPixelStride();
        }

        for (int i = 0; i < 5; i++) feltCounts[i] = 0;
        for (int i = 0; i < 36; i++) hueHist[i] = 0;
        long sampleCount = 0;

        for (int oy = 0; oy < outH; oy++) {
            int srcY = oy * imgH / outH;
            int oi = oy * outW;
            for (int ox = 0; ox < outW; ox++) {
                int srcX = ox * imgW / outW;
                int r, g, b;
                if (rgba) {
                    int off = srcY * sr + srcX * pr;
                    r = brgba.get(off) & 0xff;
                    g = brgba.get(off + 1) & 0xff;
                    b = brgba.get(off + 2) & 0xff;
                } else {
                    int Y = (by.get(srcY * sy + srcX * psy) & 0xff);
                    int U = (bu.get((srcY / 2) * su + (srcX / 2) * psu) & 0xff) - 128;
                    int V = (bv.get((srcY / 2) * sv + (srcX / 2) * psv) & 0xff) - 128;
                    r = (int) clamp(Y + 1.402f * V);
                    g = (int) clamp(Y - 0.344f * U - 0.714f * V);
                    b = (int) clamp(Y + 1.772f * U);
                }
                float max = Math.max(r, Math.max(g, b));
                float min = Math.min(r, Math.min(g, b));
                float d = max - min;
                float hh, ss = 0, vv = max / 255f;
                if (d > 0.0001f) {
                    ss = d / max;
                    if (max == r) hh = 60f * (((g - b) / d) % 6f);
                    else if (max == g) hh = 60f * ((b - r) / d + 2f);
                    else hh = 60f * ((r - g) / d + 4f);
                    if (hh < 0) hh += 360f;
                } else {
                    hh = 0;
                }
                int p = matchProfile(hh, ss, vv);
                if (p >= 0) feltCounts[p]++;
                if (ss >= 0.50f && vv >= 0.20f && vv <= 0.95f) {
                    hueHist[((int) (hh / 10f)) % 36]++;
                    sampleCount++;
                }
                int c = classify(r, g, b);
                whiteMask[oi] = (c == GameState.C_WHITE);
                ballMask[oi] = (c >= 0 && c <= 8) ? (byte) (c + 1) : 0;
                oi++;
            }
        }

        // Pick the dominant felt profile this frame (balls can never dominate)
        int bestBin = 0, bestCnt = 0;
        for (int i = 0; i < 36; i++) {
            if (hueHist[i] > bestCnt) { bestCnt = hueHist[i]; bestBin = i; }
        }
        feltCounts[P_ADAPT] = bestCnt;
        int winner = 0;
        for (int i = 1; i < 5; i++) {
            if (feltCounts[i] > feltCounts[winner]) winner = i;
        }
        state.feltPct = sampleCount > 0 ? 100f * feltCounts[winner] / sampleCount : 0f;
        state.skin = SKIN_NAMES[winner];

        // --- 1b. Build felt mask from the winning profile ---
        for (int oy = 0; oy < outH; oy++) {
            int srcY = oy * imgH / outH;
            int oi = oy * outW;
            for (int ox = 0; ox < outW; ox++) {
                int srcX = ox * imgW / outW;
                int r, g, b;
                if (rgba) {
                    int off = srcY * sr + srcX * pr;
                    r = brgba.get(off) & 0xff;
                    g = brgba.get(off + 1) & 0xff;
                    b = brgba.get(off + 2) & 0xff;
                } else {
                    int Y = (by.get(srcY * sy + srcX * psy) & 0xff);
                    int U = (bu.get((srcY / 2) * su + (srcX / 2) * psu) & 0xff) - 128;
                    int V = (bv.get((srcY / 2) * sv + (srcX / 2) * psv) & 0xff) - 128;
                    r = (int) clamp(Y + 1.402f * V);
                    g = (int) clamp(Y - 0.344f * U - 0.714f * V);
                    b = (int) clamp(Y + 1.772f * U);
                }
                float max = Math.max(r, Math.max(g, b));
                float min = Math.min(r, Math.min(g, b));
                float d = max - min;
                float hh, ss = 0, vv = max / 255f;
                if (d > 0.0001f) {
                    ss = d / max;
                    if (max == r) hh = 60f * (((g - b) / d) % 6f);
                    else if (max == g) hh = 60f * ((b - r) / d + 2f);
                    else hh = 60f * ((r - g) / d + 4f);
                    if (hh < 0) hh += 360f;
                } else {
                    hh = 0;
                }
                feltMask[oi] = matchesProfileIdx(winner, hh, ss, vv, bestBin);
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
            synchronized (state) {
                state.tableFound = false;
                state.balls = new ArrayList<>();
                state.staticLines = new ArrayList<>();
                state.dynamicLines = new ArrayList<>();
                state.cueBall = null;
                state.targetBall = null;
                state.ghostX = -1;
                state.ghostY = -1;
                state.aiming = false;
            }
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
        float[][] newPockets = new float[6][2];
        for (int i = 0; i < 4; i++) {
            newPockets[i][0] = corners[i][0];
            newPockets[i][1] = corners[i][1];
        }
        newPockets[4][0] = (corners[longA][0] + corners[(longA + 1) % 4][0]) / 2;
        newPockets[4][1] = (corners[longA][1] + corners[(longA + 1) % 4][1]) / 2;
        newPockets[5][0] = (corners[longB][0] + corners[(longB + 1) % 4][0]) / 2;
        newPockets[5][1] = (corners[longB][1] + corners[(longB + 1) % 4][1]) / 2;

        float tableW = (float) (maxU - minU);
        float tableH = (float) (maxV - minV);
        float ballR = tableW * 0.0112f;
        if (ballR < 1f) ballR = 1f;

        // Publish table geometry atomically (onDraw reads these fields)
        synchronized (state) {
            state.pockets = newPockets;
            state.tableL = Math.min(corners[0][0], Math.min(corners[1][0], Math.min(corners[2][0], corners[3][0])));
            state.tableT = Math.min(corners[0][1], Math.min(corners[1][1], Math.min(corners[2][1], corners[3][1])));
            state.tableR = Math.max(corners[0][0], Math.max(corners[1][0], Math.max(corners[2][0], corners[3][0])));
            state.tableB = Math.max(corners[0][1], Math.max(corners[1][1], Math.max(corners[2][1], corners[3][1])));
            state.tableFound = true;
        }

        // --- 4. Ball detection: flood fill on ball mask ---
        List<Cluster> clusters = new ArrayList<>();
        if (stackBuf.length < total) stackBuf = new int[total];
        int[] stack = stackBuf;
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
        int ballSearch = Math.max(2, (int) (ballR * 1.2f));
        List<GameState.Ball> newBalls = new ArrayList<>();
        GameState.Ball largestWhite = null;
        for (Cluster cl : clusters) {
            float cw = cl.maxX - cl.minX, ch = cl.maxY - cl.minY;
            if (cw > ballR * 3.5f || ch > ballR * 3.5f) continue;
            // Reject UI elements outside the playing field (top/bottom bars,
            // cushions) so they can never be mistaken for balls or the cue.
            float margin = ballR * 1.5f;
            if (cl.x < state.tableL - margin || cl.x > state.tableR + margin ||
                cl.y < state.tableT - margin || cl.y > state.tableB + margin) continue;

            float r;
            boolean isStripe = false;
            if (cl.color != GameState.C_WHITE && cl.color != GameState.C_BLACK) {
                // Stripe balls: white body surrounds the colored band.
                // Search the FULL ball extent (not just the band radius), else
                // the white body is never found and the band alone fails the
                // area filter below.
                int whiteCnt = 0;
                float whiteMaxD2 = 0;
                int top = Math.max(0, (int) (cl.y - ballSearch));
                int bottom = Math.min(outH - 1, (int) (cl.y + ballSearch));
                int left = Math.max(0, (int) (cl.x - ballSearch));
                int right = Math.min(outW - 1, (int) (cl.x + ballSearch));
                for (int stripeY = top; stripeY <= bottom; stripeY++) {
                    for (int stripeX = left; stripeX <= right; stripeX++) {
                        float dx = stripeX - cl.x, dy = stripeY - cl.y;
                        float d2 = dx * dx + dy * dy;
                        if (d2 <= ballSearch * ballSearch && whiteMask[stripeY * outW + stripeX]) {
                            whiteCnt++;
                            if (d2 > whiteMaxD2) whiteMaxD2 = d2;
                        }
                    }
                }
                if (whiteCnt >= 4) {
                    // Ball radius = extent of the white body, not the band.
                    // Band centroid ~ ball center (band is symmetric).
                    float fullR = (float) Math.sqrt(whiteMaxD2);
                    if (fullR >= ballR * 0.7f && fullR <= ballR * 1.7f) {
                        isStripe = true;
                        r = fullR;
                    } else {
                        r = (float) Math.sqrt(cl.area / Math.PI);
                    }
                } else {
                    r = (float) Math.sqrt(cl.area / Math.PI);
                }
            } else {
                r = (float) Math.sqrt(cl.area / Math.PI);
            }

            if (!isStripe) {
                if (cl.area < expArea * 0.35f || cl.area > expArea * 4f) continue;
            }

            GameState.Ball b = new GameState.Ball(cl.x, cl.y, r, cl.color, isStripe);
            newBalls.add(b);
            if (cl.color == GameState.C_WHITE && (largestWhite == null || cl.area > largestWhite.r * largestWhite.r)) {
                largestWhite = b;
            }
        }
        synchronized (state) {
            state.balls = newBalls;
        }
        state.cueBall = largestWhite;

        // --- 5. Cue direction from the game's dashed white guide line ---
        float cueAngle = Float.NaN;
        if (state.cueBall != null) {
            cueAngle = detectCueAngle(state, state.cueBall, ballR);
        }
        if (Float.isNaN(cueAngle)) {
            cueMissFrames++;
            if (cueMissFrames > 45) lastCueAngle = Float.NaN;
        } else {
            cueMissFrames = 0;
            lastCueAngle = cueAngle;
        }
        state.cueAngle = Float.isNaN(cueAngle) ? lastCueAngle : cueAngle;

        // --- 6. Compute lines ---
        computeLines(state, ballR);

        return state;
    }

    private float detectCueAngle(GameState state, GameState.Ball cue, float ballR) {
        float cxb = cue.x, cyb = cue.y;
        float searchR = ballR * 28f;
        float minD2 = ballR * ballR * 4f;
        float maxD2 = searchR * searchR;
        float tableMinX = state.tableL + ballR, tableMaxX = state.tableR - ballR;
        float tableMinY = state.tableT + ballR, tableMaxY = state.tableB - ballR;

        int minX = Math.max(0, (int) (cxb - searchR)), maxX = Math.min(outW, (int) (cxb + searchR));
        int minY = Math.max(0, (int) (cyb - searchR)), maxY = Math.min(outH, (int) (cyb + searchR));

        long wx = 0, wy = 0;
        int wn = 0;
        for (int yy = minY; yy < maxY; yy++) {
            for (int xx = minX; xx < maxX; xx++) {
                int i = yy * outW + xx;
                if (!whiteMask[i]) continue;
                float dx = xx - cxb, dy = yy - cyb;
                float d2 = dx * dx + dy * dy;
                if (d2 < minD2 || d2 > maxD2) continue;
                // Ignore white pixels outside the playing field (cushions, UI)
                if (xx < tableMinX || xx > tableMaxX || yy < tableMinY || yy > tableMaxY) continue;
                // Ignore white pixels belonging to other balls (stripe bodies, white balls)
                boolean inBall = false;
                for (GameState.Ball b : state.balls) {
                    if (b == cue) continue;
                    float bx = xx - b.x, by = yy - b.y;
                    float rr = b.r * 1.5f;
                    if (bx * bx + by * by <= rr * rr) { inBall = true; break; }
                }
                if (inBall) continue;

                float w = 1f / (1f + (float) Math.sqrt(d2) / ballR);
                wx += xx * w; wy += yy * w; wn++;
            }
        }
        if (wn >= 6) {
            float mx = wx / wn, my = wy / wn;
            float angle = (float) Math.atan2(my - cyb, mx - cxb);

            // Sanity: the aim direction must stay inside the table
            float ux = (float) Math.cos(angle), uy = (float) Math.sin(angle);
            float ahead = ballR * 6f;
            float ax = cxb + ux * ahead, ay = cyb + uy * ahead;
            if (ax < tableMinX || ax > tableMaxX || ay < tableMinY || ay > tableMaxY) return Float.NaN;

            // Hysteresis: reject jumps > ~100 deg/frame (noise), keep last angle
            if (!Float.isNaN(lastCueAngle)) {
                float diff = Math.abs(angle - lastCueAngle);
                diff = Math.min(diff, (float) (2f * Math.PI - diff));
                if (diff > (float) (Math.PI * 0.55)) return Float.NaN;
            }
            return angle;
        }
        return Float.NaN;
    }

    private boolean isPathBlocked(float x1, float y1, float x2, float y2, float ballR, List<GameState.Ball> balls, GameState.Ball ignoreA, GameState.Ball ignoreB) {
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.hypot(dx, dy);
        if (len < 0.001f) return false;
        float ux = dx / len, uy = dy / len;
        float threshold2 = (ballR * 1.85f) * (ballR * 1.85f);

        for (GameState.Ball b : balls) {
            if (b == ignoreA || b == ignoreB) continue;
            float vx = b.x - x1, vy = b.y - y1;
            float proj = vx * ux + vy * uy;
            if (proj >= ballR * 1.2f && proj <= len - ballR * 1.2f) {
                float perp2 = vx * vx + vy * vy - proj * proj;
                if (perp2 <= threshold2) {
                    return true;
                }
            }
        }
        return false;
    }

    private void computeLines(GameState state, float ballR) {
        List<GameState.Line> newStatic = new ArrayList<>();
        List<GameState.Line> newDynamic = new ArrayList<>();
        if (state.balls.isEmpty()) {
            synchronized (state) {
                state.staticLines = newStatic;
                state.dynamicLines = newDynamic;
                state.ghostX = -1;
                state.ghostY = -1;
                state.targetBall = null;
                state.aiming = false;
            }
            return;
        }

        // Static lines: every ball -> best pocket
        for (GameState.Ball b : state.balls) {
            if (b == state.cueBall) continue;
            float[] pocket = bestPocket(state, b, ballR);
            if (pocket == null) continue;
            boolean blocked = isPathBlocked(b.x, b.y, pocket[0], pocket[1], ballR, state.balls, b, null);
            int color = blocked ? 0x44FF4444 : 0x6618FFFF;
            newStatic.add(new GameState.Line(
                    b.x, b.y, pocket[0], pocket[1],
                    color, 1.6f, false, blocked));
        }

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

            float rayLen = (hit != null) ? Math.max(tMin, ballR * 4) : (state.tableR - state.tableL);
            float endX = cxb + dx * rayLen;
            float endY = cyb + dy * rayLen;
            boolean cueBlocked = (hit != null) && isPathBlocked(cxb, cyb, endX, endY, ballR, state.balls, state.cueBall, hit);

            newDynamic.add(new GameState.Line(
                    cxb, cyb, endX, endY,
                    0xCCFFE000, 2.5f, true, cueBlocked));

            if (hit != null) {
                float gx = cxb + dx * tMin;
                float gy = cyb + dy * tMin;
                state.ghostX = gx;
                state.ghostY = gy;
                state.targetBall = hit;
                float[] pocket = bestPocket(state, hit, ballR);
                if (pocket != null) {
                    boolean targetBlocked = isPathBlocked(hit.x, hit.y, pocket[0], pocket[1], ballR, state.balls, hit, null);
                    int targetColor = targetBlocked ? 0xCCFF3333 : 0xCC00FF88;
                    newDynamic.add(new GameState.Line(
                            hit.x, hit.y, pocket[0], pocket[1],
                            targetColor, 3f, false, targetBlocked));
                }
            } else {
                // Bank shot reflection off cushion boundary
                float minX = state.tableL + ballR, maxX = state.tableR - ballR;
                float minY = state.tableT + ballR, maxY = state.tableB - ballR;
                float tx = Float.MAX_VALUE, ty = Float.MAX_VALUE;
                if (dx > 0) tx = (maxX - cxb) / dx;
                else if (dx < 0) tx = (minX - cxb) / dx;
                if (dy > 0) ty = (maxY - cyb) / dy;
                else if (dy < 0) ty = (minY - cyb) / dy;

                float tHit = Math.min(tx, ty);
                if (tHit > 0 && tHit < 1000f) {
                    float bounceX = cxb + dx * tHit;
                    float bounceY = cyb + dy * tHit;
                    float rdx = (tHit == tx) ? -dx : dx;
                    float rdy = (tHit == ty) ? -dy : dy;

                    newDynamic.add(new GameState.Line(
                            bounceX, bounceY, bounceX + rdx * (state.tableR - state.tableL) * 0.4f, bounceY + rdy * (state.tableR - state.tableL) * 0.4f,
                            0xAAFF8800, 2f, true, false));
                }

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
        synchronized (state) {
            state.staticLines = newStatic;
            state.dynamicLines = newDynamic;
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

    /**
     * Felt profile matching. Ranges measured from the original game APK
     * (singletableLondon/GoldenShot/LuckyShot textures) and live match
     * screenshots (Classic table RGB(0,144,64) -> H=147).
     */
    private static int matchProfile(float h, float s, float v) {
        if (v < 0.20f || v > 0.96f || s < 0.25f) return -1;
        // TEAL London (Hmed=196, Smed=0.72, Vmed=0.72)
        if (h >= 170f && h <= 205f && s >= 0.25f && s <= 0.90f && v >= 0.35f) return P_TEAL;
        // GREEN Classic match table (H=147, S~1.0, V 0.22-0.95); lower bound 146
        // so dark-green ball pixels (H<=145) survive ball detection
        if (h >= 146f && h <= 168f && s >= 0.55f && v >= 0.22f) return P_GREEN;
        // GOLD GoldenShot event table (Hmed=38, Smed=0.87, Vmed=0.79)
        if (h >= 28f && h <= 60f && s >= 0.65f && v >= 0.50f) return P_GOLD;
        // BLUE LuckyShot event table (Hmed=226, Smed=0.91, Vmed=0.54)
        if (h >= 205f && h <= 238f && s >= 0.55f && v >= 0.30f) return P_BLUE;
        return -1;
    }

    private static boolean matchesProfileIdx(int profile, float h, float s, float v, int bestBin) {
        if (profile == P_ADAPT) {
            float center = bestBin * 10f + 5f;
            float dh = Math.abs(h - center);
            if (dh > 180f) dh = 360f - dh;
            return dh <= 10f && s >= 0.50f && v >= 0.20f && v <= 0.95f;
        }
        return matchProfile(h, s, v) == profile;
    }

    /**
     * HSV color classification — thresholds from real 8 Ball Pool sprites.
     * Ball sprites analyzed: ball0-ball15-hd.png, singletableLondon-hd.png
     */
    private static int classify(float r, float g, float b) {
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;
        float h, s = 0, v = max / 255f;
        if (d > 0.0001f) {
            s = d / max;
            if (max == r) h = 60f * (((g - b) / d) % 6f);
            else if (max == g) h = 60f * ((b - r) / d + 2f);
            else h = 60f * ((r - g) / d + 4f);
            if (h < 0) h += 360f;
        } else {
            h = 0;
        }

        // WHITE (cue ball base H≈60 S≈0.13 V≈0.94, guide line, cushions)
        if (v > 0.85f && s < 0.40f) return GameState.C_WHITE;

        // BLACK (ball8, dark areas)
        if (v < 0.20f) return GameState.C_BLACK;

        // Skip low-saturation pixels (gray UI, shadows)
        if (s < 0.35f) return C_NONE;

        // YELLOW (ball1 H≈41, ball9 H≈41): H=30-60
        if (h >= 30f && h <= 60f && v > 0.70f) return GameState.C_YELLOW;

        // ORANGE (ball5 H≈24, ball13 H≈24): H=15-30
        if (h >= 15f && h < 30f && v > 0.60f) return GameState.C_ORANGE;

        // BROWN/DARK RED (ball7 H≈11 V≈0.39, ball15 H≈10 V≈0.40): H<15, low V
        if (h < 15f && v < 0.55f && s > 0.70f) return GameState.C_BROWN;

        // RED (ball3 H≈356, ball11 H≈353): H=345-15 (wrap)
        if ((h >= 345f || h < 15f) && v > 0.55f) return GameState.C_RED;

        // GREEN (ball6 H≈129, ball14 H≈130): H=100-145, v>0.24
        // (dark green balls measured V=0.29-0.30 in live match; felt starts at 146)
        if (h >= 100f && h <= 145f && v > 0.24f) return GameState.C_GREEN;

        // BLUE (ball2 H≈216, ball10 H≈216): H=205-230
        if (h >= 205f && h <= 230f && v > 0.40f) return GameState.C_BLUE;

        // PURPLE (ball4 H≈267, ball12 H≈267): H=250-285
        if (h >= 250f && h <= 285f && v > 0.30f) return GameState.C_PURPLE;

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
