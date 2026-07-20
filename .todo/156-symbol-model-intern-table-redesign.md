# Symbol model redesign: uppercase-canonical (Approach A2), intern table deferred

Raised by the user 2026-07-20 after `.todo/155` item 3 (runtime `read` fold)
landed. The lowercase-canonical + fold-on-top model (Approach B) spread case
special-cases across the whole system; the CL way is uppercase-canonical
(Approach A). **Decision (2026-07-20): execute A2 first** — uppercase-canonical,
keep string identity, delete the fold — because the accumulated complexity the
user reacted to is caused entirely by *lowercase-canonical*, not by the missing
intern table, and A2 deletes all of it. **A1 (a real intern table with symbol
identity) is deferred to a go/no-go decision AFTER the cutover lands.**

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
  `"CAR"` (CL-correct); a user symbol stays `"FOO"`.
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
  `-Drontolisp.doc.fix=true DocExamplesTest#fixDetailResults` (AFTER editing fence
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
- **Phase 3 (WASM): NOT STARTED** — needs Docker/wasmtime. Same bug classes expected
  (car/cdr `arr[1]=='d'` in `WasmEvalRuntimeBuilder`, emitCanon, baked t/nil, component
  rich-params LOWER dual-compares, findKeywordValue copies).
- **Phase 4: NOT STARTED** — seam cleanup (delete inert `_canon`/`emitCanon`/keywordMatches/
  foldKeyword/case-flips/`Features.INTERNAL`), ci-spec + native `CiSpecE2eTest`, docs
  (reader-case.md et al.), `.kb` rewrite.

Governing rule: where behavior must match across interpreter + JVM + both WASM backends,
change all four and their pinning tests together — never one backend in isolation.
