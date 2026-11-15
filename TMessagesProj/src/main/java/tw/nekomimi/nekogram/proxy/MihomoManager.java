package tw.nekomimi.nekogram.proxy;

import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Starts a bundled mihomo core and points Telegram's MTProto stack at its local
 * SOCKS5 listener. Nothing leaves the device loopback, and no VPN permission is
 * involved: Telegram just talks to 127.0.0.1:7890 like to any other SOCKS5 proxy.
 *
 * <p>The routing config is refreshed from the subscription on every launch; when
 * that fails the last cached copy is reused, and the copy shipped in assets is the
 * first-run seed, so the app never hard-depends on the subscription being reachable.
 */
public final class MihomoManager {

    private static final String TAG = "MihomoManager";
    private static final int SOCKS_PORT = 7890;
    private static final String BINARY_NAME = "mihomo";
    private static final String BUNDLED_LIB = "libmihomo.so";
    private static final String ASSET_CONFIG = "mihomo-config.yaml";
    private static final String SUBSCRIPTION_URL = "https://sub.eaedward.dpdns.org/sub.yaml";

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

        final File config = new File(workDir, "config.yaml");
        prepareConfig(context, config);

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

    /**
     * Refreshes the routing config from the subscription. Falls back to the last
     * cached file, then to the copy bundled in assets.
     */
    private static void prepareConfig(Context context, File target) {
        final String yaml = download(SUBSCRIPTION_URL);
        if (yaml != null) {
            try {
                writeText(target, sanitize(yaml));
                return;
            } catch (IOException e) {
                FileLog.e(e);
            }
        }
        if (target.exists() && target.length() > 1024) {
            FileLog.d(TAG + ": subscription unreachable, reusing cached config");
            return;
        }
        try {
            copyAsset(context, ASSET_CONFIG, target);
            FileLog.d(TAG + ": using bundled seed config");
        } catch (IOException e) {
            FileLog.e(e);
        }
    }

    /**
     * The subscription targets a desktop/router deployment, so two keys must be
     * forced for an in-app listener: no LAN exposure and no TUN (there is no VPN
     * permission here, so TUN would just fail and kill the core).
     */
    private static String sanitize(String yaml) {
        final String[] lines = yaml.split("\n", -1);
        final StringBuilder sb = new StringBuilder(yaml.length() + 64);
        boolean inTun = false;
        boolean bindAdded = false;
        for (String line : lines) {
            final String trimmed = line.trim();
            if (trimmed.startsWith("allow-lan:")) {
                sb.append("allow-lan: false\n");
                continue;
            }
            if ("tun:".equals(trimmed)) {
                inTun = true;
            } else if (inTun && !line.isEmpty() && !line.startsWith(" ")) {
                inTun = false;
            }
            if (inTun && "enable: true".equals(trimmed)) {
                final int indentLen = line.length() - line.replaceAll("^\\s+", "").length();
                sb.append(line, 0, indentLen).append("enable: false\n");
                continue;
            }
            sb.append(line).append('\n');
            if (!bindAdded && trimmed.startsWith("mixed-port:")) {
                sb.append("bind-address: 127.0.0.1\n");
                bindAdded = true;
            }
        }
        if (!bindAdded) {
            sb.insert(0, "bind-address: 127.0.0.1\n");
        }
        return sb.toString();
    }

    private static String download(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("User-Agent", "nekogram-mihomo");
            if (connection.getResponseCode() != 200) {
                FileLog.e(TAG + ": subscription HTTP " + connection.getResponseCode());
                return null;
            }
            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024)) {
                final byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                final String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
                return text.length() > 512 ? text : null;
            }
        } catch (Throwable t) {
            FileLog.e(TAG + ": subscription fetch failed: " + t);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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

    private static void writeText(File target, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(target)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
