package de.spiasm.schiffe3d;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Zeigt die 3 Ebenen perspektivisch übereinander. Die aktive Ebene liegt flach in der Mitte,
 * die Ebene darüber/darunter schaut schräg heraus. Hoch-/Runterwischen wechselt die Ebene.
 */
public class GameView extends View {
    public interface Listener {
        void onCell(int x, int y);
    }

    public interface LayerListener {
        void onLayer(int layer);
    }

    public static final int[] LAYER_BG = {0xFF7FB9E6, 0xFF2368A8, 0xFF102A4C};
    private static final int BG = 0xFF0E1A2B;

    public Game game;
    public boolean enemyView;
    public int layer = 1;
    public Listener listener;
    public LayerListener layerListener;

    private float pos = 1f;
    private ValueAnimator anim;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private final Matrix mtx = new Matrix();
    private final float[] src = new float[8];
    private final float[] dst = new float[8];

    private float downX, downY;
    private boolean dragging;
    private final Matrix inv = new Matrix();
    private final float[] pt = new float[2];
    private final long startTime = android.os.SystemClock.uptimeMillis();
    private android.graphics.LinearGradient[] skies;
    private int skyH;

    public GameView(Context c) {
        super(c);
        label.setTextAlign(Paint.Align.CENTER);
        line.setColor(0x55FFFFFF);
        line.setStyle(Paint.Style.STROKE);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    /** Ebene wechseln, mit Animation (auch zurückfedern, wenn target == layer). */
    public void setLayer(int target) {
        if (target < 0 || target >= Game.LAYERS) return;
        if (target == layer && anim == null && pos == target) {
            invalidate();
            return;
        }
        layer = target;
        ValueAnimator old = anim;
        anim = null;
        if (old != null) old.cancel();
        final ValueAnimator a = ValueAnimator.ofFloat(pos, target);
        a.setDuration(420);
        a.setInterpolator(new DecelerateInterpolator(1.6f));
        a.addUpdateListener(v -> {
            if (anim != a) return;
            pos = (float) v.getAnimatedValue();
            invalidate();
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator x) {
                if (anim != a) return;
                pos = layer;
                anim = null;
                invalidate();
            }
        });
        anim = a;
        a.start();
    }

    /** Ebene sofort setzen (ohne Animation). */
    public void jumpToLayer(int target) {
        ValueAnimator old = anim;
        anim = null;
        if (old != null) old.cancel();
        layer = target;
        pos = target;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(w, Math.round(w * 1.08f));
    }

    /** Perspektive: Brett liegt schräg (ca. 45°) wie auf einem Tisch, hinten schmaler. */
    private void boardMatrix(Matrix m, float w, float h) {
        float near = w * 0.99f;
        float far = w * 0.74f;
        float bh = w * 0.80f;
        float cx = w / 2f, cy = h / 2f + w * 0.01f;
        float sz = w * (Game.N + 2) / (Game.N + 1f); // Brett inkl. Rand rundum
        src[0] = 0; src[1] = 0;
        src[2] = sz; src[3] = 0;
        src[4] = sz; src[5] = sz;
        src[6] = 0; src[7] = sz;
        dst[0] = cx - far / 2; dst[1] = cy - bh / 2;
        dst[2] = cx + far / 2; dst[3] = cy - bh / 2;
        dst[4] = cx + near / 2; dst[5] = cy + bh / 2;
        dst[6] = cx - near / 2; dst[7] = cy + bh / 2;
        m.setPolyToPoly(src, 0, dst, 0, 4);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (game == null) return;
        float w = getWidth(), h = getHeight();
        float t = (android.os.SystemClock.uptimeMillis() - startTime) / 1000f;

        for (int i = 0; i < Game.LAYERS; i++) {
            float d = i - pos;
            if (Math.abs(d) >= 1f) continue;
            c.save();
            c.translate(0, d * h);
            c.clipRect(0, 0, w, h);
            drawScene(c, i, w, h, t);

            boardMatrix(mtx, w, h);
            // Schatten unter dem Brett
            fill.setColor(0x55000000);
            path.reset();
            path.moveTo(dst[0] + w * 0.02f, dst[1] + w * 0.03f);
            path.lineTo(dst[2] + w * 0.02f, dst[3] + w * 0.03f);
            path.lineTo(dst[4] + w * 0.02f, dst[5] + w * 0.045f);
            path.lineTo(dst[6] + w * 0.02f, dst[7] + w * 0.045f);
            path.close();
            c.drawPath(path, fill);
            // Brettkante vorne (Dicke)
            fill.setColor(0xFF0A1220);
            path.reset();
            path.moveTo(dst[6], dst[7]);
            path.lineTo(dst[4], dst[5]);
            path.lineTo(dst[4], dst[5] + w * 0.03f);
            path.lineTo(dst[6], dst[7] + w * 0.03f);
            path.close();
            c.drawPath(path, fill);

            c.save();
            c.concat(mtx);
            drawLayer(c, i, w);
            c.restore();

            // Ebenen-Name
            label.setColor(0xEEFFFFFF);
            label.setTextSize(w * 0.05f);
            label.setFakeBoldText(true);
            c.drawText(Game.LAYER_ICONS[i] + " " + Game.LAYER_NAMES[i], w / 2, h * 0.075f, label);
            label.setFakeBoldText(false);
            c.restore();
        }

        // Hinweise zum Wischen
        label.setColor(0xCCFFFFFF);
        label.setTextSize(w * 0.036f);
        if (layer > 0 && !dragging) c.drawText("▼ runterwischen: " + Game.LAYER_NAMES[layer - 1], w * 0.25f, h - w * 0.025f, label);
        if (layer < Game.LAYERS - 1 && !dragging) c.drawText("▲ hochwischen: " + Game.LAYER_NAMES[layer + 1], w * 0.75f, h - w * 0.025f, label);

        postInvalidateDelayed(40);
    }

    /** Hintergrund rund ums Brett: Wolken, Wellen oder Luftblasen. */
    private void drawScene(Canvas c, int l, float w, float h, float t) {
        if (skies == null || skyH != (int) h) {
            skyH = (int) h;
            skies = new android.graphics.LinearGradient[]{
                    new android.graphics.LinearGradient(0, 0, 0, h, 0xFF4E9BD8, 0xFFB5DCF7, android.graphics.Shader.TileMode.CLAMP),
                    new android.graphics.LinearGradient(0, 0, 0, h, 0xFF2B7BBF, 0xFF0F4C86, android.graphics.Shader.TileMode.CLAMP),
                    new android.graphics.LinearGradient(0, 0, 0, h, 0xFF0B2342, 0xFF030A16, android.graphics.Shader.TileMode.CLAMP)};
        }
        fill.setShader(skies[l]);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);

        if (l == 0) {
            // Wolken ziehen langsam vorbei
            float[][] clouds = {{0.05f, 0.12f, 0.09f, 0.020f}, {0.6f, 0.07f, 0.07f, 0.03f}, {0.3f, 0.9f, 0.1f, 0.025f},
                    {0.85f, 0.5f, 0.06f, 0.035f}, {0.15f, 0.55f, 0.07f, 0.028f}, {0.75f, 0.93f, 0.08f, 0.018f}};
            for (float[] cl : clouds) {
                float span = w * 1.4f;
                float x = ((cl[0] * w + t * cl[3] * w) % span) - w * 0.2f;
                float y = cl[1] * h, s = cl[2] * w;
                fill.setColor(0xEEFFFFFF);
                c.drawCircle(x, y, s, fill);
                c.drawCircle(x + s * 0.9f, y + s * 0.15f, s * 0.8f, fill);
                c.drawCircle(x - s * 0.9f, y + s * 0.2f, s * 0.7f, fill);
                c.drawCircle(x + s * 0.3f, y - s * 0.5f, s * 0.75f, fill);
                fill.setColor(0x33A0B8CC);
                c.drawRect(x - s * 1.5f, y + s * 0.5f, x + s * 1.6f, y + s * 0.75f, fill);
            }
        } else if (l == 1) {
            // Wellen
            stroke.setColor(0x66FFFFFF);
            stroke.setStrokeWidth(w * 0.006f);
            for (int row = 0; row < 14; row++) {
                float y = h * (row + 0.5f) / 14f;
                float amp = w * 0.008f;
                float shift = (float) Math.sin(t * 0.8f + row) * w * 0.03f;
                for (int k = -1; k < 7; k++) {
                    float x = k * w / 6f + shift + (row % 2) * w / 12f;
                    path.reset();
                    path.moveTo(x, y);
                    path.quadTo(x + w * 0.03f, y - amp * 2, x + w * 0.06f, y);
                    c.drawPath(path, stroke);
                }
            }
            // Gischt
            fill.setColor(0x55FFFFFF);
            for (int k = 0; k < 18; k++) {
                float x = (k * 0.137f % 1f) * w, y = (k * 0.311f % 1f) * h;
                float a = (float) (0.5 + 0.5 * Math.sin(t * 2 + k));
                fill.setAlpha(Math.round(90 * a));
                c.drawCircle(x, y, w * 0.006f, fill);
            }
            fill.setAlpha(255);
        } else {
            // Luftblasen steigen auf
            stroke.setStrokeWidth(w * 0.004f);
            for (int k = 0; k < 26; k++) {
                float speed = 0.05f + (k % 5) * 0.02f;
                float x0 = (k * 0.173f % 1f) * w;
                float y = h - (((k * 0.29f) + t * speed) % 1.1f) * h;
                float x = x0 + (float) Math.sin(t * 1.5f + k) * w * 0.012f;
                float rad = w * (0.006f + (k % 4) * 0.004f);
                stroke.setColor(0x99BFE3FF);
                c.drawCircle(x, y, rad, stroke);
                fill.setColor(0x55FFFFFF);
                c.drawCircle(x - rad * 0.35f, y - rad * 0.35f, rad * 0.3f, fill);
            }
            // Lichtstrahlen von oben
            fill.setColor(0x10FFFFFF);
            for (int k = 0; k < 3; k++) {
                float x = w * (0.2f + k * 0.3f) + (float) Math.sin(t * 0.3f + k) * w * 0.03f;
                path.reset();
                path.moveTo(x - w * 0.03f, 0);
                path.lineTo(x + w * 0.03f, 0);
                path.lineTo(x + w * 0.12f, h);
                path.lineTo(x - w * 0.02f, h);
                path.close();
                c.drawPath(path, fill);
            }
        }
    }

    /** Zeichnet eine Ebene in ein Quadrat 0..w (mit Beschriftungsrand). */
    private void drawLayer(Canvas c, int l, float w) {
        int n = Game.N;
        float cs = w / (n + 1);

        fill.setColor(0xFF15253A);
        r.set(0, 0, w + cs, w + cs);
        c.drawRoundRect(r, cs * 0.3f, cs * 0.3f, fill);

        label.setColor(0xFFCFD8E3);
        label.setTextSize(cs * 0.42f);
        for (int i = 0; i < n; i++) {
            c.drawText(String.valueOf((char) ('A' + i)), cs * (i + 1.5f), cs * 0.68f, label);
            c.drawText(String.valueOf(i + 1), cs * 0.5f, cs * (i + 1.5f) + cs * 0.15f, label);
        }
        label.setTextSize(cs * 0.55f);
        c.drawText(Game.LAYER_ICONS[l], cs * 0.5f, cs * 0.72f, label);

        fill.setColor(LAYER_BG[l]);
        c.drawRect(cs, cs, w, w, fill);
        // leichte Wellen / Wolken als Muster
        line.setColor(l == 0 ? 0x22FFFFFF : 0x18FFFFFF);
        line.setStrokeWidth(cs * 0.04f);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x += 2) {
                float px = cs * (x + 1.3f + (y % 2) * 0.9f), py = cs * (y + 1.55f);
                if (px + cs * 0.5f > w) continue;
                c.drawArc(px, py - cs * 0.1f, px + cs * 0.4f, py + cs * 0.1f, 200, 140, false, line);
            }
        }

        // Felder
        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n; y++) {
                float left = cs * (x + 1), top = cs * (y + 1);
                if (enemyView) {
                    int v = game.enemy[l][x][y];
                    if (v == 2) {
                        fill.setColor(0xFFE07A1F);
                        r.set(left + cs * 0.06f, top + cs * 0.06f, left + cs * 0.94f, top + cs * 0.94f);
                        c.drawRoundRect(r, cs * 0.2f, cs * 0.2f, fill);
                    } else if (v == 3) {
                        fill.setColor(0x997A1515);
                        c.drawRect(left, top, left + cs, top + cs, fill);
                    }
                }
            }
        }

        // Schiffe
        if (!enemyView) {
            for (Game.Ship s : game.ships) {
                if (s.placed && s.layer == l) drawShip(c, l, s.x, s.y, s.len, s.horiz, cs, s.sunk());
            }
        } else {
            for (int[] wr : game.enemyWrecks) {
                if (wr[0] == l) drawShip(c, l, wr[1], wr[2], wr[3], wr[4] == 1, cs, true);
            }
        }

        // Treffer / Fehlschüsse
        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n; y++) {
                float left = cs * (x + 1), top = cs * (y + 1);
                if (enemyView) {
                    int v = game.enemy[l][x][y];
                    if (v == 1) drawMiss(c, left, top, cs);
                    else if (v >= 2) drawHit(c, left, top, cs);
                } else if (game.incoming[l][x][y]) {
                    if (game.own[l][x][y] >= 0) drawHit(c, left, top, cs);
                    else drawMiss(c, left, top, cs);
                }
            }
        }

        // Gitter
        line.setColor(0x44FFFFFF);
        line.setStrokeWidth(Math.max(1f, cs * 0.025f));
        for (int i = 0; i <= n; i++) {
            c.drawLine(cs * (i + 1), cs, cs * (i + 1), w, line);
            c.drawLine(cs, cs * (i + 1), w, cs * (i + 1), line);
        }

        // Radar/Sonar-Bereiche
        if (enemyView) {
            for (int[] s : game.scans) {
                if (s[0] != l) continue;
                int x0 = Math.max(0, s[1] - 1), y0 = Math.max(0, s[2] - 1);
                int x1 = Math.min(n - 1, s[1] + 1), y1 = Math.min(n - 1, s[2] + 1);
                r.set(cs * (x0 + 1), cs * (y0 + 1), cs * (x1 + 2), cs * (y1 + 2));
                fill.setColor(0x44FFD54F);
                c.drawRect(r, fill);
                stroke.setColor(0xFFFFD54F);
                stroke.setStrokeWidth(cs * 0.06f);
                c.drawRect(r, stroke);
                label.setTextSize(cs * 0.6f);
                label.setColor(0xFFFFD54F);
                c.drawText(String.valueOf(s[3]), cs * (s[1] + 1.5f), cs * (s[2] + 1.5f) + cs * 0.22f, label);
            }
        }
    }

    private void drawMiss(Canvas c, float left, float top, float cs) {
        stroke.setColor(0xAAFFFFFF);
        stroke.setStrokeWidth(cs * 0.05f);
        c.drawCircle(left + cs / 2, top + cs / 2, cs * 0.2f, stroke);
        fill.setColor(0xCCFFFFFF);
        c.drawCircle(left + cs / 2, top + cs / 2, cs * 0.08f, fill);
    }

    private void drawHit(Canvas c, float left, float top, float cs) {
        float mx = left + cs / 2, my = top + cs / 2;
        // Flamme / Explosion
        path.reset();
        int spikes = 8;
        for (int i = 0; i < spikes * 2; i++) {
            double a = Math.PI * i / spikes;
            float rad = cs * (i % 2 == 0 ? 0.38f : 0.2f);
            float px = mx + (float) Math.cos(a) * rad, py = my + (float) Math.sin(a) * rad;
            if (i == 0) path.moveTo(px, py);
            else path.lineTo(px, py);
        }
        path.close();
        fill.setColor(0xFFFF6D00);
        c.drawPath(path, fill);
        fill.setColor(0xFFFFD600);
        c.drawCircle(mx, my, cs * 0.13f, fill);
    }

    /**
     * Zeichnet ein ganzes Fahrzeug von oben gesehen über seine volle Länge.
     * Ebene 0 = Flugzeug, 1 = Schiff, 2 = U-Boot.
     */
    private void drawShip(Canvas c, int l, int x, int y, int len, boolean horiz, float cs, boolean wreck) {
        c.save();
        if (horiz) {
            c.translate(cs * (x + 1), cs * (y + 1));
        } else {
            c.translate(cs * (x + 2), cs * (y + 1));
            c.rotate(90);
        }
        c.scale(cs, cs);
        stroke.setStrokeWidth(0.05f);
        if (l == 0) drawPlane(c, len, wreck);
        else if (l == 1) drawBoat(c, len, wreck);
        else drawSub(c, len, wreck);
        c.restore();
    }

    private int col(int normal, boolean wreck) {
        return wreck ? 0xFF3A2A28 : normal;
    }

    private void drawPlane(Canvas c, int len, boolean wreck) {
        float L = len;
        // Tragflächen
        float wx = L * 0.52f;
        path.reset();
        path.moveTo(wx - 0.25f, 0.44f);
        path.lineTo(wx + 0.35f, 0.44f);
        path.lineTo(wx - 0.05f, 0.03f);
        path.lineTo(wx - 0.45f, 0.03f);
        path.close();
        path.moveTo(wx - 0.25f, 0.56f);
        path.lineTo(wx + 0.35f, 0.56f);
        path.lineTo(wx - 0.05f, 0.97f);
        path.lineTo(wx - 0.45f, 0.97f);
        path.close();
        // Leitwerk
        path.moveTo(0.18f, 0.45f);
        path.lineTo(0.5f, 0.45f);
        path.lineTo(0.3f, 0.2f);
        path.lineTo(0.12f, 0.2f);
        path.close();
        path.moveTo(0.18f, 0.55f);
        path.lineTo(0.5f, 0.55f);
        path.lineTo(0.3f, 0.8f);
        path.lineTo(0.12f, 0.8f);
        path.close();
        fill.setColor(col(0xFFCFD6DE, wreck));
        c.drawPath(path, fill);
        stroke.setColor(0xFF4A5560);
        c.drawPath(path, stroke);
        // Rumpf
        path.reset();
        path.moveTo(0.1f, 0.42f);
        path.lineTo(L - 0.45f, 0.4f);
        path.quadTo(L - 0.05f, 0.5f, L - 0.45f, 0.6f);
        path.lineTo(0.1f, 0.58f);
        path.close();
        fill.setColor(col(0xFFF4F6F8, wreck));
        c.drawPath(path, fill);
        c.drawPath(path, stroke);
        // Cockpit
        fill.setColor(col(0xFF3D8BD1, wreck));
        r.set(L - 0.62f, 0.44f, L - 0.3f, 0.56f);
        c.drawOval(r, fill);
        // Triebwerke
        if (len >= 3) {
            fill.setColor(col(0xFF6B7782, wreck));
            r.set(wx - 0.2f, 0.22f, wx + 0.12f, 0.3f);
            c.drawRoundRect(r, 0.04f, 0.04f, fill);
            r.set(wx - 0.2f, 0.7f, wx + 0.12f, 0.78f);
            c.drawRoundRect(r, 0.04f, 0.04f, fill);
        }
    }

    private void drawBoat(Canvas c, int len, boolean wreck) {
        float L = len;
        // Rumpf mit spitzem Bug
        path.reset();
        path.moveTo(0.1f, 0.28f);
        path.lineTo(L - 0.7f, 0.25f);
        path.quadTo(L - 0.12f, 0.32f, L - 0.06f, 0.5f);
        path.quadTo(L - 0.12f, 0.68f, L - 0.7f, 0.75f);
        path.lineTo(0.1f, 0.72f);
        path.quadTo(0.02f, 0.5f, 0.1f, 0.28f);
        path.close();
        fill.setColor(col(0xFF7E8791, wreck));
        c.drawPath(path, fill);
        stroke.setColor(0xFF2E343A);
        c.drawPath(path, stroke);
        // Deck
        fill.setColor(col(0xFFA9B1BA, wreck));
        r.set(0.25f, 0.36f, L - 0.75f, 0.64f);
        c.drawRoundRect(r, 0.08f, 0.08f, fill);
        // Aufbau (Brücke)
        float bx = L * 0.45f;
        fill.setColor(col(0xFFE3E7EB, wreck));
        r.set(bx - 0.35f, 0.38f, bx + 0.2f, 0.62f);
        c.drawRoundRect(r, 0.06f, 0.06f, fill);
        fill.setColor(col(0xFF2B3138, wreck));
        c.drawCircle(bx - 0.15f, 0.5f, 0.07f, fill); // Schornstein
        // Geschütztürme
        fill.setColor(col(0xFF59616A, wreck));
        stroke.setColor(col(0xFF59616A, wreck));
        stroke.setStrokeWidth(0.05f);
        float[] turrets = len >= 4 ? new float[]{0.55f, L - 1.05f, L * 0.45f + 0.55f}
                : len == 3 ? new float[]{0.5f, L - 0.95f} : new float[]{L - 0.85f};
        for (float tx : turrets) {
            c.drawCircle(tx, 0.5f, 0.11f, fill);
            c.drawLine(tx, 0.5f, tx + 0.3f, 0.5f, stroke);
        }
        stroke.setStrokeWidth(0.05f);
    }

    private void drawSub(Canvas c, int len, boolean wreck) {
        float L = len;
        // Rumpf (Zigarrenform)
        path.reset();
        path.moveTo(0.25f, 0.5f);
        path.quadTo(0.3f, 0.3f, 0.7f, 0.28f);
        path.lineTo(L - 0.45f, 0.28f);
        path.quadTo(L - 0.05f, 0.33f, L - 0.05f, 0.5f);
        path.quadTo(L - 0.05f, 0.67f, L - 0.45f, 0.72f);
        path.lineTo(0.7f, 0.72f);
        path.quadTo(0.3f, 0.68f, 0.25f, 0.5f);
        path.close();
        fill.setColor(col(0xFF4F5B3A, wreck));
        c.drawPath(path, fill);
        stroke.setColor(0xFF1E2416);
        c.drawPath(path, stroke);
        // Heckruder
        path.reset();
        path.moveTo(0.3f, 0.5f);
        path.lineTo(0.08f, 0.3f);
        path.lineTo(0.08f, 0.7f);
        path.close();
        fill.setColor(col(0xFF3C4630, wreck));
        c.drawPath(path, fill);
        // Turm
        float tx = L * 0.58f;
        fill.setColor(col(0xFF6F7D52, wreck));
        r.set(tx - 0.28f, 0.41f, tx + 0.22f, 0.59f);
        c.drawRoundRect(r, 0.09f, 0.09f, fill);
        // Periskop
        fill.setColor(col(0xFF1E2416, wreck));
        c.drawCircle(tx + 0.08f, 0.5f, 0.04f, fill);
        // Luken
        fill.setColor(col(0xFF3C4630, wreck));
        c.drawCircle(L * 0.3f + 0.2f, 0.5f, 0.05f, fill);
        c.drawCircle(L - 0.55f, 0.5f, 0.05f, fill);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float h = getHeight();
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                float slop = getWidth() * 0.04f;
                if (!dragging && Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) {
                    dragging = true;
                    ValueAnimator old = anim;
                    anim = null;
                    if (old != null) old.cancel();
                }
                if (dragging) {
                    float p = layer - dy / h;
                    pos = Math.max(-0.2f, Math.min(Game.LAYERS - 0.8f, p));
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float dy = e.getY() - downY;
                if (dragging) {
                    dragging = false;
                    float moved = -dy / h;
                    int target = layer;
                    if (moved > 0.12f) target = layer + 1;
                    else if (moved < -0.12f) target = layer - 1;
                    if (target < 0 || target >= Game.LAYERS) target = layer;
                    boolean changed = target != layer;
                    setLayer(target);
                    if (changed && layerListener != null) layerListener.onLayer(target);
                } else {
                    tap(e.getX(), e.getY());
                }
                performClick();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    setLayer(layer);
                }
                return true;
            default:
                return true;
        }
    }

    private void tap(float x, float y) {
        if (anim != null || pos != layer) return;
        boardMatrix(mtx, getWidth(), getHeight());
        if (!mtx.invert(inv)) return;
        pt[0] = x;
        pt[1] = y;
        inv.mapPoints(pt);
        float cs = getWidth() / (float) (Game.N + 1);
        int cx = (int) Math.floor(pt[0] / cs) - 1;
        int cy = (int) Math.floor(pt[1] / cs) - 1;
        if (cx >= 0 && cy >= 0 && cx < Game.N && cy < Game.N && listener != null) {
            listener.onCell(cx, cy);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
