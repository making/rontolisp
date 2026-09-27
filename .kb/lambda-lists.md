# Lambda list extensions (`&optional`, `&rest`, `&key`, `&aux`, `&allow-other-keys`)

`am.ik.rontolisp.LambdaLists` -- one desugarer shared by the interpreter and both
compilers -- rewrites every extension into a native shape via a generated `let*` prologue:
for the interpreter (`expand`) required symbols plus an optional trailing rest param, the
optionals stepped off it; for the compilers (`toNative`, `desugarProgram`) required
symbols, PHYSICAL optional params and a rest param (section "Optional arguments travel as
parameters"). User docs: `doc/en/reference/special-forms/defun.md`, `lambda.md`.

- Unknown keywords signal `Unknown keyword argument: <prin1>` -- a `program-error`, through
  the `%program-error` primitive of [error-handling.md](error-handling.md) ("Argument-shape
  errors") -- unless `&allow-other-keys` is declared or the caller passes
  `:allow-other-keys t`; `&whole` is rejected. The key is rendered with `%prin1-piece`
  (routed, escaped, NOT mutable-wrapped: the text only feeds `%string-concat`); with
  `prin1-to-string` a `(defun k (a &key (b 2)) ...)` program carried the JVM array runtime
  for the wrap, 14,218 -> 10,138 B (2026-09-22), WASM unchanged.
- **The keyword scan is a CALL, not an inline loop.** A keyword parameter binds
  `(%ll-key-cell rest :kw upper)` (the plist cell or nil; `upper` is the upcased twin a
  lowercase-authored keyword also accepts, nil when the spellings coincide) and a function
  runs `(%ll-check-keys rest '(:kw ...))` once (the unknown-keyword / odd-tail signal, the
  `:allow-other-keys` rules in both spellings). Both are ordinary defuns with ONE Lisp body,
  `LambdaLists.runtimeDefun`: `desugarProgram` PREPENDS them to any program that spells
  `&key` anywhere -- quoted data included, because the scan may only over-approximate: the
  `flet`/`labels` and `destructuring-bind` prologues are written while the backend compiles
  a body, long after the pass, and all they have in common is the spelling -- and the
  interpreter evaluates them on the first resolution (`LispEvaluator.resolveFunction`, beside
  the prelude hook), which is the only place that can see a lambda-creation-time expansion
  coming. The names are `PackageRegistry.CL_SYMBOLS` members so a package never qualifies
  them. Before this (2026-09-13) every keyword parameter carried its own `do` loop: the
  hello-clack Worker went 795,062 -> 775,987 B (-2.4%, `--optimize=size`; gzip 209,489 -> 206,264), `zlib` 88,315 ->
  85,687, hello-tiny-routes 839,368 -> 814,808, hello_world/pi_approx unchanged -- and NOT
  the ~10% the census had attributed to the prologue: of its 701 "keyword-loop heads" on
  the Worker, 106 were keyword parameters and 10 the checks; the rest are other
  `do`-over-a-list loops with the same head shape
  ([optimize-dead-code-elimination.md](optimize-dead-code-elimination.md)). Pinned by
  `LambdaListsTest`.
- **Trap**: `&key` with NO key params still switches the tail to keyword convention
  (`Parsed.sawKey`); losing that marker makes the function fixed-arity.
- Helpers use the `__ll_` prefix; `PackageResolver` passes `&`-prefixed symbols through
  unresolved so markers survive in any package.
- **Interpreter**: `LispEvaluator.evalDefun`/`evalLambdaForm` call `expand` at
  lambda-creation time, so runtime-generated lambdas work; `LispLambda.rest` is nullable.
- **Compilers**: `Jvm/WasmLispCompiler.compile` run `LambdaLists.desugarProgram` right
  after `PackageResolver`; extraction points use `LambdaLists.toNative`. Quoted data is
  untouched, so forms headed for the runtime `eval` get NO lambda-list support
  (`doc/en/guides/eval-limitations.md`).

## A surplus argument is a program-error (2026-09-22)
**Invariant: a call with more arguments than the lambda list takes signals the catchable
`program-error` `Function expects at most N argument(s), got M` on all four backends** --
where the list ends in `&optional` and has no `&rest`/`&key` (those consume every argument;
the key check already refuses a stray one). The desugared function is physically variadic,
so neither the native count check nor a dispatcher's shape could see it: before this,
`(defun f (&optional a) a) (f 1 2 3)` answered 1 everywhere.

- **The check is the FIRST `let*` binding, before any default runs** (CL signals before
  binding): `(__ll_arity (if (cdr ... rest) (%program-error (%arity-surplus-message max req
  rest)) nil))` -- nested `cdr`s for up to three optionals, `nthcdr` past that
  (`LambdaLists.tooManyArgsCheck`).
- **The message is the internal primitive `%arity-surplus-message`**, text
  `ClosRegistry.aritySurplusMessage`: an `Environment` function on the interpreter, ONE shared
  runtime method `_aritySurplus(max, req, rest)` on the JVM (`JvmAritySurplusRuntimeBuilder`,
  emitted unconditionally like `_nthcdr`, dropped by the class shaker when unused), and on
  WASM `LambdaLists.lowerAritySurplusMessage` -- `%string-concat` over the non-consulting
  `%prin1-to-string`, so the count is decimal under any `*print-base*` (ci-spec
  `the-surplus-argument-message`). The first cut spelled it in Lisp as `prin1-to-string` over
  `(+ req (length rest))`; on the JVM that was NOT the printer (`_lispToString` is in every
  class already) but `prin1-to-string`'s mutable-result wrap (`_toMutStr`, `_strToCharVec`,
  `_arrayToString`, `_rmGet`, ...), the generic `_length` with the code-point helpers
  (`_cpidx`, `_scount`, `_cpsimple*`/`_cpwide*`) and a `_fx` adder -- +4.7 KB on a one-defun
  program. `nthcdr` for two optionals brought `_nthcdr` in too, hence the nested `cdr`s.
- **INLINE, not a helper defun like `%ll-check-keys`.** The first cut prepended a
  `%ll-too-many-args` defun on a program that spells `&optional`; seven JVM tests went red
  with `the function %LL-TOO-MANY-ARGS is undefined`, because `BuiltinFunctionWrappers`
  (`#'terpri`, `#'read-line`, ...) and several expansions build `&optional` lambdas while the
  backend compiles, after any program scan. The message is computed, which also keeps
  `CompileWarnings.warnStaticProgramError` (literal messages only) quiet.
- **`(a &aux x)` is now FIXED arity** (`Expanded.rest` null): it used to be desugared through a
  rest parameter it never read, so it silently dropped a surplus argument too.
- **A lambda list shorter than CL's is now a refusal of a LEGAL call** -- the real blast
  radius. Audited against `sb-introspect:function-lambda-list` for every `&optional` defun
  in the sources (111): `merge-pathnames` lacked `default-version` (ANSI
  `MERGE-PATHNAMES.1` went PASS -> ERROR until it took it), `parse-namestring` lacked
  `&key start end junk-allowed`; `apropos`/`apropos-list` match CLHS (SBCL's third argument
  is an extension). The first-class wrappers `#'read-line`/`#'read-char`/`#'peek-char` took
  one/one/two arguments and now take CL's whole optional tail; `#'read-line`'s body keeps a
  literal eof-error-p (`(read-line s)` or `(read-line s nil v)`), because its wrapper is not
  reference-gated and a computed one constructs an end-of-file instance the instance gate
  (decided before the wrappers exist) cannot see -- that first cut failed EVERY wasm
  compile with `%OBJ-NEW reached the compiler with no instance type emitted`.
  `#'write-line` takes the whole `(string &optional stream &key start end)` tail since
  2026-09-23 (`.kb/read-load-streams.md`, "`write-line` takes `:start` / `:end`").
- `complement`'s lambda (arities 0-3, `expandComplement`) takes a fourth argument
  through an `&rest` arm since 2026-09-23 (`.kb/sequence-designator-evaluation.md`,
  "What a variadic complement costs"); ANSI `COMPLEMENT.4`/`COMPLEMENT.8` moved ERROR
  -> PASS on the interpreter.
- **Measured**, interpreter, whole ANSI suite (suite `ca06bd9`, test NAMES diffed across every
  chapter): 37 tests fixed (`*.ERROR.N` rows: `BIT-*`, `CLEAR-INPUT`, `PPRINT*`,
  `DIGIT-CHAR`, `GET`, `GENTEMP`, `ENCODE/DECODE-UNIVERSAL-TIME`, ...), 0 regressed, lost
  forms 424 -> 424. The full `./mvnw test` corpus (the vendored quicklisp libraries on every
  backend) had no call that relied on the dropped argument.
- **Sizes** (bytes, JVM `.class` / Preview 1 / component, default `-o`):

  | program | before | after |
  |---|---|---|
  | `(print (+ 1 2))`, hello_world, pi_approx, a `handler-case`, a `mapcar` | | identical |
  | `(defun f (a &optional (b 2)) ...) (print (f 1))` | 5,177 / 1,193 / 2,357 | 12,414 / 1,193 / 2,357 |
  | two `&optional` defuns (one with 2 optionals) | 5,458 / 1,218 / 2,383 | 13,029 / 1,249 / 2,414 |
  | `(a &aux (b 2))` | 4,924 / 1,535 / 2,699 | 4,901 / 1,535 / 2,699 |
  | `merge-pathnames` (prelude) | 31,310 / 19,388 / 20,634 | 31,979 / 19,502 / 20,748 |
  | zlib | 159,290 / 106,621 / 110,637 | 159,639 / 106,855 / 110,872 |

  The JVM's +7 KB on a tiny `&optional` program was the Lisp-spelled message (above), not the
  count's printer. With `%arity-surplus-message` (2026-09-22; own programs, so the numbers
  differ from the table above; pre-921 = `10ff1ae31~`):

  | program | pre-921 | 921 | now |
  |---|---|---|---|
  | hello_world, pi_approx, `(print (+ 1 2))`, a `handler-case`, a `mapcar` | | identical | identical |
  | `(defun f (a &optional (b 2)) (+ a b)) (print (f 1))` | 7,708 / 981 / 2,126 | 12,394 / 981 / 2,126 | 8,112 / 981 / 2,126 |
  | the same without `print` | 7,664 / 194 / 402 | 12,350 / 194 / 402 | 8,068 / 194 / 402 |
  | two `&optional` defuns (one with 2 optionals) | 8,043 / 1,589 / 2,754 | 13,008 / 1,661 / 2,826 | 8,552 / 1,635 / 2,800 |
  | `merge-pathnames` | 31,347 / 19,389 / 20,635 | 32,016 / 19,503 / 20,749 | 32,039 / 19,477 / 20,723 |
  | zlib | 159,304 / 106,621 / 110,637 | 159,653 / 106,855 / 110,872 | 159,651 / 106,810 / 110,827 |

  The remaining ~+400 B on the JVM is `_aritySurplus` (~250 B) plus ~50 B of check and
  `%error` throw per function.

## A surplus element in a destructuring pattern (2026-09-22)
**Invariant: `destructuring-bind` -- and therefore every `defmacro` lambda list beyond
"required + one `&rest`" ([defmacro-backquote.md](defmacro-backquote.md)) -- signals the same
`program-error` as a function when a list level has an element past the pattern and nothing
(dotted tail, `&rest`/`&body`, `&key`) takes it; all four backends.** A missing position
signalled too since 2026-09-23 (next section).

- Same throwaway `__ll_arity` binding and `%arity-surplus-message` rail as the function check, so
  the message is `Function expects at most N argument(s), got M` (SBCL words it as a
  DESTRUCTURING-BIND parse error; the type is what programs test). No `prin1-to-string`,
  `length` or `nthcdr` in the error path.
- Where: `LambdaLists.appendTailBindings` (a tail with neither `&rest` nor `&key`: the check comes
  first, before any `&optional` default); `LambdaLists.destructuringSurplusCheck` for a
  required-only level -- nested `cdr`s over the level's source, which the level's `car`
  accessors already walk -- emitted per level by `LispMacroExpander.appendSurplusChecks`
  (keyword-free patterns), by `destructuringBindings` for a level whose keyword sits only in a
  sub-pattern, and for the empty pattern. **Not in `destructurePairs`**: `loop`'s destructuring
  shares it and discards a surplus value by definition (CLHS 6.1.1.7).
- **Blast radius, measured before landing**: the whole ANSI suite (interpreter, suite
  `ca06bd9`, test NAMES diffed across every chapter) moved 0 tests either way -- its
  `DESTRUCTURING-BIND.ERROR.*` cases are commented out upstream -- apart from the random-input
  `REMOVE-IF-RANDOM`; 19,513 test names before and after. The full `./mvnw test` corpus (every
  spliced library's `defmacro`/`destructuring-bind` on every backend) had no caller relying on a
  dropped element.
- **Sizes** (bytes, JVM `.class` / Preview 1 / component, default `-o`):

  | program | before | after |
  |---|---|---|
  | hello_world, zlib, a `defmacro` with `&optional` (expanded at compile time) | | identical |
  | `(defun f (l) (destructuring-bind (a b) l (+ a b))) (print (f '(1 2)))` | 7,671 / 2,275 / 3,422 | 8,100 / 2,320 / 3,467 |
  | the same with `(a &optional (b 2))` | 7,862 / 1,379 / 2,526 | 8,282 / 1,378 / 2,525 |

   The JVM's ~+420 B is `_aritySurplus` (~250 B, shared with every `&optional` defun) plus the check.

## A missing element in a destructuring pattern (2026-09-23)
**Invariant: a required element the destructured list runs out before signals the
catchable `program-error` `Function expects at least N argument(s), got M` -- the
lower-bound half of the arity report, the twin of the surplus check's upper bound --
on the interpreter, the JVM and WASM.** `(destructuring-bind (a b) '(1) ...)` and
`(defmacro m (a (b c) &optional d) ...) (m 1 (2))` no longer bind nil (SBCL signals both).

- Same throwaway-binding shape as the surplus check (`__ll_missing`, first so no
  `&optional` default runs for an already-short call), same computed-message rail
  (`%arity-missing-message`, both counts literals: no `prin1-to-string`, `length` or
  `nthcdr` on the error path, and the compilers' static program-error warning stays
  quiet). Where: `LambdaLists.destructuringMissingCheck`, emitted per level by
  `LispMacroExpander.appendSurplusChecks` (keyword-free patterns; a dotted tail excuses
  only what follows it, never the elements before it) and by `destructuringBindings`
  for the required prefix of a keyword-using level. **Not in `destructurePairs`**:
  `loop`'s destructuring still discards a surplus and binds nil for a short list (CLHS
  6.1.1.7).
- **The JVM message must be quote-framed.** The first cut reused the dispatchers'
  `_arityMsg` (unframed `java.lang.String`); the message lands in the condition's
  `format-control` slot, and an unframed string fails `stringp` on this backend
  (`JvmStringpCompiler`: a string is a quote-framed `java.lang.String`), so the report
  took the function-control arm and invoked the message --
  `The function Function expects at least 2 arguments, got 1 is undefined` -- whenever
  the condition was rendered (`princ-to-string`, `princ`). The fix is a dedicated
  `_arityMissing(required, got)` beside `_aritySurplus` (`JvmAritySurplusRuntimeBuilder`,
  emitted unconditionally and shaken out when unused, like its twin), answering the
  framed string out of the very constants `ClosRegistry.arityMessage` composes.
- **A non-list source is backend `car` parity, not this check's.** The interpreter and
  the JVM signal (`(destructuring-bind (a) 5 a)` is catchable); WASM traps, as it did
  before -- the `car` accessors the pairs bind run before any check, and making `car`
  of an atom catchable is out of scope. `--no-gc` refuses `destructuring-bind` at
  compile time as it has since the surplus check (neither message form has a scalar
  lowering).
- **Blast radius, measured before landing**: the whole ANSI suite on the interpreter
  (suite `ca06bd9`, test NAMES diffed across `data-and-control-flow` -- home of
  `destructuring-bind.lsp` -- and `iteration`, 2,271 names) moved 0 tests either way
  (its `DESTRUCTURING-BIND.ERROR.*` cases are commented out upstream; the only diffs
  were gensym numbers inside four unrelated failure texts). The full `./mvnw test`
  corpus (every spliced library's `defmacro`/`destructuring-bind` on every backend) is
  covered by the landing suite run.
- **Sizes** (bytes, JVM `.class`, same harness as the surplus table):

  | program | before | after |
  |---|---|---|
  | `(defun f (l) (destructuring-bind (a b) l (+ a b))) (print (f '(1 2)))` | 8,100 | 8,509 |
  | the same with `(a &optional (b 2))` | 8,282 | 8,512 |
  | `(defun f (a &optional (b 2)) (+ a b)) (print (f 1))`, `(print (+ 1 2))` | | identical |

  The JVM's +409/+230 B is `_arityMissing` plus the per-level check; a program that
  never destructures keeps neither helper (pinned by
  `JvmLispCompilerTest#theDestructuringMissingCheckCarriesNeitherTheStringRuntimeNorGenericLength`).

## Optional arguments travel as parameters (2026-09-26)
**Invariant: on both compilers a callee with `&optional` takes each optional as a parameter
of its own; a caller with no argument for one passes the UNSUPPLIED marker, and the rest
list holds only what is past the last optional -- so a call that passes an optional conses
nothing. Every call path agrees: direct calls, the per-arity dispatchers, `apply` (literal,
aligned or not, and computed through the spread dispatcher), the compiled `eval`, an
inline `((lambda ...) ...)`.** The interpreter's answers are unchanged.

- **The shape.** `(a &optional (b 10 bp) c &rest r &key k)` desugars to
  `(a &optional __ll_opt_0 __ll_opt_1 &rest __ll_rest)`, the prologue
  `(bp (%supplied-p __ll_opt_0)) (b (if (%supplied-p __ll_opt_0) __ll_opt_0 10)) ...
  (r __ll_rest) keys...`. A callee with an optional ALWAYS takes the rest list: without
  `&rest`/`&key` it is the surplus, and the count check is `(if __ll_rest (%program-error
  ...))`, first. The physical names are internal, never the user's: a default must not
  see a LATER parameter (`(&optional (x *s*) (*s* :bound))` reads the global `*s*`).
  `NativeForm.optionals`, `DefunDecl`/`FunctionInfo`/`WasmFunctionInfo`/`LambdaInfo`
  `.optionals`; `required()` is the count a call must pass.
- **Idempotent.** A lambda list already in this shape (`__ll_opt_N` plain optionals,
  `__ll_rest`, nothing else: `isPhysicalShape`) expands to itself with its body untouched.
- **Budget** `LambdaLists.MAX_PHYSICAL_PARAMS` = 10 physical params (env not counted), both
  backends (`WasmLispCompiler.MAX_CALLABLE_ARITY` may not be lower -- a static check says
  so). Optionals past it are stepped off the rest list as the interpreter steps them, so
  `(p1 .. p9 &optional a)` keeps the old shape. Past 10 the JVM would be fine, but a
  SPREAD case walks every param from the list head: O(P^2) per case.
- **The marker.** JVM: `_unsupp()` -> the `_unsupplied` field, created on first read by
  the `synchronized` `_unsuppInit()` (`JvmUnsupplied`); `_optArg(cell)` reads an optional
  out of an argument list (`car`, or the marker at the end) branch-free -- inline, each
  was two stack-map frames per spread case, +3.6 KB on an eval-carrying class. A
  `<clinit>` field was the first cut: it pinned the field in `(print (+ 1 2))`, whose
  only optionals were shaken out. WASM: the raw-local sentinel global (a `TYPE_CELL` built
  by a constant initializer, `WasmPhysicalArgs`); `%supplied-p` is one `ref.eq`
  (`WasmConditionCompiler`), and as a value reads the cached `t` global in place (a
  `_t_sym` call per supplied optional cost `#'<` a fifth of its call).
- **A supplied-p only tested is the test itself** (`testSuppliedPInPlace`): when every
  occurrence of `sp` after its binding is `(if sp ...)`, the binding goes and each test
  becomes `(%supplied-p __ll_opt_N)`. Any other occurrence -- a value, an assignment, an
  argument of a macro (`when`), a rebinding, a declaration -- keeps it.
- **Call sites.** `JvmPhysicalArgs` / `WasmPhysicalArgs` (direct, designator, the linalg
  kernel fallbacks); `JvmRuntimeBuilder.renderCase`/`renderSpreadCase`,
  `WasmRuntimeBuilder.emitDispatchCases` (a variadic joins every dispatcher of arity >=
  `required()`); `Jvm`/`WasmApplyCompiler` (aligned = leading args cover every param
  before the rest list); `Jvm`/`WasmLambdaCompiler.compileCall` binds a missing optional
  to the marker. Wrong counts: `DefinedCallArity` and the arity shapes use `required()`
  with the variadic flag -- the upper bound stays the prologue's.
- **`flet`/`labels`** expand before the backend is known, through `expandPhysical`: the
  interpreter meets the physical list too and binds a missing `__ll_opt_N` to
  `LambdaLists.UNSUPPLIED` (a `LispJavaObject` no value is `eq` to); `%supplied-p` is an
  `Environment` function for it.
- `wasm-export` of a variadic defun is refused like `jvm-export`'s (no host signature);
  WIT exports already refuse `&` params.
- **Measured** (2026-09-26, wasmtime 49, ABBA medians of 5, x86-64): sort 200,000 fixnums
  x10 through a variable predicate `(a b)` 1.35 -> 1.31 s, `(a &rest r)` 3.78 -> 3.77,
  `(a b &rest r)` 1.31 -> 1.37, `(a &optional b)` 3.99 -> 1.49, `#'<` 1.39 -> 1.37;
  `(reduce #'+ data)` over 1,000,000 fixnums x10 14.15 -> 0.24, `#'logior` 0.24 -> 0.24;
  `(f s 1)` on `(a &optional (b 1))` 10,000,000 times over a 1,000,000-cons live heap
  4.44 -> 0.22, `(f s)` 0.20 -> 0.22. The JVM showed no difference either side. (The
  error-handling table's 21.7 s for `(a &rest r)` came from a different program; the
  shape of the loss is the same: a rest list per call, costlier the larger the live heap.)
- **Sizes** (JVM `.class` / Preview 1 / component):

  | program | before | after |
  |---|---|---|
  | `(print (+ 1 2))`, hello_world, pi_approx | | identical |
  | `(defun f (a &optional (b 2)) (+ a b)) (print (f 1))` | 10,293 / 981 / 2,126 | 10,184 / 981 / 2,126 |
  | two optional defuns, optionals passed | 10,833 / 4,556 / 5,724 | 10,726 / 3,553 / 4,721 |
  | `(print (sort (list 3 1 2) #'<))` | 33,256 / 23,158 / 24,361 | 30,590 / 19,987 / 21,190 |
  | `(print (reduce #'+ '(1 2 3)))` | 24,634 / 8,812 / 9,945 | 15,577 / 4,589 / 5,731 |
  | `(print (eval '(+ 1 2)))` | 355,090 / 262,266 / 265,032 | 357,283 / 262,201 / 264,967 |
  | zlib | 188,659 / 127,495 / 131,568 | 186,480 / 126,194 / 130,267 |

  The one-default call measured 1,330 / 2,477 at first: `(if (ref.eq p marker) 2 p)` was
  opaque to `WasmRefTypeFolder`, so `b`'s set kept `TYPE_CELL` and `+` its non-fixnum
  arms. The fold now treats the marker global as a singleton object and decides the
  `ref.eq` (`.kb/wasm-ref-type-fold.md`, "Singleton objects"; 2026-09-27), pinned by
  `WasmRefTypeFolderTest.anOptionalNoCallPassesCostsNothingOnceTheMarkerTestIsDecided`.

## Variadic calling convention (both compilers)
Physically fixed-arity: required params, the physical optionals, then one trailing
rest-list param (`variadic`), reusing `TYPE_CALLABLE_BASE + paramCount` on WASM.

- `compileDirectCall` accepts any argument count; funcall/apply cap at the dispatcher tier.
- Runtime eval registry: arity is `-physicalParamCount`; negative = evaluate ALL arg
  forms (`buildArgList`), non-negative = exactly arity (`buildNArgs`, nil-padded).

## Gaps
- `defmacro` beyond "required + one `&rest`/`&body`" goes through `destructuring-bind`
  wrapping in `LispEvaluator.evalDefmacro` (`.kb/defmacro-backquote.md`); `&environment`
  is MACRO-only (`makeUserMacro`), rejected for functions.
- Runtime `eval`'s own `lambda` (funcId == -1) binds positionally; `--no-gc`
  (`NoGcWasmCompiler.extractDefun`) rejects the keywords.

## Tests
`LambdaListsTest` (the expansion shapes -- interpreter and physical, the budget, the
idempotence, the supplied-p test in place, the `flet` list the interpreter meets -- and the
two helper bodies); the physical optionals on every call path: ci-spec
`optional-arguments-travel-as-parameters`,
`JvmLispCompilerTest#compileAndRunOptionalArgumentsTravelAsParametersOnEveryCallPath`,
`#anOptionalIsAParameterOfTheCompiledMethodAndTheMarkerTravelsOnlyWithOne`,
`WasmLispCompilerIntegrationTest#optionalArgumentsTravelAsParametersOnEveryCallPath`,
`LispEvaluatorTest#optionalArgumentsAnswerTheSameOnEveryCallPath`;
`LispEvaluatorTest#defun{Rest,Optional,Keyword,Aux}`, `#defunEmptyKeySection`,
`#defunExtraArgumentsPastTheLambdaListSignalProgramError`;
`JvmLispCompilerTest#compileAndRunExtraArgumentsPastAnOptionalTailSignalProgramError`,
`#compileAndRunALegalCallAtTheFullClArityStillRuns` and their `WasmLispCompilerIntegrationTest`
twins; the destructuring surplus: `LispEvaluatorTest#evalDestructuringBindSurplusElementsSignalProgramError`,
`#defmacroSurplusArgumentsPastADestructuringLambdaListSignalProgramError`,
`UserMacroExpanderTest#macroArgumentErrorsSurfaceAtCompileTime`,
`JvmLispCompilerTest#compileAndRunDestructuringBindSurplusElementsSignalProgramError`,
`#theDestructuringSurplusCheckCarriesNeitherTheStringRuntimeNorGenericLength`,
`WasmLispCompilerIntegrationTest#destructuringBindSurplusElementsSignalProgramError`, ci-spec
`destructuring-bind-surplus-elements-signal-program-error`; the destructuring missing:
`LispEvaluatorTest#evalDestructuringBindMissingElementsSignalProgramError`,
`JvmLispCompilerTest#compileAndRunDestructuringBindMissingElementsSignalProgramError`,
`#theDestructuringMissingCheckCarriesNeitherTheStringRuntimeNorGenericLength`,
`WasmLispCompilerIntegrationTest#destructuringBindMissingElementsSignalProgramError`, ci-spec
`destructuring-bind-missing-elements-signal-program-error`;
`JvmLispCompilerTest#compileAndRunDefun{Rest,Optional,KeywordArguments}`;
`WasmLispCompilerIntegrationTest#compileAndRunDefun{RestAndOptional,KeywordArguments}`,
`#compileAndRunVariadicFirstClass`; ci-spec `lambda-list-*` (incl.
`lambda-list-extra-arguments-signal-program-error`),
`a-legal-call-at-the-full-cl-arity-still-runs`,
`trivia-enablement-language-group`.
