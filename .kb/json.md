# JSON (`rontolisp:json-parse` / `rontolisp:json-stringify`)

`rontolisp`-package functions (not CL) whose value mapping matches `com.inuoe.jzon`'s
defaults, so a program can switch to jzon unchanged; `JzonE2eTest` is the oracle. Docs:
`doc/*/reference/functions/rontolisp-json-{parse,stringify}.md`.

## Value mapping
- In: object -> `equal` hash table with string keys, array -> vector, `true`/`false`/`null`
  -> `t`/`nil`/the symbol `null`.
- Out: `nil` -> `false`, symbol `null` -> `null`, vector or list -> array, hash table or
  CLOS instance -> object (`%json-out-instance`, slots in definition order via
  `%class-slot-defs`). A symbol/slot-name key is down-cased unless it already holds a
  lower-case letter (jzon's `coerce-key`).
- Divergence from jzon: a `defstruct` instance serializes as an OBJECT.
- Trap: stringify tests `stringp` *before* `vectorp` (a string is a vector) and
  `(eq v 'null)` / `(null v)` before the general `symbolp` branch.

## Implementation
- One Lisp source `src/main/resources/am/ik/rontolisp/eval/json.lisp`, fixed-arity
  `rontolisp::%json-*` defuns in canonical package shape;
  `PackageResolverTest.jsonLibraryFormsAreAResolverFixedPoint` pins that resolving it is a
  no-op, which is what makes splicing before and after resolution sound.
- Interpreter: `LispEvaluator` dispatchers over `JsonLibrary.forms()`. Compile path:
  `JsonLibrary.process(program)` after `LoadInliner`/`UserMacroExpander`; a program without
  JSON is returned unchanged.
- Open cleanup: both public functions are single-arity now, so the dispatcher/`#'`-wrapper
  indirection could collapse.

## Portability rules inside json.lisp
- Only ASCII structural characters go through `char-code`; non-ASCII copied by `subseq`.
- `\uXXXX` emits one CHARACTER per code point (`%json-encode-char` -> `code-char`,
  surrogate pairs combined first) — `.kb/characters-code-points.md`.
- Integers to 18 digits parse exactly everywhere, wider as float (`.kb/wasm-bignum.md`);
  float output byte-identical on all four backends (`.kb/format.md`).
- No `read-from-string`, so compiled output does not pull in the runtime reader.

## Neighbours
- Prelude defuns (`LispPreludeLibrary`): `rontolisp:plist-hash-table` / `hash-table-plist`,
  `alist-hash-table` / `hash-table-alist` (`alexandria` subsets; first-key-wins, `eql`),
  and the hash-table-free `alist-plist` / `plist-alist` (ORDER-PRESERVING, duplicate-keeping).
- Shipped with this feature: WASM `_equal`'s string branch calls `_string_eq` and `_hash`
  folds content bytes (`h = h*31 + byte`) — `WasmRuntimeBuilder.buildEqualBody`/`buildHashBody`;
  `eql` still compares strings by identity (`WasmEqGeneralCompiler`).

## Tests
`LispEvaluatorTest` json* cases; `JvmLispCompilerTest.compileAndRunJson*`;
`WasmLispCompilerTest.jsonOpsCompileInEveryMode`; `WasmLispCompilerIntegrationTest`
(`jsonOpsWorkInPreview1Mode`, `equalAndHashTablesAcceptRuntimeBuiltStringKeys`); ci-spec
`json-parse-and-stringify`, `string-equal-content-and-hash-keys`, `alist-plist-conversions`.
Native binary embeds `json.lisp` via `resource-config.json` (typeReachable `JsonLibrary`).
