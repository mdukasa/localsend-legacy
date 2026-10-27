package io.lslegacy;

import android.os.Build;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.SecureRandomSpi;

/**
 * The app's single source of secure randomness. On Android 4.3 and older the platform SecureRandom can
 * be poorly seeded (the 2013 "PRNG fixes" issue), so there we read /dev/urandom directly instead.
 */
public final class Rand {
    private Rand() {}

    private static SecureRandom instance;

    public static synchronized SecureRandom get() {
        if (instance == null) {
            instance = Build.VERSION.SDK_INT <= 18 ? new UrandomRandom() : new SecureRandom();
        }
        return instance;
    }

    /** 128 random bits as hex, for session IDs and upload tokens. */
    public static String id() {
        byte[] b = new byte[16];
        get().nextBytes(b);
        return Util.hex(b).toLowerCase();
    }

    private static final class UrandomRandom extends SecureRandom {
        UrandomRandom() {
            super(new UrandomSpi(), new Provider("LsUrandom", 1.0, "/dev/urandom") {});
        }
    }

    private static final class UrandomSpi extends SecureRandomSpi {
        private DataInputStream in;

        @Override protected void engineSetSeed(byte[] seed) {
            // /dev/urandom is seeded by the kernel; extra seed material isn't needed.
        }

        @Override protected synchronized void engineNextBytes(byte[] bytes) {
            try {
                if (in == null) in = new DataInputStream(new FileInputStream("/dev/urandom"));
                in.readFully(bytes);
            } catch (IOException e) {
                throw new SecurityException("Can't read /dev/urandom", e);
            }
        }

        @Override protected byte[] engineGenerateSeed(int numBytes) {
            byte[] b = new byte[numBytes];
            engineNextBytes(b);
            return b;
        }
    }
}
