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

- `scheme` depends on the AST types and `reader` only: `SchemeReader` (text -> datums, its
  own case-sensitive reader), `SchemeExpander` + `SyntaxRules` (macros, datums -> datums,
  "Macros" below), `SchemeLowering` (datums -> core forms), `SchemeBuiltins`
  (the procedure table), `SchemeNames` (identifier escaping), `SchemeLibraries` +
  `SchemeFiles` (`define-library` and `include`, "Libraries and include" below),
  `SchemeFeatures` (`cond-expand`'s feature list and clause choice, "`cond-expand`" below),
  `Scheme` (the facade).
- Reached ONLY through the seam: `eval/SourceLanguage.SCHEME`, picked for `.scm` or by
  `--source-language scheme` (`.kb/source-language.md`). Per FILE, so a Common Lisp file
  may `(load "lib.scm")` and call its procedures as `(|name| ...)`.
- The run-time half is Common Lisp source, `eval/scheme.lisp`, handled the `UrlLibrary`
  way by `eval/SchemeLibrary`: the interpreter loads it on the first resolution of a
  `rontolisp::%scheme-` FUNCTION, `CompileFrontend.expand` splices it innermost (beside
  `TokenizersLibrary`, INSIDE the prelude whose string comparisons it uses) and
  `LibraryDefunPruner` drops what stays unreachable. The playground's chain has the splice
  too; the playground reads Scheme when its page picks it, and the doc site's
  ```` ```scheme ```` fences are Run cells (`.kb/source-language.md`, "The browser"). Some
  definitions are GENERATED from the front end's tables and appended to the source's
  forms (`Scheme.runtimeForms`): `eval`'s procedure table and `environment`'s library
  predicate ("`eval`" below), the `(scheme char)` tables, and the feature list
  ("`cond-expand`").
- A whole FILE is lowered at once: defun-or-variable is decided by a pre-scan. A REPL has
  no whole program to scan and lowers through a session instead ("A session" below).

## The lowering table

| Scheme | lowers to | why |
|---|---|---|
| identifier `foo` | symbol `foo`, verbatim | canonical names are upcased, so a name with an ASCII lowercase letter can never hit a `LispNames` case label |
| `CAR`, `X`, `T`, `+` (no lowercase), `a:b`, `&rest`, `s%...`, `#f` | escaped: `s%` + name, `%`->`%%`, `:`->`%c` | `(defun \|CAR\| ...)` REPLACES the built-in; `a:b` is "symbol b of package a" to the resolver ("No such package: a"); `&rest` is a lambda-list keyword; the prefix and `#f` keep the map injective and the false value unforgeable. The rule is spelled twice (`SchemeNames.mangle`, `%scheme-needs-escape` in `scheme.lisp`) -- change both |
| `'()` | `NIL` | `&rest` lists, `apply` and every list primitive end in it |
| `#t` | `T` | |
| `\|foo bar\|`, `\|\|`, `\|#t\|` | an identifier of that spelling, mangled like any other (`\|#f\|` -> `s%#f`) | "Vertical-line identifiers and the infinities" below; the reader's booleans are compared by IDENTITY, so `\|#t\|` is no boolean |
| `+inf.0` `-inf.0` `+nan.0` `-nan.0` | a flonum literal | every backend already emits a non-finite double constant and prints it |
| `#f` | the VALUE of `rontolisp::%scheme-false`, the symbol `\|#f\|`, bound by the first form of every lowered file | distinct from NIL; a symbol so quoted data, `case` and `equal?` need nothing special |
| the unspecified value: an `effect` builtin, `set!`, the missing arm of `if`/`when`/`unless`, a `cond`/`case` with no clause taken, `(begin)` | the VALUE of `rontolisp::%scheme-unspecified`, the symbol `\|#!unspecific\|`, bound in the same first `setq`; `(progn effect U)` -- but the raw effect / `NIL` where the value is DISCARDED (`Context.discarded`: a body form before the last, a file's top-level form) | ONE object, so a REPL can skip it by value (`(define (g) (display "a"))` echoed `"a"`); not `NIL` (`(list (if #f #f))` has length 1) and true in a test. The spelling is escaped by `mangle` like `#f` and excluded by `symbol?`. Cost (2026-09-17): +93 B class / +26 B wasm per program (`hello`), nothing measurable on a 100M-iteration `when` loop. A missing arm is spelled `CORE_UNSPECIFIED` in desugarings |
| `exit`, `emergency-exit` (`(scheme process-context)`, merged into the no-import default) | `exit` throws its code to `rontolisp::%scheme-exit-tag`; every file top-level form runs inside a `catch` for it that ends the process through `%scheme-exit` with the caught code ("`exit` runs ..." below). `emergency-exit` calls `%scheme-exit` directly: finish both output streams, then `%host-exit` with `#t`/none 0, `#f` 1, an integer's low 8 bits | the `uiop:quit` primitive (`.kb/uiop.md`); `exit` runs the outstanding `dynamic-wind` afters on its way out, only `emergency-exit` ends the process where the call stands |
| `(if c a b)` | `(if (eq c false) b a)`; a predicate fuses: `(if (pair? x) ..)` -> `(if (consp x) ..)`, `and`/`or`/`not` compose | `SchemeBuiltins.Result`: `pred` (T/NIL) and `or-false` (value or NIL: `memq`, `assq`, `member`) fuse in a test and convert anywhere else -- `(if raw t false)` / `(or raw false)` |
| top-level `(define x v)` | top-level `(setq x v)` | NEVER `defvar`: that makes the name special and a `let` of it leaks into callees (2 instead of 1) |
| top-level procedure defined ONCE by a `lambda`, never `set!`, and -- when it shadows an import -- never read before that definition | `defun`, called directly | keeps the direct call and the tree shaker. `set!` is collected by name, blind to scope: over-approximating only costs the direct call |
| any other procedure binding | variable + `funcall`; a procedure name in value position -> `#'name` | Lisp-1 over Lisp-2 (`.kb/lisp2-namespaces.md`). A known procedure's first-class value is its `:function` form in `SchemeBuiltins` |
| a name this file does not define | call position: a direct call; value position: a variable | what lets a Common Lisp file and a Scheme file call each other |
| `(lambda args ..)`, `(lambda (a . r) ..)` | `&rest` | the dotted formals are parsed here: `LambdaLists.parse` reads through `toList()` and DROPS an improper tail |
| named `let` / `do` whose name is only tail-called; a self tail call | `tagbody`/`go`, every `go` in STATEMENT position | destination-driven lowering (below) |
| named `let` whose name escapes; `letrec`; internal `define` | bind to nil, `setq` a `lambda`, `funcall` | the shape `labels` itself lowers to (`.kb/flet-labels.md`), so `labels` would buy no direct call |
| `cond` `case` `and` `or` `when` `unless` `do` | desugared to `if`/`let`/`begin`/named `let` first | spelled with identity-compared `CORE_*` symbols: `(define (f if) (or if 1))` still gets the real `if` |
| `call/cc` | `block` + a closure doing `return-from` (`%scheme-call/cc`) | escape-only, one-shot; crosses lambdas through `CrossLambdaExitLowering` |
| `(guard (v clause..) body..)` | `(%scheme-guard (lambda () body..) (lambda (C) (let ((v C)) (cond clause.. (else (%scheme-raise C))))))` | a Scheme-level handler stack plus `handler-case`, "Exceptions" below |
| `dynamic-wind` | `before`, then `unwind-protect` | the exit half runs on every exit channel; re-entry does not exist |
| `(case-lambda (formals body..)..)` | `(lambda (&rest A) (let ((N (length A))) (if (= N 1) (let ((x (nth 0 A))) body..) .. (%scheme-case-lambda-arity A))))`, spelled as a Scheme `lambda` datum with `raw` parts; a top-level procedure defined once by one: a `defun` per clause (`s%%{f 1}`, ..) that a direct call picks by its count, and `f` the dispatch | "`case-lambda`" below |
| `(parameterize ((p v)..) body..)` | `(%scheme-parameterize (list p v ..) (lambda () body..))`; no binding: `(let () body..)` | a special `let` inside the helper, "Parameters" below |
| `(call-with-values (lambda () ..) (lambda (a b) ..))`, `let-values`, `define-values` | `multiple-value-bind` | the syntactic tier (`.kb/multiple-values.md`). Any other shape: `(apply consumer (multiple-value-list (funcall producer)))`. A loop's leaf that may answer other than one value leaves through `(return-from B ..)` ("Destination-driven lowering") -- a `(setq R (values a b))` keeps one value, on every backend |
| top-level `define-record-type` | `defstruct` with `(:conc-name nil)`, each slot NAMED after its accessor, a BOA constructor; the modifier a `defun` over `(setf (accessor r) v)` answering the unspecified object | `defstruct` is what registers the instance layout on every backend (`.kb/defstruct.md`); the accessor IS the generated one, no wrapper call. The predicate answers T/NIL and is a `pred` (`GlobalPredicate`) |
| `define-record-type` in a body | the same `defstruct`, HOISTED ahead of the top-level form holding it, named `s%%[<enclosing definition> <type>]`; the body binds its names to the hoisted procedures, no variable | "Internal record types" below |
| `quasiquote` | `cons`/`append`/`(coerce .. 'vector)`, constant parts quoted | depth-counted per R7RS: the innermost unquote of a nested template IS evaluated |
| `(define-library (a b) ...)`, `(import (a b))` | the library body lowered once, before the importer's forms, with private top-level names (`s%%(a b)name`); an export is the library's binding, so a call stays direct | "Libraries and include" below |
| `(include "f")`, `(include-ci "f")` | `(begin <f's datums>)`, spliced before macro expansion | the same section |
| `(cond-expand (req body..)..)` | `(begin <the taken clause's body>)`, decided while lowering, spliced before macro expansion; at the leading declarations its datums replace it | "`cond-expand`" below |
| `define-syntax` / `let-syntax` / `letrec-syntax` with `syntax-rules` | nothing: expanded away before the lowering (`SchemeExpander`); `let-syntax`'s body is `(let () body)` | hygiene by renaming, "Macros" below |
| `#u8(...)`, `bytevector`, `make-bytevector`, `bytevector-append`, `string->utf8` | the `(unsigned-byte 8)` pack (`.kb/packed-integer-vectors.md`): the literal is an 8-bit `LispIntVector` datum, self-evaluating; the constructors are `%scheme-` helpers over `make-array :element-type '(unsigned-byte 8)` / `rontolisp:string-to-octets` | "Bytevectors" below |
| a port procedure (a `(scheme file)` one included); the optional port argument of `display`, `read-char`, ...; `(current-output-port)` | a `%scheme-` helper over a `%scheme-port` record (`(display x p)` -> `(%scheme-display-to x p)`, which binds `*standard-output*` to the port's stream around the printer); with no port argument the template is what it always was. A current port's VALUE is `(%scheme-port-parameter 1)`, a parameter object | the standard streams are the `t` designator on the compiled backends, not values ("Ports" below) |
| `(eval datum env)`, `(interaction-environment)`, `(scheme-report-environment 5)`, `(environment sets..)`, `user-initial-environment`, `system-global-environment` | `(%scheme-eval-in datum '\|#[environment]\|)`: a Scheme evaluator over DATUMS in `scheme.lisp`; every specifier is the one global environment, a quoted symbol | the lowering is not inside a compiled program and the backends' run-time `eval` evaluates core forms, so one evaluator serves all four ("`eval`" below) |

`symbol?` excludes `T`, `NIL` and the false value; `boolean?` is `#t`/`#f` only; `vector?`
is `simple-vector-p`, so it excludes strings and bytevectors (`vectorp` does not); `integer?` accepts `2.0`; `max`/`min` are inexact
when any argument is; `equal?` is its own helper (recurses into vectors, `eqv?` on
records; CL's `equal` compares a general vector by identity and an instance slot-wise).

## Where a Common Lisp function answers differently (2026-09-18, `.todo/862`)

Found writing the reference, each checked against Gauche 0.9.15 and pinned by the
`numbers-lists-and-records-at-their-r7rs-edges` case (plus `list-copy` in
`pairs-and-lists`), two `standalone:` error cases, and
`SchemeBuiltinsTest.anArgumentR7rsMakesAnErrorIsRefusedByName`:

- **`quotient`/`truncate-quotient`/`floor-quotient`, `odd?`/`even?`, `gcd`/`lcm`** keep
  the inline CL operation behind `(integerp ..)` tests and send anything else to a helper
  that refuses a non-integer by name (`odd?: not an integer: 1.5`; CL's `oddp` answered
  for `1.5`) and makes the answer inexact when an argument is (`(quotient 7.0 2)` 3.0,
  `(gcd 2.0 4)` 2.0; CL's `truncate` answers an integer, `gcd` refuses a float).
- `rational?` excludes the infinities and NaN (`realp` did not), `integer?` too;
  `number->string` applies the radix to a ratio's two parts (`princ-to-string` ignored it).
- `list-copy` keeps a dotted tail and answers a non-pair itself; `list?` is Floyd's
  cycle check (the walk never returned on a circular list).
- `reduce` is SRFI-1's `(f elem acc)` (CL's `reduce` is `(f acc elem)`: `(reduce - 0
  '(1 2 3 4))` was -8, not 2); `fold-left`/`fold-right` take several lists (MIT).
- `string`/`list->string` refuse a non-character (`coerce` built `"a1"`);
  `stream-car`/`stream-cdr` refuse a non-pair (`car` of `'()` answered `()`).
- `case` compares with `eql`, and two equal string LITERALS are one object on every
  backend (CLHS 3.2.4.4 coalescing, `.kb/hash-tables.md`), so `(case "a" (("a") ..))`
  matches -- R7RS 6.1 leaves `(eqv? "a" "a")` unspecified, so that is conforming and
  unchanged; a computed key, `(string #\a)`, does not match, as in Gauche.
- **Never `float` an exact value in a helper**: the flonum constructor stays reachable
  in a program that makes no flonum, and wasm's type-test fold
  (`.kb/wasm-ref-type-fold.md`) can then no longer drop the flonum arms of the
  arithmetic and the printer. `%scheme-inexact-like` adds `(- x x)` of the inexact
  argument instead. Measured on `(display (quotient 17 5))`: 8,599 B of wasm before,
  34,537 with `float`, 25,490 without. The rest is the named refusal:
  `%scheme-error-message`'s string stream (a helper calling `(error "text")` alone
  cost +189 B). A program that already has it pays nearly nothing -- one with a
  `lambda` passed to `map`, `filter odd?` and a recursive `quotient`: 33,026 -> 33,657 B
  of wasm, 87,992 -> 90,697 B of class. A 3M-iteration `quotient`/`odd?`/`gcd` loop runs
  in the same time on the JVM and wasm (single runs, within noise).
- `SicpCorpusE2eTest` over the pinned corpus after the change: 5,262 legs, 0 failures,
  17 skipped, the manifest unchanged (no sample calls `reduce`, `fold-left` or
  `fold-right`).
- Found beside it, not fixed here: Common Lisp's own `copy-list` drops a dotted tail on
  the interpreter and fails on the compiled backends, and `coerce` of a list holding a
  non-character to `string` builds a string -- both fixed 2026-09-18
  ([copy-list-runtime.md](copy-list-runtime.md)).

## `exit` runs the outstanding `dynamic-wind` afters (2026-09-18, `.todo/845`)

`exit` lowers to `(throw 'rontolisp::%scheme-exit-tag code)` -- the template and the
`:function` (so an `eval`'d exit throws too) -- and every FILE top-level form runs
inside its catch:

```lisp
(let ((%done (list nil)))
  (let ((%code (catch 'rontolisp::%scheme-exit-tag (progn <form> %done))))
    (if (eq %code %done) nil (rontolisp::%scheme-exit %code))))
```

The throw unwinds through the `unwind-protect`s `%scheme-dynamic-wind` is made of, so
the afters run on every backend (the tag rides the same channel as user
`catch`/`throw`, which is what turns the wasm landing pads on), and the catch ends the
process through `%scheme-exit` with the thrown code. The fresh cell tells a throw from
normal completion whatever the code is: `(exit '())` throws NIL, which a literal marker
could not tell apart.

- **One catch per form, not per file**: the backends only hoist a `defun`/`defstruct`
  that is a direct child of the program, so those stay bare (defining never throws -- a
  body only runs inside some value form's extent); the leading setqs bind quoted values
  and stay bare too. Hoisting the defuns first instead would reorder definitions before
  the expressions that precede them, breaking the deliberate call-before-define error.
- **Only a file that can reach the throw is wrapped**: a datum spelling `exit` (a call,
  a first-class value, quoted data an `eval` may take apart), a string one may read it
  out of, or `eval` (whose run-time data may name it) -- the same line the run-time
  procedure table draws. Anything else is emitted exactly as before, so a program that
  never quits compiles to the same bytes and pulls no exit machinery (`ExitLibrary`'s
  invariant, and a `--no-wasi` reactor stays acceptable).
- **A session wraps every entry unconditionally** -- it has no whole file and no
  artifact to keep small -- answering the entry's last form's VALUES, which is what the
  prompt echoes: the guard stores `(multiple-value-list form)` and answers
  `(values-list ..)` past the exit test, because a value that went through a variable and
  an `(eq ..)` is one value (`.kb/multiple-values.md`), so `(two)` with `(define (two)
  (values 1 2))` echoes both lines and a procedure answering `(values)` echoes none.
  Non-last forms take the statement guard, a `defun`/`defstruct` stays bare as in a file
  (defining never throws), and a last form that is itself a syntactic multiple-value
  producer -- in lowered code only `(VALUES ...)` can stand there alone -- keeps its
  shape with its ARGUMENTS guarded instead: the prompt echoes through `evalValues`, which
  takes the multi-value path only for that shape.
- **Residual**: an `exit` inside a file the entry file `load`s at run time is caught by
  the loaded file's own wrapper, which ends the process without running the loading
  file's afters (the compile path inlines the load, so the afters run there). Both
  skipped everything before.

Cost (2026-09-18, x86-64 Linux, Java 25; `hello.scm` is `(display "hello, world")`
compiled `-o Hello.class --class-name Hello` / `-o hello.wasm`, the exit program the
pin test's): a program spelling neither `exit` nor `eval` is byte-identical before and
after (1,665 B of class / 510 B of wasm both times). The exit program goes from 52,074
to 53,162 B of class (+1,088) and 1,778 to 6,205 B of wasm (+4,427): the tag and its
landing pads are fixed, one catch region per wrapped form scales it. Control, Common
Lisp `(catch t (unwind-protect (throw t x) ...))` against the same without the
catch/throw: 5,256 vs 4,137 B of class (+1,119), 2,912 vs 840 B of wasm (+2,072) -- the
machinery costs what it costs on every backend, the wrapper only decides how many
regions carry it.

## Internal record types (`define-record-type` in a body; 2026-09-19, `.todo/884`)

**A body's `define-record-type` is a top-level `defstruct`, hoisted; the body binds
names, not variables.** `defstruct` is what registers the instance layout on every
backend and the compile path refuses one below the top level (`.kb/defstruct.md`), so
`SchemeLowering.internalRecord` emits the `defstruct` and the modifier `defun`s into
`hoisted`, which `topLevel` puts AHEAD of the top-level form being lowered (the
interpreter runs forms in order; the exit wrapper and a session entry keep a
`defstruct`/`defun` bare). `body` binds each record procedure in the body's scope to a
`GlobalFunction` / `GlobalPredicate` of the hoisted name, letrec*-style like an internal
`define`: every call stays direct, a predicate still fuses in a test, and a procedure in
value position is `#'name`. A retried body (a loop lowered again as a procedure) finds
its record in `internalRecords` by datum identity and defines nothing twice.

- **Names**: the struct is `s%%[<enclosing> <type>]` -- `<enclosing>` the mangled name of
  the top-level `define` holding it, empty for any other form, and ` 2`, ` 3` ... before
  the `]` when the lowering already took that name. No identifier mangles to `s%%[`
  (`SchemeNames.libraryPrefix`'s argument), and neither part can hold a space, so the
  map is injective; a library prefixes it with its own. Qualifying by the enclosing
  definition keeps two FILES apart the way their top-level names are: two files
  defining the same `f` already collide. Residual: two separately lowered files whose
  unnamed top-level forms (`(let () (define-record-type t ..) ..)`) define a type of
  the same name share `s%%[ t]`. The slots are the FIELD names (the expander may have
  renamed the accessors, `%SCM-V7`), the accessors the defstruct's own through
  `(:conc-name "s%%[f node] ")`, the constructor, predicate and modifiers
  `s%%[f node](make-node)`. So an internal record prints `#S(s%%[f node] :v 42)` and a
  run-time arity error names `s%%[f node](make-node)`.
- **One type per occurrence, not per evaluation.** R7RS leaves generativity open;
  Gauche 0.9.15 makes a new type on every evaluation of the body (an instance of one
  call's type is rejected by the next call's accessor). Per evaluation would need a
  run-time type token in every instance, visible in every print, and would turn the
  constructor and predicate into closures, for a difference a program sees only by
  mixing two evaluations' instances. A session
  keeps `internalRecordNames`, so a definition typed again at the prompt names a new
  type and the old instances keep their layout.
- **Scope**: a record procedure may not be defined twice in one body (`a body defines mk
  twice`), and `set!` of one is `cannot assign mk: it is not a variable in this file` --
  a top-level record's procedures refuse both too. Outside a body (`(if c
  (define-record-type ..))`) it is `define-record-type is only allowed at the top level
  or in a body`.
- **Macros**: `SchemeExpander.body` binds the record's constructor, predicate, accessors
  and modifiers in the body's frame before any value, and emits them renamed; the type
  and field names are labels and are stripped. So `(define-syntax gx (syntax-rules ()
  ((_ v) (point-x v))))` used beside an internal `point-x` still calls the global one.
- **JVM**: `[` is illegal in a JVM method name; `JvmLispCompiler.mangleMethodName` maps
  it (and `;`) since this change (`.kb/core-representation.md`).
- **Cost** (x86-64 Linux, Java 25, wasmtime 47): every program that spells no internal
  record compiles byte-identical before and after -- `hello.scm`, the six
  `examples/scheme/*.scm`, the 70 concatenable `scheme-spec.yaml` cases as one program
  (885,270 B of class, 1,970,462 B of wasm, 1,976,252 B of component), and two Common
  Lisp programs (`size-report/programs/hello_world`, `pi_approx`), on `.class`, wasm
  and the component. A record written in a body against the same record at the top
  level: 75,883 vs 75,752 B of class (+131, the longer names), 12,372 B of wasm and
  13,840 B of component both.
- Pinned by `SchemeLoweringTest` (`anInternalRecordType...`,
  `internalRecordTypesOfTheSameNameAreDistinctTypes`,
  `aMacroUseBesideAnInternalRecordTypeKeepsItsNamesLocal`), `SchemeSessionTest.
  anInternalRecordTypeTypedAgainIsANewTypeHoistedIntoItsEntry`, the
  `internal-record-types-are-local-to-their-body` case of `scheme-spec.yaml` (all four
  backends, Gauche's output), and `JvmLispCompilerTest.
  bracketAndSemicolonInAFunctionNameAreMangledAway`.

## Vertical-line identifiers and the infinities (2026-09-19, `.todo/886`)

- **Reader**: `|...|` is an identifier of any characters, with `\|` `\\` `\"` `\xHH;` and the
  mnemonic escapes; `|` delimits (`a|b c|` is two datums, as in Gauche) and `#!fold-case`
  never folds it. `+inf.0` `-inf.0` `+nan.0` `-nan.0`, case-insensitive and in any radix,
  are flonums (a NaN's sign is dropped: every NaN writes `+nan.0`); `+inf.00`, `inf.0` stay
  symbols. Both readers (`SchemeReader`, the run-time `read`) and `string->number` agree.
- **The booleans are identity symbols.** `SchemeReader.TRUE`/`FALSE` are `LispSymbol`s
  named `#t`/`#f`, and a record compares by name, so `|#t|` WAS `#t` to every
  `.equals(TRUE)` in the lowering. Every site now tests `==` / `SchemeReader.isBoolean`
  (`SyntaxRules.datumEquals` too). A new site must do the same.
- **`write` puts a symbol between vertical lines when its spelling would not read back**:
  R7RS 7.1.1 `<identifier>` less the `<infnan>` spellings, every non-ASCII character a
  letter (Gauche writes `λx` bare); `|` and `\` escaped, a control character as
  `\x<2 hex>;` (Gauche's `|a\x09;b|`). The false value, the unspecified object and the
  environment symbol have their own spellings and never take lines. One deviation from
  Gauche: it writes `|+inf.0x|`, a valid R7RS identifier written bare here.
  `(write (string->symbol "with space"))` was `with space` before -- a deviation the spec
  pinned, now Gauche's `|with space|`; the strict-`r7rs` "Unbound variable: |1+|" matches
  Gauche too.
- **The grammar is spelled twice** -- `SchemeNames.writtenWithVerticalLines` (compile
  time) and `%scheme-plain-identifier-p` (the printer) -- pinned against each other by
  `SchemeBuiltinsTest.writePutsASymbolBetweenVerticalLinesExactlyWhenTheFrontEndSaysSo`.
- **Gated like the bytevector arm** (`SchemeLibrary.BAR_SYMBOLS_FEATURE`): the printer's
  symbol arm calls `%scheme-write-symbol` under `escape` only in a program that quotes a
  symbol the grammar lines (a `QUOTE` or a vector literal) or can intern any name
  (`intern`/`make-symbol`, or a library function reaching one -- `string->symbol`, `read`).
  The writer walks the spelling as a character LIST, never a string: building one with
  `%scheme-symbol->string` cost +13 KB of class and +4 KB of wasm more.
- **Composed internal names escape their parts** (`SchemeNames.component`: ` `, `)`, `]`,
  `|` -> `|s` `|p` `|b` `||`): `(define-library (|a b|) ..)` and `(a b)` were both
  `s%%(a b)`. A name without those characters is unchanged.
- **What infinities reached in the helpers**: `floor`/`ceiling`/`round`/`truncate` of a
  flonum went through Common Lisp's `floor`, which clamps a non-finite float to a long on
  every backend (`(floor +inf.0)` was `9223372036854776000.0`, Gauche `+inf.0`); they are
  `%scheme-flonum-floor` & co. now, answering a flonum of magnitude >= 2^52 (the
  infinities) and a NaN as itself. `exact` of an infinity or a NaN is refused with a
  constant message (`%scheme-exact-flonum`; the irritant formatting of
  `%scheme-error-message` cost +25 KB of wasm for a lone `(exact 2.5)`); before, the
  interpreter and the JVM signalled and wasm trapped on `unreachable`. Common Lisp's own
  `floor` family now signals there ([[linalg-simd]], "mod / rem and the floor family").
- **Left as found**: `(eqv? +nan.0 +nan.0)` is `#t` on every backend (Common Lisp's `eql`
  of one bit pattern; R7RS leaves it unspecified, Gauche `#f`). `(eq? x x)` of a flonum
  variable is `#f` on the interpreter and the JVM, `#t` on wasm -- a Common Lisp split,
  `.todo/887`.
- **Cost** (x86-64 Linux, Java 25, class / wasm / component, before -> after):
  byte-identical -- `hello.scm`, the six `examples/scheme/*.scm`, `(display "...")` string
  programs, `(scheme char)` programs, an `eval` program, `size-report/programs/hello_world`
  and `pi_approx`. Changed, each using the feature: `(display (floor 2.5))` 76,250 -> 76,617
  / 21,508 -> 21,569; `(display (string->number "12"))` 85,337 -> 87,487 / 30,543 ->
  32,726; `(display (exact 2.5))` 74,241 -> 74,839 / 8,353 -> 9,835;
  `(write (string->symbol "a b"))` 96,440 -> 109,422 / 25,803 -> 29,957;
  `(write (read))` 175,552 -> 188,675 / 118,975 -> 123,748; the 67 concatenable
  `scheme-spec.yaml` cases of before as one program 884,103 -> 896,686 / 1,936,768 ->
  1,943,180. `(write '|a b|)`: 86,750 / 15,328.
- Pinned by `SchemeReaderTest`, `SchemeNamesTest`, `SchemeLoweringTest.
  aVerticalLineIdentifierLowersLikeAnyOtherAndIsNeverABoolean`, `SchemeLibraryTest`,
  `SchemeLibrariesTest.aLibraryNamePartWithASpaceKeepsItsNamesApart`, and the
  `vertical-line-identifiers-are-symbols-of-any-spelling`,
  `infinities-and-nan-read-print-and-compare` and `read-knows-vertical-lines-and-infinities`
  cases of `scheme-spec.yaml` (all four backends, Gauche 0.9.15's output).

## `string->number`'s radix and exactness prefixes (2026-09-19, `.todo/889`)

- **Two independent parsers share the same grammar, never each other's code**:
  `%scheme-string->number` (`scheme.lisp`, the run-time `string->number` builtin AND
  `%scheme-hash-token-datum`, so a `#x10` token read from source or from `(read)` and a
  `"#x10"` string given to `string->number` answer alike) and `SchemeReader.prefixedNumber`
  (compile-time source literals, Java). Both read an R7RS `<prefix>`: a radix
  (`#x`/`#b`/`#o`/`#d`, overriding the caller's own default radix argument) and an
  exactness (`#e`/`#i`), each at most once, in EITHER order -- `#e#x10` and `#x#e10`
  both answer `16`. A pair that repeats a kind already seen, or is not one of these six
  letters, is left in the remainder rather than rejected up front: the leftover `#`
  then fails the digit/infnan check downstream, so an invalid prefix answers `#f` (a
  read error from source) exactly like an invalid number, with no separate validity
  branch to keep in sync.
- **`#e` on a decimal is the exact rational the digits spell, never a flonum rounding**:
  `(string->number "#e1.1")` is `11/10`, not the double `1.1`'s nearest rational. Built
  directly from the already-scanned mantissa/scale/exponent
  (`mantissa * 10^(exponent-scale)`, Common Lisp's `/` and `expt` normalizing the ratio
  or integer) rather than through `float`; `SchemeReader` does the equivalent with
  `BigDecimal.unscaledValue()`/`scale()` so no `Double.parseDouble` rounding ever
  touches it either. `#i` on an already-exact result (an integer, a ratio, or a decimal
  answer without `#e`) is the plain `float`/nearest-double conversion
  (`LispRatio.doubleValue()` on the Java side).
- **An exactness prefix on an infinity or a NaN is a no-op**, matching Gauche:
  `(string->number "#e+inf.0")` is `+inf.0`, not an error and not the interpreter's
  `exact` procedure (which refuses an infinity, `.kb` "Vertical-line identifiers and the
  infinities" above) -- the infnan check runs before exactness is ever applied and
  answers as itself either way.
- **Cost** (x86-64 Linux, Java 25, class / wasm, before -> after): byte-identical for a
  program that calls no prefix -- the six `examples/scheme/*.scm` on both backends
  measured directly (`collatz.scm`, `queens.scm`) and by construction for every other
  program (the new helpers are unreachable, dropped like any other,
  `.kb/library-defun-pruning.md`). Changed, using the feature: `(display (string->number "#x10"))` 56,625 ->
  65,116 B of class, 32,685 -> 36,630 B of wasm.
- Pinned by `SchemeReaderTest.radixAndExactnessPrefixesCombine`,
  `.anExactnessPrefixOnAnInfinityOrANanIsANoOp`, `.aRepeatedOrUnrecognizedPrefixIsAReadError`,
  and the `string-to-number-radix-and-exactness-prefixes` case of `scheme-spec.yaml` (all
  four backends, `gosh -r7`'s output). `%scheme-number-prefix`'s tail is a three-value
  `(values remainder radix exactness)`, so it is listed in
  `SchemeValueCount.PASSING_HELPERS` (`.kb/multiple-values.md`).

## A file that reads an imported name before it redefines it

- **Such a name is a variable, initialized to the import's value** by a second leading
  `setq` (a builtin's `:function` form, a SICP constant's form), and every reference goes
  through it: `(define old-abs abs) (define (abs x) ...)` keeps the host's `abs` the way
  the book's evaluators keep `apply`. A `defun` is position-blind -- the interpreter runs
  forms in order and had no `abs` yet (`The function abs is undefined`), the compile path
  hoisted the user's (`old-abs` WAS the user's `abs`: `StackOverflowError` on the JVM) --
  so a name the file reads early cannot take that shape.
- "Read before": a name mentioned (scope-blind, `collectNames`) by a form RUN at the top
  level -- anything but a procedure definition or a record type -- at or before the
  name's first definition, directly or through what any name defined before that form
  mentions (`SchemeLowering.readBeforeDefinition`). So `(define (f x) (abs x))
  (define (abs x) ...) (f 1)` keeps both `defun`s, and `(define (f x) (abs x)) (f 1)
  (define (abs x) ...)` does not. A body of a procedure defined earlier and called later
  sees the user's binding, as a global lookup at call time would.
- Corpus (2026-09-17): the lowered forms of all 1,592 SICP samples are byte-identical
  before and after -- no sample reads a builtin before redefining it (the 19 textual
  hits are quoted data and bodies not run early). Pinned by `SchemeLoweringTest` and the
  `a-builtin-used-before-...` case of `scheme-spec.yaml` (all four backends).
- **A NON-imported name called before its `define`** (`(f 1)` above `(define (f x) ...)`)
  is left as it is, deliberately: an error in Scheme, the interpreter's
  `The function f is undefined`, while the compile path's hoisted `defun` answers 2.
  Refusing it on the compile path would cost the direct call of every procedure for a
  program that is wrong anyway; a session has the same order as the interpreter.

## The library tags: `base`, `write`, `read`, `char`, `inexact`, `cxr`, `lazy`, `case-lambda`, `process-context`, `eval`, `repl`, `file`, `sicp` and `r5rs`

`SchemeBuiltins` entries carry the R7RS library that exports them, checked entry by
entry against Gauche 0.9.15's `(module-exports (find-module 'scheme.<lib>))`
(2026-09-18). `base`, `write`, `read` (`read` alone: `eof-object`, `eof-object?`,
`read-char`, `peek-char`, `read-line`, `char-ready?` and every other port procedure are
`base`, as in R7RS), `char` (all 22 `(scheme char)` exports, "`(scheme char)`" below), `inexact`,
`cxr` (the whole `(scheme cxr)` set, `caaar` through `cddddr`: every one a
standard Common Lisp function of the same name), `lazy`, `case-lambda` (the keyword
alone), `process-context`, `eval` (`eval`, `environment`), `repl`
(`interaction-environment`) and `file` (all ten `(scheme file)` exports, "File ports" below) are `SchemeLowering.IMPORTABLE_LIBRARIES`: `(import (scheme
<tag>))` names them, and a file with no import at all merges all twelve. Keywords carry a
library too: `SYNTAX` is `base`, `LAZY_SYNTAX` (`delay`, `delay-force`) `lazy`,
`CASE_LAMBDA_SYNTAX` `case-lambda`, `SICP_SYNTAX` (`cons-stream`) `sicp`.
`sicp` (`true false nil the-empty-stream user-initial-environment
system-global-environment` -- via `SchemeLowering.Constant` over
`SchemeBuiltins.constants()`, not an `Entry`, since they are values, not procedures --
`filter reduce fold-left fold-right delete last-pair append!
list-index 1+ -1+ random runtime parallel-execute test-and-set!`) is no R7RS library, so no import names it:
`SchemeLowering.imports()`'s no-import branch merges it too, so a file with no import at
all (an unqualified SICP sample, or a REPL) sees it anyway, and an explicit import list
narrows to exactly what it names. `r5rs` (`scheme-report-environment`) rides the same
no-import default, as do R5RS's `exact->inexact` and `inexact->exact` (tagged `base`
until 2026-09-18, so `(import (scheme base))` exposed them): `(scheme r5rs)` would promise
the whole of R5RS, so it stays refused by name (`.todo/829`
measured 1,251 -> 1,307 of the 1,592-file SICP sample corpus running to exit 0 in file
mode from this alone, zero regressions -- `.todo/artefacts/828-sicp-sample-corpus-harness/`
has the harness). A user `define` of any of these still wins, exactly like `square`:
`SchemeLowering.declareGlobals` overwrites the global scope entry for any name the file
defines regardless of what library put there first -- under `--scheme-standard
rontolisp`; `r7rs` refuses it (next section).

## `--scheme-standard rontolisp|r7rs` (2026-09-18, `.todo/857`)

One option, like `gosh -r7`, picks what EVERY Scheme file of the program is read
against: the entry file, a file `load`ed at run time (`LispEvaluator.loadFile`) or
inlined (`LoadInliner`), and the REPL (`SchemeSession`). `rontolisp` (default) is
everything above: R7RS plus `sicp` and `r5rs`, all visible without an import. `r7rs`
is R7RS-small within the implemented subset:

1. **A file that does not begin with `import` is refused** at lowering, positioned at its
   first datum (`an R7RS program begins with an import declaration`, R7RS 5.1). Gauche
   instead starts empty and fails at the first unbound name.
2. **`sicp` and `r5rs` names are never visible**: `SchemeLowering.imports()`'s no-import
   branch (the session's) merges the twelve libraries only.
3. **Redefining an imported binding in a file is refused** (R7RS 5.6.1 "it is an error";
   Gauche -r7 allows the `define` silently): a top-level `define`, `define-values`, or a
   `define-record-type` type or procedure name over a `Builtin` or `Syntax` binding
   (`refuseRedefiningAnImport`, run before `declareGlobals` overwrites the scope), and a
   `set!` resolving to a `Builtin` (positioned; the default's own refusal of that `set!`
   is an unpositioned "not a variable in this file"). A SESSION may redefine, as an
   R7RS REPL and gosh's `r7rs.user` do: it is where one experiments.
4. **`eval` needs its environment**: `SchemeBuiltins.entries(R7RS)` swaps in the
   `R7RS_TABLE` row, `((x env) ...)` with a two-argument `:function`, so a direct
   `(eval x)` is the lowering's positioned arity error and a first-class or run-time
   `eval` of one argument is the helper's arity error.

**Decision: `eval`'s run-time table follows the standard.** `Scheme.runtimeForms` takes
it: under `r7rs` the generated `%scheme-builtin` holds no `sicp`/`r5rs` procedure or
constant (`entries(R7RS)`, `constants(R7RS)` empty), and the generated
`%scheme-eval-extension-keyword-p` (the `SICP_SYNTAX` names, i.e. `cons-stream`, which
`%scheme-eval-keyword-p` consults) answers nothing. So `(eval '(1+ 1) (environment
'(scheme base)))` is `Unbound variable: 1+` under `r7rs` -- Gauche -r7 says the same --
and 2 under the default. Every environment specifier is still the one global
environment, so a `(scheme base)` environment still sees the program's own globals and
every IMPORTABLE library; only the non-R7RS names are cut. The compiled table is cut to
spelled names as before (`CompileFrontend.Loaded.standards` reaches
`SchemeLibrary.process`); the interpreter's copy is loaded per evaluator from
`SchemeLibrary.forms(SourceStandards)`, cached per features x standard.

**Plumbing.** The value is `scheme/SchemeStandard`; it crosses the seam wrapped in
`eval/SourceStandards` (one member per language that offers a choice), so `cli` holds
it without an edge to `scheme` (`.kb/source-language.md`). `RontoLispCli` parses
`--scheme-standard` next to `--source-language` and refuses an unknown value by name;
`JvmSourceCompiler.schemeStandard(String)` is the embedder's. The Maven plugin compiles
`.lisp` only and has no parameter (a `.lisp` there that loads a `.scm` reads it under
the default). The playground keeps the default.

The retag and the default standard move no SICP sample: a file with no import still
merges every tag. `SicpCorpusE2eTest` over the pinned corpus after the change
(2026-09-18): 5,262 legs, 0 failures, 17 skipped, the manifest unchanged.

Unchanged in both: a name the file neither imports nor defines is a direct call or
variable reference -- how Scheme and Common Lisp files call each other; a whole-program
unbound-name check would cross files.

Pinned by `SchemeLoweringTest` (`anR7rsProgramBeginsWithAnImportDeclaration`,
`strictR7rs...`, `eachNameIsImportedFromTheR7rsLibraryThatExportsIt`), the
`RontoLispCliTest.schemeStandard...` cases (file on both paths, loaded file on both
paths, REPL, unknown value), and the `standalone:` cases of `scheme-spec.yaml` with a
`standards:` field (the valid R7RS program under both; `eval` reaching no `1+` and no
`cons-stream` under `r7rs`), all four backends.

## `(scheme inexact)` and how a flonum prints

- **Every procedure is a `%scheme-` helper**, not a template over the Common Lisp
  function: an exact argument with an exact answer stays exact (`(sqrt 16)` 4, `(sqrt 1/4)`
  1/2 through `isqrt` of numerator and denominator; `(exp 0)` 1, `(log 1)` 0, `(sin 0)` 0,
  `(cos 0)` 1, `(acos 1)` 0, `(atan 0 x>0)` 0), and a real argument Common Lisp would
  answer with a complex (`(sqrt -4)` is `#C(0.0 2.0)`, also `log` of a negative, `asin`/
  `acos` outside [-1, 1]) is refused through `%scheme-no-complex`, an `error` whose message
  names the procedure. `(log 0)` is `-inf.0` on every backend, as in Common Lisp here.
- A user binding of any of these names wins like `square` (16 SICP samples define `sqrt`,
  100 bind `exp` as a variable in `(eval exp env)`).
- **The transcendental digits are one set on every backend** -- fdlibm everywhere since
  2026-09-17 (`.kb/transcendentals.md`); before that the interpreter and the JVM ran
  `Math` and wasm a software core, and the SICP sample
  `chapter1/section1/subsection8/02.scm` printed differently on wasm. `sqrt` of a float is
  `f64.sqrt` / `Math.sqrt`, correctly rounded.
- **`%scheme-print-flonum`** is the printer's float arm and `number->string`'s: the digits
  are the Common Lisp printer's (shortest round-trip, Schubfach on wasm), re-laid out with
  the ECMAScript thresholds -- positional while the point is at most 21 digits right of the
  first digit or fewer than 6 zeros left of it (`123456789.123`,
  `100000000000000000000.0`, `0.000001`), `<digits>e<exp>` otherwise (`1e21`, `1.5e-7`);
  the Common Lisp printer answered `1.0e21` and `1.23456789123e8`. `+inf.0` `-inf.0`
  `+nan.0` print, and read since `.todo/886`.
- `-0.0` is read with `Double.parseDouble` (`BigDecimal.doubleValue()` dropped the sign),
  and `string->number` negates after converting.
- Cost (2026-09-17): `(display (list 1 'a "s"))` went from 58,745 to 72,993 B of `.class`
  (generic fixnum-fusion helpers for the digit arithmetic, `princ-to-string`); the `.wasm`
  grew 14 B, because the type-test fold drops the float arm of a program that makes no
  float. `(display 42)` is unchanged.
- Corpus (2026-09-17, `.todo/artefacts/828-sicp-sample-corpus-harness/run.py`): file mode
  1,307 -> 1,314 samples exiting 0, no regression; no sample still fails on an inexact
  name.

## `(scheme char)` (2026-09-19, `.todo/879`)

**Invariant: every answer is the JDK's Unicode data (Unicode 16 on Java 25), identical on
all four backends by construction.** Common Lisp's classifiers are not: `alpha-char-p`
and `digit-char-p` are ASCII-only on wasm (`.todo/269`; measured over every code point,
2026-09-19: 141,028 alphabetic on the interpreter and the JVM, 52 on wasm), and
`upper-case-p`/`lower-case-p` answer "has a mapping", which is not the Unicode
`Uppercase`/`Lowercase` property (`ǅ` is both there, neither in Unicode; `ª`, `ℂ`, `𝐀`
have no mapping and are cased). `char-upcase`/`char-downcase` ARE uniform (one fold table
per backend, generated from the JDK: `.kb/characters-code-points.md`) and are used as is.

- **Tables are generated, not written** (`scheme/SchemeCharacters`, appended by
  `Scheme.runtimeForms` like the procedure table): `Alphabetic` (757 ranges), `Nd` (71;
  adjacent runs of ten share a range, so `digit-value` is `(mod (- code from) 10)`), where
  `Uppercase`/`Lowercase` disagree with "has a lowercase/uppercase mapping" (58 / 132),
  `Case_Ignorable` (451, for the final sigma), and the 102 unconditional multi-character
  uppercase mappings of `SpecialCasing.txt` as one-point ranges beside a vector of
  strings. `White_Space` is spelled by value in the helper (stable since Unicode 6.3).
  A table is a string of `[from, to]` bounds, four base-64 characters each (48 + d,
  skipping the backslash; 11,752 characters in all), decoded into a simple vector on
  first use and kept in a `defvar`. ASCII answers without a table. The pruner drops every
  table a program does not reach.
- **Folding** (`char-foldcase`: Unicode simple case folding) is the lowercase of the
  uppercase, except that U+0130/U+0131 do not fold and Cherokee folds to its capitals
  (U+13A0..U+13F5). **Full folding** (`string-foldcase`, the `-ci` string comparisons,
  `#!fold-case`) is the simple folding of each character of the full uppercase, except
  U+0130 -> `i` + U+0307, U+0131 unchanged, U+1E9E -> `ss`. Both rules were checked
  against `CaseFolding.txt` (Unicode 15, Perl's `Unicode::UCD`) over every code point on
  2026-09-19: identical except six Unicode 16 additions Perl's data lacks, which the rule
  gets right. The rule is spelled twice -- `SchemeCharacters.foldcase` for the
  compile-time `#!fold-case`, `%scheme-char-foldcase` / `%scheme-string-foldcase` at run
  time -- and `SchemeCharactersTest` pins them to each other.
- **`string-upcase` / `string-downcase` are the full mappings**, so the result may be
  longer (`"straße"` -> `"STRASSE"`); downcase maps U+0130 to two characters and a capital
  sigma by Unicode 3.13 `Final_Sigma` (a cased character before it and none after,
  looking through `Case_Ignorable`), which is what `String.toLowerCase(Locale.ROOT)`
  does. An ASCII string takes Common Lisp's `string-upcase`/`string-downcase`.
- **Where Gauche 0.9.15 answers differently** (the oracle otherwise): `char-whitespace?`
  of U+0085 (`#f` there); Cherokee (tables older than Unicode 8); `string-foldcase` of
  `ẞ` (`ß`) and of `ı` (`i`, though its `char-foldcase` keeps `ı`); `#!fold-case` folds
  ASCII only there; `string-downcase` of `"1Σ"` is `"1ς"` there. The
  `char-library-follows-unicode` case of `scheme-spec.yaml` pins our side.
- **Testing cost**: the interpreter sweeps every code point in about 200 s per property
  (the full sweep was run once, 2026-09-19, and matched the JDK everywhere except
  `digit-value` on merged runs, fixed then), so `SchemeCharactersTest` asks at the 7,000
  code points where an answer can go wrong -- the first 1,024 and both sides of every
  range bound and every departing mapping -- in about 10 s.
- **Speed** (2026-09-19, x86-64 Linux, Java 25): a million `char-alphabetic?` calls on
  non-ASCII characters take 0.34 s on the JVM and 0.92 s on wasm (a control loop with
  `char<?` in their place, 0.16 / 0.08 s). Reading the four characters of each bound
  straight from the string instead cost 24.5 s on wasm, and a generic `floor` for the
  midpoint instead of `ash` 1.84 s on the JVM.
- **Size** (same date; `-o P.class` / `-o p.wasm`): a program using no `(scheme char)`
  name is byte-identical -- `hello` 1,661 / 510 B, a string program 80,935 / 18,333 B, an
  `eval` program 127,708 / 82,101 B. A program calling `read` grows from 162,078 to
  173,478 B of class and 99,724 to 117,507 B of wasm: the reader's `#!fold-case` now
  folds through `%scheme-string-foldcase`. Alone, `char-upcase` costs 73,966 / 7,069 B,
  `string-upcase` 98,569 / 41,042 B, and `char-alphabetic?` + `string-ci=?` +
  `string-downcase` together 117,906 / 64,339 B.
- No SICP sample spells a `(scheme char)` name (`sicp.zip` above, grep 2026-09-19), so the
  no-import default growing by 22 names moves nothing in the corpus manifest.

## Promises and streams (`(scheme lazy)`, SICP streams)

- **A promise is a `defstruct`, `%scheme-promise`, around a box `(state . payload)`**:
  2 forced (payload the value), 0 `(delay e)` (a thunk answering the value), 1
  `(delay-force e)` (a thunk answering a promise). A record, so `promise?` is honest and
  `procedure?` of a promise is `#f`; `force` of a non-promise answers it. `%scheme-force`
  is a loop: a `delay-force` result's box is copied in and SHARED (R7RS's
  `promise-update!`), so a chain of 100,000 `delay-force`s runs in constant stack, and the
  box is re-read after the thunk returns, so a thunk that forces its own promise
  (R7RS 4.2.5's reentrancy example) keeps the first value to land.
- `(delay e)` lowers to `(%scheme-delay 0 (lambda () e))`: the thunk goes through the
  ordinary `lambda` lowering, so a loop that delays counts as a closure and rebinds its
  variables per iteration. `(cons-stream a b)` is `(cons a <delay b>)` spelled with the
  identity-compared `CORE_DELAY`, so a user binding of `delay` does not capture it. Both
  are keywords only until shadowed: 13 SICP samples bind `delay` as a variable.
- **A stream is `'()` or `(value . promise)`**: `the-empty-stream` is a `Constant` NIL and
  `stream-null?` is `null`, since the corpus mixes them with `'()`/`null?`. The stream
  procedures are `%scheme-stream-*` Common Lisp helpers, not Scheme source, so a user
  `define` of `apply` cannot reach them (a Scheme stand-in broke in the two samples that
  redefine it). Every walk (`stream-ref`, `stream-tail`, `stream-filter`'s skip,
  `stream->list`, `stream-for-each`) is a loop: `(stream-ref s 200000)` is pinned.
  `stream-head` answers a LIST (MIT Scheme's).
- The printer writes `#<promise>`; its predicate is what every printing program keeps of
  the record (`.kb/library-defun-pruning.md`, the name exception).
- Corpus (2026-09-17, `.todo/artefacts/828-sicp-sample-corpus-harness/run.py`): file mode
  1,314 -> 1,342 samples exiting 0, REPL mode 1,591 unchanged, no regression. Every sample
  that uses a stream or promise name and now exits 0 on the interpreter prints the same on
  the JVM and wasm, except two `fragment`s whose delayed thunk names a global no file
  defines (a compile error there, `.todo/828`). The remaining stream failures are samples
  calling procedures defined in OTHER samples (`partial-sums`, `display-stream`, `pairs`).

## `parallel-execute` and `test-and-set!` (SICP 3.4)

- **`scheme.lisp` is read with the TARGET's features** (`SchemeLibrary.forms(Features)`,
  from `CompileFrontend.expand` and the playground's chain). `%scheme-parallel-execute`
  under `#+thread-support` spawns one `rontolisp:make-thread` per thunk and joins them all
  in nested `unwind-protect`, so a thunk's error is re-signaled by its join only after the
  remaining threads are joined. Without it (both wasm) the thunks run in argument order:
  a legal interleaving, where a serializer's busy-wait never contends -- NOT a call-time
  signal like `bt2:make-thread` (`.kb/threads.md`), which has no such reading. Both
  branches define the same names, so `isSchemeFunction` and the pruner read the
  interpreter's copy.
- `test-and-set!` is a `pred` over ONE `rontolisp:make-mutex` for every cell
  (`%scheme-test-and-set-lock`, a `defvar` the pruner drops with the helper: a program
  that does not call it carries no mutex, pinned in `LibraryDefunPrunerTest`). "Set" is
  "car is not `#f`", so `'()` is set. A user `define` (the book's non-atomic version,
  `24_test_and_set.scm`) wins like `square`.
- Races (2026-09-17, 64 cores): four unserialized thunks of 100,000 `(set! g (+ g 1))`
  on a global ended between 111,274 and 270,784 on the JVM and the interpreter and never
  crashed; the same on a `let` variable captured by the thunks (the JVM's boxed capture)
  kept an integer. Three serialized thunks of 20,000 were exact on all four backends,
  also under `-Djdk.virtualThreadScheduler.parallelism=1` and
  `-XX:ActiveProcessorCount=1`, and with a `display` inside the critical section: the
  book's `the-mutex` spins through its self tail call, which lowers to a loop.
- Corpus (2026-09-17, `.todo/artefacts/828-sicp-sample-corpus-harness/run.py`): file mode
  1,342 -> 1,347 exiting 0 (the five `parallel-execute` samples), no other exit or stdout
  change; all 19 `variant=concurrent` samples exit 0 on the interpreter, the JVM and wasm.

## `eval` (`(scheme eval)`, `(scheme repl)`, the MIT and R5RS environment names)

- **`eval` is a Scheme evaluator over Scheme DATUMS in `scheme.lisp`** (`%scheme-eval`),
  one definition for all four backends: the lowering is Java and not inside a compiled
  program, and the run-time `eval` the backends carry (`.kb/eval-runtime.md`) evaluates
  Common Lisp core forms. No CL `eval` is involved, so `usesEval` stays off; what the
  evaluator needs from a compiled program is `fboundp` / `symbol-function` / `boundp` /
  `symbol-value` with a COMPUTED name -- the name registry and the `_genv` mirror, which
  all four backends answer for a later `defun`, a `setq`'d global and its later updates
  (measured 2026-09-17); `set` and `(setf (symbol-value ..))` do not exist on any of them.
- **Every environment specifier is the one global environment**, the symbol
  `#[environment]` (`SchemeBuiltins.ENVIRONMENT_NAME`, MIT's spelling, so `display` shows
  it; `symbol?` of it answers `#t`, a stated wart): `(interaction-environment)` (`repl`),
  `(scheme-report-environment 5)` (`r5rs`), `user-initial-environment` /
  `system-global-environment` (`sicp` constants) and `(environment sets..)` (`eval`;
  every set is checked against `IMPORTABLE_LIBRARIES` through the generated
  `%scheme-library-p`, modifiers included). `(eval x)` with no environment is accepted,
  except under `--scheme-standard r7rs`.
- **Name resolution order**: the program's variables (`boundp` -- including what
  `eval` itself defined, since `set` makes a global appear at run time on every
  backend, `.todo/852`), then the program's `fboundp` names (a user `define` wins
  over a builtin, as in a file; `fboundp` is case-sensitive, so a lowercase `car`
  never answers `CAR`), then the builtins through `%scheme-builtin`. A `define`
  inside `eval` is a program global through `set`, visible to later `eval`s and to
  the program itself; a `set!` of a program variable or a builtin assigns it the
  same way. A keyword is syntax unless a variable of that name is in scope
  (`%scheme-eval-syntax`: frames, `boundp`, `fboundp`); a datum's symbols carry
  their mangled spelling, so the keyword `=>` is `s%=>` there.
- **The run-time table is GENERATED from `SchemeBuiltins`** (`runtimeForms`: one `case`
  arm per entry's `:function` and per constant, keyed by the mangled name, appended to
  `scheme.lisp`'s forms by `SchemeLibrary.forms`, so the table is spelled once); the
  interpreter's copy holds every entry. **A compiled program's holds only the names it
  SPELLS** (a symbol anywhere, quoted data included, or a substring of a string literal
  -- `SchemeLibrary.process`): the whole table reaches every helper there is, 291 KB of
  class / 235 KB of wasm for `(display (eval '(+ 1 2) (interaction-environment)))`
  against 132 KB / 80 KB cut to what is spelled, and 80 KB / 7 KB with no `eval` at all
  (the rest is the name registry, the evaluator and the printer its messages use;
  2026-09-17). The same line the compiled CL `eval`'s registry draws.
- **Environments are frames `(alist . loop)`**: `set!` mutates a cell, an internal
  `define` pushes onto the innermost frame, which every closure over it shares. A named
  `let`, a `define`d procedure and a `letrec` lambda whose name is only ever CALLED in
  its body (`%scheme-eval-called-only`: never a value, never assigned, never rebound; a
  mention under `quasiquote` refuses) get a LOOP frame `(name formals body outer)`, and a
  call of the name found through the frames (`%scheme-eval-loop`) rebinds a fresh frame
  over `outer` and continues in `%scheme-eval`'s own `do` loop -- a jump in tail
  position, the same value a call gives anywhere else, so no tail-position analysis is
  needed. 100,000 iterations of a named let, of a self-calling `define` and of a `do` run
  on all four backends; before the loop frames a named let of 1,000 exhausted wasm's
  stack (2026-09-17). The interpreter runs this evaluator as interpreted Lisp: ~200 us an
  iteration (100,000 took 20 s, 2026-09-24), which is why `doc/*/scheme/eval.md` loops
  10,000 -- still past the ~10,000-deep non-tail recursion that overflows the CLI's 16 MiB. A closure made in an iteration keeps its iteration's frame. Stated
  deviation, the session's too: an old closure of a name `set!` later keeps jumping to
  itself. Every other call recurses (`ev?`/`od?`).
- Errors are Scheme-spelled through `%scheme-error-message`: `Unbound variable: x`,
  `Ill-formed special form: (if)`, `Not supported inside eval: (define-record-type ..)`
  (also `define-values`, `let-values`, `import`, and a `cond-expand` a program that
  spells none constructed -- "`cond-expand`" below),
  `Wrong number of arguments: (a b) given (1)`, `The object is not applicable: 3`,
  `eval: not an environment: 2`, `environment: library is not available: (scheme time)`,
  `Syntactic keyword may not be used as an expression: if`. **Superseded (2026-09-19):**
  a first-class `values` inside `eval` was one value on the compiled backends; since the
  `%mv-spill` channel became exact (`.kb/multiple-values.md`) it answers every value there
  too, the same as the interpreter and the same as `(apply values ...)` or `values`
  through a variable. Pinned by the `multiple-values` case of `scheme-spec.yaml`.
- Corpus (2026-09-17, `run.py`): file mode 1,347 and REPL mode 1,347 samples exiting 0
  before and after, no exit or stdout change; the one `eval` sample
  (`chapter4/section4/subsection4/14_execute.scm`, the query system's `lisp-value`) is a
  fragment that only defines `execute`.
- Pinned by the three `eval-...` cases of `scheme-spec.yaml` (all four backends),
  `SchemeLoweringTest.evalAndEveryEnvironmentSpecifierLowerToTheRunTimeEvaluator`,
  `SchemeBuiltinsTest.theRunTimeTableAnswersEveryProcedureAndConstantByItsMangledName`,
  `LibraryDefunPrunerTest.theSchemeEvaluatorAndItsProcedureTableFollowOnlyAProgramThatEvals`,
  `RontoLispCliStreamsTest.anErrorInsideSchemeEvalIsReportedInSchemeTerms`.

## `(scheme read)` (the current input port, or a port argument)

A reader in `scheme.lisp` over `read-char` on `*standard-input*`, spliced like the
rest of the run-time half, on all four backends -- not the emitted Common Lisp reader
(which upcases and knows `#'`, `|...|`, packages). A datum comes back as what quoted
data lowers to (`SchemeLowering.datum`): identifiers via `SchemeNames.mangle`
(`%scheme-string->symbol` at run time), `#t` -> `T`, `#f` -> the false value, `()`
-> `NIL`, strings/chars/numbers (the `%scheme-string->number` path, `#x`/`#b`/`#o`/`#d`
included), `'`/`` ` ``/`,`/`,@` as `quote`/`quasiquote`/`unquote`/`unquote-splicing`
lists, `#( )`, dotted pairs, `;`/`#;`/`#| |#` skipped -- or `(eq? (read) 'quit)` is
false. `#u8(` / `#U8(` reads a bytevector through `%scheme-bytevector`, refusing a
non-byte element as a read error. `|...|` (`%scheme-read-bar-symbol`, the string escapes
shared through `%scheme-read-escape`) and `+inf.0`/`+nan.0` (`%scheme-infnan`, inside
`%scheme-string->number`) read as in source. Refusals match the frontend:
`[`/`]`/`{`/`}`, unsupported `#`, `(|...|/|char|)` by name; an incomplete datum
raises a read error (`read-error?`, "Exceptions" below).

- **EOF is a `defstruct` singleton** (`%scheme-eof`, one `%scheme-eof-instance`):
  unforgeable (no read syntax, `symbol?` false), prints `#<eof>` like Gauche,
  `eof-object?` is its predicate. `(eof-object)` CALLS `%scheme-eof-object` rather than
  reading the variable: the interpreter loads `scheme.lisp` on the first resolution of
  one of its FUNCTIONS, so a template whose only library reference is a variable is
  unbound when it is the first thing a fresh evaluator runs (a REPL's first form, a
  one-form program) -- `SchemeBuiltinsTest.eofObjectWorksAsTheFirstThingAFreshEvaluatorRuns`.
  `(read)`/`read-char`/`peek-char`/`read-line` answer
  it at end of input instead of signalling; a second `(read)` there answers it again
  (the peek stays parked). `(read port)` reads a textual input port ("Ports" below).
- **One Lisp-level pushback cell** (a list, so `#|` un-reads two characters), keyed on
  the current `*standard-input*` value (one stream at a time, like CL's unread-char
  cell; a rebind clears it) -- in a program that uses no port procedure. With ports the
  cell is the port's ("Ports" below). Peek is read + pushback, never CL's `peek-char`, so no
  WASM peek slot is ever parked (`PEEK_FD_ADDR` is drained by `read-char` only, and
  mixing peek with `read-line` there loses it) and `read`/`read-line` mix freely.
- **`char-ready?` answers `#t` everywhere**: WASM has no non-blocking probe (`listen`
  is a call-time error there), so a probe would split the four backends; true with
  data and at EOF (the pinned cases), true as well on a terminal with nothing typed
  (the stated deviation, where the next read would hang).
- **Stdin costs/supports**: one `read-char` per character (a WASI `fd_read` byte on
  Preview 1, UTF-8 assembled byte-wise like `sockets.lisp`; a string-stream record
  when `*standard-input*` is rebound). A non-async `--component` program keeps the
  adapter's blocking stdin path (nothing spliced, no new WIT/flags), so `< file`
  redirection works there too.
- **REPL shares one `BufferedReader`** (`Environment.replInputReader` via
  `LispEvaluator.readReplLine`, which `RontoLispCli.replWithBufferedReader` reads
  instead of its own): a piped session holds no second look-ahead of its own, so a
  `(read)` inside it -- a `(driver-loop)` typed at the prompt taking over -- sees
  what was typed next. JLine (a real terminal, interactive) is unchanged.
- **`#!fold-case` / `#!no-fold-case` (`.todo/858`)**: an R7RS `<directive>`, part of
  `<atmosphere>` like a comment -- no datum, only the side effect of folding
  identifiers (as `string-foldcase` does since 2026-09-19 -- the run-time reader through
  `%scheme-string-foldcase`, `SchemeReader` through `SchemeCharacters.foldcase`; before,
  the two lowercased, and differently: `String.toLowerCase` applies the final sigma,
  `string-downcase` did not -- never string literals) and `#\`-style character
  NAMES (not the character a bare `#\A` names) until the counterpart directive. Kept
  keyed on `*standard-input*` the same way as the pushback cell above, so a rebind
  (a fresh "file") starts folding off again; `SchemeReader` keeps the same flag as an
  instance field, one per compile-time read.

## Macros (`SchemeExpander`, `SyntaxRules`; 2026-09-18, `.todo/861`)

**The expander runs BEFORE the lowering, over datums, and outputs datums with no macro
left**, so every pre-scan of the lowering -- the `set!` census, the defun-or-variable
decision, a body's internal definitions, `readBeforeDefinition` -- sees the expanded
program: a macro expanding to `(define f (lambda ..))` still makes `f` a `defun`, one
expanding to `(set! g ..)` still makes `g` a variable. Expanding on demand inside the
lowering would have hidden both from the scans that run first.

- **Gate**: only a file (or session buffer) that spells `define-syntax`, `let-syntax`,
  `letrec-syntax` or `syntax-error` -- by name or through an import rename -- is
  expanded (`SchemeExpander.needed`); a session keeps its expander, and so its macros,
  from then on. Every other program is lowered exactly as before, byte for byte.
- **Hygiene by renaming.** A symbol a template introduces becomes an ALIAS: a fresh
  `LispSymbol` of the same spelling (identity-distinct; `LispSymbol` equality is by name,
  so aliases live in an `IdentityHashMap`) remembering the symbol and the macro's
  DEFINITION environment. Resolution looks the alias up where it is used first (a binder
  the same expansion introduced), then resolves the original in the definition
  environment. A bound alias becomes a fresh generated variable (`%SCM-V<n>`); a free
  alias resolving to a keyword becomes the lowering's identity-compared core symbol
  (`SchemeLowering.coreSymbol`, now one per implemented keyword, so `(let ((if list))
  (my-or ..))` still expands to the real `if`); to a variable, its emitted name; free,
  its spelling. `quote`, `case` data and the literal parts of a `quasiquote` get the
  aliases stripped back to their spelling. Literals match by `free-identifier=?` (same
  binding in each side's environment, or both free with one spelling): a user's local
  `else` does not match a macro's `else` literal (Gauche agrees).
- **Every LOCAL of an expanded program is renamed** to a generated variable, the user's
  too. A free alias is emitted by its spelling and the lowering keeps Scheme spellings
  for locals, so without this `(let ((x 'outer)) (let-syntax ((m .. x)) (let ((x
  'inner)) (m))))` answered `inner` (Gauche: `outer`) -- the Common Lisp binding captured
  it. Renaming every local makes that impossible by construction; renaming only on
  detected shadowing would need to know, at the binder, which macros might later emit the
  name. Top-level names keep their spelling (other files reach them); a top-level name a
  TEMPLATE defines is a hidden generated global only that expansion spells (Gauche -r7:
  `(def-foo 42)` then `foo` is unbound), which keeps `(define count 0)` inside a
  counter-defining macro from colliding with the user's `count`.
- **The walk mirrors the lowering's scoping** of every binding form (`lambda`, `define`,
  the `let` family and named `let`, `letrec`, `let-values`, `do`, internal definitions
  bound before any value, letrec*-style). A shape it cannot parse is handed to the
  lowering stripped of aliases, and the lowering names what is wrong. Top-level names
  defined anywhere in the file are pre-registered, as `declareGlobals` would.
- **Positions**: every cons an expansion builds inherits the USE's position, both in
  `SourceProvenance` and in `SchemeReader`'s own map (`SchemeReader.inherit`), so an
  error inside an expansion (`(if)`) names where the use stands.
- **Errors**, positioned: no clause matches, a macro keyword as a variable, `syntax-error`
  (message plus the arguments as written), a pattern variable at the wrong ellipsis depth,
  a syntax definition in expression position, a transformer other than `syntax-rules`,
  more than 1,000 nested expansions ("does not terminate"). Strict R7RS refuses a
  top-level `define-syntax` over an import (R7RS 5.6.1), as it does `define`.
- **Library**: `define-syntax`, `let-syntax`, `letrec-syntax`, `syntax-rules`,
  `syntax-error`, `...` and `_` are `(scheme base)` keywords (R7RS 7.1; Gauche's
  `scheme.base` exports all seven). `...` and `_` are keywords only to the matcher and as
  "misplaced" errors; a free `...` also counts as the ellipsis. None of the 1,586 SICP
  samples spells any of them, so the corpus classification (`providedNames`) is unmoved.
- **Stated limits**: `syntax-rules` only; a macro is per FILE (a `load`ed file neither
  sees nor exports macros; a library exports them, "Exported syntax"); a template's names inside a TOP-LEVEL `define-record-type`
  are stripped, not renamed (a body's are renamed like any internal definition's); `eval` knows no macro and refuses `define-syntax` by name (its keyword
  list in `scheme.lisp`); an improper use `(m 1 . 2)` is matched rather than refused.
- Pinned by the three `syntax-rules-...` / `syntax-definitions-...` cases of
  `scheme-spec.yaml` (all four backends, expected output Gauche 0.9.15's; being in the
  concatenated corpus, they also push every case before them through the expander),
  `SchemeLoweringTest` (`aSyntaxRulesMacroIsExpanded...`, `...RenamesEveryLocalVariable`,
  `aTemplateBinderCaptures...`, `aMisusedMacroIsAPositionedError`,
  `strictR7rsRefusesASyntaxDefinitionOverAnImport`) and
  `SchemeSessionTest.aMacroDefinedAtOnePromptIsExpandedAtTheNext`.

## Exceptions (`guard`, `raise`, `with-exception-handler`; 2026-09-18, `.todo/865`)

**Scheme raises go through a Scheme-level handler stack; built-in errors through
`handler-case`.** `rontolisp::%scheme-handlers` (a `defvar`, bound by `let`) holds, innermost
first, `with-exception-handler` procedures and `guard` catch tags. `raise` /
`raise-continuable` (`%scheme-dispatch`) call the innermost procedure with the stack
outside it bound -- where the raise stands, no condition made -- or `throw` the object to
the innermost guard's tag. A procedure returning answers a `raise-continuable`; from
anything else it raises the secondary error `handler returned from non-continuable
exception: <obj>` (message, no irritants -- Gauche's `error-object-irritants` is `()` too)
with the same outer stack. Stack empty: `(error '%scheme-raise :payload obj)`, the
condition whose report is the payload as `write` spells it -- what an uncaught raise
reports and what a Common Lisp `handler-case` around Scheme code catches.

- **`handler-case` catches what a built-in signals** (`(+ 1 'a)`, an internal
  `(error "~A" ..)` of `scheme.lisp`): `%scheme-guard` and `%scheme-with-exception-handler`
  wrap their thunk in one, so a built-in error's handler runs at that boundary, after the
  unwinding, on ALL FOUR backends alike. `handler-bind` would run it at the signal point on
  the interpreter only (`.kb/error-handling.md`, "Deviations") and puts the program in
  restart mode. `with-exception-handler`'s `handler-case` passes a `%scheme-raise` through
  (re-signalled typed): that raise already went past its handler. A guard's catches one
  (a thread's raise re-signalled by `parallel-execute`'s join).
- **Error objects are conditions.** `error` raises a `make-condition` of `%scheme-error`
  (message and irritants slots, `:report` = `%scheme-error-message`); `read` a
  `%scheme-read-error-condition`, whose second parent is `reader-error` (lite multiple
  parents) so `read-error?` is `(typep x 'reader-error)`. `error-object?` is `(typep x
  'condition)`: a built-in's condition is an error object whose message is its report and
  whose irritants are `()`, as in Gauche. `file-error?` is `(typep x 'file-error)`; no
  Scheme procedure opens a file.
- **Every signal is TYPED.** Raising a caught condition again goes into a fresh
  `%scheme-raise` around it, never `(error c)`: a computed designator bails
  `conditionNarrowing` and bakes the run-time error dispatch plus every condition class in
  (+60 KB class / +73 KB wasm measured on a CL probe).
- **Stated deviations** (doc pages): a guard's clauses run after the body is left and a
  clause-less fall-through raises again from the guard, so an outer handler cannot resume a
  body's `raise-continuable` (Gauche answers 11 where this is a secondary error, the
  `a-guard-reraise-...` standalone case); a guard answers its body's first value; on wasm
  an out-of-range STRING index is unchecked (`.todo/186`) -- `car` of a non-pair and a vector
  index signal like the arithmetic type errors since 2026-09-26; `eval` refuses `guard` by name
  (its keyword list), while `raise` and the rest reach `eval` through the generated table.
- The guard lowering spells its re-raise and helpers as `(raw ..)` (`CORE_RAW`), so a user
  `define` of `raise` does not capture it. `SchemeExpander` scopes the guard variable to
  the clauses.
- Cost (2026-09-18, x86-64 Linux, Java 25; `-o P.class --class-name P` / `-o p.wasm`): a
  program spelling none of these names is byte-identical (`hello.scm` 1,661 / 510 B,
  `(display (list 1 'a "s"))` 74,022 / 11,481 B, `(twice add1 5)` 79,678 / 24,993 B -- the
  pruner keys the conditions, `.kb/library-defun-pruning.md`). An uncaught-`error` program
  `(define (f x) (if (< x 0) (error "negative:" x) x)) (display (f 3))` went from
  78,423 / 22,616 to 100,023 / 41,757 B: the condition classes and their report lambdas (on
  wasm the old string message was dropped outside EH mode; a report is live code).
  `(display (guard (e (#t (list 'caught e))) (raise 'boom)))` is 104,738 / 46,989 B and the
  `with-exception-handler` + `raise-continuable` probe 105,449 / 46,888 B. The first cut --
  `handler-bind` for the handler, `(error c)` for the re-raise -- measured 168,349 /
  127,194 B and 161,939 / 120,811 B for the same two.
- Corpus (2026-09-18): no SICP sample spells a new name; `SicpCorpusE2eTest` after the
  change of `error` 5,262 legs, 0 failures, 17 skipped, the manifest unchanged.
- Pinned by the `guard-...`, `with-exception-handler-...`, `read-raises-a-read-error`
  cases of `scheme-spec.yaml` (all four backends, Gauche 0.9.15 `-r7` output), its three
  exception `standalone:` cases, `SchemeLoweringTest.aGuardIsABodyThunk...`,
  `RontoLispCliStreamsTest.theSchemeReplCatchesAndReportsRaisedObjects`.

## Parameters (`make-parameter`, `parameterize`; 2026-09-18, `.todo/867`)

**The binding is a special `let` in `scheme.lisp`, never in the lowered program.**
`rontolisp::%scheme-parameterizations` (a `defvar`) is an alist record -> value, innermost
first; `%scheme-parameterize` conses the new entries onto it and binds it with `let` around
the body thunk, so the restore is the one `.kb/dynamic-special-variables.md` gives every
special on every exit channel -- `raise` to a `guard`, a built-in error, a `call/cc`
escape, `exit`'s throw -- on all four backends, and a JVM thread sees its own. Keeping
the `let` in the helper (the `%scheme-handlers` precedent) makes the variable's specialness
a property of `scheme.lisp` alone; the interpreter loads that lazily, on the first
`%scheme-` function, so a `let` in the lowered program could be read before the `defvar`.

- **A parameter object is a closure over a `defstruct` record** (`%scheme-parameter`:
  global value, converter). `(p)` walks the alist, else the record's value. `parameterize`
  gets the record by calling the procedure with the token `%scheme-parameter-token`; only
  a parameter answers a record, so anything else is refused by name
  (`parameterize: not a parameter object: 5`). A procedure that is not a parameter IS
  called with the token -- Gauche calls it too, with no argument. A cons record would be
  forgeable by `list`, which answers `(token)`.
- Every value is converted before any is bound (a converter's error leaves all as they
  were); the converter runs on the initial value and never on the restore (R7RS 4.2.6).
- `(p v)` is refused by name (`a parameter object takes no argument: 5`); Gauche sets.
  Both refusals are `(error "~A" (%scheme-error-message ..))` like
  `%scheme-ensure-procedure`: a `%scheme-signal-error` condition cost +20 KB class /
  +17 KB wasm more on the probe below.
- `%scheme-parallel-execute` hands the alist to each thread through `make-thread`'s
  bindings, so a thread starts with the caller's values, as the sequential wasm run does.
  A `parallel-execute` program therefore keeps the `defvar` even when it makes no parameter.
- `eval` refuses `parameterize` by name (its keyword list); `make-parameter` reaches it
  through the generated table. `SchemeExpander` walks both halves of a binding as
  expressions and the body as a `<body>`.
- Cost (2026-09-18, x86-64 Linux, Java 25; `-o P.class --class-name P` / `-o p.wasm`): a
  program spelling neither name is byte-identical before and after (`hello.scm` 1,661 /
  510 B, `(display (list 1 'a "s"))` 74,022 / 11,481, `(twice add1 5)` 79,678 / 24,993,
  the guard probe 104,738 / 46,989). `(define p (make-parameter 10)) (display
  (parameterize ((p 1)) (p)))` is 82,688 / 26,714 B -- `(p)` is a variable call, so the
  right control is `twice` (the ensure-procedure message machinery): ~3.0 KB class /
  1.7 KB wasm for the parameter machinery itself.
- Pinned by the two `parameterize-...` cases and the two parameter `standalone:` cases of
  `scheme-spec.yaml` (all four backends, Gauche 0.9.15 `-r7` output except the thread
  line and the refusals), `SchemeLoweringTest.parameterizeIsTheParametersAndValuesInOrderAndABodyThunk`.

## `case-lambda` (`(scheme case-lambda)`; 2026-09-18, `.todo/869`)

**Desugared, in `SchemeLowering.caseLambda`, into a Scheme `lambda` datum with a rest
formal**, then lowered like any lambda -- nothing new reaches a backend:

```scheme
(lambda %SCM-A1
  (let ((%SCM-N2 (raw (length %SCM-A1))))
    (if (raw-predicate (= %SCM-N2 1)) (let ((x (raw (nth 0 %SCM-A1)))) body..)
        (if (raw-predicate (>= %SCM-N2 1)) (let ((x (raw (nth 0 %SCM-A1))) (r (raw (nthcdr 1 %SCM-A1)))) body..)
            (raw (%scheme-case-lambda-arity %SCM-A1))))))
```

- The keywords are the identity-compared `CORE_*` symbols and the accessors `raw`, so a
  user binding of `let`, `car` or `length` reaches neither; the temporaries are fresh
  `%SCM-` symbols no identifier spells. A clause's formals are bound by `let`, so its body
  is a `<body>`; a rest-only clause ends the chain (nothing after it is reachable), and a
  procedure with only such clauses counts nothing.
- **`definition()` desugars a `(define f (case-lambda ..))` value first**, so the
  defun-or-variable decision sees a syntactic `lambda`: defined once, `f` is a `defun`
  called directly, and a self tail call through ANOTHER clause (`((n) (f n 0))`) is a
  `selfLoop` jump with a rest carrier -- `(cl-count 100000)` in the spec case. The
  desugaring is cached per datum (`caseLambdas`, identity), so the pre-scans and the
  lowering see one set of temporaries.
- No clause accepting the count: `%scheme-case-lambda-arity`, an `(error "~A" ..)` with
  `wrong number of arguments to case-lambda: (1 2 3)` -- an error object `guard` catches,
  irritants `()` as in Gauche, whose message says `case lambda`.
- `SchemeExpander` scopes each clause like a `lambda` (formals, then a `<body>`), so a
  template's clause formal is renamed and hygienic (the `cl-opt` macro in the spec case).
- Library `case-lambda`, merged into the no-import default; `(import (scheme base))` does
  not see it (under `r7rs` a file must import it). `eval` refuses it by name (its keyword
  list already held it).
- Cost (2026-09-18, x86-64 Linux, Java 25; `-o P.class --class-name P` / `-o p.wasm`): a
  program not spelling it is byte-identical before and after (`hello.scm` 1,666 / 510 B,
  `(display (list 1 'a "s"))` 74,026 / 11,481, `twice` 79,687 / 24,993). `(define area
  (case-lambda ((w) (* w w)) ((w h) (* w h))))` with two calls is 79,014 / 23,571 B
  against 74,195 / 8,985 for the same with a dotted rest formal: the arity refusal pulls
  `%scheme-error-message`'s string-stream machinery, as the `twice` probe's
  ensure-procedure does. Time, 20M calls of a two-clause `case-lambda` against a plain
  two-argument `defun` (single runs, incl. startup): JVM 0.20 vs 0.13 s, wasm 1.09 vs
  0.26 s -- the rest list, `length` and `nth` per call. Hence the static pick below.
- None of the SICP samples spells `case-lambda` (`sicp.zip` above), so the corpus
  classification (`providedNames`) is unmoved.

**A direct call picks its clause statically** (2026-09-19, `.todo/870`). A top-level
procedure that is a `GlobalFunction` (defined once, never assigned) and whose value is a
`case-lambda` is lowered by `caseLambdaDefuns` to one `defun` per clause plus the
dispatching `defun f`:

```lisp
(defun |s%%{f 1}| (|n|) (|s%%{f 2}| |n| 0))
(defun |s%%{f 2}| (C1 C2) ...self tail calls: psetq + go...)
(defun |f| (&rest A) (let ((N (length A)))
  (if (= N 1) (|s%%{f 1}| (nth 0 A)) (if (= N 2) (|s%%{f 2}| (nth 0 A) (nth 1 A)) (%scheme-case-lambda-arity A)))))
```

- `GlobalFunction.clauses` carries the clauses, so a call through a library export picks
  them too. `call()` emits the first clause accepting the count; a count none accepts
  calls `f`, which raises the same catchable error object at run time -- NOT a lowering
  error as `(car 1 2)` is: R7RS only says "it is an error", Gauche raises it at run time
  and the spec case catches it with `guard`, and a refusal would also reject a program
  whose bad call is never reached. A rest clause is `apply`'d by the dispatch.
- The names `s%%{<name> <n>}` (prefixed by a library's `s%%(lib)`) are unforgeable the way
  internal record names `s%%[..]` are.
- A clause's self tail call is a jump when its count picks THAT clause (`Target.accepts`
  asks `GlobalFunction.clauseFor`); another count is a direct call of the other clause.
  So a CYCLE among clauses (`((n) (if (= n 0) 'pp (f n 1))) ((n k) (f (- n k)))`) would
  turn jumps into recursion: `clauses()` scans each clause body for calls headed by the
  name (scope-blind, by count) and keeps the old single dispatching lambda when the
  clause graph has a cycle through two clauses or more. `cl-pp 100000` in the spec case.
- Not split: a `case-lambda` bound by an internal `define`, `letrec` or `let` (variables
  and `funcall`, as before), and a session's (no `GlobalFunction` there).
- Measured (2026-09-19, x86-64 Linux, Java 25, wasmtime 47; 20M calls `(area (remainder i 7)
  3)` of a two-clause `case-lambda`, three runs each incl. startup): JVM 0.23 -> 0.16-0.17 s,
  wasm 0.84-0.91 -> 0.36-0.44 s; the plain two-argument `defun` is 0.15-0.17 / 0.33-0.40 s.
  Artifact 79,770 -> 77,450 B class, 24,833 -> 13,607 B wasm: nothing references the
  dispatch once every call is direct, so the tree shaker drops it and the arity refusal's
  string-stream machinery with it. Byte-identical: the 87 `scheme-spec.yaml` cases not
  spelling `case-lambda` and `examples/scheme/*.scm`, both backends; the 4 that spell it
  change (the no-matching-clause standalone +1 B wasm / +105 B class, the dispatch staying).
- Pinned by `case-lambda-takes-the-first-clause-...`, `a-library-exports-a-case-lambda-...`
  and the two `case-lambda` standalone cases of `scheme-spec.yaml` (all four backends,
  Gauche 0.9.15 `-r7` output but the message), `SchemeLoweringTest.caseLambdaIsOneRestLambda...`
  and `caseLambdaIsImportedFromItsOwnLibraryOnly`.

## Bytevectors (2026-09-18, `.todo/871`)

**A bytevector IS the `(unsigned-byte 8)` pack** (`.kb/packed-integer-vectors.md`): a
`LispIntVector` of width 8 on the interpreter, a width-headed `byte[]` on the JVM, a bare
`(array (mut i8))` on wasm. Nothing reaches a backend that a Common Lisp program cannot
already send it.

- `SchemeReader` reads `#u8(` / `#U8(` (case-insensitive like `#x`, as Gauche) into an
  8-bit `LispIntVector`, refusing a non-byte element and a dot by name. It is a datum like
  a number: `atom`, `datum` and `quoted` pass it through, so a literal is self-evaluating
  and each evaluation allocates a fresh vector (the pack's literal rule), which is what
  makes storing into a literal harmless.
- `bytevector?` is `(typep x '(simple-array (unsigned-byte 8) (*)))`; `vector?` became
  `simple-vector-p` (a packed vector is not simple). `bytevector-u8-ref` / `-length` /
  `-copy` are `aref` / `length` / `subseq` (type-preserving). `utf8->string` is
  `rontolisp:octets-to-string` (lenient: a byte that leads no valid sequence decodes to its
  own code point -- Gauche makes an incomplete string; a stated deviation).
- **Every constructor refuses a non-byte by name** (`%scheme-byte`: `bytevector: not a
  byte: 256`), and so does `bytevector-u8-set!`'s store: the pack would mask 256 to 0.
- **`bytevector-copy!` is `%scheme-bytevector-copy!`**, which copies the source region out
  first when both arguments are one bytevector: Common Lisp's `replace` copies forward on
  every backend, overlap or not (`.todo/872`).
- **The printer's `#u8(` arm and `equal?`'s vector-versus-bytevector test are behind a
  reader feature**, `rontolisp-scheme-bytevectors` (`SchemeLibrary.BYTEVECTORS_FEATURE`):
  `SchemeLibrary.process` reads `scheme.lisp` with it only when the program -- or the
  `eval` table generated for it -- can make a bytevector (`makesBytevectors`: an 8-bit
  literal, the element type `(unsigned-byte 8)`, `string-to-octets`, or a call of a
  library function that reaches one of those, the fixpoint computed over the library read
  WITHOUT the feature -- so `read`, whose reader builds one, counts, and a new
  constructor needs no list edit). The interpreter always reads with it. A bytevector a
  Common Lisp file of the same program makes, with no Scheme constructor anywhere, prints
  as `#(...)`.
- `write-shared` labels a shared bytevector (`(#0=#u8(1 2) #0#)`, as Gauche): it stays a
  printer node.
- Cost (2026-09-18, x86-64 Linux, Java 25; `-o P.class --class-name P` / `-o p.wasm`):
  every probe that cannot make a bytevector is byte-identical before and after --
  `hello` 1,666 / 510 B, `(display (list 1 'a "s"))` 74,026 / 11,481, a `write` of a list
  with a vector plus `equal?` 77,578 / 18,300, `eval` 127,288 / 81,996, the `guard` /
  `with-exception-handler` / error probes. `vector?` alone SHRINKS, 74,682 / 13,737 to
  74,670 / 13,704. `(write (read))` grows 154,937 / 94,222 to 161,652 / 99,557: it can
  read `#u8(`, so it gets the pack's `_iv*` dispatch and the printer arm. A bytevector
  program (`bytevector`, `bytevector-u8-set!`, `-ref`, `-length`, `write`) is 88,790 /
  28,829 against 78,298 / 17,462 for the same with a vector: the pack, the refusal's
  message machinery and the printer arm.
- Binary ports over bytevectors: "Ports" below.
- Pinned by the `bytevectors` case (Gauche 0.9.15 `-r7` output), the `#u8(` lines of the
  read, fold-case and strict-R7RS cases and the two `bytevector...-non-byte` standalone
  cases of `scheme-spec.yaml` (all four backends), `SchemeReaderTest.aBytevector...`,
  `LibraryDefunPrunerTest.thePrintersBytevectorArmFollowsOnlyAProgramThatCanMakeABytevector`.

## Ports (2026-09-18, `.todo/873`)

**A port is a `defstruct`, `%scheme-port`, never a bare Common Lisp stream**: the
standard streams are the `t` designator on the compiled backends (not a value,
`.kb/read-load-streams.md`), and the JVM and wasm answer a NEW wrapper instance for
every read of `*error-output*` (`(eq *error-output* *error-output*)` is NIL there,
`equal` is T; measured 2026-09-18), so neither can be told apart or compared. CL's
stream predicates do not help either: `input-stream-p` of a string OUTPUT stream is T on
all four and `open-stream-p` after `close` is T on wasm. Slots: `input`, `binary`,
`string` (made by `open-...-string`), `stream`, `open`, `pushback`, `fold-case`. A
textual port's `stream` is the CL stream it reads or writes (`t` for the standard ones);
a binary input port's is the bytevector (a copy) with the position in `pushback`; a
binary output port's the bytes written, newest first. `close-port` clears `open` (and closes a file port's stream, "File ports" below).

- **Output with a port** binds `*standard-output*` to the port's stream around the
  unchanged printer (`%scheme-display-to` & co), so the printer keeps its one shape.
  `(let ((*standard-output* t)) ..)` and `(write-string s t)` inside a
  `with-output-to-string` reach the process stdout on all four backends, and
  `(read-char t)` reads the process stdin with `*standard-input*` rebound (measured).
- **`get-output-string` writes back what it read**: CL's `get-output-stream-string`
  empties the stream, R7RS's does not.
- **The current ports are parameter objects** over three `%scheme-parameter` records
  (`%scheme-port-records`), whose converters refuse anything but an open textual port of
  the direction. `%scheme-parameterize` notes a binding of one of them and then binds the
  special it stands for (`%scheme-with-port-streams`), so `(display x)` with no port --
  and Common Lisp code called from the body -- writes where the port does, and the
  restore is the special binding's on every exit channel. Only when a port record is
  bound: a frame per level more is what took the 1,000-deep `prm-count-down` spec case
  over wasm's stack when every `parameterize` went through it.
- **Not parameterized, a current port is a cached wrapper of what the special holds
  now** (`%scheme-current-port`, compared with `equal`), so `(eq? (current-output-port)
  (current-output-port))` is `#t` and a Common Lisp caller's `with-output-to-string`
  around a Scheme procedure is honored by `(current-output-port)` too.
- **The reader's state is the port's.** With ports, `%scheme-next-char` / `peek-char` /
  `pushback` and the `#!fold-case` flag read `%scheme-reading-port` -- bound by an
  explicit port argument (`%scheme-read-from` & co), else the current input port -- so
  a peek on one port survives reads of another, and a parameterized input port shares
  its pushback with explicit reads of it (the `the-reader-state-belongs-to-the-port` and
  `the-current-input-port-mixes-with-string-ports` cases).
- **The whole port section, those reader variants, the `parameterize` hook and the
  printer's `#<textual-input-port>` arm are behind a reader feature**,
  `rontolisp-scheme-ports` (`SchemeLibrary.PORTS_FEATURE`), on for a program calling a
  function that exists ONLY under the feature (`makesPorts`: the difference between the
  two reads of `scheme.lisp`, so a new port helper needs no list edit). The interpreter
  always reads with it. **Trap**: a `#-` definition exists only in the variant a program
  gets, so `LibraryDefunPruner`'s bundled-name sets read `SchemeLibrary.everyVariantForms()`;
  with `forms()` alone the no-port reader's four `defvar`s were not known as library
  definitions and stayed as roots (+580 B class in every printing program).
- **Binary ports are over bytevectors and files.** The standard ports are textual, so
  `read-u8`/`write-u8`/`read-bytevector`... with no port argument are refused by name
  (Gauche reads/writes the byte). A port is textual or binary, never both (Gauche's are
  both: `binary-port?` of a string port is `#t` there). `u8-ready?` and `char-ready?`
  answer `#t`.
- Refusals are `(error "~A" ..)` with Scheme-spelled messages (`display: not a textual
  output port: #<textual-input-port>`, `write: the port is closed: ...`, `write-u8: not a
  byte: 256`), error objects a `guard` catches -- Gauche's texts differ.
- `eval` reaches every port procedure through the generated table; a current port's
  table value is its parameter object.
- File ports: "File ports" below. Not here: the non-R7RS `with-output-to-string` / `call-with-output-string` (no SICP sample
  spells any port name).
- Cost (2026-09-18, x86-64 Linux, Java 25; `-o P.class --class-name P` / `-o p.wasm`):
  every program spelling no port name is byte-identical before and after -- `hello`
  1,661 / 510 B, `(display (list 1 'a "s"))` 74,022 / 11,481, `twice` 79,678 / 24,993,
  `(write (read))` 161,644 / 99,557, a `read-line`/`read-char`/`peek-char` program
  90,266 / 25,303, the `guard`, `make-parameter`, bytevector and `eval` probes.
  `(define p (open-output-string)) (write (list 1 "a") p) (display (get-output-string
  p))` is 83,446 / 25,752; `parameterize` of `current-output-port` plus `(read
  (open-input-string ..))` 171,238 / 104,272; a bytevector-port round trip 96,851 /
  38,824. Time, a `read-char` loop over 2,000,000 characters of stdin with and without a
  port name elsewhere in the program: JVM 0.19 / 0.19 s, wasm 1.09 / 0.91 s (single runs
  incl. startup) -- the current-port lookup per character.
- Pinned by the seven port cases and `the-current-input-port-mixes-with-string-ports`
  of `scheme-spec.yaml` (all four backends; Gauche 0.9.15 `-r7` output but the stated
  deviations and messages), the standalone `the-reader-without-ports-reads-standard-input`
  (the no-port reader variant, which the corpus -- using ports -- no longer runs on the
  compiled backends) and `writing-to-a-closed-port-is-an-error`,
  `SchemeLoweringTest.aPortArgumentSelectsThePortHelperAndACurrentPortIsAParameterValue`,
  `LibraryDefunPrunerTest.thePortSectionFollowsOnlyAProgramThatUsesAPortProcedure`.

## File ports (`(scheme file)`; 2026-09-19, `.todo/874`)

**A file port is the `%scheme-port` record over a Common Lisp file stream**, with a
`file` slot set; everything that reads or writes a textual port works on it unchanged.

- **Each opener spells its own literal `open`** (`%scheme-open-input-file` & co in
  `scheme.lisp`): `:direction` / `:element-type` must be literal
  (`.kb/read-load-streams.md`, "Computed open options"). Output opens `:if-exists
  :supersede :if-does-not-exist :create`. The path reaches `open` as a variable, so no
  literal-path folding applies (`CompileTimePathnameFolder` folds `with-open-file` of a
  literal only): a literal `"/tmp/x"` in the Scheme source is read at RUN time
  (measured, JVM and wasm).
- **A failed open is a `file-error?` object on every backend**: the opener wraps `open`
  in `(handler-case .. (error () nil))` and raises `%scheme-file-error-condition`
  (`%scheme-error` + CL `file-error`, like the read error) with message `who: cannot open
  file:` and the path as irritant. Common Lisp's `open` / `delete-file` signal a CL
  `file-error` on all four backends now (`.kb/read-load-streams.md`), but the Scheme
  object still has to be built for its message and irritants, and the clause stays
  `error`, not `file-error`: a `--no-wasi` module stubs `open` to a plain
  "requires WASI" error (`NoWasiFilesystemStubs`), which must still become a
  `file-error?` object here.
- **Closing really closes**: `%scheme-release-port` `close`s the stream of an open file
  port, from `close-port` & co and `call-with-port`. `with-input-from-file` /
  `with-output-to-file` are `%scheme-with-file`: `%scheme-parameterize` of the port
  parameter (so `(current-output-port)` IS the file port and Common Lisp code writes
  there too) inside an `unwind-protect` that closes the file on every exit channel --
  Gauche leaves it open (and unflushed) on an escape. It answers the thunk's values, so
  it is in `SchemeValueCount.PASSING_HELPERS`. `call-with-input-file` /
  `call-with-output-file` are `call-with-port` over the opener: closed on return only, as
  R7RS says.
- **Binary file ports**: `binary` and `file` set; `read-u8` & co test `file` first and
  go to `%scheme-file-read-u8` (`read-byte`, the peeked byte or EOF in `pushback`),
  `write-u8` / `write-bytevector` to `write-byte`. `get-output-bytevector` refuses a file
  port. `read-bytevector` from a file builds its result through `%scheme-bytevector`, so
  the bytevector feature's fixpoint counts it -- computed with the files feature only for
  a program that has file ports (`SchemeLibrary.process`), so no other program's
  bytevector decision moves.
- **`file-exists?` is `(probe-file f)`, no helper**, so a program asking only that pulls
  in no port machinery. `delete-file` is `%scheme-delete-file`: CL `delete-file` in a
  `handler-case`, a file error on failure.
- **Everything file-specific is behind a third reader feature**, `rontolisp-scheme-files`
  (`SchemeLibrary.FILES_FEATURE`): the openers, the condition, the `file` slot itself and
  the file arms of `close-port`, `call-with-port`, the binary procedures and
  `get-output-bytevector`. `makesFiles` is `makesPorts`' derivation one feature up
  (functions defined under ports+files minus ports); a file program implies the ports
  feature. The interpreter always reads with all three.
- **An output file port left open keeps its output on all four backends**, as in Gauche:
  the interpreter and the JVM flush the stream table where the program ends, wasm writes
  through `fd_write` (`.kb/read-load-streams.md`, "Output left open at the end").
- The spec cases build every path under `/tmp` from `(random 1000000000)` -- a literal
  path would never test the preopen resolution, and two runs of the corpus at once (two
  worktrees) must not share a file; the driver's wasm legs pass `--dir /tmp`. A wasm
  program run without a preopen covering the path gets the file error (errno path).
- Cost (2026-09-19, x86-64 Linux, Java 25; `-o P.class --class-name P` / `-o p.wasm`):
  every program spelling no `(scheme file)` name is byte-identical before and after --
  `hello` 1,666 / 510 B, `(display (list 1 'a "s"))` 74,026 / 11,549, the string-port
  probe 83,882 / 25,913, the bytevector-port probe 98,075 / 39,604, a `parameterize` +
  `read` + `close-port` + `call-with-port` probe 186,878 / 124,918, `(write (read))`
  175,526 / 118,935, `eval` 128,025 / 82,618, a `file-error?` probe 106,447 / 47,759.
  `(write (file-exists? "/tmp"))` 76,240 / 12,927 (the printer); `with-output-to-file` +
  `display` 116,440 / 52,235; a `call-with-output-file` / `call-with-input-file` round
  trip 128,454 / 58,277; a binary round trip 115,834 / 51,186.
- Pinned by the `file-ports-write-then-read`, `with-output-to-file-restores-the-current-port`,
  `binary-file-ports` and `a-failed-open-is-a-file-error` cases of `scheme-spec.yaml`
  (all four backends; Gauche 0.9.15 `-r7` output but the deviations their comments state),
  `SchemeLoweringTest.importsSelectWhatIsVisible`,
  `LibraryDefunPrunerTest.theFileSectionFollowsOnlyAProgramThatUsesAFileProcedure`.

## Libraries and include (`define-library`, `include`; 2026-09-19, `.todo/882`)

**A library is a file lowering of its own whose top-level names are PRIVATE symbols, and
an export is the binding itself.** `SchemeLowering.instantiate` lowers the library body
with a child `SchemeLowering` (its own global `Scope` from its own `import`s, its own
expander, prefix `SchemeNames.libraryPrefix`), and the importer's `importSet` puts the
exported `Binding` objects into its scope under the external names -- so a `defun`
stays a direct call in the importer, a record predicate stays fused, an exported
variable is read live (the library's `set!` shows). No backend learns anything.

- **Private names**: every top-level name a library defines -- `defun`, variable,
  `defstruct` type/slots/constructor/predicate/modifier, and every `fresh` temporary
  (a macro's hidden global, a record's anonymous slot) -- is `s%%(a b)` + the mangled
  name. No identifier mangles to it (an escaped one continues `s%` with `%%`, `%c` or a
  non-`%`; no identifier holds a parenthesis), and the space/`)` keep `(a b)` apart from
  `(a)`. So a program's `helper` never collides with a library's, two libraries never
  collide, and the same library lowered twice emits the same names. Symbol names with
  a space and parentheses compile on the JVM and wasm (checked by the spec cases).
  Stated deviation: a library's record prints those names (`#S(s%%(p)point ...)`).
- **Where a library comes from** (`SchemeLibraries`, one per top-level lowering, shared
  by the nested ones): the leading `define-library` forms of the importing file or
  session buffer (`declareLibraries`, before the `import`s -- Gauche -r7 accepts that),
  else `a/b.sld`, else `a/b.scm`, relative to the directory of the file the lowering
  STARTED from (`libraries.root()`), for a library's own imports too, like a load path.
  Every `define-library` in a found file is declared. A cycle is refused with the chain.
- **Lowered once per lowering, emitted in dependency order**: `instantiate` lowers on
  the first import and parks the forms in `SchemeLibraries.pending`; a nested library's
  `leave` runs before its importer's, and `lower()` / `interact()` drain them right after
  the `#f` binding. **Once per PROGRAM** across separately lowered files (a Common Lisp
  file loading two `.scm` files that import one library -- inlined on the compile path,
  loaded at run time on the interpreter): definitions stay bare top-level forms (the
  backends hoist only a direct child), the statements -- variable initializations,
  expressions, `initialValues` -- run behind `(defvar s%%(a)%SCM-INSTANTIATED nil)` and
  one `(if flag nil (progn (setq flag t) ...))` (`instantiationGuarded`). A library of
  definitions alone has no flag. The statements move after the library's definitions;
  that only changes a library that calls a procedure before defining it (an error in
  Scheme).
- **Exports** (`exports`): an identifier or `(rename internal external)`, resolved in the
  library scope AFTER its body is lowered; a name neither defined nor imported, or
  exported twice, is a positioned error. A macro is exported too ("Exported syntax"
  below).
- **Importer rules**: `set!` of an imported library variable is refused (R7RS 5.6.1) in
  both standards; a `define` over a library import wins under `rontolisp` (only the
  importer's name changes: the library keeps calling its own) and is refused under
  `r7rs`, like a builtin (`libraryImports`, an identity set). A name read before such a
  redefinition becomes a variable holding the import (`readBeforeDefinition`). Importing
  one name from two libraries is not refused (the later wins; Gauche is silent too).
- **Library scope**: no `import` declaration sees everything under `rontolisp` (like a
  program) and is refused under `r7rs` when the library has a body. Declarations:
  `export`, `import`, `begin`, `include`, `include-ci`, `include-library-declarations`,
  `cond-expand` (the taken clause's declarations, in place); anything else is "unknown
  library declaration". A
  library is always lowered in FILE mode, also when a session imports it.
- **`include` / `include-ci`** (`includes`/`included`): a datum pre-pass BEFORE macro
  expansion -- so an included definition or `define-syntax` is seen by every pre-scan
  and by the expander's gate -- that replaces each `(include "f"...)` whose head the
  global scope maps to the keyword with `(CORE_BEGIN datums...)`, recursively, relative
  to the file the `include` is written in; `quote`/`quasiquote` are not entered. It is
  scope-blind below the top level, like `collectAssigned` (a local named `include` would
  still include). `include-ci` reads with the reader's fold-case flag set. Datums that
  include nothing are returned as the SAME objects, so a program without `include`
  lowers byte-identically. `SchemeExpander.resolve` reads an identity `CORE_*` symbol as
  its keyword (the include's `begin` enters the expander). An `include` a macro expands
  into reaches the lowering and is refused by name.
- **Positions**: an included file or a library file gets its own `SchemeReader`; the
  program's reader keeps the included ones (`SchemeReader.other`) and consults them in
  `locate`/`inherit`, so `sub/bad.scm:2:3: malformed if` names the included file.
- **Files** come through `scheme/SchemeFiles` (`find(from, path)`, `null` for none):
  `eval/SourceLanguage.schemeFiles` adapts the loader the reading site already has --
  `SourceLoader.fileSystem()` on the compile path's entry read, the `LoadInliner`'s
  loader for an inlined file, the evaluator's `sourceLoader()` for `RontoLispCli` and
  run-time `load`, a filesystem loader for the REPL (relative to the working directory).
  A read with no loader (`SchemeFiles.NONE`) refuses every file by name. The files are
  read at lowering time, so a compiled program carries their contents.

Measured (2026-09-19, x86-64 Linux, Java 25): every program that spells neither
(`hello`, the seven `examples/scheme/*.scm`, the concatenated `scheme-spec.yaml` corpus and
its 20 standalone cases) compiles to byte-identical `.class` and `.wasm` before and after
(56 of 56 artifacts). The `libraries-in-the-program-file-...` spec program (two libraries,
a record, an instantiation flag) against the same program written inline: 82,143 vs
81,755 B of class (+388), 26,555 vs 26,508 B of wasm (+47) -- the flag and the longer
symbol names; a library procedure is a direct call, so no run-time cost per call.
Oracle: Gauche 0.9.15 (`gosh -r7 -I.`) prints the same for both spec cases.

Pinned by `SchemeLibrariesTest` (the emitted forms, the errors, a session), the two
`libraries-...` standalone cases of `scheme-spec.yaml` (all four backends, both standards;
the `files:` field writes a case's other files beside it),
`RontoLispCliTest.aSchemeProgramReadsItsLibraryFilesAndIncludesBesideItOnEveryPath` (CLI
interpreter, `-o`, once-per-program across two loaded files, the REPL) and the reference
pages' `; file: NAME` blocks (`DocExamplesTest`).

## Exported syntax (2026-09-19, `.todo/883`)

**An exported macro is the library expander's own `Macro` -- its `syntax-rules` and its
DEFINITION `Env` -- and a free template identifier reaches the importer's lowering as the
library's `Binding` itself, through a generated identifier.** No datum form of the macro
is re-read by the importer, so nothing about the library has to be spelled twice.

- **Export**: `exports` asks `SchemeExpander.exportedMacro(name)` FIRST (a
  `define-syntax` shadows an import of the name, as the expander resolves it) and wraps
  it in the lowering's `ImportedSyntax` binding. `importSet`'s `only`/`except`/`prefix`/
  `rename` move it like any binding; a library that imports a macro re-exports it by the
  plain `global.find`. It joins `libraryImports`, so strict R7RS refuses a `define` over it.
- **Every `Env` knows its lowering** (`Env.host`, the `Host` of the expander whose global
  it descends from). Resolution walks the frames as before; then, at the root, it asks
  THAT host: a keyword, else an imported macro (`Host.importedMacro`), else -- only when
  the root is another lowering's -- that lowering's top-level binding (`Host.binding`,
  its `lookup`). A top-level `Variable` found in another lowering's global frame is that
  lowering's spelling and is translated the same way. The answer is the `Imported`
  meaning.
- **`Imported` is emitted as `Host.foreign(binding)`**: a fresh generated `%SCM-L<n>`
  the importer puts in its global scope bound to the library's `Binding` object (one per
  binding per lowering). So a private `defun` is a direct call to `s%%(lib)name`, a record
  predicate stays fused, a builtin is the builtin whatever the importer did to its name,
  and a variable is read live. A template may `set!` the library's variable (Gauche
  agrees): `variableSymbol` exempts a foreign identifier from the "imported from a
  library" refusal. It cannot assign a library procedure that the library itself never
  assigns -- that is a `defun`, "not a variable in this file".
- **Aliases are shared by every expander of one top-level lowering**
  (`SchemeLibraries.aliases`, `SchemeExpander.Aliases`): the importer strips and keys
  aliases a library's own expansion created.
- **The gate**: `SchemeExpander.needed` also fires for a program spelling a name bound to
  an imported macro; nothing else changes for a program that imports none. A session runs
  a buffer's top-level `import`s BEFORE expanding it (`sessionImport`, again after, as
  before), so `(import (m)) (m-macro ...)` typed at one prompt expands.
- **`exit` by another name**: `mayThrowExit` decides by spelling; a template's `exit`
  reaches the importer as `%SCM-L<n>`, so `foreign` raises `foreignExitOrEval` for the
  `exit` and `eval` builtins and the file's forms take the exit guard.
- **Stated deviation**: a template identifier its library neither defines nor imports
  resolves FREE, i.e. by its spelling where the macro is used (Gauche: unbound variable).
  An error case only, unless the name is a Common Lisp function another file defines,
  which the free spelling still reaches; resolving it to "unbound" would need a binding
  kind every `case null` of the lowering learns.

Measured (2026-09-19, x86-64 Linux, Java 25): every program that imports no macro
compiles to byte-identical `.class` and `.wasm` before and after -- `hello`, the six
`examples/scheme/*.scm`, the concatenated `scheme-spec.yaml` corpus and its standalone
cases under every standard each lists (78 of 78 artifacts). Oracle: Gauche 0.9.15 (`gosh
-r7 -I.`) prints the same as all four backends for the `a-library-exports-syntax-...` case
under both standards.

Pinned by `SchemeLibrariesTest` (`anExportedMacro...`, `importSetsSelectRenameAndPrefixAMacro`,
`aLibraryReExportsAMacroItImports`, `aStrictR7rsProgramMayNotRedefineAnImportedMacro`,
`aSessionImportsALibraryThatExportsSyntax`), the `a-library-exports-syntax-hygienically`
standalone case of `scheme-spec.yaml` (all four backends, both standards) and the
`define-library` reference page (`DocExamplesTest`).

## `cond-expand` (2026-09-19, `.todo/892`)

**Decided while LOWERING, so a clause not taken never reaches a backend, and a feature is
only one every backend shares.** `SchemeFeatures.FEATURES` -- `r7rs exact-closed
ieee-float full-unicode ratios rontolisp`, R7RS appendix B's order -- is what
`(features)` answers (`%scheme-features`, generated into `Scheme.runtimeForms` so the list
is spelled once) and what a feature identifier is tested against. Checked on all four
backends (2026-09-19): `(/ 1 3)` is `1/3`, `(expt 2 100)` exact, a string holds a code
point above U+FFFF as one character, flonums are doubles. Never an OS, processor or
backend name: the lowering is shared by the four backends and a compiled program runs
elsewhere. `exact-complex` is absent (no complex numbers). Gauche 0.9.15 lists ~140
features (`gauche`, `srfi-N`, `posix`, ...); a program testing those takes its `else`.

- **Requirements** (`SchemeFeatures.clause`): an identifier, `(and ..)`, `(or ..)`,
  `(not x)`, `(library name)`, compared by NAME; a last `else`. `(library name)` holds
  when an import would find it (`SchemeLowering.libraryAvailable`): `(scheme <tag>)` of
  `IMPORTABLE_LIBRARIES` (not `r5rs`, `time`, ...), a library declared already, or one
  whose `.sld`/`.scm` file declares it -- that declares the file's libraries, as an import
  would, without lowering them. **No clause taken and no `else` is a positioned error**
  (Gauche: "Unfulfilled cond-expand"; R7RS: unspecified). A non-last `else`, a malformed
  clause or requirement: positioned errors.
- **Where it is taken**, four places, one choice:
  1. The leading declarations (`resolveTopLevelCondExpand`): `declareLibraries` and
     `imports` replace a top-level `cond-expand` at the index they scan by the taken
     clause's datums, by SPELLING (no scope exists before the imports, as for `import`).
     So a clause may hold `import`s (an R7RS program may begin with one, after its first
     `import` for Gauche, whose initial module knows no `else`) or `define-library`
     forms, and a `(library ..)` there sees the leading libraries above it. A session
     does the same per buffer datum.
  2. The `include` pre-pass (`includes`/`included`), by scope: every other occurrence, top
     level, body and expression alike, becomes `(CORE_BEGIN body..)`, recursively.
     `spliceBegins` / `spliceBodyBegins` then splice definitions; an empty clause at the
     top level is nothing, in a body an empty `(begin)` (the body lowering accepts a
     definition after it). Quoted data is not entered, nor a `syntax-rules` (so an unused
     template is never decided).
  3. `SchemeExpander.headExpanded` / `keywordForm`: a `cond-expand` a macro expanded into.
     The requirements are decided on the STRIPPED form (a feature is a name; an alias
     keeps its spelling), the body keeps its aliases.
  4. `SchemeLowering.syntax`'s `COND_EXPAND` arm: what no walk enters -- a quasiquote's
     unquoted expression.
  As a library declaration, `libraryDeclarations` recurses into the taken clause.
- **`eval`** takes one at run time (`%scheme-eval-cond-expand`, `%scheme-eval-feature-p`
  in `scheme.lisp`): the same features, `(library (scheme <tag>))` through
  `%scheme-library-p`, no user library (eval has none). The arm is behind the reader
  feature `rontolisp-scheme-cond-expand`, selected by `SchemeLibrary.process` only for a
  program that SPELLS `cond-expand` (a symbol, quoted data included, or inside a string)
  -- the line the procedure table draws -- because it costs +2,455 B class / +1,900 B
  wasm on every `eval` program (measured on a two-line `eval` program). Any other `eval`
  keeps its bytes and still answers "Not supported inside eval" for a constructed one.

Measured (2026-09-19, x86-64 Linux, Java 25): every program that spells no `cond-expand`
compiles to byte-identical `.class` and `.wasm` before and after -- `hello`, the seven
`examples/scheme/*.scm`, the concatenated `scheme-spec.yaml` corpus and its standalone
cases, each under every standard it lists (74 of 74 artifacts; before the `eval` arm was
gated, the ten artifacts of the five `eval` programs among them grew by about that
figure). A program with a
`cond-expand` compiles to exactly the bytes of the program spelling the clause it takes
(checked on the `(define (third x) (cond-expand ...))` reference example).

Pinned by `SchemeCondExpandTest` (each place, `library`, the errors, a session),
`SchemeLibraryTest` (the `eval` arm's gate), the `cond-expand-...` case and standalone
case of `scheme-spec.yaml` (all four backends, both standards, a library file whose
declarations are `cond-expand`s; Gauche 0.9.15 prints the same but for its own
feature names) and the reference pages (`DocExamplesTest`).

## A session (`SchemeSession`, `SchemeLowering.interact`)

`rontolisp --source-language scheme` with no file; reached through `eval/SourceSession`
(`.kb/source-language.md`). The global `Scope`, the temporaries counter and the `set!`
names outlive each buffer; everything else is per buffer.

- **Every top-level definition is a variable** (`setq`, later calls `funcall` it), never a
  `defun`: no pre-scan can see the forms still to be typed. Its own recursive calls are
  `funcall`s too, not the trampoline. The interpreter's `evalCons` applies
  `(funcall closure ...)` itself instead of through the `funcall` built-in (keeping its
  handler-bind seam): that built-in was two Java frames per Lisp call, 14 against a
  `defun`'s 13 (12 now), and a non-tail `count` in a session overflowed between 7,000 and 7,500
  against a file's 9,000-9,500 (default `--stack`, 2026-09-17). After it both pass 9,000;
  JIT state moves either edge by a few hundred. Since `.todo/912` (2026-09-19) that
  application is a tail call of `evalCons`'s loop: a session's `(self self ..)` runs in
  constant stack like a file's, and a non-tail call costs two Java frames
  (`.kb/interpreter-tail-calls.md`).
- **Each definition also emits a trampoline**, `(defun f (&rest a) (apply f a))`. A form
  typed BEFORE `f` existed lowered `(f x)` as a direct call ("a name this file does not
  define"), and the trampoline is what that call reaches -- reading the variable on every
  call, so it follows `set!` and redefinition. Without it, `ev?`/`od?` typed at two prompts
  fail with an undefined function (measured 2026-09-17).
- **Stateless is not enough**, which is why this is a session and not a lowering mode: a
  later buffer must know `point?` is a record predicate (a raw `T`/`NIL` in an `if` test is
  always true to Scheme) and that `f` is a variable in VALUE position. A record
  procedure stays a direct call and cannot be `define`d over; the whole
  `define-record-type` may be typed again.
- A procedure's self tail calls stay a loop (a file makes it a `defun` with the same loop;
  without it 1,000,000 iterations overflow the stack) unless the session has assigned the
  name SO FAR. Stated deviation: a `set!` typed later does not reach a saved old value,
  which keeps jumping to itself. Likewise a form is lowered against the names known when
  it was typed: shadowing a built-in later does not reach it.
- `(setq false '|#f|)` is emitted once, by the first buffer that lowers. `(import ...)` is
  accepted anywhere at the top level and only ADDS names (an R7RS REPL starts with
  everything visible; base and write are).
- Echo: `SchemeTopLevel.echoes` is false for a definition, an import and a record type --
  forms with no value. Everything else is decided by the VALUE: `SourceSession.echo` skips
  the unspecified object, whatever expression answered it (a syntactic rule on the head
  missed every procedure of the user's own ending in `display`). A session lowers its
  top-level forms in value context, a file in a discarding one. Values print through
  `%scheme-write`, CALLED on the value (`LispEvaluator.printThrough`) -- never quoted into
  a form, which the package resolver walks before evaluating and never finishes on a
  cyclic value (the Common Lisp echo with a `print-object` method had the same bug).
  Continuation is "the reader ran out of input"
  (`LispReadException.isEndOfFile`), so `#;`, `#| |#` and `#\(` need no second rule.
- **One typed datum is one `SchemeTopLevel`, however many forms `spliceBegins` turns it
  into** (2026-09-19, `.todo/909`): a top-level `begin` -- typed, or a macro's template --
  must still splice so its definitions land at the top level, but `interact` groups the
  spliced forms of ONE datum into a single entry (echoing only the last, the others
  statement-guarded) instead of one entry per spliced form. `SchemeExpander.topLevel`
  already splices a `begin` into its queue while expanding, so the grouping is carried
  by `topLevelGrouped`, tagging each queued form with which input datum it came from.
  Before this, `cli/ReplBuffer`'s per-step fresh-line ran once per spliced form, so
  `(begin (display 3) (display 4) 5)` printed `3`, `4` and `5` on three lines instead of
  `34` then `5` (the `(progn (princ 1) (princ 2))` shape the Common Lisp REPL uses).
- **Applying a non-procedure** reports the condition's own text at the REPL, as file mode
  and every compiled backend do: `The object is not applicable: 3`, `...: #f` and
  `...: #!unspecific` -- the same text `%scheme-eval-apply` reports, built by
  `%scheme-error-message` through the Scheme printer. Every lowered combination whose
  operator is not a known procedure goes through `%scheme-ensure-procedure` (a
  `functionp` check; `.todo/851`, 2026-09-18): a variable call, a computed operator,
  a literal `#f`/`#t` operator, and the `apply`/`map`/`for-each`/`call/cc`/
  `dynamic-wind`/`call-with-values` templates' procedure arguments. A Scheme
  application applies a PROCEDURE value, never a symbol designator, so a symbol --
  `#f` included -- is not an `undefined-function` about a name. No backend learns a
  Scheme name: the message is built in `scheme.lisp`, the backends only carry it.
  The REPL used to reword it (`#f is not a procedure; operands: (2 3)`, 2026-09-17,
  removed the same day): a rewording only the interpreter REPL could produce split one
  failure into two texts ([error-handling.md](error-handling.md), "Applying a value
  that names no function"). Pinned by `RontoLispCliStreamsTest.theSchemeReplReportsANonProcedureAsAFileDoes`
  and the `standalone:` cases of `scheme-spec.yaml` (all four backends).
- **Cost** (2026-09-18, x86-64 Linux, Java 25): a program with no indirect call is
  byte-identical (`hello.scm`: 1,665 B of class / 510 B of wasm before and after).
  `(display (twice add1 5))` (two variable calls) goes from 75,135 to 79,717 B of
  class (+4,582) and 13,660 to 25,027 B of wasm (+11,367): the first pull of
  `%scheme-error-message`'s `with-output-to-string` string-stream machinery (a
  program already using Scheme `error` or `eval` pays only the ensure defun and its
  call sites). Time: a 2M-iteration Scheme variable-call loop 0.127 vs 0.134 s on
  the JVM (real, incl. startup, single runs -- in the noise, as the backend check
  of [error-handling.md](error-handling.md) was); 20M iterations 0.150 s after.
  The marking alternative (backend classes reserved symbols as non-functions) was
  not taken: it would still need the Scheme printer at the signal point to name
  `#f` as `#f` rather than `|#f|`, i.e. a backend that learns Scheme printing, for
  no size win over the one helper every indirect call already shares with `eval`.

## The shared REPL loop (`cli/ReplBuffer`), both languages

**A terminal is a person, a pipe is a script.** `RontoLispCli.repl` calls it a terminal when
`in` is `System.in` and `System.console()` is non-null and `isTerminal()` (a test forces
either with `assumeTerminal`). On a terminal: a prompt per fresh form, `Error:` on stdout
between the prompts, status 0. On a pipe: no prompt at all (it was one per form AND per
blank or comment line, `scheme> scheme> scheme> 25`), `Error:` on stderr after flushing
stdout, and status 1 at end of input when any form failed -- what a file's uncaught error
gives. JLine is used only on the real system terminal. `LispExitSignal` escapes the loop
(`(exit n)`, `(uiop:quit n)`: it used to be caught as `Error: null` and the session went on)
and its status wins over an earlier failure. A malformed buffer (`(+ 5 6) garbage)`) is
reported whole and nothing in it runs: the buffer is read before it is evaluated (pinned).
SICP corpus (`.todo/artefacts/828-sicp-sample-corpus-harness/`, 2026-09-17): file mode 1,307 exit 0 before and
after, one stdout difference (a timing print); REPL transcripts no longer contain a prompt.

## Destination-driven lowering (`SchemeLowering.lower`)

`lower(expr, Context)` answers an EXPRESSION when the context has no `Destination` and a
STATEMENT when it has one: a statement stores its value in the destination's result
variable or jumps to a `Target` label. Tail-transparent forms (`if`, `begin`, the `let`
family, bodies) pass the destination down; every other form is a leaf `(setq R value)`.

- A named `let` is first tried as a PURE loop: its name is a `LoopName` binding, and any
  use other than a jump -- a non-tail call, a call from inside a `lambda`, a bare
  reference -- sets `escaped`, discarding the attempt for the procedure shape. Optimistic
  re-lowering is exponential in the nesting depth of NON-pure named lets, which is small.
- A procedure with a known name (`defun`, a never-assigned `letrec`/`define` variable
  bound to a syntactic `lambda`) is tried as a self loop only when its body mentions a
  call to the name; if no call turned out to be a tail call the plain shape is used, so
  `fib` carries no `tagbody`.
- **Loop variables are assigned in place (`psetq`)** -- the shape the backends' typed
  loops recognize. **A loop whose body creates a closure rebinds per iteration** from
  carrier variables, `(tagbody L (let ((i C)) ...))`, because each iteration's closure must
  capture ITS binding. Detected by counting the `lambda`s the body lowering emitted, not by
  scanning for the word `lambda` (an `(import (prefix ..))` renames it).
- **So does a loop jumped to from where a loop variable is shadowed**
  (`(let ((count (+ count 1))) (fill (cdr path) count))`, an inner named `let` reusing the
  outer's variable name): a variable keeps its Scheme spelling, so the in-place `setq`
  there assigned the INNER binding and the loop never advanced. `Target.shadowedFrom`
  compares the jump site's binding of each name with the loop's; a mismatch re-lowers the
  loop in the carrier shape, whose fresh names nothing can shadow. Found by
  `examples/scheme/collatz.scm`.
- Mutual tail calls among top-level procedures, among a body's internal definitions and
  among a `letrec`'s lambdas are jumps too ("Tail-call groups" below); higher-order ones
  are ordinary calls (depths below).
- **A leaf stores ONE value** (`(setq R value)`), so a leaf that may answer other than one
  value leaves the loop instead: `(return-from B form)`, B the loop's `block`, named after
  R (`%SCM-B<n>` for `%SCM-R<n>`, never from the counter, so a discarded loop attempt
  renumbers nothing) and emitted only when some leaf uses it. Inner loops share the outer
  one's (`Exit`, the `Destination`'s third component). Which forms: `SchemeValueCount` --
  a user procedure call (its name always has a lower-case letter), `funcall`/`apply`/
  `values-list`, a `values` of other than one argument, a helper in `PASSING_HELPERS`,
  through `if`/`let`/`progn`/`cond`/`and`/`or` tails; a `cl` function answers one value.
  `PASSING_HELPERS` is pinned against `scheme.lisp` by `SchemeValueCountTest` (a
  conservative tail walk of every helper; a new helper handing on a user procedure's values
  fails it until listed). Pinned end to end by scheme-spec `multiple-values`.
- Measured (2026-09-19, x86-64 Linux, Java 25, wasmtime 47) against the list carrier the
  `(values ..)` leaves used before (`(setq R (multiple-value-list form))`, `(values-list R)`):
  30M calls of a 4-iteration loop exiting through a call every time, JVM 350-400 / 330-365 /
  360-450 ms (plain / list / block, noise), wasm 1.27-1.46 / 1.61-1.78 / 1.31-1.46 s,
  interpreter (300K calls) 5.7-6.2 / 6.3-6.8 / 6.1-6.4 s; a 300M-iteration integer loop
  exiting through a call, no difference on any backend (the typed loops survive the
  `block`). The `return-from` is a jump on both compiled backends and a stackless
  `BlockReturnSignal` on the interpreter; the list carrier would also pull `values-list`
  into the wasm (+3.9 KB). Blast radius: 65 of 1,586 SICP corpus files and 1 of 6
  `examples/scheme` (`evaluator.scm`, +262 B of class, +8 B of wasm) change; every
  changed leaf is a user procedure call, `funcall` or `apply`. The rest lower byte-identically.

## Tail-call groups (`SchemeLowering.declareGroups`; 2026-09-19, `.todo/897`)

**Top-level procedures of a file whose TAIL calls to each other form a cycle are one
`defun`, and every tail call among them is a jump.** `ev?`/`od?`, a state machine, SICP's
`eval`/`apply`/`eval-sequence` all run in constant stack on the four backends. Nothing
outside such a cycle changes: a program with none lowers byte-identically.

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

- **Members**: `GlobalFunction`s without `case-lambda` clauses -- defined once by a
  `lambda`, never `set!`, not read before definition. A `case-lambda` procedure keeps its
  own clause loop (`.todo/870`); a variable procedure is not a member.
- **Finding them**: a cheap scan of call heads (blind to scope, like `collectAssigned`)
  gives candidate cycles (Tarjan); each candidate is then lowered once as a PROBE with every
  member a jump `Target`, and what the lowered bodies JUMP to -- `(go own-label)`, `(setq ..
  W j ..)` -- is the real tail-call graph. A candidate the jumps do not hold together is
  settled again as the cycles they do form. The probe restores the counter and every
  memo it touched (`Snapshot`), so a program whose candidate turns out to be no cycle
  numbers its temporaries exactly as before (pinned).
- **Shape**: the carriers are shared by position (as many as the widest member); every
  member rebinds its variables from them per entry, so a jump is sequential `setq`s of
  carriers and a closure captures its own entry's binding (the `fresh` loop shape). A
  jump to ITSELF goes to its own label; a jump to another member sets `W` too and goes
  to `TOP`, which is the one entry of every cycle (`Target.presets`). The group's
  `defun` stands at the first member's `define`; the others emit only their entry.
- **Where the time went (measured before the shape was fixed).** `(eql W 1)` in the
  dispatch is a generic call on the JVM: the metacircular evaluator (`evalfib`: the
  `examples/scheme/evaluator.scm` evaluator running `(fib 27)`) ran 29-36% slower until
  it became `(= W 1)`. The members as an `if` chain under `TOP` (no `(go END)`) ran it
  15-17% slower on the JVM; a stub per member setting `W` cost wasm a dispatch round per
  jump. Final shape against the plain defuns (2026-09-19, x86-64 Linux, GraalVM 25.0.4,
  wasmtime 47, a loaded 64-core box, best of 5-7 alternating runs):

  | program | JVM | wasm | component | interpreter |
  |---|---|---|---|---|
  | `evalfib` | 1043 -> 1088 ms | 1937 -> 2017 | 1956 -> 2006 | (`fib 24`) 31.1-33.9 -> 36.5-38.7 s |
  | 10M shallow `(ev? (remainder k 4))` | 242 -> 228 | 271 -> 389 | 286 -> 334 | 37.8 -> 73.8 s |

  The shallow row is the worst case -- a trivial body entered 10M times: on wasm it is
  the tagbody's dispatch loop entering a function that had none (a hand-written group of
  the same two bodies measured the same); on the interpreter every `go` inside a form is
  a thrown `GoSignal` (a `go`-step costs 1.75x a call-step there, the same price every
  self loop already pays), which is also the evaluator's +16% there. `.todo/901` is the
  interpreter half: memoizing the label table per `tagbody` form alone bought nothing
  measurable, so it was not kept.
  **Since `.todo/901` (2026-09-19) a `go` in a tagbody statement's tail is not thrown**
  (`.kb/do-return-block.md`), which is every jump these groups and loops emit. Interpreter,
  same programs, one loaded 64-core box, alternating runs, whole process: 10M shallow
  77.1-90.6 -> 43.4-44.2 s; `evalfib` (`fib 24`) 39.7-42.2 -> 32.4-35.1 s -- back at the
  plain defuns' 37.8 s and 31.1-33.9 s above, measured on a quieter box.
- **Depth, default stacks** (before -> after): `ev?`/`od?` JVM 3,516 / wasm and component
  10,780 / interpreter 10,230 -> 1,000,000 on all four; `evalloop` (the evaluator running
  a 1,000,000-iteration interpreted loop) overflowed on all four, now answers `done`.
- **Blast radius**: of 1,586 SICP corpus files and the six `examples/scheme`, 20 SICP
  files (the chapter-4.4 query system, the chapter-5.5.7 compile-and-go) and
  `evaluator.scm` change; each prints the same on all four backends before and after.
  Common Lisp programs never reach this pass.
- **Not members** (ordinary calls): a tail call through a procedure VALUE (an argument,
  a `lambda` in a variable, `apply`), and every definition typed at the REPL (a session's
  definitions are variables; internal definitions inside one are groups like anywhere
  else). On the two wasm targets such a call is proper anyway: the backend emits every
  call in tail position as `return_call` and the dispatcher tail-calls its target
  (`.kb/wasm-tail-calls.md`, 2026-09-19, `.todo/899`), so the lowering's `(funcall
  (%scheme-ensure-procedure f) ..)` runs in constant stack there -- the check returns
  before the call. So is the interpreter's since `.todo/912`: `evalCons` applies the
  closure a `funcall`/`apply` names in its own loop frame (`.kb/interpreter-tail-calls.md`).
  On the JVM it uses stack (depths below).
- **Not a trampoline.** A hand-written trampoline (a tail call answers a bounce, every
  non-tail call site drives them) measured, against plain calls: `fib 32` JVM 43-45 vs
  47-56 ms, wasm 85-107 vs 58-72, interpreter 12.2 vs 5.5 s; 3M shallow `ev?`/`od?` calls
  JVM 720-914 vs 39-60 ms (15x), wasm 1.58-1.78 s vs 73-81 ms (20x), interpreter 122 vs
  7.6 s (16x). Not acceptable as a general mechanism on any backend, and on wasm
  `return_call` made it moot. The JVM has no counterpart (`.todo/899` measured its
  options: a Scheme call through a value is two JVM frames, `g` and `_invoke_2`; a larger
  stack for compiled output's `main` raises the ceiling 1,844 -> 17,677 at 16 MiB and is a
  Common Lisp-wide change of every emitted `main` -- landed the same day as the sized-main
  launcher, `.todo/911`, `.kb/interpreter-stack.md`). The interpreter's loop
  in `eval`, the non-trampoline shape, landed 2026-09-19 (`.todo/912`,
  `.kb/interpreter-tail-calls.md`): a tail call through a value is proper there too, and
  the loop is faster than the recursion it replaced (`fib 32` 4.72-5.26 -> 4.49-4.68 s,
  `evalfib` on `(fib 24)` 32.6-38.6 -> 25.2-26.3 s).

Pinned by `SchemeLoweringTest.topLevelProceduresWhoseTailCallsFormACycleAreOneGroupEachEntersAtItsLabel`,
`#aCycleThroughANonTailCallIsNoGroupAndNumbersNothing`, `#anAssignedOrRedefinedProcedureIsNoMember`
and the `top-level-procedures-whose-tail-calls-cycle-run-in-constant-stack` case of
`scheme-spec.yaml` (all four backends; Gauche 0.9.15 `-r7` prints the same).

### Internal groups (`SchemeLowering.internalGroups`; 2026-09-19, `.todo/898`)

**The same probe runs on every body and every `letrec`**: the internal `define`s bound to a
syntactic `lambda` (and the `letrec` bindings whose init is one) whose tail calls cycle are
one group, `lowerGroup` with the body's scope as home. The group is a `lambda` in a fresh
variable of the body's `let`, assigned where the first member stands; each member's variable
gets a `lambda` entering it:

```
(let ((ev? nil) (od? nil) (G nil))
  (setq G (lambda (W C1) (let ((R nil)) (tagbody TOP .. L0 .. L1 .. END) R)))
  (setq ev? (lambda (n) (funcall G 0 n)))
  (setq od? (lambda (n) (funcall G 1 n)))
  ..)
```

- **Members**: never `set!` (by spelling, like `collectAssigned`), bound once in the body,
  not also bound by a `define-values`, no `case-lambda`. A member used as a value is its
  variable's entry `lambda` -- no escape rule is needed, since nothing but the jumps inside
  `G` ever bypasses the variable. A jump to a member whose `define` has not run yet runs it
  anyway; R7RS calls reading that variable before its definition an error.
- **Measured before building (the todo's premise)**: 0 of the 1,586 SICP corpus files and 0
  of the six `examples/scheme` have such a cycle (a static scan of tail positions over
  every body and `letrec`, `.todo/artefacts/828-sicp-sample-corpus-harness/internal_tail_cycles.py`; 252 bodies define local procedures, 82 of them tail-call a
  sibling, none back). Built anyway: R7RS requires proper tail calls, and a local state
  machine is a normal Scheme shape. Blast radius: all 1,592 files compile to
  byte-identical `.class` and `.wasm` before and after (the probe restores the counter).
- **Depth** (`(parity 1000000)` over internal `ev?`/`od?`): overflowed on all four
  backends before (the depths of a procedure value, below); `#t` on all four now.
- **Time** (2026-09-19, x86-64 Linux, Java 25, wasmtime 47, a loaded 64-core box, best
  of 5 alternating runs; before -> after): 10M `(parity (remainder k 4))`, JVM 475 -> 249
  ms, wasm 799 -> 678, component 777 -> 678; 300K `(parity 100)`, JVM 324 -> 176, wasm
  651 -> 250, component 646 -> 253. Faster, unlike the top-level groups: the calls it
  replaces were `funcall`s through `%scheme-ensure-procedure`, not direct `defun` calls.
  Interpreter (best of 3): 300K shallow 4.1 -> 5.8 s, 30K `(parity 100)` 5.5 -> 9.6 s --
  every jump was a thrown `GoSignal` there. After `.todo/901` (same day, no throw for a
  tail `go`; before -> after, same box): 300K shallow 5.35-5.72 -> 3.98-4.56 s, 30K
  `(parity 100)` 8.81-10.40 -> 4.94-5.89 s, i.e. the plain procedures' cost again.
- **Size**: `G` takes one argument more than the widest member, and on both compiled
  backends the first indirect call of an ARITY pulls every dispatchable function of that
  arity (`_invoke_N`, `.kb/core-representation.md`). The shallow program grew 50,117 ->
  56,756 B of class and 26,185 -> 28,840 B of wasm, where nothing else called through a
  value with two arguments; with such a call already present (`(f a b)` once) 56,681 ->
  56,931 / 28,494 -> 28,929.

Pinned by `SchemeLoweringTest.internalProceduresWhoseTailCallsFormACycleAreOneGroupLambdaEachMemberCallsIt`,
`#internalProceduresWithNoTailCycleLowerAsBeforeAndNumberNothing` and the
`internal-procedures-whose-tail-calls-cycle-run-in-constant-stack` case of `scheme-spec.yaml`
(all four backends; Gauche 0.9.15 `-r7` prints the same).

## Traps

- **A helper whose tail is a Common Lisp operator with a second value answers it too.**
  `%scheme-string->symbol` ended in `intern`, so `(call-with-values (lambda ()
  (string->symbol "a")) list)` was `(a ())` and the REPL echoed a stray `()` after any
  form that called it. It answers `(values (intern ...))` now
  (`SchemeBuiltinsTest.stringToSymbolAnswersOneValue`); a helper ending in `floor`,
  `gethash`, `intern` and the like needs the same `values`.

- **`(setq false '|#f|)` is emitted unconditionally**, first: the helpers in `scheme.lisp`
  read the variable, and the interpreter's lazy load is keyed on FUNCTION resolution.
- A template parameter used more than once (`symbol?`, `square`, `floor`) binds a
  non-atomic argument to a temporary; substitution never touches the operator position or
  a `quote`/`function` form. No parameter may be named `t`/`nil` (the table is read by
  `LispReader`; `SchemeBuiltinsTest` pins it).
- `#'apply` is not a function value on the compile path ("Cannot compile: APPLY"), and the
  comparison function values are BINARY: first-class `apply` spreads through
  `%scheme-spread`, first-class `<`/`char<?`/`string<?` walk `%scheme-chain`.
- Generated temporaries are UPPERCASE `%SCM-<kind><n>`, which no escaped user identifier
  can spell; the counter is per file, so emission is deterministic (three compiles of the
  corpus: one `.class` hash, one `.wasm` hash).
- A syntax error is a positioned `LispReadException` on BOTH paths: `SchemeReader` keeps
  its own cons -> offset map, because `SourceProvenance` records on the compile path only.
  Lowered conses also `inherit` their datum's position (`.kb/source-positions.md`).
- **The interpreter loads `scheme.lisp` through `evalResolved`, not the bare
  `eval(form, globalEnv)` its neighbours use**: `%scheme-error-message` rebinds
  `*standard-output*`, and only a form whose special bindings were registered
  (`SpecialVarCollector.collectForm`) binds it dynamically for its callees. Loaded bare,
  the message went to stdout and the report named the exception class. Any other lazily
  loaded library that starts rebinding a special needs the same entry.
- The front end changes no Common Lisp program's output: the whole `ci-spec.yaml` corpus
  (7.3 MB of `.class`, `.wasm` and component) compiled with the jar before and after
  differs in 3 bytes, the build timestamp (2026-09-17).
- Found through this corpus, fixed in the backends, not worked around here: parallel `let`
  (`.kb/parallel-let.md`) and `(+)`/`(*)` with no arguments.

## Measurements (2026-09-17, wasmtime 47, x86-64 Linux, Java 25)

**`#f` representation** -- 100,000,000 `if` tests, wasm / JVM seconds, artifact bytes:

| `#f` is | wasm | JVM | `.wasm` | `.class` |
|---|---|---|---|---|
| an inline `'\|#f\|` | 4.56 | 0.27 | 3225 | 9573 |
| a global holding the symbol (chosen) | 1.48 | 0.28 | 3248 | 9635 |
| a global holding a `defstruct` singleton | 1.32 | 0.27 | 3672 | 10363 |
| (reference: NIL, a native test) | 1.48 | 0.24 | 3094 | 9180 |

A quoted symbol costs a lookup per evaluation on wasm; the global costs nothing over a
native NIL test. The singleton buys unforgeability the name escaping already gives, for
+424 / +728 bytes and quoted data that would have to be built at run time.

**Loop shape** -- 300,000,000 iterations of a two-variable loop, wasm / JVM seconds:

| shape | wasm | JVM |
|---|---|---|
| in place, `psetq` (chosen) | 1.97 | 0.80 |
| per-iteration `let` from carriers (only under a closure) | 2.41 | 0.73 |
| variables pre-bound to nil, copied in at the label | 8.31 | -- |
| (reference: CL `do`) | 1.64 | 0.83 |

The nil-initialized shape loses the integer typing of the loop variables: 4x.

**Tail-call depth that is NOT a loop**, default stacks, largest passing depth (2026-09-19,
binary search): a tail call through a procedure VALUE, `(define (g self n) (if (= n 0) 'done
(self self (- n 1))))`, JVM (`java Prog`) 1,716-1,844 (17,677 under `-Xss16m`; 16,201 since the
compiled `main` runs on a 16 MiB worker, `.kb/interpreter-stack.md`), wasm and
component 2,693-2,975 before `return_call` and 5,000,000 (the probe's ceiling) after
(`.kb/wasm-tail-calls.md`), interpreter 15,234-15,497 before the loop in `eval` and
5,000,000 after (`.kb/interpreter-tail-calls.md`). Top-level `ev?`/`od?` was JVM 3,516
/ wasm 10,780 / interpreter 10,230 before the tail-call groups and is unbounded now, and
so is an internal or `letrec` pair.

**Size.** `(display "hello, world")` is 1,594 B of class and 498 B of wasm: `display` of a
string, character or integer LITERAL lowers to `write-string` / `write-char` / `princ`.
The generic printer (`%scheme-print`) costs 43.1 KB of class and 8.4 KB of wasm
(`(define x (list 1 2)) (display x)`, 2026-09-19). It writes a symbol's spelling character by
character instead of building it: with `(coerce list 'string)` in that path the same program
was 70.2 KB / 17.5 KB.

**Where the JVM bytes went (2026-09-19, `.todo/894`).** The printer measured 74,048 B of class
against 8,505 B of wasm, and the old per-piece reading (`aref` 14 KB, a `do` loop 9 KB, `char`
7 KB, `write-char` 6 KB) was marginal cost inside that program, not the pieces': alone, each is
6.8 / 7.3 / 8.2 / 2.4 KB over an empty program, the shared generic-arithmetic and prin1 helpers
counted once. 31 KB of the 74 was the JVM eval runtime (`_eval`, `_invoke_v`, `_store`, the
registered wrapper lambdas), switched on by the funcall in `%stream-target` -- a prelude entry
rooted because DEAD `scheme.lisp` helpers spell `with-output-to-string` / `*error-output*`
(`.kb/library-defun-pruning.md`, synthesized-call entries). Both halves are fixed at their
source: the entry is rooted by live forms only, and on the JVM a computed funcall no longer
forces the runtime and an apply gets the apply tier (`.kb/eval-runtime.md`). Class bytes
before -> after, outputs identical: `(display x)` 74,048 -> 43,141; `examples/scheme`
collatz 101,066 -> 71,056, queens 90,597 -> 67,381, differentiation 112,884 -> 96,530,
huffman 113,542 -> 97,093, streams 99,692 -> 83,814, evaluator 121,905 -> 106,169. Wasm moved
only for the printer (-100 B, the resolver); a no-printer program (`hello.scm`) and
`size-report/programs/hello_world` / `pi_approx` are byte-identical. What is left of the 43 KB
is the printer's own defuns (`%scheme-print-flonum` alone 3.4 KB of code) and the runtime
`princ` renderers its fallback arm reaches.

**Cycles in `write`/`display`** (R7RS: must terminate; label what a cycle closes on,
`#0=(1 2 . #0#)`). `(define x (list 1 2)) (display x)`, class / wasm bytes, and writing a
50,000-element list of `(i #(i "s"))` ten times, JVM / wasm seconds:

| printer | class | wasm | 50k x10 |
|---|---|---|---|
| no cycle check (before) | 58,786 | 3,914 | 6.99 / 10.41 |
| eq hash table of seen nodes | 66,322 | 20,014 | -- (one 50k write: 0.40 / 62.3) |
| tree walk first, list of seen nodes only when it cannot finish (chosen) | 65,534 | 8,468 | 7.49 / 11.10 |

An eq hash table costs wasm 16 KB (4.3 KB of it only for VECTOR keys) and, since wasm has
no identity hash, puts every aggregate key in one bucket: quadratic. The chosen shape
walks the datum as the tree `write` prints -- never more work than the printing -- and
calls it cycle-free when the walk ends. It gives up on a cdr chain meeting itself
(Brent) or car/element nesting past 1,000, where any cycle through a car must end up;
only then `%scheme-mark-cycles` runs, over an alist: quadratic, but only for a cyclic or
over-deep datum. `(* power 2)` in Brent's step cost 468 wasm bytes over `(+ power power)`.

**`write-shared` labels every shared node, not only cycles** (2026-09-18,
`.todo/840`): `%scheme-write-shared` counts occurrences in one depth-first walk
beside the cycle walk -- a revisit of an open node (a cycle) or a closed one
(sharing) counts without recursing, so the walk ends on a cycle and visits each
node once -- and reuses the `(entries . next-number)` label shape, with an entry
`(node . 4)` for every node seen more than once. Only the outermost shared node
takes a label, as R7RS's own example does: `(write-shared (list x x))` is
`(#0=(1 2) #0#)`, nested sharing `(#0=(#1=("s") #1#) #0#)`, a shared vector
`(#0=#(1 2) #0#)`. `write` and `display` are untouched. A program calling only
`display` compiles byte-identical before and after (80,121 B of class / 11,636 B of
wasm for `(display (list 1 'a "s"))`, `-o Disp.class --class-name Disp` /
`-o disp.wasm`, both times -- the pruner drops the three new helpers with the
`write-shared` template that alone references them); a program that writes shared
structure SHRINKS, 80,177 to 78,376 B of class and 9,230 to 8,018 B of wasm for
`(define x (list 1 2)) (write-shared (list x x))` (the counting walk is smaller than
the Brent pre-walk plus the mark-cycles pass it no longer pulls).

## Not here yet (each its own follow-up)

The other
libraries, re-entrant continuations, tail calls through a procedure value on the JVM (proper on both
wasm targets, `.kb/wasm-tail-calls.md`, and on the interpreter,
`.kb/interpreter-tail-calls.md`). Each is refused by name where it can be.

## The SICP sample corpus harness (`.todo/828`)

`SicpCorpusE2eTest` (opt-in: `-Drontolisp.sicp=<unpacked sicp.zip>`) runs every
sample in file mode on all four backends against the interpreter, gated by the
checked-in `src/test/resources/sicp-manifest.tsv` (category + expected outcome per
file; the static rule of `baseline.py` ported into the test, which fails on any
drift from the manifest instead of silently re-gating).

Corpus (2026-09-18, `sicp.zip` sha256
`476c0904386f02db5cebfa8a11410e13e0d3fb92da513c376e50c6a16873d8fe`, 1,586 `.scm`):
`scheme` 1,181, `concurrent` 19, `fragment` 286, `embedded-query` 52,
`embedded-amb` 27, `embedded-lazy` 4, `js-import` 13, `unreadable` 4. Interpreter
exits 0 on 1,347 (every `scheme`/`concurrent` file but two that behave as the book
says: `chapter1/section1/subsection6/26.scm` never terminates under applicative
order, `chapter3/section3/subsection5/05_set_value_example_2.scm` ends in
`(error "Contradiction" ...)`). File mode and REPL mode agree on every file. Of
the 1,347, the JVM, wasm Preview 1 AND the component print byte-identical stdout
on 1,333; the rest are 2 `scheme` timing prints (`(runtime)` wall-clock, matched
modulo the seconds) and 12 `fragment`s the compile backends refuse (a global
VARIABLE nothing defines is a compile error where the interpreter only fails if
the path runs -- the harness classes them as fragments by decision). The
`chapter1/section1/subsection8/02.scm` transcendental digits print identically on
wasm (fdlibm): no tolerance, no exception. The `; expected:` annotations (450
files) are a positive check only: 158 agree with the REPL echo, no disagreement
is a wrong value (the annotation names another expression, the file ends in a
definition, or the book's digits are stale -- e.g. `02_sine_example.scm`, where
IEEE arithmetic answers `0.9999996073961978`).

`embedded-*` stay excluded for a measured reason, one per family: the corpus
does not ship a complete evaluator. The amb evaluator lacks its `(amb? exp)`
dispatch clause and `define-variable!`, and no stream support is unwirable under
`ambeval` (a `(cons-stream a b)` under it looks up an unbound operator -- the
stream-using interactions cannot run there without evaluator surgery). The lazy
evaluator lacks its `eval` dispatch, `eval-sequence`, `eval-definition`, the
whole expression-syntax layer AND `define-variable!` (2026-09-18,
`.todo/856`: the earlier "only `define-variable!`" note did not survive
measurement -- no corpus file defines `self-evaluating?`, `variable?`,
`quoted?`, `cond->if`, `define-variable!` or `analyze-quoted` either). The query
system lacks its whole syntax layer (`assertion-to-be-added?`,
`query-syntax-process`, ... -- no file in the corpus defines them). Feeding them
to the evaluators' `driver-loop`s over stdin is follow-up work once those pieces
exist.

SICP stage 3 (2026-09-18, `.todo/856`): the amb family's stream-free samples run
as driver legs (`SicpCorpusE2eTest#ambDrivers`, legs listed in
`src/test/resources/sicp-amb-drivers.tsv`) -- 19 of the 20 exit 0 on all four
backends with byte-identical stdout to the interpreter (the first value the book
prints: `(1 a)`, the `(sentence ...)` parses, `ok` for the defines-only legs).
The composition is one corpus core file
(`chapter4/section3/subsection3/16_driver_loop_amb.scm`, minus its trailing
`(driver-loop)` call) over six corpus support files, closed by the
harness-written `src/test/resources/sicp-amb-glue.scm` (the syntax layer the
corpus never ships, `define-variable!`, `analyze-quoted`,
`analyze-sequence`, the `amb?`/`let?` advice on `analyze` -- the samples use
`let`, which the corpus evaluator has no clause for --
`apply-primitive-procedure` over the host `apply`, since the corpus's
`apply-in-underlying-scheme` is not provided, the driver prompts, a driver loop
with the EOF clause the book loop lacks, the global environment with the extra
primitives the samples need, and the replacing `(driver-loop)` call); stdin per
leg is the two corpus prelude files (`require`, `an-element-of`) then the sample
to EOF. The EOF clause (rather than an `(exit)` terminator) keeps the legs
in-process on every backend -- on the JVM an `(exit)` inside the evaluated
program is `System.exit`, which kills the test's own fork. Still excluded, with numbers:
the 7 stream-using interactions (a `(cons-stream a b)` under `ambeval` looks up
an unbound operator) and `03_office_move.scm`, which does find the book answer
`((baker 3) (cooper 2) (fletcher 4) (miller 5) (smith 1))` but needs ~256 MiB of
host stack on the interpreter (64 MiB still overflows) where the CLI hands every
program 16 MiB. The lazy 4 and query 52 stay excluded for the reasons above.

## Tests

`SchemeSpecE2eTest` over `scheme-spec.yaml` (one case per table row and per procedure
group, all four backends in `./mvnw test`; the wasm legs need `wasmtime` on `PATH`),
`SchemeLoweringTest` (the table as emitted forms), `SchemeSessionTest` (what a session emits,
when a buffer is complete), `SchemeReaderTest`, `SchemeNamesTest`,
`SchemeBuiltinsTest` (every `:function` evaluates, every helper a template names exists),
`SchemeLibrariesTest` (`define-library` / `include`), `SchemeCondExpandTest`, `SchemeLibraryTest` (which programs
get the printer's vertical-line arm and `eval`'s `cond-expand` arm),
`RontoLispCliTest` (`aSchemeFileIsPickedByItsExtension`, `aCommonLispProgramLoadsASchemeFile`,
the `theSchemeRepl...` transcripts, the
first of which replays its input as a FILE and compares, `aPipedReplWritesNoPromptForEitherLanguage`),
`RontoLispCliStreamsTest` -- the ones that read standard error --
(`aSchemeSyntaxErrorNamesItsPositionOnEveryPath`, `aSchemeProgramIsRefusedByTheScalarBackend`,
`anUncaughtSchemeErrorReportsItsMessageAndIrritants`,
`aSchemeTranscendentalWithAComplexAnswerIsRefusedByName`, `anErrorInsideSchemeEvalIsReportedInSchemeTerms`,
the `theSchemeRepl...` transcripts that report an error, `aCyclicValueIsEchoedWithoutKillingTheSession`,
`aTerminalReplPromptsOncePerFreshForm`,
`aPipedReplReportsFailuresOnStandardErrorAndEndsNonZero`, `exitEndsTheSessionWithItsStatusInEitherLanguage`),
`SchemeSpecE2eTest.exitEndsTheProcessWithItsStatusOnEveryBackend` (one program per status, each asserting the `after` thunk ran; the JVM
leg in a child process, since `exit` there is `System.exit`; plus `emergency-exit`
legs asserting it did not),
`DocExamplesTest` (a ```` ```scheme ```` fence is a
whole program whose stdout is asserted; one with a `; =>` runs through a REPL session and
each annotation is the form's echo; a whole-program block runs through `PlaygroundRepl.run`,
what the site's Run cell runs), `PlaygroundReplTest` (the browser's REPL and cells in
Scheme), `SchemeReferenceTest` (the reference, below).
Probes behind the first version of this table: `.todo/artefacts/825-minimal-experimental-scheme-front-end/`.

## The reference (`doc/<lang>/scheme/reference/`, `.todo/860`)

One page per name `Scheme.providedNames()` answers -- every `SchemeBuiltins` entry and
constant and every `SchemeLowering.syntaxNames()` keyword, 250 on 2026-09-18 -- under one
`_catalog.yaml` (`label: Scheme`, so a search hit on `car` says which language's page it
is). A category is the library the name is REALLY exported from (Gauche 0.9.15's
`module-exports` is the oracle): `exact->inexact`/`inexact->exact` under `(scheme r5rs)`
and the `read-char` family under `(scheme base)`, as the `SchemeBuiltins` tags say since
`.todo/857`; the keywords of `(scheme base)` (and `import`, which no library exports) are the
`Syntax` table, `delay`/`delay-force` sit with `(scheme lazy)` and `cons-stream` with the
SICP names. Table pages are `syntax.md` and `library-<name>.md`, never a bare library
name: `read`, `write` and `eval` are procedure slugs in the same directory (docgen refuses
a detail page at an index page's path).

`SchemeReferenceTest` pins it: the catalog's names equal `Scheme.providedNames()` in both
trees (a builtin added without a page fails there), the trees list the same entries in the
same order, and every table row's example is on its detail page -- with its result as the
`; =>` annotation when the result is code -- inside a ```` ```scheme ```` block
`DocExamplesTest` checks. **A new Scheme builtin, constant or keyword needs a detail page,
a catalog entry and a table row in both trees.** A detail page states behavior and every
deviation in Scheme terms; how a name is lowered stays here.

