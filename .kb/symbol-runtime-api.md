# Runtime symbol API (`symbol-name`/`intern`/`find-symbol`/`make-symbol`/`boundp`/`fboundp`/`symbol-value`)

## `symbol-name` drops the package qualifier; the package API (todo-173)

**`symbol-name` (and the string-designator coercions `string`/`string=`/
`string-equal`) return the MEMBER name**: `(symbol-name 'foo::bar)` is `"BAR"` —
the qualifier says where the symbol lives, it is not part of its name
(`LispSymbol.memberName`). `princ`/`~A`/`display` still keep the qualifier
(`LispSymbol.displayName` strips only the `:`/`#:` markers), and `prin1` keeps
everything. This is CL's answer, and it is what makes name surgery work: a
library that does `(intern (concatenate 'string (symbol-name x) "-SUFFIX"))`
under `(in-package p)` would otherwise re-qualify an already-qualified spelling
into `P::P:X-SUFFIX` (ironclad's `optimized-maker-name`).

**A "package" at runtime is the UPCASED canonical package name as a keyword** —
there are no package objects, and `eq` compares symbols by content, so
`find-package` and `symbol-package` agree by construction and
`(eq (symbol-package s) (find-package :p))` works (ironclad's `massage-symbol`).
Upcased because the compile paths' spelling comes from reader-upcased literals.
- `find-package`: nil for an unknown package. A LITERAL designator is folded by
  `PackageResolver.resolveCons` (the one pass with the registry), so it answers
  identically on all four backends; a computed designator stays a runtime call,
  which only the interpreter serves.
- `symbol-package`: registry-backed on the interpreter; a backend-neutral
  `LispPreludeLibrary` defun elsewhere, which reads the qualifier off
  `prin1-to-string` and therefore cannot tell `cl` from `cl-user` (both answer
  `:CL-USER` on the compiled backends).
- `type-of`: also a prelude defun, over `class-of` — it strips the
  `%struct-`/`%class-` tag prefix to yield the type NAME, so a digest object's
  type is usable as the digest-name designator it came from.
- **2-argument `find-symbol`**: interpreter = registry-backed ("interned" means
  the package owns/exports/imports the verbatim name), returning the canonical
  spelling so plist and dispatch lookups keyed by a resolver-canonicalized quote
  match. On the compiled backends a symbol IS its canonical spelling, so
  `expandFindSymbolInPackage` BUILDS that spelling: `(intern (concatenate 'string
  "PKG:" name))` for a literal package designator, or the same over
  `(string PKG)` when the designator is computed (a local holding a package
  value). Two deviations there: an unknown name yields a symbol instead of nil
  (harmless where find-symbol feeds a plist lookup that then answers nil anyway),
  and the qualifier is the single-colon EXTERNAL spelling — right for a library's
  exported API, wrong for an internal symbol. A bare non-keyword symbol in the
  package-argument position is a VARIABLE REFERENCE, never a designator literal:
  reading its name as the package is how this once built the doubly-qualified
  `IRONCLAD::IRONCLAD:SHA256`.
- **A quoted LONE SYMBOL is package-resolved in ordinary code**, not only inside
  a defmacro template: CL interns it in the current package at read time, so a
  `'%indicator` in a defun body must name the same canonical symbol a template in
  the same package stores (ironclad's `defdigest` writes plist entries under
  template-resolved indicators that `digestp` reads back with a body quote).
  Quoted LISTS stay untouched, and the `wasm-export`/`wasm-import` option tail is
  exempt (`inHostFacingData`) because its quoted values are host-facing data — an
  export field name must stay `tick`, not `gl::tick`.
- **`(let ((*package* X)) ...)`**: the resolver substitutes a `*package*` READ
  with its quoted read-time package, which also hits a binding-NAME slot, so
  `normalizeBindingList` renames the binding to the `PACKAGE_REBIND_VAR` marker.
  The interpreter's `evalLet` additionally swaps the resolver's current package
  for the binding's extent, so a macro-time `(intern ...)` under it homes where CL
  would; the compilers treat it as a plain throwaway binding.

`unintern`, `export` and the rest of the runtime package-mutation API remain in
`.todo/038`.

Seven CL functions (`PackageRegistry.CL_FUNCTIONS`, cl function count 210 -> 217) in all three backends. rontolisp symbols compare by name (no intern table), which shapes every deviation: `symbol-name` returns the name **without the package marker** — a keyword's leading `:` and a gensym's `#:` are stripped (`LispSymbol.displayName`, shared with `princ`/`~A`/`string`; `prin1`/`print` keep the stored spelling) — the STORED spelling verbatim, which under the uppercase-canonical model (`.kb/reader-case-upcase.md`) is upcased for every symbol read from source — user AND standard (`(symbol-name 'foo)` = `"FOO"`, `(symbol-name 'car)` = `"CAR"`, the CL answer; there is no lowercase-standard-name deviation); `intern`/`find-symbol` take the name VERBATIM (`(find-symbol "car")` = `NIL` because the standard symbol is named `"CAR"`; `(intern "TIME")` = `TIME`, `(intern "time")` = the distinct `time`). `intern` (1-arg) interns into the **current package** on the interpreter (`PackageResolver.internSpelling`: an accessible symbol keeps its canonical home spelling, an unknown name is homed verbatim into the resolver's `in-package` state — the LispEvaluator override; the Environment converter and the compiled backends stay package-blind), `(intern name :keyword)` builds a keyword, any other package argument is a hard error (as is find-symbol's); `make-symbol` prepends the `#:` uninterned marker (same string twice = `eq` symbols, unlike CL); `find-symbol` returns the symbol only when the (verbatim) name is "known".

**Interpreter**: the pure converters live in `Environment` (next to gensym), but `intern` is overridden in `LispEvaluator.registerEval` (it needs the evaluator's `packageResolver` for the current package -- the Environment version stays as the resolver-less fallback); `boundp`/`symbol-value`/`fboundp`/`find-symbol` live in `LispEvaluator.registerEval` because they capture `globalEnv` (variable lookups see GLOBAL bindings only — CL's dynamic-only semantics; `Environment.lookupOrNull` was added for this) and `userMacros`/`SPECIAL_OPERATORS` (fboundp is t for macros, special forms and car/cdr compositions, like CL). t/nil/keywords are self-bound in boundp/symbol-value on every backend.

**JVM** (`JvmSymbolApiCompiler`): symbol-name = the princ-to-string emission (leniency note below); intern/make-symbol = quote-strip `substring(1, len-1)` (+ `"#:".concat`); boundp/symbol-value read the eval runtime's `_genv` mirror via `_envLookup` (binding pair `Object[2]`, value = index 1; unbound symbol-value throws `The variable X is unbound`), computed fboundp probes `_fenv` then `_lookup` — all three are added to the `usesEval` force list in `JvmLispCompiler` (the apply precedent), which also turns on the top-level `_store` mirroring they depend on.

**WASM** (`WasmSymbolApiCompiler` + `WasmSymbolApiRuntimeBuilder`): five always-present unary helpers `FUNC_MAKE_SYMBOL`..`FUNC_FBOUNDP` (type `TYPE_CALLABLE_BASE`, appended before `FUNC_USER_BASE` like gensym); symbol-name reuses `FUNC_PRINC_TO_STR`. `_intern_sym` interns the content range (verbatim) through the reader runtime's `_intern` so the result's string-table offset matches literals in the offset-based `_env_lookup`/`eq` — a new `usesIntern` gate (`usesRead || program uses intern`) emits the real `_intern` body + blob without the rest of the reader, and gates the intern wrapper (read-from-string precedent). The helper bodies embed the offset of the symbol `t` (interned before the blob snapshot). boundp/symbol-value/fboundp force `usesEval` like on the JVM; unbound symbol-value traps (`unreachable`, no message — the `%error` convention).

**Compile-path folds and limits (both compilers)**: `find-symbol` requires a literal string and matches its VERBATIM name against `isClSymbol` + keyword + Pass-1 `userDefunNames` (so `(find-symbol "car")` is nil, `(find-symbol "CAR")` names `CAR`; runtime-defined globals and defmacro macros are interpreter-only knowledge); a literal `(fboundp 'x)` folds with full knowledge (specialOperatorNames + clFunctionNames + carcdr + userDefunNames + ctx.functions), a computed one sees functions only (`(fboundp (intern "COND"))` = nil compiled, t interpreted — the macro `COND` is interpreter-only knowledge; `(intern "cond")` is the distinct unbound symbol `cond`, nil on both). `#'symbol-name`/`#'intern`/`#'make-symbol` have wrappers; find-symbol/boundp/fboundp/symbol-value deliberately have none (macroexpand precedent: fold-only or eval-runtime-dependent). On compiled backends symbol-name is princ-to-string-lenient on non-symbols (the interpreter type-errors); JVM intern/make-symbol don't type-check their argument either.

Tests: the *SymbolName/Intern/MakeSymbol/FindSymbol/Boundp/SymbolValue/Fboundp* groups in the three backend tests, the `symbol-runtime-api` ci-spec case, and the 217 count pinned in ci-spec + LispEvaluatorTest + JvmLispCompilerTest (x2) + WasmLispCompilerIntegrationTest. Package-mutation functions (`export`/`use-package`/runtime `find-package`) remain in `.todo/038-symbol-and-package-extensions.md`.

## Is "no intern table" (identity = name) a stable design? (assessed 2026-07-05)

Yes — nothing on the roadmap (split-sequence, CLOS subset, condition system, dynamic variables) forces a redesign. Almost everything CL hangs off a symbol object works as a name-keyed side table instead (`symbol-plist`, class/method tables, condition type names, `macro-function`, special-variable bindings), and cross-package distinctness is already carried by the canonical spelling (`pkg::name` — same short name in two packages IS two different names). Real-library macro hygiene rests on `gensym`'s counter, not on object identity. Three costs DO persist:

1. **WASM canonical-offset discipline (the recurring one)**: env lookup and `eq` compare string-table offsets, so EVERY future primitive that builds a symbol at runtime must route the bytes through `_intern` (reuse the `usesIntern` gate + `_intern_sym` rail) or it princs correctly but fails lookups/`eq`. This is a per-feature tax, not a one-time fix.
2. **True uninterned identity is unrepresentable**: `(make-symbol "x")` twice is `eq`, hand-written `#:x` literals in two independent macros collide, `copy-symbol` cannot exist. Only affects code that bypasses `gensym`; accepted.
3. **`unintern` and shadowing can never be implemented** (an intern table is the thing you'd unintern from) — the same deliberate line as `defpackage` rejecting `:shadow`. A library depending on either is where this design hits its wall (`.todo/038`).
