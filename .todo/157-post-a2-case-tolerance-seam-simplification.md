# Post-A2 case-tolerance seam simplification (optional, behavior-neutral)

Raised 2026-07-20 as a follow-up to the A2 uppercase-canonical cutover (`.todo/156`).
That cutover DELETED the fold machinery (`UpcaseSymbols`, `Features.INTERNAL`, JVM
`_canon`, WASM `emitCanon`). It deliberately KEPT several case-TOLERANCE seams because
they are *functional* (not the fold) and removing them is a separate, riskier change.
Keeping them is behavior-neutral. This todo tracks assessing/removing the ones that are
now provably dead under A2. **Priority: LOW — pure hygiene, nothing is broken.**

## Candidates

1. **`--component` LOWER dual-compares.** `WasmComponentImportCompiler` compares a Lisp
   tag against BOTH cases on the way OUT to the canonical ABI (`:get` OR `:GET` via
   `I32_OR`), and `wit.lisp`'s `%wit-result` accepts both envelope heads (`:ok`/`:OK`).
   Rationale under the OLD model: internal lowercase-authored sources (`http.lisp`
   `%fetch-method-variant`/`%serve-method-string`, `sockets.lisp` `%sock-format-quad`)
   constructed the LOWERCASE tag while user data was upcased. Under A2 every library
   `.lisp` is read UPCASED, so those internal constructions now produce the UPPERCASE
   tag and the lowercase arm is likely dead.
   - Action: for each dual-compare, grep the internal construction that feeds it and
     confirm it now yields the upcased tag; then drop the lowercase arm (keep the LIFT
     upcased — it faces user data). Do NOT touch the LIFT direction.
   - Pinned by: `WasmComponentImportCompilerTest` (lift emits upcased, never a lowercase
     fallback) and `ServeMethodCaseComponentE2eTest` (every HTTP method round-trips
     through the real `wasi:http` `method` variant). Both must stay green.

2. **`LispNames.keywordMatches` (equalsIgnoreCase) + `foldKeyword`.** The builtin
   keyword-argument matchers accept `:TEST` where `:test` is meant, at ~20 sites (the
   helper choke points, the `findKeywordValue` copies, the option `switch`es). Under A2
   the reader upcases every keyword at BOTH the call site and the matcher's own literal,
   so an exact `.equals` would suffice and the case-insensitivity is no longer reachable
   from source. Could collapse `keywordMatches` to `.equals` and inline `foldKeyword`.
   - Caution: ~20 files; low value (behavior-neutral); easy to miss a site. Only worth it
     for the tidiness. A careful sweep + full JVM suite + native `CiSpecE2eTest` re-run.

## Validation

Behavior must stay byte-identical: `./mvnw test` (incl. Docker WASM integration) AND a
native `CiSpecE2eTest -Drontolisp.binary=<path>` re-run after `-Pnative` build. Deleting
a truly-dead arm is byte-identical; if the native ci-spec shifts, the arm was NOT dead.

## Not in scope

The A1 real-intern-table go/no-go is `.todo/156` Phase 5 (a strategic decision, deferred).
`.todo/153` (WASM non-ASCII code-char) is an independent pre-existing limitation.
