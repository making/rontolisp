# Symbol model redesign: adopt CL's intern-table + uppercase-canonical (Approach A)

Raised by the user 2026-07-20 after `.todo/155` item 3 (runtime `read` fold)
landed. The user's judgement: the reader-upcase **premise** should have been
built the CL way from the start (**Approach A** — a real symbol/intern table with
UPPERCASE-canonical standard symbols), not the way it was actually built
(**Approach B** — keep lowercase as canonical and fold on top). Approach B has
spread special cases across the whole system and made the internal rules hard to
reason about. The user also notes they had earlier questioned whether NOT having
an intern table would cause problems later and were told at the time that the
no-intern-table model was better; the accumulated complexity below vindicates
that original concern. This todo is the plan to fix it properly.

## The problem: Approach B's fold-on-top has too many seams

Because rontolisp keeps **lowercase** as the canonical spelling (a symbol IS its
verbatim name string, matched case-SENSITIVELY, no intern table) and layers CL's
upcase reader on top as a *fold*, every place that touches names needs its own
special case. Current seams (all in `.kb/reader-case-upcase.md`):

1. `UpcaseSymbols.canonicalize` — the fold itself, plus `foldableBareNames()` /
   `EXTRA_CANONICAL`, hand-kept in sync with `PackageRegistry.CL_SYMBOLS`,
   `ClosRegistry`'s seeded condition types, and the reader constants.
2. `Features.INTERNAL` (case-preserving) — every read of rontolisp's OWN lowercase
   `.lisp`/`.asd` sources MUST remember to pass it, or the library breaks with
   `Undefined function` (a footgun for any new internal read).
3. Case-insensitive keyword-arg matching — `LispNames.keywordMatches`/`foldKeyword`
   swept through ~15 builtin choke points + keyword `switch`es.
4. Runtime `intern`/`find-symbol` fold (`LispEvaluator.foldRuntimeSymbolName`),
   and its GAP: the compiled backends' `intern` does NOT fold a CL-name string, so
   `(intern "TIME")` is `time` on the interpreter but `TIME` on JVM/WASM — a live
   interp-vs-compiled divergence.
5. Case-flip retries (one-shot, scattered): `PackageResolver.resolveUnqualified`/
   `resolveQualified`, `ClosRegistry.slotPosition`, `JvmEvalRuntimeBuilder`
   (function/variable/apply lookup).
6. WASM has NO such bridge (offset identity) — `.todo/155` item 1 (runtime-load
   symbols reachable only under a compile-time-interned offset) exists ONLY because
   of this model.
7. Runtime `read` fold (item 3, this landed): JVM `_canon`, WASM `emitCanon`, a
   ~5.6 KB fold-set blob baked into EVERY read-using JVM class and WASM module, a
   fold-whole-or-keep SIMPLIFICATION with two pathological package-qualified
   deviations from the interpreter, and an ASCII-only WASM limitation.
8. `HttpPlistShape` keys forced UPPERCASE; component rich-params keywords
   dual-compared + upcased on lift (item 2); `affixFor` case-matching synthesized
   names to their base; WIT/WASM host names `toLowerCase`d back down.
9. `symbol-name`/print report standard names lowercase (`(symbol-name 'car)` is
   `"car"`, CL says `"CAR"`) — a permanent CL deviation, and the root reason the
   fold set even has to exist.

Every one of these is a consequence of "lowercase canonical + fold", and each new
name-touching feature has to re-learn all of them.

## Approach A: what "the CL way" means here

Make the reader's upcase premise structural instead of a fold:

- **Standard symbols are UPPERCASE-canonical.** `car` interns/stores as `CAR`,
  `defun` as `DEFUN`, `:test` as `:TEST`, `&optional` as `&OPTIONAL`, `t`/`nil` as
  `T`/`NIL`. `(symbol-name 'car)` becomes `"CAR"` (CL-correct; deletes deviation #9).
- **Identity is settled once, at read/intern time** (the reader upcases; an intern
  table or an uppercase-exact match gives identity), so NOTHING downstream needs a
  fold or a case-flip. Deletes seams #1, #3, #5, #6, #7, and the #4 divergence.
- `Features.INTERNAL` (#2) changes meaning: rontolisp's own `.lisp` sources are
  currently lowercase-authored; under Approach A they either (a) are re-read with
  the upcase reader so their defuns/helpers become UPPERCASE (then every Java-side
  matcher for internal helpers must be uppercase too), or (b) keep a
  case-preserving internal read but then the internal names stay lowercase and must
  be reachable — i.e. the seam does not fully vanish. Decide this explicitly.

### Two variants to weigh

- **A1 — full intern table.** A real symbol object with identity (like CL's
  package/symbol model). Biggest cleanup (enables `eq` by identity, `keywordp`,
  `gensym` distinctness, package homing for free) but the largest change: the core
  value model (`LispSymbol` is currently a bare name string) and every backend's
  name mangling would move to interned identity.
- **A2 — uppercase-canonical, keep string identity.** Reader always upcases;
  ALL canonical names (builtins, special forms, macros, keyword args, lambda-list
  markers, `t`/`nil`, condition types) are stored/compared UPPERCASE; `LispNames`
  constants flip to uppercase; no intern table object. Removes the fold /
  `foldableBareNames` / case-flip / per-backend fold reimplementation (#1,#3,#5,#7)
  while leaving the value model alone. Smaller than A1, still large.

Recommendation: evaluate **A2 first** (it deletes most of the seam surface for a
fraction of A1's blast radius); reach for **A1** only if a later feature genuinely
needs symbol identity beyond string equality.

## Cost / blast radius (why this is a major undertaking, not a quick fix)

- **`LispNames`** — hundreds of lowercase name constants; flipping to uppercase
  touches every reference (reader, evaluator, macro expander, all compilers).
- **Library `.lisp` sources** (json/url/linalg/vec/usocket/wit/gray/http/sockets/
  stdin/wait/prelude + shims) are lowercase-authored — must be re-read consistently
  or explicitly kept lowercase, and every `%helper` matched Java-side updated.
- **Compilers** — JVM method mangling and the WASM string table key on the name
  bytes; every baked string literal and the `--component` blobs would churn.
- **ci-spec / DocExamplesTest** — `symbol-name`/print output flips for standard
  names (`car` -> `CAR`) across a large number of pinned expectations.
- **Docs** — the entire "canonical spelling is lowercase" story (`reader-case.md`
  Deviations section) is rewritten; the CL-deviation shrinks (a plus).

## Suggested phasing (each independently shippable, all-four-backends green each step)

1. **Spike + decision:** prototype A2 on the interpreter only (reader upcases,
   `LispNames`/matchers uppercase, `symbol-name 'car` = "CAR"); measure how much of
   `UpcaseSymbols`/`keywordMatches`/case-flip actually deletes. Choose A1 vs A2.
2. **Interpreter cutover** to uppercase-canonical; delete the now-dead fold paths.
3. **Per-backend cutover** (JVM, then WASM P1, then component), regenerating the
   baked strings/blobs and the ci-spec expectations; delete `_canon`/`emitCanon`
   and the fold-set blob (item 3's machinery) since the reader no longer folds.
4. **Retire the seams:** `Features.INTERNAL`, `keywordMatches`/`foldKeyword`,
   the case-flip retries, `HttpPlistShape`'s forced-uppercase (now natural),
   `.todo/155` item 1 (the WASM offset bridge — moot once identity is settled at
   read). Fold the survivors into the new model's docs/kb.

## Open questions to settle before starting

- A1 vs A2 (symbol identity vs string identity) — the pivotal call.
- Internal `.lisp` sources: re-read upcased, or a preserved-case island? (#2 above.)
- `intern`/`make-symbol`/keyword semantics under the new model (keywords become
  `:UPPERCASE`; does any data path rely on the current keyword spelling?).
- Migration ordering vs the still-open `.todo/155` item 1 (this work SUBSUMES it —
  do not do item 1 separately; close it into this).

## Status

NOT STARTED. This supersedes `.todo/155` item 1 (the WASM runtime-load offset
bridge) — that should be closed into this redesign rather than solved on its own.
Prereq reading before any work: `.kb/reader-case-upcase.md`,
`.kb/core-representation.md` (JVM method mangling, WASM string table),
`.kb/symbol-runtime-api.md` (the original no-intern-table assessment this revisits).
