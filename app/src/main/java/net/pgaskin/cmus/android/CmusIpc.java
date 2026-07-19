package net.pgaskin.cmus.android;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.JsonReader;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for the cmus app IPC socket (patches/cmus/0001; the protocol
 * comment atop android.c is the contract). Parses the newline-delimited
 * JSON event stream into typed {@link Event}s delivered to listeners on the
 * main thread, sends raw cmus command lines, and reconnects on its own.
 * Every event is complete-state and a (re)connect always starts with a full
 * snapshot, so no state carries across connections and reconnecting is the
 * fix for everything — including another client stealing the single slot
 * (new-connection-wins on the cmus side).
 */
public final class CmusIpc implements AutoCloseable {
    private static final String TAG = "cmus";

    public sealed interface Event permits Hello, Status, Position, Volume, View, Options {
    }

    /** First line after connect. */
    public record Hello(String version) implements Event {
    }

    /**
     * Playback status/track/metadata; on connect and on change. Without a
     * current track, file is null, duration/position -1, tags empty. Tag
     * keys are as stored in the file, lowercased by cmus, and may repeat
     * (e.g. multiple artists). Position is fractional seconds.
     */
    public record Status(PlayState state, String file, int duration, double position,
            Map<String, List<String>> tags) implements Event {
    }

    /**
     * Position advance/seek, sent when the whole second changes (&le; 1/s
     * while playing) but carrying the exact fractional position so
     * animating between events doesn't jump on rebase; only sent when no
     * Status goes out in the same flush (Status carries position too).
     */
    public record Position(double position) implements Event {
    }

    /** Percent; -1 = unknown. On connect and on change. */
    public record Volume(int left, int right) implements Event {
    }

    /**
     * The active view by cmus name (tree, sorted, playlist, queue, browser,
     * filters, settings; treat others as unknown-but-valid). On connect and
     * on change — the 1-7 keys, the `view` command, and resume all funnel
     * through it.
     */
    public record View(String name) implements Event {
    }

    /**
     * Every cmus option as the string its getter returns (includes all
     * color_*); a full replacement on connect and after any executed
     * command, coalesced per cmus main-loop iteration.
     */
    public record Options(Map<String, String> values) implements Event {
    }

    public enum PlayState { STOPPED, PLAYING, PAUSED }

    /** All callbacks run on the main thread. */
    public interface Listener {
        void onEvent(Event event);

        default void onConnected() {
        }

        default void onDisconnected() {
        }
    }

    private final File socketPath;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread writeThread;
    private final Handler writeHandler;
    private final Thread readThread;

    private final Object lock = new Object();
    private volatile boolean closed; // writes guarded by lock
    private LocalSocket socket; // guarded by lock

    // main thread only
    private final List<Listener> listeners = new ArrayList<>();
    private boolean connected;

    // last known state; written on the main thread just before dispatch,
    // volatile so the getters work from any thread
    private volatile Status status;
    private volatile Volume volume;
    private volatile View view;
    private volatile Options options;
    private volatile String version;

    /** Starts connecting (and retrying) immediately. */
    public CmusIpc(File socketPath) {
        this.socketPath = socketPath;
        writeThread = new HandlerThread("CmusIpcWrite");
        writeThread.start();
        writeHandler = new Handler(writeThread.getLooper());
        readThread = new Thread(this::readLoop, "CmusIpcRead");
        readThread.setDaemon(true);
        readThread.start();
    }

    /** Replays the connection state and cached Status/Volume/Options. */
    public void addListener(Listener listener) {
        mainHandler.post(() -> {
            if (closed) {
                return;
            }
            listeners.add(listener);
            if (connected) {
                listener.onConnected();
            }
            if (status != null) {
                listener.onEvent(status);
            }
            if (volume != null) {
                listener.onEvent(volume);
            }
            if (view != null) {
                listener.onEvent(view);
            }
            if (options != null) {
                listener.onEvent(options);
            }
        });
    }

    public void removeListener(Listener listener) {
        mainHandler.post(() -> listeners.remove(listener));
    }

    /** Last known playback state; null before the first event. */
    public Status status() {
        return status;
    }

    /** Last known volume; null before the first event. */
    public Volume volume() {
        return volume;
    }

    /** Last known view; null before the first event. */
    public View view() {
        return view;
    }

    /** Last known options; null before the first event. */
    public Options options() {
        return options;
    }

    /**
     * Sends one raw cmus command line ("player-pause", "seek +5", ...,
     * including the "/" and "?" search prefixes) from any thread. No acks;
     * errors show in the TUI and state changes come back as events. Dropped
     * with a log when disconnected — a queued command against a dead cmus
     * is meaningless. Throws on embedded newlines and overlong commands
     * (android.c drops the client on lines its 4096-byte buffer can't
     * hold), which are caller bugs, not runtime conditions.
     */
    public void send(String command) {
        if (command.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("command contains a newline");
        }
        byte[] line = (command + "\n").getBytes(StandardCharsets.UTF_8);
        if (line.length > 4095) {
            throw new IllegalArgumentException("command too long (" + line.length + " bytes)");
        }
        writeHandler.post(() -> {
            LocalSocket s;
            synchronized (lock) {
                s = socket;
            }
            if (s == null) {
                Log.w(TAG, "ipc: send dropped, not connected: " + command);
                return;
            }
            try {
                s.getOutputStream().write(line);
            } catch (IOException e) {
                // the reader notices the dead connection and reconnects
                Log.w(TAG, "ipc: send failed: " + e);
            }
        });
    }

    /** Stops reconnecting and closes the connection; idempotent. */
    @Override
    public void close() {
        LocalSocket s;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            s = socket;
            socket = null;
        }
        if (s != null) {
            // plain close doesn't reliably unblock a LocalSocket read
            try {
                s.shutdownInput();
            } catch (IOException ignored) {
            }
            try {
                s.shutdownOutput();
            } catch (IOException ignored) {
            }
            closeQuietly(s);
        }
        readThread.interrupt(); // unblock a retry sleep
        writeThread.quitSafely();
    }

    private void readLoop() {
        // 100ms attempts while cmus may still be binding the socket after
        // spawn (~10s budget, like the old forceMouseOption poll), then 1s
        // forever until close(); reset per disconnect so recovery from a
        // stolen connection is quick too
        long phaseStart = SystemClock.uptimeMillis();
        while (!closed) {
            LocalSocket s = new LocalSocket();
            try {
                s.connect(new LocalSocketAddress(socketPath.getPath(),
                        LocalSocketAddress.Namespace.FILESYSTEM));
            } catch (IOException e) {
                closeQuietly(s);
                try {
                    Thread.sleep(SystemClock.uptimeMillis() - phaseStart < 10_000 ? 100 : 1000);
                } catch (InterruptedException ie) {
                    return;
                }
                continue;
            }
            synchronized (lock) {
                if (closed) {
                    closeQuietly(s);
                    return;
                }
                socket = s;
            }
            mainHandler.post(this::dispatchConnected);
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(
                        s.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = in.readLine()) != null) {
                    Event event;
                    try {
                        event = parseLine(line);
                    } catch (IOException | RuntimeException e) {
                        // a malformed line is an android.c bug, not the
                        // network; a fresh snapshot beats silently skewed
                        // state
                        Log.e(TAG, "ipc: malformed event, reconnecting: " + line, e);
                        break;
                    }
                    if (event != null) {
                        mainHandler.post(() -> dispatchEvent(event));
                    }
                }
            } catch (IOException e) {
                if (!closed) {
                    Log.w(TAG, "ipc: connection lost: " + e);
                }
            }
            synchronized (lock) {
                socket = null;
            }
            closeQuietly(s);
            mainHandler.post(this::dispatchDisconnected);
            phaseStart = SystemClock.uptimeMillis();
        }
    }

    // main thread

    private void dispatchConnected() {
        if (closed) {
            return;
        }
        connected = true;
        for (Listener l : List.copyOf(listeners)) {
            l.onConnected();
        }
    }

    private void dispatchDisconnected() {
        if (closed) {
            return;
        }
        connected = false;
        for (Listener l : List.copyOf(listeners)) {
            l.onDisconnected();
        }
    }

    private void dispatchEvent(Event event) {
        if (closed) {
            return;
        }
        switch (event) {
            case Hello h -> version = h.version();
            case Status s -> status = s;
            case Volume v -> volume = v;
            case View v -> view = v;
            case Options o -> options = o;
            case Position ignored -> {
            }
        }
        for (Listener l : List.copyOf(listeners)) {
            l.onEvent(event);
        }
    }

    // parsing (reader thread)

    /** Returns null for unknown event types (forward compatibility). */
    private static Event parseLine(String line) throws IOException {
        String type = null;
        String version = null;
        String state = null;
        String file = null;
        String viewName = null;
        int duration = -1;
        double position = -1;
        int left = -1;
        int right = -1;
        Map<String, List<String>> tags = Map.of();
        Map<String, String> options = Map.of();
        try (JsonReader r = new JsonReader(new StringReader(line))) {
            r.beginObject();
            while (r.hasNext()) {
                switch (r.nextName()) {
                    case "type" -> type = r.nextString();
                    case "version" -> version = r.nextString();
                    case "status" -> state = r.nextString();
                    case "file" -> file = r.nextString();
                    case "duration" -> duration = r.nextInt();
                    case "position" -> position = r.nextDouble();
                    case "left" -> left = r.nextInt();
                    case "right" -> right = r.nextInt();
                    case "view" -> viewName = r.nextString();
                    case "tags" -> tags = readTags(r);
                    case "options" -> options = readOptions(r);
                    default -> r.skipValue();
                }
            }
            r.endObject();
        }
        return switch (type) {
            case "hello" -> new Hello(version);
            case "status" -> new Status(parseState(state), file, duration, position, tags);
            case "position" -> new Position(position);
            case "volume" -> new Volume(left, right);
            case "view" -> new View(viewName);
            case "options" -> new Options(options);
            case null -> throw new IOException("event without a type");
            default -> null;
        };
    }

    private static PlayState parseState(String state) throws IOException {
        return switch (state) {
            case "stopped" -> PlayState.STOPPED;
            case "playing" -> PlayState.PLAYING;
            case "paused" -> PlayState.PAUSED;
            case null, default -> throw new IOException("bad play state: " + state);
        };
    }

    private static Map<String, List<String>> readTags(JsonReader r) throws IOException {
        // JSONObject would keep only the last duplicate key; tag keys repeat
        Map<String, List<String>> tags = new LinkedHashMap<>();
        r.beginObject();
        while (r.hasNext()) {
            tags.computeIfAbsent(r.nextName(), k -> new ArrayList<>()).add(r.nextString());
        }
        r.endObject();
        tags.replaceAll((k, v) -> Collections.unmodifiableList(v));
        return Collections.unmodifiableMap(tags);
    }

    private static Map<String, String> readOptions(JsonReader r) throws IOException {
        Map<String, String> options = new LinkedHashMap<>();
        r.beginObject();
        while (r.hasNext()) {
            options.put(r.nextName(), r.nextString());
        }
        r.endObject();
        return Collections.unmodifiableMap(options);
    }

    private static void closeQuietly(LocalSocket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
