# The EXPERIMENTAL Scheme front end (`am.ik.rontolisp.scheme`)

**Invariant: a Scheme program is read, desugared and LOWERED to the Common Lisp core forms
the pipeline already consumes; no backend learns a Scheme name.** There is no IR beneath
those forms to target instead: both backends dispatch on the canonical operator NAME and
`FreeVarAnalyzer` is a hand walker over the same names, so an unknown head would be a
silent three-way divergence. Status is experimental -- partial R7RS-small conformance by
design, no compatibility promise -- and every user surface says so (`--source-language`
help, the title of `doc/*/scheme/index.md`). `--no-gc` is refused by name
(`CompileFrontend.run`): it has no cons cell, no symbol and no closure.

## Where it sits

- `scheme` depends on the AST types and `reader` only; `SchemeReader` is its own
  case-sensitive reader. Facade `Scheme`.
- Reached ONLY through the seam `eval/SourceLanguage.SCHEME` (`.scm` or `--source-language
  scheme`, `.kb/source-language.md`). Per FILE, so a Common Lisp file may `(load "lib.scm")`
  and call its procedures as `(|name| ...)`.
- The run-time half is Common Lisp source, `eval/scheme.lisp`, handled the `UrlLibrary` way
  by `eval/SchemeLibrary`: loaded by the interpreter on the first `rontolisp::%scheme-`
  FUNCTION resolution, spliced innermost by `CompileFrontend.expand` (INSIDE the prelude
  whose string comparisons it uses), pruned by `LibraryDefunPruner`. Some definitions are
  GENERATED from the front end's tables (`Scheme.runtimeForms`): `eval`'s procedure table and
  `environment`'s predicate, the `(scheme char)` tables, the feature list.
- A whole FILE is lowered at once: defun-or-variable is decided by a pre-scan. A REPL lowers
  through a session ("A session").

## The lowering table

| Scheme | lowers to | why |
|---|---|---|
| identifier `foo` | symbol `foo`, verbatim | canonical names are upcased, so a name with an ASCII lowercase letter can never hit a `LispNames` case label |
| `CAR`, `X`, `T`, `+`, `&REST` (no lowercase), `a:b`, `s%...`, `#f` | escaped: `s%` + name, `%`->`%%`, `:`->`%c` | `(defun \|CAR\| ...)` REPLACES the built-in; `a:b` is "symbol b of package a" to the resolver; `&REST` is a lambda-list keyword; the prefix and `#f` keep the map injective and the false value unforgeable. The rule is spelled twice (`SchemeNames.mangle`, `%scheme-needs-escape` in `scheme.lisp`) -- change both |
| `'()` | `NIL` | `&rest` lists, `apply` and every list primitive end in it |
| `#t` | `T` | |
| `\|foo bar\|`, `\|\|`, `\|#t\|` | an identifier of that spelling, mangled like any other (`\|#f\|` -> `s%#f`) | the reader's booleans are compared by IDENTITY, so `\|#t\|` is no boolean |
| `+inf.0` `-inf.0` `+nan.0` `-nan.0` | a flonum literal | every backend already emits and prints a non-finite double |
| `#f` | the VALUE of `rontolisp::%scheme-false`, the symbol `\|#f\|`, bound by the first form of every lowered file | distinct from NIL; a symbol so quoted data, `case` and `equal?` need nothing special |
| the unspecified value: an `effect` builtin, `set!`, the missing arm of `if`/`when`/`unless`, a `cond`/`case` with no clause taken, `(begin)` | the VALUE of `rontolisp::%scheme-unspecified`, the symbol `\|#!unspecific\|`; `(progn effect U)` -- but the raw effect / `NIL` where the value is DISCARDED (`Context.discarded`) | ONE object, so a REPL can skip it by value; not `NIL` (`(list (if #f #f))` has length 1) and true in a test. Escaped like `#f`, excluded by `symbol?`. Spelled `CORE_UNSPECIFIED` in desugarings |
| `exit`, `emergency-exit` (`(scheme process-context)`, in the no-import default) | `exit` throws its code to `rontolisp::%scheme-exit-tag`, caught per top-level form ("`exit` runs ..."). `emergency-exit` calls `%scheme-exit` directly: finish both output streams, `%host-exit` with `#t`/none 0, `#f` 1, an integer's low 8 bits | the `uiop:quit` primitive (`.kb/uiop.md`); only `emergency-exit` skips the `dynamic-wind` afters |
| `command-line` (`(scheme process-context)`) | `(%host-argv)`: program name, then arguments | matches `gosh -r7` 0.9.15 (`scheme-spec` `a-valid-r7rs-program-prints-the-same-under-both-standards`, run with arguments on four backends). |
| `get-environment-variable`, `get-environment-variables` (`(scheme process-context)`) | `(%host-getenv name)` (`or-false`, no `uiop:getenv` override map); `(%scheme-environment-variables (%host-environ))` splits the "NAME=value" strings at the FIRST `=` (the call names the primitive, not the `scheme.lisp` defun: a defun naming it made every `--component` Scheme program that spliced `scheme.lisp` import `get-environment`, +79 B measured 2026-11-25). Order is the host's, unspecified | matches `gosh -r7` 0.9.15 (same `scheme-spec` case, `environment:` on every leg: `LispEvaluator.setEnvironmentVariables`, `ProcessBuilder.environment`, `wasmtime --env`). |
| `(if c a b)` | `(if (eq c false) b a)`; a predicate fuses: `(if (pair? x) ..)` -> `(if (consp x) ..)`, `and`/`or`/`not` compose | `SchemeBuiltins.Result`: `pred` (T/NIL) and `or-false` (value or NIL: `memq`, `assq`, `member`) fuse in a test and convert anywhere else |
| top-level `(define x v)` | top-level `(setq x v)` | NEVER `defvar`: that makes the name special and a `let` of it leaks into callees |
| top-level procedure defined ONCE by a `lambda`, never `set!`, and -- when it shadows an import -- never read before that definition | `defun`, called directly | keeps the direct call and the tree shaker. `set!` is collected by name, blind to scope: over-approximating only costs the direct call |
| any other procedure binding | variable + `funcall`; a procedure name in value position -> `#'name` | Lisp-1 over Lisp-2 (`.kb/lisp2-namespaces.md`). A known procedure's first-class value is its `:function` form in `SchemeBuiltins` |
| a name this file does not define | call position: a direct call; value position: a variable | what lets a Common Lisp file and a Scheme file call each other |
| `(lambda args ..)`, `(lambda (a . r) ..)` | `&rest` | dotted formals are parsed here: `LambdaLists.parse` DROPS an improper tail |
| named `let` / `do` whose name is only tail-called; a self tail call | `tagbody`/`go`, every `go` in STATEMENT position | "Destination-driven lowering" |
| named `let` whose name escapes; `letrec`; internal `define` | bind to nil, `setq` a `lambda`, `funcall` | the shape `labels` itself lowers to (`.kb/flet-labels.md`), so `labels` would buy no direct call |
| `cond` `case` `and` `or` `when` `unless` `do` | desugared to `if`/`let`/`begin`/named `let` first | spelled with identity-compared `CORE_*` symbols: `(define (f if) (or if 1))` still gets the real `if` |
| `call/cc` | `block` + a closure doing `return-from` (`%scheme-call/cc`) | escape-only, one-shot; crosses lambdas through `CrossLambdaExitLowering` |
| `(guard (v clause..) body..)` | `(%scheme-guard (lambda () body..) (lambda (C) (let ((v C)) (cond clause.. (else (%scheme-raise C))))))` | "Exceptions" |
| `dynamic-wind` | `before`, then `unwind-protect` | the exit half runs on every exit channel; re-entry does not exist |
| `(case-lambda (formals body..)..)` | `(lambda (&rest A) (let ((N (length A))) (if (= N 1) .. (%scheme-case-lambda-arity A))))`; a top-level procedure defined once by one: a `defun` per clause (`s%%{f 1}`, ..) picked by a direct call's count, `f` the dispatch | "`case-lambda`" |
| `(parameterize ((p v)..) body..)` | `(%scheme-parameterize (list p v ..) (lambda () body..))`; no binding: `(let () body..)` | "Parameters" |
| `call-with-values` of two lambdas, `let-values`, `define-values` | `multiple-value-bind` | the syntactic tier (`.kb/multiple-values.md`). Other shapes: `(apply consumer (multiple-value-list (funcall producer)))`. A loop leaf that may answer other than one value leaves through `(return-from B ..)` -- a `(setq R (values a b))` keeps one value |
| top-level `define-record-type` | `defstruct` with `(:conc-name nil)`, slots NAMED after accessors, a BOA constructor; the modifier a `defun` over `(setf (accessor r) v)` | `defstruct` registers the instance layout on every backend (`.kb/defstruct.md`); no wrapper call. The predicate is a `pred` (`GlobalPredicate`) |
| `define-record-type` in a body | the same `defstruct`, HOISTED, named `s%%[<enclosing definition> <type>]` | "Internal record types" |
| `quasiquote` | `cons`/`append`/`(coerce .. 'vector)`, constant parts quoted | depth-counted per R7RS |
| `(define-library (a b) ...)`, `(import (a b))` | the library body lowered once, before the importer, private names `s%%(a b)name`; an export stays a direct call | "Libraries and include" |
| `(include "f")`, `(include-ci "f")` | `(begin <f's datums>)`, spliced before macro expansion | the same section |
| `(cond-expand (req body..)..)` | `(begin <the taken clause's body>)`, decided while lowering, spliced before macro expansion | "`cond-expand`" |
| `define-syntax` / `let-syntax` / `letrec-syntax` with `syntax-rules` | expanded away before the lowering (`SchemeExpander`) | "Macros" |
| `#u8(...)`, `bytevector`, `make-bytevector`, `bytevector-append`, `string->utf8` | the `(unsigned-byte 8)` pack (`.kb/packed-integer-vectors.md`); the literal is a self-evaluating 8-bit `LispIntVector` datum | "Bytevectors" |
| a port procedure; the optional port argument of `display`, `read-char`, ...; `(current-output-port)` | a `%scheme-` helper over a `%scheme-port` record (binds `*standard-output*` around the printer); no port argument: the old template. A current port's VALUE is `(%scheme-port-parameter 1)` | the standard streams are the `t` designator on the compiled backends, not values ("Ports") |
| `(eval datum env)` and every environment specifier | `(%scheme-eval-in datum '\|#[environment]\|)`: a Scheme evaluator over DATUMS in `scheme.lisp`; every specifier is the one global environment | the backends' run-time `eval` evaluates core forms, so one evaluator serves all four ("`eval`") |

`symbol?` excludes `T`, `NIL` and the false value; `boolean?` is `#t`/`#f` only; `vector?`
is `simple-vector-p` (excludes strings and bytevectors; `vectorp` does not); `integer?`
accepts `2.0`; `max`/`min` are inexact when any argument is; `equal?` is its own helper
(recurses into vectors, `eqv?` on records; CL's `equal` compares a general vector by
identity and an instance slot-wise).

## Where a Common Lisp function answers differently

Checked against Gauche 0.9.15; pinned by `numbers-lists-and-records-at-their-r7rs-edges`
(plus `list-copy` in `pairs-and-lists`), two `standalone:` error cases, and
`SchemeBuiltinsTest.anArgumentR7rsMakesAnErrorIsRefusedByName`:

- `quotient`/`truncate-quotient`/`floor-quotient`, `odd?`/`even?`, `gcd`/`lcm`: inline CL
  operation behind `(integerp ..)`, else a helper that refuses a non-integer by name (CL's
  `oddp` answered for `1.5`) and answers inexact when an argument is (CL's `truncate`
  answers an integer, `gcd` refuses a float).
- `rational?`/`integer?` exclude the infinities and NaN (`realp` did not); `number->string`
  applies the radix to a ratio's two parts (`princ-to-string` ignored it).
- `list-copy` keeps a dotted tail and answers a non-pair itself; `list?` is Floyd's cycle
  check.
- `reduce` is SRFI-1's `(f elem acc)` (CL's is `(f acc elem)`); `fold-left`/`fold-right`
  take several lists (MIT).
- `string`/`list->string` refuse a non-character; `stream-car`/`stream-cdr` refuse a non-pair.
- `case` compares with `eql`, and two equal string LITERALS are one object on every backend
  (CLHS 3.2.4.4 coalescing, `.kb/hash-tables.md`), so `(case "a" (("a") ..))` matches --
  conforming (R7RS 6.1 leaves it unspecified); a computed key does not match, as in Gauche.
- **Never `float` an exact value in a helper**: the flonum constructor stays reachable in a
  program that makes no flonum, and wasm's type-test fold (`.kb/wasm-ref-type-fold.md`) can
  no longer drop the flonum arms of the arithmetic and printer. `%scheme-inexact-like` adds
  `(- x x)` of the inexact argument instead. The remaining cost is the named refusal
  (`%scheme-error-message`'s string stream).
- See also [copy-list-runtime.md](copy-list-runtime.md).

## `exit` runs the outstanding `dynamic-wind` afters

`exit` lowers to `(throw 'rontolisp::%scheme-exit-tag code)` (template and `:function`, so
an `eval`'d exit throws too); every FILE top-level form runs inside

```lisp
(let ((%done (list nil)))
  (let ((%code (catch 'rontolisp::%scheme-exit-tag (progn <form> %done))))
    (if (eq %code %done) nil (rontolisp::%scheme-exit %code))))
```

The throw unwinds through `%scheme-dynamic-wind`'s `unwind-protect`s, so afters run on
every backend. The fresh cell tells a throw from completion whatever the code: `(exit '())`
throws NIL.

- **One catch per form, not per file**: the backends only hoist a `defun`/`defstruct` that
  is a direct child of the program, so those stay bare (defining never throws); the leading
  setqs stay bare too. Hoisting defuns first would reorder definitions before preceding
  expressions, breaking the deliberate call-before-define error.
- **Only a file that can reach the throw is wrapped**: a datum spelling `exit`, a string
  one may read it from, or `eval`. Anything else compiles to the same bytes and pulls no
  exit machinery (`ExitLibrary`'s invariant; a `--no-wasi` reactor stays acceptable). A
  top-level `load` call stays bare: the guard's `LET`/`CATCH` shape would hide it from
  `LoadInliner`'s top-level match, and the loaded forms carry their own guards.
- **A session wraps every entry unconditionally**, answering the last form's VALUES: the
  guard stores `(multiple-value-list form)` and answers `(values-list ..)`, because a value
  through a variable and an `(eq ..)` is one value (`.kb/multiple-values.md`). A last form
  that is itself `(VALUES ...)` keeps its shape with its ARGUMENTS guarded: the prompt echoes
  through `evalValues`, which takes the multi-value path only for that shape.
- **Residual**: an `exit` inside a file `load`ed at run time is caught by the loaded file's
  wrapper, skipping the loading file's afters (the compile path inlines the load, so there
  they run).

Cost: a program spelling neither `exit` nor `eval` is byte-identical; one that exits pays
the tag and its landing pads plus one catch region per wrapped form.

## Internal record types (`define-record-type` in a body)

**A body's `define-record-type` is a top-level `defstruct`, hoisted; the body binds names,
not variables.** The compile path refuses a `defstruct` below the top level
(`.kb/defstruct.md`), so `SchemeRecordLowering.internalRecord` emits it into `hoisted`,
AHEAD of the enclosing top-level form; `body` binds each record procedure to a
`GlobalFunction` / `GlobalPredicate`, so calls stay direct and a predicate fuses. A retried
body finds its record in `internalRecords` by datum identity.

- **Names**: `s%%[<enclosing> <type>]` (`<enclosing>` the mangled top-level `define`, empty
  otherwise, ` 2`, ` 3` ... on collision). No identifier mangles to `s%%[` and neither part
  holds a space, so the map is injective. Residual: two separately lowered files whose
  unnamed top-level forms define a same-named type share `s%%[ t]`. Slots are the FIELD
  names (the expander may have renamed accessors); an internal record prints
  `#S(s%%[f node] :v 42)`.
- **One type per occurrence, not per evaluation.** Gauche 0.9.15 makes a new type per
  evaluation; that would need a run-time type token in every instance (visible in every
  print) and closures for constructor/predicate, for a difference seen only by mixing two
  evaluations' instances. A session keeps `internalRecordNames`, so a definition typed again
  names a new type.
- **Scope**: a record procedure defined twice in one body, or `set!` of one, is refused;
  outside a body (`(if c (define-record-type ..))`) it is refused.
- **Macros**: `SchemeExpander.body` binds the record's procedures in the body's frame and
  emits them renamed; type and field names are labels, stripped.
- **JVM**: `[` (and `;`) is illegal in a JVM method name; `JvmLispCompiler.mangleMethodName`
  maps it (`.kb/core-representation.md`).
- Programs with no internal record compile byte-identical.
- Pinned by `SchemeLoweringTest` (`anInternalRecordType...`,
  `internalRecordTypesOfTheSameNameAreDistinctTypes`,
  `aMacroUseBesideAnInternalRecordTypeKeepsItsNamesLocal`), `SchemeSessionTest.
  anInternalRecordTypeTypedAgainIsANewTypeHoistedIntoItsEntry`, the
  `internal-record-types-are-local-to-their-body` case of `scheme-spec.yaml` (four backends,
  Gauche's output), and `JvmLispCompilerTest.
  bracketAndSemicolonInAFunctionNameAreMangledAway`.

## Vertical-line identifiers and the infinities

- **Reader**: `|...|` is an identifier of any characters (`\|` `\\` `\"` `\xHH;`, mnemonic
  escapes); `|` delimits (`a|b c|` is two datums, as in Gauche); `#!fold-case` never folds
  it. `+inf.0` `-inf.0` `+nan.0` `-nan.0`, case-insensitive, any radix, are flonums (every
  NaN writes `+nan.0`); `+inf.00`, `inf.0` stay symbols. `SchemeReader`, the run-time `read`
  and `string->number` agree.
- **The booleans are identity symbols.** `SchemeReader.TRUE`/`FALSE` are `LispSymbol`s named
  `#t`/`#f`, and a record compares by name, so `.equals(TRUE)` is true of `|#t|`. Every site
  tests `==` / `SchemeReader.isBoolean` (`SyntaxRules.datumEquals` too). A new site must too.
- **`write` puts a symbol between vertical lines when its spelling would not read back**:
  R7RS 7.1.1 `<identifier>` less the `<infnan>` spellings, every non-ASCII character a letter
  (Gauche writes `λx` bare); a control character as `\x<2 hex>;`. The false value, the
  unspecified object and the environment symbol never take lines. One deviation from Gauche:
  it writes `|+inf.0x|`, a valid R7RS identifier written bare here.
- **The grammar is spelled twice** -- `SchemeNames.writtenWithVerticalLines` and
  `%scheme-plain-identifier-p` -- pinned against each other by
  `SchemeBuiltinsTest.writePutsASymbolBetweenVerticalLinesExactlyWhenTheFrontEndSaysSo`.
- **Gated like the bytevector arm** (`SchemeLibrary.BAR_SYMBOLS_FEATURE`): the printer calls
  `%scheme-write-symbol` only in a program that quotes a symbol the grammar lines or can
  intern any name (`intern`/`make-symbol`, or `string->symbol`, `read`). The writer walks the
  spelling as a character LIST: a string costs the string machinery on top.
- **Composed internal names escape their parts** (`SchemeNames.component`: ` `, `)`, `]`,
  `|` -> `|s` `|p` `|b` `||`): otherwise `(define-library (|a b|) ..)` and `(a b)` collide.
- **Infinities in the helpers**: Common Lisp's `floor` clamps a non-finite float to a long on
  every backend, so `floor`/`ceiling`/`round`/`truncate` of a flonum are
  `%scheme-flonum-floor` & co., answering a flonum of magnitude >= 2^52 and a NaN as itself.
  `exact` of an infinity or NaN is refused with a constant message (`%scheme-exact-flonum`;
  `%scheme-error-message`'s irritant formatting is most of a refusal's cost on wasm). See
  [[linalg-simd]], "mod / rem and the floor family".
- **Left as found**: `(eqv? +nan.0 +nan.0)` is `#t` on every backend (`eql` of one bit
  pattern; R7RS unspecified, Gauche `#f`). `(eq? x x)` of a flonum variable is `#f` on the
  interpreter and the JVM, `#t` on wasm -- a Common Lisp split.
- Programs spelling none of these names are byte-identical; writing a read datum tows the
  reader's error machinery.
- Pinned by `SchemeReaderTest`, `SchemeNamesTest`, `SchemeLoweringTest.
  aVerticalLineIdentifierLowersLikeAnyOtherAndIsNeverABoolean`, `SchemeLibraryTest`,
  `SchemeLibrariesTest.aLibraryNamePartWithASpaceKeepsItsNamesApart`, and the
  `vertical-line-identifiers-are-symbols-of-any-spelling`,
  `infinities-and-nan-read-print-and-compare` and `read-knows-vertical-lines-and-infinities`
  cases of `scheme-spec.yaml` (four backends, Gauche 0.9.15's output).

## `string->number`'s radix and exactness prefixes

- **Two independent parsers share the grammar, never each other's code**:
  `%scheme-string->number` (`scheme.lisp`; also `%scheme-hash-token-datum`, so a `#x10` token
  from `(read)` and a `"#x10"` string answer alike) and `SchemeReader.prefixedNumber`
  (compile-time literals). Radix and exactness, each at most once, in EITHER order. A
  repeated or unknown pair is left in the remainder, so the leftover `#` fails downstream and
  an invalid prefix answers `#f` like an invalid number -- no separate validity branch.
- **`#e` on a decimal is the exact rational the digits spell, never a flonum rounding**
  (`"#e1.1"` is `11/10`): built from mantissa/scale/exponent, never through `float` /
  `Double.parseDouble` (`BigDecimal.unscaledValue()`/`scale()` in Java).
- **An inexact decimal is the double nearest its digits**: `%scheme-decimal` hands mantissa
  and `exponent - scale` to the prelude's `%decimal-double` (shared with the Clojure reader),
  rounding once on every backend. A value surely past the double range answers from the
  mantissa's bit length (`"1e100000000"` is `+inf.0` at once, as in Gauche, instead of
  building 10^100000000). Pinned by `decimal-string-to-number-is-the-nearest-double`.
- **An exactness prefix on an infinity or NaN is a no-op**, matching Gauche: the infnan
  check runs before exactness (`exact` itself refuses an infinity).
- Pinned by `SchemeReaderTest.radixAndExactnessPrefixesCombine`,
  `.anExactnessPrefixOnAnInfinityOrANanIsANoOp`, `.aRepeatedOrUnrecognizedPrefixIsAReadError`,
  and `string-to-number-radix-and-exactness-prefixes` of `scheme-spec.yaml` (four backends,
  `gosh -r7`'s output). `%scheme-number-prefix`'s tail is a three-value `values`, so it is
  in `SchemeValueCount.PASSING_HELPERS` (`.kb/multiple-values.md`).

## A file that reads an imported name before it redefines it

- **Such a name is a variable, initialized to the import's value** by a second leading
  `setq`, and every reference goes through it: `(define old-abs abs) (define (abs x) ...)`
  keeps the host's `abs`, as the SICP evaluators keep `apply`. A `defun` is position-blind
  (interpreter: `abs` undefined; compile path: hoisted, `old-abs` WAS the user's `abs`).
- "Read before": a name mentioned (scope-blind, `collectNames`) by a form RUN at the top
  level at or before the name's first definition, directly or through names defined before
  that form (`SchemeDefinitionLowering.readBeforeDefinition`). `(define (f x) (abs x))
  (define (abs x) ...) (f 1)` keeps both `defun`s.
- No SICP sample reads a builtin before redefining it (lowered forms of all 1,592 unchanged).
  Pinned by `SchemeLoweringTest` and the `a-builtin-used-before-...` case of
  `scheme-spec.yaml` (four backends).
- **A NON-imported name called before its `define`** is left as is, deliberately: the
  interpreter says undefined, the compile path's hoisted `defun` answers. Refusing it would
  cost the direct call of every procedure for a program that is wrong anyway.

## The library tags: `base`, `write`, `read`, `char`, `inexact`, `complex`, `cxr`, `lazy`, `case-lambda`, `process-context`, `eval`, `repl`, `file`, `sicp` and `r5rs`

`SchemeBuiltins` entries carry the R7RS library that exports them, checked against Gauche
0.9.15's `(module-exports (find-module 'scheme.<lib>))`. Traps: `read` is `read` alone
(every port procedure, `eof-object`, `read-char`, `read-line` ... is `base`, as in R7RS);
`load` is the Common Lisp `load` (inlined on the compile path, run-time on the interpreter,
read as Scheme either way). `SchemeLibraryLowering.IMPORTABLE_LIBRARIES` are the R7RS ones;
a file with no import merges all of them. Keywords carry a library too (`SYNTAX` `base`,
`LAZY_SYNTAX` `lazy`, `CASE_LAMBDA_SYNTAX` `case-lambda`, `SICP_SYNTAX` `sicp`).

`sicp` (constants via `SchemeLowering.Constant` over `SchemeBuiltins.constants()`, since they
are values, not procedures) is no R7RS library, so no import names it: the no-import branch
of `SchemeLibraryLowering.imports()` merges it, an explicit import list narrows to exactly
what it names. `r5rs` (`scheme-report-environment`, `exact->inexact`, `inexact->exact`)
rides the same default: `(scheme r5rs)` would promise the whole of R5RS, so it stays refused
by name. A user `define` of any of these wins, like `square`
(`SchemeDefinitionLowering.declareGlobals` overwrites the scope entry) -- under
`--scheme-standard rontolisp`; `r7rs` refuses it.

## `--scheme-standard rontolisp|r7rs`

One option, like `gosh -r7`, for EVERY Scheme file of the program: the entry file, a file
`load`ed (`LispEvaluator.loadFile`) or inlined (`LoadInliner`), and the REPL
(`SchemeSession`). `rontolisp` (default): R7RS plus `sicp` and `r5rs`, no import needed.
`r7rs` is R7RS-small within the implemented subset:

1. **A file that does not begin with `import` is refused** at its first datum (R7RS 5.1).
   Gauche instead starts empty and fails at the first unbound name.
2. **`sicp` and `r5rs` names are never visible.**
3. **Redefining an imported binding in a file is refused** (R7RS 5.6.1; Gauche -r7 allows
   it silently): `define`, `define-values`, a `define-record-type` name over a `Builtin` or
   `Syntax` binding (`refuseRedefiningAnImport`, before `declareGlobals`), and a `set!`
   resolving to a `Builtin`. A SESSION may redefine, as gosh's `r7rs.user` does.
4. **`eval` needs its environment**: `SchemeBuiltins.entries(R7RS)` swaps in the `R7RS_TABLE`
   two-argument row.

**Decision: `eval`'s run-time table follows the standard.** Under `r7rs` the generated
`%scheme-builtin` holds no `sicp`/`r5rs` name and `%scheme-eval-extension-keyword-p` answers
nothing, so `(eval '(1+ 1) (environment '(scheme base)))` is `Unbound variable: 1+` (as Gauche
-r7). Every specifier is still the one global environment; only the non-R7RS names are cut.
The interpreter's copy is `SchemeLibrary.forms(SourceStandards)`, cached per features x
standard.

**Plumbing.** `scheme/SchemeStandard` crosses the seam wrapped in `eval/SourceStandards`, so
`cli` holds it without an edge to `scheme` (`.kb/source-language.md`).
`JvmSourceCompiler.schemeStandard(String)` is the embedder's. The Maven plugin compiles
`.lisp` only and has no parameter; the playground keeps the default.

Unchanged in both: a name the file neither imports nor defines is a direct call or variable
reference -- how Scheme and Common Lisp files call each other; a whole-program unbound-name
check would cross files.

Pinned by `SchemeLoweringTest` (`anR7rsProgramBeginsWithAnImportDeclaration`,
`strictR7rs...`, `eachNameIsImportedFromTheR7rsLibraryThatExportsIt`), the
`RontoLispCliTest.schemeStandard...` cases, and the `standalone:` cases of `scheme-spec.yaml`
with a `standards:` field, all four backends.

## `(scheme inexact)` and how a flonum prints

- **Every procedure is a `%scheme-` helper**, not a template over the CL function: an exact
  argument with an exact answer stays exact (`(sqrt 1/4)` 1/2, `(exp 0)` 1), and a real
  argument whose answer is complex answers the complex.
- A user binding of any of these names wins like `square` (SICP samples define `sqrt` and
  bind `exp` as a variable).
- **The transcendental digits are one set on every backend** (fdlibm, `.kb/transcendentals.md`).
- **`%scheme-print-flonum`** (printer float arm and `number->string`): the CL printer's
  shortest round-trip digits (Schubfach on wasm), laid out with the ECMAScript thresholds --
  positional while the point is at most 21 digits right of the first digit or fewer than 6
  zeros left of it, `<digits>e<exp>` otherwise (`1e21`, `1.5e-7`).
- `-0.0` is read with `Double.parseDouble` (`BigDecimal.doubleValue()` drops the sign);
  `string->number` negates after converting.
- A program that makes no float keeps the `.wasm` near unchanged: the type-test fold drops
  the float arm.

## `(scheme complex)`

**The tower is the runtimes' own: every template is the plain Common Lisp operator, and the
`complex` call is what makes each site complex-aware on the compiled backends.** No new
helper carries logic. `magnitude` is `abs` behind a `complexp` test whose complex arm
reconstructs `(complex (realpart x) (imagpart x))` so the wasm site takes the steered hypot
(the plain abs arm's `_rat_cmp` knows no holder); `angle` is `phase`.

- **Reader/printer**: `#c(r i)` reads through `LispComplex.valueOf` (canonicalization as the
  constructor's on every backend: a RATIONAL zero imaginary part demotes, so `#C(1 0)` IS
  `1`, `#C(1.0 0.0)` stays complex -- R7RS 6.2.4, matching `_ccomplex`). The printer needs no
  arm; the run-time `(read)` has the same `#c(` arm.
- **Transcendentals**: `sqrt`/`log`/`log-base`/`asin`/`acos` sites are complex-aware already.
  `exp`/`sin`/`cos`/`tan`/`atan` have no escape, so their complex arms reconstruct `(complex
  (realpart x) (imagpart x))` INLINE -- that call turns the site's `hasComplexOperand` true
  (`_cu1` / the wasm complex arm). Two-argument `atan` stays real-only (CLHS `atan2`).
- **exact/inexact/exact?/inexact? have complex arms** (per part). Their `(complex ...)` calls
  open the JVM complex gate for every program spelling `exact` or `inexact`; the holder
  travels behind the `_hasComplex` probe (.kb/jvm-complex.md, the holder-presence probe).
- **Deviations (Gauche 0.9.15)**: we print `#C(0.0 2.0)`, Gauche `0.0+2.0i`;
  `(make-rectangular 1 0)` is exact `1` (Gauche `1.0`); `(exact <complex>)` works here,
  Gauche refuses; `(exact? ...)` of an exact complex is `#t` here, `#f` there. Exactness
  otherwise follows the SBCL-pinned Common Lisp tower.
- Pinned by `complex-numbers-read-print-the-tower-and-the-transcendentals` of
  `scheme-spec.yaml` (four backends), `SchemeReaderTest`'s `#c` cases,
  `RontoLispCliStreamsTest.aSchemeTranscendentalWithAComplexAnswerAnswersTheComplexOnEveryPath`,
  and the reference pages (`DocExamplesTest`).

## `(scheme char)` (2026-09-19, `.todo/879`)

**Invariant: every answer is the JDK's Unicode data (Unicode 16 on Java 25), identical on all
four backends by construction.** Common Lisp's classifiers are not: `alpha-char-p` and
`digit-char-p` are ASCII-only on wasm (141,028 alphabetic code points on the interpreter and
the JVM, 52 on wasm), and `upper-case-p`/`lower-case-p` answer "has a mapping", not the
Unicode `Uppercase`/`Lowercase` property (`ǅ` is both there, neither in Unicode; `ª`, `ℂ`,
`𝐀` have no mapping and are cased). `char-upcase`/`char-downcase` ARE uniform
(`.kb/characters-code-points.md`) and are used as is.

- **Tables are generated, not written** (`scheme/SchemeCharacters`, appended by
  `Scheme.runtimeForms`): `Alphabetic`, `Nd` (runs of ten share a range, so `digit-value` is
  `(mod (- code from) 10)`), where `Uppercase`/`Lowercase` disagree with "has a mapping",
  `Case_Ignorable` (final sigma), and `SpecialCasing.txt`'s unconditional multi-character
  uppercase mappings. `White_Space` is spelled by value (stable since Unicode 6.3). ASCII
  answers without a table; the pruner drops unreached tables.
- **Folding** (`char-foldcase`) is the lowercase of the uppercase, except U+0130/U+0131 do not
  fold and Cherokee folds to its capitals. **Full folding** (`string-foldcase`, `-ci`
  comparisons, `#!fold-case`) is the simple folding of each character of the full uppercase,
  except U+0130 -> `i` + U+0307, U+0131 unchanged, U+1E9E -> `ss`. Both rules match
  `CaseFolding.txt` over every code point. Spelled twice -- `SchemeCharacters.foldcase`
  (compile-time `#!fold-case`), `%scheme-char-foldcase` / `%scheme-string-foldcase` -- and
  `SchemeCharactersTest` pins them to each other.
- **`string-upcase` / `string-downcase` are the full mappings** (`"straße"` -> `"STRASSE"`);
  downcase applies Unicode 3.13 `Final_Sigma`, as `String.toLowerCase(Locale.ROOT)`. ASCII
  strings take CL's `string-upcase`/`string-downcase`.
- **Where Gauche 0.9.15 answers differently**: `char-whitespace?` of U+0085; Cherokee (tables
  older than Unicode 8); `string-foldcase` of `ẞ` and `ı`; `#!fold-case` folds ASCII only;
  `string-downcase` of `"1Σ"` is `"1ς"`. The `char-library-follows-unicode` case of
  `scheme-spec.yaml` pins our side.
- **Testing cost**: an interpreter sweep of every code point per property takes minutes, so
  `SchemeCharactersTest` asks only where an answer can go wrong (the first slice, both sides
  of every range bound and every departing mapping): seconds.
- **Speed**: `char-alphabetic?` on non-ASCII is table-lookup fast on the JVM and wasm only with
  bounds addressed by `ash`-style midpoints; reading each bound straight from the string, or a
  generic `floor` midpoint, is far slower.
- A program using no `(scheme char)` name is byte-identical; one calling `read` grows (the
  reader's `#!fold-case` folds through `%scheme-string-foldcase`).

## Promises and streams (`(scheme lazy)`, SICP streams)

- **A promise is a `defstruct`, `%scheme-promise`, around a box `(state . payload)`**: a
  record, so `promise?` is honest and `procedure?` of a promise is `#f`. `%scheme-force` is a
  loop: a `delay-force` result's box
  is copied in and SHARED (R7RS `promise-update!`), so a 100,000-long chain runs in constant
  stack; the box is re-read after the thunk returns (R7RS 4.2.5 reentrancy).
- `(delay e)` lowers to `(%scheme-delay 0 (lambda () e))` (ordinary `lambda` lowering, so a
  loop that delays rebinds per iteration). `(cons-stream a b)` uses identity-compared
  `CORE_DELAY`, so a user `delay` does not capture it. Both are keywords only until shadowed
  (SICP samples bind `delay` as a variable).
- **A stream is `'()` or `(value . promise)`**: `the-empty-stream` is NIL, `stream-null?` is
  `null`. The stream procedures are `%scheme-stream-*` CL helpers, not Scheme source, so a
  user `define` of `apply` cannot reach them. Every walk is a loop (`(stream-ref s 200000)`
  pinned). `stream-head` answers a LIST (MIT).
- The printer writes `#<promise>`; its predicate is what every printing program keeps of the
  record (`.kb/library-defun-pruning.md`, the name exception).

## `parallel-execute` and `test-and-set!` (SICP 3.4)

- **`scheme.lisp` is read with the TARGET's features** (`SchemeLibrary.forms(Features)`).
  Under `#+thread-support` `%scheme-parallel-execute` spawns one `rontolisp:make-thread` per
  thunk and joins all in nested `unwind-protect` (a thunk's error re-signals only after the
  others are joined). Without it (both wasm) the thunks run in argument order: a legal
  interleaving where a serializer's busy-wait never contends -- NOT a call-time signal like
  `bt2:make-thread` (`.kb/threads.md`). Both branches define the same names, so
  `isSchemeFunction` and the pruner read the interpreter's copy.
- `test-and-set!` is a `pred` over ONE `rontolisp:make-mutex` for every cell
  (`%scheme-test-and-set-lock`, dropped with the helper: pinned in `LibraryDefunPrunerTest`).
  "Set" is "car is not `#f`", so `'()` is set. A user `define` wins like `square`.
- Races (64 cores): unserialized `(set! g (+ g 1))` thunks lose updates but never crash, also
  on a captured `let` variable (the JVM's boxed capture); serialized ones were exact on all
  four backends, also with one carrier thread / one processor.

## `eval` (`(scheme eval)`, `(scheme repl)`, the MIT and R5RS environment names)

- **`eval` is a Scheme evaluator over Scheme DATUMS in `scheme.lisp`** (`%scheme-eval`), one
  definition for all four backends: the backends' run-time `eval` (`.kb/eval-runtime.md`)
  evaluates CL core forms. No CL `eval`, so `usesEval` stays off; it needs `fboundp` /
  `symbol-function` / `boundp` / `symbol-value` with a COMPUTED name, which all four backends
  answer; `set` and `(setf (symbol-value ..))` do not exist on any of them.
- **Every environment specifier is the one global environment**, the symbol `#[environment]`
  (`SchemeBuiltins.ENVIRONMENT_NAME`, MIT's spelling; `symbol?` of it is `#t`, a stated
  wart). `(environment sets..)` checks each set against `IMPORTABLE_LIBRARIES` through the
  generated `%scheme-library-p`. `(eval x)` with no environment is accepted, except under
  `--scheme-standard r7rs`.
- **Name resolution order**: program variables (`boundp`, including what `eval` defined),
  then program `fboundp` names (a user `define` wins; `fboundp` is case-sensitive, so `car`
  never answers `CAR`), then builtins via `%scheme-builtin`. A `define` inside `eval` is a
  program global. A keyword is syntax unless a variable of that name is in scope
  (`%scheme-eval-syntax`); a datum's symbols carry their mangled spelling (`=>` is `s%=>`).
- **The run-time table is GENERATED from `SchemeBuiltins`** (`runtimeForms`, keyed by the
  mangled name, spelled once). The interpreter's copy holds every entry; **a compiled
  program's holds only the names it SPELLS** (a symbol anywhere, or a substring of a string
  literal -- `SchemeLibrary.process`): the whole table would tow every helper into a program
  that evals. The same line the compiled CL `eval`'s registry draws.
- **Environments are frames `(alist . loop)`**. A named `let`, a `define`d procedure and a
  `letrec` lambda whose name is only ever CALLED in its body (`%scheme-eval-called-only`)
  get a LOOP frame; a call of it (`%scheme-eval-loop`) continues in `%scheme-eval`'s own `do`
  loop -- no tail-position analysis needed. 100,000 iterations run on all four backends;
  without loop frames a shallow named let exhausted wasm's stack. The interpreter runs this
  evaluator as interpreted Lisp, so `doc/*/scheme/eval.md` loops 10,000 -- still past the
  ~10,000-deep non-tail recursion that overflows the CLI's 16 MiB. Stated deviation: an old
  closure of a name `set!` later keeps jumping to itself. Every other call recurses.
- Errors are Scheme-spelled through `%scheme-error-message` (`Unbound variable: x`, `Not
  supported inside eval: ...` for `define-record-type`, `define-values`, `let-values`,
  `import`, a constructed `cond-expand`). A first-class `values` inside `eval` answers every
  value on all backends (`.kb/multiple-values.md`; the `multiple-values` case).
- Pinned by the three `eval-...` cases of `scheme-spec.yaml` (four backends),
  `SchemeLoweringTest.evalAndEveryEnvironmentSpecifierLowerToTheRunTimeEvaluator`,
  `SchemeBuiltinsTest.theRunTimeTableAnswersEveryProcedureAndConstantByItsMangledName`,
  `LibraryDefunPrunerTest.theSchemeEvaluatorAndItsProcedureTableFollowOnlyAProgramThatEvals`,
  `RontoLispCliStreamsTest.anErrorInsideSchemeEvalIsReportedInSchemeTerms`.

## `(scheme read)` (the current input port, or a port argument)

A reader in `scheme.lisp` over `read-char` on `*standard-input*`, on all four backends -- not
the emitted CL reader (which upcases and knows `#'`, `|...|`, packages). A datum comes back
as what quoted data lowers to (`SchemeLowering.datum`), or `(eq? (read) 'quit)` is false.
Refusals match the front end; an incomplete datum raises a read error (`read-error?`).

- **EOF is a `defstruct` singleton** (`%scheme-eof`): unforgeable, prints `#<eof>` like
  Gauche. `(eof-object)` CALLS `%scheme-eof-object` rather than reading the variable: the
  interpreter loads `scheme.lisp` on the first resolution of one of its FUNCTIONS, so a
  template whose only library reference is a variable is unbound as the first thing a fresh
  evaluator runs -- `SchemeBuiltinsTest.eofObjectWorksAsTheFirstThingAFreshEvaluatorRuns`.
  Reads answer it at end of input instead of signalling, repeatedly.
- **One Lisp-level pushback cell** (a list, so `#|` un-reads two characters), keyed on the
  current `*standard-input*` (a rebind clears it), when no port procedure is used; with ports
  it is the port's. Peek is read + pushback, never CL's `peek-char`, so nothing parks in the
  WASM fd byte pushback but what a malformed UTF-8 sequence leaves, and `read`/`read-line` mix
  freely.
- **`char-ready?` answers `#t` everywhere**: WASM has no non-blocking probe (`listen` is a
  call-time error there), so a probe would split the backends; stated deviation on a terminal
  with nothing typed.
- Stdin is one `read-char` per character (a WASI `fd_read` byte on Preview 1, UTF-8 assembled
  byte-wise). A non-async `--component` program keeps the adapter's blocking stdin path.
- **REPL shares one `BufferedReader`** (`Environment.replInputReader` via
  `LispEvaluator.readReplLine`, read by `RontoLispCli.replWithBufferedReader`): a piped
  session holds no second look-ahead, so a `(read)` inside it sees what was typed next.
- **`#!fold-case` / `#!no-fold-case`**: an R7RS `<directive>` (no datum) folding identifiers
  and character NAMES (never string literals) until the counterpart, through
  `%scheme-string-foldcase` / `SchemeCharacters.foldcase`. Keyed on `*standard-input*` like
  the pushback cell; `SchemeReader` keeps it as an instance field per compile-time read.

## Macros (`SchemeExpander`, `SyntaxRules`; 2026-09-18, `.todo/861`)

**The expander runs BEFORE the lowering, over datums, and outputs datums with no macro left**,
so every pre-scan of the lowering -- the `set!` census, defun-or-variable, a body's internal
definitions, `readBeforeDefinition` -- sees the expanded program. Expanding on demand inside
the lowering would hide macro-made definitions from the scans that run first.

- **Gate**: only a file (or session buffer) spelling `define-syntax`, `let-syntax`,
  `letrec-syntax` or `syntax-error` (also through an import rename) is expanded
  (`SchemeExpander.needed`); a session keeps its expander from then on. Every other program
  lowers byte for byte as without the expander.
- **Hygiene by renaming.** A symbol a template introduces becomes an ALIAS: a fresh
  `LispSymbol` of the same spelling (identity-distinct; `LispSymbol` equality is by name, so
  aliases live in an `IdentityHashMap`) remembering the macro's DEFINITION environment. A
  bound alias becomes a generated variable (`%SCM-V<n>`); a free alias resolving to a keyword
  becomes the identity-compared core symbol (`SchemeLowering.coreSymbol`, one per keyword, so
  `(let ((if list)) (my-or ..))` still gets the real `if`); to a variable, its emitted name;
  free, its spelling. `quote`, `case` data and `quasiquote` literals are stripped back to
  spelling. Literals match by `free-identifier=?`: a user's local `else` does not match a
  macro's `else` literal (Gauche agrees).
- **Every LOCAL of an expanded program is renamed** to a generated variable, the user's too:
  the lowering keeps Scheme spellings for locals, so otherwise a CL binding captures a free
  alias (`(let ((x 'outer)) (let-syntax ((m .. x)) (let ((x 'inner)) (m))))` would answer
  `inner`). Renaming only on detected shadowing would need to know at the binder which macros
  later emit the name. Top-level names keep their spelling (other files reach them); a
  top-level name a TEMPLATE defines is a hidden generated global (Gauche -r7 agrees), so a
  macro's `(define count 0)` does not collide with the user's `count`.
- **The walk mirrors the lowering's scoping** of every binding form. A shape it cannot parse
  goes to the lowering stripped of aliases, which names what is wrong.
- **Positions**: every cons an expansion builds inherits the USE's position (`SourceProvenance`
  and `SchemeReader.inherit`).
- **Errors** are positioned; more than 1,000 nested expansions is "does not terminate".
  Strict R7RS refuses a top-level `define-syntax` over an import, as it does `define`.
- **Library**: `define-syntax`, `let-syntax`, `letrec-syntax`, `syntax-rules`,
  `syntax-error`, `...` and `_` are `(scheme base)` keywords (R7RS 7.1, as Gauche).
- **Stated limits**: `syntax-rules` only; a macro is per FILE (a `load`ed file neither sees
  nor exports macros; a library exports them, "Exported syntax"); a template's names inside a
  TOP-LEVEL `define-record-type` are stripped, not renamed; `eval` knows no macro and refuses
  `define-syntax` by name; an improper use `(m 1 . 2)` is matched rather than refused.
- Pinned by the `syntax-rules-...` / `syntax-definitions-...` cases of `scheme-spec.yaml`
  (four backends, Gauche 0.9.15's output; they also push every earlier case through the
  expander), `SchemeLoweringTest` (`aSyntaxRulesMacroIsExpanded...`,
  `...RenamesEveryLocalVariable`, `aTemplateBinderCaptures...`,
  `aMisusedMacroIsAPositionedError`, `strictR7rsRefusesASyntaxDefinitionOverAnImport`) and
  `SchemeSessionTest.aMacroDefinedAtOnePromptIsExpandedAtTheNext`.

## Exceptions (`guard`, `raise`, `with-exception-handler`; 2026-09-18, `.todo/865`)

**Scheme raises go through a Scheme-level handler stack; built-in errors through
`handler-case`.** `rontolisp::%scheme-handlers` (a `defvar`, bound by `let`) holds, innermost
first, `with-exception-handler` procedures and `guard` catch tags. `raise` /
`raise-continuable` (`%scheme-dispatch`) call the innermost procedure with the outer stack
bound -- where the raise stands -- or `throw` to the innermost guard's tag. A handler
returning from a non-continuable raise raises the secondary error (irritants `()`, as
Gauche). Stack empty: `(error '%scheme-raise :payload obj)`, what an uncaught raise reports
and what a CL `handler-case` around Scheme code catches.

- **`handler-case` catches what a built-in signals**: `%scheme-guard` and
  `%scheme-with-exception-handler` wrap their thunk in one, so the handler runs at that
  boundary, after unwinding, on ALL FOUR backends alike. `handler-bind` would run it at the
  signal point on the interpreter only (`.kb/error-handling.md`, "Deviations").
  `with-exception-handler`'s `handler-case` passes a `%scheme-raise` through (it already went
  past its handler); a guard's catches one.
- **Error objects are conditions.** `error` raises a `%scheme-error` condition (`:report` =
  `%scheme-error-message`); `read` a `%scheme-read-error-condition` whose second parent is
  `reader-error`, so `read-error?` is `(typep x 'reader-error)`. `error-object?` is `(typep x
  'condition)`: a built-in's condition's message is its report, irritants `()`, as Gauche.
- **Every signal is TYPED.** Re-raising a caught condition wraps it in a fresh
  `%scheme-raise`, never `(error c)`: a computed designator bails `conditionNarrowing` and
  bakes the run-time error dispatch plus every condition class in (a sizable slice of both
  backends' output). `handler-bind` + `(error c)` measured larger on both backends.
- **Stated deviations**: a guard's clauses run after the body is left, so an outer handler
  cannot resume a body's `raise-continuable` through a clause-less fall-through (Gauche
  answers 11; the `a-guard-reraise-...` standalone case); a guard answers its body's first
  value; on wasm an out-of-range STRING index is unchecked; `eval` refuses `guard` by name.
- The guard lowering spells its re-raise and helpers as `(raw ..)` (`CORE_RAW`), so a user
  `raise` does not capture it. `SchemeExpander` scopes the guard variable to the clauses.
- A program spelling none of these names is byte-identical (the pruner keys the conditions,
  `.kb/library-defun-pruning.md`); an `error` program pays the condition classes and their
  report lambdas.
- Pinned by the `guard-...`, `with-exception-handler-...`, `read-raises-a-read-error` cases of
  `scheme-spec.yaml` (four backends, Gauche 0.9.15 `-r7` output), its three exception
  `standalone:` cases, `SchemeLoweringTest.aGuardIsABodyThunk...`,
  `RontoLispCliStreamsTest.theSchemeReplCatchesAndReportsRaisedObjects`.

## Parameters (`make-parameter`, `parameterize`; 2026-09-18, `.todo/867`)

**The binding is a special `let` in `scheme.lisp`, never in the lowered program.**
`rontolisp::%scheme-parameterizations` (a `defvar`) is an alist record -> value, innermost
first; `%scheme-parameterize` binds it with `let` around the body thunk, so the restore is
the one `.kb/dynamic-special-variables.md` gives every special on every exit channel, on all
four backends, and a JVM thread sees its own. The `let` stays in the helper because the
interpreter loads `scheme.lisp` lazily, on the first `%scheme-` function, so a `let` in the
lowered program could run before the `defvar`.

- **A parameter object is a closure over a `defstruct` record** (`%scheme-parameter`).
  `parameterize` gets the record by calling the procedure with `%scheme-parameter-token`; only
  a parameter answers a record, anything else is refused by name. A cons record would be
  forgeable by `list`.
- Every value is converted before any is bound; the converter runs on the initial value and
  never on the restore (R7RS 4.2.6).
- `(p v)` is refused by name; Gauche sets.
- `%scheme-parallel-execute` hands the alist to each thread through `make-thread`'s
  bindings, as the sequential wasm run sees it, so a `parallel-execute` program keeps the
  `defvar` even with no parameter.
- `eval` refuses `parameterize` by name; `make-parameter` reaches it through the table.
- Pinned by the two `parameterize-...` cases and the two parameter `standalone:` cases of
  `scheme-spec.yaml` (four backends, Gauche 0.9.15 `-r7` output except the thread line and
  the refusals), `SchemeLoweringTest.parameterizeIsTheParametersAndValuesInOrderAndABodyThunk`.

## `case-lambda` (`(scheme case-lambda)`)

**Desugared, in `SchemeLowering.caseLambda`, into a Scheme `lambda` datum with a rest
formal**, then lowered like any lambda -- nothing new reaches a backend. Keywords are the
identity-compared `CORE_*` symbols and accessors are `raw`, so a user `let`, `car` or `length`
reaches neither; temporaries are `%SCM-` symbols no identifier spells.

- **`definition()` desugars a `(define f (case-lambda ..))` value first**, so the
  defun-or-variable decision sees a syntactic `lambda`. Cached per datum (`caseLambdas`,
  identity), so the pre-scans and the lowering see one set of temporaries.
- No clause accepting the count: `%scheme-case-lambda-arity`, a catchable error object
  (irritants `()`).
- Library `case-lambda` only (in the no-import default; `(import (scheme base))` does not see
  it). `eval` refuses it by name.
- The dynamic dispatch (rest list, `length`, `nth` per call) is slower than a plain `defun`
  on the JVM and far slower on wasm, and its arity refusal pulls `%scheme-error-message`'s
  string-stream machinery. Hence the static pick:

**A direct call picks its clause statically.** A top-level `GlobalFunction` whose value is a
`case-lambda` is lowered by `caseLambdaDefuns` to one `defun` per clause plus the dispatching
`defun f`:

```lisp
(defun |s%%{f 1}| (|n|) (|s%%{f 2}| |n| 0))
(defun |s%%{f 2}| (C1 C2) ...self tail calls: psetq + go...)
(defun |f| (&rest A) (let ((N (length A)))
  (if (= N 1) (|s%%{f 1}| (nth 0 A)) (if (= N 2) (|s%%{f 2}| (nth 0 A) (nth 1 A)) (%scheme-case-lambda-arity A)))))
```

- `GlobalFunction.clauses` carries the clauses, so a library export picks them too. A count
  none accepts calls `f`, which raises at run time -- NOT a lowering error: R7RS says only "it
  is an error", Gauche raises at run time, and a refusal would reject a program whose bad call
  is never reached. A rest clause is `apply`'d by the dispatch.
- `s%%{<name> <n>}` names are unforgeable like `s%%[..]`.
- A clause's self tail call is a jump when its count picks THAT clause (`Target.accepts` asks
  `GlobalFunction.clauseFor`); another count is a direct call of the other clause. A CYCLE
  among two or more clauses would turn jumps into recursion, so `clauses()` keeps the single
  dispatching lambda then (`cl-pp 100000` in the spec case).
- Not split: a `case-lambda` bound by an internal `define`, `letrec` or `let`, and a session's.
- With the static pick, calls run close to a plain `defun` on both backends and the dispatch
  is tree-shaken away once every call is direct.
- Pinned by `case-lambda-takes-the-first-clause-...`, `a-library-exports-a-case-lambda-...`
  and the two `case-lambda` standalone cases of `scheme-spec.yaml` (four backends, Gauche
  0.9.15 `-r7` output but the message), `SchemeLoweringTest.caseLambdaIsOneRestLambda...` and
  `caseLambdaIsImportedFromItsOwnLibraryOnly`.

## Bytevectors (2026-09-18, `.todo/871`)

**A bytevector IS the `(unsigned-byte 8)` pack** (`.kb/packed-integer-vectors.md`): a
`LispIntVector` of width 8 on the interpreter, a width-headed `byte[]` on the JVM, a bare
`(array (mut i8))` on wasm. Nothing reaches a backend that a CL program cannot already send.

- A `#u8(` literal is a self-evaluating datum; each evaluation allocates a fresh vector (the
  pack's literal rule), so storing into a literal is harmless.
- `bytevector?` is `(typep x '(simple-array (unsigned-byte 8) (*)))`; `vector?` is
  `simple-vector-p` (a packed vector is not simple). `utf8->string` is
  `rontolisp:octets-to-string`, lenient (an invalid lead byte decodes to its own code point;
  Gauche makes an incomplete string -- stated deviation).
- **Every constructor and `bytevector-u8-set!` refuse a non-byte by name** (`%scheme-byte`):
  the pack would mask 256 to 0.
- **`bytevector-copy!` is `%scheme-bytevector-copy!`**: CL's `replace` copies forward on every
  backend, overlap or not, so it copies the source region out first when both are one object.
- **The printer's `#u8(` arm and `equal?`'s vector-versus-bytevector test are behind a reader
  feature**, `rontolisp-scheme-bytevectors` (`SchemeLibrary.BYTEVECTORS_FEATURE`), on only when
  the program or its `eval` table can make a bytevector (`makesBytevectors`, a fixpoint over the
  library read WITHOUT the feature -- so `read` counts and a new constructor needs no list
  edit). The interpreter always has it. A bytevector a CL file of the same program makes, with
  no Scheme constructor anywhere, prints as `#(...)`.
- `write-shared` labels a shared bytevector (`(#0=#u8(1 2) #0#)`, as Gauche).
- Binary ports over bytevectors: "Ports".
- Pinned by the `bytevectors` case (Gauche 0.9.15 `-r7` output), the `#u8(` lines of the read,
  fold-case and strict-R7RS cases and the two `bytevector...-non-byte` standalone cases of
  `scheme-spec.yaml` (four backends), `SchemeReaderTest.aBytevector...`,
  `LibraryDefunPrunerTest.thePrintersBytevectorArmFollowsOnlyAProgramThatCanMakeABytevector`.

## Ports

**A port is a `defstruct`, `%scheme-port`, never a bare Common Lisp stream**: the standard
streams are the `t` designator on the compiled backends (not a value,
`.kb/read-load-streams.md`), and the JVM and wasm answer a NEW wrapper for every read of
`*error-output*` (`eq` NIL, `equal` T), so neither can be told apart or compared. CL's stream
predicates do not help either: `input-stream-p` of a string OUTPUT stream is T on all four.

- **Output with a port** binds `*standard-output*` to the port's stream around the unchanged
  printer (`%scheme-display-to` & co), so the printer keeps its one shape.
- **`get-output-string` writes back what it read**: CL's `get-output-stream-string` empties
  the stream, R7RS's does not.
- **The current ports are parameter objects** over three `%scheme-parameter` records
  (`%scheme-port-records`). `%scheme-parameterize` binds the special a port record stands for
  (`%scheme-with-port-streams`), so portless `(display x)` and CL code called from the body
  write there, restored on every exit channel. Only when a port record is bound: a frame per
  level more took the 1,000-deep `prm-count-down` spec case over wasm's stack.
- **Not parameterized, a current port is a cached wrapper of what the special holds now**
  (`%scheme-current-port`, compared with `equal`), so `(eq? (current-output-port)
  (current-output-port))` is `#t` and a CL caller's `with-output-to-string` is honored.
- **The reader's state is the port's** (`%scheme-reading-port`): a peek on one port survives
  reads of another (`the-reader-state-belongs-to-the-port`,
  `the-current-input-port-mixes-with-string-ports`).
- **The whole port section is behind a reader feature**, `rontolisp-scheme-ports`
  (`SchemeLibrary.PORTS_FEATURE`), on for a program calling a function that exists ONLY under
  the feature (`makesPorts`: the difference between the two reads, so a new helper needs no
  list edit). The interpreter always has it. **Trap**: a `#-` definition exists only in the
  variant a program gets, so `LibraryDefunPruner`'s bundled-name sets read
  `SchemeLibrary.everyVariantForms()`; with `forms()` alone the no-port reader's `defvar`s
  stayed as roots in every printing program.
- **Binary ports are over bytevectors and files.** The standard ports are textual, so
  `read-u8` & co with no port are refused (Gauche reads the byte). A port is textual or
  binary, never both (Gauche's string port is both). `u8-ready?` and `char-ready?` answer `#t`.
- Refusals are Scheme-spelled error objects a `guard` catches; Gauche's texts differ.
- Not here: the non-R7RS `with-output-to-string` / `call-with-output-string`.
- A `read-char` loop over stdin is slightly slower on wasm when a port name appears elsewhere
  in the program (the current-port lookup per character).
- Pinned by the seven port cases and `the-current-input-port-mixes-with-string-ports` of
  `scheme-spec.yaml` (four backends; Gauche 0.9.15 `-r7` output but stated deviations and
  messages), the standalone `the-reader-without-ports-reads-standard-input` (the no-port
  reader variant, which the corpus no longer runs on the compiled backends) and
  `writing-to-a-closed-port-is-an-error`,
  `SchemeLoweringTest.aPortArgumentSelectsThePortHelperAndACurrentPortIsAParameterValue`,
  `LibraryDefunPrunerTest.thePortSectionFollowsOnlyAProgramThatUsesAPortProcedure`.

## File ports (`(scheme file)`)

**A file port is the `%scheme-port` record over a CL file stream**, `file` slot set.

- **Each opener spells its own literal `open`** (`:direction` / `:element-type` must be
  literal, `.kb/read-load-streams.md`, "Computed open options"). The path reaches `open` as a
  variable, so a literal path in the Scheme source is read at RUN time
  (`CompileTimePathnameFolder` folds only a literal `with-open-file`).
- **A failed open is a `file-error?` object on every backend**: `(handler-case .. (error ()
  nil))` then `%scheme-file-error-condition`. The clause stays `error`, not `file-error`: a
  `--no-wasi` module stubs `open` to a plain "requires WASI" error (`NoWasiFilesystemStubs`),
  which must still become a `file-error?` object.
- **Closing really closes** (`%scheme-release-port`). `with-input-from-file` /
  `with-output-to-file` (`%scheme-with-file`) parameterize the port inside an
  `unwind-protect` that closes on every exit channel -- Gauche leaves it open and unflushed on
  an escape. It answers the thunk's values, so it is in `SchemeValueCount.PASSING_HELPERS`.
  `call-with-*-file` close on return only, as R7RS says.
- **Binary file ports** go to `read-byte`/`write-byte`. `read-bytevector` from a file builds
  through `%scheme-bytevector`, so the bytevector fixpoint counts it -- computed with the files
  feature only for a program with file ports, so no other program's bytevector decision moves.
- **`file-exists?` is `(probe-file f)`, no helper**, so it pulls in no port machinery.
- **Everything file-specific is behind a third reader feature**, `rontolisp-scheme-files`
  (`SchemeLibrary.FILES_FEATURE`); `makesFiles` is `makesPorts`' derivation one feature up. A
  file program implies the ports feature. The interpreter always reads with all three.
- **An output file port left open keeps its output on all four backends**, as in Gauche
  (`.kb/read-load-streams.md`, "Output left open at the end").
- The spec cases build every path under `/tmp` from `(random 1000000000)`: a literal path would
  not test preopen resolution, and two concurrent corpus runs must not share a file; the wasm
  legs pass `--dir /tmp`.
- Pinned by the `file-ports-write-then-read`, `with-output-to-file-restores-the-current-port`,
  `binary-file-ports` and `a-failed-open-is-a-file-error` cases of `scheme-spec.yaml` (four
  backends; Gauche 0.9.15 `-r7` output but stated deviations),
  `SchemeLoweringTest.importsSelectWhatIsVisible`,
  `LibraryDefunPrunerTest.theFileSectionFollowsOnlyAProgramThatUsesAFileProcedure`.

## Libraries and include (`define-library`, `include`; 2026-09-19, `.todo/882`)

**A library is a file lowering of its own whose top-level names are PRIVATE symbols, and an
export is the binding itself.** `SchemeLibraryLowering.instantiate` lowers the body with a
child `SchemeLowering` (own scope, own expander, prefix `SchemeNames.libraryPrefix`); the
importer's `importSet` puts the exported `Binding` objects into its scope -- so a `defun`
stays a direct call, a record predicate stays fused, a variable is read live. No backend
learns anything; a library procedure has no per-call cost.

- **Private names**: every top-level name a library defines (temporaries included) is
  `s%%(a b)` + the mangled name. No identifier mangles to it, and space/`)` keep `(a b)` apart
  from `(a)`; the same library lowered twice emits the same names. Stated deviation: a
  library's record prints them (`#S(s%%(p)point ...)`).
- **Where a library comes from** (`SchemeLibraries`, shared by nested lowerings): leading
  `define-library` forms of the importing file or buffer (`declareLibraries`, Gauche -r7
  accepts that order), else `a/b.sld`, else `a/b.scm`, relative to the directory of the file
  the lowering STARTED from (`libraries.root()`), for a library's own imports too. A cycle is
  refused with the chain.
- **Lowered once per lowering, in dependency order** (`SchemeLibraries.pending`, drained after
  the `#f` binding). **Once per PROGRAM** across separately lowered files: definitions stay
  bare top-level forms (the backends hoist only a direct child), statements run behind
  `(defvar s%%(a)%SCM-INSTANTIATED nil)` (`instantiationGuarded`). Statements move after the
  definitions; that changes only a library calling a procedure before defining it (an error).
- **Exports** are resolved AFTER the body is lowered; undefined or twice-exported is a
  positioned error. Macros export too ("Exported syntax").
- **Importer rules**: `set!` of an imported library variable is refused (R7RS 5.6.1) in both
  standards; a `define` over a library import wins under `rontolisp` (the library keeps
  calling its own) and is refused under `r7rs` (`libraryImports`, an identity set). Importing
  one name from two libraries is not refused (later wins; Gauche is silent too).
- **Library scope**: no `import` sees everything under `rontolisp` and is refused under `r7rs`
  when the library has a body. A library is always lowered in FILE mode, also from a session.
- **`include` / `include-ci`** (`includes`/`included`): a datum pre-pass BEFORE macro
  expansion, so an included definition or `define-syntax` is seen by every pre-scan and the
  expander's gate; relative to the file the `include` is written in. Scope-blind below the top
  level, like `collectAssigned`. Datums that include nothing are returned as the SAME objects
  (byte-identical lowering). `SchemeExpander.resolve` reads an identity `CORE_*` symbol as its
  keyword. An `include` a macro expands into is refused by name.
- **Positions**: an included or library file gets its own `SchemeReader`, kept in
  `SchemeReader.other` and consulted by `locate`/`inherit`, so errors name the included file.
- **Files** come through `scheme/SchemeFiles`: `eval/SourceLanguage.schemeFiles` adapts the
  loader the reading site already has (compile path, `LoadInliner`, evaluator, REPL working
  directory); `SchemeFiles.NONE` refuses every file. Files are read at lowering time, so a
  compiled program carries their contents.
- Oracle: Gauche 0.9.15 (`gosh -r7 -I.`).
- Pinned by `SchemeLibrariesTest`, the two `libraries-...` standalone cases of
  `scheme-spec.yaml` (four backends, both standards; the `files:` field writes a case's other
  files beside it), `RontoLispCliTest.aSchemeProgramReadsItsLibraryFilesAndIncludesBesideItOnEveryPath`
  (CLI interpreter, `-o`, once-per-program across two loaded files, the REPL) and the reference
  pages' `; file: NAME` blocks (`DocExamplesTest`).

## Exported syntax

**An exported macro is the library expander's own `Macro` -- its `syntax-rules` and its
DEFINITION `Env` -- and a free template identifier reaches the importer's lowering as the
library's `Binding` itself, through a generated identifier.** Nothing about the library is
spelled twice.

- **Export**: `exports` asks `SchemeExpander.exportedMacro(name)` FIRST and wraps it in
  `ImportedSyntax`; it moves through `importSet` like any binding and joins `libraryImports`.
- **Every `Env` knows its lowering** (`Env.host`); at the root, resolution asks THAT host: a
  keyword, an imported macro (`Host.importedMacro`), else -- only for another lowering's root
  -- its top-level binding (`Host.binding`).
- **`Imported` is emitted as `Host.foreign(binding)`**: a fresh `%SCM-L<n>` bound in the
  importer's global scope to the library's `Binding`. A template may `set!` the library's
  variable (Gauche agrees: `variableSymbol` exempts a foreign identifier); it cannot assign a
  library `defun`.
- **Aliases are shared by every expander of one top-level lowering**
  (`SchemeLibraries.aliases`, `SchemeExpander.Aliases`).
- **The gate**: `SchemeExpander.needed` also fires for a name bound to an imported macro. A
  session runs a buffer's top-level `import`s BEFORE expanding it (`sessionImport`), so
  `(import (m)) (m-macro ...)` at one prompt expands.
- **`exit` by another name**: `mayThrowExit` decides by spelling, and a template's `exit`
  arrives as `%SCM-L<n>`, so `foreign` raises `foreignExitOrEval` for `exit` and `eval`.
- **Stated deviation**: a template identifier its library neither defines nor imports resolves
  FREE, by its spelling at the use (Gauche: unbound variable). "Unbound" would need a binding
  kind every `case null` of the lowering learns.
- Pinned by `SchemeLibrariesTest` (`anExportedMacro...`, `importSetsSelectRenameAndPrefixAMacro`,
  `aLibraryReExportsAMacroItImports`, `aStrictR7rsProgramMayNotRedefineAnImportedMacro`,
  `aSessionImportsALibraryThatExportsSyntax`), the `a-library-exports-syntax-hygienically`
  standalone case of `scheme-spec.yaml` (four backends, both standards) and the
  `define-library` reference page (`DocExamplesTest`).

## `cond-expand` (2026-09-19, `.todo/892`)

**Decided while LOWERING, so a clause not taken never reaches a backend, and a feature is
only one every backend shares.** `SchemeFeatures.FEATURES` -- `r7rs exact-closed ieee-float
full-unicode ratios rontolisp`, R7RS appendix B's order -- is what `(features)` answers
(`%scheme-features`, generated into `Scheme.runtimeForms`, spelled once) and what a feature
identifier is tested against; each was checked on all four backends. Never an OS, processor or
backend name: the lowering is shared by the four backends and a compiled program runs
elsewhere; not `exact-complex`. Gauche's ~140 features (`gauche`, `srfi-N`, ...) take `else`.

- **Requirements** (`SchemeFeatures.clause`) compare by NAME. `(library name)` holds when an
  import would find it (`SchemeLibraryLowering.libraryAvailable`): an `IMPORTABLE_LIBRARIES`
  tag, a declared library, or one a `.sld`/`.scm` file declares (declared, not lowered).
  **No clause taken and no `else` is a positioned error** (Gauche: "Unfulfilled
  cond-expand"; R7RS: unspecified).
- **Where it is taken**, four places, one choice:
  1. The leading declarations (`resolveTopLevelCondExpand`): `declareLibraries` and
     `imports` replace it by the taken clause's datums, by SPELLING (no scope exists before
     the imports). So a clause may hold `import`s or `define-library` forms. A session does
     the same per buffer datum.
  2. The `include` pre-pass (`includes`/`included`): every other occurrence becomes
     `(CORE_BEGIN body..)`; `spliceBegins` / `spliceBodyBegins` splice definitions. Quoted
     data and `syntax-rules` are not entered (an unused template is never decided).
  3. `SchemeExpander.headExpanded` / `keywordForm`: one a macro expanded into; requirements
     decided on the STRIPPED form, the body keeps its aliases.
  4. `SchemeLowering.syntax`'s `COND_EXPAND` arm: what no walk enters (a quasiquote's
     unquoted expression).
  As a library declaration, `libraryDeclarations` recurses into the taken clause.
- **`eval`** takes one at run time (`%scheme-eval-cond-expand`, `%scheme-eval-feature-p`),
  no user library. The arm is behind the reader feature `rontolisp-scheme-cond-expand`,
  selected by `SchemeLibrary.process` only for a program that SPELLS `cond-expand` --
  otherwise its machinery would ride along on every `eval` program. Any other `eval` answers
  "Not supported inside eval" for a constructed one.
- A program with a `cond-expand` compiles to exactly the bytes of the program spelling the
  clause it takes.
- Pinned by `SchemeCondExpandTest` (each place, `library`, the errors, a session),
  `SchemeLibraryTest` (the `eval` arm's gate), the `cond-expand-...` case and standalone case
  of `scheme-spec.yaml` (four backends, both standards; Gauche 0.9.15 prints the same but for
  its feature names) and the reference pages (`DocExamplesTest`).

## A session (`SchemeSession`, `SchemeLowering.interact`)

`rontolisp --source-language scheme` with no file; reached through `eval/SourceSession`
(`.kb/source-language.md`). The global `Scope`, the temporaries counter and the `set!` names
outlive each buffer; everything else is per buffer.

- **Every top-level definition is a variable** (`setq`, later calls `funcall` it), never a
  `defun`: no pre-scan can see forms still to be typed. The interpreter's `evalCons` applies
  `(funcall closure ...)` itself (a tail call of its loop), so a session's `(self self ..)`
  runs in constant stack like a file's (`.kb/interpreter-tail-calls.md`).
- **Each definition also emits a trampoline**, `(defun f (&rest a) (apply f a))`: a form typed
  BEFORE `f` existed lowered `(f x)` as a direct call, and the trampoline is what that call
  reaches, reading the variable each call (follows `set!` and redefinition). Without it,
  `ev?`/`od?` typed at two prompts fail with an undefined function.
- **Stateless is not enough**, which is why this is a session and not a lowering mode: a later
  buffer must know `point?` is a record predicate (a raw `T`/`NIL` in an `if` test is always
  true to Scheme) and that `f` is a variable in VALUE position. A record procedure stays a
  direct call and cannot be `define`d over; the whole `define-record-type` may be typed again.
- A procedure's self tail calls stay a loop unless the session has assigned the name SO FAR.
  Stated deviation: a later `set!` does not reach a saved old value, which keeps jumping to
  itself; a form is lowered against the names known when typed.
- `(setq false '|#f|)` is emitted once, by the first buffer. `(import ...)` is accepted
  anywhere at the top level and only ADDS names.
- Echo: `SchemeTopLevel.echoes` is false for a definition, an import and a record type;
  otherwise `SourceSession.echo` skips the unspecified object by VALUE (a syntactic rule on
  the head missed user procedures ending in `display`). Values print through `%scheme-write`
  CALLED on the value (`LispEvaluator.printThrough`) -- never quoted into a form, which the
  package resolver walks and never finishes on a cyclic value. Continuation is "the reader ran
  out of input" (`LispReadException.isEndOfFile`).
- **One typed datum is one `SchemeTopLevel`, however many forms `spliceBegins` turns it
  into**: a top-level `begin` must splice so definitions land at the top level, but
  `interact` groups ONE datum's forms into a single entry (echoing only the last), carried by
  `topLevelGrouped` through `SchemeExpander.topLevel`'s queue. Otherwise `cli/ReplBuffer`'s
  fresh-line per entry prints `(begin (display 3) (display 4) 5)` on three lines.
- **Applying a non-procedure** reports the condition's own text, as file mode and every
  compiled backend do (`The object is not applicable: 3`, built by `%scheme-error-message`).
  Every combination whose operator is not a known procedure goes through
  `%scheme-ensure-procedure` (a `functionp` check), including the procedure arguments of the
  `apply`/`map`/`for-each`/`call/cc`/`dynamic-wind`/`call-with-values` templates: a Scheme
  application applies a PROCEDURE value, never a symbol designator. No REPL-only rewording:
  it would split one failure into two texts ([error-handling.md](error-handling.md),
  "Applying a value that names no function"). Rejected alternative: backends marking reserved
  symbols as non-functions would need a backend that learns Scheme printing (`#f` not `|#f|`),
  for no size win. Pinned by
  `RontoLispCliStreamsTest.theSchemeReplReportsANonProcedureAsAFileDoes` and the `standalone:`
  cases of `scheme-spec.yaml` (four backends).

## The shared REPL loop (`cli/ReplBuffer`), both languages

**A terminal is a person, a pipe is a script.** `RontoLispCli.repl` calls it a terminal when
`in` is `System.in`, `System.console()` is non-null and `isTerminal()` (a test forces either
with `assumeTerminal`). Terminal: a prompt per fresh form, `Error:` on stdout, status 0. Pipe:
no prompt, `Error:` on stderr after flushing stdout, status 1 at end of input when any form
failed -- what a file's uncaught error gives. JLine only on the real system terminal.
`LispExitSignal` escapes the loop and its status wins over an earlier failure. A malformed
buffer is reported whole and nothing in it runs (pinned).

## Destination-driven lowering (`SchemeLowering.lower`)

`lower(expr, Context)` answers an EXPRESSION when the context has no `Destination` and a
STATEMENT when it has one: a statement stores into the destination's result variable or jumps
to a `Target` label. Tail-transparent forms pass the destination down; every other form is a
leaf `(setq R value)`.

- A named `let` is first tried as a PURE loop (`LoopName` binding); any use other than a jump
  sets `escaped` and falls back to the procedure shape. Optimistic re-lowering is exponential
  in the nesting depth of NON-pure named lets, which is small.
- A procedure with a known name is tried as a self loop only when its body mentions a call to
  the name, and keeps the plain shape if no call was a tail call (`fib` carries no `tagbody`).
- **Loop variables are assigned in place (`psetq`)** -- the shape the backends' typed loops
  recognize. **A loop whose body creates a closure rebinds per iteration** from carrier
  variables, `(tagbody L (let ((i C)) ...))`, so each closure captures ITS binding. Detected
  by counting emitted `lambda`s, not by scanning for the word (`(import (prefix ..))` renames
  it).
- **So does a loop jumped to from where a loop variable is shadowed**: a variable keeps its
  Scheme spelling, so an in-place `setq` there would assign the INNER binding and the loop
  never advances. `Target.shadowedFrom` compares bindings and re-lowers in the carrier shape.
- Mutual tail calls among top-level procedures, a body's internal definitions and a
  `letrec`'s lambdas are jumps too ("Tail-call groups"); higher-order ones are ordinary calls.
- **A leaf stores ONE value**, so a leaf that may answer other than one value leaves the loop
  through `(return-from B form)` (B named after R, `%SCM-B<n>` for `%SCM-R<n>`, never from the
  counter, so a discarded attempt renumbers nothing; inner loops share it via `Exit`). Which
  forms: `SchemeValueCount` -- a user procedure call (always a lower-case letter),
  `funcall`/`apply`/`values-list`, a `values` of other than one argument, a helper in
  `PASSING_HELPERS`; a `cl` function answers one value. `PASSING_HELPERS` is pinned against
  `scheme.lisp` by `SchemeValueCountTest` (a new helper handing on a user procedure's values
  fails it until listed). End to end: scheme-spec `multiple-values`.
- Versus a list carrier (`(setq R (multiple-value-list form))`): same or faster on every
  backend, the typed loops survive the `block`, and the list carrier would tow `values-list`
  into the wasm. The `return-from` is a jump on both compiled backends and a stackless
  `BlockReturnSignal` on the interpreter.

## Tail-call groups (`SchemeGroupLowering.declareGroups`; 2026-09-19, `.todo/897`)

**Top-level procedures of a file whose TAIL calls to each other form a cycle are one `defun`,
and every tail call among them is a jump.** `ev?`/`od?`, a state machine, SICP's
`eval`/`apply`/`eval-sequence` run in constant stack on the four backends. A program with no
such cycle lowers byte-identically.

```
(defun G (W C1 .. Cn)
  (let ((R nil))
    (tagbody
     TOP (if (= W 1) (go L1) (if (= W 2) (go L2)))
     L0 (let ((a C1) ..) body0) (go END)
     L1 (let ((b C1) ..) body1) (go END)
     L2 ...
     END)
    R))
(defun ev? (n) (G 0 n))   ; each member: its own defun, W = its index, nil padding
```

- **Members**: `GlobalFunction`s without `case-lambda` clauses (a `case-lambda` keeps its own
  clause loop); a variable procedure is not a member.
- **Finding them**: a cheap scope-blind scan of call heads gives candidate cycles (Tarjan);
  each is lowered once as a PROBE with every member a jump `Target`, and what the lowered
  bodies JUMP to is the real tail-call graph. The probe restores the counter and every memo
  (`Snapshot`), so a non-cycle candidate numbers temporaries exactly as without it (pinned).
- **Shape**: carriers shared by position; every member rebinds its variables from them per
  entry (a closure captures its own entry's binding). A jump to ITSELF goes to its own label;
  to another member sets `W` and goes to `TOP` (`Target.presets`). The group's `defun` stands
  at the first member's `define`.
- **Why this shape (measured)**: `(eql W 1)` in the dispatch is a generic call on the JVM
  (the then evaluator example ran tens of percent slower than with `(= W 1)`); an `if` chain
  under `TOP` (no `(go END)`) was slower still on the JVM; a stub per member setting `W` cost
  wasm a dispatch round per jump. The final shape costs a little over plain defuns, worst on a
  trivial body in a tight loop. A tail `go` is not thrown on the interpreter
  (`.kb/do-return-block.md`).
- `ev?`/`od?` run to 1,000,000 on all four backends with default stacks.
- **Not members** (ordinary calls): a tail call through a procedure VALUE (an argument, a
  `lambda` in a variable, `apply`), and every definition typed at the REPL. Such calls are
  proper anyway: wasm `return_call` (`.kb/wasm-tail-calls.md`), the interpreter's `evalCons`
  loop (`.kb/interpreter-tail-calls.md`), the JVM's value-tail bounce (`JvmTailBounce`,
  [jvm-tail-bounce.md](jvm-tail-bounce.md); a call the Lisp-2 rewrite lowers to a fresh
  funcall cons keeps a real frame).
- **Not a trampoline.** A hand-written general trampoline (a tail call answers a bounce, every
  non-tail call site drives them) measured markedly slower on the JVM and wasm and slower by a
  wide multiple on the interpreter, shallow calls included -- rejected
  on every backend. wasm has `return_call`; the interpreter's `eval` loop
  (`.kb/interpreter-tail-calls.md`) is faster than the recursion it replaced; a larger stack
  for compiled `main` is `.kb/interpreter-stack.md`. On the JVM a trampoline exists ONLY on the
  value-tail edges (above), so every proved-direct call keeps its `invokestatic` and a class
  whose tails are all direct compiles byte-identically to the pre-trampoline emitter (pinned by
  `JvmLispCompilerTest.theValueTailTrampolineIsEmittedOnlyWhereATailLeavesThroughAValue`).

Pinned by `SchemeLoweringTest.topLevelProceduresWhoseTailCallsFormACycleAreOneGroupEachEntersAtItsLabel`,
`#aCycleThroughANonTailCallIsNoGroupAndNumbersNothing`, `#anAssignedOrRedefinedProcedureIsNoMember`
and the `top-level-procedures-whose-tail-calls-cycle-run-in-constant-stack` case of
`scheme-spec.yaml` (four backends; Gauche 0.9.15 `-r7` prints the same).

### Internal groups (`SchemeGroupLowering.internalGroups`)

**The same probe runs on every body and every `letrec`**: internal `define`s (and `letrec`
bindings) of a syntactic `lambda` whose tail calls cycle are one group, a `lambda` in a fresh
variable of the body's `let`; each member's variable gets a `lambda` entering it:

```
(let ((ev? nil) (od? nil) (G nil))
  (setq G (lambda (W C1) (let ((R nil)) (tagbody TOP .. L0 .. L1 .. END) R)))
  (setq ev? (lambda (n) (funcall G 0 n)))
  (setq od? (lambda (n) (funcall G 1 n)))
  ..)
```

- **Members**: never `set!` (by spelling), bound once in the body, not also bound by a
  `define-values`, no `case-lambda`. A member used as a value is its entry `lambda` -- no
  escape rule needed. A jump to a member whose `define` has not run yet runs it anyway (R7RS:
  an error).
- No SICP or `examples/scheme` file has such a cycle (premise measured; built anyway: R7RS
  requires proper tail calls, and a local state machine is a normal shape).
- `(parity 1000000)` over internal `ev?`/`od?` overflowed on all four backends before; `#t`
  now. On the compiled backends internal groups run FASTER than the closures they replace
  (those were `funcall`s through `%scheme-ensure-procedure`).
- **Size**: the first indirect call of an ARITY (`G` takes one more than the widest member)
  pulls every dispatchable function of that arity (`_invoke_N`, `.kb/core-representation.md`).

Pinned by `SchemeLoweringTest.internalProceduresWhoseTailCallsFormACycleAreOneGroupLambdaEachMemberCallsIt`,
`#internalProceduresWithNoTailCycleLowerAsBeforeAndNumberNothing` and the
`internal-procedures-whose-tail-calls-cycle-run-in-constant-stack` case of `scheme-spec.yaml`
(four backends; Gauche 0.9.15 `-r7` prints the same).

## Traps

- **A helper whose tail is a CL operator with a second value answers it too**: `intern`,
  `floor`, `gethash` and the like need a `(values ...)` wrap
  (`SchemeBuiltinsTest.stringToSymbolAnswersOneValue`).
- **`(setq false '|#f|)` is emitted unconditionally**, first: the helpers read the variable,
  and the interpreter's lazy load is keyed on FUNCTION resolution.
- A template parameter used more than once binds a non-atomic argument to a temporary;
  substitution never touches the operator position or a `quote`/`function` form. No parameter
  may be named `t`/`nil` (the table is read by `LispReader`; `SchemeBuiltinsTest` pins it).
- `#'apply` is not a function value on the compile path, and the comparison function values
  are BINARY: first-class `apply` spreads through `%scheme-spread`, first-class
  `<`/`char<?`/`string<?` walk `%scheme-chain`.
- Generated temporaries are UPPERCASE `%SCM-<kind><n>`, which no escaped user identifier can
  spell; the counter is per file, so emission is deterministic.
- A syntax error is a positioned `LispReadException` on BOTH paths: `SchemeReader` keeps its
  own cons -> offset map, because `SourceProvenance` records on the compile path only.
  Lowered conses `inherit` their datum's position (`.kb/source-positions.md`).
- **The interpreter loads `scheme.lisp` through `evalResolved`, not bare `eval(form,
  globalEnv)`**: `%scheme-error-message` rebinds `*standard-output*`, and only a form whose
  special bindings were registered (`SpecialVarCollector.collectForm`) binds it dynamically
  for its callees. Any other lazily loaded library that rebinds a special needs the same.
- The front end changes no Common Lisp program's output.
- Parallel `let` (`.kb/parallel-let.md`) and `(+)`/`(*)` were fixed in the backends, not
  worked around here.

## Measurements

**`#f` representation.** An inline quoted `|#f|` costs a lookup per `if` test on wasm (3x on a
test-heavy loop); a global holding the symbol (chosen) costs nothing over a native NIL test on
either backend. A global holding a `defstruct` singleton buys unforgeability the name escaping
already gives, for extra bytes and quoted data built at run time -- not taken.

**Loop shape.** In-place `psetq` carriers (chosen) match the CL `do` reference on wasm and the
JVM; the per-iteration `let` from carriers is kept only under a closure; variables pre-bound to
nil and copied in at the label lose the integer typing of the loop variables and are far slower
on wasm.

**Tail-call depth that is NOT a loop**, default stacks: a tail call through a procedure VALUE,
`(define (g self n) (if (= n 0) 'done (self self (- n 1))))`, passes 1,000,000 on every backend
(JVM: value-tail bounce, compiled `main` on a 16 MiB worker, `.kb/interpreter-stack.md`; wasm and
the component: `return_call`, `.kb/wasm-tail-calls.md`; interpreter: the loop in `eval`,
`.kb/interpreter-tail-calls.md`); without those each overflowed at a few to ten thousand frames.
Top-level and internal / `letrec` `ev?`/`od?` pairs are unbounded through tail-call groups.

**Size.** `display` of a string, character or integer LITERAL lowers to `write-string` /
`write-char` / `princ`, so hello-world is barely more than the runtime. The generic printer
(`%scheme-print`, `%scheme-print-flonum` its largest defun) is a sizable slice of class and
wasm; it writes a symbol's spelling character by character, because a `(coerce list 'string)`
there towed the string machinery on top. A prelude entry must be rooted by live forms only, or
DEAD `scheme.lisp` helpers drag in the JVM eval runtime (`.kb/library-defun-pruning.md`,
synthesized-call entries).

**Cycles in `write`/`display`** (R7RS: must terminate; label what a cycle closes on,
`#0=(1 2 . #0#)`). An eq hash table of seen nodes costs a large slice of extra code on wasm and,
since wasm has no identity hash, puts every aggregate key in one bucket: quadratic. Chosen: walk
the datum as the tree `write` prints -- never more work than the printing -- and call it
cycle-free when the walk ends; give up on a cdr chain meeting itself (Brent) or car/element
nesting past 1,000, and only then run `%scheme-mark-cycles` over an alist (quadratic, but only for
a cyclic or over-deep datum). Little code over no check, about the no-check speed on the JVM and
wasm. Brent's step uses `(+ power power)` over `(* power 2)` for code size.

**`write-shared` labels every shared node, not only cycles**: `%scheme-write-shared` counts
occurrences in one depth-first walk (a revisit of an open or closed node counts without
recursing, so it ends on a cycle) and reuses the `(entries . next-number)` label shape. Only the
outermost shared node takes a label, as R7RS's own example does: `(write-shared (list x x))` is
`(#0=(1 2) #0#)`. `write` and `display` are untouched.

## Not here yet (each its own follow-up)

The other libraries, re-entrant continuations. Each is refused by name where it can be.

## The SICP sample corpus harness (`.todo/828`)

`SicpCorpusE2eTest` (opt-in: `-Drontolisp.sicp=<unpacked sicp.zip>`) runs every sample in file
mode on all four backends against the interpreter, gated by the checked-in
`src/test/resources/sicp-manifest.tsv` (category + expected outcome per file; the static
category rule lives in the test, which fails on any drift from the manifest instead of
silently re-gating).

Corpus (2026-09-18, `sicp.zip` sha256
`476c0904386f02db5cebfa8a11410e13e0d3fb92da513c376e50c6a16873d8fe`, 1,586 `.scm`): `scheme`
1,181, `concurrent` 19, `fragment` 286, `embedded-query` 52, `embedded-amb` 27, `embedded-lazy`
4, `js-import` 13, `unreadable` 4. Interpreter exits 0 on 1,347 (every `scheme`/`concurrent` file
but two that behave as the book says: `chapter1/section1/subsection6/26.scm` never terminates
under applicative order, `chapter3/section3/subsection5/05_set_value_example_2.scm` ends in
`(error "Contradiction" ...)`). File mode and REPL mode agree on every file. Of the 1,347, the
JVM, wasm Preview 1 AND the component print byte-identical stdout on 1,333; the rest are 2
`scheme` timing prints (`(runtime)`, matched modulo the seconds) and 12 `fragment`s the compile
backends refuse (a global VARIABLE nothing defines is a compile error where the interpreter only
fails if the path runs -- classed as fragments by decision). The
`chapter1/section1/subsection8/02.scm` transcendental digits print identically on wasm (fdlibm):
no tolerance. The `; expected:` annotations (450 files) are a positive check only: 158 agree with
the REPL echo, no disagreement is a wrong value (another expression named, a file ending in a
definition, or stale book digits -- `02_sine_example.scm`, IEEE answers `0.9999996073961978`).

`embedded-*` stay excluded because the corpus does not ship a complete evaluator: the amb
evaluator lacks its `(amb? exp)` dispatch clause and `define-variable!`; the lazy evaluator lacks
its `eval` dispatch, `eval-sequence`, `eval-definition`, the expression-syntax layer AND
`define-variable!` (no corpus file defines `self-evaluating?`, `variable?`, `quoted?`,
`cond->if` or `analyze-quoted` either); the query system lacks its whole syntax layer
(`assertion-to-be-added?`, `query-syntax-process`, ...). No harness-written evaluator fills the
gap: that glue would reproduce the book's evaluator, so none is checked in (decision 2026-11-26).

## Tests

`SchemeSpecE2eTest` over `scheme-spec.yaml` (one case per table row and per procedure group,
all four backends in `./mvnw test`; the wasm legs need `wasmtime` on `PATH`),
`SchemeLoweringTest` (the table as emitted forms), `SchemeSessionTest` (what a session emits,
when a buffer is complete), `SchemeReaderTest`, `SchemeNamesTest`, `SchemeBuiltinsTest` (every
`:function` evaluates, every helper a template names exists), `SchemeLibrariesTest`
(`define-library` / `include`), `SchemeCondExpandTest`, `SchemeLibraryTest` (which programs get
the printer's vertical-line arm and `eval`'s `cond-expand` arm), `RontoLispCliTest`
(`aSchemeFileIsPickedByItsExtension`, `aCommonLispProgramLoadsASchemeFile`, the
`theSchemeRepl...` transcripts, the first of which replays its input as a FILE and compares,
`aPipedReplWritesNoPromptForEitherLanguage`), `RontoLispCliStreamsTest` -- the ones that read
standard error -- (`aSchemeSyntaxErrorNamesItsPositionOnEveryPath`,
`aSchemeProgramIsRefusedByTheScalarBackend`, `anUncaughtSchemeErrorReportsItsMessageAndIrritants`,
`aSchemeTranscendentalWithAComplexAnswerIsRefusedByName`,
`anErrorInsideSchemeEvalIsReportedInSchemeTerms`, the `theSchemeRepl...` transcripts that report
an error, `aCyclicValueIsEchoedWithoutKillingTheSession`, `aTerminalReplPromptsOncePerFreshForm`,
`aPipedReplReportsFailuresOnStandardErrorAndEndsNonZero`,
`exitEndsTheSessionWithItsStatusInEitherLanguage`),
`SchemeSpecE2eTest.exitEndsTheProcessWithItsStatusOnEveryBackend` (one program per status, each
asserting the `after` thunk ran; the JVM leg in a child process, since `exit` there is
`System.exit`; plus `emergency-exit` legs asserting it did not), `DocExamplesTest` (a
```` ```scheme ```` fence is a whole program whose stdout is asserted; one with a `; =>` runs
through a REPL session and each annotation is the form's echo; a whole-program block runs through
`PlaygroundRepl.run`, what the site's Run cell runs), `PlaygroundReplTest` (the browser's REPL and
cells in Scheme), `SchemeReferenceTest` (the reference, below).

## The reference (`doc/<lang>/scheme/reference/`, `.todo/860`)

One page per name `Scheme.providedNames()` answers -- every `SchemeBuiltins` entry and constant
and every `SchemeLowering.syntaxNames()` keyword -- under one `_catalog.yaml` (`label: Scheme`, so
a search hit on `car` says which language's page it is). A category is the library the name is
REALLY exported from (Gauche 0.9.15's `module-exports` is the oracle): `exact->inexact` /
`inexact->exact` under `(scheme r5rs)`, the `read-char` family under `(scheme base)`; the
keywords of `(scheme base)` (and `import`, which no library exports) are the `Syntax` table,
`delay`/`delay-force` sit with `(scheme lazy)` and `cons-stream` with the SICP names. Table pages
are `syntax.md` and `library-<name>.md`, never a bare library name: `read`, `write` and `eval` are
procedure slugs in the same directory (docgen refuses a detail page at an index page's path).

`SchemeReferenceTest` pins it: the catalog's names equal `Scheme.providedNames()` in both trees
(a builtin added without a page fails there), the trees list the same entries in the same order,
and every table row's example is on its detail page -- with its result as the `; =>` annotation
when the result is code -- inside a ```` ```scheme ```` block `DocExamplesTest` checks. **A new
Scheme builtin, constant or keyword needs a detail page, a catalog entry and a table row in both
trees.** A detail page states behavior and every deviation in Scheme terms; how a name is lowered
stays here.
