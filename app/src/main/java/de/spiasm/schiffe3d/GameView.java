package de.spiasm.schiffe3d;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Zeichnet eine Ebene (8x8) – entweder die eigene Flotte oder die Sicht auf den Gegner. */
public class GameView extends View {
    public interface Listener {
        void onCell(int x, int y);
    }

    public static final int[] LAYER_BG = {0xFF7FB9E6, 0xFF2368A8, 0xFF102A4C};
    public static final int[] SHIP_COLOR = {0xFFF2F2F2, 0xFF8A9099, 0xFF5E6B45};

    public Game game;
    public boolean enemyView;
    public int layer;
    public Listener listener;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    public GameView(Context c) {
        super(c);
        label.setColor(0xFFCFD8E3);
        label.setTextAlign(Paint.Align.CENTER);
        line.setColor(0x55FFFFFF);
        line.setStyle(Paint.Style.STROKE);
        cross.setStyle(Paint.Style.STROKE);
        cross.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(w, w);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (game == null) return;
        int n = Game.N;
        float cs = getWidth() / (float) (n + 1);

        // Beschriftung A-H und 1-8
        label.setTextSize(cs * 0.42f);
        for (int i = 0; i < n; i++) {
            c.drawText(String.valueOf((char) ('A' + i)), cs * (i + 1.5f), cs * 0.68f, label);
            c.drawText(String.valueOf(i + 1), cs * 0.5f, cs * (i + 1.5f) + cs * 0.15f, label);
        }

        // Hintergrund der Ebene
        fill.setColor(LAYER_BG[layer]);
        c.drawRect(cs, cs, cs * (n + 1), cs * (n + 1), fill);

        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n; y++) {
                float left = cs * (x + 1), top = cs * (y + 1);
                if (!enemyView) {
                    int id = game.own[layer][x][y];
                    if (id >= 0) {
                        Game.Ship s = game.ships[id];
                        fill.setColor(s.sunk() ? 0xFF6B2020 : SHIP_COLOR[layer]);
                        r.set(left + cs * 0.08f, top + cs * 0.08f, left + cs * 0.92f, top + cs * 0.92f);
                        c.drawRoundRect(r, cs * 0.2f, cs * 0.2f, fill);
                        label.setTextSize(cs * 0.45f);
                        c.drawText(Game.LAYER_ICONS[layer], left + cs / 2, top + cs * 0.66f, label);
                    }
                    if (game.incoming[layer][x][y]) {
                        if (id >= 0) drawCross(c, left, top, cs, 0xFFFF3B30);
                        else drawDot(c, left, top, cs);
                    }
                } else {
                    int v = game.enemy[layer][x][y];
                    if (v == 1) {
                        drawDot(c, left, top, cs);
                    } else if (v == 2 || v == 3) {
                        fill.setColor(v == 2 ? 0xFFE07A1F : 0xFF8B1A1A);
                        r.set(left + cs * 0.06f, top + cs * 0.06f, left + cs * 0.94f, top + cs * 0.94f);
                        c.drawRoundRect(r, cs * 0.2f, cs * 0.2f, fill);
                        drawCross(c, left, top, cs, 0xFFFFFFFF);
                    }
                }
            }
        }

        // Gitterlinien
        line.setStrokeWidth(Math.max(1f, cs * 0.03f));
        for (int i = 0; i <= n; i++) {
            c.drawLine(cs * (i + 1), cs, cs * (i + 1), cs * (n + 1), line);
            c.drawLine(cs, cs * (i + 1), cs * (n + 1), cs * (i + 1), line);
        }

        // Radar/Sonar-Bereiche
        if (enemyView) {
            for (int[] s : game.scans) {
                if (s[0] != layer) continue;
                int x0 = Math.max(0, s[1] - 1), y0 = Math.max(0, s[2] - 1);
                int x1 = Math.min(n - 1, s[1] + 1), y1 = Math.min(n - 1, s[2] + 1);
                r.set(cs * (x0 + 1), cs * (y0 + 1), cs * (x1 + 2), cs * (y1 + 2));
                fill.setColor(0x44FFD54F);
                c.drawRect(r, fill);
                cross.setColor(0xFFFFD54F);
                cross.setStrokeWidth(cs * 0.06f);
                c.drawRect(r, cross);
                label.setTextSize(cs * 0.6f);
                int old = label.getColor();
                label.setColor(0xFFFFD54F);
                c.drawText(String.valueOf(s[3]), cs * (s[1] + 1.5f), cs * (s[2] + 1.5f) + cs * 0.22f, label);
                label.setColor(old);
            }
        }
    }

    private void drawCross(Canvas c, float left, float top, float cs, int color) {
        cross.setColor(color);
        cross.setStrokeWidth(cs * 0.1f);
        float a = cs * 0.25f, b = cs * 0.75f;
        c.drawLine(left + a, top + a, left + b, top + b, cross);
        c.drawLine(left + b, top + a, left + a, top + b, cross);
    }

    private void drawDot(Canvas c, float left, float top, float cs) {
        fill.setColor(0xCCFFFFFF);
        c.drawCircle(left + cs / 2, top + cs / 2, cs * 0.12f, fill);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float cs = getWidth() / (float) (Game.N + 1);
            int x = (int) (e.getX() / cs) - 1;
            int y = (int) (e.getY() / cs) - 1;
            if (x >= 0 && y >= 0 && x < Game.N && y < Game.N && listener != null) {
                listener.onCell(x, y);
            }
            performClick();
            return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
