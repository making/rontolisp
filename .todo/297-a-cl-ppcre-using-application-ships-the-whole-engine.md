# A cl-ppcre-USING application ships the whole engine on WASM

Difficulty: High

REDIRECTED 2026-08-08 (user instruction, mid-session): the goal is to shrink
the module of a program that loads the REAL cl-ppcre -- not to substitute a
lite engine. The opt-in `cl-ppcre/lite` system (the original lever 4) was
built and parity-pinned by the time of the redirect and then REJECTED by the
user and reverted (record near the end of this file). What remains open here
is the real engine's module share, and this file now carries the measured
composition of that share and the identified levers.

## Where the real engine's bytes are (measured 2026-08-08, this session)

Probe = `(ql:quickload "cl-ppcre")` + one literal `ppcre:scan`, wasm-GC
Preview 1 at `--optimize=size` (the per-feature cost map with all probes is in
`.kb/optimize-dead-code-elimination.md`, "What a cl-ppcre-USING application
costs"). Headline: usage barely matters -- zero-reference 747,882 B, one scan
+286 B, all five API features +22,319 B. The composition of the 748,168 B:

- **wasm sections**: code 696,108 B (93%) in 863 functions; data 50,439 B
  (string blob 28,184 + three registry segments 8,280/8,088/5,824); everything
  else under 1.6 KB combined. The lever is CODE, not data.
- **top wasm functions**: 24,076 / 18,458 / 17,523 / 17,077 / 15,959 B --
  five functions are 93 KB. 468 functions are under 300 B (44,950 B total),
  so per-function fixed overhead is NOT the story either.
- **JVM twin of the same probe** (methods are named there; class total
  1,057,403 B, 849 methods, 971,011 B of method bytes):

  | bucket | bytes | methods |
  | --- | ---: | ---: |
  | cl-ppcre defun/defmethod bodies | 562,523 | 379 |
  | CL builtin wrapper catalog + prelude (`SEARCH`, `STRING=`, ...) | 172,108 | 164 |
  | lambdas (scanner closures, method bodies) | 170,879 | 220 |
  | funcall dispatch ladders (`_invoke_v$*`) | 37,092 | 12 |
  | runtime helpers / infra | 23,649 | 73 |

  Top methods: `%END-STRING-AUX--m1` 22,649, `%GATHER-STRINGS--m0` 20,137,
  `GET-TOKEN` 17,482, `MAYBE-ACCUMULATE` 16,272, `BUILD-REPLACEMENT` 12,660 --
  these are 40-80-line Lisp functions compiling to ~300-550 B per source line.

## The diagnosis: per-site sequence-dispatch inlining (`.todo/288`)

The density has a known mechanism, already filed as `.todo/288`: every generic
sequence lowering (`reverse`, `remove`, `position`, `substitute`, `count`,
`find`, `mapcar`, `sort`, ...) inlines a whole list/string/general-array
representation dispatch AT EVERY CALL SITE, including the other generic
operators it calls -- measured there at 5-8 KB per expansion, and `subseq` was
already fixed by the shared-callee recipe (2,316 -> 11 B per site,
`.kb/subseq-runtime.md`). cl-ppcre's engine sources are saturated with exactly
these operators. Call-site counts over the 17 engine files (this session):

| operator | sites | ~cost/site (todo-288 table) |
| --- | ---: | ---: |
| `nreverse` + `reverse` | 16 | ~7.2 KB |
| `mapcar` | 6 | ~8.1 KB |
| `char-equal`/`char=`-heavy scans, `schar`/`aref`/`char` | 45+38+24 | (already shared: `_arr_get`, ~0.2 KB) |
| `map` | 4 | dispatch-shaped |
| `count` | 4 | ~6-7 KB |
| `concatenate` / `coerce` / `copy-seq` | 4+4+3 | dispatch-shaped |
| `position` + `position-if` | 5 | ~4.9-6.7 KB |
| `find` | 3 | ~5-7 KB |
| `substitute` | 2 | ~7.7 KB |
| `remove-if` / `replace` / `search` / `sort` | 1 each | ~7 KB |

Frequency x cost puts the reverse family alone near 100 KB on this module, and
the same inlining sits inside the 172 KB wrapper-catalog bucket (a `mapcar`
wrapper body re-inlines `reverse`, ...). **Implementing `.todo/288`'s
shared-callee outlining is therefore THE lever for this item** -- it shrinks
every library-carrying module, with cl-ppcre the headline beneficiary. The
mechanics found this session for whoever implements it: the JVM injects
`%subseq-runtime` in `JvmLispCompiler` (~line 818) right after
`BuiltinFunctionWrappers.generate`; site routing is per-backend
(`Jvm/WasmSubseqCompiler` consult `ctx.functions.containsKey`); the wasm
backend has NO injection site for the subseq helper today (its array arm
reaches the helper only if the program defines it) -- locating/creating the
wasm-side wrapper+helper injection loop is the first mechanical step. The JVM
array-runtime gate (`programUsesAnyArrayOp`, ~120 KB if mis-fired) applies to
every new helper whose body names `aref`/`%aset`.

## What shaking CANNOT do (settled this session; do not re-derive)

- The pruner already covers ASDF-spliced third-party trees (the filed item's
  premise was stale); its residual on a USING app is ~zero -- the per-feature
  map's tiny increments (+286 B for scan) are it working.
- CLOS-aware shaking cannot pay on a USING app: the 27 defgenerics ARE the
  build pipeline and the parse tree is runtime data, so every node class is
  instantiable from `create-scanner` and every method reachable. The 823,589 B
  zero-reference ceiling collapses once one entry point is real. Full argument
  in the `.kb` section.
- Parse-half/match-half splitting and the compiler macros: settled by
  todo-295, still true.
- Compile-time lowering of literal regexes (old lever 5): un-taken -- identical
  semantics cannot be promised beyond a pinned subset, and one dynamic regex
  anywhere silently brings the engine back.

## Residual non-code observations (record, low yield)

- Data section 50,439 B total; the string blob is 28,184 B (whether its dead
  ranges stand down via `usesIntern` was not isolated -- the engine's parser
  interns for named groups).
- Any module loading the real engine is EH-mode (`-W exceptions=y`): the
  scanner closures' `return-from scan` crosses the `advance-fn`/`match-fn`
  lambdas. Intrinsic to the engine's shape; also the trigger of the `.todo/192`
  fourth hole (below).

## Found along the way: the compile paths corrupt the real engine's scanners

The probe sequences exposed a compile-path correctness hole, recorded as
`.todo/192`'s FOURTH hole with a ppcre-free reproducer: a `return-from`
crossing a lambda boundary skips the special-binding restore, so after any
failing/looping scan over a register regex (`all-matches`, `regex-replace-all`,
`split`), a later zero-register scan returns stale `*reg-starts*`
(`(1 4 #(NIL) #(NIL))` where the interpreter answers `(1 4 #() #())`).
`ClPpcreE2eTest` passes only by case order. Fixing it needs the save-stack
design sketched in 192; until then the interpreter is the only backend that
runs the real engine's scan sequences per the standard.

## The opt-in `cl-ppcre/lite`: built, parity-proven, REJECTED (2026-08-08)

The original lever 4 was fully built during this session (todo-296 delivery
mechanism: an `AsdOverrides` replacement `.asd` adding the secondary system, a
`ShimLibraries` leaf-module substitution carrying a CLOS-free
special-variable-free CPS backtracking engine, co-load refusal, a 194-row
corpus generated from and pinned against the real engine, 4-backend E2E green)
and measured: a five-feature probe was 135,476 B raw / 43,893 gzip against the
real engine's 770,201 / 183,182, zero-reference 542 B, no EH mode. **The user
rejected it the same day -- the wanted outcome is a smaller module for the
REAL engine, not a substitute -- and the implementation was reverted without
ever being committed.** Recorded so a subset engine is not re-proposed as this
item's answer; the numbers above stand as the measured cost of one. (If a
subset engine is ever wanted after all, the corpus method is the part worth
repeating: generate expectations from the real engine on the interpreter --
the only backend that runs its scan sequences per the standard until the
`.todo/192` fourth hole is fixed -- and pin both engines to the same rows.)

## Done when (redirected)

- `.todo/288`'s shared-callee outlining lands (its own item; its "Done when"
  governs) and the cl-ppcre probes re-measured here show the yield -- or a
  measured reason it cannot pay on this module is recorded.
- The `.kb` cost-map section stays consistent with whatever lands.

## 2026-08-08: todo-288 LANDED; the yield on this module, measured

The `%seq-to-*` conversion trio (`.kb/seq-conversion-runtime.md`) re-measured on
probe (a) (`ql:quickload` + one literal scan, `--optimize=size`): **748,091 ->
678,977 raw, -69,114 B (-9.2%)** -- real but bounded, where wrapper-catalog-heavy
modules halved (minesweeper -45%). The bound is structural: this module's sites
are spread across ~440 KB of engine defun bodies, and what stays per site is the
operator's own scan loop (0.5-0.9 KB) with only the conversion arms outlined;
the frequency table above also over-credited the reverse family (16 sites are
mostly `nreverse`, which was always a cheap in-place splice, ~0.2 KB -- only
plain `reverse` carried the 7 KB dispatch). The next density lever, if this
module's share is still worth chasing, is per-OPERATOR callees with runtime
`:test`/`:key` parameters stacked on the trio (the seq-conversion-runtime
re-evaluation trigger); the wrapper-catalog bucket (172 KB on the JVM twin) is
already collapsed by the trio itself.

## 2026-08-08 follow-up session: per-operator lever OUT, CLOS lowering IN

Both new levers landed, probe (a) **678,977 -> 585,940
(-13.7%; cumulative 748,091 -> 585,940, -21.7%)**, JVM twin 1,120,321 ->
843,568 (-24.7%). All four backends verified on the probe and on a
no-applicable-method error program (identical output).

- **Per-operator callees cannot pay HERE (measured):** the engine's 17 files
  hold only ~28 generic-sequence call sites (grep of head positions: reverse 4,
  nreverse 9 [always cheap], mapcar 6, position family 3, find 3, count 4,
  remove-if/replace/search/member/every 1-2 each) and the post-trio per-site
  residual is 0.1-0.9 KB (measured by 1-vs-3-site probe deltas: substitute 812,
  remove 810, find 564, position 544, count 444, reverse 444, mapcar 120) -- a
  ~2% bound. Verdict recorded in `.kb/seq-conversion-runtime.md`'s
  re-evaluation trigger; re-open only for a module with dozens of sites of ONE
  operator.
- **The real density was the CLOS lowering** (found by re-bucketing the JVM
  twin: 54 dispatcher bases were 91,325 B, 18% of all method bytes; each slot
  reader 1,721 B for a semantic `(if (%obj-is ...) (%obj-ref ...) (error ...))`).
  Two levers, both landed this session:
  1. **Shared no-applicable-method tail** (`%no-applicable-method`, injected by
     `expandTopLevelDefinitions`, interpreter defines it in
     `defineDispatcher`): the inlined error tail (condition construction + the
     class-naming render, 4 `PRINT-OBJECT-STR` sites per accessor) became one
     call; readers 1,721 -> 389 B, probe -85,266 B. Mechanics `.kb/clos.md`.
  2. **Aligned apply fast path** (`Jvm/WasmApplyCompiler`): a literal `#'m`
     apply whose leading arguments cover the callee's required parameters now
     passes them directly, rest = tail verbatim (or excess consed on) -- the
     build-then-unpack round trip (230 of the probe's 259 `-mN` branch calls,
     ~80-300 B each) is gone. Probe -7,771 B; every variadic dispatcher branch
     and next-chain lambda has this shape. Mechanics `.kb/clos.md`.
- Checked-in browser artifacts rebuilt: 12 of 13 byte-identical (no CLOS, no
  aligned apply); `hiragana/infer.wasm` 540,291 -> 411,770 (-23.8%, the convnet
  layers are CLOS). NOTE: `webgl-battlefront/build.sh` prefers a `rontolisp` on
  PATH -- a stale `/usr/local/bin/rontolisp` (2026-08-07) silently produced a
  1.37 MB module; rebuilt with the repo jar, byte-identical.
- What remains in the probe module (JVM twin buckets after both levers):
  engine defun bodies 151 KB, lambdas 98 KB, %-helpers 72 KB (slot-value
  runtimes 18 KB, SBR-1 8.4 KB), wrapper catalog 48 KB, invoke ladders 25 KB.
  No single identified lever above ~2% is left on this module; the next
  candidates would be the `_invoke` ladders or the per-branch `%obj-is` tag
  ladders, neither measured further.
