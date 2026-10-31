package com.heavendoor.ultralab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict, bounded standard-LRC decoder. No network, Android, or estimated word timing.
 * Unsupported/malformed input rejects the whole document, rather than displaying partial lyrics.
 * Timestamp order is normalized; the last physical occurrence wins at duplicate timestamps.
 * Blank timed entries are boundaries, not display lines. Recognized descriptive metadata is ignored.
 * Offsets are deliberately unsupported except zero, avoiding an ambiguous clock convention.
 * The final entry ends at a known track duration only; without one it is omitted, never assigned
 * an invented duration or held forever. Intermediate entries end at the next explicit timestamp.
 */
public final class UltraLrcParser {
    public static final int MAX_INPUT_CHARS = 65536;
    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,4}):(\\d{2})(?:[.](\\d{1,3}))?\\]");
    private static final Pattern META = Pattern.compile("\\[(ar|ti|al|by|re|ve|length):[^\\]\\r\\n]*\\]");
    private static final Pattern ENHANCED_TIME = Pattern.compile("<\\d{1,4}:\\d{2}(?:[.]\\d+)?[>]");
    private UltraLrcParser() {}

    /** durationMs == 0 means unknown. Negative/over-limit durations fail closed. */
    public static List<UltraLyricsState.Line> parse(String lrc, long durationMs) {
        if (lrc == null || lrc.isEmpty() || lrc.length() > MAX_INPUT_CHARS
                || durationMs < 0 || durationMs > UltraLyricsState.MAX_TIMESTAMP_MS)
            return Collections.emptyList();
        Map<Long, String> entries = new TreeMap<>();
        int count = 0, expandedText = 0;
        String[] rows = lrc.split("\\r?\\n", -1);
        for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
            String row = rows[rowIndex].trim();
            if (rowIndex == 0 && row.startsWith("\ufeff")) row = row.substring(1).trim();
            if (row.isEmpty() || META.matcher(row).matches() || row.equals("[offset:0]")) continue;
            List<Long> times = new ArrayList<>();
            int at = 0;
            Matcher matcher = TIME.matcher(row);
            while (matcher.find(at) && matcher.start() == at) {
                int seconds = Integer.parseInt(matcher.group(2));
                if (seconds >= 60 || ++count > UltraLyricsState.MAX_LINES) return Collections.emptyList();
                long time = Long.parseLong(matcher.group(1)) * 60000 + seconds * 1000L;
                String fraction = matcher.group(3);
                if (fraction != null) time += Integer.parseInt(fraction) * (fraction.length() == 1 ? 100 : fraction.length() == 2 ? 10 : 1);
                if (time >= UltraLyricsState.MAX_TIMESTAMP_MS || durationMs > 0 && time > durationMs)
                    return Collections.emptyList();
                times.add(time);
                at = matcher.end();
            }
            if (times.isEmpty()) return Collections.emptyList();
            String text = row.substring(at).trim();
            // A remaining bracket prefix is usually a malformed timestamp/metadata mixture.
            if (text.startsWith("[") || ENHANCED_TIME.matcher(text).find()
                    || text.length() > UltraLyricsState.MAX_TEXT_CHARS || hasInvalidText(text))
                return Collections.emptyList();
            expandedText += text.length() * times.size();
            if (expandedText > MAX_INPUT_CHARS) return Collections.emptyList();
            for (long time : times) entries.put(time, text);
        }
        List<UltraLyricsState.Line> result = new ArrayList<>();
        Long priorTime = null;
        String priorText = null;
        for (Map.Entry<Long, String> entry : entries.entrySet()) {
            if (priorTime != null && !priorText.isEmpty())
                result.add(new UltraLyricsState.Line(priorTime, entry.getKey(), priorText));
            priorTime = entry.getKey(); priorText = entry.getValue();
        }
        if (priorTime != null && durationMs > priorTime && !priorText.isEmpty())
            result.add(new UltraLyricsState.Line(priorTime, durationMs, priorText));
        return Collections.unmodifiableList(result);
    }

    private static boolean hasInvalidText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c)) return true;
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) return true;
            } else if (Character.isLowSurrogate(c)) return true;
        }
        return false;
    }
}
