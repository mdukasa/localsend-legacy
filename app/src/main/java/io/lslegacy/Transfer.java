package io.lslegacy;

import android.os.SystemClock;

/**
 * One transfer in progress, in either direction, as shown on the main screen's card. The service owns it,
 * so the card comes back as it was when the user leaves the app and returns.
 */
final class Transfer {
    final boolean sending;
    final String peer;
    volatile String file;
    volatile int fileIndex;
    volatile int fileCount;
    volatile long done;
    volatile long total;
    /** Smoothed bytes per second; 0 until there's been a second of data. */
    private long speed;

    private long sampleAt;
    private long sampleBytes;

    Transfer(boolean sending, String peer) {
        this.sending = sending;
        this.peer = peer;
    }

    /** True once the receiver has accepted and bytes can flow. */
    boolean started() {
        return total > 0 && file != null;
    }

    int percent() {
        return total > 0 ? (int) (done * 100 / total) : 0;
    }

    /**
     * Bytes per second, or 0 if not known yet. It falls while nothing moves, so a stalled transfer doesn't
     * keep showing the time left from before it stalled.
     */
    synchronized long speed() {
        long idle = SystemClock.elapsedRealtime() - sampleAt;
        if (sampleAt == 0 || idle < 2000) return speed;
        return Math.min(speed, (done - sampleBytes) * 1000 / idle);
    }

    /** Seconds left at the current speed, or -1 when there's no speed. */
    long secondsLeft() {
        long s = speed();
        return s > 0 ? (total - done + s - 1) / s : -1;
    }

    synchronized void progress(long bytes) {
        done = bytes;
        long now = SystemClock.elapsedRealtime();
        if (sampleAt == 0) {
            sampleAt = now;
            sampleBytes = bytes;
            return;
        }
        long dt = now - sampleAt;
        if (dt < 1000) return;
        long current = (bytes - sampleBytes) * 1000 / dt;
        // Smooth over a few seconds so the time left doesn't jump about on a bursty Wi-Fi link.
        speed = speed == 0 ? current : (speed * 2 + current) / 3;
        sampleAt = now;
        sampleBytes = bytes;
    }
}
