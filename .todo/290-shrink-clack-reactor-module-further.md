# Shrink the clack reactor module further (the ~2x that remains after the gate split)

Difficulty: High

The 2026-08-08 dispatch-gate refinement (`.kb/optimize-dead-code-elimination.md`,
"The symbol BUILDERS no longer bail") halved the clack Worker builds:
`examples/cloudflare-workers/hello-clack` 1,133,471 -> 514,999 B, `httpbin-clack`
1,156,633 -> 534,777 B. The clack-vs-no-clack factor is now 2.1x raw / 1.9x gzip
(was 4x). This item collects the measured next levers for whoever wants the rest.

## Where the remaining bytes are (hello-clack, 514,999 B)

`twiggy top` on the shaken module (2026-08-08): the largest single item is a
~34 KB data segment (the string blob; it was ~76 KB before the split -- the
registry rows that went took their name strings with them), then a long tail of
clack/lack/bordeaux/usocket-shim defuns (~630 items, none over 26 KB) that are
genuinely reachable from `clackup`'s call graph plus the ~170 dispatchable rows.

## Levers, in rough value order

1. **String-blob dead ranges under `usesIntern`.** The blob's droppable-range
   pass stands down entirely for a program that interns
   (`.kb/optimize-dead-code-elimination.md`, "the `usesIntern` bail is the
   coarse half") -- and every clack program interns (handler discovery).
   The recorded re-evaluation trigger applies: make it per-entry (drop a range
   when `_intern` is unreachable, or filter the intern blob's rows post-shake).
   A clack module is exactly the case the trigger asks for: interns AND has a
   large dead-wrapper string set. Share measured 2026-08-08 on the ROUTED
   worker probe (todo-295): the blob segment is 58,756 B of an 81,003 B data
   section -- the cap on this lever, an order of magnitude under lever 3.
2. **Probe over-approximation on gate-closed programs.** The widened probes
   (framed string literal, keyword member) cost a gate-closed program a few KB
   of extra rows (`httpbin` 245,525 -> 248,956; the `--component` build
   +1.3%). A refinement: apply the framed-string / keyword probes only when the
   program contains a matching BUILDER operator (`intern`/`find-symbol`/
   `uiop:symbol-call`...) -- without one, no runtime path can turn a string
   into a designator, so the original three probes suffice. Cheap, and would
   also claw back part of the clack rows.
3. **CLOS-aware shaking.** `LibraryDefunPruner` keeps every
   `defclass`/`defgeneric`/`defmethod` as a root (`.kb/library-defun-pruning.md`,
   "What stays a root"); lack-component's CLOS surface rides into every clack
   module through it. Already recorded there as "a separate item, not a tweak".
   Ceiling measured 2026-08-08 (todo-295): a clack+tiny-routes worker whose
   cl-ppcre is loaded but ZERO-referenced sheds only 0.9% -- 823,589 B
   (648 functions) stay anchored through CLOS roots at the AST level plus
   load-time method closures at the module level
   (`.kb/optimize-dead-code-elimination.md`, "What ROUTING costs a clack
   module"). Both halves (pruner roots AND `valueFuncIds`) must learn CLOS
   for the bytes to move.
4. **EH mode.** The cloudflare shim's `handler-case` puts the module in EH mode
   (condition system + tags). Inherent to "handle answers 500", not removable --
   but its cost has never been measured on the POST-gate-split module; measure
   before assuming it still "roughly doubles" anything
   (`examples/cloudflare-workers/httpbin/README.md` still carries that phrase
   from the pre-split era).

## Non-goals

- Reopening the gate semantics: the carve-out (a name forged from computed
  pieces stops resolving; `--dynamic` restores) is settled and test-pinned.
- The `--no-wasi` filesystem stub (`compiler/NoWasiFilesystemStubs`) is done and
  documented in `.kb/wasm-export-no-wasi.md`; extending it to `random`/time is
  `.todo/284`'s question, and the input-fabrication asymmetry recorded there
  still forbids it.
