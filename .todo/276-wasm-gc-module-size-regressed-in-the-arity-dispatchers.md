# wasm-GC module size regressed (NOT in the arity dispatchers -- see the correction)

Difficulty: High

Regenerating every `examples/**/*.wasm` with the compiler at `0e044c7a` (2026-08-07)
against the artifacts built at `7bf7b2ce` (2026-07-17) showed the wasm-GC output
growing several-fold **with the same source and the same flags**. The `--no-gc` and
component paths are unaffected (they got slightly smaller); this is wasm-GC only.

## The measurement

Same `.lisp`, same flags, only the compiler differs:

| program | flags | 7bf7b2ce | 0e044c7a | |
| --- | --- | ---: | ---: | ---: |
| `webgl-cube/cube.lisp` | `--no-wasi --optimize` | 26,602 | 256,407 | 9.6x |
| `webgl-galaxy/galaxy.lisp` | `--no-wasi --optimize` | 17,012 | 68,881 | 4.0x |
| `minesweeper/minesweeper-wasm.lisp` | `--no-wasi --optimize` | 105,800 | 314,413 | 3.0x |
| `rainbow/rainbow.lisp` | `--no-wasi --optimize` | 106,479 | 56,931 | 0.5x |
| `webgl-triangle/triangle.lisp` | `--no-wasi --optimize` | 2,467 | 2,711 | 1.1x |
| `wasm-browser/hello.lisp` | (none) | 103,562 | 319,357 | 3.1x |
| `wasm-browser/hello.lisp` | `--optimize` | 4,644 | 8,730 | 1.9x |

To rebuild the reference compiler:

```bash
git worktree add /tmp/old 7bf7b2ce && (cd /tmp/old && ./mvnw -q clean package -DskipTests)
```

## CORRECTION 2026-08-08: it was never the arity dispatchers

The "six generic arity-dispatch functions hold 93% of the module" reading below was
**wrong**, and the two items it produced were aimed at a mechanism that already has the
shape they proposed. What the disassembly actually shows, checked function by function:

- The six big `(eqref ...) -> eqref` bodies in `cube.wasm` are **cube's own `mat4-*`
  defuns**. Their leading `eqref` parameter is the CLOSURE ENV every compiled defun
  takes, not a function id -- which is why their arities read as "0..4" and why there
  were two with the same signature.
- `WasmRuntimeBuilder.buildDispatchBody` has emitted a `br_table` with one `call` per
  arm since long before this window (item 2's premise, "the emitted bodies contain
  `br_table` zero times today", was reading those `mat4-*` bodies). `cube.wasm`
  contains **no dispatcher at all** -- it never takes a function as a value, so the
  funcall-dispatch gate leaves the set empty.
- `BuiltinFunctionWrappers` bodies are NOT inlined into dispatcher arms. Each wrapper is
  its own function and each arm is a `call`, which is what item 1 proposed building.

The real cost was **per `(setf (aref v i) x)` site**: `expandSetf` gives a rank-1
indexed place a runtime string arm, and that arm inlined the whole immutable-string
rebuild -- two `subseq`s (each an inline copy LOOP), a `string`, two `%string-concat`s.
**8,615 bytes per site**, paid by array-only code because nothing in
`(setf (aref m 0) 1.0)` says `m` is not a string. cube has 25 such sites across those
six `mat4-*` defuns: 203 of its 218 KB.

Lesson for the next item of this shape: **read the WAT against a function whose name
you have confirmed** before naming the mechanism. Two independent readings agreed on
"arity dispatchers" only because both started from the same guess.

## What landed

1. ~~Give each builtin wrapper its own wasm function and make the dispatcher arm a
   `call`.~~ **NOT A DEFECT** -- this is already the emitted shape (above).
2. ~~Make the dispatcher a `br_table` on a small integer tag.~~ **ALREADY TRUE**
   (above).
3. ~~Route `castFloatGetF64` through one shared runtime helper.~~ **LANDED 2026-08-07**
   at the DEFAULT level: `pi_approx` 5,356 -> 3,540 (-33.9%), `ml/mlp` -10.1%,
   `ml/nn` -11.4%. `.kb/wasm-shared-coercion.md`.
4. ~~`castFloatGetF64` calls `ctx.allocTemp()` per site.~~ **LANDED with 3.**
5. **The string arm of an indexed write is one spliced defun.** **LANDED 2026-08-08**:
   `%schar-set-runtime`, injected once per program by `expandTopLevelDefinitions`.
   Per-site marginal **8,615 -> 588 bytes** on wasm-GC and **5,042 -> 293 bytes** on the
   JVM; cube **218,235 -> 37,202 (-83.0%)**. `.kb/string-write-runtime.md`. The
   checked-in `examples/browser/**` artifacts were rebuilt and every page was verified
   in a real browser (cube, galaxy, triangle, heat3d, platformer, robot-arm,
   battlefront, minesweeper, rainbow, the three wasm-browser modules, hiragana
   recognition), zero console errors.

Against the ORIGINAL `7bf7b2ce` baseline the top of the table now reads: cube 26,602 ->
37,202, galaxy 17,012 -> 25,620, minesweeper 105,800 -> 303,308, hello `--optimize`
4,644 -> 7,624. The first two are within ~1.5x of where they started; the last two are
not, and that residue is what is left of this item.

## What is left

- **`minesweeper` is still 2.9x its `7bf7b2ce` size** (303,308 vs 105,800) and did not
  move at all across item 5 -- it has no rank-1 `setf`-`aref` site. Nothing here
  explains it yet; it needs its own function-by-function disassembly, done the way the
  correction above insists on.
- **`hello --optimize` is 1.6x** (7,624 vs 4,644) and also did not move. Same.
- **`%aset` itself is ~515 bytes of inline code per site**: an farray test, a
  packed-integer-vector test and the general-array store, all three arms emitted at
  every site. That is the same shape one order of magnitude down. The packed-integer arm
  is the fused raw-i64 store (`.kb/packed-integer-vectors.md`) and must stay inline; the
  GENERAL arm is the candidate for a shared callee.
- **`subseq` on a string is ~3.7 KB of inline copy loop per site** on both compile
  paths (`expandSubseqCompat`'s general-array arm). Item 5 routed around it for the one
  helper that needed it; every other `subseq` in every library still pays it. This is
  probably the largest single remaining per-site cost in the compiler.
- **Compile-path temps are never released.** Item 4 removed one caller; every other
  `ctx.allocTemp()` is still permanent, and `.todo/137` is the JVM twin.

## Non-goals

- The `--no-gc` and component paths: both are flat or smaller across the same
  window, and the component wrapper floor is a different budget.
- The funcall-dispatch gate itself. It works, and its floor improved.
- Reverting the numeric tiers. The 5-way ladder is the price of exact arbitrary
  precision on wasm; the fix is to emit it once, not to drop a tier.
