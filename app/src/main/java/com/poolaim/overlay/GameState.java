package com.poolaim.overlay;

import java.util.ArrayList;
import java.util.List;

public class GameState {
    public static final int C_WHITE = 0;
    public static final int C_YELLOW = 1;
    public static final int C_BLUE = 2;
    public static final int C_RED = 3;
    public static final int C_PURPLE = 4;
    public static final int C_ORANGE = 5;
    public static final int C_GREEN = 6;
    public static final int C_BROWN = 7;
    public static final int C_BLACK = 8;

    public static class Ball {
        public float x, y, r;
        public int color;
        public boolean isStripe;
        public Ball(float x, float y, float r, int color, boolean isStripe) {
            this.x = x; this.y = y; this.r = r; this.color = color; this.isStripe = isStripe;
        }
        public Ball(float x, float y, float r, int color) {
            this(x, y, r, color, false);
        }
    }

    public static class Line {
        public float x1, y1, x2, y2;
        public int argb;
        public float width;
        public boolean dashed;
        public boolean blocked;
        public Line(float x1, float y1, float x2, float y2, int argb, float width, boolean dashed, boolean blocked) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
            this.argb = argb; this.width = width; this.dashed = dashed; this.blocked = blocked;
        }
        public Line(float x1, float y1, float x2, float y2, int argb, float width, boolean dashed) {
            this(x1, y1, x2, y2, argb, width, dashed, false);
        }
    }

    public boolean tableFound = false;
    public float analyzeH = 1f;
    public int capW = 0;
    public int capH = 0;
    public int rotDeg = 0;
    public String skin = "";
    public float feltPct = 0;
    public float tableL, tableT, tableR, tableB;
    public float[][] pockets = new float[6][2];
    public List<Ball> balls = new ArrayList<>();
    public Ball cueBall = null;
    public Ball targetBall = null;
    public float cueAngle = Float.NaN;
    public float ghostX = -1, ghostY = -1;
    public boolean aiming = false;
    public int fps = 0;
    public List<Line> staticLines = new ArrayList<>();
    public List<Line> dynamicLines = new ArrayList<>();
}
