package com.local.glasslauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * One custom view draws the whole home screen: animated night-city wallpaper,
 * clock, dock, app grid, top-bar pills and a single springy glass highlight.
 * Everything is laid out on a 1920-unit-wide design canvas and scaled to the screen.
 */
public class LauncherView extends View {

    // ---- Dock apps, in this order. Change the package names here. ----
    private static final String[] DOCK_PKGS = {
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "in.startv.hotstar"
    };
    // Apps never shown in the grid.
    private static final String[] HIDE_PKGS = {"me.efesser.flauncher"};

    private static final float DW = 1920f;
    private static final int TW = 320, TH = 180, COLS = 4;
    private static final float DOCK_X = 96f, DOCK_Y = 730f, DOCK_GAP = 40f;
    private static final float GRID_X = 96f, GRID_Y = 1180f, GRID_GX = 70f, GRID_ROW = 290f;
    private static final float FOCUS_SCALE = 1.08f;

    private static final int Z_TOP = 0, Z_DOCK = 1, Z_GRID = 2;

    private static class Tile {
        String pkg, label;
        Intent intent;
        Bitmap bmp;
        int glow = 0xFFFAC814;
        float x, y, scale = 1f;
    }

    private static class Spring {
        float x, v, t;
        final float k, zeta;

        Spring(float k, float zeta) {
            this.k = k;
            this.zeta = zeta;
        }

        void snap(float p) {
            x = p;
            t = p;
            v = 0f;
        }

        void step(float dt) {
            int n = Math.max(1, (int) Math.ceil(dt / 0.008f));
            float h = dt / n;
            float c = 2f * (float) Math.sqrt(k) * zeta;
            for (int i = 0; i < n; i++) {
                float a = k * (t - x) - c * v;
                v += a * h;
                x += v * h;
            }
        }
    }

    private final List<Tile> dock = new ArrayList<>();
    private final List<Tile> grid = new ArrayList<>();
    private int zone = Z_DOCK, dockIdx = 0, gridIdx = 0, topIdx = 0;
    private boolean firstApply = true;

    private final Spring[] hl = {
            new Spring(320f, 0.62f), new Spring(320f, 0.62f),
            new Spring(320f, 0.62f), new Spring(320f, 0.62f)};
    private final Spring scroll = new Spring(140f, 1.0f);
    private boolean hlInit = false;

    private float s = 1f, vh = 1080f;
    private Bitmap bg, glowMask, roundMask;
    private float[] winX = new float[0], winY = new float[0], winPh = new float[0], winSp = new float[0];

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF target = new RectF();
    private final Rect dst = new Rect();

    private final Typeface thin = Typeface.create("sans-serif-thin", Typeface.NORMAL);
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);

    private long lastN, startN, lastSec = -1, lastSsidMs = 0;
    private boolean running = false;
    private float headAlpha = 0f, labelFade = 1f;
    private int glowColor = 0xFFFAC814, lastLabelIdx = -1;
    private String clockStr = "", dateStr = "", greet = "", ssid = "Wi-Fi";
    private final RectF[] pill = {new RectF(), new RectF()};
    private final String[] pillText = {"Wi-Fi", "Settings"};

    private final SharedPreferences prefs;

    public LauncherView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(Color.BLACK);
        prefs = context.getSharedPreferences("glass", Context.MODE_PRIVATE);

        roundMask = Bitmap.createBitmap(TW, TH, Bitmap.Config.ARGB_8888);
        Canvas mc = new Canvas(roundMask);
        Paint mp = new Paint(Paint.ANTI_ALIAS_FLAG);
        mp.setColor(Color.WHITE);
        mc.drawRoundRect(new RectF(0, 0, TW, TH), 22f, 22f, mp);

        glowMask = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Canvas gc = new Canvas(glowMask);
        Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);
        gp.setShader(new RadialGradient(128, 128, 128, 0xCCFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        gc.drawCircle(128, 128, 128, gp);

        layoutPills();
    }

    // ------------------------------------------------------------------ lifecycle

    public void onResumeLauncher() {
        running = true;
        lastN = System.nanoTime();
        if (startN == 0) startN = lastN;
        loadApps();
        invalidate();
    }

    public void onPauseLauncher() {
        running = false;
        savePref();
    }

    public void goHome() {
        zone = dock.isEmpty() ? (grid.isEmpty() ? Z_TOP : Z_GRID) : Z_DOCK;
        dockIdx = 0;
        gridIdx = 0;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        s = w / DW;
        vh = h / s;
        buildScene(w, h);
        layoutPills();
        hlInit = false;
    }

    // ------------------------------------------------------------------ scene

    private void buildScene(int w, int h) {
        int bw = w / 2, bh = h / 2;
        bg = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bg);
        float k = bw / DW;
        c.scale(k, k);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, vh, 0xFF06070B, 0xFF1A1C2B, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, DW, vh, p);
        p.setShader(new RadialGradient(1390, 300, 560, 0x66FAC814, 0x00FAC814, Shader.TileMode.CLAMP));
        c.drawCircle(1390, 300, 560, p);
        p.setShader(null);
        p.setColor(0xFFFAC814);
        c.drawCircle(1390, 300, 150, p);

        Random r = new Random(11);
        List<float[]> wins = new ArrayList<>();
        skyline(c, p, r, 800f, 120, 330, 70, 150, 0xFF121319, wins, 0.20f);
        skyline(c, p, r, 900f, 100, 260, 80, 170, 0xFF0A0A0E, wins, 0.30f);

        int n = Math.min(wins.size(), 260);
        winX = new float[n];
        winY = new float[n];
        winPh = new float[n];
        winSp = new float[n];
        for (int i = 0; i < n; i++) {
            float[] wv = wins.get(i);
            winX[i] = wv[0];
            winY[i] = wv[1];
            winPh[i] = r.nextFloat() * 6.28f;
            winSp[i] = 0.6f + r.nextFloat() * 1.8f;
        }
    }

    private void skyline(Canvas c, Paint p, Random r, float base, int hmin, int hmax,
                         int wmin, int wmax, int color, List<float[]> wins, float prob) {
        p.setColor(color);
        float x = -20f;
        while (x < DW) {
            float w = wmin + r.nextInt(wmax - wmin);
            float h = hmin + r.nextInt(hmax - hmin);
            float top = base - h;
            c.drawRect(x, top, x + w, vh + 10f, p);
            if (r.nextFloat() < 0.3f) {
                c.drawRect(x + w / 2f - 4f, top - 30f - r.nextInt(60), x + w / 2f + 4f, top, p);
            }
            int cols = (int) ((w - 24f) / 22f);
            int rows = (int) ((h - 30f) / 30f);
            for (int cx = 0; cx < cols; cx++) {
                for (int cy = 0; cy < rows; cy++) {
                    if (r.nextFloat() < prob * 0.5f) {
                        wins.add(new float[]{x + 14f + cx * 22f, top + 20f + cy * 30f});
                    }
                }
            }
            x += w + r.nextInt(6);
        }
    }

    // ------------------------------------------------------------------ apps

    private void loadApps() {
        final Context ctx = getContext().getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final PackageManager pm = ctx.getPackageManager();
                LinkedHashMap<String, ResolveInfo> found = new LinkedHashMap<>();
                Set<String> hide = new HashSet<>(Arrays.asList(HIDE_PKGS));
                String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
                for (String cat : cats) {
                    Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
                    for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                        String pk = ri.activityInfo.packageName;
                        if (!pk.equals(ctx.getPackageName()) && !hide.contains(pk) && !found.containsKey(pk)) {
                            found.put(pk, ri);
                        }
                    }
                }
                final List<Tile> nd = new ArrayList<>();
                final List<Tile> ng = new ArrayList<>();
                Set<String> dockSet = new HashSet<>(Arrays.asList(DOCK_PKGS));
                for (String pk : DOCK_PKGS) {
                    ResolveInfo ri = found.get(pk);
                    if (ri != null) nd.add(makeTile(pm, ri));
                }
                List<ResolveInfo> rest = new ArrayList<>();
                for (Map.Entry<String, ResolveInfo> e : found.entrySet()) {
                    if (!dockSet.contains(e.getKey())) rest.add(e.getValue());
                }
                final PackageManager fpm = pm;
                Collections.sort(rest, new java.util.Comparator<ResolveInfo>() {
                    @Override
                    public int compare(ResolveInfo a, ResolveInfo b) {
                        return a.loadLabel(fpm).toString().compareToIgnoreCase(b.loadLabel(fpm).toString());
                    }
                });
                for (ResolveInfo ri : rest) ng.add(makeTile(pm, ri));
                post(new Runnable() {
                    @Override
                    public void run() {
                        applyTiles(nd, ng);
                    }
                });
            }
        }).start();
    }

    private Tile makeTile(PackageManager pm, ResolveInfo ri) {
        Tile t = new Tile();
        t.pkg = ri.activityInfo.packageName;
        t.label = ri.loadLabel(pm).toString();
        t.intent = new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName(t.pkg, ri.activityInfo.name))
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        try {
            Drawable d = ri.activityInfo.loadBanner(pm);
            boolean banner = d != null;
            if (d == null) d = ri.loadIcon(pm);
            Bitmap out = Bitmap.createBitmap(TW, TH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            if (banner) {
                d.setBounds(0, 0, TW, TH);
                d.draw(c);
            } else {
                c.drawColor(0xFF1B1D26);
                int sz = 110;
                int l = (TW - sz) / 2, tp = (TH - sz) / 2;
                d.setBounds(l, tp, l + sz, tp + sz);
                d.draw(c);
            }
            Paint mp = new Paint();
            mp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            c.drawBitmap(roundMask, 0, 0, mp);
            t.bmp = out;
            t.glow = sampleColor(out);
        } catch (Throwable ignored) {
            // leave bmp null: a plain placeholder tile is drawn instead
        }
        return t;
    }

    private int sampleColor(Bitmap b) {
        int px = Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0);
        float[] hsv = new float[3];
        Color.colorToHSV(px, hsv);
        if (hsv[1] < 0.2f && hsv[2] < 0.2f) return 0xFFFAC814;
        hsv[1] = Math.max(hsv[1], 0.55f);
        hsv[2] = Math.max(hsv[2], 0.9f);
        return Color.HSVToColor(255, hsv);
    }

    private void applyTiles(List<Tile> nd, List<Tile> ng) {
        String want = firstApply ? prefs.getString("pkg", null) : currentPkg();
        dock.clear();
        dock.addAll(nd);
        grid.clear();
        grid.addAll(ng);
        for (int i = 0; i < dock.size(); i++) {
            Tile t = dock.get(i);
            t.x = DOCK_X + 30f + i * (TW + DOCK_GAP);
            t.y = DOCK_Y;
        }
        for (int i = 0; i < grid.size(); i++) {
            Tile t = grid.get(i);
            t.x = GRID_X + (i % COLS) * (TW + GRID_GX);
            t.y = GRID_Y + (i / COLS) * GRID_ROW;
        }
        boolean placed = false;
        if (want != null) {
            for (int i = 0; i < dock.size() && !placed; i++) {
                if (dock.get(i).pkg.equals(want)) {
                    zone = Z_DOCK;
                    dockIdx = i;
                    placed = true;
                }
            }
            for (int i = 0; i < grid.size() && !placed; i++) {
                if (grid.get(i).pkg.equals(want)) {
                    zone = Z_GRID;
                    gridIdx = i;
                    placed = true;
                }
            }
        }
        if (!placed) {
            zone = !dock.isEmpty() ? Z_DOCK : (!grid.isEmpty() ? Z_GRID : Z_TOP);
            dockIdx = Math.min(dockIdx, Math.max(0, dock.size() - 1));
            gridIdx = Math.min(gridIdx, Math.max(0, grid.size() - 1));
        }
        if (firstApply) {
            firstApply = false;
            hlInit = false;
            scroll.snap(0f);
        }
        invalidate();
    }

    private String currentPkg() {
        if (zone == Z_DOCK && dockIdx < dock.size()) return dock.get(dockIdx).pkg;
        if (zone == Z_GRID && gridIdx < grid.size()) return grid.get(gridIdx).pkg;
        return null;
    }

    private void savePref() {
        String p = currentPkg();
        if (p != null) prefs.edit().putString("pkg", p).apply();
    }

    // ------------------------------------------------------------------ keys

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveH(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveH(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                moveV(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveV(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.getRepeatCount() == 0) activate();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void moveH(int dir) {
        if (zone == Z_TOP) {
            topIdx = Math.max(0, Math.min(1, topIdx + dir));
        } else if (zone == Z_DOCK) {
            if (!dock.isEmpty()) dockIdx = Math.max(0, Math.min(dock.size() - 1, dockIdx + dir));
        } else if (zone == Z_GRID) {
            int row = gridIdx / COLS, col = gridIdx % COLS;
            int nc = col + dir;
            int ni = row * COLS + nc;
            if (nc >= 0 && nc < COLS && ni < grid.size()) gridIdx = ni;
        }
        invalidate();
    }

    private void moveV(int dir) {
        if (zone == Z_TOP) {
            if (dir > 0) {
                if (!dock.isEmpty()) zone = Z_DOCK;
                else if (!grid.isEmpty()) zone = Z_GRID;
            }
        } else if (zone == Z_DOCK) {
            if (dir < 0) {
                zone = Z_TOP;
                topIdx = 0;
            } else if (!grid.isEmpty()) {
                zone = Z_GRID;
                gridIdx = Math.min(dockIdx, grid.size() - 1);
            }
        } else if (zone == Z_GRID) {
            int row = gridIdx / COLS, col = gridIdx % COLS;
            if (dir < 0) {
                if (row == 0) {
                    if (!dock.isEmpty()) {
                        zone = Z_DOCK;
                        dockIdx = Math.min(col, dock.size() - 1);
                    } else {
                        zone = Z_TOP;
                        topIdx = 0;
                    }
                } else {
                    gridIdx -= COLS;
                }
            } else {
                int next = gridIdx + COLS;
                int lastRow = (grid.size() - 1) / COLS;
                if (next < grid.size()) gridIdx = next;
                else if (row < lastRow) gridIdx = grid.size() - 1;
            }
        }
        invalidate();
    }

    private void activate() {
        try {
            if (zone == Z_TOP) {
                String action = topIdx == 0 ? Settings.ACTION_WIFI_SETTINGS : Settings.ACTION_SETTINGS;
                getContext().startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            Tile t = null;
            if (zone == Z_DOCK && dockIdx < dock.size()) t = dock.get(dockIdx);
            if (zone == Z_GRID && gridIdx < grid.size()) t = grid.get(gridIdx);
            if (t != null) {
                savePref();
                Intent i = new Intent(t.intent);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                getContext().startActivity(i);
            }
        } catch (Throwable ignored) {
            // app may have been disabled or removed; ignore
        }
    }

    // ------------------------------------------------------------------ update

    private String readSsid() {
        try {
            WifiManager wm = (WifiManager) getContext().getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
            String name = wi == null ? null : wi.getSSID();
            if (name == null || name.contains("unknown")) return "Wi-Fi";
            name = name.replace("\"", "");
            return name.isEmpty() ? "Wi-Fi" : name;
        } catch (Throwable e) {
            return "Wi-Fi";
        }
    }

    private void layoutPills() {
        textPaint.setTypeface(regular);
        textPaint.setTextSize(30f);
        float sw = textPaint.measureText(pillText[1]) + 68f;
        float ww = Math.min(460f, textPaint.measureText(pillText[0]) + 68f);
        float right = 1824f;
        pill[1].set(right - sw, 70f, right, 134f);
        pill[0].set(pill[1].left - 24f - ww, 70f, pill[1].left - 24f, 134f);
    }

    private boolean focusRect(RectF out) {
        if (zone == Z_TOP) {
            out.set(pill[topIdx]);
            out.inset(-8f, -8f);
            return true;
        }
        Tile t = null;
        if (zone == Z_DOCK && dockIdx < dock.size()) t = dock.get(dockIdx);
        if (zone == Z_GRID && gridIdx < grid.size()) t = grid.get(gridIdx);
        if (t == null) return false;
        out.set(t.x - 21f, t.y - 15f - scroll.x, t.x + TW + 21f, t.y + TH + 15f - scroll.x);
        return true;
    }

    private void update(float dt) {
        long now = System.currentTimeMillis();
        if (now / 1000L != lastSec) {
            lastSec = now / 1000L;
            boolean h24 = android.text.format.DateFormat.is24HourFormat(getContext());
            clockStr = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(new Date(now));
            dateStr = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(new Date(now));
            int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            greet = hr < 12 ? "Good morning" : (hr < 17 ? "Good afternoon" : "Good evening");
        }
        if (now - lastSsidMs > 10000L) {
            lastSsidMs = now;
            String n = readSsid();
            if (!n.equals(ssid)) {
                ssid = n;
                pillText[0] = n;
                layoutPills();
            }
        }

        float st = 0f;
        if (zone == Z_GRID) {
            st = Math.max(0f, GRID_Y + (gridIdx / COLS) * GRID_ROW - 360f);
        }
        scroll.t = st;
        scroll.step(dt);

        float k = Math.min(1f, dt * 12f);
        for (int i = 0; i < dock.size(); i++) {
            Tile t = dock.get(i);
            float tg = (zone == Z_DOCK && i == dockIdx) ? FOCUS_SCALE : 1f;
            t.scale += (tg - t.scale) * k;
        }
        for (int i = 0; i < grid.size(); i++) {
            Tile t = grid.get(i);
            float tg = (zone == Z_GRID && i == gridIdx) ? FOCUS_SCALE : 1f;
            t.scale += (tg - t.scale) * k;
        }

        if (focusRect(target)) {
            hl[0].t = target.left;
            hl[1].t = target.top;
            hl[2].t = target.right;
            hl[3].t = target.bottom;
            if (!hlInit) {
                for (Spring sp : hl) sp.snap(sp.t);
                hlInit = true;
            }
            for (Spring sp : hl) sp.step(dt);
        }

        boolean showHead = zone == Z_DOCK && !dock.isEmpty();
        headAlpha += ((showHead ? 1f : 0f) - headAlpha) * Math.min(1f, dt * 8f);

        if (!dock.isEmpty()) {
            int idx = Math.min(dockIdx, dock.size() - 1);
            if (idx != lastLabelIdx) {
                lastLabelIdx = idx;
                labelFade = 0f;
            }
            int tgc = dock.get(idx).glow;
            float f = Math.min(1f, dt * 6f);
            int a = 0xFF;
            int rr = (int) (Color.red(glowColor) + (Color.red(tgc) - Color.red(glowColor)) * f);
            int gg = (int) (Color.green(glowColor) + (Color.green(tgc) - Color.green(glowColor)) * f);
            int bb = (int) (Color.blue(glowColor) + (Color.blue(tgc) - Color.blue(glowColor)) * f);
            glowColor = Color.argb(a, rr, gg, bb);
        }
        labelFade += (1f - labelFade) * Math.min(1f, dt * 10f);
    }

    // ------------------------------------------------------------------ draw

    @Override
    protected void onDraw(Canvas c) {
        long nowN = System.nanoTime();
        float dt = (nowN - lastN) / 1e9f;
        lastN = nowN;
        if (dt > 0.05f) dt = 0.05f;
        if (dt <= 0f) dt = 0.016f;
        float t = (nowN - startN) / 1e9f;
        update(dt);

        c.drawColor(Color.BLACK);
        if (bg != null) {
            dst.set(0, 0, getWidth(), getHeight());
            c.drawBitmap(bg, null, dst, bgPaint);
        }

        c.save();
        c.scale(s, s);
        drawWindows(c, t);

        float fade = 1f - Math.min(1f, scroll.x / 420f);
        c.save();
        c.translate(0f, -scroll.x);
        if (fade < 0.99f) {
            c.saveLayerAlpha(0f, 0f, DW, vh + scroll.x, (int) (Math.max(0f, fade) * 255f));
        }
        drawHeader(c);
        drawDock(c);
        if (fade < 0.99f) c.restore();
        drawGrid(c);
        c.restore();

        drawTopBar(c);
        drawHighlight(c);
        c.restore();

        if (running) postInvalidateOnAnimation();
    }

    private void drawWindows(Canvas c, float t) {
        paint.setShader(null);
        for (int i = 0; i < winX.length; i++) {
            float a = 0.20f + 0.80f * (0.5f + 0.5f * (float) Math.sin(t * winSp[i] + winPh[i]));
            paint.setColor(Color.argb((int) (a * 255f), 0xD2, 0xAA, 0x1E));
            c.drawRect(winX[i], winY[i], winX[i] + 7f, winY[i] + 10f, paint);
        }
    }

    private void drawText(Canvas c, String text, float x, float y, float size, Typeface tf,
                          int color, Paint.Align align) {
        textPaint.setTypeface(tf);
        textPaint.setTextSize(size);
        textPaint.setColor(color);
        textPaint.setTextAlign(align);
        c.drawText(text, x, y, textPaint);
    }

    private void drawHeader(Canvas c) {
        drawText(c, greet, 96f, 118f, 34f, light, 0xCCFFFFFF, Paint.Align.LEFT);
        drawText(c, clockStr, 90f, 232f, 118f, thin, Color.WHITE, Paint.Align.LEFT);
        drawText(c, dateStr, 96f, 292f, 30f, light, 0x99FFFFFF, Paint.Align.LEFT);
    }

    private void drawDock(Canvas c) {
        if (dock.isEmpty()) return;
        if (headAlpha > 0.01f) {
            int a = (int) (headAlpha * 0.55f * 255f);
            bmpPaint.setColorFilter(new PorterDuffColorFilter(
                    Color.argb(a, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor)),
                    PorterDuff.Mode.SRC_IN));
            rect.set(-100f, 270f, 1100f, 870f);
            c.drawBitmap(glowMask, null, rect, bmpPaint);
            bmpPaint.setColorFilter(null);
            int idx = Math.min(dockIdx, dock.size() - 1);
            int ta = (int) (255f * headAlpha * labelFade);
            drawText(c, dock.get(idx).label, 96f, 650f, 64f, light,
                    Color.argb(ta, 255, 255, 255), Paint.Align.LEFT);
        }
        int n = dock.size();
        float bw = n * TW + (n - 1) * DOCK_GAP + 60f;
        paint.setColor(0x22FFFFFF);
        rect.set(DOCK_X, DOCK_Y - 30f, DOCK_X + bw, DOCK_Y + TH + 80f);
        c.drawRoundRect(rect, 44f, 44f, paint);
        for (int i = 0; i < n; i++) drawTile(c, dock.get(i), zone == Z_DOCK && i == dockIdx);
    }

    private void drawGrid(Canvas c) {
        if (grid.isEmpty()) return;
        drawText(c, "All apps", 96f, GRID_Y - 50f, 34f, light, 0xB3FFFFFF, Paint.Align.LEFT);
        for (int i = 0; i < grid.size(); i++) {
            Tile tl = grid.get(i);
            float sy = tl.y - scroll.x;
            if (sy > vh + 40f || sy + TH < -120f) continue;
            drawTile(c, tl, zone == Z_GRID && i == gridIdx);
        }
    }

    private void drawTile(Canvas c, Tile tl, boolean focused) {
        float cx = tl.x + TW / 2f, cy = tl.y + TH / 2f;
        c.save();
        c.scale(tl.scale, tl.scale, cx, cy);
        if (tl.bmp != null) {
            c.drawBitmap(tl.bmp, tl.x, tl.y, bmpPaint);
        } else {
            paint.setColor(0xFF22242E);
            rect.set(tl.x, tl.y, tl.x + TW, tl.y + TH);
            c.drawRoundRect(rect, 22f, 22f, paint);
            String ch = tl.label.isEmpty() ? "?" : tl.label.substring(0, 1).toUpperCase();
            drawText(c, ch, cx, cy + 22f, 64f, light, Color.WHITE, Paint.Align.CENTER);
        }
        c.restore();
        float p = Math.max(0f, Math.min(1f, (tl.scale - 1f) / (FOCUS_SCALE - 1f)));
        if (p > 0.05f && focused) {
            drawText(c, tl.label, cx, tl.y + TH + 50f, 28f, regular,
                    Color.argb((int) (p * 255f), 255, 255, 255), Paint.Align.CENTER);
        }
    }

    private void drawTopBar(Canvas c) {
        for (int i = 0; i < 2; i++) {
            paint.setColor(0x33FFFFFF);
            c.drawRoundRect(pill[i], 32f, 32f, paint);
            drawText(c, pillText[i], pill[i].centerX(), pill[i].centerY() + 10f, 30f, regular,
                    Color.WHITE, Paint.Align.CENTER);
        }
    }

    private void drawHighlight(Canvas c) {
        if (!hlInit) return;
        rect.set(hl[0].x, hl[1].x, hl[2].x, hl[3].x);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(rect, 30f, 30f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f);
        paint.setColor(0x99FFFFFF);
        c.drawRoundRect(rect, 30f, 30f, paint);
        paint.setStyle(Paint.Style.FILL);
    }
}
