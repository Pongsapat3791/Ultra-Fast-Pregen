#!/usr/bin/env python3
"""Draw the benchmark chart from results.csv (written by bench.py).

    python chart.py                                   # results-26.1.2.csv next to this script
    python chart.py results-1.21.1.csv --out some/dir
    python chart.py --cpu "AMD Ryzen 9 9950X3D (16C/32T)" --ram "64 GB" --os "Windows 11 Pro"

Writes chart.svg, plus chart-c2me.svg / chart-c2me-nodfc.svg when the CSV has C2ME rows
(<config>-c2me / <config>-c2me-nodfc, i.e. density function compiler on / off), and chart.html,
which embeds the charts and a table of the numbers. Every bar is the average chunks per second
of that configuration's runs. Python 3.8+, standard library only.
"""
import argparse
import ast
import csv
import math
import os
import platform
import sys
from xml.sax.saxutils import escape

HERE = os.path.dirname(os.path.abspath(__file__))
NBSP = "\u00a0"  # no-break space: keeps a mod name and its version on one line

# Colors (light background, so the image reads on a mod page).
BG = "#ffffff"
PANEL_BG = "#f6f7f8"
INK = "#1f2328"
INK_2 = "#57606a"
INK_3 = "#8c959f"
GRID = "#e3e6e9"
BASELINE = "#c4c9ce"
TOOLS = (("chunky", "Vanilla", "#7f8790"), ("ufp", "Ultra Fast Pregen", "#2e8b7a"))
# With C2ME both bars run it: Chunky drives C2ME's generation, as in Bye?Pregen's C2ME chart.
C2ME_TOOLS = (("chunky", "C2ME", "#7f8790"), ("ufp", "C2ME + Ultra Fast Pregen", "#2e8b7a"))
WORLDS = (("vanilla", "Vanilla worldgen"), ("tw", "Tectonic + WWOO"))
# One chart per C2ME variant: (variant, config suffix, file, title, panel label suffix, C2ME's DFC)
VARIANTS = (("", "", "chart.svg", "Chunk pregeneration speed", "", None),
            ("c2me", "-c2me", "chart-c2me.svg", "Chunk pregeneration speed with C2ME", " + C2ME", "on"),
            ("nodfc", "-c2me-nodfc", "chart-c2me-nodfc.svg", "Chunk pregeneration speed with C2ME (DFC off)",
             " + C2ME (DFC off)", "off"))
FONT = "system-ui, -apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif"

# Fallbacks when bench.py cannot be read; bench.py is the source of truth.
DEFAULT_MC = "26.1.2"
DEFAULT_NEOFORGE = {"26.1.2": "26.1.2.122", "1.21.1": "21.1.259"}
DIMENSION_NAMES = {"overworld": "the Overworld", "the_nether": "the Nether", "the_end": "the End"}
MOD_NAMES = {"ufp": "Ultra Fast Pregen", "chunky": "Chunky", "c2me": "C2ME", "tectonic": "Tectonic",
             "lithostitched": "Lithostitched", "wwoo": "WWOO", "cristellib": "Cristel Lib",
             "cloth": "Cloth Config"}


# ---------------------------------------------------------------- inputs

def bench_constants(mc):
    """MC, NEOFORGE, UFP_JAR and MODS of that Minecraft version from bench.py's VERSIONS, read without running it."""
    found = {"MC": mc, "NEOFORGE": DEFAULT_NEOFORGE.get(mc, "?"),
             "UFP_JAR": f"ultrafastpregen-neoforge-{mc}-15.1.jar", "MODS": {}}
    path = os.path.join(HERE, "bench.py")
    try:
        with open(path, encoding="utf-8") as f:
            tree = ast.parse(f.read(), path)
        for node in tree.body:
            if (isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name)
                    and node.targets[0].id == "VERSIONS"):
                # MODRINTH + "..." in the URLs: only the jar names are needed.
                versions = {}
                for key, value in zip(node.value.keys, node.value.values):
                    entry = {}
                    for k, v in zip(value.keys, value.values):
                        name = ast.literal_eval(k)
                        if name == "mods":
                            entry[name] = {ast.literal_eval(mk): (ast.literal_eval(mv.elts[0]), "")
                                           for mk, mv in zip(v.keys, v.values)}
                        else:
                            entry[name] = ast.literal_eval(v)
                    versions[ast.literal_eval(key)] = entry
                if mc not in versions:
                    print(f"warning: bench.py has no Minecraft {mc}", file=sys.stderr)
                else:
                    v = versions[mc]
                    found.update(NEOFORGE=v["neoforge"], UFP_JAR=v["ufp"], MODS=v["mods"])
    except (OSError, SyntaxError, ValueError, AttributeError, IndexError, KeyError) as failure:
        print(f"warning: could not read mod versions from bench.py ({failure})", file=sys.stderr)
    return found


def results_version(path, rows_mc):
    """Minecraft version of a results file: its mc column, else results-<version>.csv, else the default."""
    if rows_mc:
        if len(rows_mc) > 1:
            print(f"warning: {path} mixes Minecraft versions {', '.join(sorted(rows_mc))}", file=sys.stderr)
        return sorted(rows_mc)[0]
    stem = os.path.splitext(os.path.basename(path))[0]
    for dim in ("-the_nether", "-the_end"):
        stem = stem.replace(dim, "")
    return stem[len("results-"):] if stem.startswith("results-") else "1.21.1"


def jar_version(jar):
    """'tectonic-3.0.28-neoforge-21.1.jar' -> '3.0.28', 'ultrafastpregen-neoforge-26.1.2-26.2-15.1.jar' -> '15.1';
    '' when no version is found."""
    stem = jar[:-4] if jar.lower().endswith(".jar") else jar
    parts = []
    for token in stem.split("-"):
        low = token.lower()
        if low in ("neoforge", "forge", "fabric", "quilt", "1.21", "1.21.1", "21.1", "26.1.2", "26.1", "26.2") or (
                low.startswith("mc") and low[2:3].isdigit()):
            continue
        parts.append(token)
    for i, token in enumerate(parts):
        if token[:1].isdigit():
            return "-".join(parts[i:])
    return ""


def mod_versions(constants):
    jars = {key: name for key, (name, _url) in constants["MODS"].items()}
    jars["ufp"] = constants["UFP_JAR"]
    order = ["ufp"] + [key for key in constants["MODS"]]
    return order, {key: jar_version(jar) for key, jar in jars.items()}


def read_rows(path, radius=None):
    rows = []
    with open(path, newline="", encoding="utf-8-sig") as f:
        for line, row in enumerate(csv.DictReader(f), start=2):
            name = (row.get("config") or "").strip()
            base, variant = name, ""
            for candidate, suffix, *_ in VARIANTS[::-1]:  # longest suffix first
                if suffix and name.endswith(suffix):
                    base, variant = name[:-len(suffix)], candidate
                    break
            parts = base.split("-")
            if len(parts) != 2 or parts[0] not in dict(WORLDS) or parts[1] not in dict((t[0], t) for t in TOOLS):
                print(f"warning: line {line}: unknown config {name!r}, skipped", file=sys.stderr)
                continue
            try:
                cps = float(row["cps"])
                run_radius = int(float(row.get("radius") or 0))
                chunks = int(float(row.get("chunks") or 0))
            except (TypeError, ValueError):
                print(f"warning: line {line}: unreadable numbers, skipped", file=sys.stderr)
                continue
            if cps <= 0:
                print(f"warning: line {line}: {name} has {cps} chunks/s, skipped", file=sys.stderr)
                continue
            if radius is not None and run_radius != radius:
                continue
            rows.append({"world": parts[0], "tool": parts[1], "variant": variant, "cps": cps, "radius": run_radius,
                         "chunks": chunks, "mods": (row.get("mods") or "").split(), "mc": (row.get("mc") or "").strip(),
                         "dimension": (row.get("dimension") or "overworld").strip()})
    return rows


def default_cpu():
    name = ""
    try:
        if platform.system() == "Windows":
            import winreg
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"HARDWARE\DESCRIPTION\System\CentralProcessor\0") as key:
                name = winreg.QueryValueEx(key, "ProcessorNameString")[0]
        elif os.path.exists("/proc/cpuinfo"):
            with open("/proc/cpuinfo", encoding="utf-8", errors="replace") as f:
                for line in f:
                    if line.lower().startswith("model name"):
                        name = line.split(":", 1)[1]
                        break
    except Exception:
        name = ""
    name = " ".join(name.split()) or platform.processor() or platform.machine() or "unknown CPU"
    return f"{name} ({os.cpu_count()} threads)" if os.cpu_count() else name


def default_ram():
    try:
        if platform.system() == "Windows":
            import ctypes

            class MemoryStatus(ctypes.Structure):
                _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong),
                            ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                            ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
                            ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
                            ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]

            status = MemoryStatus()
            status.dwLength = ctypes.sizeof(MemoryStatus)
            if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
                return ""
            total = status.ullTotalPhys
        else:
            total = os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES")
        return f"{round(total / 1024 ** 3)} GB"
    except Exception:
        return ""


def default_os():
    system = platform.system()
    if system == "Linux":
        try:
            with open("/etc/os-release", encoding="utf-8") as f:
                for line in f:
                    if line.startswith("PRETTY_NAME="):
                        return line.split("=", 1)[1].strip().strip('"')
        except OSError:
            pass
    if system in ("Windows", "Darwin") and platform.release():
        return f"{'macOS' if system == 'Darwin' else system} {platform.release()}"
    return platform.platform(terse=True) or system or "unknown OS"


# ---------------------------------------------------------------- numbers

def average(values):
    return sum(values) / len(values) if values else None


def nice_ticks(top_value, target=5):
    """Clean tick values 0..top with top >= top_value."""
    if top_value <= 0:
        top_value = 1.0
    raw = top_value / target
    magnitude = 10 ** math.floor(math.log10(raw))
    step = magnitude * 10
    for m in (1, 2, 2.5, 5, 10):
        if m * magnitude * target >= top_value:
            step = m * magnitude
            break
    count = int(math.ceil(top_value / step - 1e-9))
    return [round(i * step, 10) for i in range(count + 1)]


def tick_label(value, ticks):
    if all(abs(t - round(t)) < 1e-9 for t in ticks):
        return f"{int(round(value)):,}"
    return f"{value:,.1f}"


def thousands(values):
    return ", ".join(f"{v:,}" for v in sorted(values))


def wrap(text, max_width, size):
    """Greedy word wrap by an estimated glyph width (no font metrics in pure SVG).
    Words joined by a no-break space (U+00A0) stay on one line."""
    limit = max(10, int(max_width / (size * 0.53)))
    lines, current = [], ""
    for word in text.split(" "):
        candidate = word if not current else current + " " + word
        if len(candidate) > limit and current:
            lines.append(current)
            current = word
        else:
            current = candidate
    if current:
        lines.append(current)
    return lines


def text_width(text, size, bold=False):
    return len(text) * size * (0.6 if bold else 0.56)


# ---------------------------------------------------------------- drawing

def svg_text(x, y, text, size=13, fill=INK, anchor="start", weight="normal", halo=None):
    """halo: a background-colored outline drawn under the glyphs so gridlines do not cut the text."""
    outline = f' stroke="{halo}" stroke-width="4" stroke-linejoin="round" paint-order="stroke"' if halo else ""
    return (f'<text x="{x:.1f}" y="{y:.1f}" font-size="{size}" fill="{fill}" text-anchor="{anchor}"'
            f' font-weight="{weight}"{outline}>{escape(text)}</text>')


def bar_path(x, y, width, height, radius=4):
    """Column with a rounded top and a square foot on the baseline."""
    r = min(radius, width / 2, height)
    bottom = y + height
    return (f"M{x:.1f},{bottom:.1f} V{y + r:.1f} Q{x:.1f},{y:.1f} {x + r:.1f},{y:.1f} "
            f"H{x + width - r:.1f} Q{x + width:.1f},{y:.1f} {x + width:.1f},{y + r:.1f} V{bottom:.1f} Z")


def build_chart(rows, title, footnotes, id_prefix, panel_suffix="", tools=TOOLS):
    """Return (svg markup, table rows) for one chart; None when no panel has data."""
    panels = []
    for world, label in WORLDS:
        bars = []
        for tool, tool_label, color in tools:
            values = [r["cps"] for r in rows if r["world"] == world and r["tool"] == tool]
            bars.append({"tool": tool, "label": tool_label, "color": color, "avg": average(values), "runs": len(values)})
        if any(b["avg"] is not None for b in bars):
            panels.append({"label": label + panel_suffix, "bars": bars})
    if not panels:
        return None, []

    width, margin, panel_gap = 960, 32, 40
    content = width - 2 * margin
    panel_w = min(460.0, (content - panel_gap * (len(panels) - 1)) / len(panels))
    group_w = panel_w * len(panels) + panel_gap * (len(panels) - 1)
    first_x = margin + (content - group_w) / 2

    out = []
    title_y, subtitle_y, legend_y = 44, 68, 98
    out.append(svg_text(margin, title_y, title, size=22, weight="700"))
    out.append(svg_text(margin, subtitle_y, "Bar height = chunks per second (higher is better) | independent y-axes",
                        size=13, fill=INK_2))
    x = margin
    for _tool, tool_label, color in tools:
        out.append(f'<rect x="{x:.1f}" y="{legend_y - 11}" width="12" height="12" rx="2" fill="{color}"/>')
        out.append(svg_text(x + 18, legend_y, tool_label, size=13, fill=INK))
        x += 18 + text_width(tool_label, 13) + 24

    panel_top = 120
    header_h, headroom, plot_h = 34, 58, 250
    plot_top = panel_top + header_h + headroom
    baseline = plot_top + plot_h
    panel_bottom = baseline + 44
    axis_w, inner_pad, bar_w = 46, 16, 64
    table = []

    for i, panel in enumerate(panels):
        px = first_x + i * (panel_w + panel_gap)
        out.append(f'<rect x="{px:.1f}" y="{panel_top}" width="{panel_w:.1f}" height="{panel_bottom - panel_top}"'
                   f' rx="8" fill="{PANEL_BG}"/>')
        out.append(svg_text(px + inner_pad, panel_top + 24, panel["label"], size=15, weight="600"))
        plot_x0 = px + inner_pad + axis_w
        plot_x1 = px + panel_w - inner_pad
        averages = [b["avg"] for b in panel["bars"] if b["avg"] is not None]
        ticks = nice_ticks(max(averages))
        top = ticks[-1]

        def y_of(value):
            return baseline - plot_h * value / top

        for t in ticks:
            y = y_of(t)
            color = BASELINE if t == 0 else GRID
            out.append(f'<line x1="{plot_x0:.1f}" y1="{y:.1f}" x2="{plot_x1:.1f}" y2="{y:.1f}" stroke="{color}"'
                       f' stroke-width="1" shape-rendering="crispEdges"/>')
            out.append(svg_text(plot_x0 - 8, y + 4, tick_label(t, ticks), size=11, fill=INK_3, anchor="end"))

        centers = [plot_x0 + (plot_x1 - plot_x0) * f for f in (0.3, 0.7)]
        for bar, cx in zip(panel["bars"], centers):
            out.append(svg_text(cx, baseline + 22, bar["label"], size=13, fill=INK_2, anchor="middle"))
            if bar["avg"] is None:
                out.append(svg_text(cx, baseline - 10, "no data", size=12, fill=INK_3, anchor="middle"))
                continue
            y = y_of(bar["avg"])
            runs = f'{bar["runs"]} run' + ("" if bar["runs"] == 1 else "s")
            tooltip = f'{bar["label"]}, {panel["label"]}: {bar["avg"]:,.1f} chunks/s (average of {runs})'
            out.append(f'<g><title>{escape(tooltip)}</title>'
                       f'<path d="{bar_path(cx - bar_w / 2, y, bar_w, baseline - y)}" fill="{bar["color"]}"/></g>')
            out.append(svg_text(cx, y - 8, f'{bar["avg"]:,.1f} CPS', size=13, fill=INK, anchor="middle", weight="600",
                                halo=PANEL_BG))

        base, ours = panel["bars"][0]["avg"], panel["bars"][1]["avg"]
        speedup = f"{ours / base:.2f}x" if base and ours else ""
        if speedup:
            bracket_y = plot_top - 28
            out.append(f'<path d="M{centers[0]:.1f},{bracket_y + 7:.1f} V{bracket_y:.1f} H{centers[1]:.1f}'
                       f' V{bracket_y + 7:.1f}" fill="none" stroke="{INK_3}" stroke-width="1"/>')
            mid = (centers[0] + centers[1]) / 2
            pill_w = text_width(speedup, 17, bold=True) + 18
            out.append(f'<rect x="{mid - pill_w / 2:.1f}" y="{bracket_y - 14:.1f}" width="{pill_w:.1f}" height="26"'
                       f' rx="13" fill="{BG}" stroke="{BASELINE}" stroke-width="1"/>')
            out.append(svg_text(mid, bracket_y + 5, speedup, size=17, weight="700", anchor="middle"))
        table.append((panel["label"], panel["bars"], speedup))

    note_size, note_line = 12, 18
    note_y = panel_bottom + 34
    lines = []
    for note in footnotes:
        lines.extend(wrap(note, content, note_size))
    body = [f'<line x1="{margin}" y1="{panel_bottom + 16}" x2="{width - margin}" y2="{panel_bottom + 16}"'
            f' stroke="{GRID}" stroke-width="1" shape-rendering="crispEdges"/>']
    for j, line in enumerate(lines):
        body.append(svg_text(margin, note_y + j * note_line, line, size=note_size, fill=INK_2))
    height = int(note_y + max(0, len(lines) - 1) * note_line + 24)

    desc = "; ".join(f'{label}: ' + ", ".join(
        f'{b["label"]} {b["avg"]:.1f} chunks/s' for b in bars if b["avg"] is not None)
        + (f" ({speedup})" if speedup else "") for label, bars, speedup in table)
    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}"'
           f' viewBox="0 0 {width} {height}" role="img" aria-labelledby="{id_prefix}-title {id_prefix}-desc"'
           f' font-family="{escape(FONT, {chr(34): "&quot;"})}">',
           f'<title id="{id_prefix}-title">{escape(title)}</title>',
           f'<desc id="{id_prefix}-desc">{escape(desc)}</desc>',
           f'<rect width="{width}" height="{height}" fill="{BG}"/>']
    svg.extend(out)
    svg.extend(body)
    svg.append("</svg>")
    return "\n".join(svg) + "\n", table


def footnotes_for(rows, args, constants, dfc=None, tools=TOOLS):
    lines = []
    radii = sorted({r["radius"] for r in rows})
    radius_text = " / ".join(f"{r:,}" for r in radii)
    sides = " / ".join(f"{2 * r:,} x {2 * r:,}" for r in radii)
    chunks = []
    for tool, tool_label, _color in tools:
        counts = {r["chunks"] for r in rows if r["tool"] == tool}
        if counts:
            chunks.append(f"{tool_label} {thousands(counts)}")
    lines.append(f"Each run: fresh world, square of radius {radius_text} blocks ({sides} blocks); "
                 f"chunks per run: {' | '.join(chunks)}.")
    runs = {}
    for r in rows:
        runs[(r["world"], r["tool"])] = runs.get((r["world"], r["tool"]), 0) + 1
    counts = sorted(set(runs.values()))
    if len(counts) == 1:
        lines.append(f"Each bar is the average of {counts[0]} run{'s' if counts[0] != 1 else ''}.")
    else:
        lines.append(f"Each bar is the average of {counts[0]}-{counts[-1]} runs (hover a bar for its count).")
    system = [args.cpu, f"{args.ram} RAM" if args.ram else "", args.os]
    lines.append("System: " + ", ".join(s for s in system if s) + ".")
    dimensions = sorted({r["dimension"] for r in rows})
    where = "" if dimensions == ["overworld"] else ", " + " / ".join(DIMENSION_NAMES.get(d, d) for d in dimensions)
    lines.append(f"Minecraft {constants['MC']}, NeoForge {constants['NEOFORGE']}{where}.")
    order, versions = mod_versions(constants)
    used = {m for r in rows for m in r["mods"]}
    mods = []
    for key in order:
        if key in used:
            text = f"{MOD_NAMES.get(key, key)} {versions.get(key, '')}".strip()
            if key == "c2me" and dfc:
                text += f" (density function compiler {dfc})"
            mods.append(text.replace(" ", NBSP))
    if mods:
        lines.append("Mods: " + (NBSP + "| ").join(mods) + ".")
    if tools is C2ME_TOOLS:
        lines.append("C2ME: Minecraft's generation with C2ME, driven by Chunky (same method as Bye?Pregen's chart).")
    else:
        lines.append("Vanilla: Minecraft's own generation, driven by Chunky (same method as Bye?Pregen's chart).")
    lines.append("Ultra Fast Pregen runs on the CPU only: no OpenCL / GPU acceleration.")
    return lines


def html_page(charts):
    parts = []
    for svg, table, title in charts:
        rows = []
        for label, bars, speedup in table:
            cells = "".join(
                f'<td>{b["avg"]:,.1f} <span class="runs">({b["runs"]})</span></td>' if b["avg"] is not None
                else "<td>-</td>" for b in bars)
            rows.append(f"<tr><th scope=\"row\">{escape(label)}</th>{cells}<td>{escape(speedup or '-')}</td></tr>")
        parts.append(
            '<section class="chart">\n' + svg +
            f'<div class="table-wrap"><table><caption>{escape(title)}: chunks per second, average (runs)</caption>'
            '<thead><tr><th scope="col">World</th>' +
            "".join(f'<th scope="col">{escape(b["label"])}</th>' for b in table[0][1]) +
            '<th scope="col">Speedup</th></tr></thead><tbody>' + "".join(rows) +
            "</tbody></table></div>\n</section>\n")
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Pregen Benchmark</title>
<style>
  :root {{ color-scheme: light; }}
  body {{ margin: 0; padding: 24px 16px; background: {BG}; color: {INK}; font-family: {FONT}; }}
  .chart {{ max-width: 960px; margin: 0 auto 40px; }}
  .chart svg {{ display: block; width: 100%; max-width: 100%; height: auto; }}
  .table-wrap {{ overflow-x: auto; padding: 8px 32px 0; }}
  table {{ border-collapse: collapse; font-size: 13px; font-variant-numeric: tabular-nums; }}
  caption {{ text-align: left; color: {INK_2}; padding-bottom: 6px; }}
  th, td {{ padding: 4px 14px 4px 0; text-align: right; border-bottom: 1px solid {GRID}; }}
  th[scope="row"], thead th:first-child {{ text-align: left; }}
  thead th {{ color: {INK_2}; font-weight: 600; }}
  .runs {{ color: {INK_3}; }}
  @media (max-width: 600px) {{ .table-wrap {{ padding: 8px 0 0; }} }}
</style>
</head>
<body>
{''.join(parts)}</body>
</html>
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("csv", nargs="?", default=os.path.join(HERE, f"results-{DEFAULT_MC}.csv"),
                        help=f"results file (default: results-{DEFAULT_MC}.csv next to this script)")
    parser.add_argument("--mc", help="Minecraft version of the results (default: from the file)")
    parser.add_argument("--out", help="output directory (default: the CSV's directory)")
    parser.add_argument("--cpu", help='CPU for the footnote, e.g. "AMD Ryzen 9 9950X3D (16C/32T)"')
    parser.add_argument("--ram", help='RAM for the footnote, e.g. "64 GB"')
    parser.add_argument("--os", help='OS for the footnote, e.g. "Windows 11 Pro"')
    parser.add_argument("--radius", type=int, help="only use runs with this radius")
    args = parser.parse_args()
    if args.cpu is None:
        args.cpu = default_cpu()
    if args.ram is None:
        args.ram = default_ram()
    if args.os is None:
        args.os = default_os()

    if not os.path.exists(args.csv):
        raise SystemExit(f"{args.csv} not found: run  python bench.py run  first")
    rows = read_rows(args.csv, args.radius)
    if not rows:
        raise SystemExit(f"no usable rows in {args.csv}" + (f" with radius {args.radius}" if args.radius else ""))
    radii = sorted({r["radius"] for r in rows})
    if len(radii) > 1:
        print(f"warning: runs with different radii ({', '.join(map(str, radii))}) are averaged together;"
              " use --radius to pick one", file=sys.stderr)

    out_dir = args.out or os.path.dirname(os.path.abspath(args.csv))
    os.makedirs(out_dir, exist_ok=True)
    constants = bench_constants(args.mc or results_version(args.csv, {r["mc"] for r in rows if r["mc"]}))
    # Other dimensions' charts get their own file names, so they do not replace the overworld's.
    file_tag = "-".join(d for d in sorted({r["dimension"] for r in rows}) if d != "overworld")
    charts = []
    for variant, suffix, name, title, panel_suffix, dfc in VARIANTS:
        subset = [r for r in rows if r["variant"] == variant]
        if not subset:
            continue
        tools = C2ME_TOOLS if dfc else TOOLS
        dims = sorted({r["dimension"] for r in subset})
        if dims != ["overworld"]:
            title = title + " in " + " / ".join(DIMENSION_NAMES.get(d, d) for d in dims)
        svg, table = build_chart(subset, title, footnotes_for(subset, args, constants, dfc, tools),
                                 "ufp" + suffix, panel_suffix, tools)
        if svg is None:
            continue
        path = os.path.join(out_dir, name if not file_tag else name.replace("chart", "chart-" + file_tag, 1))
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write('<?xml version="1.0" encoding="UTF-8"?>\n' + svg)
        print(f"wrote {path}")
        charts.append((svg, table, title))
        for label, bars, speedup in table:
            cells = "  ".join(f'{b["label"]} {b["avg"]:.1f}' if b["avg"] is not None else f'{b["label"]} -'
                              for b in bars)
            print(f"  {title} | {label}: {cells}" + (f"  ({speedup})" if speedup else ""))
    if not charts:
        raise SystemExit("nothing to draw")
    path = os.path.join(out_dir, "chart.html" if not file_tag else f"chart-{file_tag}.html")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(html_page(charts))
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
