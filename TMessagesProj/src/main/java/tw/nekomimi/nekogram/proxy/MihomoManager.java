package tw.nekomimi.nekogram.proxy;

import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Starts the bundled mihomo core and points Telegram's MTProto stack at its local
 * SOCKS5 listener. Everything stays on the device loopback and no VPN permission is
 * involved: Telegram simply talks to 127.0.0.1:7890 like to any other SOCKS5 proxy.
 *
 * <p>The routing table is a fixed minimal config shipped in assets: two US Reality
 * nodes in a fallback group (dedione first, gcp second). No subscription fetch, no
 * rule providers, nothing to keep in sync at runtime.
 */
public final class MihomoManager {

    private static final String TAG = "MihomoManager";
    private static final int SOCKS_PORT = 7890;
    private static final String BINARY_NAME = "mihomo";
    private static final String BUNDLED_LIB = "libmihomo.so";
    private static final String ASSET_CONFIG = "mihomo-config.yaml";

    private static Process process;
    private static volatile boolean ready;

    private MihomoManager() {
    }

    public static boolean isReady() {
        return ready;
    }

    public static void startAsync(Context context) {
        if (ready || process != null) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                start(appContext);
            } catch (Throwable t) {
                FileLog.e(TAG + ": start failed");
                FileLog.e(t);
                disableTelegramProxy();
            }
        }, "mihomo-start").start();
    }

    private static void start(Context context) throws Exception {
        final File workDir = new File(context.getFilesDir(), "mihomo");
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw new IOException("cannot create " + workDir);
        }

        final File bundled = new File(context.getApplicationInfo().nativeLibraryDir, BUNDLED_LIB);
        if (!bundled.exists()) {
            FileLog.e(TAG + ": bundled core missing at " + bundled.getAbsolutePath());
            disableTelegramProxy();
            return;
        }

        final File binary = new File(workDir, BINARY_NAME);
        if (!binary.exists() || binary.length() != bundled.length()) {
            copyFile(bundled, binary);
        }
        if (!binary.canExecute()) {
            //noinspection ResultOfMethodCallIgnored
            binary.setExecutable(true, false);
        }

        // Always overwrite: the routing table is part of the APK, not user state.
        final File config = new File(workDir, "config.yaml");
        copyAsset(context, ASSET_CONFIG, config);

        final ProcessBuilder builder = new ProcessBuilder(
                binary.getAbsolutePath(),
                "-d", workDir.getAbsolutePath(),
                "-f", config.getAbsolutePath());
        builder.redirectErrorStream(true);
        builder.redirectOutput(new File(workDir, "core.log"));
        process = builder.start();

        if (!waitForPort(45_000)) {
            FileLog.e(TAG + ": core did not open SOCKS5 port " + SOCKS_PORT);
            disableTelegramProxy();
            return;
        }

        ready = true;
        applyTelegramProxy();
    }

    private static boolean waitForPort(int timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", SOCKS_PORT), 400);
                return true;
            } catch (IOException ignored) {
                // core still booting
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void applyTelegramProxy() {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                final ProxySettings settings = ProxySettings.builder()
                        .setType(ProxySettings.Type.SOCKS5)
                        .setAddress("127.0.0.1")
                        .setPort(SOCKS_PORT)
                        .build();
                ConnectionsManager.setProxySettings(true, settings);
                FileLog.d(TAG + ": Telegram routed through local SOCKS5");
            } catch (Throwable t) {
                FileLog.e(t);
            }
        });
    }

    private static void disableTelegramProxy() {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                ConnectionsManager.setProxySettings(false, ProxySettings.EMPTY);
            } catch (Throwable t) {
                FileLog.e(t);
            }
        });
    }

    private static void copyFile(File source, File target) throws IOException {
        try (InputStream in = new java.io.FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static void copyAsset(Context context, String name, File target) throws IOException {
        try (InputStream in = context.getAssets().open(name);
             OutputStream out = new FileOutputStream(target)) {
            final byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }
}
