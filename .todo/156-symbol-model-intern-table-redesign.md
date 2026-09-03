# Symbol model redesign: uppercase-canonical (Approach A2), intern table deferred

Raised by the user 2026-07-20 after `.todo/155` item 3 (runtime `read` fold)
landed. The lowercase-canonical + fold-on-top model (Approach B) spread case
special-cases across the whole system; the CL way is uppercase-canonical
(Approach A). **Decision (2026-07-20): execute A2 first** — uppercase-canonical,
keep string identity, delete the fold — because the accumulated complexity the
user reacted to is caused entirely by *lowercase-canonical*, not by the missing
intern table, and A2 deletes all of it. **A1 (a real intern table with symbol
identity) is deferred to a go/no-go decision AFTER the cutover lands.**

## ⟶ STATUS (2026-07-20): CUTOVER COMPLETE + COMMITTED; only the deferred A1 (Phase 5) remains

The A2 cutover is DONE, validated on all four backends, and COMMITTED to `develop` + pushed
(see git log). This todo is kept open ONLY to track the deferred **Phase 5 (A1 intern-table
go/no-go)** below, plus the optional follow-up in `.todo/157`. Nothing else is open.

Validated green before the commit (native binary at `target/rontolisp`):
- `./mvnw test` = **4052 / 0 failures / 0 errors**, 7 skipped (incl. Docker WASM integration)
- native `CiSpecE2eTest -Drontolisp.binary=$PWD/target/rontolisp` = **908 / 0** (all 4 backends)
- `DocExamplesTest` 542/0; `javadoc:jar` = only the known `Version` build-time error; `-Pweb compile` OK

When Phase 5 is decided (or dropped), close this todo per the global protocol: delete it in a
commit, then record its `.todo/.history.md` row (path, date, status, the commit ID that REMOVED
the file, model from the work commits' `Co-Authored-By`) in a SECOND commit. `.todo/155` is also
closeable (its Gap A — the reader case-fold — is what this cutover solved).

The rest of this file is the historical decision record + phase-by-phase implementation log.

## Why A2, and why the intern table is a separate axis (decision record)

The seams below (fold, `Features.INTERNAL`, `keywordMatches`, case-flip retries,
per-backend fold blobs, WASM offset bridge, `symbol-name` deviation) are ALL a
consequence of "lowercase-canonical + fold". Uppercase-canonical (A2) deletes
every one of them. The intern table (A1) is an ORTHOGONAL axis about symbol
*identity* (eq-by-object, true uninterned symbols, `unintern`/`:shadow`, package
homing) — none of which any current library or roadmap item needs
(`symbol-runtime-api.md` §15). A1 is a strict SUPERSET of A2 whose mandatory
first phase IS the uppercase cutover, so the two share Phases 1-4 regardless.

Confirmed facts that make A2 low-risk (from the 2026-07-20 seam-mapping sweep):

- Symbol equality is ALREADY name-string equality on all three backends
  (`Environment.isEq`/`eqlValue` = `a.equals(b)` over `record LispSymbol(String)`;
  JVM `_eq`/`_eqv` over the baked String; WASM `eq` over the deduped string-table
  offset). So A2 changes the eq/eql/equal machinery NOT AT ALL — case was never a
  factor, only consistency.
- `intern`/`make-symbol` become plain verbatim (CL-correct: they do NOT upcase
  their string argument; only the reader upcases). This CLOSES the current
  interp-vs-compiled `(intern "TIME")` divergence *naturally*: with `time` stored
  as `TIME`, `(intern "TIME")` names the standard `TIME` on every backend, and
  `(intern "car")` is a distinct `"car"` symbol exactly as in CL. The interpreter
  fold `LispEvaluator.foldRuntimeSymbolName` is deleted.
- The WASM offset gap (`.todo/155` item 1) VANISHES under A2 (verified): the reader
  always upcases with no fold, so a runtime `read` of a name that was baked at
  compile time produces byte-identical bytes and `_intern`'s compile-time-table
  scan HITS the baked offset. A Java-level intern table (A1) does NOT close this —
  the WASM offset table is itself a per-module intern table, and the runtime
  `_intern` re-intern rail already exists. **This work SUBSUMES `.todo/155` item 1**;
  do not solve it separately — close it into this redesign.

## Settled sub-decisions (apply during the cutover; do not re-litigate)

- **Everything uppercase, including package names.** The reader upcases the WHOLE
  token; keeping package names lowercase would require folding just the package
  part = reintroducing a fold. So `cl`→`CL`, `rontolisp`→`RONTOLISP`,
  `vec:aref`→`VEC:AREF`. `PackageRegistry`'s keys / `isBuiltinPackageName` /
  `canonicalBuiltinName` / `splitQualified` move to uppercase.
- **Keywords → `:UPPERCASE`** (`:test`→`:TEST`), **lambda markers → `&UPPERCASE`**
  (`&optional`→`&OPTIONAL`), **`%`-internal helpers → uppercase alpha** (`%aset`→
  `%ASET`, reconcile every Java-side `new LispSymbol("%...")` synthesis site).
- **`t`/`nil` stored `T`/`NIL`** (flip atomically across every baked literal).
- **Library `.lisp` sources are READ upcased** (drop `Features.INTERNAL` for the
  symbol-bearing sources): their defuns/`%`-helpers become UPPERCASE, matching CL
  and the upcased user references. The `.lisp` FILES stay lowercase-authored (the
  reader upcases them) — no file edits, but a CONTENT PASS is required to catch any
  lowercase-literal self-comparison inside them.
- **`.asd` data and `ShimLibraries` system/file designators stay a lowercase
  island.** System names and component filenames are designators, not symbols;
  ASDF `coerce-name` downcases (`AsdfSystems.symbolName`). Document this asymmetry.
- **`intern`/`make-symbol` are verbatim; runtime `read`/`read-from-string` upcase.**
  Split them (current `foldRuntimeSymbolName` folded both — delete it).
- **`gensym` prefix uppercase `G`** (CL). `symbol-name` of a standard symbol is now
  `"CAR"` (CL-correct); a user symbol stays `"FOO"`. Ours still spells `g`, which is
  observable rather than cosmetic; split out as `.todo/640`.
- **`HttpPlistShape` forced-upcase STAYS** — it is a host-ABI bridge (lowercase WIT
  field → uppercase keyword), NOT fold compensation. Deleting it would emit
  lowercase keys the reader never produces. Only reword its comment. Same for the
  component LIFT `.toUpperCase` sites (they build keywords the served Lisp reads).
- **Component rich-params LOWER dual-compare (`:get` OR `:GET`) collapses to a
  single uppercase compare**; the LIFT (upcased) sites are untouched.
- **`keywordMatches`/`foldKeyword` are removed** (tighten to exact `.equals` /
  identity) so a missed flip surfaces as a failure instead of being masked by
  `equalsIgnoreCase` tolerance.
- **Host-ABI `toLowerCase` sites STAY** (component/WIT/P1 names are lower-kebab
  regardless of Lisp case): `WitExportDirective` worldName/label, `WitImportDirective`,
  `WasmImportDirective`, `WasmExportCompiler`, HTTP-method value upcase.

## Micro-decisions to confirm while implementing (not blockers)

- `(find-symbol "car")` / `(intern "car")` with a lowercase literal: with the fold
  gone this is a distinct `"car"` (strict CL) — update the tests/docs args or
  expectations accordingly (`LispEvaluatorTest` ~6150-6152, `ci-spec` ~3655-3656,
  `find-symbol.md`). Settle the arg-casing once and apply everywhere.
- `make-symbol` cross-backend divergence to pin: interp/JVM make two
  `(make-symbol "X")` eq (same `"#:X"` name), WASM builds a fresh struct (non-eq).
  Pick ONE target under A2 (keeping the name-based faking with a documented
  deviation is fine; true distinctness needs A1) and pin it on all four backends.

## Phase plan (each phase: all four backends green, then commit)

Line anchors below are approximate (2026-07-20 HEAD) — verify before editing.

### Phase 1 — Interpreter cutover to uppercase-canonical
- Delete `am.ik.rontolisp.UpcaseSymbols` entirely.
- `LispLexer.readSymbol` (~534/546): drop `canonicalize`; the token is already
  upcased. Keep the `preserveCase` branch only for the `.asd`/shim island.
- `LispReader` (~209/212/215/220): flip `nil`/`t`/`pi`/`most-*-fixnum` constant
  compares to uppercase. Decide `*features*` member case (`:RONTOLISP`).
- `LispEvaluator.foldRuntimeSymbolName` (~305): delete; inline at ~566/591.
  `intern`/`make-symbol` become verbatim.
- `LispNames`: flip the ~577 alphabetic CL/library-member constants, 7 lambda
  markers, 4 type-spec (`DOUBLE_FLOAT`/`SINGLE_FLOAT`/`UNSIGNED_BYTE`/
  `CHARACTER_TYPE`), 6 special-vars, 29 keyword designators, 52 `%`-helpers, ~14
  package names to uppercase. LEAVE the 12 pure-operator constants
  (`+ - * / = < > <= >= /= 1+ 1-`) untouched. The 19 composed `PKG:member`
  constants auto-track. Rewrite the case/no-intern-table Javadoc on
  `SYMBOL_NAME`/`INTERN`/`FIND_SYMBOL`/`GENSYM`/`MAKE_SYMBOL`.
- Delete `keywordMatches` (`LispNames`~3645) and `foldKeyword` (~3656); flip the
  ~30 inline keyword args + ~55 inline `switch` labels; delete
  `LambdaLists.upcasedTwin` (~533) + the dual `:allow-other-keys` (~553-564).
- `PackageRegistry`: uppercase `CL_SYMBOLS`/`CL_TYPES`/package names/nicknames;
  `isBuiltinPackageName`/`canonicalBuiltinName`/`splitQualified` accept uppercase.
- `PackageResolver`: delete the lowercase retries (~333-336, 353-354, 602-605,
  689-699); keep the `isCarCdrComposition` allowance (~634, semantic, not a fold).
  Re-home the two genuine non-reader lowercase sources (WIT lower-kebab ~602,
  shim leaf packages ~238) explicitly instead of by fold.
- `ClosRegistry.slotPosition`: delete the bidirectional retry (~467-477); register
  slots under the single uppercase baseName. Uppercase the seeded condition types.
- Library sources: read the 20 `.lisp` under `resources/am/ik/rontolisp/eval`
  upcased (drop `Features.INTERNAL` for them; keep it for `.asd`/shims); flip the
  ~10 raw literal matchers (`GrayStreamsLibrary` 64/65/67/69/114, `StdinLibrary`
  58/62, `HttpLibrary` 120 +inline 145-148, `LispEvaluator` 1611/1612); remove the
  3 defensive `.toLowerCase()` (`VecLibrary`~147, `LinalgLibrary`~145,
  `UsocketLibrary`~171). CONTENT-PASS all 20 sources for lowercase self-compares.
- `Environment`~276 `"equal"`/`"equalp"` → uppercase (or route via `LispNames`).
- Pin: `LispEvaluatorTest`, `LispReaderTest`.

### Phase 2 — JVM backend
- `JvmReadRuntimeBuilder`: delete `buildCanon` (~847-953) + `buildDelimContains`
  (~959-970) + their field/CP/blob machinery; `buildClassify` (~723) replace the
  `_canon` call with unconditional `toUpperCase(ROOT)`; flip `nil`/`t` literals
  (~728/738/740) to `NIL`/`T`.
- Delete the 3 `JvmEvalRuntimeBuilder` case-flip retries: function lookup
  (~700-732), variable (~1573-1621), apply (~2468-2572) — and revert the `ARITY`
  slot to arity-only (drop the ~2477-2478 init and ~2551-2552 consume guard),
  keeping the variadic negative-arity dispatch intact.
- `JvmEmitHelper.compileTrue` (~97) `"t"`→`"T"`; `JvmSymbolApiCompiler` (~275)
  `"t"`→`"T"`. `mangleMethodName`/quote/symbol-name/intern/make-symbol are
  case-agnostic — only their baked bytes churn.
- Pin: `JvmLispCompilerTest`.

### Systematic bug classes found during Phase 1 (Phase 2/3 WILL hit the same in the backends)
The interpreter cutover surfaced recurring failure shapes; the JVM/WASM eval-runtime
and codegen have the same shapes and MUST be swept for each:
1. **Lowercase char checks for car/cdr compositions** (`== 'a'`/`'d'`): interpreter had
   `isCarCdrComposition`, `expandCarCdrComposition`, `expandSetfCarCdr` (setf/incf cadr
   silently wrong). Backends: `JvmEvalRuntimeBuilder` (~1487 `op.charAt(1)=='d'`),
   `WasmEvalRuntimeBuilder` (~2274 `arr[1]=='d'`) — flip to `'A'`/`'D'`.
2. **`switch(foldKeyword(x))` with `LispNames.*_KEYWORD` (now UPPERCASE) case labels**:
   foldKeyword lowercases the scrutinee so the uppercase constant labels never match.
   Fix = drop foldKeyword (raw uppercase scrutinee) + uppercase any raw-lowercase
   `case ":..."` labels. Backends: `WasmExportCompiler` (~166), `WasmImportDirective`
   (~77), plus any keyword switch in codegen. (AsdfSystems stays lowercase — `.asd` island.)
3. **`.toLowerCase()` compared against a now-UPPERCASE set/constant** (e.g. `substituteTree`
   vs `IT_SKIP_HEADS`). Grep codegen for `toLowerCase(...).contains/.equals` against
   flipped constants.
4. **Baked lowercase `"t"`/`"nil"` literals** in emitted code (`JvmEmitHelper.compileTrue`,
   `JvmReadRuntimeBuilder` classify, WASM `addString("t")`) → `"T"`/`"NIL"`.
5. **`PKG + ":lowercase-member"` / `"::%lowercase"` qualified-name concatenations** and
   `"%lowercase-marker"` constants matched against upcased library defuns (found:
   usocket close/listen/stream + `%usock-resignal`, gray `%gray-write-*-dispatch`, stdin
   `%stdin-*`/`%io-read-line`, wit-import generated `:use`/`:export`/`:params`...,
   setf-subseq `:start1`). Grep the WHOLE tree for `+ ":`/`+ "::`/`":[a-z]`/`= "%[a-z]`.
6. **`requireKeywords`/keyword validators folding input** vs uppercase `allowed` constants.
7. **`LispNil`/`LispTrue.print()`** were `"nil"`/`"t"` → now `"NIL"`/`"T"` (done, interp);
   the compiled backends' nil/t PRINT runtime must match (Phase 2/3).
Expectation-flip note: AssertJ fails one assertion per run, so test-expectation updates
converge over several `run → autoflip → rerun` rounds (scratch `autoflip2.py` +
`flip_all.sh`); watch for over-flip of DATA strings (JSON `null` input must stay lowercase).

### Phase 3 — WASM backends (P1 → component → no-gc)
- `WasmReadRuntimeBuilder`: delete `emitCanon` (~642-959) + `CANON_*` locals
  (~622-625); drop the 4th local group (~432-433, count ~424 → 3) and the 4 fold
  params (~417-418); replace the `emitCanon` call (~579-583) with an unconditional
  in-place ASCII-uppercase loop (runtime `read` of `"car"` must still yield `CAR`;
  non-ASCII stays as-is — the existing documented WASM limitation).
- `WasmLispCompiler` (~2118-2131): remove the `foldBlob`/`pkgBlob` construction +
  `appendBlob` calls; call `buildReadExprBody` with 4 args; flip ~2110-2111 to
  `addString("NIL")`/`addString("T")`.
- `WasmComponentImportCompiler`: collapse the 4 LOWER dual-compares to single
  uppercase — variant-to-flats (~1785-1802), `emitLowerVariantAt` (~2209-2225),
  `emitLowerRecordAt` (~2262-2280), `emitLowerRecordParam` (~1966-1985): delete the
  lowercase half + `I32_OR`/null-fallback. LEAVE the LIFT builders (~2559,
  2565-2566, 2592-2593).
- `NoGcWasmCompiler.findKeywordValue` (~3369) + the 2 other copies
  (`JvmArrayCompiler`~394, `WasmArrayCompiler`~1390): flip to uppercase / exact.
- Pin: `WasmLispCompilerIntegrationTest` (Docker/wasmtime image).

### Phase 4 — Seam finish + host boundaries + tests/docs/kb
- `affixFor` (`LispMacroExpander`~3732): collapse to always-uppercase (drop the
  `isLowerCase` early return ~3736-3738); 6 call sites need no per-call change.
- `WitExportDirective` (~333-341): upper-only defun lookup (keep worldName
  `toLowerCase` ~194). `BuiltinFunctionWrappers.getfKw` (~270): single `(getf kw
  :UPPER)`. `HttpPlistShape` (~242): KEEP; reword comment (240-241) only.
- ci-spec: flip `read-from-string ":cl"` (~1929), `find-symbol` car/cond
  (~3684/3685); reconsider lowercase args (~3655/3656). Rebuild the native binary
  and run `CiSpecE2eTest` across all four backends (JVM `./mvnw test` skips it).
- Tests: `LispReaderTest` (632/648/656/657), `LispEvaluatorTest`
  (6098/6099/6119/6120/6150-6152), `JvmLispCompilerTest` (654),
  `WasmLispCompilerIntegrationTest` (5963); recast the `*FoldsRuntimeRead`
  fold-machinery tests (the code they pin is deleted) into "reader upcases, no
  fold" pins; keep `AssocUtilsUpcaseE2eTest` (expectations don't flip).
- Docs (byte-identical en+ja): rewrite `guides/reader-case.md` (the Deviations
  section shrinks — a plus); update `reference/functions/{symbol-name,string,
  read,read-from-string,find-symbol,intern}.md` + the `functions.md` table row;
  fix the pre-existing `defpackage.md` en/ja `UTIL`/`util` mismatch. Run
  `-Drontolisp.doc.fix=true DocExamplesTest#fixShownResults` (AFTER editing fence
  ARGS for find-symbol/intern), then `DocExamplesTest`.
- `.kb`: rewrite `reader-case-upcase.md` for the new model; update
  `symbol-runtime-api.md`, `core-representation.md`. Close `.todo/155` item 1 here.

### Phase 5 (deferred) — A1 go/no-go
Decide AFTER the cutover, on a clean base: introduce a real intern table +
interned symbol objects (eq-by-identity, true uninterned distinctness, package
homing, `unintern`/`:shadow`)? Cost: a runtime value-representation rewrite on all
three backends (`~512` `instanceof LispSymbol`, `~1128` `.name()` sites; JVM
symbols are bare Strings; WASM needs a distinct `TYPE_SYMBOL` or discriminator so
`(eq "CAR" 'car)` is nil; `LispHashTable`/`Environment` binding keying rework;
`LispMacroExpander` package-dependency tension). The intern point is localized to
`LispReader.readSymbol` (`new LispSymbol(name)` → `intern(name, pkg)`), so
deferring is cheap. Only pursue if a concrete feature requires symbol identity
beyond string equality.

**Evidence for the go/no-go, found 2026-09-03** while rewriting the `do-symbols` /
`do-external-symbols` doc examples to stop counting a shipped package. Without an intern
table, **a user package's accessible set is exactly its exports plus what it inherits**:
a name that is interned never joins the set, and `defpackage`'s `:intern` clause is not
supported. So the internal/external distinction -- the thing `do-symbols` and
`do-external-symbols` differ over in CL -- **is not constructible in a user package**, and
the doc example has to teach the difference through INHERITANCE instead. That is a real
feature the absence blocks, which is the bar this section sets ("only pursue if a concrete
feature requires symbol identity beyond string equality"). It is one data point, not a
decision: the example works, taught through the other half of the rule. Recorded so the
go/no-go has a concrete cost to weigh rather than only the rewrite's price.

## Status

IN PROGRESS (A2-first) 2026-07-20. Supersedes `.todo/155` item 1 (closed into this
redesign). NOT committed yet (backends must all be green first).

- **Phase 1 (interpreter): DONE** — eval/reader/compiler/PackageResolver (987) + cli
  all green. `.asd` lowercase island removed (reads upcased; AsdfSystems handles case
  via foldKeyword + coerce-name) so `Features.INTERNAL` is now fully unused (Phase-4
  retire achieved early). ~15 real cutover bugs fixed; ~350+ expectation flips applied
  via a surefire-driven auto-updater. The reusable tools live at
  `~/.claude/projects/-Users-toshiaki-git-rontolisp/cutover-tools/`
  (`autoflip2.py <TEST-*.xml> <TestFile.java>` + `flip_all.sh`): they flip a test's
  expected literal to the actual ONLY when `expected.lower()==actual.lower()` (a pure
  case flip) and report everything else. Gotchas learned the hard way, all handled in
  the script: AssertJ indents continuation lines of a multi-line String (un-indent
  before matching); `containsExactly`/`to contain` list forms; `\n`/quote escapes in
  the Java source literal; AssertJ shows one failing assertion per run, so loop
  `run -> flip -> rerun` to convergence; and NEVER let a method-scoped `null`/`t`
  token flip touch a DATA string (JSON `null`/`true` INPUT must stay lowercase).
  Genuine (non-case-flip) failures the tool reports are the real bugs / semantic
  updates — investigate those, do not force-flip.
- **Phase 2 (JVM): DONE** — `JvmLispCompilerTest` (742) + Jvm{Async,FloatArray,
  JavaInterop,SimdAccel,LinalgSimdAccel}CompilerTest all green. JVM real bugs fixed:
  `JvmMathFnCompiler` (Lisp name reused as the Java `Math.<name>` method → `Math.SQRT`;
  now lowercased), `JvmSymbolApiCompiler` self-bound check (`"t"`→`"T"` so `(boundp t)`),
  the two JVM eval-runtime car/cdr char decoders (`'a'/'d'`→`'A'/'D'`), classify/compileTrue
  baked `t`/`nil`→`T`/`NIL`, and the Jvm map* error messages (`mapcar:`→`MAPCAR:` for
  cross-backend parity). `_canon`/eval-runtime case-flip retries left in place (inert:
  the fold-set blob is empty, retries are one-shot) — delete as Phase-4 cleanup.
  Semantic test updates: `(find-symbol "car")`/`(fboundp (intern "car"))` now nil
  (verbatim intern; CL-correct).
- **Phase 3 (WASM): PRODUCTION CODE DONE + VERIFIED on all four backends** 2026-07-20
  (interp/JVM/WASM-P1/WASM-component/WASM-no-gc all produce byte-identical A2 output for
  t/nil print, symbol/keyword upcase, runtime read fold-free, eval car/cdr, and every
  t/nil-returning predicate — verified by cross-backend probes). NOT committed yet.
  Real WASM/cross-backend bugs fixed:
  1. `WasmReadRuntimeBuilder`: deleted `emitCanon`+CANON locals+fold params; runtime read
     now upcases in place unconditionally (fixes `&`/`%` staying lowercase). The read
     runtime's t special-case (returned i31(1) → printed "1") DELETED: `t` reads as the
     ordinary interned symbol `T` (same string-table offset a compiled `t` uses, so eq).
  2. `WasmLispCompiler`: `addString("nil"/"t")` → `"NIL"/"T"` at StringTable.nil (print),
     symbolTOffset, and the read-runtime nil offset; dropped the fold-blob appendBlob wiring.
  3. `WasmEmitHelper.emitTrue` → `compileStringLiteral("T")` (compiled `t` + every boolean
     result is now the symbol `T`). `WasmIoRuntimeBuilder`/`WasmStringRuntimeBuilder`
     close/string= return `"T"` (baking `"t"` late produced a blank — the early `"t"` entry
     is gone). `WasmEvalRuntimeBuilder` `(and)` special form → `off.of("T")` (+ its
     registration in `WasmLispCompiler`).
  4. `WasmEvalRuntimeBuilder` car/cdr composition decoders (eval + store copies): `'c'/'r'/
     'a'/'d'` (0x63/72/61/64) → `'C'/'R'/'A'/'D'` (0x43/52/41/44).
  5. `NoGcWasmCompiler`: t/nil print pool `"t"/"nil"` → `"T"/"NIL"`; `concatenate 'string`
     type check `isQuotedSymbol(..,"string")` → `"STRING"`.
  6. Component-splice compile bugs (were RED at HEAD — full suite never run for Phase 1/2):
     the 4 library member-filter scanners (`WaitForLibrary`/`HttpLibrary`/`SocketsLibrary`/
     `StdinLibrary` `collectNames`) were missing the lowercase-twin `WitImportInliner` has,
     so upcased references (`%MONO-CLOCK:WAIT-FOR`) never matched the lower-kebab WIT member
     (`wait-for`) → the member was filtered out → its defpackage `:export` was empty →
     "X not external". `WasmSocketsRewrite` dispatch maps had inconsistent case
     (`%io-read-char`/`%tcp-connect-f` lowercase vs the upcased sockets.lisp defuns) → now
     all uppercase. `HttpLibrary` serve root `"%serve-handle"` → `"%SERVE-HANDLE"` (was
     pruned before its wasm-export). wait/tcp/stdin/serve/fetch all compile `--component` now.
  7. JVM residuals (bug-class 7 + the `_canon` `&`/`%` fold Phase 2 deferred, both needed for
     4-backend consistency): `JvmLispCompiler` nil print `"nil"` → `"NIL"`; the 5 runtime
     builders + JVM eval-runtime `(and)` + java-interop bridge that baked symbol `"t"` →
     `"T"`; `JvmReadRuntimeBuilder.buildClassify` `_canon` call → unconditional
     `toUpperCase(ROOT)`. `describe(nil)` → `"NIL"`.
  Component rich-params LOWER dual-compares + JVM `buildCanon`/`emitCanon`-machinery
  deletion + `findKeywordValue` tightening are INERT and DEFERRED to Phase 4 (behavior is
  already correct). `WitImportDirective` was NOT changed (the resolver lowercase retry
  handles upcased references once the member is bound — the collectNames fix is what
  matters).
  TEST STATUS: `JvmLispCompilerTest` green (147 pure case-flips, 0 real bugs). WASM
  integration flip loop running. The rest of the suite needs the same mechanical case-flip
  sweep (autoflip tool + hand-updates of hardcoded Lisp-name strings in library/wit/
  component tests). Pitfall: the autoflip tool can corrupt a `.java` file when a case-flip
  token collides with a Java identifier (it flipped `block`→`BLOCK` in DocExamplesTest) —
  keep DocExamplesTest out of it (its examples are `.md`; use `-Drontolisp.doc.fix=true`).
  PRE-EXISTING Phase-1 residuals surfaced by finally running the full suite (NOT Phase 3
  WASM, out of scope here): parse-number `(coerce x computed-type)` fails because
  parse-number interns a verbatim-lowercase `double-float` while `FLOAT_TYPE_NAMES` is
  uppercase; several AsdfSystemsTest `.asd` cases; some DocExamples. Investigate under
  Phase 4 / a separate pass.
- **Phase 4: DONE** (2026-07-20) — tests/docs/kb + native ci-spec + the inert-seam deletion all
  landed (see "GREEN-SUITE UPDATE", "Native ci-spec DONE", and "#6 inert cleanup DONE + #5 docs
  DONE" below, and the top-of-file RESUME section). `UpcaseSymbols`/`Features.INTERNAL`/JVM
  `_canon` DELETED; `keywordMatches`/`foldKeyword` + the `--component` LOWER dual-compares were
  KEPT (case-tolerance, not fold) and moved to `.todo/157` as an optional simplification. The
  parse-number/asdf residuals were fixed. Only the commit remains (RESUME section, top).

## MID-FLIGHT HANDOFF (compaction point, 2026-07-20)

Working tree is UNCOMMITTED and COMPILES CLEAN (`./mvnw -o test-compile` = 0). 297 files
changed: **22 src/main (production, DONE + verified on all 4 backends)**, 28 src/test
(flip in progress), 246 doc (en+ja doc-fix, DONE), 1 .todo. Do NOT `git checkout` broadly.

### Production (src/main) — DONE, verified via cross-backend probes, do not revert
The 22 changed main files implement the full items 1-7 listed in the Phase 3 status above.
Extra production fixes made during the full-suite sweep (all real A2 bugs, keep them):
- `Environment.java`: `*READ-DEFAULT-FLOAT-FORMAT*` seed `"double-float"` → `"DOUBLE-FLOAT"`
  (parse-number `(coerce x *read-default-float-format*)` was erroring; compiler side already
  had DOUBLE-FLOAT in `LispMacroExpander` ~14151).
- `WaitForLibrary`/`HttpLibrary`/`SocketsLibrary`/`StdinLibrary` `collectNames`: added the
  lowercase-twin (mirrors `WitImportInliner.collectNames`) — the ROOT of every component
  "X not external in %PKG". `WasmSocketsRewrite` SYNC_DISPATCH/ASYNC_FUTURES/TCP_FUTURES
  now uppercase (`%IO-READ-CHAR`/`%TCP-CONNECT-F`...). `HttpLibrary` serve root
  `"%serve-handle"` → `"%SERVE-HANDLE"`. `NoGcWasmCompiler` `concatenate 'string` check
  `isQuotedSymbol(..,"STRING")`. Verified: wait/tcp/stdin/serve/fetch all compile `--component`.
- WitImportDirective.java was NOT changed (the resolver lowercase-retry `PackageResolver`
  ~602-605 handles upcased refs once the member is BOUND — the collectNames fix is the key).

### Tests — status + the tooling to finish the rest
GREEN now: JvmLispCompilerTest (147 flips), DocExamplesTest (via doc-fix; ja synced),
WasmLispCompilerIntegrationTest (Docker; 8 survivors hand-fixed + `findSymbolIsVerbatim`/
`fboundp` recast), ClUtilities/Jzon/LinalgSimd/VecSimd E2E, JvmAsync/FloatArray/JavaInterop/
Simd/LinalgSimd, AsyncEval, Http/Wait/Stdin/LispEvaluatorAsdf/LispFloatArray/ClPpcre.

REMAINING FAILURES (~11 classes, ~30, ALL are test-EXPECTATION case updates — every
`but was:` shows the A2-correct actual; production is right). Run this to see them:
`./mvnw -Dtest='WitImportDirectiveTest,WitExportDirectiveTest,WitImportInlinerTest,WitExportInlinerTest,WasmComponentImportCompilerTest,WasmLispCompilerTest,NoGcWasmCompilerTest,UserMacroExpanderTest,LibraryDefunPrunerTest,AsdfSystemsTest,LispLexerTest' test`
- **Form-comparison** (startsWith / containsExactly / dynamic isEqualTo with `+ VAR +`):
  WitImportDirective (~11: `%component-import`/`wasm-import` forms + `(DEFUN emit ...)`
  emit/save/reset), WitExportDirective (3), Wit{Import,Export}Inliner (3+3),
  WasmComponentImport (STARTS/LIST: `(DEFPACKAGE kv (:USE CL) (:EXPORT open ...))`),
  LispLexer (2: `SymbolToken[name=PRINT]`), UserMacroExpander (3 macroexpand:
  `(PRINT (QUOTE ...))`). Rule of the actual: uppercase EVERY Lisp symbol/keyword EXCEPT
  WIT names (the quoted member in `(QUOTE open)` / string args) and type designators
  (`:int`/`:string`/`:void`/`:float`/`:long`/`:bool`/`:s-expr`) and string data. This
  WIT-vs-Lisp case split is why a blind global uppercase fails.
- **Component `Cannot compile: KV:thing-move-to`**: the `thing-*` `:export` in
  `WasmComponentImportCompilerTest` ~542-543 spans Java `+`-concatenated lines, so the
  `(:export ...)` uppercaser missed those members. Uppercase them (and any other multi-line
  `:export`) to match the upcased refs/DefunDecls.
- **UserMacroExpander impure* / configSetter (4)** and **LibraryDefunPruner (3:
  bareNamesInsideInPackage returns `[]`, keepsTheRngSeeds, functionQuote...)**: verify these
  are pure case (they looked like it: `(DEFVAR *MODE* :A) ...`) — but bareNames returning an
  EMPTY list smells like a possible real pruner/resolve issue under `(in-package :linalg)` +
  `(cl:print ...)`; INVESTIGATE that one, don't blind-flip.
- **AsdfSystemsTest.parsesTheClPostgresAsdHeaderShape (1E)**: `.asd` now read upcased
  (`Features.INTERPRETER`; the test's other `Features.INTERNAL` were switched already), and a
  `defparameter` whose value is `(IF *UNICODE* "..." "...")` is rejected as "unsupported
  form". Decide: is an IF-valued defparameter meant to parse? (probably a test-expectation or
  a small AsdfSystems allowance).

### The flip TOOLS (persisted, survive compaction) in
`~/.claude/projects/-Users-toshiaki-git-rontolisp/cutover-tools/`:
- `repl2.py <TEST-x.xml> <src.java>` — BEST for `isEqualTo`: value-matches the RIGHT
  assertion (handles multi-isEqualTo methods) and un-indents AssertJ's 2-space continuation
  prefix. Use after a run that produced the XML.
- `autoflip2.py` + `flipall.sh` — line-anchored pure-case-flip for simple `expected/actual`;
  `flipall.sh` SKIPS DocExamplesTest (it corrupts it, see gotcha).
- `fliploop.sh <FQCN> <src> <rounds>` — run→flip→rerun loop for one class.
- `sync_doc_ja.py` — obsolete since the doc-fix helper (`DocExamplesTest#fixShownResults`)
  rewrites every language tree itself.
- `isequal_replace.py`/`formflip.py`/`globalflip.py` — earlier iterations; repl2 supersedes.

### GOTCHAS (learned the hard way)
- The interactive `grep` shell function is BROKEN on some large files (returns nothing for
  `package` etc.) — ALWAYS use `/usr/bin/grep`.
- autoflip flips a Java identifier `block`→`BLOCK` in DocExamplesTest.java (its examples are
  .md, not .java) → breaks test-compile. Keep DocExamplesTest OUT of any autoflip; fix its
  results only via `-Drontolisp.doc.fix=true` then `sync_doc_ja.py`.
- doc-fix (`DocExamplesTest#fixShownResults`) walks every language tree, so `sync_doc_ja.py`
  is no longer needed to keep en/ja shown results in step (it did only walk `doc/en` before
  2026-08-16).
- `spring-javaformat:apply` re-wraps long escaped-string test expecteds; harmless.

### To finish + verify
1. Flip the remaining ~30 (per rules above), re-run the class list to green.
2. `./mvnw spring-javaformat:apply test` (full, Docker up) → all green.
3. ci-spec (Phase 4, native): ~150 standalone t/nil lines flip (line counts stable). Build
   `-Pnative`, run `CiSpecE2eTest -Drontolisp.binary=...`; also flip `read-from-string-upcase-fold`
   (t→T, `:cl`→`:CL`) and `symbol-runtime-api` (t→T, `(find-symbol "car")`→nil) cases.
4. `.kb/reader-case-upcase.md` still describes the OLD fold model — rewrite for A2. Also
   `symbol-runtime-api.md`, `core-representation.md`, `doc/{en,ja}/guides/reader-case.md`.
5. Phase-4 inert cleanup: delete JVM `buildCanon`/`_delimContains`, WASM component LOWER
   dual-compares, `keywordMatches`/`foldKeyword`, `Features.INTERNAL`, `UpcaseSymbols`.
   Consider the user's suggestion: wrap the `WasmSocketsRewrite`-style dispatch Maps in an
   always-uppercasing Map to prevent future case-drift structurally.
6. Only then commit (all four backends green first, per the governing rule).

Governing rule: where behavior must match across interpreter + JVM + both WASM backends,
change all four and their pinning tests together — never one backend in isolation.

## GREEN-SUITE UPDATE (2026-07-20, post-compaction)

The whole `./mvnw test` suite is GREEN: **4052 tests, 0 failures, 0 errors, 7 skipped**
(the 7 are opt-in / native-binary-gated). Docker was up, so `WasmLispCompilerIntegrationTest`
(real wasmtime P1 + component) ran and passed too. Working tree still UNCOMMITTED.

Beyond the test-expectation flips listed above, the full-suite sweep found and fixed FOUR
more real A2 production bugs (all in `src/main`, keep them):
1. `LinalgLibrary` / `VecLibrary` / `UsocketLibrary` splice triggers matched bare names under
   `(in-package :pkg)` against the now-UPPERCASE `*FunctionNames()` sets with `.toLowerCase()`
   — flipped to `.toUpperCase()`. Before the fix a `(in-package :linalg) (cl:print (to-list
   (zeros ...)))` program spliced NOTHING (`spliced size=2`), so the pruner saw no defuns.
2. `LibraryDefunPruner.collectReferences` string-literal carve-out now upcases the string
   before `contains` — `(read-from-string "linalg:ndim")` names `LINALG:NDIM`, so the match
   must be case-insensitive.
3. `AsdfSystems.evalDataForm` `.asd` mini-eval `case "if"/"not"/"or"/"and"` were lowercase
   literals; the reader upcases, so `(if *unicode* ...)` fell through to "unsupported form".
   Switched to `LispNames.IF/NOT/OR/AND`.
4. (test-side but subtle) `WasmComponentImportCompilerTest` directive-path lisp-names keep the
   "kv" designator's case (lowercase `kv:member`); the hand-written `%component-import` test
   uppercases its `:export` designators to line up with the upcased user calls.

### Native ci-spec DONE (2026-07-20)
Built `-Pnative`; regenerated ci-spec.yaml `expected` from the native binary's real per-backend
output with a line-based regen tool (`scratchpad/cispec_regen.py`, copied to `cutover-tools/`):
it parses the block scalars, builds the concatenated program, runs all four backends
(interpreter / `-o Test.class`+java / `-o test.wasm`+wasmtime / `--component`+wasmtime), slices
each backend's stdout by the CURRENT per-case expected line counts (A2 preserves counts — only
casing shifts), and REFUSES to rewrite if any simple case's four backends disagree (guards the
cross-backend-identity invariant). `CiSpecE2eTest -Drontolisp.binary=target/rontolisp` =
**908 tests, 0 failures** on all four backends.

The regen caught THREE real A2 interpreter regressions the unit suite missed (fixed in `src/main`):
1. `Environment` `*features*` seeded `:rontolisp` (lowercase) -> `.toUpperCase()` = `:RONTOLISP`.
2. `LispEvaluator` `class-of` wrapped a lowercase `builtinTypeName` -> `.toUpperCase()` so
   `(class-of 42)` prints `INTEGER`, matching the compiled backends.
3. `LispEvaluator.instanceSlotCell` compared slot base names with `equals`; a Java-side caller
   spells the built-in slot lowercase ("format-control") while an upcase-read condition registers
   `FORMAT-CONTROL` -> `equalsIgnoreCase` (same reconciliation `expandConditionSlotReader` makes),
   so `simple-condition-format-control` returns "lbr 7" not NIL.
Renamed the ci-spec case `read-from-string-upcase-fold` -> `read-from-string-upcase`.

### REMAINING (tasks #5 + #6 are COUPLED; do #6 then #5; needs one more hot native ci-spec re-run)
The fold is already INERT — `UpcaseSymbols.canonicalize` is identity, `foldableBareNames()`/
`foldPackageNamesBlob()` are empty; the class Javadoc says it "remains only so the now-inert fold
entry points ... can be retired backend by backend". So:
- **Inert cleanup** (task #6, do FIRST): behavior-neutral dead-code removal across ~20 files —
  `UpcaseSymbols` + `canonicalize` callers, JVM `buildCanon`/`_canon`, WASM `emitCanon`, the baked
  `foldNamesBlob`/`foldPackageNamesBlob`, `Features.INTERNAL`/`preserveCase` (IF the .asd/shim
  lowercase island is truly gone — verify AsdfSystems/shim reads), the component LOWER dual-compares
  and `keywordMatches`/`foldKeyword` simplification. Validate with the JVM suite AND a final native
  `CiSpecE2eTest` (deleting inert code must be byte-identical). Consider the user's always-uppercasing
  Map wrapper for `WasmSocketsRewrite` dispatch to prevent future case-drift.
- **.kb/docs rewrite** (task #5, do AFTER #6 so it describes the CLEAN state): rewrite
  `.kb/reader-case-upcase.md` for A2 (no fold; `symbol-name 'car` = "CAR" now — the old lowercase
  deviation is GONE; `(find-symbol "car")` = NIL; runtime read just upcases), and
  `symbol-runtime-api.md`, `core-representation.md`, `doc/{en,ja}/guides/reader-case.md`.
- Then commit (with permission).

### COMMITTABLE NOW (independent of the follow-up)
The A2 cutover behavior + all test-expectation updates are a complete, validated unit: full suite
4052/0 (incl. Docker WASM integration) + native CiSpecE2eTest 908/0 on all four backends, with the
fold code left as inert no-ops. This is committable on its own; #6+#5 are a clean second unit.

### #6 inert cleanup DONE + #5 docs DONE (2026-07-20)
#6 (behavior-neutral fold removal): DELETED `UpcaseSymbols` (identity/empty no-ops); inlined the
`LispEvaluator.foldRuntimeSymbolName` identity wrapper (intern/find-symbol now verbatim); removed
`LispLexer`'s `canonicalize` calls + the dead `preserveCase` branches; removed `Features.INTERNAL`/
`preservingCase()`/`preserveCase` (the case-preserving island was already unreachable); removed the
JVM read runtime's dead `_canon`/`_delimContains` + their fold-only string helpers/blobs
(`JvmReadRuntimeBuilder`). WASM `emitCanon` was already gone (Phase 3). Left in place (case-tolerance,
NOT fold; noted in `.kb/reader-case-upcase.md`): `keywordMatches`/`foldKeyword`,
`instanceSlotCell` equalsIgnoreCase, PackageResolver lowercase-retry, the component LOWER dual-compares
(a later simplification candidate). Full suite re-ran GREEN after cleanup except 3 DocExamples that my
earlier interpreter fixes changed (class-of->INTEGER, simple-condition-format-control/arguments now
return the value not NIL) -- fixed via doc-fix.

#5 (docs): rewrote `doc/{en,ja}/guides/reader-case.md` for A2 (upcase, no fold; `symbol-name 'car`
= "CAR"; `find-symbol "car"` = NIL; `|car|` distinct from CAR), `.kb/reader-case-upcase.md` (fold
deleted; documents the residual case-tolerance seams), and fixed the stale "standard names stay
lowercase" prose in `.kb/symbol-runtime-api.md` + `functions.md`/`symbol-name.md`/`string.md`/
`read.md`/`read-from-string.md` and the self-hosted-REPL "case-preserving" claim (the embedded runtime
reader upcases now -> the REPL echoes `SQUARE`, verified). Ran `-Drontolisp.doc.fix=true` +
`sync_doc_ja.py` + a hand pass for the multi-line examples; DocExamplesTest 542/0 and all 125 changed
doc pages have en/ja code-fence parity. Javadoc: only the known `Version` build-time error. Web
profile compiles.

Only the FINAL native `CiSpecE2eTest` re-run (byte-identical after the dead-code deletion) remains
before commit.
