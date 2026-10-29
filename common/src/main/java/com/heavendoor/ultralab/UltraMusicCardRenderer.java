package com.heavendoor.ultralab;

import static com.heavendoor.ultralab.UltraMusicCardStyle.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Music contents only. The dashboard still owns the original glass, theme and side exchange. */
final class UltraMusicCardRenderer {
    private final Typeface medium, bold;
    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clip = new Path();
    private final Path rowClip = new Path();
    private final Path completedClip = new Path();
    private final Rect source = new Rect();
    private final RectF bounds = new RectF();
    // At most four rows are visible during a transition. Never cache a whole song's layouts.
    private final Map<String, StaticLayout> layouts = new LinkedHashMap<String, StaticLayout>(12, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, StaticLayout> entry) {
            return size() > 12;
        }
    };

    UltraMusicCardRenderer(Typeface medium, Typeface bold) {
        this.medium = medium;
        this.bold = bold;
    }

    void clear() { layouts.clear(); }

    void draw(Canvas canvas, Bitmap cover, String title, String artist, long position, long duration,
              UltraLyricsState.Frame lyrics, int ink, int muted, int line, int accent) {
        canvas.save();
        canvas.clipRect(0, 0, WIDTH, HEIGHT);
        drawCover(canvas, cover, ink, muted);
        String primary = title.isEmpty() ? (artist.isEmpty() ? "等待音乐" : artist) : title;
        String secondary = title.isEmpty() ? (artist.isEmpty() ? "在 iPhone 播放后显示" : "") : artist;
        label(canvas, primary, HEADER_TEXT_X, TITLE_BASELINE, WIDTH - INSET - HEADER_TEXT_X, TITLE_SIZE, ink, bold);
        label(canvas, secondary, HEADER_TEXT_X, ARTIST_BASELINE, WIDTH - INSET - HEADER_TEXT_X, ARTIST_SIZE, muted, medium);
        setPaint(line, 1, medium);
        canvas.drawLine(INSET, DIVIDER_Y, WIDTH - INSET, DIVIDER_Y, paint);

        canvas.save();
        canvas.clipRect(INSET, LYRIC_TOP, WIDTH - INSET, LYRIC_BOTTOM);
        if (lyrics.lines.isEmpty()) {
            centered(canvas, title.isEmpty() && artist.isEmpty() ? "等待音乐" : "暂无歌词",
                    CURRENT_CENTER_Y - 10, 28, ink, bold);
            centered(canvas, "当前来源未提供歌词", CURRENT_CENTER_Y + 30, 18, muted, medium);
        } else if (lyrics.currentIndex < 0) {
            int next = lyrics.upcomingIndex;
            String status = next == 0 ? "等待歌词开始" : next == lyrics.lines.size() ? "歌词已结束" : "间奏";
            centered(canvas, status, CURRENT_CENTER_Y, 26, muted, medium);
            lyricRow(canvas, lyrics, next - 1, CURRENT_CENTER_Y - ROW_GAP, 0, 1, ink, muted, accent);
            lyricRow(canvas, lyrics, next, CURRENT_CENTER_Y + ROW_GAP, 0, 1, ink, muted, accent);
        } else {
            int current = lyrics.currentIndex, previous = lyrics.previousIndex;
            float progress = lyrics.scrollProgress;
            boolean scrolling = previous >= 0 && previous != current && progress < 1;
            if (!scrolling) {
                for (int i = current - 1; i <= current + 1; i++) {
                    lyricRow(canvas, lyrics, i, CURRENT_CENTER_Y + (i - current) * ROW_GAP,
                            i == current ? 1 : 0, 1, ink, muted, accent);
                }
            } else {
                // Position and emphasis move together; no marquee or full-card translation.
                for (int i = previous - 1; i <= current + 1; i++) {
                    float y = CURRENT_CENTER_Y + (i - previous - (current - previous) * progress) * ROW_GAP;
                    boolean wasVisible = Math.abs(i - previous) <= 1;
                    boolean nowVisible = Math.abs(i - current) <= 1;
                    float opacity = wasVisible && nowVisible ? 1 : wasVisible ? 1 - progress : progress;
                    float emphasis = i == previous ? 1 - progress : i == current ? progress : 0;
                    // One shaped text layout per row: cross-fading two font sizes causes double glyphs.
                    lyricRow(canvas, lyrics, i, y, emphasis, opacity, ink, muted, accent);
                }
            }
        }
        canvas.restore();

        setPaint(line, 1, medium);
        bounds.set(42, PROGRESS_Y, WIDTH - 42, PROGRESS_Y + 3);
        canvas.drawRoundRect(bounds, 2, 2, paint);
        if (duration > 0) {
            paint.setColor(accent);
            bounds.right = 42 + (WIDTH - 84) * Math.min(1f, Math.max(0, position) / (float) duration);
            canvas.drawRoundRect(bounds, 2, 2, paint);
        }
        label(canvas, time(position), 42, TIME_BASELINE, 150, 17, muted, medium);
        setPaint(muted, 17, medium);
        String end = time(duration);
        canvas.drawText(end, WIDTH - 42 - paint.measureText(end), TIME_BASELINE, paint);
        canvas.restore();
    }

    private void drawCover(Canvas canvas, Bitmap cover, int ink, int muted) {
        setPaint(withOpacity(ink, .08f), 1, medium);
        bounds.set(INSET, COVER_TOP, INSET + COVER_SIZE, COVER_TOP + COVER_SIZE);
        canvas.drawRoundRect(bounds, 12, 12, paint);
        if (cover != null && !cover.isRecycled()) {
            canvas.save();
            clip.reset();
            clip.addRoundRect(bounds, 12, 12, Path.Direction.CW);
            canvas.clipPath(clip);
            int side = Math.min(cover.getWidth(), cover.getHeight());
            int x = (cover.getWidth() - side) / 2, y = (cover.getHeight() - side) / 2;
            source.set(x, y, x + side, y + side);
            setPaint(ink, 1, medium);
            canvas.drawBitmap(cover, source, bounds, paint);
            canvas.restore();
        } else {
            setPaint(muted, 1, medium);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2);
            canvas.drawCircle(INSET + COVER_SIZE / 2f, COVER_TOP + COVER_SIZE / 2f, 19, paint);
            canvas.drawCircle(INSET + COVER_SIZE / 2f, COVER_TOP + COVER_SIZE / 2f, 4, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private void lyricRow(Canvas canvas, UltraLyricsState.Frame frame, int index, float centerY,
                          float emphasis, float opacity, int ink, int muted, int accent) {
        if (index < 0 || index >= frame.lines.size() || opacity <= 0) return;
        UltraLyricsState.Line lyric = frame.lines.get(index);
        StaticLayout layout = layout(lyric.text, emphasis);
        TextPaint textPaint = layout.getPaint();
        boolean karaoke = emphasis > 0 && index == frame.currentIndex && frame.wordTimed;
        int base = mix(muted, karaoke ? withOpacity(ink, UNSUNG_ALPHA_PERCENT / 100f) : ink, emphasis);
        textPaint.setColor(withOpacity(base, opacity));
        canvas.save();
        canvas.translate(INSET, centerY - layout.getHeight() / 2f);
        layout.draw(canvas);
        if (karaoke) {
            textPaint.setColor(withOpacity(accent, opacity * emphasis));
            // A provider may omit timing for a prefix or inter-word text. Never color those gaps.
            // Combine selected ranges, then draw the text once, not once per completed word.
            completedClip.reset();
            int visibleEnd = visibleEnd(layout);
            for (UltraLyricsState.Word word : lyric.words) {
                if (word.endMs > frame.positionMs) break;
                int start = Math.min(word.startChar, visibleEnd), end = Math.min(word.endChar, visibleEnd);
                if (start < end) {
                    clip.reset(); layout.getSelectionPath(start, end, clip); completedClip.addPath(clip);
                }
            }
            if (!completedClip.isEmpty()) {
                canvas.save(); canvas.clipPath(completedClip); layout.draw(canvas); canvas.restore();
            }
            if (frame.activeWordIndex >= 0 && frame.activeWordIndex < lyric.words.size()) {
                UltraLyricsState.Word word = lyric.words.get(frame.activeWordIndex);
                highlight(canvas, layout, word.startChar, word.endChar, frame.activeWordProgress);
            }
        }
        canvas.restore();
    }

    private StaticLayout layout(String text, float emphasis) {
        boolean emphasized = emphasis > .5f;
        int size = Math.round(NEIGHBOR_SIZE + (CURRENT_SIZE - NEIGHBOR_SIZE) * emphasis);
        String key = size + (emphasized ? ":current:" : ":neighbor:") + text;
        StaticLayout cached = layouts.get(key);
        if (cached != null) return cached;
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(medium);
        p.setTextSize(size);
        StaticLayout result = StaticLayout.Builder.obtain(text, 0, text.length(), p, TEXT_WIDTH)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false).setLineSpacing(0, LINE_SPACING_MULTIPLIER)
                .setMaxLines(emphasized ? CURRENT_MAX_LINES : 1)
                .setEllipsize(TextUtils.TruncateAt.END).setEllipsizedWidth(TEXT_WIDTH).build();
        layouts.put(key, result);
        return result;
    }

    /** Clip real timestamped word ranges; line timestamps alone never synthesize a word sweep. */
    private void highlight(Canvas canvas, StaticLayout layout, int start, int end, float fraction) {
        if (start >= end || fraction <= 0) return;
        int visibleEnd = visibleEnd(layout);
        start = Math.min(start, visibleEnd);
        end = Math.min(end, visibleEnd);
        if (start >= end) return;
        if (fraction >= 1) {
            clip.reset();
            layout.getSelectionPath(start, end, clip);
            canvas.save(); canvas.clipPath(clip); layout.draw(canvas); canvas.restore();
            return;
        }
        // Mixed-direction timed tokens have no single meaningful sweep direction. Degrade to
        // completion-only emphasis for that token rather than pretending a left-to-right timing.
        boolean rtl = layout.isRtlCharAt(start);
        for (int at = start; at < end; at += Character.charCount(Character.codePointAt(layout.getText(), at))) {
            if (layout.isRtlCharAt(at) != rtl) return;
        }
        // Distribute one word's measured visual width over its actual wrapped fragments.
        float total = 0;
        for (int row = 0; row < layout.getLineCount(); row++) {
            int a = Math.max(start, layout.getLineStart(row)), b = Math.min(end, layout.getLineEnd(row));
            if (a < b) { selectFragment(layout, a, b, row); total += bounds.width(); }
        }
        float remaining = total * fraction;
        for (int row = 0; row < layout.getLineCount() && remaining > 0; row++) {
            int a = Math.max(start, layout.getLineStart(row)), b = Math.min(end, layout.getLineEnd(row));
            if (a >= b) continue;
            selectFragment(layout, a, b, row);
            float width = Math.min(remaining, bounds.width());
            canvas.save(); canvas.clipPath(clip);
            if (rtl) canvas.clipRect(bounds.right - width, bounds.top, bounds.right, bounds.bottom);
            else canvas.clipRect(bounds.left, bounds.top, bounds.left + width, bounds.bottom);
            layout.draw(canvas); canvas.restore();
            remaining -= bounds.width();
        }
    }

    private static int visibleEnd(StaticLayout layout) {
        int last = layout.getLineCount() - 1;
        return layout.getEllipsisCount(last) > 0
                ? layout.getLineStart(last) + layout.getEllipsisStart(last) : layout.getLineEnd(last);
    }

    private void selectFragment(StaticLayout layout, int start, int end, int row) {
        clip.reset(); layout.getSelectionPath(start, end, clip);
        // Selection at a line-end also includes its newline/trailing margin and sometimes the
        // next line's leading margin. Remove these before measuring a wrapped word's width.
        rowClip.reset();
        rowClip.addRect(layout.getLineLeft(row), layout.getLineTop(row), layout.getLineRight(row),
                layout.getLineBottom(row), Path.Direction.CW);
        clip.op(rowClip, Path.Op.INTERSECT);
        clip.computeBounds(bounds, true);
    }

    private void centered(Canvas canvas, String text, float baseline, int size, int color, Typeface face) {
        setPaint(color, size, face);
        canvas.drawText(text, (WIDTH - paint.measureText(text)) / 2, baseline, paint);
    }

    private void label(Canvas canvas, String text, float x, float baseline, float width, int size, int color, Typeface face) {
        setPaint(color, size, face);
        canvas.drawText(TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString(), x, baseline, paint);
    }

    private void setPaint(int color, int size, Typeface face) {
        paint.setShader(null); paint.setStyle(Paint.Style.FILL); paint.setStrokeWidth(1);
        paint.setColor(color); paint.setTypeface(face); paint.setTextSize(size);
    }

    private static int mix(int from, int to, float fraction) {
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int a = (from >>> shift) & 255, b = (to >>> shift) & 255;
            result |= Math.round(a + (b - a) * fraction) << shift;
        }
        return result;
    }

    private static int withOpacity(int color, float opacity) {
        return (color & 0xffffff) | (Math.round((color >>> 24) * Math.max(0, Math.min(1, opacity))) << 24);
    }

    private static String time(long ms) {
        return Math.max(0, ms) / 60000 + ":" + String.format(Locale.ROOT, "%02d", Math.max(0, ms) / 1000 % 60);
    }
}
