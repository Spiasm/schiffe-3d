package de.spiasm.schiffe3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Spiellogik: 3 Ebenen (Luft, Wasser, Tiefe) mit je 8x8 Feldern. */
public class Game {
    public static final int N = 8;
    public static final int LAYERS = 3;
    public static final String[] LAYER_NAMES = {"Luft", "Wasser", "Tiefe"};
    public static final String[] LAYER_ICONS = {"✈", "🚢", "⚓"};

    /** {Ebene, Länge} */
    public static final int[][] FLEET = {{0, 3}, {0, 2}, {1, 4}, {1, 3}, {1, 2}, {2, 3}, {2, 2}};
    public static final String[] SHIP_NAMES = {
            "Bomber", "Jäger", "Schlachtschiff", "Kreuzer", "Zerstörer", "U-Boot", "Mini-U-Boot"};

    public static class Ship {
        public final int layer;
        public final int len;
        public final String name;
        public int x, y;
        public boolean horiz, placed;
        public int hits;

        Ship(int layer, int len, String name) {
            this.layer = layer;
            this.len = len;
            this.name = name;
        }

        public boolean sunk() {
            return placed && hits >= len;
        }
    }

    public Ship[] ships;
    /** Eigene Felder: Index des Schiffs oder -1 */
    public int[][][] own;
    /** Hat der Gegner hier hingeschossen? */
    public boolean[][][] incoming;
    /** Sicht auf den Gegner: 0 unbekannt, 1 Wasser, 2 Treffer, 3 versenkt */
    public int[][][] enemy;
    public int[] enemySunk;
    public int enemyHits;
    public int ownLost;
    /** Radar/Sonar-Ergebnisse: {Ebene, x, y, Anzahl} */
    public final List<int[]> scans = new ArrayList<>();
    /** Versenkte Gegnerschiffe: {Ebene, x, y, Länge, waagrecht 1/0} */
    public final List<int[]> enemyWrecks = new ArrayList<>();
    public boolean radarUsed, sonarUsed;

    public Game() {
        reset();
    }

    public void reset() {
        ships = new Ship[FLEET.length];
        for (int i = 0; i < FLEET.length; i++) {
            ships[i] = new Ship(FLEET[i][0], FLEET[i][1], SHIP_NAMES[i]);
        }
        own = new int[LAYERS][N][N];
        for (int[][] layer : own) {
            for (int[] row : layer) {
                Arrays.fill(row, -1);
            }
        }
        incoming = new boolean[LAYERS][N][N];
        enemy = new int[LAYERS][N][N];
        enemySunk = new int[LAYERS];
        enemyHits = 0;
        ownLost = 0;
        scans.clear();
        enemyWrecks.clear();
        radarUsed = false;
        sonarUsed = false;
    }

    public static int totalCells() {
        int t = 0;
        for (int[] f : FLEET) t += f[1];
        return t;
    }

    public static int shipsInLayer(int layer) {
        int t = 0;
        for (int[] f : FLEET) if (f[0] == layer) t++;
        return t;
    }

    public static boolean valid(int l, int x, int y) {
        return l >= 0 && l < LAYERS && x >= 0 && x < N && y >= 0 && y < N;
    }

    public static String cellName(int x, int y) {
        return String.valueOf((char) ('A' + x)) + (y + 1);
    }

    public boolean canPlace(int layer, int len, int x, int y, boolean h) {
        for (int i = 0; i < len; i++) {
            int cx = h ? x + i : x;
            int cy = h ? y : y + i;
            if (cx < 0 || cy < 0 || cx >= N || cy >= N) return false;
            if (own[layer][cx][cy] >= 0) return false;
        }
        return true;
    }

    public boolean place(int idx, int x, int y, boolean h) {
        Ship s = ships[idx];
        if (s.placed) remove(idx);
        if (!canPlace(s.layer, s.len, x, y, h)) return false;
        s.x = x;
        s.y = y;
        s.horiz = h;
        for (int i = 0; i < s.len; i++) {
            int cx = h ? x + i : x;
            int cy = h ? y : y + i;
            own[s.layer][cx][cy] = idx;
        }
        s.placed = true;
        return true;
    }

    public void remove(int idx) {
        Ship s = ships[idx];
        if (!s.placed) return;
        for (int i = 0; i < s.len; i++) {
            int cx = s.horiz ? s.x + i : s.x;
            int cy = s.horiz ? s.y : s.y + i;
            own[s.layer][cx][cy] = -1;
        }
        s.placed = false;
    }

    public void clearAll() {
        for (int i = 0; i < ships.length; i++) remove(i);
    }

    public void randomize(Random r) {
        clearAll();
        for (int i = 0; i < ships.length; i++) {
            int tries = 0;
            while (!ships[i].placed && tries++ < 2000) {
                place(i, r.nextInt(N), r.nextInt(N), r.nextBoolean());
            }
        }
    }

    public int nextUnplaced(int layer) {
        for (int i = 0; i < ships.length; i++) {
            if (ships[i].layer == layer && !ships[i].placed) return i;
        }
        return -1;
    }

    public boolean allPlaced() {
        for (Ship s : ships) if (!s.placed) return false;
        return true;
    }

    /** Ergebnis: {0 Wasser / 1 Treffer / 2 versenkt, Schiffsindex} */
    public int[] receiveShot(int l, int x, int y) {
        int id = own[l][x][y];
        if (incoming[l][x][y]) return new int[]{id >= 0 ? 1 : 0, -1};
        incoming[l][x][y] = true;
        if (id < 0) return new int[]{0, -1};
        Ship s = ships[id];
        s.hits++;
        ownLost++;
        return new int[]{s.hits >= s.len ? 2 : 1, id};
    }

    public boolean defeated() {
        return ownLost >= totalCells();
    }

    public int scanCount(int l, int x, int y) {
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                int cx = x + dx, cy = y + dy;
                if (valid(l, cx, cy) && own[l][cx][cy] >= 0) n++;
            }
        }
        return n;
    }

    public void markSunk(int l, int sx, int sy, int len, boolean h) {
        enemyWrecks.add(new int[]{l, sx, sy, len, h ? 1 : 0});
        for (int i = 0; i < len; i++) {
            int cx = h ? sx + i : sx;
            int cy = h ? sy : sy + i;
            if (valid(l, cx, cy)) enemy[l][cx][cy] = 3;
        }
    }

    public int ownShipsLeft(int layer) {
        int n = 0;
        for (Ship s : ships) if (s.layer == layer && !s.sunk()) n++;
        return n;
    }

    public int enemyShipsLeft(int layer) {
        return shipsInLayer(layer) - enemySunk[layer];
    }
}
