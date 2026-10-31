package com.heavendoor.ultralab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, Android-independent presentation state for an explicitly supplied lyric timeline.
 * This class does not fetch lyrics or infer word timing from line timing. All clock arguments
 * are monotonic milliseconds; a source must retain generation() when starting asynchronous work.
 */
public final class UltraLyricsState {
    public static final int NORMAL_INTERVAL_MS = 200;
    public static final int TRANSITION_INTERVAL_MS = 100;
    public static final int SCROLL_MS = 520;
    public static final int MAX_LINES = 512;
    public static final int MAX_TEXT_CHARS = 512;
    public static final int MAX_WORDS = 256;
    public static final long MAX_TIMESTAMP_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_TOTAL_TEXT_CHARS = 65536;
    private static final long SEEK_THRESHOLD_MS = 750;

    /** Character offsets are UTF-16 boundaries in Line.text, not estimated timing. */
    public static final class Word {
        public final long startMs, endMs;
        public final int startChar, endChar;
        public Word(long startMs, long endMs, int startChar, int endChar) {
            this.startMs = startMs; this.endMs = endMs;
            this.startChar = startChar; this.endChar = endChar;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Word)) return false;
            Word w = (Word) other;
            return startMs == w.startMs && endMs == w.endMs
                    && startChar == w.startChar && endChar == w.endChar;
        }
        @Override public int hashCode() { return Objects.hash(startMs, endMs, startChar, endChar); }
    }

    public static final class Line {
        public final long startMs, endMs;
        public final String text;
        public final List<Word> words;
        public Line(long startMs, long endMs, String text, List<Word> words) {
            this.startMs = startMs; this.endMs = endMs; this.text = text;
            this.words = words == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(words));
        }
        public Line(long startMs, long endMs, String text) {
            this(startMs, endMs, text, Collections.emptyList());
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Line)) return false;
            Line l = (Line) other;
            return startMs == l.startMs && endMs == l.endMs
                    && Objects.equals(text, l.text) && words.equals(l.words);
        }
        @Override public int hashCode() { return Objects.hash(startMs, endMs, text, words); }
    }

    /** Immutable drawing snapshot. Gaps/before/after the timeline have currentIndex == -1. */
    public static final class Frame {
        public final List<Line> lines;
        public final int currentIndex, previousIndex, upcomingIndex;
        public final float scrollProgress;
        public final long positionMs;
        public final boolean wordTimed;
        public final int completedCharEnd, activeWordIndex;
        public final float activeWordProgress;
        private Frame(List<Line> lines, int currentIndex, int previousIndex, int upcomingIndex,
                      float scrollProgress, long positionMs, boolean wordTimed,
                      int completedCharEnd, int activeWordIndex, float activeWordProgress) {
            this.lines = lines; this.currentIndex = currentIndex; this.previousIndex = previousIndex;
            this.upcomingIndex = upcomingIndex;
            this.scrollProgress = scrollProgress; this.positionMs = positionMs; this.wordTimed = wordTimed;
            this.completedCharEnd = completedCharEnd; this.activeWordIndex = activeWordIndex;
            this.activeWordProgress = activeWordProgress;
        }
    }

    private String trackKey = "";
    private long generation, anchorPosition, anchorNow, duration, revision;
    private double playbackSpeed = 1;
    private long validUntil = Long.MAX_VALUE;
    private boolean playing, visible, dirty = true, snap = true;
    private List<Line> lines = Collections.emptyList();
    private Frame cached = new Frame(lines, -1, -1, 0, 1, 0, false, 0, -1, 0);
    private long nextSampleAt, transitionPosition;
    private int transitionFrom = -1, transitionTo = -1;

    public synchronized long generation() { return generation; }

    /** Invalidate outstanding requests when a stronger adapter detects a new same-metadata item. */
    public synchronized long resetLyrics() {
        generation++;
        lines = Collections.emptyList();
        resetMotion(); dirty = true; snap = true;
        return generation;
    }

    /**
     * A nonempty track key must identify one playback item/session. The current metadata adapter
     * has no persistent item ID; future lyric providers must supply a stronger key when available.
     * Small periodic position corrections retain the clock, avoiding 300 ms polling jitter.
     */
    public synchronized void updateMedia(String key, long positionMs, long durationMs,
                                         boolean isPlaying, long now) {
        key = key == null ? "" : key;
        now = Math.max(0, now);
        long newDuration = clampTime(durationMs);
        long newPosition = boundedPosition(positionMs, newDuration);
        if (!trackKey.equals(key)) {
            trackKey = key;
            generation++;
            lines = Collections.emptyList();
            anchorPosition = newPosition; anchorNow = now; duration = newDuration;
            playing = isPlaying && !key.isEmpty();
            resetMotion(); dirty = true; snap = true;
            return;
        }
        if (key.isEmpty()) isPlaying = false;
        long predicted = positionAt(now);
        boolean seek = Math.abs(newPosition - predicted) > SEEK_THRESHOLD_MS
                || (!playing && newPosition != predicted);
        boolean playChanged = playing != isPlaying;
        boolean durationClamps = newDuration > 0 && predicted > newDuration;
        if (seek || playChanged || durationClamps) {
            anchorPosition = newPosition; anchorNow = now;
            dirty = true;
            if (seek || durationClamps) { resetMotion(); snap = true; }
        }
        duration = newDuration;
        playing = isPlaying;
    }

    /** Adapter clock: expires even when paused; unknown/future anchors must pass known=false. */
    public synchronized void updateClock(String key, long position, long durationMs, boolean advances,
                                         double speed, long expiresAt, boolean known, long now) {
        boolean speedChanged = playbackSpeed != speed;
        updateMedia(known ? key : "", position, durationMs, advances, now);
        if (speedChanged) { anchorPosition = boundedPosition(position, duration); anchorNow = now; dirty = true; snap = true; }
        playbackSpeed = Double.isFinite(speed) && speed >= 0 && speed <= 4 ? speed : 0;
        validUntil = expiresAt;
    }

    /**
     * Atomically replace data for the retained generation. null/empty clears valid current data.
     * Invalid, oversized, overlapping, or stale input leaves the existing timeline untouched.
     */
    public synchronized boolean accept(long ownerGeneration, List<Line> supplied, long now) {
        if (ownerGeneration != generation || trackKey.isEmpty()) return false;
        if (supplied != null && supplied.size() > MAX_LINES) return false;
        List<Line> candidate = supplied == null ? Collections.emptyList() : new ArrayList<>(supplied);
        if (!valid(candidate)) return false;
        if (lines.equals(candidate)) return true;
        lines = Collections.unmodifiableList(candidate);
        resetMotion(); dirty = true; snap = true;
        return true;
    }

    /**
     * Hidden cards do not advance visual state or revisions. Reappearing cards snap to the current
     * position instead of replaying queued transitions. Repeated calls within a sample period
     * return the same snapshot, even if another dashboard layer renders more frequently.
     */
    public synchronized Frame frame(long now, boolean isVisible) {
        now = Math.max(0, now);
        if (now > validUntil && !trackKey.isEmpty()) { updateMedia("", 0, 0, false, now); }
        if (!isVisible) {
            if (visible) { visible = false; resetMotion(); snap = true; }
            return cached;
        }
        if (!visible) { visible = true; dirty = true; snap = true; }
        if (!dirty && now < nextSampleAt) return cached;
        long position = positionAt(now);
        int index = currentIndex(position);
        boolean atEnd = position >= MAX_TIMESTAMP_MS || duration > 0 && position >= duration;
        if (snap || atEnd || index < 0 || index != transitionTo && index != cached.currentIndex) {
            if (!snap && !atEnd && playing && cached.currentIndex >= 0
                    && index == cached.currentIndex + 1
                    && lines.get(cached.currentIndex).endMs == lines.get(index).startMs
                    && position - lines.get(index).startMs < SCROLL_MS) {
                transitionFrom = cached.currentIndex;
                transitionTo = index;
                // Bind to the actual lyric boundary, not when a delayed renderer notices it.
                // A missed window (or interlude) snaps instead of replaying an old transition.
                transitionPosition = lines.get(index).startMs;
            } else resetMotion();
        }
        float scroll = 1;
        int previous = index;
        if (transitionTo == index && transitionFrom >= 0) {
            float t = Math.min(1, Math.max(0, position - transitionPosition) / (float) SCROLL_MS);
            scroll = t * t * (3 - 2 * t);
            previous = transitionFrom;
            if (t >= 1) { resetMotion(); previous = index; scroll = 1; }
        }
        boolean wordTimed = index >= 0 && !lines.get(index).words.isEmpty();
        int completed = 0, active = -1;
        float progress = 0;
        if (wordTimed) {
            List<Word> words = lines.get(index).words;
            for (int i = 0; i < words.size(); i++) {
                Word word = words.get(i);
                if (position >= word.endMs) completed = word.endChar;
                else if (position >= word.startMs) {
                    active = i;
                    progress = (position - word.startMs) / (float) (word.endMs - word.startMs);
                    break;
                } else break;
            }
        }
        Frame next = new Frame(lines, index, previous, upcomingIndex(position), scroll, position, wordTimed, completed, active, progress);
        if (!sameVisual(cached, next)) revision++;
        cached = next; dirty = false; snap = false;
        int interval = transitionFrom >= 0 ? TRANSITION_INTERVAL_MS : NORMAL_INTERVAL_MS;
        nextSampleAt = Math.min(now, Long.MAX_VALUE - interval) + interval;
        return cached;
    }

    public synchronized long visualRevision(long now, boolean isVisible) {
        frame(now, isVisible);
        return revision;
    }

    /** Only the short visible scroll window; normal word progress uses revision sampling. */
    public synchronized boolean animating(long now, boolean isVisible) {
        Frame f = frame(now, isVisible);
        return isVisible && playing && f.currentIndex >= 0 && f.previousIndex != f.currentIndex;
    }

    /** Zero means no lyric wakeup is needed; it never requests more than ten updates per second. */
    public synchronized long nextDelayMillis(long now, boolean isVisible) {
        Frame f = frame(now, isVisible);
        if (isVisible && !playing && !lines.isEmpty() && validUntil != Long.MAX_VALUE)
            return Math.max(TRANSITION_INTERVAL_MS, validUntil - Math.max(0, now) + 1);
        if (!isVisible || !playing || lines.isEmpty()
                || f.positionMs >= lines.get(lines.size() - 1).endMs
                || duration > 0 && f.positionMs >= duration) return 0;
        return Math.max(TRANSITION_INTERVAL_MS, nextSampleAt - Math.max(0, now));
    }

    private long positionAt(long now) {
        long delta = playing ? (long)Math.min(MAX_TIMESTAMP_MS, Math.max(0, now - anchorNow) * playbackSpeed) : 0;
        long position = anchorPosition + Math.min(MAX_TIMESTAMP_MS, delta);
        return boundedPosition(position, duration);
    }

    private static long clampTime(long time) { return Math.max(0, Math.min(MAX_TIMESTAMP_MS, time)); }
    private static long boundedPosition(long position, long duration) {
        long p = clampTime(position);
        return duration > 0 ? Math.min(p, duration) : p;
    }
    private void resetMotion() { transitionFrom = transitionTo = -1; }

    private int currentIndex(long position) {
        int lo = 0, hi = lines.size() - 1, candidate = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).startMs <= position) { candidate = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return candidate >= 0 && position < lines.get(candidate).endMs ? candidate : -1;
    }

    private int upcomingIndex(long position) {
        int lo = 0, hi = lines.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).startMs <= position) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private static boolean sameVisual(Frame a, Frame b) {
        return a.lines == b.lines && a.currentIndex == b.currentIndex && a.previousIndex == b.previousIndex
                && a.upcomingIndex == b.upcomingIndex
                && a.scrollProgress == b.scrollProgress && a.wordTimed == b.wordTimed
                && a.completedCharEnd == b.completedCharEnd && a.activeWordIndex == b.activeWordIndex
                && a.activeWordProgress == b.activeWordProgress;
    }

    static boolean valid(List<Line> input) {
        if (input.size() > MAX_LINES) return false;
        long previousEnd = -1;
        int totalChars = 0;
        for (Line line : input) {
            if (line == null || line.text == null || line.text.trim().isEmpty()
                    || line.text.length() > MAX_TEXT_CHARS || line.startMs < 0
                    || line.endMs <= line.startMs || line.endMs > MAX_TIMESTAMP_MS
                    || line.startMs < previousEnd || line.words.size() > MAX_WORDS) return false;
            totalChars += line.text.length();
            if (totalChars > MAX_TOTAL_TEXT_CHARS) return false;
            previousEnd = line.endMs;
            long wordEnd = line.startMs;
            int charEnd = 0;
            for (Word word : line.words) {
                if (word == null || word.startMs < wordEnd || word.startMs < line.startMs
                        || word.endMs <= word.startMs || word.endMs > line.endMs
                        || word.startChar < charEnd || word.endChar <= word.startChar
                        || word.endChar > line.text.length()
                        || splitsSurrogate(line.text, word.startChar)
                        || splitsSurrogate(line.text, word.endChar)) return false;
                wordEnd = word.endMs; charEnd = word.endChar;
            }
        }
        return true;
    }
    private static boolean splitsSurrogate(String value, int at) {
        return at > 0 && at < value.length() && Character.isHighSurrogate(value.charAt(at - 1))
                && Character.isLowSurrogate(value.charAt(at));
    }
}
