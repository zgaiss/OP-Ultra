#!/usr/bin/env python3
"""Label native Canvas test PNGs outside their original pixels; never redraw UI text.

Requires PROVENANCE.json from UltraMusicCardRenderTest. The source and PNG hashes
must still match the producer's manifest. Final output requires a clean Git tree.
This script does not run an APK or connect to a vehicle.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "common/src/main/java/com/heavendoor/ultralab/"
STYLE = PACKAGE + "UltraMusicCardStyle.java"
RENDERER = PACKAGE + "UltraMusicCardRenderer.java"
DASHBOARD = PACKAGE + "UltraDashboardView.java"
TEST = "common/src/test/java/com/heavendoor/ultralab/UltraMusicCardRenderTest.java"
SELECTION = (
    ("dashboard-native-night-music-left-card.png", "lyrics-night-words.png", "音乐卡近景", "card"),
    ("dashboard-native-night-music-left.png", "lyrics-night-left-words-full.png", "夜间 · 音乐在左 / 车况在右", "dashboard"),
    ("dashboard-native-day-music-left.png", "lyrics-day-left-words-full.png", "日间 · 音乐在左 / 车况在右", "dashboard"),
)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def verify_provenance(directory):
    manifest = json.loads((directory / "PROVENANCE.json").read_text())
    assert manifest["engine"] == "Robolectric" and manifest["engine_version"] == "4.17"
    assert manifest["sdk"] == 28 and manifest["graphics"] == "NATIVE"
    sources = manifest["source_sha256"]
    assert all(name in sources for name in (STYLE, RENDERER, DASHBOARD, TEST)), "Producer source hashes are incomplete"
    for name, expected in sources.items():
        path = (ROOT / name).resolve()
        assert path.is_relative_to(ROOT) and path.is_file(), "Unexpected producer source path"
        assert sha256(path) == expected, f"Native PNGs no longer match source: {name}. Rerun the native render test."
    for name, _, _, _ in SELECTION:
        assert sha256(directory / name) == manifest["png_sha256"][name], f"Producer PNG was changed: {name}"
    return manifest


def label_image(original, title, kind, provenance, commit, dirty, font_path):
    """Return a taller image with an unchanged top rectangle and a separate footer."""
    original = original.convert("RGBA")
    footer = 124 if kind == "card" else 132
    result = Image.new("RGBA", (original.width, original.height + footer), "#0c1218")
    result.paste(original, (0, 0))
    draw = ImageDraw.Draw(result)

    def text(value, offset, size, color="#a5b7c3"):
        # This font is only for the out-of-canvas explanation. UI pixels are never redrawn.
        font = ImageFont.truetype(str(font_path), size, index=2)
        while font.getlength(value) > result.width - 36 and size > 9:
            size -= 1
            font = ImageFont.truetype(str(font_path), size, index=2)
        assert font.getlength(value) <= result.width - 36, "Caption does not fit"
        draw.text((18 if kind == "card" else 40, original.height + offset), value,
                  font=font, fill=color, anchor="ls")

    source = provenance["source_sha256"]
    revision = f"commit {commit[:8]}{' +dirty' if dirty else ''} · style {source[STYLE][:8]} · render {source[RENDERER][:8]}"
    if kind == "card":
        text(title, 28, 19, "#dce8ed")
        text("生产代码 · 原生 Canvas 测试渲染", 53, 14)
        text("Robolectric 4.17 / API 28 · 非 APK / 实车截图", 77, 12)
        text("原创测试数据 · 图内像素未重画", 98, 12)
        text(revision, 117, 10)
    else:
        text(title + " · 生产代码原生 Canvas 测试渲染", 34, 27, "#dce8ed")
        text("原创测试地图 / 歌词 / 车况 · 无独立红绿灯 · 画面像素原样保留", 66, 20)
        text("Robolectric 4.17 / Android API 28 · 非 APK / 实车截图 · " + revision, 99, 18)
        text("构建用源码和此测试渲染使用同一 UltraMusicCardStyle；字体与中文均由生产 Canvas / StaticLayout 绘制。", 124, 16)
    assert result.crop((0, 0, original.width, original.height)).tobytes() == original.tobytes()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=ROOT / "common/build/reports/lyrics-renderer")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--font", type=Path, default=Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"))
    parser.add_argument("--expected-commit", help="Exact full commit required for final delivery")
    parser.add_argument("--allow-dirty", action="store_true", help="Internal review only; marks the caption +dirty")
    args = parser.parse_args()
    directory, output = args.input.resolve(), args.output.resolve()
    assert directory != output, "Do not overwrite producer PNGs"
    commit, tree, status = git("rev-parse", "HEAD"), git("rev-parse", "HEAD^{tree}"), git("status", "--porcelain")
    assert args.allow_dirty or args.expected_commit, "Final delivery requires --expected-commit with the reviewed full commit"
    assert not args.expected_commit or commit == args.expected_commit, "Checkout commit changed"
    assert args.allow_dirty or not status, "Commit the final source before making deliverable previews"
    manifest = verify_provenance(directory)
    output.mkdir(parents=True, exist_ok=True)
    raw = output / "raw"
    raw.mkdir(exist_ok=True)
    records = []
    for name, filename, title, kind in SELECTION:
        if kind == "card":
            style_text = (ROOT / STYLE).read_text()
            size = re.search(r"\bCURRENT_SIZE\s*=\s*(\d+)", style_text).group(1)
            family = re.search(r'\bLYRIC_TYPEFACE_FAMILY\s*=\s*"([^\"]+)"', style_text).group(1)
            title = f"当前歌词 {size} px · {family}"
        original = Image.open(directory / name).convert("RGBA")
        assert original.size == ((480, 512) if kind == "card" else (1920, 720))
        image = label_image(original, title, kind, manifest, commit, bool(status), args.font)
        destination = output / filename
        image.save(destination)
        # Reopen exported bytes, not just the in-memory canvas, to verify unchanged content.
        decoded = Image.open(destination).convert("RGBA")
        unchanged = decoded.crop((0, 0, original.width, original.height))
        assert unchanged.tobytes() == original.tobytes(), "Export changed production UI pixels"
        shutil.copyfile(directory / name, raw / name)
        assert sha256(raw / name) == manifest["png_sha256"][name]
        records.append({"filename": filename, "sha256": sha256(destination), "producer_png": name,
                        "producer_png_sha256": manifest["png_sha256"][name],
                        "original_pixel_sha256": hashlib.sha256(original.tobytes()).hexdigest(),
                        "original_pixel_rectangle": [0, 0, original.width, original.height], "unchanged_pixels": True})
    assert manifest == verify_provenance(directory), "Source or render changed while annotating"
    assert commit == git("rev-parse", "HEAD") and status == git("status", "--porcelain"), "Checkout changed while annotating"
    report = {"commit": commit, "tree": tree, "working_tree_dirty": bool(status), "producer": manifest,
              "annotation_script_sha256": sha256(Path(__file__).resolve()), "outputs": records,
              "limitation": "Production native Canvas/StaticLayout in Robolectric JVM tests, not APK/device/vehicle screenshots or performance validation."}
    (output / "native-preview-manifest.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"PASS: {len(records)} native renders labeled; all original UI pixels preserved exactly.")
    print(f"Source commit: {commit}; tree: {tree}; dirty: {bool(status)}")


if __name__ == "__main__":
    main()
