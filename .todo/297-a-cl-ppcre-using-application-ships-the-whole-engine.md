# A cl-ppcre-USING application ships the whole engine on WASM

Difficulty: High

An application that calls `ppcre:` itself -- `scan`, `scan-to-strings`,
`regex-replace-all`, a `create-scanner` over runtime input -- pays the whole
engine in its compiled module. Measured twice on 2026-08-08 (same jar, `--no-wasi
--optimize=size`, `.kb/optimize-dead-code-elimination.md` "What ROUTING costs a
clack module"): the engine's share is **732,748 B raw** on the routed Worker
probe (1,219,894 with it, 487,146 without) and **735,122 B** on the
httpbin-tiny-routes example (1,236,811 vs 501,689); gzip roughly 290 KB vs
130 KB. This item is about shrinking that share for a program where the engine
is THE FEATURE and cannot simply leave.

## How this differs from the tiny-routes case (todo-296) -- and what it can reuse

The routing case is SOLVED, by removal: tiny-routes needed only the
`:name`-token template subset, so `tiny-routes/lite` (todo-296) swaps
`path-template.lisp` for a ppcre-free matcher, drops the `:cl-ppcre`
dependency, and the engine leaves whole. That lever does not exist here -- a
program that spells `ppcre:` wants regexes. What todo-296 DID build and this
item can reuse verbatim is the OPT-IN MECHANISM and its contract:

- the delivery: an `AsdOverrides` replacement `.asd` declaring the primary
  system verbatim plus an opt-in secondary system, a substitution keyed by
  that system name only, `ShimLibraries.conflictingSystem` refusing the
  co-load in both orders, and `QuicklispClient`'s slash-name fallback so the
  `ql:quickload` spelling resolves;
- the contract: "matches identically or refuses loudly at build time", pinned
  by ONE corpus run against BOTH engines (`TinyRoutesLiteCorpus` /
  `TinyRoutesLiteE2eTest` / `TinyRoutesLiteUpstreamParityTest` are the
  template);
- the docs shape: an asdf-systems-guide subsection (en+ja) stating the exact
  accepted subset and the escape, a Worker example with the size table.

## Already measured -- do not re-derive

All in `.kb/optimize-dead-code-elimination.md` (todo-295, 2026-08-08):

- **The 8 `define-compiler-macro`s are not a size lever.** The routed module is
  byte-identical with all eight stripped; where one fires (a literal
  `(ppcre:scan "..." x)`) it ADDS 179 B and removes nothing, because the
  scanner BUILDER still ships to run at load time.
- **Parse-half/match-half splitting cannot pay.** A cl-ppcre scanner is a tree
  of closures closing over each other (`closures.lisp`); no mechanism can
  serialize one into an artifact, and `load-time-value` runs INSIDE the module,
  so the builder ships whatever the call sites look like.
- **The zero-reference anchor is 823,589 B**: with the engine loaded but
  unreferenced, only 0.9% leaves, because `LibraryDefunPruner` keeps every
  `defgeneric`/`defmethod`/`defclass`/`defstruct` as a root and every method
  body is a load-time closure in `valueFuncIds`, dispatchable through the
  funcall ladders. That number is the measured ceiling of `.todo/290`'s
  CLOS-aware-shaking lever ON THAT MODULE -- but it is the ZERO-reference
  case; a USING app keeps some of it genuinely live.

## The levers to measure and design (the item)

1. **The per-feature cost map comes first.** Probe programs, each built at
   `--optimize=size` on wasm-GC: (a) one literal `ppcre:scan`; (b) plus
   `scan-to-strings` + registers; (c) plus `regex-replace-all` with `\1`
   substitutions; (d) `create-scanner` over runtime input; (e) `split`.
   Today's numbers measure ROUTING's usage only. Everything below is decided
   by this map -- e.g. if scanner building (lexer -> parser -> converter ->
   optimizer -> closure compiler) dominates and is live even for (a), the
   shaking levers cannot pay and only 4/5 can.
2. **CLOS-aware shaking (`.todo/290`'s lever) on a LIVE engine.** How much of
   the node-class/method surface is reachable from ONE used entry point?
   Measure against the 823,589 B zero-reference ceiling; expect much less
   yield here, since building any scanner walks the whole pipeline. Measure,
   do not assume.
3. **`LibraryDefunPruner` over ASDF-spliced third-party trees.** Today the
   pruner covers only rontolisp's own bundled libraries
   (`.kb/library-defun-pruning.md`); extending it to spliced trees is
   defun-level only, and cl-ppcre's core is CLOS-anchored, so expect modest
   yield alone -- but it composes with 2 and helps every other library too.
4. **An opt-in engine-subset substitution, `cl-ppcre/lite` in the todo-296
   pattern.** A compact scanner -- no CLOS node tree, no closure compiler; a
   direct matcher over a parsed tree -- for the common syntax subset
   (literals, `[...]` classes incl. ranges/negation, `.`, the `* + ? {m,n}`
   quantifiers incl. lazy variants, `^ $`, groups + alternation,
   `\d \w \s \b` escapes, the `:case-insensitive-mode` keyword), SIGNALING at
   `create-scanner` time on everything else (lookaround, backreferences,
   properties, embedded-flag groups, filters, ...). The mechanism is already
   built (above); the ENGINE and its corpus are the work, and the parity bar
   is far higher than path templates: greedy/lazy backtracking order, class
   edge cases, case folding, `scan-to-strings`/`regex-replace-all` register
   semantics -- all pinned against the real engine on all four backends
   before it may ship.
5. **Compile-time lowering of LITERAL regexes into emitted code.** Not the
   rejected compiler-macro route: the point would be that when EVERY `ppcre:`
   call site in the program carries a literal regex, the lowered matchers are
   emitted as code and NO runtime builder ships at all. Shares the
   semantics/corpus work of 4; its advantage is that identical semantics need
   no opt-in (behavior does not change, only bytes), its risk is the cliff --
   one dynamic regex anywhere brings the whole engine back -- which must at
   least be visible, not silent.

## Non-goals

- Changing what plain `(ql:quickload "cl-ppcre")` loads. The verbatim library
  stays the default; substitution is opt-in only (the todo-295/296
  principle).
- Re-opening the routing case: a routed application opts into
  `tiny-routes/lite`, not into anything here.
- Shrinking the clack base itself (`.todo/290`).

## Done when

- The per-feature cost map (lever 1) exists and is recorded in
  `.kb/optimize-dead-code-elimination.md` beside the routing numbers.
- Each lever above has a measured yield or a recorded reason it cannot pay.
- Whatever ships meets the todo-296 bar: identical-or-loud, one corpus pinned
  against the real engine on all four backends, opt-in spelling documented in
  the asdf-systems guide (en+ja) -- or the item is re-filed with the
  measurements and a chosen direction.
