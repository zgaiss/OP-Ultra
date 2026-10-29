package com.heavendoor.ultralab;

import static com.heavendoor.ultralab.UltraMusicCardStyle.*;
import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.StaticLayout;
import com.shilapi.xcertplay.iap2.message.RouteGuidanceSummary;
import com.shilapi.xcertplay.iap2.message.RouteTurnIcon;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * Executes production Canvas/StaticLayout code with Robolectric's native graphics backend.
 * Original, synthetic fixtures only. PNGs are JVM test renders, not APK or vehicle screenshots.
 * No Activity, provider, service, vehicle interface or external lyric source is started.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class UltraMusicCardRenderTest {
    private static final int INK = 0xffe4e9ed, MUTED = 0xffb4c0c9;
    private static final int LINE = 0x24d3e5f0, ACCENT = 0xffa9d5c6;
    private static final String TITLE = "沿途微光", ARTIST = "云岸 · 原创测试";
    private static final String CURRENT = "让每一束微光\n陪我驶向明亮的远方";
    private static Map<String, String> sourcesAtStart;

    @BeforeClass public static void recordSourceAndRemovePreviousFixtureOutput() throws Exception {
        sourcesAtStart = sourceHashes();
        File[] previous = outputDirectory().listFiles();
        if (previous != null) for (File file : previous) {
            if (isFixturePng(file) || "PROVENANCE.json".equals(file.getName())) Files.delete(file.toPath());
        }
    }

    @AfterClass public static void bindNativePngsToUnchangedProductionSources() throws Exception {
        Map<String, String> finalSources = sourceHashes();
        assertEquals("Source changed while native fixtures were rendering", sourcesAtStart, finalSources);
        Map<String, String> images = new TreeMap<>();
        File[] outputs = outputDirectory().listFiles();
        if (outputs != null) for (File file : outputs) if (isFixturePng(file)) images.put(file.getName(), sha256(file));
        String json = "{\n  \"engine\":\"Robolectric\",\n  \"engine_version\":\"4.17\",\n"
                + "  \"sdk\":28,\n  \"graphics\":\"NATIVE\",\n"
                + "  \"source_sha256\":" + jsonObject(finalSources) + ",\n"
                + "  \"png_sha256\":" + jsonObject(images) + "\n}\n";
        Files.write(new File(outputDirectory(), "PROVENANCE.json").toPath(), json.getBytes(StandardCharsets.UTF_8));
    }

    @Test public void currentAndTransitionLayoutsUseMediumRatherThanBold() throws Exception {
        UltraMusicCardRenderer renderer = renderer();
        Method layout = UltraMusicCardRenderer.class.getDeclaredMethod("layout", String.class, float.class);
        layout.setAccessible(true);
        for (float emphasis : new float[] {0, .25f, .5f, .75f, 1}) {
            StaticLayout shaped = (StaticLayout) layout.invoke(renderer, CURRENT, emphasis);
            assertEquals(Math.round(NEIGHBOR_SIZE + (CURRENT_SIZE - NEIGHBOR_SIZE) * emphasis),
                    shaped.getPaint().getTextSize(), 0f);
            assertFalse("Lyrics must remain non-bold during transitions", shaped.getPaint().getTypeface().isBold());
            assertEquals(Typeface.NORMAL, shaped.getPaint().getTypeface().getStyle());
            assertEquals("Requested Android sans-serif-medium face", 500, shaped.getPaint().getTypeface().getWeight());
            assertTrue("Chinese fixture must resolve to real glyphs, not tofu", shaped.getPaint().hasGlyph("微"));
            assertTrue(shaped.getPaint().hasGlyph("光"));
            assertTrue(shaped.getLineCount() <= (emphasis > .5f ? CURRENT_MAX_LINES : 1));
        }
        StaticLayout current = (StaticLayout) layout.invoke(renderer, CURRENT, 1f);
        assertEquals(2, current.getLineCount());
        assertEquals(28f, current.getPaint().getTextSize(), 0f);
        assertEquals(TEXT_WIDTH, current.getWidth());
    }

    @Test public void longCurrentLineUsesAtMostTwoLinesAndEllipsizes() throws Exception {
        Method layout = UltraMusicCardRenderer.class.getDeclaredMethod("layout", String.class, float.class);
        layout.setAccessible(true);
        String longText = "把每一段清晨的故事写在路上" + "向着安静明亮的远方慢慢前行" + "把新的心愿留给下一站";
        StaticLayout shaped = (StaticLayout) layout.invoke(renderer(), longText, 1f);
        assertEquals(CURRENT_MAX_LINES, shaped.getLineCount());
        assertTrue(shaped.getEllipsisCount(shaped.getLineCount() - 1) > 0);
        assertFalse(shaped.getPaint().getTypeface().isBold());
    }

    @Test public void unsungNativePixelsUseSeventyPercentAlpha() {
        String text = "尚未唱到的微光";
        UltraLyricsState.Line lyric = new UltraLyricsState.Line(0, 10000, text,
                Collections.singletonList(new UltraLyricsState.Word(5000, 9000, 0, text.length())));
        Bitmap bitmap = drawContents(frame(Collections.singletonList(lyric), 1000), false);
        int maxAlpha = 0;
        for (int y = CURRENT_CENTER_Y - 30; y < CURRENT_CENTER_Y + 30; y++) {
            for (int x = INSET; x < WIDTH - INSET; x++) maxAlpha = Math.max(maxAlpha, bitmap.getPixel(x, y) >>> 24);
        }
        assertEquals("Native Canvas must draw real nonzero glyph pixels at the specified alpha",
                Math.round(255 * UNSUNG_ALPHA_PERCENT / 100f), maxAlpha);
        bitmap.recycle();
    }

    @Test public void wordTimingAndLineTimingProduceDifferentNativePixels() throws Exception {
        UltraLyricsState.Frame timed = frame(timeline(true), 83400);
        UltraLyricsState.Frame lineOnly = frame(timeline(false), 83400);
        assertTrue(timed.wordTimed);
        assertFalse(lineOnly.wordTimed);
        Bitmap word = drawContents(timed, false), lineOnlyBitmap = drawContents(lineOnly, false);
        assertFalse("Only supplied word timing may produce karaoke color", word.sameAs(lineOnlyBitmap));
        save(word, "music-contents-night-word.png");
        save(lineOnlyBitmap, "music-contents-night-line-only.png");
        word.recycle(); lineOnlyBitmap.recycle();
    }

    @Test public void emptyCoverAndLyricsRemainClippedInsideTheCard() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH + 32, HEIGHT + 32, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.translate(16, 16);
        renderer().draw(canvas, null, "", "", 0, 0, frame(Collections.emptyList(), 0),
                INK, MUTED, LINE, ACCENT);
        for (int x = 0; x < bitmap.getWidth(); x++) {
            assertEquals(0, bitmap.getPixel(x, 0));
            assertEquals(0, bitmap.getPixel(x, bitmap.getHeight() - 1));
        }
        for (int y = 0; y < bitmap.getHeight(); y++) {
            assertEquals(0, bitmap.getPixel(0, y));
            assertEquals(0, bitmap.getPixel(bitmap.getWidth() - 1, y));
        }
        save(bitmap, "music-empty-native.png");
        bitmap.recycle();
    }

    @Test public void actualDashboardRendersDayNightAndBothSidesWithoutTrafficSignalFixture() throws Exception {
        for (boolean night : new boolean[] {true, false}) {
            for (boolean swapped : new boolean[] {true, false}) {
                Context context = RuntimeEnvironment.getApplication();
                context.getSharedPreferences("op-ultra-display", 0).edit().clear().commit();
                UltraPreferences.saveDefaultCard(context, 2);
                UltraPreferences.saveThemeMode(context, night ? 2 : 1);
                UltraPreferences.saveCardsSwapped(context, swapped);
                UltraDashboardView view = new UltraDashboardView(context);
                view.media(null, null, 0, 0, false); // Boundary normalization belongs to the production view.
                view.media(TITLE, ARTIST, 83400, 241000, false);
                assertTrue(view.lyrics(view.lyricsGeneration(), timeline(true)));
                view.cover(cover());
                view.map(map(night), 1);
                view.accept(vehicleFixture());
                view.navigation(new RouteGuidanceSummary(true, "测试目的地", 1080L, 12600L,
                        1793260800L, 320L, "右转", "测试道路", RouteTurnIcon.RIGHT));
                Bitmap result = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888);
                view.drawDashboard(new Canvas(result));
                assertEquals(swapped, view.display().getBoolean("cardsSwapped"));
                assertEquals(night ? "night" : "day", view.display().getString("theme"));
                assertEquals(1920, result.getWidth());
                assertEquals(720, result.getHeight());
                String stem = "dashboard-native-" + (night ? "night" : "day") + "-music-" + (swapped ? "left" : "right");
                save(result, stem + ".png");
                Bitmap crop = Bitmap.createBitmap(result, Math.round(UltraCardLayout.infoX(swapped)), 112, WIDTH, HEIGHT);
                save(crop, stem + "-card.png");
                assertTrue("Actual dashboard card must contain detailed text pixels", distinctPixels(crop) > 500);
                crop.recycle(); result.recycle(); view.release();
            }
        }
        Files.write(new File(outputDirectory(), "RENDER-NOTES.txt").toPath(),
                ("Production UltraDashboardView.drawDashboard and UltraMusicCardRenderer.draw executed by "
                        + "Robolectric 4.17, Android API 28, GraphicsMode.NATIVE; native Android Canvas/StaticLayout.\n"
                        + "Original synthetic map, cover, vehicle readings and timed lyrics; no traffic-signal fixture.\n"
                        + "System font family is read from UltraMusicCardStyle, no replacement font or offline text redraw.\n"
                        + "These PNGs are JVM test renders, NOT APK/device/vehicle screenshots or performance validation.\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static UltraMusicCardRenderer renderer() {
        return new UltraMusicCardRenderer(Typeface.create(LYRIC_TYPEFACE_FAMILY, Typeface.NORMAL),
                Typeface.create("sans-serif", Typeface.BOLD));
    }

    private static UltraLyricsState.Frame frame(List<UltraLyricsState.Line> lines, long position) {
        UltraLyricsState state = new UltraLyricsState();
        state.updateMedia("original-fixture", position, 241000, false, 1000);
        assertTrue(state.accept(state.generation(), lines, 1000));
        return state.frame(1000, true);
    }

    private static List<UltraLyricsState.Line> timeline(boolean wordTimed) {
        List<UltraLyricsState.Word> words = new ArrayList<>();
        if (wordTimed) {
            int count = CURRENT.replace("\n", "").length(), word = 0;
            for (int i = 0; i < CURRENT.length(); i++) {
                if (CURRENT.charAt(i) == '\n') continue;
                // Explicit invented timestamps for this original test fixture, never production data.
                long start = 80000 + word * 9000L / count, end = 80000 + (++word) * 9000L / count;
                words.add(new UltraLyricsState.Word(start, end, i, i + 1));
            }
        }
        return Arrays.asList(new UltraLyricsState.Line(72000, 80000, "城市在晨雾中慢慢醒来"),
                new UltraLyricsState.Line(80000, 90000, CURRENT, words),
                new UltraLyricsState.Line(90000, 100000, "把新的心愿留给下一站"));
    }

    private static Bitmap drawContents(UltraLyricsState.Frame frame, boolean day) {
        Bitmap result = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        renderer().draw(new Canvas(result), null, TITLE, ARTIST, frame.positionMs, 241000, frame,
                day ? 0xff26343d : INK, day ? 0xff526676 : MUTED,
                day ? 0x296e8a9f : LINE, day ? 0xff356d62 : ACCENT);
        return result;
    }

    private static Bitmap cover() {
        Bitmap result = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(result); Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 256, 256, 0xffb08c67, 0xff283f50, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 256, 256, p); p.setShader(null); p.setColor(0x80fff6e5);
        c.drawCircle(190, 80, 62, p); p.setColor(0xff273c42); c.drawRect(0, 183, 256, 256, p);
        return result;
    }

    private static Bitmap map(boolean night) {
        Bitmap result = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(result); Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.drawColor(night ? 0xff172226 : 0xffe7eadf);
        p.setColor(night ? 0xff233a32 : 0xffc8e3c5);
        for (int x = 100; x < 1900; x += 330) c.drawRoundRect(x, 140, x + 230, 570, 16, 16, p);
        p.setColor(night ? 0xff53646c : 0xfff9faf7); p.setStrokeWidth(26);
        for (int x = -300; x < 2000; x += 310) c.drawLine(x, 0, x + 350, 720, p);
        for (int y = 120; y < 800; y += 240) c.drawLine(0, y, 1920, y - 220, p);
        p.setColor(0xff3a8ce2); p.setStrokeWidth(10);
        c.drawLine(780, 720, 1040, 390, p); c.drawLine(1040, 390, 1360, 230, p);
        p.setColor(0xff2b81dd); c.drawCircle(1040, 390, 12, p);
        return result;
    }

    private static JSONObject vehicleFixture() throws Exception {
        JSONObject fields = new JSONObject();
        String[] names = {"speed_raw", "gear", "soc", "range_mode", "range_wltp", "trip_since_charge_raw",
                "trip_since_start_raw", "average_consumption_raw"};
        double[] values = {48, 4, 87, 1, 502, 126.4, 7.5, 16.8};
        for (int i = 0; i < names.length; i++) fields.put(names[i],
                new JSONObject().put("raw", values[i]).put("ageMs", 0).put("state", "returned"));
        return new JSONObject().put("schema", 1).put("source", "vehicle").put("uid", 2000)
                .put("connected", true).put("fields", fields).put("fixtureOnly", true);
    }

    private static int distinctPixels(Bitmap bitmap) {
        java.util.HashSet<Integer> colors = new java.util.HashSet<>();
        for (int y = 0; y < bitmap.getHeight(); y += 2)
            for (int x = 0; x < bitmap.getWidth(); x += 2) colors.add(bitmap.getPixel(x, y));
        return colors.size();
    }

    private static File outputDirectory() throws Exception {
        File directory = new File(System.getProperty("opultra.renderOutput", "build/reports/lyrics-renderer"));
        Files.createDirectories(directory.toPath()); return directory;
    }

    private static void save(Bitmap bitmap, String name) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(outputDirectory(), name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
    }

    private static boolean isFixturePng(File file) {
        String name = file.getName();
        return file.isFile() && name.endsWith(".png")
                && (name.startsWith("dashboard-native-") || name.startsWith("music-"));
    }

    private static Map<String, String> sourceHashes() throws Exception {
        File root = new File(System.getProperty("opultra.sourceRoot"));
        Map<String, String> hashes = new TreeMap<>();
        for (String path : new String[] {
                "common/src/main/java/com/heavendoor/ultralab/UltraMusicCardStyle.java",
                "common/src/main/java/com/heavendoor/ultralab/UltraMusicCardRenderer.java",
                "common/src/main/java/com/heavendoor/ultralab/UltraDashboardView.java",
                "common/src/main/java/com/heavendoor/ultralab/UltraLyricsState.java",
                "common/src/test/java/com/heavendoor/ultralab/UltraMusicCardRenderTest.java"}) {
            hashes.put(path, sha256(new File(root, path)));
        }
        return hashes;
    }

    private static String sha256(File file) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }

    private static String jsonObject(Map<String, String> hashes) {
        // Keys are controlled relative source paths / fixture basenames; values are hex digests.
        StringBuilder json = new StringBuilder("{");
        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            if (json.length() > 1) json.append(',');
            json.append('\n').append("    \"").append(entry.getKey()).append("\":\"")
                    .append(entry.getValue()).append('"');
        }
        return json.append("\n  }").toString();
    }
}
