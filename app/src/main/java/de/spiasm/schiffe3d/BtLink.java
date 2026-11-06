package de.spiasm.schiffe3d;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bluetooth-Verbindung zwischen zwei Handys. Nachrichten sind einfache Textzeilen. */
@SuppressLint("MissingPermission")
public class BtLink {
    public interface Listener {
        void onConnected(String peerName);

        void onMessage(String line);

        void onClosed(String reason);
    }

    private static final UUID ID = UUID.fromString("7a1f3c52-9d4e-4b8a-a3c1-5e2f8b6d0c91");

    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private volatile BluetoothServerSocket server;
    private volatile BluetoothSocket socket;
    private volatile OutputStream out;
    private volatile boolean closed;

    public BtLink(Listener listener) {
        this.listener = listener;
    }

    public void host(final BluetoothAdapter adapter) {
        new Thread(() -> {
            BluetoothSocket s;
            try {
                server = adapter.listenUsingRfcommWithServiceRecord("Schiffe3D", ID);
                s = server.accept();
                quietClose(server);
            } catch (IOException | SecurityException e) {
                fail("Warten abgebrochen.");
                return;
            }
            run(s);
        }).start();
    }

    public void join(final BluetoothDevice device, final BluetoothAdapter adapter) {
        new Thread(() -> {
            BluetoothSocket s;
            try {
                try {
                    adapter.cancelDiscovery();
                } catch (SecurityException ignored) {
                }
                s = device.createRfcommSocketToServiceRecord(ID);
                s.connect();
            } catch (IOException | SecurityException e) {
                fail("Verbindung hat nicht geklappt. Hat dein Kumpel schon „Spiel erstellen“ getippt?");
                return;
            }
            run(s);
        }).start();
    }

    private void run(BluetoothSocket s) {
        socket = s;
        if (closed) {
            quietClose(s);
            return;
        }
        try {
            out = s.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String name;
            try {
                name = s.getRemoteDevice().getName();
            } catch (SecurityException e) {
                name = null;
            }
            final String peer = name == null ? "Gegner" : name;
            main.post(() -> {
                if (!closed) listener.onConnected(peer);
            });
            String line;
            while ((line = in.readLine()) != null) {
                final String msg = line.trim();
                main.post(() -> {
                    if (!closed) listener.onMessage(msg);
                });
            }
        } catch (IOException ignored) {
        }
        fail("Die Verbindung wurde getrennt.");
    }

    public void send(final String msg) {
        if (closed) return;
        try {
            writer.execute(() -> {
                OutputStream o = out;
                if (o == null || closed) return;
                try {
                    o.write((msg + "\n").getBytes(StandardCharsets.UTF_8));
                    o.flush();
                } catch (IOException e) {
                    fail("Die Verbindung wurde getrennt.");
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private void fail(final String reason) {
        if (closed) return;
        closed = true;
        quietClose(server);
        quietClose(socket);
        writer.shutdown();
        main.post(() -> listener.onClosed(reason));
    }

    public void close() {
        closed = true;
        quietClose(server);
        quietClose(socket);
        writer.shutdown();
    }

    private static void quietClose(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
