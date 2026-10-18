#!/usr/bin/env python3
"""Draw every benchmark result on one image (summary.svg), from the results files bench.py writes.

    python summary.py                                   # results-26.1.2*.csv next to this script
    python summary.py --mc 1.21.1 --out some/dir
    python summary.py --cpu "AMD Ryzen 9 9950X3D (16C/32T)" --ram "64 GB" --os "Windows 11 Pro"

Two rows of panels, without C2ME and with C2ME (both bars run it), and one panel per world and
dimension that has runs: results-<mc>.csv (the Overworld), results-<mc>-the_nether.csv and
results-<mc>-the_end.csv. Every bar is the average chunks per second of that configuration's runs;
each panel has its own y-axis. Python 3.8+, standard library only (reads and draws with chart.py).
"""
import argparse
import os
import sys
from xml.sax.saxutils import escape

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import chart  # noqa: E402

GRAY, TEAL = chart.TOOLS[0][2], chart.TOOLS[1][2]
# (dimension, world, panel label)
PANELS = (("overworld", "vanilla", "Overworld"), ("overworld", "tw", "Overworld: Tectonic + WWOO"),
          ("the_nether", "vanilla", "Nether"), ("the_end", "vanilla", "End"))
# (variant, row label); the bars are named after what was run: Chunky drives Minecraft's generation.
ROWS = (("", "Without C2ME"), ("c2me", "With C2ME (installed for both)"))
TOOLS = (("chunky", "Chunky", GRAY), ("ufp", "Ultra Fast Pregen", TEAL))

WIDTH, MARGIN, GAP = 1600, 32, 24
HEADER_H, HEADROOM, PLOT_H, LABELS_H = 34, 58, 220, 44
PANEL_H = HEADER_H + HEADROOM + PLOT_H + LABELS_H


def draw_panel(out, px, top, panel_w, label, bars, row_label):
    """One panel: two bars on their own y-axis and the speedup between them. Returns the speedup text."""
    out.append(f'<rect x="{px:.1f}" y="{top:.1f}" width="{panel_w:.1f}" height="{PANEL_H}" rx="8"'
               f' fill="{chart.PANEL_BG}"/>')
    out.append(chart.svg_text(px + 16, top + 24, label, size=15, weight="600"))
    plot_top = top + HEADER_H + HEADROOM
    baseline = plot_top + PLOT_H
    plot_x0, plot_x1 = px + 16 + 46, px + panel_w - 16
    averages = [b["avg"] for b in bars if b["avg"] is not None]
    if not averages:
        out.append(chart.svg_text(px + panel_w / 2, plot_top + PLOT_H / 2, "no runs", size=13, fill=chart.INK_3,
                                  anchor="middle"))
        return ""
    ticks = chart.nice_ticks(max(averages))

    def y_of(value):
        return baseline - PLOT_H * value / ticks[-1]

    for t in ticks:
        y = y_of(t)
        out.append(f'<line x1="{plot_x0:.1f}" y1="{y:.1f}" x2="{plot_x1:.1f}" y2="{y:.1f}"'
                   f' stroke="{chart.BASELINE if t == 0 else chart.GRID}" stroke-width="1" shape-rendering="crispEdges"/>')
        out.append(chart.svg_text(plot_x0 - 8, y + 4, chart.tick_label(t, ticks), size=11, fill=chart.INK_3,
                                  anchor="end"))
    centers = [plot_x0 + (plot_x1 - plot_x0) * f for f in (0.28, 0.72)]
    bar_w = 60
    for bar, cx in zip(bars, centers):
        out.append(chart.svg_text(cx, baseline + 22, bar["label"], size=13, fill=chart.INK_2, anchor="middle"))
        if bar["avg"] is None:
            out.append(chart.svg_text(cx, baseline - 10, "no data", size=12, fill=chart.INK_3, anchor="middle"))
            continue
        y = y_of(bar["avg"])
        runs = f'{bar["runs"]} run' + ("" if bar["runs"] == 1 else "s")
        tooltip = f'{bar["label"]}, {label}, {row_label}: {bar["avg"]:,.1f} chunks/s (average of {runs})'
        out.append(f'<g><title>{escape(tooltip)}</title>'
                   f'<path d="{chart.bar_path(cx - bar_w / 2, y, bar_w, baseline - y)}" fill="{bar["color"]}"/></g>')
        out.append(chart.svg_text(cx, y - 8, f'{bar["avg"]:,.1f} CPS', size=13, anchor="middle", weight="600",
                                  halo=chart.PANEL_BG))
    base, ours = bars[0]["avg"], bars[1]["avg"]
    if not (base and ours):
        return ""
    speedup = f"{ours / base:.2f}x"
    bracket_y = plot_top - 28
    out.append(f'<path d="M{centers[0]:.1f},{bracket_y + 7:.1f} V{bracket_y:.1f} H{centers[1]:.1f}'
               f' V{bracket_y + 7:.1f}" fill="none" stroke="{chart.INK_3}" stroke-width="1"/>')
    mid = (centers[0] + centers[1]) / 2
    pill_w = chart.text_width(speedup, 17, bold=True) + 18
    out.append(f'<rect x="{mid - pill_w / 2:.1f}" y="{bracket_y - 14:.1f}" width="{pill_w:.1f}" height="26" rx="13"'
               f' fill="{chart.BG}" stroke="{chart.BASELINE}" stroke-width="1"/>')
    out.append(chart.svg_text(mid, bracket_y + 5, speedup, size=17, weight="700", anchor="middle"))
    return speedup


def footnotes(rows, args, constants):
    radii = sorted({r["radius"] for r in rows})
    radius_text = " / ".join(f"{r:,}" for r in radii)
    sides = " / ".join(f"{2 * r:,} x {2 * r:,}" for r in radii)
    chunks = []
    for tool, name in (("chunky", "Chunky"), ("ufp", "Ultra Fast Pregen")):
        counts = {r["chunks"] for r in rows if r["tool"] == tool}
        if counts:
            chunks.append(f"{name} {chart.thousands(counts)}")
    runs = {}
    for r in rows:
        key = (r["variant"], r["dimension"], r["world"], r["tool"])
        runs[key] = runs.get(key, 0) + 1
    counts = sorted(set(runs.values()))
    each = (f"each bar is the average of {counts[0]} run{'s' if counts[0] != 1 else ''}" if len(counts) == 1
            else f"each bar is the average of {counts[0]}-{counts[-1]} runs")
    lines = [f"Each run: fresh world, square of radius {radius_text} blocks ({sides} blocks), chunks per run:"
             f" {' | '.join(chunks)}; {each}."]
    system = [args.cpu, f"{args.ram} RAM" if args.ram else "", args.os]
    lines.append("System: " + ", ".join(s for s in system if s)
                 + f". Minecraft {constants['MC']}, NeoForge {constants['NEOFORGE']}.")
    order, versions = chart.mod_versions(constants)
    used = {m for r in rows for m in r["mods"]}
    mods = []
    for key in order:
        if key in used:
            text = f"{chart.MOD_NAMES.get(key, key)} {versions.get(key, '')}".strip()
            if key == "c2me":
                text += " (density function compiler on)"
            mods.append(text.replace(" ", chart.NBSP))
    if mods:
        lines.append("Mods: " + (chart.NBSP + "| ").join(mods) + ".")
    lines.append("Chunky bars: Minecraft's own generation (with C2ME in the lower row), driven by Chunky,"
                 " the same method as Bye?Pregen's chart.")
    lines.append("Ultra Fast Pregen runs on the CPU only: no OpenCL / GPU acceleration.")
    return lines


def build(rows, args, constants):
    columns = [p for p in PANELS if any(r["dimension"] == p[0] and r["world"] == p[1] for r in rows)]
    if not columns:
        return None, []
    content = WIDTH - 2 * MARGIN
    panel_w = (content - GAP * (len(columns) - 1)) / len(columns)
    ufp_version = chart.jar_version(constants["UFP_JAR"])
    title = f"Ultra Fast Pregen {ufp_version}: chunk pregeneration speed".replace("  ", " ")
    out = [chart.svg_text(MARGIN, 46, title, size=26, weight="700"),
           chart.svg_text(MARGIN, 74, "Chunks per second (higher is better) | each panel has its own y-axis",
                          size=14, fill=chart.INK_2)]
    x = MARGIN
    for _tool, name, color in TOOLS:
        out.append(f'<rect x="{x:.1f}" y="93" width="12" height="12" rx="2" fill="{color}"/>')
        out.append(chart.svg_text(x + 18, 104, name, size=13))
        x += 18 + chart.text_width(name, 13) + 24

    table = []
    top = 130
    for variant, row_label in ROWS:
        subset = [r for r in rows if r["variant"] == variant]
        if not subset:
            continue
        out.append(chart.svg_text(MARGIN, top + 20, row_label, size=17, weight="700"))
        top += 34
        for i, (dimension, world, label) in enumerate(columns):
            bars = []
            for tool, tool_label, color in TOOLS:
                values = [r["cps"] for r in subset
                          if r["dimension"] == dimension and r["world"] == world and r["tool"] == tool]
                bars.append({"label": tool_label, "color": color, "avg": chart.average(values), "runs": len(values)})
            speedup = draw_panel(out, MARGIN + i * (panel_w + GAP), top, panel_w, label, bars, row_label)
            table.append((f"{label} ({row_label})", bars, speedup))
        top += PANEL_H + 26

    line_y = top - 10
    out.append(f'<line x1="{MARGIN}" y1="{line_y}" x2="{WIDTH - MARGIN}" y2="{line_y}" stroke="{chart.GRID}"'
               f' stroke-width="1" shape-rendering="crispEdges"/>')
    lines = []
    for note in footnotes(rows, args, constants):
        lines.extend(chart.wrap(note, content, 13))
    note_y = line_y + 26
    for j, line in enumerate(lines):
        out.append(chart.svg_text(MARGIN, note_y + j * 20, line, size=13, fill=chart.INK_2))
    height = int(note_y + (len(lines) - 1) * 20 + 26)

    desc = "; ".join(f"{label}: " + ", ".join(f'{b["label"]} {b["avg"]:.1f} chunks/s' for b in bars
                                               if b["avg"] is not None) + (f" ({speedup})" if speedup else "")
                     for label, bars, speedup in table)
    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{WIDTH}" height="{height}" viewBox="0 0 {WIDTH} {height}"'
           f' role="img" aria-labelledby="ufp-summary-title ufp-summary-desc"'
           f' font-family="{escape(chart.FONT, {chr(34): "&quot;"})}">',
           f'<title id="ufp-summary-title">{escape(title)}</title>',
           f'<desc id="ufp-summary-desc">{escape(desc)}</desc>',
           f'<rect width="{WIDTH}" height="{height}" fill="{chart.BG}"/>']
    svg.extend(out)
    svg.append("</svg>")
    return "\n".join(svg) + "\n", table


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mc", default=chart.DEFAULT_MC, help=f"Minecraft version (default: {chart.DEFAULT_MC})")
    parser.add_argument("--dir", default=HERE, help="folder with the results files (default: next to this script)")
    parser.add_argument("--out", help="output file (default: summary.svg in --dir)")
    parser.add_argument("--cpu", help='CPU for the footnote, e.g. "AMD Ryzen 9 9950X3D (16C/32T)"')
    parser.add_argument("--ram", help='RAM for the footnote, e.g. "64 GB"')
    parser.add_argument("--os", help='OS for the footnote, e.g. "Windows 11 Pro"')
    parser.add_argument("--radius", type=int, help="only use runs with this radius")
    args = parser.parse_args()
    args.cpu = chart.default_cpu() if args.cpu is None else args.cpu
    args.ram = chart.default_ram() if args.ram is None else args.ram
    args.os = chart.default_os() if args.os is None else args.os

    rows = []
    for suffix in ("", "-the_nether", "-the_end"):
        path = os.path.join(args.dir, f"results-{args.mc}{suffix}.csv")
        if os.path.exists(path):
            found = chart.read_rows(path, args.radius)
            rows.extend(found)
            print(f"read {path}: {len(found)} runs")
    if not rows:
        raise SystemExit(f"no results-{args.mc}*.csv with usable rows in {args.dir}: run  python bench.py run  first")
    svg, table = build(rows, args, chart.bench_constants(args.mc))
    if svg is None:
        raise SystemExit("nothing to draw")
    out = args.out or os.path.join(args.dir, "summary.svg")
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n' + svg)
    print(f"wrote {out}")
    for label, bars, speedup in table:
        cells = "  ".join(f'{b["label"]} {b["avg"]:.1f}' if b["avg"] is not None else f'{b["label"]} -' for b in bars)
        print(f"  {label}: {cells}" + (f"  ({speedup})" if speedup else ""))


if __name__ == "__main__":
    main()
