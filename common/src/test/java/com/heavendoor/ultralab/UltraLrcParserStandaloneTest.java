package com.heavendoor.ultralab;

import java.util.List;

/** Original synthetic text only; no device/source compatibility claim. */
public final class UltraLrcParserStandaloneTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static List<UltraLyricsState.Line> parse(String value, long duration) {
        return UltraLrcParser.parse(value, duration);
    }
    private static void rejects(String value) { check(parse(value, 10000).isEmpty(), "must reject: " + (value == null ? "null" : value.substring(0, Math.min(value.length(), 30)))); }
    public static void main(String[] args) {
        List<UltraLyricsState.Line> lines = parse("[00:01.2]first\n[00:02.34]second\n[00:03.456]third", 5000);
        check(lines.size() == 3, "three entries");
        check(lines.get(0).startMs == 1200 && lines.get(0).endMs == 2340, "tenths/hundredths");
        check(lines.get(1).endMs == 3456 && lines.get(2).endMs == 5000, "milliseconds/final duration");
        for (UltraLyricsState.Line line : lines) check(line.words.isEmpty(), "no synthetic word timing");
        UltraLyricsState state = new UltraLyricsState();
        state.updateMedia("fixture", 0, 5000, false, 0);
        check(state.accept(state.generation(), lines, 0), "model accepts parser contract");
        check(parse("[00:01]only", 0).isEmpty(), "unknown final omitted");
        lines = parse("[00:01]one\n[00:03]two", 0);
        check(lines.size() == 1 && lines.get(0).endMs == 3000, "explicit next boundary retained");
        check(parse("[00:01]at end", 1000).isEmpty(), "no zero duration line");
        lines = parse("[00:04]later\n[00:01][00:02]repeat\n[00:01]replacement", 6000);
        check(lines.size() == 3 && lines.get(0).text.equals("replacement") && lines.get(1).text.equals("repeat"), "sort and last duplicate wins");
        lines = parse("[00:00]one\n[00:01]\n[00:02]three", 3000);
        check(lines.size() == 2 && lines.get(0).endMs == 1000 && lines.get(1).startMs == 2000, "blank boundaries leave gaps");
        lines = parse("\ufeff[ar:Synthetic]\r\n[ti:Fixture]\n[offset:0]\n[00:00] 雲 🌤 \n[00:01]", 0);
        check(lines.size() == 1 && lines.get(0).text.equals("雲 🌤"), "BOM CRLF metadata and Unicode");
        rejects(null); rejects(""); rejects("unanchored text"); rejects("[00:60]bad"); rejects("[00:00.1234]bad");
        rejects("[offset:10]\n[00:00]bad"); rejects("[offset:-10]\n[00:00]bad");
        rejects("[00:01][00:xx]bad"); rejects("[00:00]one\n[broken]two");
        rejects("[00:01]<00:01.23>word"); rejects("[00:00]bad\u0001text"); rejects("[00:00]bad\ud800");
        rejects("[00:00]" + "x".repeat(513)); rejects("x".repeat(65537)); rejects("[00:00]x\n".repeat(513));
        check(parse("[00:00]a", -1).isEmpty(), "negative duration rejected");
        check(parse("[00:00]a", UltraLyricsState.MAX_TIMESTAMP_MS + 1).isEmpty(), "huge duration rejected");
        check(parse("[1440:00]a", 0).isEmpty(), "max timestamp rejected");
        check(parse("[00:04]a", 3000).isEmpty(), "time past known duration rejected");
        String repeated = "[00:00]".repeat(129) + "x".repeat(512);
        rejects(repeated);
        lines = parse("[00:00]a", 1000);
        try { lines.clear(); throw new AssertionError("mutable result"); } catch (UnsupportedOperationException expected) { checks++; }
        for (int i = 0; i < 512; i++) {
            String value = "[00:00]" + "x".repeat(i + 1);
            check(parse(value, 1000).size() == 1, "accepted text bound " + i);
        }
        // Deterministic replay-style contracts with out-of-order and duplicate observations.
        java.util.Random random = new java.util.Random(20261031L);
        for (int run = 0; run < 100; run++) {
            StringBuilder fixture = new StringBuilder();
            for (int i = 0; i < 50; i++) {
                int second = random.nextInt(60);
                fixture.append(String.format(java.util.Locale.ROOT, "[00:%02d]", second));
                if (random.nextBoolean()) fixture.append("fixture ").append(i);
                fixture.append('\n');
            }
            lines = parse(fixture.toString(), run % 2 == 0 ? 60000 : 0);
            state.updateMedia("replay-" + run, 0, 60000, false, run);
            check(state.accept(state.generation(), lines, run), "normalized replay accepted " + run);
            long end = -1;
            for (UltraLyricsState.Line line : lines) {
                check(line.startMs >= end && line.endMs > line.startMs && line.endMs <= 60000
                        && line.words.isEmpty(), "ordered bounded line-only contract");
                end = line.endMs;
            }
        }
        System.out.println("UltraLrcParser: " + checks + " checks passed");
    }
}
