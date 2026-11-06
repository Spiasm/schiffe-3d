package de.spiasm.schiffe3d;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

@SuppressLint("MissingPermission")
public class MainActivity extends Activity implements BtLink.Listener {
    private static final int REQ_PERM = 1;
    private static final int REQ_ENABLE = 2;
    private static final String PERM_CONNECT = "android.permission.BLUETOOTH_CONNECT";

    private static final int BG = 0xFF0E1A2B;
    private static final int BTN = 0xFF2A3A50;
    private static final int ACCENT = 0xFF1E88E5;
    private static final int GREEN = 0xFF2E7D32;
    private static final int RED = 0xFFC62828;

    private static final int S_MENU = 0, S_WAIT = 1, S_PICK = 2, S_PLACE = 3, S_BATTLE = 4, S_END = 5, S_RULES = 6;

    private BluetoothAdapter adapter;
    private BtLink link;
    private Bot bot;
    private final Game game = new Game();
    private final Random random = new Random();
    private String peerName = "Gegner";
    private int state = S_MENU;
    private Runnable pending;

    private boolean isHost, meReady, peerReady, myTurn, waiting;
    private boolean horiz = true;
    private boolean viewEnemy = true;
    private int viewLayer = 1;
    private int scanMode = -1;

    private LinearLayout root;
    private GameView board;
    private TextView info, status, turnText;
    private final List<Button> layerTabs = new ArrayList<>();
    private Button btnRotate, btnRandom, btnClear, btnReady, btnEnemy, btnOwn, btnRadar, btnSonar;

    // ------------------------------------------------------------------ Start

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        showMenu(null);
    }

    @Override
    protected void onDestroy() {
        closeLink();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (state == S_MENU) {
            super.onBackPressed();
        } else if (state == S_RULES || state == S_PICK) {
            showMenu(null);
        } else {
            new AlertDialog.Builder(this)
                    .setMessage(bot != null ? "Spiel verlassen?" : "Spiel verlassen? Die Verbindung wird getrennt.")
                    .setPositiveButton("Verlassen", (d, w) -> {
                        closeLink();
                        showMenu(null);
                    })
                    .setNegativeButton("Weiterspielen", null)
                    .show();
        }
    }

    // ------------------------------------------------------------- Bluetooth

    private void withBluetooth(Runnable r) {
        if (adapter == null) {
            toast("Dieses Gerät hat kein Bluetooth.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(PERM_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            pending = r;
            requestPermissions(new String[]{PERM_CONNECT}, REQ_PERM);
            return;
        }
        if (!adapter.isEnabled()) {
            pending = r;
            try {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE);
            } catch (Exception e) {
                toast("Bitte Bluetooth einschalten.");
            }
            return;
        }
        r.run();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_PERM) return;
        Runnable p = pending;
        pending = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && p != null) {
            withBluetooth(p);
        } else {
            toast("Ohne die Erlaubnis „Geräte in der Nähe“ geht Bluetooth nicht.");
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code != REQ_ENABLE) return;
        Runnable p = pending;
        pending = null;
        if (adapter != null && adapter.isEnabled() && p != null) p.run();
        else toast("Bluetooth ist aus.");
    }

    private void closeLink() {
        if (link != null) {
            link.close();
            link = null;
        }
        if (bot != null) {
            bot.stop();
            bot = null;
        }
    }

    private void send(String msg) {
        if (bot != null) bot.receive(msg);
        else if (link != null) link.send(msg);
    }

    private void startBot(boolean smart) {
        closeLink();
        isHost = true;
        peerName = "Computer";
        bot = new Bot(this::onMessage, smart);
        startNewRound();
        bot.start();
    }

    private void showBotPick() {
        state = S_RULES;
        newScreen();
        add(text("🤖 Gegen den Computer", 24, Color.WHITE, true));
        add(text("Wie schlau soll der Computer sein?", 16, 0xFF9FB3C8, false));
        add(button("😊  Leicht – schießt wild drauflos", GREEN, v -> startBot(false)));
        add(button("😈  Normal – jagt getroffene Schiffe gezielt", RED, v -> startBot(true)));
        add(button("Zurück", BTN, v -> showMenu(null)));
    }

    @Override
    public void onConnected(String name) {
        peerName = name;
        toast("Verbunden mit " + name);
        startNewRound();
    }

    @Override
    public void onClosed(String reason) {
        link = null;
        showMenu(reason);
    }

    @Override
    public void onMessage(String line) {
        String[] p = line.split(" ");
        if (p.length == 0) return;
        try {
            switch (p[0]) {
                case "READY":
                    peerReady = true;
                    if (state == S_PLACE) {
                        if (meReady) startBattle();
                        else setStatus(peerName + " ist schon fertig mit Aufstellen.");
                    }
                    break;
                case "SHOT":
                    if (state == S_BATTLE) onShot(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                    break;
                case "RES":
                    if (state == S_BATTLE) onResult(p);
                    break;
                case "SCAN":
                    if (state == S_BATTLE) onScan(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                    break;
                case "SCANRES":
                    if (state == S_BATTLE) onScanResult(Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                            Integer.parseInt(p[3]), Integer.parseInt(p[4]));
                    break;
                case "AGAIN":
                    if (state == S_END) {
                        toast(peerName + " will nochmal spielen!");
                        startNewRound();
                    }
                    break;
                default:
                    break;
            }
        } catch (RuntimeException e) {
            // kaputte Nachricht ignorieren
        }
    }

    // ----------------------------------------------------------- Spielablauf

    private void startNewRound() {
        game.reset();
        meReady = false;
        peerReady = false;
        waiting = false;
        scanMode = -1;
        viewLayer = 0;
        showPlace();
    }

    private void startBattle() {
        myTurn = isHost;
        waiting = false;
        viewEnemy = true;
        viewLayer = 1;
        showBattle();
        setInfo(myTurn ? "Du fängst an! Wähle eine Ebene und tippe auf ein Feld."
                : peerName + " fängt an. Gleich bist du dran.");
    }

    private void onShot(int l, int x, int y) {
        if (!Game.valid(l, x, y)) return;
        int[] res = game.receiveShot(l, x, y);
        String where = Game.LAYER_NAMES[l] + " " + Game.cellName(x, y);
        if (res[0] == 2) {
            Game.Ship s = game.ships[res[1]];
            send("RES " + l + " " + x + " " + y + " 2 " + s.x + " " + s.y + " " + s.len + " " + (s.horiz ? 1 : 0));
            setInfo("💥 " + peerName + " hat deinen " + s.name + " versenkt! (" + where + ")");
        } else if (res[0] == 1) {
            send("RES " + l + " " + x + " " + y + " 1");
            setInfo("🔥 " + peerName + " hat getroffen: " + where);
        } else {
            send("RES " + l + " " + x + " " + y + " 0");
            setInfo("💦 " + peerName + " hat daneben geschossen: " + where);
            myTurn = true;
        }
        if (game.defeated()) {
            showEnd(false);
            return;
        }
        refreshBattle();
    }

    private void onResult(String[] p) {
        int l = Integer.parseInt(p[1]), x = Integer.parseInt(p[2]), y = Integer.parseInt(p[3]);
        int r = Integer.parseInt(p[4]);
        if (!Game.valid(l, x, y)) return;
        waiting = false;
        String where = Game.LAYER_NAMES[l] + " " + Game.cellName(x, y);
        if (r == 0) {
            game.enemy[l][x][y] = 1;
            myTurn = false;
            setInfo("💦 Daneben (" + where + "). Jetzt ist " + peerName + " dran.");
        } else {
            if (game.enemy[l][x][y] == 0) game.enemyHits++;
            game.enemy[l][x][y] = 2;
            if (r == 2 && p.length >= 9) {
                game.markSunk(l, Integer.parseInt(p[5]), Integer.parseInt(p[6]), Integer.parseInt(p[7]), "1".equals(p[8]));
                game.enemySunk[l]++;
                setInfo("💥 Versenkt! (" + where + ") Du darfst nochmal.");
            } else {
                setInfo("🔥 Treffer! (" + where + ") Du darfst nochmal.");
            }
        }
        if (game.enemyHits >= Game.totalCells()) {
            showEnd(true);
            return;
        }
        refreshBattle();
    }

    private void onScan(int l, int x, int y) {
        if (!Game.valid(l, x, y)) return;
        int n = game.scanCount(l, x, y);
        send("SCANRES " + l + " " + x + " " + y + " " + n);
        setInfo((l == 0 ? "📡 " + peerName + " hat mit dem Radar" : "🔊 " + peerName + " hat mit dem Sonar")
                + " um " + Game.cellName(x, y) + " gesucht.");
        myTurn = true;
        refreshBattle();
    }

    private void onScanResult(int l, int x, int y, int n) {
        if (!Game.valid(l, x, y)) return;
        waiting = false;
        game.scans.add(new int[]{l, x, y, n});
        myTurn = false;
        setInfo((l == 0 ? "📡 Radar: " : "🔊 Sonar: ") + n + (n == 1 ? " Feld" : " Felder")
                + " im gelben Bereich belegt. Jetzt ist " + peerName + " dran.");
        refreshBattle();
    }

    private void onBattleTap(int x, int y) {
        if (!viewEnemy) {
            toast("Zum Schießen oben auf „Gegner“ tippen.");
            return;
        }
        if (!myTurn || waiting) {
            toast(peerName + " ist dran – kurz warten.");
            return;
        }
        int l = viewLayer;
        if (scanMode >= 0) {
            if (scanMode == 0) game.radarUsed = true;
            else game.sonarUsed = true;
            scanMode = -1;
            waiting = true;
            send("SCAN " + l + " " + x + " " + y);
            setInfo("Suche läuft…");
            refreshBattle();
            return;
        }
        if (game.enemy[l][x][y] != 0) {
            toast("Da hast du schon hingeschossen.");
            return;
        }
        waiting = true;
        send("SHOT " + l + " " + x + " " + y);
        setInfo("Schuss auf " + Game.LAYER_NAMES[l] + " " + Game.cellName(x, y) + "…");
        refreshBattle();
    }

    // ------------------------------------------------------------- Oberfläche

    private void showMenu(String message) {
        state = S_MENU;
        newScreen();
        add(text("⚓ Schiffe versenken 3D", 28, Color.WHITE, true));
        add(text("✈ Luft  •  🚢 Wasser  •  ⚓ Tiefe\nZu zweit per Bluetooth", 16, 0xFF9FB3C8, false));
        if (message != null) add(text(message, 15, 0xFFFFB74D, false));
        add(button("🛰  Spiel erstellen", ACCENT, v -> withBluetooth(this::hostGame)));
        add(button("🔗  Spiel beitreten", ACCENT, v -> withBluetooth(this::showPick)));
        add(button("🤖  Gegen den Computer", GREEN, v -> showBotPick()));
        add(button("📖  Spielregeln", BTN, v -> showRules()));
        add(text("So geht's:\n1. Koppelt eure Handys einmal in den Bluetooth-Einstellungen.\n"
                + "2. Einer tippt „Spiel erstellen“.\n3. Der andere tippt „Spiel beitreten“ und wählt das Handy aus.",
                14, 0xFF9FB3C8, false));
    }

    private void showRules() {
        state = S_RULES;
        newScreen();
        add(text("📖 Spielregeln", 24, Color.WHITE, true));
        add(text("Jeder hat 3 Ebenen mit je 8×8 Feldern:\n\n"
                + "✈ Luft: Bomber (3), Jäger (2)\n"
                + "🚢 Wasser: Schlachtschiff (4), Kreuzer (3), Zerstörer (2)\n"
                + "⚓ Tiefe: U-Boot (3), Mini-U-Boot (2)\n\n"
                + "Ihr schießt abwechselnd: Ebene wählen, Feld antippen.\n"
                + "Treffer = du darfst nochmal schießen.\n\n"
                + "📡 Radar (1× pro Spiel): zeigt, wie viele Felder in einem 3×3-Bereich der Luft belegt sind.\n"
                + "🔊 Sonar (1× pro Spiel): dasselbe für die Tiefe.\n"
                + "Radar und Sonar kosten deinen Zug.\n\n"
                + "Wer zuerst alle Schiffe des anderen versenkt, gewinnt!",
                16, 0xFFDDE6F0, false));
        add(button("Zurück", BTN, v -> showMenu(null)));
    }

    private void hostGame() {
        closeLink();
        isHost = true;
        link = new BtLink(this);
        link.host(adapter);
        state = S_WAIT;
        newScreen();
        String myName;
        try {
            myName = adapter.getName();
        } catch (SecurityException e) {
            myName = null;
        }
        add(text("⏳ Warte auf deinen Kumpel…", 24, Color.WHITE, true));
        add(text("Er soll jetzt „Spiel beitreten“ tippen und dieses Handy auswählen"
                + (myName != null ? ":\n\n📱 " + myName : "."), 16, 0xFFDDE6F0, false));
        add(button("Abbrechen", RED, v -> {
            closeLink();
            showMenu(null);
        }));
    }

    private void showPick() {
        state = S_PICK;
        newScreen();
        add(text("🔗 Mit wem spielen?", 24, Color.WHITE, true));
        Set<BluetoothDevice> devices;
        try {
            devices = adapter.getBondedDevices();
        } catch (SecurityException e) {
            devices = null;
        }
        if (devices == null || devices.isEmpty()) {
            add(text("Keine gekoppelten Geräte gefunden. Koppelt eure Handys zuerst in den Bluetooth-Einstellungen.",
                    16, 0xFFFFB74D, false));
        } else {
            add(text("Tippe auf das Handy deines Kumpels. Er muss vorher „Spiel erstellen“ getippt haben.",
                    15, 0xFF9FB3C8, false));
            for (final BluetoothDevice d : devices) {
                String name;
                try {
                    name = d.getName();
                } catch (SecurityException e) {
                    name = null;
                }
                final String label = name == null ? d.getAddress() : name;
                add(button("📱  " + label, ACCENT, v -> {
                    closeLink();
                    isHost = false;
                    link = new BtLink(this);
                    link.join(d, adapter);
                    state = S_WAIT;
                    newScreen();
                    add(text("⏳ Verbinde mit " + label + "…", 22, Color.WHITE, true));
                    add(button("Abbrechen", RED, w -> {
                        closeLink();
                        showMenu(null);
                    }));
                }));
            }
        }
        add(button("⚙  Bluetooth-Einstellungen öffnen", BTN, v -> {
            try {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            } catch (Exception ignored) {
            }
        }));
        add(button("Zurück", BTN, v -> showMenu(null)));
    }

    private void showPlace() {
        state = S_PLACE;
        newScreen();
        add(text("🛠 Flotte aufstellen", 24, Color.WHITE, true));
        add(layerTabRow());
        board = new GameView(this);
        board.game = game;
        board.enemyView = false;
        board.listener = this::onPlaceTap;
        add(board);
        info = text("", 16, Color.WHITE, false);
        add(info);
        btnRotate = button("", BTN, v -> {
            horiz = !horiz;
            refreshPlace();
        });
        btnRandom = button("🎲 Zufällig", BTN, v -> {
            game.randomize(random);
            refreshPlace();
        });
        btnClear = button("🗑 Leeren", BTN, v -> {
            game.clearAll();
            refreshPlace();
        });
        add(row(btnRotate, btnRandom, btnClear));
        btnReady = button("✅  Fertig!", GREEN, v -> {
            if (!game.allPlaced()) {
                toast("Erst alle Schiffe auf allen 3 Ebenen aufstellen.");
                return;
            }
            meReady = true;
            send("READY");
            if (peerReady) startBattle();
            else refreshPlace();
        });
        add(btnReady);
        status = text("", 14, 0xFF9FB3C8, false);
        add(status);
        refreshPlace();
    }

    private void onPlaceTap(int x, int y) {
        if (meReady) return;
        int l = viewLayer;
        int id = game.own[l][x][y];
        if (id >= 0) {
            game.remove(id);
        } else {
            int next = game.nextUnplaced(l);
            if (next < 0) {
                toast("Diese Ebene ist voll. Tippe ein Schiff an, um es wegzunehmen.");
                return;
            }
            if (!game.place(next, x, y, horiz)) {
                toast("Passt da nicht hin – drehen oder anderes Feld.");
                return;
            }
            if (game.nextUnplaced(l) < 0) {
                for (int i = 0; i < Game.LAYERS; i++) {
                    if (game.nextUnplaced(i) >= 0) {
                        viewLayer = i;
                        break;
                    }
                }
            }
        }
        refreshPlace();
    }

    private void refreshPlace() {
        for (int i = 0; i < Game.LAYERS; i++) {
            boolean done = game.nextUnplaced(i) < 0;
            Button t = layerTabs.get(i);
            t.setText(Game.LAYER_ICONS[i] + " " + Game.LAYER_NAMES[i] + (done ? " ✔" : ""));
            setColor(t, i == viewLayer ? GameView.LAYER_BG[i] : BTN);
        }
        board.layer = viewLayer;
        board.invalidate();
        int next = game.nextUnplaced(viewLayer);
        if (meReady) {
            info.setText("✅ Deine Flotte steht.");
        } else if (next >= 0) {
            Game.Ship s = game.ships[next];
            info.setText("Nächstes: " + s.name + " (" + s.len + " Felder)\nTippe auf das Startfeld. Antippen eines Schiffs nimmt es wieder weg.");
        } else if (game.allPlaced()) {
            info.setText("Alles aufgestellt! Tippe auf „Fertig“.");
        } else {
            info.setText("Diese Ebene ist fertig ✔ – wechsle oben die Ebene.");
        }
        btnRotate.setText(horiz ? "↔ Waagrecht" : "↕ Senkrecht");
        setEnabled(btnRotate, !meReady);
        setEnabled(btnRandom, !meReady);
        setEnabled(btnClear, !meReady);
        setEnabled(btnReady, !meReady);
        if (meReady) setStatus("⏳ Warte, bis " + peerName + " fertig ist…");
        else if (peerReady) setStatus(peerName + " ist schon fertig mit Aufstellen.");
        else setStatus("Gegner: " + peerName);
    }

    private void showBattle() {
        state = S_BATTLE;
        newScreen();
        turnText = text("", 22, Color.WHITE, true);
        add(turnText);
        btnEnemy = button("🎯 Gegner", BTN, v -> {
            viewEnemy = true;
            refreshBattle();
        });
        btnOwn = button("🛡 Meine Flotte", BTN, v -> {
            viewEnemy = false;
            scanMode = -1;
            refreshBattle();
        });
        add(row(btnEnemy, btnOwn));
        add(layerTabRow());
        board = new GameView(this);
        board.game = game;
        board.listener = this::onBattleTap;
        add(board);
        btnRadar = button("", BTN, v -> toggleScan(0));
        btnSonar = button("", BTN, v -> toggleScan(2));
        add(row(btnRadar, btnSonar));
        info = text("", 16, Color.WHITE, false);
        add(info);
        Button leave = button("Spiel verlassen", BTN, v -> onBackPressed());
        add(leave);
        refreshBattle();
    }

    private void toggleScan(int layer) {
        if (!myTurn || waiting) {
            toast(peerName + " ist dran – kurz warten.");
            return;
        }
        if (scanMode == layer) {
            scanMode = -1;
            setInfo("Abgebrochen. Tippe auf ein Feld zum Schießen.");
        } else {
            scanMode = layer;
            viewLayer = layer;
            viewEnemy = true;
            setInfo((layer == 0 ? "📡 Radar" : "🔊 Sonar") + " bereit: Tippe auf die Mitte des Suchbereichs (3×3).");
        }
        refreshBattle();
    }

    private void refreshBattle() {
        if (state != S_BATTLE) return;
        if (waiting) {
            turnText.setText("⏳ Warte auf Antwort…");
            turnText.setTextColor(0xFFFFD54F);
        } else if (myTurn) {
            turnText.setText("🎯 Du bist dran!");
            turnText.setTextColor(0xFF81C784);
        } else {
            turnText.setText("⏳ " + peerName + " ist dran…");
            turnText.setTextColor(0xFF9FB3C8);
        }
        setColor(btnEnemy, viewEnemy ? ACCENT : BTN);
        setColor(btnOwn, !viewEnemy ? ACCENT : BTN);
        for (int i = 0; i < Game.LAYERS; i++) {
            int left = viewEnemy ? game.enemyShipsLeft(i) : game.ownShipsLeft(i);
            Button t = layerTabs.get(i);
            t.setText(Game.LAYER_ICONS[i] + " " + Game.LAYER_NAMES[i] + " (" + left + ")");
            setColor(t, i == viewLayer ? GameView.LAYER_BG[i] : BTN);
        }
        board.layer = viewLayer;
        board.enemyView = viewEnemy;
        board.invalidate();
        boolean canAct = myTurn && !waiting;
        btnRadar.setText(game.radarUsed ? "📡 Radar (weg)" : scanMode == 0 ? "📡 Radar: abbrechen" : "📡 Radar");
        btnSonar.setText(game.sonarUsed ? "🔊 Sonar (weg)" : scanMode == 2 ? "🔊 Sonar: abbrechen" : "🔊 Sonar");
        setEnabled(btnRadar, canAct && !game.radarUsed);
        setEnabled(btnSonar, canAct && !game.sonarUsed);
        setColor(btnRadar, scanMode == 0 ? 0xFFF9A825 : BTN);
        setColor(btnSonar, scanMode == 2 ? 0xFFF9A825 : BTN);
    }

    private void showEnd(boolean won) {
        state = S_END;
        newScreen();
        add(text(won ? "🏆 Gewonnen!" : "💥 Verloren!", 36, won ? 0xFF81C784 : 0xFFE57373, true));
        add(text(won ? "Du hast die komplette Flotte von " + peerName + " versenkt."
                : peerName + " hat deine komplette Flotte versenkt.", 17, Color.WHITE, false));
        add(button("🔁  Nochmal spielen", GREEN, v -> {
            send("AGAIN");
            startNewRound();
        }));
        add(button("Zum Menü", BTN, v -> {
            closeLink();
            showMenu(null);
        }));
    }

    // ---------------------------------------------------------------- Helfer

    private LinearLayout layerTabRow() {
        layerTabs.clear();
        Button[] tabs = new Button[Game.LAYERS];
        for (int i = 0; i < Game.LAYERS; i++) {
            final int layer = i;
            tabs[i] = button("", BTN, v -> {
                viewLayer = layer;
                if (state == S_PLACE) refreshPlace();
                else {
                    if (scanMode >= 0 && scanMode != layer) scanMode = -1;
                    refreshBattle();
                }
            });
            tabs[i].setTextSize(14);
            layerTabs.add(tabs[i]);
        }
        return row(tabs);
    }

    private void newScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(28), dp(14), dp(28));
        sv.addView(root);
        setContentView(sv);
    }

    private void add(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        root.addView(v, lp);
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < views.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(6);
            r.addView(views[i], lp);
        }
        return r;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String s, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(6), dp(12), dp(6), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        return b;
    }

    private void setColor(Button b, int color) {
        if (b.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) b.getBackground()).setColor(color);
        }
    }

    private void setEnabled(Button b, boolean on) {
        b.setEnabled(on);
        b.setAlpha(on ? 1f : 0.4f);
    }

    private void setInfo(String s) {
        if (info != null) info.setText(s);
    }

    private void setStatus(String s) {
        if (status != null) status.setText(s);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
