package net.pgaskin.cmus.android;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Debounced continuous state saves (stage 19): closes the loss window for
 * exits that skip cmus's own save paths entirely — force-stop's uid
 * SIGKILL, battery death, panics — by sending granular {@code android-save}
 * lines while cmus runs. The cadences are Patrick's:
 * <ul>
 * <li>playlist/queue changes → 5s;
 * <li>command/search history → 5s;
 * <li>settings (the autosave file) → 5s after a real change;
 * <li>library+cache → 15s after track changes, or immediately when an
 *     import/update-cache finishes (deferred while a job runs — saving a
 *     half-imported library is churn);
 * <li>resume → 15s after play/pause or a track change (never keyed to
 *     position ticks; the delay also pushes the write away from the
 *     track-boundary moment when the producer is opening the next file);
 * <li>everything → at a playback boundary (track change, pause, stop) if
 *     15 min have passed since the last full save — the cache is the one
 *     multi-MB write, and boundaries are where it can't stutter.
 * </ul>
 * Library/cache/playlist/queue/history triggers are cmus's Dirty events
 * (the only way to see TUI-side edits); they're edge-triggered per save
 * cycle, so the timers are arm-on-first-change — bounded staleness, a
 * continuous edit stream can't postpone a save forever. Settings changes
 * are detected app-side by diffing Options echoes (cmus emits them per
 * command, changed or not) plus Volume changes (softvol_state lives in
 * autosave); resume by Status transitions. A mutation landing mid-save
 * re-announces (cmus snapshots its counters before writing), so nothing
 * is ever lost to the debounce bookkeeping.
 *
 * <p>Every android-save in the app funnels through here: acks are matched
 * FIFO against the request queue (one socket, cmus replies in order), so
 * {@link #saveNow}'s bounded waits (zip export, pre-reset saves) can never
 * be satisfied by a stray periodic ack. Everything runs on the main
 * thread; the owning TermService closes this with the CmusIpc instance it
 * was created for (a respawn gets a fresh pair, and cmus re-announces
 * still-dirty kinds in the connect snapshot, so nothing here needs to
 * survive).
 */
final class StateSaver implements CmusIpc.Listener, AutoCloseable {
    private static final String TAG = "cmus";

    private static final long SMALL_DEBOUNCE_MS = 5_000;
    private static final long BIG_DEBOUNCE_MS = 15_000;
    private static final long FULL_SAVE_MS = 15 * 60_000L;

    private final CmusIpc ipc;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // all state main-thread only
    private final Set<String> dirty = new TreeSet<>();
    private final ArrayDeque<Runnable> pendingAcks = new ArrayDeque<>(); // null-free; NOOP = no callback
    private static final Runnable NOOP = () -> {
    };
    private boolean closed;
    private long lastFullSave = SystemClock.elapsedRealtime();
    private boolean jobsRunning;

    // baselines; unset until the first (snapshot) event
    private CmusIpc.Status prevStatus;
    private Map<String, String> prevOptions;
    private CmusIpc.Volume prevVolume;

    /** One debounce timer over a fixed kind set; saves dirty ∩ kinds. */
    private final class Bucket {
        private final long delayMs;
        // worker jobs stream track adds into these kinds' editables (the
        // startup restore included): a save mid-job would write a
        // *partial* file over a complete one — and a force-stop before
        // the follow-up save persists the truncation. Defer to the
        // job-finished edge, which flushes directly.
        private final boolean deferWhileJobs;
        private final List<String> kinds;
        private final Runnable fire = this::onFire;
        private boolean armed;

        Bucket(long delayMs, boolean deferWhileJobs, String... kinds) {
            this.delayMs = delayMs;
            this.deferWhileJobs = deferWhileJobs;
            this.kinds = List.of(kinds);
        }

        void markDirty(String kind) {
            dirty.add(kind);
            if (!armed && !closed) {
                armed = true;
                handler.postDelayed(fire, delayMs);
            }
        }

        private void onFire() {
            armed = false;
            if (closed) {
                return;
            }
            if (deferWhileJobs && jobsRunning) {
                return;
            }
            flush();
        }

        void flush() {
            List<String> save = new ArrayList<>(kinds);
            save.retainAll(dirty);
            if (!save.isEmpty()) {
                sendSave(save, NOOP);
            }
        }

        void cancel() {
            armed = false;
            handler.removeCallbacks(fire);
        }
    }

    private final Bucket playlists = new Bucket(SMALL_DEBOUNCE_MS, true, "playlist", "queue");
    private final Bucket history = new Bucket(SMALL_DEBOUNCE_MS, false, "history");
    private final Bucket settings = new Bucket(SMALL_DEBOUNCE_MS, false, "settings");
    private final Bucket libCache = new Bucket(BIG_DEBOUNCE_MS, true, "library", "cache");
    private final Bucket resume = new Bucket(BIG_DEBOUNCE_MS, false, "resume");
    private final List<Bucket> buckets = List.of(playlists, history, settings, libCache, resume);

    StateSaver(CmusIpc ipc) {
        this.ipc = ipc;
    }

    /**
     * Bounded full save for the zip export and the pre-reset checkbox:
     * done runs on the main thread exactly once — on this request's own
     * ack (FIFO, so an earlier periodic ack can't stand in for it) or
     * after timeoutMs, whichever comes first; a wedged cmus never blocks
     * an export. A full save also clears every pending debounce, so
     * nothing periodic is left to fire into the export's zip walk (and
     * with atomic renames a late straggler couldn't tear a read anyway).
     */
    void saveNow(long timeoutMs, Runnable done) {
        Runnable once = new Runnable() {
            private boolean ran;

            @Override
            public void run() {
                if (!ran) {
                    ran = true;
                    done.run();
                }
            }
        };
        fullSave(once);
        // the queue entry stays for FIFO alignment; the late ack just
        // finds an already-run once
        handler.postDelayed(once, timeoutMs);
    }

    /** Playback-boundary full save (and saveNow's body). */
    private void fullSave(Runnable done) {
        lastFullSave = SystemClock.elapsedRealtime();
        for (Bucket b : buckets) {
            b.cancel();
        }
        dirty.clear();
        pendingAcks.add(done);
        Log.d(TAG, "save: full");
        ipc.send("android-save");
    }

    private void sendSave(List<String> kinds, Runnable done) {
        dirty.removeAll(kinds);
        pendingAcks.add(done);
        Log.d(TAG, "save: " + kinds);
        ipc.send("android-save " + String.join(" ", kinds));
    }

    @Override
    public void onEvent(CmusIpc.Event event) {
        if (closed) {
            return;
        }
        switch (event) {
            case CmusIpc.Dirty d -> {
                for (String kind : d.what()) {
                    switch (kind) {
                        case "library", "cache" -> libCache.markDirty(kind);
                        case "playlist", "queue" -> playlists.markDirty(kind);
                        case "history" -> history.markDirty(kind);
                        default -> Log.w(TAG, "save: unknown dirty kind " + kind);
                    }
                }
            }
            case CmusIpc.Jobs j -> {
                boolean was = jobsRunning;
                jobsRunning = j.running();
                if (was && !jobsRunning) {
                    // import/update-cache/restore finished: save what it
                    // dirtied now (the dirty announcement precedes the
                    // jobs diff in the flush, so bucket state is current)
                    for (Bucket b : buckets) {
                        if (b.deferWhileJobs) {
                            b.cancel();
                            b.flush();
                        }
                    }
                }
            }
            case CmusIpc.Status s -> {
                CmusIpc.Status prev = prevStatus;
                prevStatus = s;
                if (prev == null) {
                    return; // the snapshot is the baseline, not a change
                }
                boolean stateChanged = s.state() != prev.state();
                boolean fileChanged = !Objects.equals(s.file(), prev.file());
                if (!stateChanged && !fileChanged) {
                    return; // metadata-only echo
                }
                resume.markDirty("resume");
                // "between track playback": a track boundary or a stop in
                // playback is where the multi-MB cache write can't stutter
                boolean boundary = (fileChanged && s.state() == CmusIpc.PlayState.PLAYING)
                        || (stateChanged && s.state() != CmusIpc.PlayState.PLAYING);
                if (boundary && SystemClock.elapsedRealtime() - lastFullSave >= FULL_SAVE_MS) {
                    fullSave(NOOP);
                }
            }
            case CmusIpc.Options o -> {
                // cmus echoes options after every command, changed or not:
                // the diff is what says the autosave file's content moved
                Map<String, String> prev = prevOptions;
                prevOptions = o.values();
                if (prev != null && !prev.equals(o.values())) {
                    settings.markDirty("settings");
                }
            }
            case CmusIpc.Volume v -> {
                // softvol_state is autosave content but volume changes
                // don't dirty any option echo
                CmusIpc.Volume prev = prevVolume;
                prevVolume = v;
                if (prev != null && !prev.equals(v)) {
                    settings.markDirty("settings");
                }
            }
            case CmusIpc.Saved s -> {
                Runnable done = pendingAcks.poll();
                if (done == null) {
                    Log.w(TAG, "save: unmatched ack " + s.what());
                } else {
                    done.run();
                }
            }
            default -> {
            }
        }
    }

    @Override
    public void onDisconnected() {
        // in-flight acks died with the connection (saveNow timeouts still
        // release their callers); dirty kinds re-announce in the next
        // connect's snapshot, so pending debounces just re-arm
        pendingAcks.clear();
        for (Bucket b : buckets) {
            b.cancel();
        }
        dirty.clear();
    }

    @Override
    public void close() {
        closed = true;
        pendingAcks.clear();
        for (Bucket b : buckets) {
            b.cancel();
        }
        dirty.clear();
    }
}
