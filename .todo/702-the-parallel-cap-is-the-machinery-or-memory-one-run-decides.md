# 702. Is the parallel cap the machinery or memory? One run decides it

Difficulty: Low (one benchmark on a cleared box; the reasoning is already written)

Filed 2026-09-05 out of `.todo/670`'s "Lanes for the week of 2026-09-08". Both
orchestrators independently concluded that a parallel GEMV's ceiling is **not** DRAM
bandwidth, from different hardware by different methods, and both explicitly declined to
call it a law. `.todo/670` names the discriminator and marks it unrun; this item exists so
it has an owner and a size instead of only a mention, because a remainder without either
is the kind that evaporates (`.todo/670` rule 3).

## The two observations

- **GB10, a kernel sweep** (`.todo/488`'s README, qualified in `bc421524`). The parallel
  f32 arm sits at 41-42 Gelem/s at BOTH 1024x1024 (4.2 MB of weights) and 4096x4096
  (67 MB). A GEMV re-reads no weight so both stream, but 4.2 MB fits this part's
  system-level cache and 67 MB does not -- a DRAM bandwidth ceiling has no reason to bind
  the two identically.
- **dorian, four whole models** (`.todo/670`, corrected in `78434218` / `f63f6eb3`). The
  scaling curves differ per model: 1 -> 32 threads is 4.37x for LFM2.5-1.2B against 2.95x
  for Qwen3.5-0.8B, and Qwen3.5 is saturated by 16 threads while LFM2.5 is still climbing
  at 32. Within-model ratios throughout, so no `GB/token` estimate enters. The ordering
  was predicted from access shape -- ~30 big matvecs against 576 small 128x128 GEMVs per
  token -- before it was measured.

Neither is decisive. Four models on one box and one kernel sweep on another is two
suggestive results, and the models leg cannot separate "the work was cut up badly" from
"these models stream differently" without a byte estimate nobody trusts.

## Do

**Run the parallel f32 GEMV at a shape small enough to be unambiguously cache-resident --
256x256, and a size sweep down to it -- on a cleared box, and see whether it still lands
on the box's plateau rate.**

That is the whole item. Its value is that it contains **no model and no `GB/token`**: one
kernel, one width, shapes that differ only in whether they fit. Nothing has to be
estimated, so nothing can be over-read.

- **Still at the plateau rate when the weights certainly fit in cache** -> the cap is the
  parallel machinery (work distribution, barrier cost, per-row dispatch), memory is not
  the binding constraint, and the per-model differences on dorian are how the work was cut
  up rather than how the model streams.
- **Clearly above it at 256x256, falling to it as the matrix leaves cache** -> the cap is
  memory after all, the GB10 two-shape observation has some other cause (start with
  whether 1024x1024 is actually resident at 20 threads), and dorian's per-model curves
  need a different explanation.

Take the load average before and after, the base commit, the JIT, the thread count
(`RONTOLISP_THREADS` unset resolves to 20 on GB10), and more than one run per cell --
`.todo/488`'s README records that one run per cell is not enough in the parallel column.

## Why it is worth a lane rather than a footnote

The answer changes what to do next for **every** width, not only bf16: `.todo/672`'s Q8_0,
`.todo/490`'s device work, and `.todo/489`'s remaining predictions all rest on whether a
parallel arm is bandwidth-bound. If it is the machinery, then halving the bytes buys
nothing in parallel no matter which width does the halving, and the parallel column stops
being evidence about widths at all.

The failure mode this item is written against: two orchestrators each said "I would not
call it a law", which is the point at which it usually becomes one anyway. One run makes
that unnecessary either way.
