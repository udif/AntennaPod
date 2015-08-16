package de.ph1b.audiobook.mediaplayer;

import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.PowerManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.google.common.base.Preconditions;

import net.jcip.annotations.GuardedBy;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import de.ph1b.audiobook.activity.BookShelfActivity;
import de.ph1b.audiobook.model.Book;
import de.ph1b.audiobook.model.Chapter;
import de.ph1b.audiobook.model.DataBaseHelper;

public class MediaPlayerController implements MediaPlayer.OnErrorListener,
        MediaPlayerInterface.OnCompletionListener {


    private static final String TAG = MediaPlayerController.class.getSimpleName();
    public static volatile boolean sleepTimerActive = false;
    private static volatile PlayState playState = PlayState.STOPPED;
    private final Context c;
    private final ReentrantLock lock = new ReentrantLock();
    private final PrefsManager prefs;
    private final DataBaseHelper db;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
    @GuardedBy("lock")
    private final MediaPlayerInterface player;
    private final Communication communication = Communication.getInstance();
    @GuardedBy("lock")
    @Nullable
    private Book book;
    private volatile State state;
    private ScheduledFuture<?> sleepSand;
    private ScheduledFuture updater = null;
    private volatile int prepareTries = 0;

    public MediaPlayerController(@NonNull final Context c) {
        lock.lock();
        try {
            this.c = c;
            prefs = PrefsManager.getInstance(c);
            db = DataBaseHelper.getInstance(c);

            if (canSetSpeed()) {
                player = new CustomMediaPlayer();
            } else {
                player = new AndroidMediaPlayer();
            }
            state = State.IDLE;
            setPlayState(PlayState.STOPPED);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Checks if the device can set playback-seed by {@link MediaPlayerInterface#setPlaybackSpeed(float)}
     * Therefore it has to be >= {@link android.os.Build.VERSION_CODES#JELLY_BEAN} and not blacklisted
     * due to a bug.
     *
     * @return true if the device can set variable playback speed.
     */
    public static boolean canSetSpeed() {
        boolean greaterJellyBean = Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.JELLY_BEAN;

        boolean me173x = Build.MODEL.equals("ME173X") && Build.HARDWARE.equals("mt8125");
        boolean n903 = Build.MODEL.equals("N903") && Build.HARDWARE.equals("mt6582");
        boolean microMaxA116 = Build.MODEL.equals("Micromax A116") && Build.HARDWARE.equals("mt6589");
        boolean hmNote1w = Build.MODEL.equals("HM NOTE 1W") && Build.HARDWARE.equals("mt6592");
        boolean gSmartRomaR2 = Build.MODEL.equals("GSmart Roma R2") && Build.HARDWARE.equals("mt6572");

        return greaterJellyBean && !me173x && !n903 && !microMaxA116 && !hmNote1w && !gSmartRomaR2;
    }

    public static PlayState getPlayState() {
        return playState;
    }

    public static void setPlayState(PlayState playState) {
        MediaPlayerController.playState = playState;
        Communication.getInstance().playStateChanged();
    }

    /**
     * Initializes a new book. After this, a call to play can be made.
     *
     * @param book The book to be initialized.
     */
    public void init(@NonNull Book book) {
        lock.lock();
        try {
            Log.e(TAG, "constructor called with book=" + book);
            Preconditions.checkNotNull(book);
            this.book = book;
            prepare();
        } finally {
            lock.unlock();
        }
    }

    public void updateBook(@Nullable Book book) {
        lock.lock();
        try {
            this.book = book;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Prepares the current chapter set in book.
     */
    private void prepare() {
        lock.lock();
        try {
            if (book != null) {
                player.reset();

                player.setOnCompletionListener(this);
                player.setOnErrorListener(this);
                player.setWakeMode(c, PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE);

                try {
                    player.setDataSource(book.getCurrentChapter().getPath());
                    player.prepare();
                    player.seekTo(book.getTime());
                    player.setPlaybackSpeed(book.getPlaybackSpeed());
                    state = State.PREPARED;
                } catch (IOException e) {
                    e.printStackTrace();
                    state = State.DEAD;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Plays the prepared file.
     */
    public void play() {
        lock.lock();
        try {
            switch (state) {
                case PLAYBACK_COMPLETED:
                    player.seekTo(0);
                case PREPARED:
                case PAUSED:
                    player.start();
                    startUpdating();
                    setPlayState(PlayState.PLAYING);
                    state = State.STARTED;
                    prepareTries = 0;
                    break;
                case DEAD:
                case IDLE:
                    if (prepareTries > 5) {
                        prepareTries = 0;
                        state = State.DEAD;
                        break;
                    }
                    prepare();
                    prepareTries++;
                    play();
                    break;
                default:
                    Log.e(TAG, "play called in illegal state:" + state);
                    break;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Updates the current time and position of the book, writes it to the database and sends
     * updates to the GUI.
     */
    private void startUpdating() {
        Log.v(TAG, "startupdating");
        if (!updaterActive()) {
            updater = executor.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    lock.lock();
                    try {
                        if (book != null) {
                            book.setPosition(player.getCurrentPosition(), book.getCurrentMediaPath());
                            db.updateBook(book);
                        }
                    } finally {
                        lock.unlock();
                    }
                }
            }, 0, 1, TimeUnit.SECONDS);
        }
    }

    /**
     * Skips by the amount, specified in the settings.
     *
     * @param direction The direction to skip
     */
    public void skip(Direction direction) {
        final String TAG = MediaPlayerController.TAG + ":skip()";
        Log.v(TAG, "direction=" + direction);
        lock.lock();
        try {
            if (book != null) {
                final int currentPos = player.getCurrentPosition();
                final int duration = player.getDuration();
                final int delta = prefs.getSeekTime() * 1000;

                final int seekTo = (direction == Direction.FORWARD) ? currentPos + delta : currentPos - delta;
                Log.v(TAG, "currentPos=" + currentPos + ",seekTo=" + seekTo + ",duration=" + duration);

                if (seekTo < 0) {
                    previous(false);
                } else if (seekTo > duration) {
                    next();
                } else {
                    changePosition(seekTo, book.getCurrentMediaPath());
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * If current time is > 2000ms, seek to 0. Else play previous chapter if there is one.
     */
    public void previous(boolean toNullOfNewTrack) {
        lock.lock();
        try {
            if (book != null) {
                if (player.getCurrentPosition() > 2000 || book.getPreviousChapter() == null) {
                    player.seekTo(0);
                    book.setPosition(0, book.getCurrentMediaPath());
                    db.updateBook(book);
                } else {
                    if (toNullOfNewTrack) {
                        changePosition(0, book.getPreviousChapter().getPath());
                    } else {
                        changePosition(book.getPreviousChapter().getDuration() -
                                (prefs.getSeekTime() * 1000), book.getPreviousChapter().getPath());
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return the current book.
     */
    @Nullable
    public Book getBook() {
        return book;
    }

    /**
     * Stops the playback and releases some resources.
     */
    public void stop() {
        lock.lock();
        try {
            stopUpdating();
            player.reset();
            setPlayState(PlayState.STOPPED);
            if (sleepSandActive()) {
                toggleSleepSand();
            }
            state = State.IDLE;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stops updating the book with the current position.
     */
    private void stopUpdating() {
        if (updaterActive()) {
            updater.cancel(true);
        }
    }

    /**
     * @return true if a sleep timer has been set.
     */
    private boolean sleepSandActive() {
        lock.lock();
        try {
            return sleepSand != null && !sleepSand.isCancelled() && !sleepSand.isDone();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Turns the sleep timer on or off.
     */
    public void toggleSleepSand() {
        Log.i(TAG, "toggleSleepSand. Old state was:" + sleepSandActive());
        lock.lock();
        try {
            if (sleepSandActive()) {
                Log.i(TAG, "sleepSand is active. cancelling now");
                sleepSand.cancel(false);
                sleepTimerActive = false;
            } else {
                Log.i(TAG, "preparing new sleep sand");
                final int minutes = prefs.getSleepTime();
                sleepTimerActive = true;
                sleepSand = executor.schedule(new Runnable() {
                    @Override
                    public void run() {
                        lock.lock();
                        try {
                            pause();
                            sleepTimerActive = false;
                            communication.sleepStateChanged();
                        } finally {
                            lock.unlock();
                        }
                    }
                }, minutes, TimeUnit.MINUTES);
            }
            communication.sleepStateChanged();
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return true if the position updater is active.
     */
    private boolean updaterActive() {
        return updater != null && !updater.isCancelled() && !updater.isDone();
    }

    /**
     * Pauses the player. Also stops the updating mechanism which constantly updates the book to the
     * database.
     */
    public void pause() {
        lock.lock();
        try {
            Log.v(TAG, "pause acquired lock. state is=" + state);
            if (book != null) {
                switch (state) {
                    case STARTED:
                        player.pause();
                        stopUpdating();

                        final int autoRewind = prefs.getAutoRewindAmount() * 1000;
                        if (autoRewind != 0) {
                            int originalPosition = player.getCurrentPosition();
                            int seekTo = originalPosition - autoRewind;
                            if (seekTo < 0) seekTo = 0;
                            player.seekTo(seekTo);
                            book.setPosition(seekTo, book.getCurrentMediaPath());
                        }
                        db.updateBook(book);

                        setPlayState(PlayState.PAUSED);

                        state = State.PAUSED;
                        break;
                    default:
                        Log.e(TAG, "pause called in illegal state=" + state);
                        break;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        lock.lock();
        try {
            Log.e(TAG, "onError");
            if (book != null) {
                c.startActivity(BookShelfActivity.malformedFileIntent(c, book.getCurrentMediaPath()));
            } else {
                c.startActivity(new Intent(c, BookShelfActivity.class));
            }

            state = State.DEAD;
        } finally {
            lock.unlock();
        }

        return false;
    }

    /**
     * After the current song has ended, prepare the next one if there is one. Else stop the
     * resources.
     */
    @Override
    public void onCompletion() {
        lock.lock();
        try {
            if (book != null) {
                Log.v(TAG, "onCompletion called, nextChapter=" + book.getNextChapter());
                if (book.getNextChapter() != null) {
                    next();
                } else {
                    Log.v(TAG, "Reached last track. Stopping player");
                    stopUpdating();
                    setPlayState(PlayState.STOPPED);

                    state = State.PLAYBACK_COMPLETED;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Plays the next chapter. If there is none, don't do anything.
     */
    public void next() {
        lock.lock();
        try {
            if (book != null) {
                Chapter nextChapter = book.getNextChapter();
                if (nextChapter != null) {
                    changePosition(0, nextChapter.getPath());
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Changes the current position in book. If the path is the same, continues playing the song.
     * Else calls {@link #prepare()} to prepare the next file
     *
     * @param time The time in chapter at which to start
     * @param path The path of the media to play (relative to the books root path)
     */
    public void changePosition(int time, String path) {
        final String TAG = MediaPlayerController.TAG + ":changePosition()";
        lock.lock();
        try {
            Log.v(TAG, "time=" + time + ", relPath=" + path);
            if (book != null) {
                boolean changeFile = (!book.getCurrentChapter().getPath().equals(path));
                Log.v(TAG, "changeFile=" + changeFile);
                if (changeFile) {
                    boolean wasPlaying = (state == State.STARTED);
                    book.setPosition(time, path);
                    db.updateBook(book);
                    prepare();
                    if (wasPlaying) {
                        player.start();
                        state = State.STARTED;
                        setPlayState(PlayState.PLAYING);
                    } else {
                        state = State.PREPARED;
                        setPlayState(PlayState.PAUSED);
                    }
                } else {
                    switch (state) {
                        case PREPARED:
                        case STARTED:
                        case PAUSED:
                        case PLAYBACK_COMPLETED:
                            player.seekTo(time);
                            book.setPosition(time, book.getCurrentChapter().getPath());
                            db.updateBook(book);
                            break;
                        default:
                            Log.e(TAG, "changePosition called in illegal state:" + state);
                            break;
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public float getPlaybackSpeed() {
        lock.lock();
        try {
            return player.getPlaybackSpeed();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sets the current playback speed
     *
     * @param speed The playback-speed. 1.0 for normal playback, 2.0 for twice the speed, etc.
     */
    public void setPlaybackSpeed(float speed) {
        lock.lock();
        try {
            if (book != null) {
                book.setPlaybackSpeed(speed);
                db.updateBook(book);
                if (state != State.DEAD) {
                    player.setPlaybackSpeed(speed);
                } else {
                    Log.e(TAG, "setPlaybackSpeed called in illegal state: " + state);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * After this this object should no longer be used.
     */
    public void onDestroy() {
        player.release();
    }

    public enum PlayState {
        PLAYING,
        PAUSED,
        STOPPED,
    }

    /**
     * The direction to skip.
     */
    public enum Direction {
        FORWARD, BACKWARD
    }

    /**
     * The various internal states the player can have.
     */
    private enum State {
        PAUSED,
        DEAD,
        PREPARED,
        STARTED,
        PLAYBACK_COMPLETED,
        IDLE
    }
}
