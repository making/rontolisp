#!/usr/bin/env python3
"""Reads a fusion-baseline.lisp trace back: kernel time per bench, per rep.

usage: fusion-segments.py fb.sqlite fb.txt [reps]

The trace is cut at the MARKER kernels (the `rng_fill` launches with a 4823-block grid,
`linalg:rand` over 1234567 elements); segment k is bench k, whose label is line k of the
program's output. The `sumsq` launches are the per-rep sync and are discounted. Prints, per
bench, the kernel time per call and the launches per call, then the kernels inside it.
"""
import sqlite3
import sys

MARKER_GRID = (1234567 + 255) // 256

db, labels_path = sys.argv[1], sys.argv[2]
reps = int(sys.argv[3]) if len(sys.argv) > 3 else 5
NOISE = ("WARNING", "Collecting", "Generating", "Generated", "[", "\t", "exit ", "Try ")
labels = [line.rstrip("\n") for line in open(labels_path) if line.strip() and not line.startswith(NOISE)]
rows = sqlite3.connect(db).execute(
    "select s.value, k.gridX, k.start, k.end from CUPTI_ACTIVITY_KIND_KERNEL k "
    "join StringIds s on s.id = k.shortName order by k.start").fetchall()
segments = []
current = None
for name, grid, start, end in rows:
    if name.startswith("rng_fill") and grid == MARKER_GRID:
        current = []
        segments.append(current)
        continue
    if current is not None and not name.startswith("sumsq"):
        current.append((name, grid, end - start))
if len(segments) != len(labels):
    print(f"{len(segments)} segments but {len(labels)} labels", file=sys.stderr)
for label, seg in zip(labels, segments):
    total = sum(t for _, _, t in seg) / 1e6 / reps
    print(f"{label:64s} {total:8.3f} ms/call  {len(seg) / reps:6.1f} launches/call")
    by = {}
    for name, grid, t in seg:
        n, tt = by.get((name, grid), (0, 0))
        by[(name, grid)] = (n + 1, tt + t)
    for (name, grid), (n, tt) in sorted(by.items(), key=lambda kv: -kv[1][1]):
        if tt / 1e6 / reps >= 0.01:
            print(f"    {name:24s} grid {grid:6d}  {n / reps:5.1f}/call  {tt / 1e6 / reps:8.3f} ms/call")
