package com.heavendoor.ultralab;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static com.heavendoor.ultralab.UltraLyricsState.*;

/** Dependency-free regression runner; all text is an original test fixture, not a lyric source. */
public final class UltraLyricsStateStandaloneTest {
    private static int checks;
    public static void main(String[] args) {
        checks = 0;
        nullAndEmpty(); immutableInputs(); invalidDataIsAtomic(); wordBounds(); beforeGapsAfter();
        onlyRealWordTiming(); wordProgress(); samplingBudget(); scrollWindow(); pauseAndResume();
        seekWhilePaused(); seekWhilePlaying(); trackAndSessionReset(); staleResults(); hiddenCard();
        durationClamp(); monotonicClockAndBadPositions(); smallPollCorrections(); longText();
        gapRevision(); repeatedData(); pauseWords(); durationEndsScroll(); delayedRendering();
        System.out.println("UltraLyricsState: " + checks + " checks passed");
    }
    private static void check(boolean ok, String message) {
        checks++; if (!ok) throw new AssertionError(message);
    }
    private static void eq(long expected, long actual, String message) {
        check(expected == actual, message + ": " + expected + " != " + actual);
    }
    private static void close(float expected, float actual, String message) {
        check(Math.abs(expected - actual) < .00001f, message + ": " + expected + " != " + actual);
    }
    private static List<Line> lines() {
        return Arrays.asList(new Line(0, 1000, "微光穿过窗边"),
                new Line(1000, 3000, "此刻缓缓向前"), new Line(3000, 5000, "让风带走回声"));
    }
    private static UltraLyricsState state(List<Line> lines, boolean playing) {
        UltraLyricsState s = new UltraLyricsState();
        s.updateMedia("item/session", 0, 10000, playing, 0);
        check(s.accept(s.generation(), lines, 0), "valid fixture accepted");
        return s;
    }
    private static List<Line> wordLines() {
        return Arrays.asList(new Line(0, 4000, "微光 向前", Arrays.asList(
                new Word(0, 1000, 0, 2), new Word(1500, 3000, 3, 5))),
                new Line(4000, 5000, "此刻"));
    }
    private static void nullAndEmpty() {
        UltraLyricsState s = new UltraLyricsState();
        check(!s.accept(s.generation(), lines(), 0), "disconnected source rejected");
        s.updateMedia(null, -1, -1, true, 0);
        check(s.frame(0, true).lines.isEmpty(), "null media has no fake lyric");
        eq(0, s.nextDelayMillis(0, true), "null has no timer");
        s.updateMedia("one", 0, 0, true, 0);
        check(s.accept(s.generation(), null, 0), "null lyric means unavailable");
        check(s.frame(0, true).lines.isEmpty(), "no fixture injected");
        check(s.accept(s.generation(), Collections.emptyList(), 0), "empty accepted");
    }
    private static void immutableInputs() {
        List<Word> words = new ArrayList<>(); words.add(new Word(0, 1000, 0, 2));
        Line line = new Line(0, 1000, "微光", words); words.clear();
        eq(1, line.words.size(), "word list copied");
        List<Line> input = new ArrayList<>(); input.add(line);
        UltraLyricsState s = state(input, true); input.clear();
        Frame frame = s.frame(0, true); eq(1, frame.lines.size(), "line list copied");
        boolean linesFrozen = false, wordsFrozen = false;
        try { frame.lines.clear(); } catch (UnsupportedOperationException expected) { linesFrozen = true; }
        try { line.words.clear(); } catch (UnsupportedOperationException expected) { wordsFrozen = true; }
        check(linesFrozen && wordsFrozen, "public snapshots cannot mutate model");
    }
    private static void invalidDataIsAtomic() {
        UltraLyricsState s = state(lines(), true);
        List<List<Line>> invalid = Arrays.asList(Arrays.asList((Line) null),
                Arrays.asList(new Line(0, 1, null)), Arrays.asList(new Line(0, 1, "  ")),
                Arrays.asList(new Line(-1, 1, "微")), Arrays.asList(new Line(3, 3, "光")),
                Arrays.asList(new Line(0, MAX_TIMESTAMP_MS + 1, "光")),
                Arrays.asList(new Line(0, 100, "微"), new Line(50, 200, "光")),
                Arrays.asList(new Line(0, 1, "x".repeat(MAX_TEXT_CHARS + 1))));
        for (List<Line> bad : invalid) {
            check(!s.accept(s.generation(), bad, 0), "invalid dataset rejected atomically");
            eq(3, s.frame(0, true).lines.size(), "previous timeline retained");
        }
        List<Line> tooMany = new ArrayList<>();
        for (int i = 0; i <= MAX_LINES; i++) tooMany.add(new Line(i * 2, i * 2 + 1, "光"));
        check(!s.accept(s.generation(), tooMany, 0), "line count bounded");
        List<Line> tooMuchText = new ArrayList<>();
        for (int i = 0; i < 129; i++) tooMuchText.add(new Line(i * 2, i * 2 + 1, "x".repeat(512)));
        check(!s.accept(s.generation(), tooMuchText, 0), "total text bounded");
    }
    private static void wordBounds() {
        UltraLyricsState s = state(lines(), true);
        List<List<Word>> invalid = Arrays.asList(Arrays.asList((Word) null),
                Arrays.asList(new Word(-1, 20, 0, 1)), Arrays.asList(new Word(0, 1001, 0, 1)),
                Arrays.asList(new Word(20, 20, 0, 1)), Arrays.asList(new Word(0, 20, 2, 1)),
                Arrays.asList(new Word(0, 20, 0, 3)),
                Arrays.asList(new Word(0, 20, 0, 1), new Word(10, 40, 1, 2)),
                Arrays.asList(new Word(0, 20, 0, 2), new Word(20, 40, 1, 2)));
        for (List<Word> bad : invalid) check(!s.accept(s.generation(),
                Arrays.asList(new Line(0, 1000, "微光", bad)), 0), "invalid word timing rejected");
        check(!s.accept(s.generation(), Arrays.asList(new Line(0, 1000, "a😀b",
                Arrays.asList(new Word(0, 1000, 0, 2)))), 0), "surrogate split rejected");
        check(s.accept(s.generation(), Arrays.asList(new Line(0, 1000, "a😀b",
                Arrays.asList(new Word(0, 1000, 1, 3)))), 0), "whole surrogate pair accepted");
        List<Word> many = new ArrayList<>();
        for (int i = 0; i <= MAX_WORDS; i++) many.add(new Word(i, i + 1, i, i + 1));
        check(!s.accept(s.generation(), Arrays.asList(new Line(0, 1000,
                "x".repeat(MAX_WORDS + 1), many)), 0), "word count bounded");
    }
    private static void beforeGapsAfter() {
        UltraLyricsState s = state(Arrays.asList(new Line(1000, 2000, "微光"),
                new Line(3000, 4000, "向前")), true);
        eq(-1, s.frame(0, true).currentIndex, "intro is not lyric");
        eq(0, s.frame(1000, true).currentIndex, "first line starts");
        eq(-1, s.frame(2000, true).currentIndex, "explicit interlude retained");
        Frame next = s.frame(3000, true);
        eq(1, next.currentIndex, "line after gap starts");
        eq(1, next.previousIndex, "no queued scroll across gap");
        eq(-1, s.frame(4000, true).currentIndex, "outro is not lyric");
        eq(0, s.nextDelayMillis(4000, true), "outro stops lyric timer");
    }
    private static void onlyRealWordTiming() {
        UltraLyricsState s = state(lines(), true);
        Frame a = s.frame(0, true); long revision = s.visualRevision(0, true);
        Frame b = s.frame(400, true);
        check(!a.wordTimed && !b.wordTimed, "line timing never fabricated as words");
        eq(-1, b.activeWordIndex, "line-only no active word");
        eq(0, b.completedCharEnd, "line-only no fake character sweep");
        eq(revision, s.visualRevision(400, true), "line-only stable pixels do not invalidate");
    }
    private static void wordProgress() {
        UltraLyricsState s = state(wordLines(), true);
        Frame f = s.frame(500, true);
        check(f.wordTimed, "real word timing enabled"); eq(0, f.activeWordIndex, "first word active");
        close(.5f, f.activeWordProgress, "word progress follows exact timing");
        f = s.frame(1200, true); eq(2, f.completedCharEnd, "first word complete");
        eq(-1, f.activeWordIndex, "timed word gap has no sweep");
        f = s.frame(1800, true); eq(1, f.activeWordIndex, "second word active");
        close(.2f, f.activeWordProgress, "word gap not uniformly interpolated");
        f = s.frame(3200, true); eq(5, f.completedCharEnd, "all timed words complete");
        eq(-1, f.activeWordIndex, "completed word not active");
    }
    private static void samplingBudget() {
        UltraLyricsState s = state(wordLines(), true);
        Frame f = s.frame(0, true); long rev = s.visualRevision(0, true);
        check(f == s.frame(100, true), "10 Hz map draw cannot advance 5 Hz lyrics");
        eq(rev, s.visualRevision(199, true), "normal revision stable before 200ms");
        check(s.frame(200, true) != f, "5 Hz sampling advances on next tick");
        check(s.visualRevision(200, true) > rev, "word pixel change invalidates cache");
        eq(200, s.nextDelayMillis(200, true), "normal wakeup budget");
        check(!s.animating(200, true), "ordinary word timing not unbounded captureAnimating");
    }
    private static void scrollWindow() {
        UltraLyricsState s = state(lines(), true); s.frame(800, true);
        Frame f = s.frame(1000, true);
        eq(1, f.currentIndex, "natural next line"); eq(0, f.previousIndex, "scroll retains old line");
        close(0, f.scrollProgress, "scroll begins at old position");
        check(s.animating(1000, true), "short transition marked animating");
        eq(100, s.nextDelayMillis(1000, true), "short window max10Hz");
        f = s.frame(1100, true);
        float t = 100f / SCROLL_MS;
        close(t * t * (3 - 2 * t), f.scrollProgress, "smoothstep easing");
        check(f == s.frame(1150, true), "scroll not updated above10Hz");
        f = s.frame(1600, true);
        close(1, f.scrollProgress, "520ms window converges on bounded samples");
        eq(1, f.previousIndex, "completed scroll releases old line");
        check(!s.animating(1600, true), "no perpetual capture animation");
    }
    private static void pauseAndResume() {
        UltraLyricsState s = state(lines(), true); s.frame(800, true); s.frame(1000, true);
        s.updateMedia("item/session", 1200, 10000, false, 1200);
        Frame paused = s.frame(1200, true); long revision = s.visualRevision(1200, true);
        Frame later = s.frame(2200, true);
        eq(paused.positionMs, later.positionMs, "pause freezes clock");
        close(paused.scrollProgress, later.scrollProgress, "pause freezes scroll");
        eq(revision, s.visualRevision(2200, true), "paused lyric revision stable");
        eq(0, s.nextDelayMillis(2200, true), "pause needs no lyric timer");
        check(!s.animating(2200, true), "pause stops capture animation only");
        s.updateMedia("item/session", 1200, 10000, true, 3000);
        close(paused.scrollProgress, s.frame(3000, true).scrollProgress, "resume starts at frozen motion");
        check(s.frame(3100, true).scrollProgress > paused.scrollProgress, "resume moves forward");
    }
    private static void seekWhilePaused() {
        UltraLyricsState s = state(lines(), false); s.frame(0, true);
        s.updateMedia("item/session", 3200, 10000, false, 1000);
        Frame f = s.frame(1000, true); eq(2, f.currentIndex, "paused seek resolves immediately");
        eq(2, f.previousIndex, "paused seek snaps");
        s.updateMedia("item/session", 3300, 10000, false, 1100);
        eq(3300, s.frame(1100, true).positionMs, "small paused seek honored");
        s.updateMedia("item/session", 0, 10000, false, 1200);
        eq(0, s.frame(1200, true).currentIndex, "restart while paused clears prior selection");
    }
    private static void seekWhilePlaying() {
        UltraLyricsState s = state(lines(), true); s.frame(0, true);
        s.updateMedia("item/session", 3200, 10000, true, 100);
        Frame f = s.frame(100, true); eq(2, f.currentIndex, "playing seek jumps");
        eq(2, f.previousIndex, "seek does not replay skipped transitions");
        s.updateMedia("item/session", 0, 10000, true, 200);
        f = s.frame(200, true); eq(0, f.currentIndex, "backward restart");
        eq(0, f.previousIndex, "restart snaps");
    }
    private static void trackAndSessionReset() {
        UltraLyricsState s = state(lines(), true); long old = s.generation(); s.frame(0, true);
        s.updateMedia("other/session", 0, 1000, true, 100);
        check(s.generation() > old, "new track advances generation");
        check(s.frame(100, true).lines.isEmpty(), "new track clears lyric immediately");
        s.accept(s.generation(), lines(), 100); old = s.generation();
        s.resetLyrics(); check(s.generation() > old, "same-metadata new item can invalidate explicitly");
        check(s.frame(100, true).lines.isEmpty(), "explicit session reset clears lyric");
        s.accept(s.generation(), lines(), 100);
        s.updateMedia("", 0, 0, false, 200);
        check(s.frame(200, true).lines.isEmpty(), "disconnect clears old lyric");
        check(!s.accept(s.generation(), lines(), 200), "cannot attach lyric while disconnected");
    }
    private static void staleResults() {
        UltraLyricsState s = state(lines(), true); long old = s.generation();
        s.updateMedia("new/item", 0, 10000, true, 100);
        check(!s.accept(old, lines(), 200), "old track asynchronous result rejected");
        check(s.frame(200, true).lines.isEmpty(), "stale result does not leak");
        check(s.accept(s.generation(), lines(), 3300), "current late result accepted");
        Frame f = s.frame(3300, true); eq(2, f.currentIndex, "late result selects current playback");
        eq(2, f.previousIndex, "late result snaps rather than scrolling entire track");
    }
    private static void hiddenCard() {
        UltraLyricsState s = state(wordLines(), true); Frame first = s.frame(0, true);
        long rev = s.visualRevision(0, true);
        check(first == s.frame(1000, false), "hidden snapshot frozen");
        eq(rev, s.visualRevision(2000, false), "hidden card causes no visual invalidation");
        eq(0, s.nextDelayMillis(2000, false), "hidden card has no wakeup");
        check(!s.animating(2000, false), "hidden card not animating");
        Frame shown = s.frame(4200, true); eq(1, shown.currentIndex, "visible card catches current time");
        eq(1, shown.previousIndex, "return snaps without queued scroll");
        eq(2, shown.lines.size(), "hidden card retains data");
    }
    private static void durationClamp() {
        UltraLyricsState s = state(wordLines(), true);
        s.updateMedia("item/session", 0, 2500, true, 0);
        eq(2500, s.frame(10000, true).positionMs, "extrapolation bounded by duration");
        eq(0, s.nextDelayMillis(10000, true), "finished duration stops timer");
        s.updateMedia("item/session", 100, 50, false, 10001);
        eq(50, s.frame(10001, true).positionMs, "reported position clamped to shorter duration");
    }
    private static void monotonicClockAndBadPositions() {
        UltraLyricsState s = new UltraLyricsState();
        s.updateMedia("one", -100, -100, true, -100);
        eq(0, s.frame(-10, true).positionMs, "negative inputs bounded");
        s.updateMedia("two", Long.MAX_VALUE, Long.MAX_VALUE, true, 100);
        eq(MAX_TIMESTAMP_MS, s.frame(Long.MAX_VALUE, true).positionMs, "huge input bounded without overflow");
    }
    private static void smallPollCorrections() {
        UltraLyricsState s = state(wordLines(), true); s.frame(0, true);
        s.updateMedia("item/session", 305, 10000, true, 300);
        eq(400, s.frame(400, true).positionMs, "small poll jitter does not reset clock phase");
        s.updateMedia("item/session", 601, 10000, true, 600);
        eq(600, s.frame(600, true).positionMs, "small correction not cumulative timing drift");
    }
    private static void gapRevision() {
        UltraLyricsState s = state(Arrays.asList(new Line(1000, 1500, "微光"),
                new Line(3000, 3500, "向前"), new Line(5000, 5500, "回声")), false);
        Frame intro = s.frame(0, true); eq(0, intro.upcomingIndex, "intro points at first line");
        long revision = s.visualRevision(0, true);
        s.updateMedia("item/session", 2000, 10000, false, 2000);
        Frame gap = s.frame(2000, true); eq(-1, gap.currentIndex, "first gap");
        eq(1, gap.upcomingIndex, "first gap points at next line");
        check(s.visualRevision(2000, true) > revision, "gap neighbor change invalidates");
        revision = s.visualRevision(2000, true);
        s.updateMedia("item/session", 4000, 10000, false, 4000);
        eq(2, s.frame(4000, true).upcomingIndex, "second gap points at following line");
        check(s.visualRevision(4000, true) > revision, "seek between gaps invalidates");
        s.updateMedia("item/session", 6000, 10000, false, 6000);
        eq(3, s.frame(6000, true).upcomingIndex, "outro points past last line");
    }
    private static void repeatedData() {
        UltraLyricsState s = state(lines(), true); s.frame(0, true);
        long rev = s.visualRevision(0, true);
        check(s.accept(s.generation(), lines(), 100), "equal replacement valid");
        eq(rev, s.visualRevision(100, true), "equal replacement avoids invalidation");
        check(s.accept(s.generation(), null, 100), "explicit no-data replacement");
        check(s.frame(100, true).lines.isEmpty(), "clear is immediate");
        check(s.visualRevision(100, true) > rev, "clear invalidates old lyric");
        eq(0, s.nextDelayMillis(100, true), "cleared data stops lyric timer");
    }
    private static void pauseWords() {
        UltraLyricsState s = state(wordLines(), true); s.frame(0, true);
        s.updateMedia("item/session", 350, 10000, false, 350);
        Frame f = s.frame(350, true);
        close(.35f, f.activeWordProgress, "pause captures exact reported word fraction");
        long revision = s.visualRevision(350, true);
        close(.35f, s.frame(5350, true).activeWordProgress, "pause freezes karaoke indefinitely");
        eq(revision, s.visualRevision(5350, true), "pause word pixels do not invalidate");
    }
    private static void durationEndsScroll() {
        UltraLyricsState s = state(lines(), true);
        s.updateMedia("item/session", 0, 1100, true, 0);
        s.frame(800, true); s.frame(1000, true);
        check(s.animating(1000, true), "short track can start normal scroll");
        Frame ended = s.frame(1100, true);
        eq(1, ended.currentIndex, "duration ending inside lyric retains current line");
        eq(1, ended.previousIndex, "duration clamp completes in-flight scroll");
        check(!s.animating(1100, true), "bounded clock cannot leave captureAnimating on forever");
        eq(0, s.nextDelayMillis(1100, true), "bounded clock has no wakeup");
        long revision = s.visualRevision(1100, true);
        eq(revision, s.visualRevision(100000, true), "terminal scroll revision stays stable");
    }
    private static void delayedRendering() {
        List<Line> timeline = Arrays.asList(new Line(0, 1000, "微光"),
                new Line(1000, 10000, "向前"));
        UltraLyricsState delayed = state(timeline, true);
        delayed.frame(800, true);
        Frame late = delayed.frame(4000, true);
        eq(1, late.currentIndex, "late renderer selects current line");
        eq(1, late.previousIndex, "missed scroll window snaps instead of replaying old line");
        close(1, late.scrollProgress, "late renderer has no deferred motion");
        check(!delayed.animating(4000, true), "missed window does not restart capture animation");

        UltraLyricsState slightlyLate = state(timeline, true);
        slightlyLate.frame(800, true);
        Frame partial = slightlyLate.frame(1100, true);
        float t = 100f / SCROLL_MS;
        close(t * t * (3 - 2 * t), partial.scrollProgress,
                "first render 100ms after boundary starts partway through curve");
        eq(0, partial.previousIndex, "within-window renderer retains previous line");
        slightlyLate.updateMedia("item/session", 1200, 10000, false, 1200);
        Frame paused = slightlyLate.frame(1200, true);
        slightlyLate.updateMedia("item/session", 1200, 10000, true, 3000);
        close(paused.scrollProgress, slightlyLate.frame(3000, true).scrollProgress,
                "boundary-anchored scroll resumes at frozen fraction");
        check(slightlyLate.frame(3100, true).scrollProgress > paused.scrollProgress,
                "resumed boundary-anchored scroll advances normally");

        UltraLyricsState atDeadline = state(timeline, true);
        atDeadline.frame(800, true);
        eq(1, atDeadline.frame(1000 + SCROLL_MS, true).previousIndex,
                "exact scroll deadline is already complete");

        UltraLyricsState skippedGap = state(Arrays.asList(new Line(0, 1000, "微光"),
                new Line(3000, 10000, "向前")), true);
        skippedGap.frame(800, true);
        eq(1, skippedGap.frame(3100, true).previousIndex,
                "unrendered interlude cannot animate an old lyric into a new line");
    }
    private static void longText() {
        String text = "微光".repeat(256);
        UltraLyricsState s = state(Arrays.asList(new Line(0, 1000, text)), false);
        eq(512, s.frame(0, true).lines.get(0).text.length(), "long lyric preserved for renderer's two-line ellipsis");
    }
}
