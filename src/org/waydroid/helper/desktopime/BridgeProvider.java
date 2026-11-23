package org.waydroid.helper.desktopime;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Two private Binder pipes connect the selected Android IME to the desktop's ADB shell. */
public final class BridgeProvider extends ContentProvider {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService OUTPUT = Executors.newSingleThreadExecutor();
    // Binder threads establish pipes. Editor state and all InputConnection calls
    // belong to the IME main thread. A replaced pipe cannot edit the new session.
    private static volatile BridgeProvider current;
    private volatile OutputStream events;
    private ParcelFileDescriptor eventPipe;
    private String token;
    private boolean hasCommands;

    @Override public boolean onCreate() { current = this; return true; }

    @Override public synchronized ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        int uid = Binder.getCallingUid();
        if (uid != 2000 && uid != 0) throw new SecurityException("ADB shell required");
        List<String> path = uri.getPathSegments();
        if (path.size() != 2 || !path.get(0).matches("[0-9a-f]{32}"))
            throw new FileNotFoundException("Invalid bridge URI");
        String id = path.get(0);
        try {
            if (path.get(1).equals("events") && mode.equals("r")) {
                // Do not steal an active window's editor or its IME selection
                // lease. poll(0) distinguishes a live reader from a dead ADB peer.
                if (readerAlive()) {
                    return openPipeHelper(uri, getType(uri), null, null,
                            (fd, ignoredUri, mime, opts, arg) -> {
                                try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(fd)) {
                                    out.write("{\"event\":\"busy\"}\n".getBytes(StandardCharsets.UTF_8));
                                } catch (IOException ignored) {}
                            });
                }
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                disconnect();
                eventPipe = pipe[1];
                token = id;
                events = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
                send(new JSONObject().put("event", "ready"));
                return pipe[0];
            }
            if (path.get(1).equals("commands") && mode.startsWith("w")
                    && id.equals(token) && !hasCommands) {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                hasCommands = true;
                // The shell selects the IME before opening this pipe. Acknowledge
                // that the shell owns both ends before the desktop saves opt-in.
                send(new JSONObject().put("event", "connected"));
                MAIN.post(() -> {
                    if (id.equals(currentToken()) && HostIme.instance != null)
                        HostIme.instance.connected();
                });
                new Thread(() -> readCommands(id, pipe[0]), "host-ime-commands").start();
                return pipe[1];
            }
        } catch (IOException | org.json.JSONException error) {
            throw new FileNotFoundException(error.toString());
        }
        throw new FileNotFoundException("Unknown bridge session");
    }

    private boolean readerAlive() {
        if (eventPipe == null) return false;
        StructPollfd probe = new StructPollfd();
        probe.fd = eventPipe.getFileDescriptor();
        try {
            Os.poll(new StructPollfd[]{probe}, 0);
            return (probe.revents & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) == 0;
        } catch (ErrnoException error) { return false; }
    }

    private synchronized String currentToken() { return token; }

    private void readCommands(String id, ParcelFileDescriptor pipe) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ParcelFileDescriptor.AutoCloseInputStream(pipe), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final JSONObject command = new JSONObject(line);
                MAIN.post(() -> {
                    if (id.equals(currentToken()) && HostIme.instance != null)
                        HostIme.instance.apply(command);
                });
            }
        } catch (IOException | org.json.JSONException ignored) {
            // EOF and malformed input both end this transport without logging text.
        } finally {
            synchronized (this) { if (id.equals(token)) disconnect(); }
        }
    }

    private synchronized void disconnect() {
        OutputStream old = events;
        events = null;
        eventPipe = null;
        token = null;
        hasCommands = false;
        if (old != null) {
            try { old.close(); } catch (IOException ignored) {}
            MAIN.post(() -> { if (HostIme.instance != null) HostIme.instance.cancelComposition(); });
        }
    }

    static void send(JSONObject message) {
        BridgeProvider provider = current;
        if (provider == null) return;
        OutputStream dest = provider.events;
        if (dest == null) return;
        byte[] bytes = (message.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        OUTPUT.execute(() -> {
            if (provider.events != dest) return;
            try { dest.write(bytes); }
            catch (IOException closed) {
                synchronized (provider) { if (provider.events == dest) provider.disconnect(); }
            }
        });
    }

    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
