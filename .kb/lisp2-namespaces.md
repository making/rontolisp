# Lisp-2 (separate function/variable namespaces) in all three backends

- A bare symbol is a VARIABLE reference only (interpreter: `The variable X is unbound`;
  compilers: `Cannot compile symbol ...`).
- A symbol in call position resolves in the FUNCTION namespace only: a `let`-bound `car` never
  shadows the function `car`.
- A function value comes from `(function name)` / `#'name` (a `function` special form; the lexer
  emits `Token.FunctionQuote` for `#'`) or `symbol-function`.
- `funcall`/`map`/`reduce` accept symbol designators -- interpreter: at runtime via `apply`;
  compilers: `compiler.FunctionDesignators.normalize` statically rewrites a literal
  `(quote name)` in function position to `(function name)`, and `.literalName` reads it back so
  `funcall` / the map family / `reduce` / `sort` emit the DIRECT call instead of dispatching
  through a function value (`.kb/optimize-dead-code-elimination.md`). A quoted name with no
  definition is NOT rewritten (`.kb/error-handling.md`, "Undefined functions keep the call-time
  stub contract").
- `defun` defines into the function namespace and returns the name symbol.

Interpreter: `Environment` keeps two maps (`lookup`/`define`, `lookupFunction`/`defineFunction`;
builtins use `defineFunction`); `LispEvaluator.SPECIAL_OPERATORS` (=
`PackageRegistry.specialOperatorNames()`) lists names with no function value, so `#'if` errors.

Compilers: pass 1 collects only real `(defun ...)` -- a top-level `(setq f (lambda ...))` binds a
VARIABLE, called via `funcall`. `Jvm/WasmFunctionFormCompiler` compiles
`(function name)`/`symbol-function`. Eval runtimes keep a second function namespace (`_fenv`
field on JVM, `GLOBAL_FENV` wasm global). `FreeVarAnalyzer` skips the operator position and
`(function name)` / `(function (setf name))` designators: only a `#'(lambda ...)` operand is code.
Every walker that descends into `function`'s operand must make the same test
(`LambdaLists.setfFunctionPlaceName`); reading `(setf name)` as a form made a closure over
`#'(setf name)` capture a variable NAME (`Cannot capture variable` on the JVM, `Cannot find
variable for closure` on wasm) and hid the writer from `ReadBeforeStore`'s call graph.
Pinned by `SetfFunctionNameFixture.CLOSURE` (`aSetfFunctionNameIsAFunctionNameInsideAClosure`
in the three backend suites) and `ReadBeforeStoreTest#aFunctionTheTextNamesOnlyIndirectlyIsFollowed`.

## A native built-in's function value

**Invariant: every `cl` function but `require`/`provide` is a function value on every compiled
target** (`StandardFunctionValueCompileTest`
compiles `#'name` of all of them in one program). The catalog (`BuiltinFunctionWrappers.WRAPPER_DEFS`)
covers what the interpreter lowers in `evalCons`; the NATIVE built-ins it binds as Java
`LispFunction`s and the compilers lower in call position only (`NativeCallShapes`) had none, and
`#'arrayp` refused the program (measured 2026-10-07: 28 names, `array-dimensions arrayp close eval
export fdefinition fmakunbound get-internal-real-time get-internal-run-time
get-output-stream-string get-universal-time hash-table-rehash-size hash-table-rehash-threshold
hash-table-size hash-table-test import load make-random-state make-string-input-stream
make-string-output-stream make-synonym-stream open-stream-p rationalp row-major-aref
symbol-function unexport unuse-package use-package`; JVM, P1 and component identical).

- `BuiltinFunctionWrappers.NATIVE_VALUE_FUNCTIONS` names them; `FunctionValueWrappers`, which
  both compilers call for every injected wrapper, derives each value from the operator's
  `NativeCallShapes` row (`nativeValue`). It sits ABOVE the catalog: the bodies are built by
  `BuiltinCallArity` and `ShadowedBuiltins`, which read the catalog, so building them inside it
  is a class cycle (`PackageCycleTest`). The value: the required parameters, and where the row takes more,
  `ShadowedBuiltins.tailDispatch` over a `&rest` tail -- one direct call per admitted count, the
  interpreter's wrong-count text for the rest, `close`'s `:abort` checked. The shape stays the
  row's, so the catalog and the native table stay disjoint (`BuiltinCallArity.buildShapes`).
  `load` and `make-string-output-stream` read a keyword tail with `getf` (an injected wrapper
  cannot carry a `&key` check: its `%LL-*` helpers are spliced before it exists), so an unknown
  keyword passes where the interpreter rejects it.
- `make-string-input-stream`, `make-string-output-stream` and `make-synonym-stream` joined
  `NativeCallShapes` for it: the interpreter's `1 to 3 arguments` text became the shape's
  (`at least 1` / `at most 3`), on a direct call too.
- All are REFERENCE-GATED: the bodies reach runtimes a source scan turns on by the operator's
  name, and a program that takes none as a value is untouched. The scans that see the body
  only after the gates were taught the reference: `usesRuntimeFunctionBox` counts
  `#'symbol-function`/`#'fdefinition`, wasm's `usesEval` counts `makesComputedSynonymStream` -- whose
  computed arm traps on P1 and the component even in call position before 2026-10-07.
- `FunctionDesignators.normalizeBuiltinDesignators` rewrites a literal
  `(symbol-function 'name)` / `(fdefinition 'name)` of a reference-gated name to `#'name`
  (not a `setf`/`psetf` place, not under a local binding), so it injects the wrapper: it
  compiled to `The function FORMAT is undefined` for `format`.
- `#'require` / `#'provide` refuse with the computed call's text, `REQUIRE is only supported as a
  literal top-level form on the compile path` (`BuiltinFunctionWrappers.noFunctionValueMessage`).
- **Every `rontolisp:` row of `NativeCallShapes` but `await` (a special form) is an entry**
  (qualified names; the async ones: [async-await.md](async-await.md), "Function values"). A value
  does on each backend what a call does there (measured 2026-10-11 on all four):
  - Mutexes and `version`: all four. `json-parse`/`json-stringify` are no entries: `json.lisp` is
    spliced on every compile path, so its defuns were already the values (the todo's "undefined on
    the JVM" for them did not reproduce).
  - Threads: interpreter and JVM. On WASM a call is an undefined function, so the names are
    `BuiltinFunctionWrappers.WASM_UNSUPPORTED`: no wrapper (its body would call the wrapper
    itself) and `WasmFunctionFormCompiler.undefined` answers true, so `#'` is the catchable
    `undefined-function` a call is.
  - `tcp-*`: Preview 1's wrapper body is the call-time stub; under `--component` (and for `fetch`,
    `wait-for`, `tls-connect`, `tls-upgrade`) the spliced library defun is the value and no wrapper
    is injected (`userDefinedNames`).
  - A call that refuses the program refuses it as a value: P1's `fetch`/`wait-for`/`tls-connect`/
    `tls-upgrade`, both WASM's `tls-listen`/`tls-listen-pem`.
  - JVM `tls-listen-pem` is `BuiltinFunctionWrappers.JVM_UNSUPPORTED`: its call compiles only with
    literal paths (`TlsPemInliner`), and a value is a computed call, refused with that text
    (`TLS_LISTEN_PEM_COMPUTED_PATH_MESSAGE`). A wrapper would call itself: the inliner runs before
    the wrappers exist and the JVM has no lowering for the unrewritten call.
  - The paired `tls-connect`/`tls-upgrade` tail: the JVM lowering takes `:insecure` only as a
    literal, so `ShadowedBuiltins.call` checks the run-time keyword and passes a literal, another
    keyword being the interpreter's `TLS-CONNECT expects :insecure, got: :VERIFY`.
  Pinned by `RontolispNativeValueFixture` (`rontolispNativesAreFunctionValues` in
  `LispEvaluatorTest`/`WasmLispCompilerIntegrationTest`,
  `compileAndRunRontolispNativesAreFunctionValues`, `compileAndRunTlsNativesAreFunctionValues`,
  `compileTlsListenPemAsAFunctionValueIsRefusedAsAComputedCall` in `JvmLispCompilerTest`, ci-spec
  `rontolisp-natives-are-function-values`).
- `--component`'s sockets rewrite (`WasmSocketsRewrite`) runs before the wrappers exist, so
  `#'close` of a socket stream skips its `%io-close` bookkeeping, as `#'listen` does.

## A lexical variable named like a function

CLHS 11.1.2.1.2.1 lets a program lexically bind any `cl` symbol that is not a variable, so
`(let ((close s)) close)` and `(dolist (read-line l) ...)` are legal and must not meet the
function. The front-end passes that rename CALL sites (`ShadowedBuiltins`, `GrayStreamsLibrary`,
`WasmSocketsRewrite`) all walk through `compiler.CallSiteWalk`, which offers a rewriter only the
lists in code positions and keeps a binding's variable, a lambda list, a `case` key, a
`handler-case` clause head, a `cond` clause (every element a form), a `loop` destructuring
pattern after `for`/`as`/`with`, a slot spec's name and options, `declare`, `the`'s type, a
setf place's head, quoted data and `defmacro`. The walk sees the built-in macros unexpanded,
so their shapes are in it too; a new rewrite of call heads walks through it rather than its
own copy.

Until 2026-10-24 each pass had its own walk, and they had drifted: the socket rewrite knew no
binding form at all, so a `--component` program splicing socket I/O (any program reading stdin
and sleeping, or taking `#'read-line` and `#'sleep`) failed `(let ((close s)) ...)` with
`Cannot compile symbol: CLOSE` and silently compiled `(case k (close 1))` against the key
`%IO-CLOSE`; the Gray rewrite failed `(dolist (read-line l) ...)` on the three compiled
backends. Pinned by `WasmSocketsRewriteTest#anUnknownShapeOrAListThatIsNoCallKeepsItsHead`,
`GrayStreamsLibraryTest#aProgramBindingNoStandardStreamKeepsItsStreamlessCallsAndItsNonCalls`
and `StandardFunctionValueCompileTest` (its every-value program binds `close`).
