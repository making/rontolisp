# Post-A2 case-tolerance seam simplification (optional, behavior-neutral)

Raised 2026-07-20 as a follow-up to the A2 uppercase-canonical cutover (`.todo/156`).
That cutover DELETED the fold machinery (`UpcaseSymbols`, `Features.INTERNAL`, JVM
`_canon`, WASM `emitCanon`). It deliberately KEPT several case-TOLERANCE seams because
they are *functional* (not the fold) and removing them is a separate, riskier change.
Keeping them is behavior-neutral. This todo tracks assessing/removing the ones that are
now provably dead under A2. **Priority: LOW -- pure hygiene, nothing is broken.**

## Candidates

1. **`--component` LOWER dual-compares.** -- DONE 2026-07-21.

   `WasmComponentImportCompiler` compared a Lisp tag against BOTH cases on the way OUT to
   the canonical ABI (`:get` OR `:GET` via `I32_OR`), and `wit.lisp`'s `%wit-result`
   accepted both envelope heads (`:ok`/`:OK`).
   - Confirmed every feeder now yields the UPCASED tag: the internal constructions
     (`http.lisp` `%fetch-method-variant`/scheme, `sockets.lisp` `%sock-addr`/
     `tcp-socket-create :ipv4`) are literal keywords that upcase under A2, there is NO
     dynamic keyword construction (`intern`/`make-keyword`) in any spliced `.lisp`, and
     the LIFT already spells every tag/plist-key/envelope-head upcased. So the lowercase
     arm was dead.
   - Dropped the lowercase arm at all four `WasmComponentImportCompiler` LOWER sites
     (`emitLowerVariantParam`, `emitLowerRecordParam`, `emitLowerVariantAt`,
     `emitLowerRecordAt`) and collapsed `%wit-result` to the upcased envelope head. Did
     NOT touch the LIFT emission (stays upcased-only).
   - Also collapsed the sibling lift-facing Lisp case-twins that A2 made `(or A A)` --
     both arms read the identical upcased symbol: `http.lisp` `%serve-method-string`,
     `sockets.lisp` `%sock-addr-string`, `usocket.lisp` protocol check. Fixed the stale
     `Features.INTERNAL` comment in `%serve-method-string`.
   - Validation: `./mvnw test` GREEN (4052 tests, incl. the 754 Docker WASM integration
     run on real wasmtime 46 + `WasmComponentImportCompilerTest`'s lift-upcased and
     byte-identity pins) and a native `CiSpecE2eTest` re-run (byte-identical output on all
     four backends). Documented in `.kb/reader-case-upcase.md` ("Removed seam").

2. **`LispNames.keywordMatches` (equalsIgnoreCase) + `foldKeyword`.** -- ATTEMPTED and
   BACKED OUT 2026-07-21; DEFERRED (user decision 2026-07-22) to a dedicated
   designator-system refactor in a SEPARATE session. It is not a simple collapse -- it is
   entangled with the lowercase-canonical WIT/CLOS designator system.

   The builtin keyword-argument matchers accept `:TEST` where `:test` is meant. Under A2
   the reader upcases every SOURCE keyword, so for a source keyword an exact `.equals`
   against an upcased literal would suffice -- which is what made this look like a
   behavior-neutral tidy-up.

   What the attempt found (2026-07-21):
   - **Scope:** 68 `keywordMatches` call sites across 19 files + 12 `foldKeyword` sites
     across 4 files. The canonical arguments are a MIX of already-upcased `*_KEYWORD`
     constants, lowercase inline literals, and variables; the first argument is almost
     always a parenthesized `X.name()`, so a regex literal-sweep is unreliable.
   - **`keywordMatches` also bridges Java-SYNTHESIZED lowercase keywords, not just
     source-vs-runtime.** Changing it to an exact match (upcasing the canonical inside the
     matcher, `canonical.toUpperCase().equals(symbolName)`) compiled and passed 4051/4052
     tests but BROKE `WitScaffolderTest`: `WasmExportCompiler.T_VOID = ":void"` is a
     LOWERCASE-canonical WIT type designator, and `WitExportDirective` synthesizes a
     lowercase `:void` symbol into the `wasm-export` form. `keywordMatches(sym.name(),
     T_VOID)` was matching BOTH a source-upcased `:VOID` AND that Java-synthesized
     lowercase `:void` against the lowercase canonical -- exactly the case-insensitivity
     an exact match removes. The whole `wasm-export`/`wasm-import` type-designator system
     (`:int`/`:void`/`:string`/...) is lowercase-canonical internally (it faces WIT type
     names), and `foldKeyword` likewise produces lowercase designators consumed downstream
     by `KNOWN_TYPES.contains`, per-type `switch`es, CLOS initarg maps and method
     qualifiers -- a cascading system, not a local comparison.
   - **Conclusion:** making keyword matching exact would require either upcasing every
     Java-side synthesized designator AND its downstream lowercase switches/Sets/maps, or
     reworking the lowercase-canonical WIT designator system -- a multi-subsystem refactor
     for NEAR-ZERO behavioral value (under A2 the case tolerance is only reachable via a
     verbatim runtime `intern` or such internal lowercase synthesis, never from source).
   - **Plan (DEFERRED to a separate session):** pursue this deliberately as a refactor of
     the DESIGNATOR SYSTEM -- make the wasm-export/import WIT type designators
     (`:int`/`:void`/`:string`/...) and the CLOS initarg / method-qualifier matching
     uppercase-canonical end to end (upcase `T_VOID`/`KNOWN_TYPES`/the per-type `switch`
     labels/the Java-synthesized designators in `WitExportDirective`, and the CLOS initarg
     maps), THEN collapse `keywordMatches` to `.equals` and inline `foldKeyword`. Not a
     `keywordMatches` one-liner. Verify with the full JVM suite + Docker WASM integration +
     a native `CiSpecE2eTest` re-run; the `WitScaffolderTest` `:void` case is the canary.
   - Until then the `equalsIgnoreCase`/`foldKeyword` seams STAY (documented in
     `.kb/reader-case-upcase.md` under "Case-tolerance seams that REMAIN"). They are
     harmless: under A2 the case tolerance is unreachable from source.

## Validation

Behavior must stay byte-identical: `./mvnw test` (incl. Docker WASM integration) AND a
native `CiSpecE2eTest -Drontolisp.binary=<path>` re-run after `-Pnative` build. Deleting
a truly-dead arm is byte-identical; if the native ci-spec shifts, the arm was NOT dead.

## Not in scope

The A1 real-intern-table go/no-go is `.todo/156` Phase 5 (a strategic decision, deferred).
`.todo/153` (WASM non-ASCII code-char) is an independent pre-existing limitation.
