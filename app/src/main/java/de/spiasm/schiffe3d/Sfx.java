package de.spiasm.schiffe3d;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import java.util.Random;

/** Soundeffekte – werden beim Start im Programm selbst erzeugt, keine Sounddateien nötig. */
public class Sfx {
    public static final int SHOT = 0, SPLASH = 1, BOOM = 2, BIG = 3, PING = 4, WIN = 5, LOSE = 6;
    private static final int RATE = 22050;

    private volatile short[][] data;
    private final Handler handler = new Handler(Looper.getMainLooper());
    public boolean enabled = true;

    public Sfx() {
        new Thread(this::build).start();
    }

    public void play(int id) {
        play(id, 0);
    }

    public void play(final int id, long delayMs) {
        if (!enabled) return;
        if (delayMs > 0) {
            handler.postDelayed(() -> play(id, 0), delayMs);
            return;
        }
        short[][] all = data;
        if (all == null || all[id] == null) return;
        short[] d = all[id];
        try {
            final AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(d.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            t.write(d, 0, d.length);
            t.play();
            handler.postDelayed(() -> {
                try {
                    t.stop();
                } catch (Exception ignored) {
                }
                t.release();
            }, d.length * 1000L / RATE + 400);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ Erzeugung

    private void build() {
        Random rnd = new Random(7);
        short[][] d = new short[7][];
        d[SHOT] = pcm(shot(rnd));
        d[SPLASH] = pcm(splash(rnd));
        d[BOOM] = pcm(boom(rnd, 0.9f, 0.06f, 4.5f));
        d[BIG] = pcm(bigBoom(rnd));
        d[PING] = pcm(ping());
        d[WIN] = pcm(melody(new float[]{523.25f, 659.25f, 783.99f, 1046.5f, 783.99f, 1046.5f},
                new float[]{0.13f, 0.13f, 0.13f, 0.3f, 0.12f, 0.5f}));
        d[LOSE] = pcm(melody(new float[]{392f, 369.99f, 349.23f, 293.66f},
                new float[]{0.3f, 0.3f, 0.3f, 0.8f}));
        data = d;
    }

    private static float[] shot(Random rnd) {
        int n = (int) (RATE * 0.4f);
        float[] f = new float[n];
        float y = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float p = t / 0.4f;
            float a = 0.03f + 0.3f * p;
            y += a * ((rnd.nextFloat() * 2 - 1) - y);
            float env = (float) Math.pow(Math.sin(Math.PI * p), 2);
            float thump = (float) (Math.sin(2 * Math.PI * 110 * t) * Math.exp(-t * 35));
            f[i] = y * env * 1.2f + thump * 0.8f;
        }
        return f;
    }

    private static float[] splash(Random rnd) {
        int n = (int) (RATE * 0.7f);
        float[] f = new float[n];
        float y = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            y += 0.35f * ((rnd.nextFloat() * 2 - 1) - y);
            float env = (float) ((1 - Math.exp(-t * 60)) * Math.exp(-t * 6));
            float bubble = (float) (Math.sin(2 * Math.PI * (500 + 900 * t) * t) * Math.exp(-(t - 0.25f) * (t - 0.25f) * 200) * 0.3);
            f[i] = y * env + bubble;
        }
        return f;
    }

    private static float[] boom(Random rnd, float sec, float lp, float decay) {
        int n = (int) (RATE * sec);
        float[] f = new float[n];
        float y = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float noise = rnd.nextFloat() * 2 - 1;
            y += lp * (noise - y);
            float env = (float) Math.exp(-t * decay);
            float crack = (float) (noise * Math.exp(-t * 90) * 0.4);
            float rumble = (float) (Math.sin(2 * Math.PI * (70 - 30 * t) * t) * Math.exp(-t * 5) * 0.5);
            f[i] = y * 4f * env + crack + rumble;
        }
        return f;
    }

    private static float[] bigBoom(Random rnd) {
        float[] a = boom(rnd, 1.6f, 0.045f, 2.6f);
        float[] b = boom(rnd, 1.2f, 0.07f, 3.5f);
        int off = (int) (RATE * 0.28f);
        for (int i = 0; i < b.length && i + off < a.length; i++) a[i + off] += b[i] * 0.8f;
        return a;
    }

    private static float[] ping() {
        int n = (int) (RATE * 1.0f);
        float[] f = new float[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            double v = Math.sin(2 * Math.PI * 1320 * t) * Math.exp(-t * 7);
            if (t > 0.32f) v += 0.35 * Math.sin(2 * Math.PI * 1320 * t) * Math.exp(-(t - 0.32f) * 7);
            f[i] = (float) v;
        }
        return f;
    }

    private static float[] melody(float[] freqs, float[] durs) {
        float total = 0;
        for (float d : durs) total += d;
        int n = (int) (RATE * (total + 0.3f));
        float[] f = new float[n];
        float start = 0;
        for (int k = 0; k < freqs.length; k++) {
            int s0 = (int) (start * RATE);
            int len = (int) ((durs[k] + 0.25f) * RATE);
            for (int i = 0; i < len && s0 + i < n; i++) {
                float t = i / (float) RATE;
                float env = (float) (Math.min(1, t * 200) * Math.exp(-t * 3.5));
                double w = Math.sin(2 * Math.PI * freqs[k] * t) + 0.3 * Math.sin(4 * Math.PI * freqs[k] * t)
                        + 0.12 * Math.sin(6 * Math.PI * freqs[k] * t);
                f[s0 + i] += (float) (w * env);
            }
            start += durs[k];
        }
        return f;
    }

    private static short[] pcm(float[] f) {
        float max = 0;
        for (float v : f) max = Math.max(max, Math.abs(v));
        float g = max > 0 ? 0.85f / max : 0;
        short[] s = new short[f.length];
        for (int i = 0; i < f.length; i++) s[i] = (short) (f[i] * g * 32767);
        return s;
    }
}
