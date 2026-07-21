package net.pgaskin.cmus.android;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MediaSession + media notification + cover art + audio focus, fed entirely
 * by {@link CmusIpc} events, with system/headset controls sent back as cmus
 * commands. Strictly a mirror: cmus is driven from its TUI too, so playback
 * we didn't initiate is user intent — audio focus is cooperative
 * (best-effort request, never counter the user), and all state shown to the
 * system comes from events, never from what we sent.
 */
public final class MediaControl implements CmusIpc.Listener, AutoCloseable {
    private static final String TAG = "cmus";

    /** Larger dimension of decoded art; power-of-2 downsampled below it. */
    private static final int ART_MAX_DIM = 640;

    /**
     * A position event farther than this from the extrapolated position is
     * a seek. The system extrapolates from (position, speed) itself, so the
     * ~1/s position events are otherwise ignored — no per-second binder
     * churn. elapsedRealtime-vs-audio-clock drift stays well under this;
     * speed 0 while paused makes paused seeks trip it.
     */
    private static final long SEEK_THRESHOLD_MS = 2000;

    private static final long ACTIONS = PlaybackState.ACTION_PLAY
            | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_PAUSE
            | PlaybackState.ACTION_SKIP_TO_NEXT
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS
            | PlaybackState.ACTION_SEEK_TO
            | PlaybackState.ACTION_STOP;

    /** Folder-art fallback names, in preference order (compared lowercased). */
    private static final String[] FOLDER_ART_NAMES = {
            "cover.jpg", "cover.jpeg", "cover.png",
            "folder.jpg", "folder.jpeg", "folder.png",
            "front.jpg", "front.jpeg", "front.png",
            "album.jpg", "album.jpeg", "album.png",
    };

    private final CmusService service;
    private final CmusIpc ipc;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final MediaSession session;
    private final PendingIntent activityIntent;
    private final NotificationManager notificationManager;
    private final AudioManager audioManager;
    private final AudioFocusRequest focusRequest;
    private final ExecutorService artExecutor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "CmusArt"));

    // main thread only
    private boolean closed;
    private CmusIpc.Status status;
    private boolean focusHeld;
    private boolean resumeOnFocusGain; // we paused for a transient loss
    private String artFile; // file the pending/loaded art belongs to
    private Bitmap art; // null while no art / load pending
    // extrapolation basis for seek detection
    private long basisPositionMs = PlaybackState.PLAYBACK_POSITION_UNKNOWN;
    private long basisRealtime;
    private boolean basisPlaying;

    // art executor thread only: folder-art cache, so track changes within an
    // album don't rescan/redecode the directory
    private String artDirPath;
    private Bitmap artDirBitmap;

    public MediaControl(CmusService service, CmusIpc ipc) {
        this.service = service;
        this.ipc = ipc;
        activityIntent = PendingIntent.getActivity(service, 0,
                new Intent(service, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        notificationManager = service.getSystemService(NotificationManager.class);
        audioManager = service.getSystemService(AudioManager.class);
        // attributes match op/aaudio.c (AAUDIO_USAGE_MEDIA + CONTENT_TYPE_MUSIC)
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setOnAudioFocusChangeListener(this::onAudioFocusChange, mainHandler)
                .build();
        session = new MediaSession(service, "cmus");
        session.setSessionActivity(activityIntent);
        session.setCallback(callback, mainHandler);
        // media-key fallback once the session is gone: resurrects cmus after
        // an idle-quit. The registration is never cleared — passing null
        // here NPEs server-side (MediaButtonReceiverHolder.create, Android
        // 16) — so foreground quits are gated by CmusService.PREF_RESURRECT
        // in the receiver instead
        session.setMediaButtonBroadcastReceiver(
                new ComponentName(service, MediaButtonReceiver.class));
        session.setActive(true);
        updatePlaybackState(PlaybackState.PLAYBACK_POSITION_UNKNOWN);
        service.registerReceiver(noisyReceiver,
                new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                Context.RECEIVER_NOT_EXPORTED);
    }

    /**
     * The current media notification; CmusService uses it for
     * startForeground, updates go through notify() on status/metadata/art
     * changes (never per-position — the system renders controls, art, and
     * the seekbar from the session on its own).
     */
    public Notification buildNotification() {
        Notification.Builder nb = new Notification.Builder(service, CmusService.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken()))
                .setOngoing(true)
                .setContentIntent(activityIntent);
        String title = status != null && status.file() != null ? title(status) : null;
        if (title != null) {
            nb.setContentTitle(title);
            String artist = tag(status, "artist");
            if (artist != null) {
                nb.setContentText(artist);
            }
            if (art != null) {
                nb.setLargeIcon(art);
            }
        } else {
            nb.setContentTitle(service.getString(R.string.app_name));
        }
        return nb.build();
    }

    /** Abandons focus, releases the session, stops art loads; idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        service.unregisterReceiver(noisyReceiver);
        if (focusHeld) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            focusHeld = false;
        }
        session.release();
        artExecutor.shutdownNow();
    }

    // CmusIpc.Listener (main thread); disconnects are ignored — state stays
    // up and the reconnect snapshot re-primes everything

    @Override
    public void onEvent(CmusIpc.Event event) {
        if (closed) {
            return;
        }
        switch (event) {
            case CmusIpc.Status s -> onStatus(s);
            case CmusIpc.Position p -> onPosition(p);
            default -> {
            }
        }
    }

    private void onStatus(CmusIpc.Status s) {
        status = s;
        switch (s.state()) {
            case PLAYING -> requestFocus();
            case STOPPED -> {
                abandonFocus();
                resumeOnFocusGain = false;
            }
            case PAUSED -> {
                // keep holding focus so transient interruptions resume
            }
        }
        if (s.file() == null) {
            artFile = null;
            art = null;
        } else if (!s.file().equals(artFile)) {
            String file = artFile = s.file();
            art = null; // placeholder until the load lands
            artExecutor.execute(() -> {
                Bitmap bitmap = decodeArt(file);
                mainHandler.post(() -> {
                    if (closed || !file.equals(artFile)) {
                        return; // stale: track changed while decoding
                    }
                    art = bitmap;
                    updateMetadata();
                    notificationManager.notify(CmusService.NOTIFICATION_ID, buildNotification());
                });
            });
        }
        updatePlaybackState(s.position() < 0
                ? PlaybackState.PLAYBACK_POSITION_UNKNOWN : (long) (s.position() * 1000));
        updateMetadata();
        notificationManager.notify(CmusService.NOTIFICATION_ID, buildNotification());
    }

    private void onPosition(CmusIpc.Position p) {
        long extrapolated = basisPositionMs
                + (basisPlaying ? SystemClock.elapsedRealtime() - basisRealtime : 0);
        long positionMs = (long) (p.position() * 1000);
        if (basisPositionMs == PlaybackState.PLAYBACK_POSITION_UNKNOWN
                || Math.abs(positionMs - extrapolated) > SEEK_THRESHOLD_MS) {
            updatePlaybackState(positionMs);
        }
    }

    // session state (main thread)

    private void updatePlaybackState(long positionMs) {
        boolean playing = status != null && status.state() == CmusIpc.PlayState.PLAYING;
        int state = status == null ? PlaybackState.STATE_STOPPED : switch (status.state()) {
            case STOPPED -> PlaybackState.STATE_STOPPED;
            case PLAYING -> PlaybackState.STATE_PLAYING;
            case PAUSED -> PlaybackState.STATE_PAUSED;
        };
        basisPositionMs = positionMs;
        basisRealtime = SystemClock.elapsedRealtime();
        basisPlaying = playing;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(state, positionMs, playing ? 1.0f : 0.0f)
                .build());
    }

    private void updateMetadata() {
        MediaMetadata.Builder b = new MediaMetadata.Builder();
        if (status != null && status.file() != null) {
            b.putString(MediaMetadata.METADATA_KEY_TITLE, title(status));
            String artist = tag(status, "artist");
            if (artist != null) {
                b.putString(MediaMetadata.METADATA_KEY_ARTIST, artist);
            }
            String album = tag(status, "album");
            if (album != null) {
                b.putString(MediaMetadata.METADATA_KEY_ALBUM, album);
            }
            if (status.duration() >= 0) {
                // with SEEK_TO in the actions, this is what makes the
                // system controls grow a seekbar
                b.putLong(MediaMetadata.METADATA_KEY_DURATION, status.duration() * 1000L);
            }
            if (art != null) {
                b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            }
        }
        session.setMetadata(b.build());
    }

    private static String title(CmusIpc.Status s) {
        String title = tag(s, "title");
        return title != null ? title : new File(s.file()).getName();
    }

    /** Joined values of a (possibly repeated) tag; null if absent. */
    private static String tag(CmusIpc.Status s, String key) {
        List<String> values = s.tags().get(key);
        return values == null || values.isEmpty() ? null : String.join(", ", values);
    }

    // controls in: MediaSession callbacks + becoming-noisy (main thread).
    // No onMediaButtonEvent override — the default dispatch of headset/BT
    // keys onto these is exactly right.

    private final MediaSession.Callback callback = new MediaSession.Callback() {
        @Override
        public void onPlay() {
            // player-play restarts the current track from the beginning
            // when one is loaded (player.c _producer_play), so resume from
            // pause must be the toggle
            if (status != null && status.state() == CmusIpc.PlayState.PAUSED) {
                ipc.send(CmusIpc.CMD_TOGGLE_PAUSE);
            } else {
                ipc.send(CmusIpc.CMD_PLAY);
            }
        }

        @Override
        public void onPause() {
            ipc.send(CmusIpc.CMD_PAUSE);
        }

        @Override
        public void onSkipToNext() {
            ipc.send(CmusIpc.CMD_NEXT);
        }

        @Override
        public void onSkipToPrevious() {
            ipc.send(CmusIpc.CMD_PREV);
        }

        @Override
        public void onSeekTo(long positionMs) {
            ipc.send("seek " + Math.max(0, positionMs) / 1000);
        }

        @Override
        public void onStop() {
            ipc.send("player-stop");
        }
    };

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // headphones unplugged / BT gone: don't blast the speaker
            ipc.send(CmusIpc.CMD_PAUSE);
        }
    };

    // audio focus (main thread)

    private void requestFocus() {
        if (focusHeld) {
            return;
        }
        if (audioManager.requestAudioFocus(focusRequest)
                == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusHeld = true;
        } else {
            // playback was started from the TUI; never counter the user
            Log.w(TAG, "media: audio focus denied, playing anyway");
        }
    }

    private void abandonFocus() {
        if (focusHeld) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            focusHeld = false;
        }
    }

    private void onAudioFocusChange(int change) {
        if (closed) {
            return;
        }
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false;
                focusHeld = false; // no GAIN follows a permanent loss
                ipc.send(CmusIpc.CMD_PAUSE);
            }
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = status != null
                        && status.state() == CmusIpc.PlayState.PLAYING;
                ipc.send(CmusIpc.CMD_PAUSE);
            }
            case AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain && status != null
                        && status.state() == CmusIpc.PlayState.PAUSED) {
                    ipc.send(CmusIpc.CMD_TOGGLE_PAUSE); // toggle back to playing
                }
                resumeOnFocusGain = false;
            }
            default -> {
                // CAN_DUCK: the system ducks unhandled apps itself
            }
        }
    }

    // cover art (art executor thread)

    /** Cover for the track, in order: embedded → ogg picture block → folder art. */
    private Bitmap decodeArt(String path) {
        Bitmap embedded = decodeEmbedded(path);
        if (embedded != null) {
            return embedded;
        }
        // The framework extractor ignores ogg/opus METADATA_BLOCK_PICTURE, so
        // parse it ourselves before falling back to folder art.
        byte[] ogg = OggCover.extract(path);
        if (ogg != null) {
            Bitmap bitmap = decodeBytes(ogg);
            if (bitmap != null) {
                return bitmap;
            }
        }
        File dir = new File(path).getParentFile();
        if (dir == null) {
            return null;
        }
        return decodeFolderArt(dir);
    }

    /** The file's own embedded cover via the framework extractor, or null. */
    private Bitmap decodeEmbedded(String path) {
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(path);
            byte[] picture = retriever.getEmbeddedPicture();
            if (picture != null) {
                return decodeBytes(picture);
            }
        } catch (Exception e) {
            // formats the framework extractor can't open at all (e.g. wv)
            Log.d(TAG, "media: no embedded art for " + path + ": " + e);
        }
        return null;
    }

    /** Cached scan of the track's folder for a cover file (art thread only). */
    private Bitmap decodeFolderArt(File dir) {
        if (!dir.getPath().equals(artDirPath)) {
            artDirPath = dir.getPath();
            artDirBitmap = null;
            Map<String, File> byLowerName = new HashMap<>();
            File[] files = dir.listFiles();
            for (File f : files != null ? files : new File[0]) {
                byLowerName.putIfAbsent(f.getName().toLowerCase(Locale.ROOT), f);
            }
            for (String name : FOLDER_ART_NAMES) {
                File f = byLowerName.get(name);
                if (f != null) {
                    artDirBitmap = decodeScaled(opts -> BitmapFactory.decodeFile(f.getPath(), opts));
                    if (artDirBitmap != null) {
                        break;
                    }
                }
            }
        }
        return artDirBitmap;
    }

    private static Bitmap decodeBytes(byte[] data) {
        return decodeScaled(opts -> BitmapFactory.decodeByteArray(data, 0, data.length, opts));
    }

    private interface ArtDecoder {
        Bitmap decode(BitmapFactory.Options opts);
    }

    /** Two-pass decode, power-of-2 downsampled to &le; {@link #ART_MAX_DIM}. */
    private static Bitmap decodeScaled(ArtDecoder decoder) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        decoder.decode(bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / opts.inSampleSize > ART_MAX_DIM) {
            opts.inSampleSize *= 2;
        }
        return decoder.decode(opts);
    }
}
