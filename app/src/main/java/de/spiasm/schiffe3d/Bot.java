package de.spiasm.schiffe3d;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Computer-Gegner. Spricht dieselben Textnachrichten wie die Bluetooth-Verbindung,
 * dadurch läuft das Spiel genau gleich ab.
 */
public class Bot {
    public interface Out {
        void onMessage(String line);
    }

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final Out out;
    private final boolean smart;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final Game game = new Game();
    /** Wissen über den Spieler: 0 unbekannt, 1 Wasser, 2 Treffer, 3 versenkt */
    private int[][][] known;
    private int[] sunk;
    private boolean stopped;

    public Bot(Out out, boolean smart) {
        this.out = out;
        this.smart = smart;
    }

    public void start() {
        newRound();
    }

    public void stop() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);
    }

    private void newRound() {
        handler.removeCallbacksAndMessages(null);
        game.reset();
        game.randomize(rnd);
        known = new int[Game.LAYERS][Game.N][Game.N];
        sunk = new int[Game.LAYERS];
        post("READY", 1200);
    }

    private void post(final String msg, long delay) {
        handler.postDelayed(() -> {
            if (!stopped) out.onMessage(msg);
        }, delay);
    }

    private void shootLater(long delay) {
        handler.postDelayed(this::shoot, delay);
    }

    public void receive(String line) {
        if (stopped) return;
        String[] p = line.split(" ");
        try {
            switch (p[0]) {
                case "SHOT": {
                    int l = Integer.parseInt(p[1]), x = Integer.parseInt(p[2]), y = Integer.parseInt(p[3]);
                    if (!Game.valid(l, x, y)) return;
                    int[] r = game.receiveShot(l, x, y);
                    if (r[0] == 2) {
                        Game.Ship s = game.ships[r[1]];
                        post("RES " + l + " " + x + " " + y + " 2 " + s.x + " " + s.y + " " + s.len + " " + (s.horiz ? 1 : 0), 400);
                    } else {
                        post("RES " + l + " " + x + " " + y + " " + r[0], 400);
                    }
                    if (r[0] == 0) shootLater(1500);
                    break;
                }
                case "SCAN": {
                    int l = Integer.parseInt(p[1]), x = Integer.parseInt(p[2]), y = Integer.parseInt(p[3]);
                    if (!Game.valid(l, x, y)) return;
                    post("SCANRES " + l + " " + x + " " + y + " " + game.scanCount(l, x, y), 600);
                    shootLater(1800);
                    break;
                }
                case "RES": {
                    int l = Integer.parseInt(p[1]), x = Integer.parseInt(p[2]), y = Integer.parseInt(p[3]);
                    int r = Integer.parseInt(p[4]);
                    if (!Game.valid(l, x, y)) return;
                    if (r == 0) {
                        known[l][x][y] = 1;
                    } else {
                        known[l][x][y] = 2;
                        if (r == 2 && p.length >= 9) {
                            int sx = Integer.parseInt(p[5]), sy = Integer.parseInt(p[6]);
                            int len = Integer.parseInt(p[7]);
                            boolean h = "1".equals(p[8]);
                            for (int i = 0; i < len; i++) {
                                int cx = h ? sx + i : sx, cy = h ? sy : sy + i;
                                if (Game.valid(l, cx, cy)) known[l][cx][cy] = 3;
                            }
                            sunk[l]++;
                        }
                        shootLater(1200);
                    }
                    break;
                }
                case "AGAIN":
                    newRound();
                    break;
                default:
                    break;
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void shoot() {
        if (stopped) return;
        int[] c = choose();
        if (c == null) return;
        out.onMessage("SHOT " + c[0] + " " + c[1] + " " + c[2]);
    }

    private boolean layerOpen(int l) {
        return Game.shipsInLayer(l) - sunk[l] > 0;
    }

    private int[] choose() {
        List<int[]> cand = new ArrayList<>();
        int n = Game.N;

        if (smart) {
            // Zielmodus: rund um Treffer weitersuchen, bevorzugt in einer Linie
            for (int l = 0; l < Game.LAYERS; l++) {
                for (int x = 0; x < n; x++) {
                    for (int y = 0; y < n; y++) {
                        if (known[l][x][y] != 2) continue;
                        for (int[] d : DIRS) {
                            int nx = x + d[0], ny = y + d[1];
                            if (!Game.valid(l, nx, ny) || known[l][nx][ny] != 0) continue;
                            int bx = x - d[0], by = y - d[1];
                            boolean inLine = Game.valid(l, bx, by) && known[l][bx][by] == 2;
                            int weight = inLine ? 4 : 1;
                            for (int w = 0; w < weight; w++) cand.add(new int[]{l, nx, ny});
                        }
                    }
                }
            }
            if (!cand.isEmpty()) return cand.get(rnd.nextInt(cand.size()));

            // Suchmodus: Schachbrettmuster, nur Ebenen mit übrigen Schiffen
            for (int l = 0; l < Game.LAYERS; l++) {
                if (!layerOpen(l)) continue;
                for (int x = 0; x < n; x++) {
                    for (int y = 0; y < n; y++) {
                        if (known[l][x][y] == 0 && (x + y) % 2 == 0) cand.add(new int[]{l, x, y});
                    }
                }
            }
            if (!cand.isEmpty()) return cand.get(rnd.nextInt(cand.size()));
        }

        for (int l = 0; l < Game.LAYERS; l++) {
            if (!layerOpen(l)) continue;
            for (int x = 0; x < n; x++) {
                for (int y = 0; y < n; y++) {
                    if (known[l][x][y] == 0) cand.add(new int[]{l, x, y});
                }
            }
        }
        if (cand.isEmpty()) {
            for (int l = 0; l < Game.LAYERS; l++)
                for (int x = 0; x < n; x++)
                    for (int y = 0; y < n; y++)
                        if (known[l][x][y] == 0) cand.add(new int[]{l, x, y});
        }
        return cand.isEmpty() ? null : cand.get(rnd.nextInt(cand.size()));
    }
}
