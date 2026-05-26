package it.belloworld.mercurygram.plugin.tor;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

// MG: reads tor's control port event stream to surface bootstrap progress.
//
// Protocol (control-spec.txt): connect, AUTHENTICATE (with the hex-encoded
// auth cookie tor writes to <DataDirectory>/control_auth_cookie when the
// control port binds — empty arg falls back to OPEN auth for tests), then
// SETEVENTS STATUS_CLIENT, then a GETINFO status/bootstrap-phase to seed
// the current progress (necessary on a warm start where tor crosses
// PROGRESS=100 before our SETEVENTS subscription and never replays the
// event), then read async lines prefixed with "650".
//
// Bootstrap status example:
//   650 STATUS_CLIENT NOTICE BOOTSTRAP PROGRESS=85 TAG=ap_handshake SUMMARY="..."
final class MgTorBootstrap implements Runnable {

    interface Listener {
        void onBootstrapProgress(int percent, String tag, String summary);
        void onBootstrapReady();
        void onBootstrapFailed(String reason);
    }

    // Happy path: tor writes the 32-byte cookie within ~100 ms of binding the
    // control port. The budget only matters under memory pressure / heavy disk
    // contention where the libc write can lag several seconds. 15 s headroom
    // covers slow devices without leaving the bootstrap dialog visibly hung.
    private static final long COOKIE_WAIT_BUDGET_MS = 15_000L;
    private static final long COOKIE_POLL_INTERVAL_MS = 100L;
    // STATUS_CLIENT events fire frequently during bootstrap (handshake,
    // descriptor fetch, circuit build — typically every few seconds). 60 s of
    // total silence on the control port means tor is wedged (libevent loop
    // deadlock, netfilter drop, etc.) and the bootstrap will never complete.
    // SO_TIMEOUT lets the read loop unblock and surface onBootstrapFailed
    // instead of pinning the bootstrap thread forever against a dead daemon.
    private static final int  READ_TIMEOUT_MS = 60_000;

    private final int controlPort;
    private final File cookieFile;
    private final Listener listener;
    private volatile Socket socket;
    private volatile boolean cancelled;

    MgTorBootstrap(int controlPort, File cookieFile, Listener listener) {
        this.controlPort = controlPort;
        this.cookieFile = cookieFile;
        this.listener = listener;
    }

    void cancel() {
        cancelled = true;
        Socket s = socket;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    @Override
    public void run() {
        try {
            Socket s = new Socket();
            // Publish the socket BEFORE the (potentially up-to-5 s) connect
            // so a cancel() racing the dial closes the unconnected socket
            // and interrupts connect() with SocketException. Without this,
            // cancel() during connect() reads socket=null, closes nothing,
            // and the bootstrap thread runs to completion against a daemon
            // the caller has already decided to abandon.
            socket = s;
            s.connect(new InetSocketAddress("127.0.0.1", controlPort), 5_000);
            // Bound subsequent reads so a wedged daemon eventually surfaces
            // as onBootstrapFailed via SocketTimeoutException rather than
            // pinning this thread forever. See READ_TIMEOUT_MS rationale.
            s.setSoTimeout(READ_TIMEOUT_MS);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = s.getOutputStream();
            String cookieHex = readCookieHex(cookieFile);
            writeCommand(out, cookieHex.isEmpty()
                    ? "AUTHENTICATE\r\n"
                    : "AUTHENTICATE " + cookieHex + "\r\n");
            String resp = in.readLine();
            if (resp == null || !resp.startsWith("250")) {
                listener.onBootstrapFailed("auth: " + resp);
                return;
            }
            writeCommand(out, "SETEVENTS STATUS_CLIENT\r\n");
            resp = in.readLine();
            if (resp == null || !resp.startsWith("250")) {
                listener.onBootstrapFailed("setevents: " + resp);
                return;
            }
            // Warm-start probe: tor may have already crossed PROGRESS=100
            // before SETEVENTS subscribed. STATUS_CLIENT events are not
            // replayed for late subscribers, so the event-loop readLine()
            // would block forever. Seed the state via GETINFO.
            writeCommand(out, "GETINFO status/bootstrap-phase\r\n");
            int seedProgress = readBootstrapPhaseReply(in);
            if (seedProgress >= 100) {
                listener.onBootstrapReady();
                return;
            }
            String line;
            while (!cancelled && (line = in.readLine()) != null) {
                if (!line.startsWith("650 STATUS_CLIENT") || !line.contains("BOOTSTRAP ")) {
                    continue;
                }
                int progress = parseIntField(line, "PROGRESS=", -1);
                String tag = parseBareField(line, "TAG=");
                String summary = parseQuotedField(line, "SUMMARY=");
                if (progress >= 0) {
                    listener.onBootstrapProgress(progress, tag, summary);
                    if (progress >= 100) {
                        listener.onBootstrapReady();
                        return;
                    }
                }
            }
        } catch (IOException e) {
            if (!cancelled) {
                Log.e("MgTor", "bootstrap I/O error", e);
                listener.onBootstrapFailed(e.getMessage());
            }
        } finally {
            Socket s = socket;
            if (s != null) {
                try { s.close(); } catch (IOException ignored) {}
            }
            socket = null;
        }
    }

    // Reads a multi-line GETINFO reply terminated by "250 OK" (or any 250
    // line lacking the '-' continuation marker). Returns the PROGRESS=
    // value of the status/bootstrap-phase entry, or -1 if absent / error
    // reply (4xx/5xx).
    private static int readBootstrapPhaseReply(BufferedReader in) throws IOException {
        int progress = -1;
        String line;
        while ((line = in.readLine()) != null) {
            if (line.startsWith("250-status/bootstrap-phase=")) {
                String phase = line.substring("250-status/bootstrap-phase=".length());
                progress = parseIntField(phase, "PROGRESS=", progress);
            } else if (line.startsWith("250 ") || line.startsWith("250+")) {
                return progress;
            } else if (line.length() >= 3 && (line.charAt(0) == '4' || line.charAt(0) == '5')) {
                return -1;
            }
        }
        return progress;
    }

    private static String readCookieHex(File cookieFile) {
        if (cookieFile == null) return "";
        long deadline = System.currentTimeMillis() + COOKIE_WAIT_BUDGET_MS;
        while (true) {
            if (cookieFile.length() >= 32) {
                try (FileInputStream fis = new FileInputStream(cookieFile)) {
                    byte[] buf = new byte[32];
                    int read = 0;
                    while (read < buf.length) {
                        int n = fis.read(buf, read, buf.length - read);
                        if (n < 0) break;
                        read += n;
                    }
                    if (read == buf.length) return toHex(buf);
                } catch (IOException ignored) {}
            }
            if (System.currentTimeMillis() >= deadline) return "";
            try { Thread.sleep(COOKIE_POLL_INTERVAL_MS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return ""; }
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static void writeCommand(OutputStream out, String cmd) throws IOException {
        out.write(cmd.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static int parseIntField(String line, String key, int dflt) {
        int i = line.indexOf(key);
        if (i < 0) return dflt;
        i += key.length();
        int j = i;
        while (j < line.length() && Character.isDigit(line.charAt(j))) j++;
        if (j == i) return dflt;
        try { return Integer.parseInt(line.substring(i, j)); }
        catch (NumberFormatException e) { return dflt; }
    }

    private static String parseBareField(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) return "";
        i += key.length();
        int j = line.indexOf(' ', i);
        return j < 0 ? line.substring(i) : line.substring(i, j);
    }

    // Tor control-spec QuotedString: closing '"' is the first unescaped one.
    // Backslash escapes the next char (including '"' and '\\'). Unescaping
    // is done char-by-char so a SUMMARY containing C-escapes round-trips
    // cleanly instead of truncating at the first inner '"'.
    private static String parseQuotedField(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) return "";
        i += key.length();
        if (i >= line.length() || line.charAt(i) != '"') return "";
        StringBuilder out = new StringBuilder();
        int n = line.length();
        for (int k = i + 1; k < n; k++) {
            char c = line.charAt(k);
            if (c == '\\' && k + 1 < n) {
                out.append(line.charAt(k + 1));
                k++;
                continue;
            }
            if (c == '"') return out.toString();
            out.append(c);
        }
        return ""; // unterminated quote — control-spec violation; drop quietly.
    }
}
