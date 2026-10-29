#!/usr/bin/env python3
"""Offline layout proof, NOT Android Canvas/StaticLayout or a vehicle screenshot.

Reads geometry from UltraMusicCardRenderer.java and uses Pillow/Noto font metrics.
Fixtures below are original demonstration text, not a production lyrics source.
No network access, SDK, vehicle connection, or production assets are needed.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "common/src/main/java/com/heavendoor/ultralab/UltraMusicCardRenderer.java"
ORIGINAL_LINES = [
    "城市在晨雾中慢慢醒来",
    "让每一束微光\n陪我驶向明亮的远方",
    "把新的心愿留给下一站",
    "风沿着车窗\n写下一行晴朗",
    "我们把今天唱成新的篇章",
]
LIMITATION = "离线源码布局预览 · 原创模拟歌词 · 非 APK / 实车截图"
PALETTE = {
    "night": {"base": "#080a0e", "glass": "#152027", "ink": "#e4e9ed", "muted": "#b4c0c9", "line": "#394952", "accent": "#a9d5c6"},
    "day": {"base": "#deebf7", "glass": "#e3edf2", "ink": "#26343d", "muted": "#526676", "line": "#b9cbd4", "accent": "#356d62"},
}


def constants():
    source = SOURCE.read_text()
    values = {}
    for group in re.findall(r"static final int ([^;]+);", source):
        values.update({k: int(v) for k, v in re.findall(r"(\w+)\s*=\s*(\d+)", group)})
    required = ["WIDTH", "HEIGHT", "INSET", "TEXT_WIDTH", "COVER_SIZE", "COVER_TOP", "HEADER_TEXT_X",
                "DIVIDER_Y", "LYRIC_TOP", "LYRIC_BOTTOM", "CURRENT_CENTER_Y", "ROW_GAP", "CURRENT_SIZE",
                "NEIGHBOR_SIZE", "CURRENT_MAX_LINES", "PROGRESS_Y", "TIME_BASELINE"]
    assert all(k in values for k in required), "Renderer geometry changed; update this offline proof."
    assert values["WIDTH"] == 480 and values["HEIGHT"] == 512
    assert values["COVER_SIZE"] == 88
    assert values["CURRENT_SIZE"] == 34 and values["NEIGHBOR_SIZE"] == 22
    assert values["CURRENT_MAX_LINES"] == 2
    assert values["TEXT_WIDTH"] + 2 * values["INSET"] == values["WIDTH"]
    assert values["COVER_TOP"] + values["COVER_SIZE"] < values["DIVIDER_Y"] < values["LYRIC_TOP"]
    assert values["LYRIC_TOP"] < values["LYRIC_BOTTOM"] < values["PROGRESS_Y"] < values["TIME_BASELINE"] < values["HEIGHT"]
    return values, hashlib.sha256(source.encode()).hexdigest()


class Proof:
    def __init__(self, geometry, regular, bold):
        self.g = geometry
        self.paths = regular, bold
        self.fonts = {}
        self.checks = []

    def font(self, size, bold=False):
        key = size, bold
        if key not in self.fonts:
            self.fonts[key] = ImageFont.truetype(str(self.paths[int(bold)]), size, index=2)
        return self.fonts[key]

    def fit(self, text, size, width, bold=False):
        f = self.font(size, bold)
        if f.getlength(text) <= width:
            return text
        while text and f.getlength(text + "…") > width:
            text = text[:-1]
        return text + "…"

    def wrap(self, text, size, width, count, bold=False):
        f = self.font(size, bold)
        rows, row = [], ""
        for char in text:
            if char == "\n":
                rows.append(row)
                row = ""
            elif row and f.getlength(row + char) > width:
                rows.append(row)
                row = char
            else:
                row += char
        rows.append(row)
        if len(rows) > count:
            rows = rows[:count]
            rows[-1] = self.fit(rows[-1] + "…", size, width, bold)
        return rows

    def text(self, image, text, x, baseline, size, color, bold=False):
        ImageDraw.Draw(image).text((x, baseline), text, font=self.font(size, bold), fill=color, anchor="ls")

    def centered(self, image, text, baseline, size, color, bold=False):
        self.text(image, text, (self.g["WIDTH"] - self.font(size, bold).getlength(text)) / 2, baseline, size, color, bold)

    def row(self, layer, text, center_y, active, opacity, palette, highlight=None, check=False):
        g = self.g
        emphasis = float(active)
        emphasized = emphasis > .5
        size = int(g["NEIGHBOR_SIZE"] + (g["CURRENT_SIZE"] - g["NEIGHBOR_SIZE"]) * emphasis + .5)
        rows = self.wrap(text, size, g["TEXT_WIDTH"], g["CURRENT_MAX_LINES"] if emphasized else 1, emphasized)
        f = self.font(size, emphasized)
        boxes = [f.getbbox(row, anchor="ls") for row in rows]
        top = min(box[1] for box in boxes)
        bottom = max(box[3] for box in boxes)
        step = size * 1.16
        height = bottom - top + (len(rows) - 1) * step
        baseline = center_y - height / 2 - top
        draw_layer = Image.new("RGBA", layer.size)
        start = tuple(bytes.fromhex(palette["muted"][1:])) + (255,)
        target = tuple(bytes.fromhex(palette["ink"][1:])) + ((107,) if emphasis > 0 and highlight is not None else (255,))
        color = tuple(round(a + (b - a) * emphasis) for a, b in zip(start, target))
        glyph_bounds = []
        for i, row in enumerate(rows):
            width = f.getlength(row)
            x, y = (g["WIDTH"] - width) / 2, baseline + i * step
            self.text(draw_layer, row, x, y, size, color, emphasized)
            glyph_bounds.append((x, y + boxes[i][1], x + width, y + boxes[i][3]))
        if emphasis > 0 and highlight is not None:
            accented = Image.new("RGBA", layer.size)
            remaining = sum(f.getlength(row) for row in rows) * highlight
            for i, row in enumerate(rows):
                width = f.getlength(row)
                x, y = (g["WIDTH"] - width) / 2, baseline + i * step
                row_image = Image.new("RGBA", layer.size)
                accent = tuple(bytes.fromhex(palette["accent"][1:])) + (round(255 * emphasis),)
                self.text(row_image, row, x, y, size, accent, emphasized)
                cut = int(x + min(width, max(0, remaining)))
                row_image.paste((0, 0, 0, 0), (cut, 0, g["WIDTH"], g["HEIGHT"]))
                accented.alpha_composite(row_image)
                remaining -= width
            draw_layer.alpha_composite(accented)
        if opacity < 1:
            draw_layer.putalpha(draw_layer.getchannel("A").point(lambda a: round(a * opacity)))
        layer.alpha_composite(draw_layer)
        if check:
            assert len(rows) <= (2 if emphasized else 1)
            for left, top, right, bottom in glyph_bounds:
                assert left >= g["INSET"] - 1 and right <= g["WIDTH"] - g["INSET"] + 1
                assert top >= g["LYRIC_TOP"] - 1, f"Static row top clipped: {text}: {top:.1f} < {g['LYRIC_TOP']}"
                assert bottom <= g["LYRIC_BOTTOM"] + 1, f"Static row bottom clipped: {text}"
            self.checks.append({"text": text, "active": active, "rows": rows, "bounds": glyph_bounds})

    def card(self, theme, state="words", current=1, previous=None, progress=1, highlight=.53, transparent=False,
             position_ms=83000, duration_ms=241000):
        g, p = self.g, PALETTE[theme]
        image = Image.new("RGBA", (g["WIDTH"], g["HEIGHT"]), (0, 0, 0, 0) if transparent else p["glass"])
        d = ImageDraw.Draw(image)
        if not transparent:
            d.rounded_rectangle((0, 0, g["WIDTH"] - 1, g["HEIGHT"] - 1), 26, fill=p["glass"], outline=p["line"])
        # Original geometric cover, not an extracted or copyrighted album cover.
        art = (g["INSET"], g["COVER_TOP"], g["INSET"] + g["COVER_SIZE"], g["COVER_TOP"] + g["COVER_SIZE"])
        if state == "empty":
            d.rounded_rectangle(art, 12, fill=p["line"])
            cx, cy = art[0] + 44, art[1] + 44
            d.ellipse((cx - 19, cy - 19, cx + 19, cy + 19), outline=p["muted"], width=2)
            d.ellipse((cx - 4, cy - 4, cx + 4, cy + 4), outline=p["muted"], width=2)
        else:
            d.rounded_rectangle(art, 12, fill="#807e72")
            d.ellipse((art[0] + 43, art[1] + 8, art[0] + 80, art[1] + 45), fill="#c6c5b4")
            d.rectangle((art[0], art[1] + 62, art[2], art[3]), fill="#29434b")
            self.text(image, "ORIGINAL", art[0] + 8, art[1] + 72, 9, "#dbe8ed")
            self.text(image, "DEMO", art[0] + 8, art[1] + 84, 9, "#dbe8ed")
        title = "等待音乐" if state == "empty" else "沿途微光"
        artist = "在 iPhone 播放后显示" if state == "empty" else "云岸 · 原创演示"
        if state == "long":
            title = "一首足够长的原创歌名用于确认顶栏不会越界"
            artist = "原创演示歌手与一段足够长的演出者介绍"
        for label, baseline, size, color, bold in [(title, 58, 28, p["ink"], True), (artist, 91, 19, p["muted"], False)]:
            self.text(image, self.fit(label, size, g["WIDTH"] - g["INSET"] - g["HEADER_TEXT_X"], bold), g["HEADER_TEXT_X"], baseline, size, color, bold)
        d.line((g["INSET"], g["DIVIDER_Y"], g["WIDTH"] - g["INSET"], g["DIVIDER_Y"]), fill=p["line"])
        self.text(image, "歌词", g["INSET"] + 2, 162, 15, p["muted"])
        lyrics = Image.new("RGBA", image.size)
        if state in ("none", "empty"):
            self.centered(lyrics, "等待音乐" if state == "empty" else "暂无歌词", g["CURRENT_CENTER_Y"] - 10, 28, p["ink"], True)
            self.centered(lyrics, "当前来源未提供歌词", g["CURRENT_CENTER_Y"] + 30, 18, p["muted"])
        elif state in ("intro", "gap", "outro"):
            self.centered(lyrics, {"intro": "等待歌词开始", "gap": "间奏", "outro": "歌词已结束"}[state], g["CURRENT_CENTER_Y"], 26, p["muted"])
            next_index = {"intro": 0, "gap": 2, "outro": len(ORIGINAL_LINES)}[state]
            for i, y in ((next_index - 1, g["CURRENT_CENTER_Y"] - g["ROW_GAP"]), (next_index, g["CURRENT_CENTER_Y"] + g["ROW_GAP"])):
                if 0 <= i < len(ORIGINAL_LINES):
                    self.row(lyrics, ORIGINAL_LINES[i], y, False, 1, p, check=True)
        else:
            lines = list(ORIGINAL_LINES)
            if state == "long":
                lines[1] = "这是原创超长歌词排版夹具让我们一起把一整天的微光写进柔软又明亮的道路再慢慢走向远方"
            previous = current if previous is None else previous
            scrolling = previous >= 0 and previous != current and progress < 1
            if not scrolling:
                for i in range(current - 1, current + 2):
                    if 0 <= i < len(lines):
                        self.row(lyrics, lines[i], g["CURRENT_CENTER_Y"] + (i - current) * g["ROW_GAP"], i == current, 1, p,
                                 highlight if state in ("words", "paused") and i == current else None, check=True)
            else:
                for i in range(previous - 1, current + 2):
                    if not 0 <= i < len(lines):
                        continue
                    y = g["CURRENT_CENTER_Y"] + (i - previous - (current - previous) * progress) * g["ROW_GAP"]
                    was, now = abs(i - previous) <= 1, abs(i - current) <= 1
                    opacity = 1 if was and now else 1 - progress if was else progress
                    emphasis = 1 - progress if i == previous else progress if i == current else 0
                    self.row(lyrics, lines[i], y, emphasis, opacity, p, highlight if i == current else None)
        cropped = lyrics.crop((g["INSET"], g["LYRIC_TOP"], g["WIDTH"] - g["INSET"], g["LYRIC_BOTTOM"]))
        image.alpha_composite(cropped, (g["INSET"], g["LYRIC_TOP"]))
        d = ImageDraw.Draw(image)
        d.rounded_rectangle((42, g["PROGRESS_Y"], g["WIDTH"] - 42, g["PROGRESS_Y"] + 3), 2, fill=p["line"])
        if state == "empty":
            position_ms = duration_ms = 0
        if duration_ms > 0:
            d.rounded_rectangle((42, g["PROGRESS_Y"], 42 + (g["WIDTH"] - 84) * min(1, max(0, position_ms / duration_ms)), g["PROGRESS_Y"] + 3), 2, fill=p["accent"])
        clock = lambda ms: f"{max(0, ms) // 60000}:{max(0, ms) // 1000 % 60:02d}"
        self.text(image, clock(position_ms), 42, g["TIME_BASELINE"], 17, p["muted"])
        duration = clock(duration_ms)
        self.text(image, duration, g["WIDTH"] - 42 - self.font(17).getlength(duration), g["TIME_BASELINE"], 17, p["muted"])
        return image

    def caption(self, image, title):
        result = Image.new("RGB", (image.width, image.height + 96), "#0c1218")
        result.paste(image.convert("RGB"), (0, 0))
        self.text(result, title, 18, image.height + 31, 19, "#dce8ed", True)
        self.text(result, "测试数据 / 离线源码布局重建", 18, image.height + 58, 15, "#a5b7c3")
        self.text(result, "原创演示 · 非 APK / 实车截图", 18, image.height + 81, 15, "#a5b7c3")
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--font", type=Path, default=Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"))
    parser.add_argument("--bold-font", type=Path, default=Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"))
    args = parser.parse_args()
    g, source_hash = constants()
    proof = Proof(g, args.font, args.bold_font)
    args.output.mkdir(parents=True, exist_ok=True)
    cases = [("night", "words", "逐字时间戳 · 当前句双行"), ("day", "words", "日间 · 当前句双行"),
             ("night", "none", "生产默认 · 暂无歌词"), ("day", "empty", "无媒体 / 无封面"),
             ("night", "long", "超长歌名与歌词 · 有界省略"), ("day", "line", "普通行时间戳 · 不伪造逐字"),
             ("night", "paused", "暂停 · 歌词高亮位置冻结"), ("day", "intro", "前奏 · 等待歌词开始"),
             ("night", "gap", "间奏 · 保留相邻歌词"), ("day", "outro", "尾奏 · 歌词已结束")]
    tiles = []
    for theme, state, title in cases:
        image = proof.caption(proof.card(theme, state), title)
        name = f"lyrics-{theme}-{state}.png"
        image.save(args.output / name)
        tiles.append(image)
    contact = Image.new("RGB", (g["WIDTH"] * 4, (g["HEIGHT"] + 96) * ((len(cases) + 3) // 4)), "#0c1218")
    for i, tile in enumerate(tiles):
        contact.paste(tile, ((i % 4) * g["WIDTH"], (i // 4) * (g["HEIGHT"] + 96)))
    contact.save(args.output / "lyrics-layout-states.png")
    transitions = []
    for i, t in enumerate((0, .2, .4, .6, .8, 1)):
        eased = t * t * (3 - 2 * t)
        image = proof.caption(proof.card("night", current=2, previous=1, progress=eased, highlight=.1), f"上滚几何 · {round(t * 520)} ms / 520 ms")
        image.save(args.output / f"lyrics-transition-{i}.png")
        transitions.append(image)
    contact = Image.new("RGB", (g["WIDTH"] * 3, (g["HEIGHT"] + 96) * 2), "#0c1218")
    for i, tile in enumerate(transitions):
        contact.paste(tile, ((i % 3) * g["WIDTH"], (i // 3) * (g["HEIGHT"] + 96)))
    contact.save(args.output / "lyrics-transition-geometry.png")
    long_transitions = Image.new("RGB", contact.size, "#0c1218")
    for i, progress in enumerate((.3, .49, .51, .7, .9, 1)):
        tile = proof.caption(proof.card("night", "long", current=2, previous=1, progress=progress),
                             f"超长句上滚 · emphasis {1 - progress:.2f}")
        long_transitions.paste(tile, ((i % 3) * g["WIDTH"], (i // 3) * (g["HEIGHT"] + 96)))
    long_transitions.save(args.output / "lyrics-long-transition-geometry.png")
    report = {"source": str(SOURCE.relative_to(ROOT)), "source_sha256": source_hash, "geometry": g,
              "static_text_checks": proof.checks, "fixtures_original": True,
              "limitation": "Pillow layout approximation, not Android Canvas/StaticLayout or vehicle output. Transition sheet tests geometry only, not model clock cadence.",
              "cases": len(cases), "transition_snapshots": len(transitions), "long_transition_snapshots": 6}
    (args.output / "geometry-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"PASS: source geometry and {len(proof.checks)} static lyric bounds; {len(cases)} states, {len(transitions)} transition snapshots.")
    print("OFFLINE ONLY: not an APK, Android renderer, real lyrics source, or vehicle test.")


if __name__ == "__main__":
    main()
