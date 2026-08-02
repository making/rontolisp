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
- `type-of`: also a prelude defun, over the internal `%class-designator` (NOT
  `class-of`, which answers a class metaobject since the DAO/MOP migration) —
  it strips the `%struct-`/`%class-` tag prefix to yield the type NAME, so a
  digest object's type is usable as the digest-name designator it came from,
  and it drags no metaobject runtime into the program.
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

Seven CL functions (`PackageRegistry.CL_FUNCTIONS`, cl function count 210 -> 217) in all three backends. rontolisp symbols compare by name (no intern table), which shapes every deviation: `symbol-name` returns the name **without the package marker** — a keyword's leading `:` and a gensym's `#:` are stripped (`LispSymbol.displayName`, shared with `princ`/`~A`/`string`; `prin1`/`print` keep the stored spelling) — the STORED spelling verbatim, which under the uppercase-canonical model (`.kb/reader-case-upcase.md`) is upcased for every symbol read from source — user AND standard (`(symbol-name 'foo)` = `"FOO"`, `(symbol-name 'car)` = `"CAR"`, the CL answer; there is no lowercase-standard-name deviation); `intern`/`find-symbol` take the name VERBATIM (`(find-symbol "car")` = `NIL` because the standard symbol is named `"CAR"`; `(intern "TIME")` = `TIME`, `(intern "time")` = the distinct `time`). `intern` (1-arg) interns into the **current package** on the interpreter (`PackageResolver.internSpelling`: an accessible symbol keeps its canonical home spelling, an unknown name is homed verbatim into the resolver's `in-package` state — the LispEvaluator override; the Environment converter and the compiled backends stay package-blind), `(intern name :keyword)` builds a keyword, and any other package argument works via the canonical-spelling lowering on the compile paths (see "Runtime-interned symbols as function designators" below; the interpreter is registry-backed via `PackageResolver.internSpellingIn`, which throws `No such package: X` for an unknown package on every backend); `make-symbol` prepends the `#:` uninterned marker (same string twice = `eq` symbols, unlike CL); `find-symbol` returns the symbol only when the (verbatim) name is "known".

**Interpreter**: the pure converters live in `Environment` (next to gensym), but `intern` is overridden in `LispEvaluator.registerEval` (it needs the evaluator's `packageResolver` for the current package -- the Environment version stays as the resolver-less fallback); `boundp`/`symbol-value`/`fboundp`/`find-symbol` live in `LispEvaluator.registerEval` because they capture `globalEnv` (variable lookups see GLOBAL bindings only — CL's dynamic-only semantics; `Environment.lookupOrNull` was added for this) and `userMacros`/`SPECIAL_OPERATORS` (fboundp is t for macros, special forms and car/cdr compositions, like CL). t/nil/keywords are self-bound in boundp/symbol-value on every backend.

**JVM** (`JvmSymbolApiCompiler`): symbol-name = the princ-to-string emission (leniency note below); intern/make-symbol = quote-strip `substring(1, len-1)` (+ `"#:".concat`); boundp/symbol-value read the eval runtime's `_genv` mirror via `_envLookup` (binding pair `Object[2]`, value = index 1; unbound symbol-value throws `The variable X is unbound`), computed fboundp probes `_fenv` then `_lookup` — all three are added to the `usesEval` force list in `JvmLispCompiler` (the apply precedent), which also turns on the top-level `_store` mirroring they depend on.

**WASM** (`WasmSymbolApiCompiler` + `WasmSymbolApiRuntimeBuilder`): five always-present unary helpers `FUNC_MAKE_SYMBOL`..`FUNC_FBOUNDP` (type `TYPE_CALLABLE_BASE`, appended before `FUNC_USER_BASE` like gensym); symbol-name reuses `FUNC_PRINC_TO_STR`. `_intern_sym` interns the content range (verbatim) through the reader runtime's `_intern` so the result's string-table offset matches literals in the offset-based `_env_lookup`/`eq` — a new `usesIntern` gate (`usesRead || program uses intern`) emits the real `_intern` body + blob without the rest of the reader, and gates the intern wrapper (read-from-string precedent). The helper bodies embed the offset of the symbol `t` (interned before the blob snapshot). boundp/symbol-value/fboundp force `usesEval` like on the JVM; unbound symbol-value traps (`unreachable`, no message — the `%error` convention).

**Compile-path folds and limits (both compilers)**: `find-symbol` requires a literal string and matches its VERBATIM name against `isClSymbol` + keyword + Pass-1 `userDefunNames` (so `(find-symbol "car")` is nil, `(find-symbol "CAR")` names `CAR`; runtime-defined globals and defmacro macros are interpreter-only knowledge); a literal `(fboundp 'x)` folds with full knowledge (specialOperatorNames + clFunctionNames + carcdr + userDefunNames + ctx.functions), a computed one sees functions only (`(fboundp (intern "COND"))` = nil compiled, t interpreted — the macro `COND` is interpreter-only knowledge; `(intern "cond")` is the distinct unbound symbol `cond`, nil on both). `#'symbol-name`/`#'intern`/`#'make-symbol` have wrappers; find-symbol/boundp/fboundp/fmakunbound/symbol-value deliberately have none (macroexpand precedent: fold-only or eval-runtime-dependent). On compiled backends symbol-name is princ-to-string-lenient on non-symbols (the interpreter type-errors); JVM intern/make-symbol don't type-check their argument either.

Tests: the *SymbolName/Intern/MakeSymbol/FindSymbol/Boundp/SymbolValue/Fboundp* groups in the three backend tests, the `symbol-runtime-api` ci-spec case, and the 217 count pinned in ci-spec + LispEvaluatorTest + JvmLispCompilerTest (x2) + WasmLispCompilerIntegrationTest. Package-mutation functions (`export`/`use-package`/runtime `find-package`) remain in `.todo/038-symbol-and-package-extensions.md`.

## Is "no intern table" (identity = name) a stable design? (assessed 2026-07-05)

Yes — nothing on the roadmap (split-sequence, CLOS subset, condition system, dynamic variables) forces a redesign. Almost everything CL hangs off a symbol object works as a name-keyed side table instead (`symbol-plist`, class/method tables, condition type names, `macro-function`, special-variable bindings), and cross-package distinctness is already carried by the canonical spelling (`pkg::name` — same short name in two packages IS two different names). Real-library macro hygiene rests on `gensym`'s counter, not on object identity. Three costs DO persist:

1. **WASM canonical-offset discipline (the recurring one)**: env lookup and `eq` compare string-table offsets, so EVERY future primitive that builds a symbol at runtime must route the bytes through `_intern` (reuse the `usesIntern` gate + `_intern_sym` rail) or it princs correctly but fails lookups/`eq`. This is a per-feature tax, not a one-time fix.
2. **True uninterned identity is unrepresentable**: `(make-symbol "x")` twice is `eq`, hand-written `#:x` literals in two independent macros collide, `copy-symbol` cannot exist. Only affects code that bypasses `gensym`; accepted.
3. **`unintern` and shadowing can never be implemented** (an intern table is the thing you'd unintern from) — the same deliberate line as `defpackage` rejecting `:shadow`. A library depending on either is where this design hits its wall (`.todo/038`).

## Runtime (COMPUTED) package/symbol operations — `fmakunbound`, computed `find-package`/`find-symbol` (todo-198, 2026-07-28)

The section above covers the LITERAL forms, which fold. postmodern reaches the same
API through variables, so the computed forms had to grow real answers rather than the
compile errors they used to be. Eight CL functions in the group now (cl function count
324 -> 325 with `fmakunbound`).

**`fmakunbound`: the name becomes call-time-undefined again.** Interpreter — the global
function binding AND any user macro of the same name are dropped outright
(`Environment.undefineFunction` + `userMacros.remove`), so `fboundp` answers nil and a
later call signals `The function X is undefined`. Compiled backends — a **TOMBSTONE**
is installed in the eval runtime's function namespace (`_fenv` on the JVM,
`GLOBAL_FENV` on WASM): a binding whose value cell is nil. That namespace is probed
BEFORE the compiled-function registry, so it shadows it, and both lookups already read
"no value" as "undefined". `fboundp` had to learn the same rule (a binding decides the
answer on its own instead of merely existing) or a retired name answered t again.
Returns the name on every backend.

- **`(fboundp 'x)` stops being a bare constant when the program calls
  `fmakunbound`.** The fold is emitted BEHIND the tombstone probe (`Ctx.usesFmakunbound`
  in both compilers: `JvmSymbolApiCompiler.emitTombstoneGuard`,
  `WasmSymbolApiCompiler.emitTombstoneGuardedFold` — inline `_env_lookup` on the
  literal's string-table offset, no helper call). A program without `fmakunbound` is
  byte-identical to before.
- **The divergence, and its reason**: a call site the compiler already bound directly
  (an `invokestatic`/`call` to the defun) keeps working after `fmakunbound` — eager
  compilation cannot be undone, so only LATE-bound references (`fboundp`, `funcall` /
  `#'name` / `eval` through the symbol) see the retirement. `symbol-function` /
  `fdefinition` of a LITERAL name are folded the same way and are likewise not
  tombstone-aware: the interpreter signals there, the compiled backends return the
  function. **Re-evaluate when** literal `symbol-function`/`fdefinition` gains a
  runtime probe, or if a library starts round-tripping a retired name through it —
  the guard shape above is exactly what it would need.

**Computed `find-package` is answered from a BAKED table.** A literal designator is
folded by `PackageResolver.resolveCons` (the one pass holding the registry); a computed
one now lowers to `(cdr (assoc (string x) '(("CL" . :CL) ...) :test #'string=))` built
by `LispMacroExpander.expandRuntimeFindPackage` from
`PackageResolver.runtimePackageTable()` — read AFTER `resolveProgram`, so it covers
every `defpackage` in the program — and threaded to both backends as `Ctx.packageTable`.
Keys are matched VERBATIM, and the table carries both the registered spelling and its
uppercase form because that is exactly what `findPackageName` accepts (a built-in is
registered lowercase and answers to both cases; a user `defpackage` is registered as the
reader upcased it and answers only to that). The lowering is pure AST, so no per-backend
codegen exists. **The divergence, and its reason**: the table is frozen at compile time,
so a package a compiled program creates later (interpreter-only `(eval '(defpackage ...))`)
is invisible to a computed `find-package` there. **Re-evaluate when** runtime
`defpackage` becomes a thing on the compiled backends (`.todo/038`).

**Computed `find-symbol`.** A computed NAME lowers to `(intern name)`
(`LispMacroExpander.computedFindSymbol`) — a symbol IS its canonical spelling on the
compiled backends, so interning it IS the lookup, carrying the deviation the two-argument
lowering already had: an unknown name yields a symbol instead of nil, because "already
interned" is knowledge only an intern table could hold. A computed PACKAGE designator
now builds the spelling through a runtime test of the three packages whose members carry
no qualifier (`computedPackageFindSymbol`: `keyword` -> `:NAME`, `cl`/`cl-user` -> bare,
anything else -> `PKG:NAME`), instead of unconditionally prefixing `(string PKG)` —
without it postmodern's `json-intern`, which reads its package out of
`*json-symbols-package*` (a `(find-package 'keyword)` value), built `KEYWORD:X` instead
of the keyword `:X`.

**A package that does not exist provides no symbol: `find-symbol` answers nil, not an
error.** A LITERAL designator naming no package folds straight to nil on the compile
paths (the same baked table `find-package` uses), so the four backends agree on
postmodern's `(find-symbol "TIMESTAMP" :simple-date)` probe and on ironclad's
`(find-symbol "EA" :sb-vm)`, which used to build the symbol `SB-VM:EA` there. CL signals a `package-error`; the interpreter used to as well
(`PackageResolver.memberSpelling` throws), which made it the only backend that did — the
compile paths have no registry at run time and cannot. Probing an OPTIONAL system with
`(find-symbol "TIMESTAMP" :simple-date)` is exactly what libraries do (postmodern's
json-encoder, five sites), so all four backends now answer nil. `nil` itself is accepted
as the designator naming the package `"NIL"` (`packageDesignator`), so `(find-package nil)`
is nil rather than a type error, matching CL.

Tests: the *Fmakunbound* / *FindPackage* / *FindSymbol* groups in the three backend
tests, the `runtime-package-symbol-ops` ci-spec case, and the 325 count pinned in
ci-spec + LispEvaluatorTest + JvmLispCompilerTest (x2) + WasmLispCompilerIntegrationTest.

## Runtime-interned symbols as function designators (todo-229, 2026-08-02)

Clack's handler protocol is late-bound by NAME — `(apply (intern #.(string '#:run)
handler-package) ...)` in handler.lisp, `(funcall (intern (string :wrap)
:clack.middleware) ...)` in lack's builder, `(symbol-value (intern (format nil "*~A*"
...) package))` in find-middleware — so the compile paths carry a full
symbol-to-function route. Three pieces, on BOTH compile backends (the interpreter
resolves designators against the live environment and needs none of it):

- **2-arg `(intern name pkg)` lowers like 2-arg `find-symbol`**
  (`LispMacroExpander.expandInternInPackage`, called from
  `Jvm/WasmSymbolApiCompiler.compileIntern`): the `keyword` designator keeps the
  byte-identical keyword lowering; a literal `cl`/`cl-user` drops the qualifier; any
  other literal known package builds `(intern (concatenate "PKG:" name))`; a computed
  designator runs the same three-way `cond` as `computedPackageFindSymbol` (the two
  share `computedQualifiedSpelling`). The old "runtime package argument is not
  supported" stub is retired — its "needs the resolver's package state" reason died
  when todo-198 built the canonical-spelling machinery. **Intern's two contract
  differences from find-symbol**: an unknown LITERAL package is a call-time
  `(error "No such package: X")` stub, not a nil fold (so lack/builder.lisp's dead
  old-Clack branch interning into the never-existing `:clack.middleware` still
  compiles), and a nil COMPUTED designator signals the same way. That interpreter
  error (`LispPackageException`) is NOT handler-case-catchable on any backend, so it
  is pinned per-backend, not in ci-spec.
- **Alias rows for internal names in the `_lookup` registries**
  (`JvmEvalRuntimeBuilder.lookupSegments`, the registry-blob loop in
  `WasmLispCompiler`): the runtime-built spelling is always the single-colon EXTERNAL
  one (exportedness is compile-time knowledge), so every registered `PKG::NAME` defun
  also answers to `PKG:NAME` — appended after the base rows, genuine keys win,
  collision-free because one package cannot house two distinct symbols with one member
  name. Emitted only when the registry itself is (the
  `usesRuntimeFunctionDesignator` / `usesEval` gate), so ordinary programs stay
  byte-identical. **VARIABLES do not get the alias**: `boundp`/`symbol-value` of an
  interned symbol probe the `_genv` mirror by the single-colon spelling, so only
  EXPORTED specials resolve (lack's `*lack-middleware-backtrace*` is exported).
  Re-evaluate if a library reads an unexported special through runtime intern.
- **`_apply`'s symbol miss is LOUD**: a designator resolving in neither `_fenv` nor
  the registry throws `The function X is undefined` (JVM,
  `emitUndefinedFunctionThrow` — same text as the funcall dispatchers' miss arm) /
  traps (`unreachable`, WASM). It used to return nil silently, hiding a shaken-out or
  never-defined name behind wrong output — the tree-shaker carve-out
  (`.kb/library-defun-pruning.md`) promises a loud failure there.

Two divergences retired in the same pass because their "no runtime name table" reason
died with the registry:

- **Computed `(symbol-function x)` / `(fdefinition x)` lowers to the IDENTITY**
  (`LispMacroExpander.expandRuntimeSymbolFunction`, was an unconditional call-time
  signal): on the compiled backends a symbol is a function designator wherever a
  function value is consumed, so the symbol itself is the most faithful value — the
  jzon `:key-fn` residue `(funcall (symbol-function sym) str)` now runs. Two
  deviations vs the interpreter's live lookup: `functionp` of the result is nil, and
  an undefined name signals at the CALL, not at `symbol-function`. The literal-name
  fold is untouched.
- **`uiop:symbol-call` is REAL on the compile paths** (was interpreter-only, see the
  history in `.kb/asdf.md`): `expandUiopStubCall` lowers it to
  `(funcall (intern (string name) (find-package pkg)) args...)` over two fixed
  `%UIOP-SC-*` temps that keep the package-before-name evaluation order. Because that
  lowering happens INSIDE the per-expression compilers, AFTER the emission-gate scans
  ran, the pre-lowering spelling itself counts in two gates: the shared
  `containsRuntimeFunctionDesignator` (the registry/`_lookup` gate) and the WASM
  `usesIntern` (the real `_intern` body). **Any future lowering that synthesizes
  `intern`/`funcall`/`apply` at compile-expression time has the same obligation** —
  check every gate the synthesized forms need, or the runtime pieces are stubs.

The rest was already in place since the cl-postgres work: the registry and `_lookup`
themselves, symbol resolution in the funcall dispatchers and `_apply`, builtin
designators via the injected wrappers, and the WASM canonical-offset discipline
(`_intern` routes the runtime-built spelling to the same string-table offset the
registry row carries — alias rows add their spelling to the static table, which is
what makes the offset compare hit).

Tests: the *InternIntoALiteralPackage* / *InternIntoAComputedPackage* /
*InternIntoAnUnknownPackage* / *ApplyOfAnUndefinedRuntimeSymbol* groups in
JvmLispCompilerTest + WasmLispCompilerIntegrationTest, and the
`runtime-intern-funcall` ci-spec case.
