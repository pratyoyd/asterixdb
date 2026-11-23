# Figures for 2026_11_23_PLAQUE_ICEBERG_EXPERIMENTS_SUMMARY. Numbers copied from the dated notes in plaque/.
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

INK, MUTED, GRID = "#0b0b0b", "#52514e", "#e4e3df"
BLUE, ORANGE, AQUA, GREY = "#2a78d6", "#eb6834", "#1baf7a", "#b9b8b2"
plt.rcParams.update({"font.size": 9, "axes.edgecolor": GRID, "axes.labelcolor": MUTED,
                     "xtick.color": MUTED, "ytick.color": INK, "axes.spines.top": False,
                     "axes.spines.right": False, "figure.dpi": 200, "savefig.bbox": "tight"})

# Fig 1: scalar filters, off vs on (medians of 6 runs)
rows = [("MAX(l_suppkey), single table", 19.1, 2.5), ("MIN(l_suppkey), single table", 18.7, 2.6),
        ("MAX(l_suppkey) over a join", 36.1, 11.1), ("GROUP BY MAX(l_discount)", 56.9, 30.0),
        ("Theta join COUNT(*)", 22.4, 3.8), ("MAX(l_extendedprice), row test", 19.1, 0.59),
        ("MAX(l_extendedprice) + WHERE, row test", 30.3, 1.08), ("MAX(l_extendedprice) over a join, row test", 38.7, 9.9)]
fig, ax = plt.subplots(figsize=(7, 3.6))
h = 0.36
for i, (name, off, on) in enumerate(rows):
    y = len(rows) - 1 - i
    ax.barh(y + h / 2, off, h, color=GREY, label="Baleen off" if i == 0 else None)
    ax.barh(y - h / 2, on, h, color=BLUE, label="Baleen on" if i == 0 else None)
    ax.text(off + 0.6, y + h / 2, f"{off:g} s", va="center", color=MUTED, fontsize=8)
    ax.text(on + 0.6, y - h / 2, f"{on:g} s  ({off / on:.1f}x)", va="center", color=INK, fontsize=8)
ax.set_yticks(range(len(rows))); ax.set_yticklabels([r[0] for r in rows][::-1])
ax.set_xlabel("Median time of 6 runs (s)"); ax.set_xlim(0, 66); ax.xaxis.grid(True, color=GRID); ax.set_axisbelow(True)
ax.legend(frameon=False, loc="lower right")
fig.savefig("fig1_scalar_filters.png"); plt.close(fig)

# Fig 2: DISTINCT by level, Roaring seen-set
lv = ["off", "L0", "L1", "L2", "L3", "L4"]
data = {"Q1 l_shipmode, join (7 values)": [203.8, 39.5, 36.6, 35.7, 12.3, 9.4],
        "Q2 l_suppkey, join (100,000 values)": [164.6, 36.5, 34.9, 16.4, 16.0, None],
        "Q3 l_partkey, join (1,999,998 values)": [143.1, 46.3, 45.6, 46.4, 47.6, None],
        "Q4 l_shipmode, no join (7 values)": [402.5, 19.8, 19.9, 19.8, 3.0, 0.4]}
fig, axs = plt.subplots(2, 2, figsize=(7.0, 4.8), sharey=True)
for ax, (t, v) in zip(axs.flat, data.items()):
    for i, x in enumerate(v):
        if x is None:
            ax.text(i, 0.3, "same as L3", ha="center", va="bottom", color=MUTED, fontsize=7); continue
        ax.bar(i, x, 0.62, color=GREY if i == 0 else BLUE)
        ax.text(i, x * 1.12, f"{x:g}", ha="center", va="bottom", color=INK, fontsize=8)
    ax.set_yscale("log"); ax.set_ylim(0.25, 1500); ax.set_xticks(range(6)); ax.set_xticklabels(lv, fontsize=8.5)
    ax.set_title(t, fontsize=9, color=INK, loc="left")
    ax.yaxis.grid(True, color=GRID); ax.set_axisbelow(True)
[a.set_ylabel("Median time (s), log scale") for a in axs[:, 0]]; fig.tight_layout()
fig.savefig("fig2_distinct_levels.png"); plt.close(fig)

# Fig 3: Q2 files skipped per run by early-send setting (level 2)
sk = {"none": ([18, 4, 6, 0, 0, 14], [22, 2, 14, 19, 16, 18]), "f": ([0, 6, 14, 0, 2, 10], [22, 22, 22, 26, 0, 0]),
      "j": ([3, 18, 22, 22, 17, 22], [10, 23, 23, 26, 26, 25]), "fj": ([26] * 6, [26] * 6),
      "jo": ([22, 22, 1, 14, 1, 1], [26, 1, 2, 22, 2, 22]), "fjo": ([26] * 6, [26] * 6)}
fig, ax = plt.subplots(figsize=(6.4, 2.8))
for i, (k, (fl, ro)) in enumerate(sk.items()):
    for j, (vals, c, m) in enumerate(((fl, BLUE, "o"), (ro, ORANGE, "s"))):
        xs = [i + (-0.17 if j == 0 else 0.17) + (n - 2.5) * 0.035 for n in range(6)]
        ax.scatter(xs, vals, s=22, color=c, marker=m, edgecolor="white", linewidth=0.6,
                   label=("flat bitmap" if j == 0 else "Roaring bitmap") if i == 0 else None, zorder=3)
ax.set_xticks(range(6)); ax.set_xticklabels(list(sk)); ax.set_ylim(-1.5, 30); ax.set_yticks([0, 10, 20, 26, 30])
ax.set_xlabel("Early-send setting (f = filter, j = join, o = observer)"); ax.set_ylabel("Files skipped of 30, one point per run")
ax.yaxis.grid(True, color=GRID); ax.set_axisbelow(True); ax.legend(frameon=False, loc="lower center", ncol=2, bbox_to_anchor=(0.5, 1.0))
fig.savefig("fig3_flush_ablation.png"); plt.close(fig)

# Fig 4: row test with and without the scan flush, per-run times
runs = {"I1 MAX, no WHERE": ([3.0, 2.8, 2.8, 2.6, 2.5, 2.8], [1.1, 2.9, 2.9, 5.3, 3.3, 3.7], [0.6, 0.6, 0.6, 0.7, 0.6, 0.6]),
        "I2 MAX, WHERE": ([4.2, 3.7, 3.8, 4.3, 4.0, 4.2], [5.2, 3.2, 2.9, 5.6, 4.9, 5.3], [1.6, 1.0, 1.5, 1.1, 1.1, 1.1]),
        "I5 MAX over a join": ([14.0, 14.2, 13.0, 12.8, 13.3, 13.7], [10.9, 12.5, 9.9, 11.5, 14.6, 14.5], [9.9, 9.8, 9.8, 9.8, 10.4, 10.5])}
names = ["File / row-group skip only", "+ row test, no scan flush", "+ row test, scan flush"]
fig, axs = plt.subplots(1, 3, figsize=(7.2, 2.5))
for ax, (t, cfgs) in zip(axs, runs.items()):
    for i, (vals, c, m) in enumerate(zip(cfgs, (BLUE, ORANGE, AQUA), ("o", "s", "D"))):
        ax.scatter([i + (n - 2.5) * 0.07 for n in range(6)], vals, s=20, color=c, marker=m, edgecolor="white",
                   linewidth=0.6, label=names[i], zorder=3)
    ax.set_ylim(0, max(max(c) for c in cfgs) * 1.15); ax.set_xticks([]); ax.set_xlim(-0.6, 2.6)
    ax.set_title(t, fontsize=8.5, color=INK, loc="left"); ax.yaxis.grid(True, color=GRID); ax.set_axisbelow(True)
axs[0].set_ylabel("Run time (s), one point per run")
h, l = axs[0].get_legend_handles_labels()
fig.legend(h, l, frameon=False, loc="lower center", ncol=3, bbox_to_anchor=(0.5, -0.1), fontsize=8)
fig.savefig("fig4_row_test_scan_flush.png"); plt.close(fig)
