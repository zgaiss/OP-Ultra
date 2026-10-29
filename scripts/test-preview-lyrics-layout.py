#!/usr/bin/env python3
"""Regression checks for single-source preview wiring; not Android rendering tests."""
import importlib.util
from pathlib import Path
import unittest

from PIL import Image

MODULE_PATH = Path(__file__).with_name("preview-lyrics-layout.py")
spec = importlib.util.spec_from_file_location("lyrics_preview", MODULE_PATH)
preview = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preview)
native_spec = importlib.util.spec_from_file_location("native_labels", Path(__file__).with_name("label-native-lyrics-previews.py"))
native = importlib.util.module_from_spec(native_spec)
native_spec.loader.exec_module(native)
REGULAR = Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc")
BOLD = Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc")


class PreviewWiringTest(unittest.TestCase):
    def setUp(self):
        self.style = preview.parse_style(preview.STYLE.read_text())
        self.renderer = preview.SOURCE.read_text()
        self.dashboard = preview.DASHBOARD.read_text()

    def test_simple_style_literals_are_the_only_parameter_source(self):
        self.assertEqual({"A": 12, "B": 24, "GAP": 1.2, "FACE": "sans-serif-medium"},
                         preview.parse_style('static final int A = 12, B = 24; static final float GAP = 1.2f; '
                                             'static final String FACE = "sans-serif-medium";'))
        with self.assertRaises(AssertionError):
            preview.parse_style("static final int A = 12; static final int A = 14;")
        with self.assertRaises(AssertionError):
            preview.parse_style("static final int A = 12 + 2;")

    def test_approved_style_and_real_renderer_wiring_match(self):
        values, renderer_hash = preview.constants()
        self.assertEqual(28, values["CURRENT_SIZE"])
        self.assertEqual(22, values["NEIGHBOR_SIZE"])
        self.assertEqual(26, values["TITLE_SIZE"])
        self.assertEqual(70, values["UNSUNG_ALPHA_PERCENT"])
        self.assertEqual("sans-serif-medium", values["LYRIC_TYPEFACE_FAMILY"])
        self.assertEqual(64, len(renderer_hash))

    def test_renderer_cannot_shadow_a_style_constant(self):
        with self.assertRaises(AssertionError):
            preview.validate_source_wiring(self.style, self.renderer + "\nstatic final int CURRENT_SIZE = 34;", self.dashboard)

    def test_bold_lyrics_cannot_silently_return(self):
        with self.assertRaises(AssertionError):
            preview.validate_source_wiring(self.style, self.renderer.replace("p.setTypeface(medium);", "p.setTypeface(bold);"), self.dashboard)

    def test_hardcoded_opacity_and_line_spacing_are_rejected(self):
        for before, after in (("UNSUNG_ALPHA_PERCENT / 100f", ".42f"), ("LINE_SPACING_MULTIPLIER)", "1.16f)")):
            changed = self.renderer.replace(before, after)
            self.assertNotEqual(changed, self.renderer)
            with self.assertRaises(AssertionError):
                preview.validate_source_wiring(self.style, changed, self.dashboard)

    def test_lyrics_heading_must_stay_removed(self):
        with self.assertRaises(AssertionError):
            preview.validate_source_wiring(self.style, self.renderer + '\nlabel(canvas,"歌词",0,0,0,0,0,medium);', self.dashboard)

    def test_dashboard_must_supply_the_style_typeface(self):
        with self.assertRaises(AssertionError):
            preview.validate_source_wiring(self.style, self.renderer,
                                           self.dashboard.replace("UltraMusicCardStyle.LYRIC_TYPEFACE_FAMILY", '"sans-serif"'))

    @unittest.skipUnless(REGULAR.is_file() and BOLD.is_file(), "Noto CJK fonts required for Pillow-only checks")
    def test_lyrics_never_request_the_bold_fallback(self):
        class RecordedProof(preview.Proof):
            def font(self, size, bold=False):
                self.requests.append((size, bold))
                return super().font(size, bold)

        proof = RecordedProof(self.style, REGULAR, BOLD)
        proof.requests = []
        for emphasis in (0, .25, .5, .75, 1):
            proof.row(Image.new("RGBA", (480, 512)), "原创测试歌词", 280, emphasis, 1,
                      preview.PALETTE["night"], highlight=.5)
        self.assertTrue(proof.requests)
        self.assertTrue(all(not bold for _, bold in proof.requests))
        self.assertIn((28, False), proof.requests)
        self.assertIn((22, False), proof.requests)

    @unittest.skipUnless(REGULAR.is_file() and BOLD.is_file(), "Noto CJK fonts required for Pillow-only checks")
    def test_unsung_alpha_uses_the_production_style(self):
        proof = preview.Proof(self.style, REGULAR, BOLD)
        image = Image.new("RGBA", (480, 512))
        proof.row(image, "测试歌词", 280, 1, 1, preview.PALETTE["night"], highlight=0)
        self.assertEqual(int(255 * self.style["UNSUNG_ALPHA_PERCENT"] / 100 + .5), image.getchannel("A").getextrema()[1])

    @unittest.skipUnless(REGULAR.is_file(), "Noto CJK font required only for outside-the-image captions")
    def test_native_labels_preserve_every_rgba_byte(self):
        # Non-opaque input catches accidental compositing; unequal RGB with equal
        # alpha must not be hidden by an alpha-only image comparison.
        original = Image.frombytes("RGBA", (480, 512), bytes(range(256)) * (480 * 512 * 4 // 256))
        metadata = {"source_sha256": {native.STYLE: "a" * 64, native.RENDERER: "b" * 64}}
        labeled = native.label_image(original, "测试", "card", metadata, "c" * 40, False, REGULAR)
        self.assertEqual(original.tobytes(), labeled.crop((0, 0, 480, 512)).tobytes())
        self.assertGreater(labeled.height, original.height)

    @unittest.skipUnless(REGULAR.is_file(), "Noto CJK font required only for outside-the-image captions")
    def test_native_dashboard_labels_remain_outside_the_canvas(self):
        original = Image.new("RGBA", (1920, 720), (11, 35, 78, 255))
        metadata = {"source_sha256": {native.STYLE: "a" * 64, native.RENDERER: "b" * 64}}
        labeled = native.label_image(original, "夜间 · 音乐在左", "dashboard", metadata, "c" * 40, False, REGULAR)
        self.assertEqual(original.tobytes(), labeled.crop((0, 0, 1920, 720)).tobytes())


if __name__ == "__main__":
    unittest.main()
