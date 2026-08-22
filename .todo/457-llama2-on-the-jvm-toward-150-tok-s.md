# 457. llama2 on the JVM: beat the Java port (535 tok/s)

Difficulty: High

Rewritten 2026-08-22 after `.todo/478` landed (`--simd --parallel`), replacing the
2026-08-19 plan (64-core x86 box, 87 tok/s, "toward 150") whose GEMV half is done. The
standing on the GB10 (10 Cortex-X925 + 10 A725, GraalVM 25; stories15M, the 222-token
`-t 0` story, medians of three interleaved runs, nothing pinned):

| | threads | tok/s | ms/token |
|---|---|---|---|
| rontolisp JVM `--simd` | 1 | 221 | 4.5 |
| rontolisp JVM `--simd --parallel` | 20 | 319 (330 at `RONTOLISP_THREADS=10`) | 3.1 |
| rontolisp JVM `--gpu --simd` | 1 + device | 278 | 3.6 |
| `run.c -O2` | 1 | 147 | 6.8 |
| kishida's Java Vector API port, `.parallel()` removed | 1 | 297 | 3.4 |
| **kishida's port as published** (`IntStream.range(0, d).parallel()` matmul) | 20 | **535** | **1.87** |

**Every acceleration flag is now out of the picture.** `--blas` does nothing here
(`vec:matvec` is outside its set, `.todo/471`; measured 224 / 314 with it), `--gpu` on
top of `--parallel` is a loss (265, the spinners compete with the driver), and the GEMVs
are ~0.9-1.1 ms of the 3.1 ms token -- so **even a zero-cost GEMV leaves ~2.2 ms =
~450 tok/s, below 535.** The whole remaining gap is the boxed Lisp around the GEMVs, and
the port's equivalent of it is plain `float[]` loops that cost it ~0.2 ms. Its GEMVs are
not faster than ours; its glue is ten times cheaper.

## Where the 3.1 ms goes (measured)

`457-.../llama2-prof.lisp` is the example with `System.nanoTime` ticks around every
section (`java:static`, ~1.4 us a tick, ~200 ticks a token -- subtract ~0.3 ms from the
total); `--simd --parallel`, us per token:

| section | us/token | what it is |
|---|---|---|
| attention, of which | ~1900 | 6 layers x 6 heads |
| -- softmax (3 boxed loops over `pos+1`) | 800 | `tick.lisp`: 21 us per call over 111 scores = 63 ns per boxed iteration; x36 per token |
| -- `qh` copy + scores GEMV `(vec:matvec kch qh)` | 430 | the GEMV is 256x48 = 12K MACs, below the `--parallel` threshold (serial ~2 us); the rest is the 48-element boxed copy and the call |
| -- `oh` GEMV + copy into `out` | 240 | same shape, same story |
| -- KV append (2 x 48 boxed stores per kv head) | 170 | |
| rope (2 x 144 boxed pair rotations) | 380 | |
| rmsnorm x 2 (`vec:dot` + `vec:scale` + `vec:mul`, 3 fresh vectors) | 250 | |
| silu * h3 (7 fresh vectors, a scalar `Math.exp` loop over 768) | 200 | |
| GEMVs (24 x 288x288, 12 x 768x288, 6 x 288x768, the 32000x288 head) | ~900-1100 | `GemvColdProbe.java`: a 288x288 GEMV from cold DRAM is 11 us serial / 4.3 us parallel; the head ~400 us |
| sample + decode | 20 | |

So ~2.0 ms of the token is 36 x (softmax + copies) + rope + the element-wise chains, and
it is all the same thing: **a compiled Lisp numeric loop boxes every `Double` and every
`Long` and dispatches `+`/`*`/`>` on `Object`** (`.todo/412`), ~60 ns per iteration where
the JVM would do the same work in ~2 ns. The budget to beat 535: GEMV ~0.9 ms + glue
<= 0.9 ms = 1.8 ms.

## The plan, by expected saving

1. **Backend: unboxed numeric loops over packed float arrays (~1.5 ms, the lever).** The
   shape every hot loop here has -- `(dotimes (u n) ...)` with a fixnum counter whose
   body reads and writes packed single/double-float arrays (`aref` / `(setf (aref ...))`)
   through `let`/`let*` temporaries and float arithmetic (`+ - * / exp sqrt > <`) -- must
   compile to primitive `int`/`float`/`double` locals and `float[]` accesses, no `Double`
   or `Long` allocation, no `_add(Object,Object)`. A type pass over the loop body
   (the index is a fixnum by construction; an `aref` of a known packed array is a float;
   arithmetic over floats is a float; anything else bails to the boxed path for the
   whole loop) and a second emitter for the typed subset. This is `.todo/412`'s "no
   fusion" narrowed to what every numeric program needs first; it turns the softmax,
   rope, the three attention copies and the KV append into ~0.15 ms together. Pin it
   with the byte-identity of the llama2 story and with ci-spec cases per construct; the
   interpreter and wasm are untouched (same values, the JVM only gets faster).
2. **Example-side, independent of 1 (~0.4 ms):**
   - the attention GEMVs (256x48 and 48x256, 144 a token) sit below the 2^15
     `--parallel` threshold and below the 128-column lane threshold: a head of 48 runs
     the scalar tail in `matvecRowsF` row by row. Give the row loop a lane form for
     short rows (the `.todo/457` original item 3 -- THRESHOLD guards a per-call cost the
     GEMV pays once), and consider letting `--parallel` take a call of >= 2^14 MACs when
     it has >= 64 rows;
   - fuse `wq`/`wk`/`wv` into one 864x288 matrix and `w1`/`w3` into one 1536x288 at load
     time: 6 dispatches a layer become 2, each better amortized, and the split is a
     copy the unboxed loop of 1 makes free (or `vec:matvec-into` into a slice);
   - the residual / RMSNorm / SwiGLU chain on `-into` kernels over state buffers
     (`vec:exp-into`, `vec:mul-into`, `vec:add-into`): 12+ fresh vectors a layer become
     none, and the GC stops evicting the weight stream;
   - the softmax as whole-vector kernels once `.todo/456` (the wasm `exp` past |x|~300)
     is closed: `(vec:clip (scores - max) -80 0)` -> `vec:exp-into` -> `vec:sum` ->
     `vec:scale-into` over the `pos+1` prefix -- which needs a prefix length on the
     `-into` kernels or a `vec:softmax-into`; with 1 done this is moot.
3. **GEMV in situ (~0.2-0.3 ms):** the 24 projections cost ~4.3 us each cold (DRAM
   latency on ten 32 KB streams, not bandwidth); try a smaller leaf for a cold matrix
   only if 2's fusion does not already hide it. `vec:matvec-into` over a reused result
   buffer for every GEMV in the loop.

Order: 2 first (an afternoon, measurable per bullet with `llama2-prof.lisp`), then 1.
Re-measure the README table after each.

## Gate

`examples/llama2` on the GB10: **`--simd --parallel` above the published port's 535
tok/s** (README table re-measured on one day, the port re-measured beside it), `--simd`
single-threaded above the port's single-thread 297, the story byte-identical to `run.c`
on all four backends (`examples.yaml`'s `equals`), and every ci-spec case unchanged.
