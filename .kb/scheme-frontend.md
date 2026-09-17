# The EXPERIMENTAL Scheme front end (`am.ik.rontolisp.scheme`)

**Invariant: a Scheme program is read, desugared and LOWERED to the Common Lisp core forms
the pipeline already consumes; no backend learns a Scheme name.** There is no IR beneath
those forms to target instead: both backends dispatch on the canonical operator NAME and
`FreeVarAnalyzer` is a hand walker over the same names, so an unknown head would be a
silent three-way divergence. Status is experimental -- partial R7RS-small conformance by
design, no compatibility promise -- and every user surface says so (`--source-language`
help, the title of `doc/*/guides/scheme.md`). `--no-gc` is refused by name
(`CompileFrontend.run`): it has no cons cell, no symbol and no closure.

## Where it sits

- `scheme` depends on the AST types and `reader` only: `SchemeReader` (text -> datums, its
  own case-sensitive reader), `SchemeLowering` (datums -> core forms), `SchemeBuiltins`
  (the procedure table), `SchemeNames` (identifier escaping), `Scheme` (the facade).
- Reached ONLY through the seam: `eval/SourceLanguage.SCHEME`, picked for `.scm` or by
  `--source-language scheme` (`.kb/source-language.md`). Per FILE, so a Common Lisp file
  may `(load "lib.scm")` and call its procedures as `(|name| ...)`.
- The run-time half is Common Lisp source, `eval/scheme.lisp`, handled the `UrlLibrary`
  way by `eval/SchemeLibrary`: the interpreter loads it on the first resolution of a
  `rontolisp::%scheme-` FUNCTION, `CompileFrontend.expand` splices it innermost (beside
  `TokenizersLibrary`, INSIDE the prelude whose string comparisons it uses) and
  `LibraryDefunPruner` drops what stays unreachable. The playground's chain has the splice
  too; a `.scm` reaches the playground through `(load ...)` of an uploaded file.
- A whole FILE is lowered at once: defun-or-variable is decided by a pre-scan. A REPL has
  no whole program to scan and lowers through a session instead ("A session" below).

## The lowering table

| Scheme | lowers to | why |
|---|---|---|
| identifier `foo` | symbol `foo`, verbatim | canonical names are upcased, so a name with an ASCII lowercase letter can never hit a `LispNames` case label |
| `CAR`, `X`, `T`, `+` (no lowercase), `a:b`, `&rest`, `s%...`, `#f` | escaped: `s%` + name, `%`->`%%`, `:`->`%c` | `(defun \|CAR\| ...)` REPLACES the built-in; `a:b` is "symbol b of package a" to the resolver ("No such package: a"); `&rest` is a lambda-list keyword; the prefix and `#f` keep the map injective and the false value unforgeable. The rule is spelled twice (`SchemeNames.mangle`, `%scheme-needs-escape` in `scheme.lisp`) -- change both |
| `'()` | `NIL` | `&rest` lists, `apply` and every list primitive end in it |
| `#t` | `T` | |
| `#f` | the VALUE of `rontolisp::%scheme-false`, the symbol `\|#f\|`, bound by the first form of every lowered file | distinct from NIL; a symbol so quoted data, `case` and `equal?` need nothing special |
| the unspecified value: an `effect` builtin, `set!`, the missing arm of `if`/`when`/`unless`, a `cond`/`case` with no clause taken, `(begin)` | the VALUE of `rontolisp::%scheme-unspecified`, the symbol `\|#!unspecific\|`, bound in the same first `setq`; `(progn effect U)` -- but the raw effect / `NIL` where the value is DISCARDED (`Context.discarded`: a body form before the last, a file's top-level form) | ONE object, so a REPL can skip it by value (`(define (g) (display "a"))` echoed `"a"`); not `NIL` (`(list (if #f #f))` has length 1) and true in a test. The spelling is escaped by `mangle` like `#f` and excluded by `symbol?`. Cost (2026-09-17): +93 B class / +26 B wasm per program (`hello`), nothing measurable on a 100M-iteration `when` loop. A missing arm is spelled `CORE_UNSPECIFIED` in desugarings |
| `exit`, `emergency-exit` (`(scheme process-context)`, merged into the no-import default) | `%scheme-exit`: finish both output streams, then `%host-exit` with `#t`/none 0, `#f` 1, an integer's low 8 bits | the `uiop:quit` primitive (`.kb/uiop.md`), so all four backends end the process where the call stands -- `exit` does NOT run pending `dynamic-wind` afters either (stated deviation) |
| `(if c a b)` | `(if (eq c false) b a)`; a predicate fuses: `(if (pair? x) ..)` -> `(if (consp x) ..)`, `and`/`or`/`not` compose | `SchemeBuiltins.Result`: `pred` (T/NIL) and `or-false` (value or NIL: `memq`, `assq`, `member`) fuse in a test and convert anywhere else -- `(if raw t false)` / `(or raw false)` |
| top-level `(define x v)` | top-level `(setq x v)` | NEVER `defvar`: that makes the name special and a `let` of it leaks into callees (2 instead of 1) |
| top-level procedure defined ONCE by a `lambda`, never `set!` | `defun`, called directly | keeps the direct call and the tree shaker. `set!` is collected by name, blind to scope: over-approximating only costs the direct call |
| any other procedure binding | variable + `funcall`; a procedure name in value position -> `#'name` | Lisp-1 over Lisp-2 (`.kb/lisp2-namespaces.md`). A known procedure's first-class value is its `:function` form in `SchemeBuiltins` |
| a name this file does not define | call position: a direct call; value position: a variable | what lets a Common Lisp file and a Scheme file call each other |
| `(lambda args ..)`, `(lambda (a . r) ..)` | `&rest` | the dotted formals are parsed here: `LambdaLists.parse` reads through `toList()` and DROPS an improper tail |
| named `let` / `do` whose name is only tail-called; a self tail call | `tagbody`/`go`, every `go` in STATEMENT position | destination-driven lowering (below) |
| named `let` whose name escapes; `letrec`; internal `define` | bind to nil, `setq` a `lambda`, `funcall` | the shape `labels` itself lowers to (`.kb/flet-labels.md`), so `labels` would buy no direct call |
| `cond` `case` `and` `or` `when` `unless` `do` | desugared to `if`/`let`/`begin`/named `let` first | spelled with identity-compared `CORE_*` symbols: `(define (f if) (or if 1))` still gets the real `if` |
| `call/cc` | `block` + a closure doing `return-from` (`%scheme-call/cc`) | escape-only, one-shot; crosses lambdas through `CrossLambdaExitLowering` |
| `dynamic-wind` | `before`, then `unwind-protect` | the exit half runs on every exit channel; re-entry does not exist |
| `(call-with-values (lambda () ..) (lambda (a b) ..))`, `let-values`, `define-values` | `multiple-value-bind` | the syntactic tier (`.kb/multiple-values.md`). Any other shape: `(apply consumer (multiple-value-list (funcall producer)))`. A loop's `(values ..)` result survives the `setq` into the result variable because `values` publishes through `%mv-spill` |
| `define-record-type` (top level only) | `defstruct` with `(:conc-name nil)`, each slot NAMED after its accessor, a BOA constructor; the modifier a `defun` over `(setf (accessor r) v)` | `defstruct` is what registers the instance layout on every backend (`.kb/defstruct.md`); the accessor IS the generated one, no wrapper call. The predicate answers T/NIL and is a `pred` (`GlobalPredicate`) |
| `quasiquote` | `cons`/`append`/`(coerce .. 'vector)`, constant parts quoted | depth-counted per R7RS: the innermost unquote of a nested template IS evaluated |

`symbol?` excludes `T`, `NIL` and the false value; `boolean?` is `#t`/`#f` only; `vector?`
excludes strings (`vectorp` does not); `integer?` accepts `2.0`; `max`/`min` are inexact
when any argument is; `equal?` is its own helper (recurses into vectors, `eqv?` on
records; CL's `equal` compares a general vector by identity and an instance slot-wise).

## The library tags: `base`, `write`, `inexact`, `cxr`, `lazy`, `process-context` and `sicp`

`SchemeBuiltins` entries carry the R7RS library that exports them. `base`, `write`,
`inexact`, `cxr` (the whole `(scheme cxr)` set, `caaar` through `cddddr`: every one a
standard Common Lisp function of the same name), `lazy` and `process-context` are
`SchemeLowering.IMPORTABLE_LIBRARIES`: `(import (scheme <tag>))` names them, and a file
with no import at all merges all six. Keywords carry a library too: `SYNTAX` is `base`,
`LAZY_SYNTAX` (`delay`, `delay-force`) `lazy`, `SICP_SYNTAX` (`cons-stream`) `sicp`.
`sicp` (`true false nil` -- via `SchemeLowering.Constant`, not an `Entry`, since they are
values, not procedures -- `filter reduce fold-left fold-right delete last-pair append!
list-index 1+ -1+ random runtime`) is no R7RS library, so no import names it:
`SchemeLowering.imports()`'s no-import branch merges it too, so a file with no import at
all (an unqualified SICP sample, or a REPL) sees it anyway, and an explicit import list
narrows to exactly what it names (`.todo/829`
measured 1,251 -> 1,307 of the 1,592-file SICP sample corpus running to exit 0 in file
mode from this alone, zero regressions -- `.todo/artefacts/828-sicp-sample-corpus-harness/`
has the harness). A user `define` of any of these still wins, exactly like `square`:
`SchemeLowering.declareGlobals` overwrites the global scope entry for any name the file
defines regardless of what library put there first.

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
  `+nan.0` print; READING them is still refused.
- `-0.0` is read with `Double.parseDouble` (`BigDecimal.doubleValue()` dropped the sign),
  and `string->number` negates after converting.
- Cost (2026-09-17): `(display (list 1 'a "s"))` went from 58,745 to 72,993 B of `.class`
  (generic fixnum-fusion helpers for the digit arithmetic, `princ-to-string`); the `.wasm`
  grew 14 B, because the type-test fold drops the float arm of a program that makes no
  float. `(display 42)` is unchanged.
- Corpus (2026-09-17, `.todo/artefacts/828-sicp-sample-corpus-harness/run.py`): file mode
  1,307 -> 1,314 samples exiting 0, no regression; no sample still fails on an inexact
  name.

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
  JIT state moves either edge by a few hundred.
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
- **Applying a non-procedure** is reported by `SourceSession.describe` as
  `#f is not a procedure; operands: (2 3)`. The interpreter's `apply` throws
  `eval/LispApplyException` (a `LispEvalException` with the SAME message and condition
  class Common Lisp saw -- `The function #f is undefined` for a symbol designator,
  `Not a function: 3` otherwise -- plus the value and the evaluated arguments), found
  through the cause chain because the handler-bind seam may wrap it. Interpreter REPL only:
  file mode keeps the Common Lisp wording, and the compiled backends fail differently
  again (a `ClassCastException` on the JVM, a trap on wasm), for Common Lisp too.

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
- Mutual and higher-order tail calls are ordinary calls (depths below).

## Traps

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

**Tail-call depth that is NOT a loop** (`ev?`/`od?`), default stacks: JVM (`java Prog`)
passes 2,000 and overflows at 5,000; interpreter, wasm and component pass 10,000 and fail
at 100,000 (`StackOverflowError` / `call stack exhausted`).

**Size.** `(display "hello, world")` is 1,594 B of class and 498 B of wasm: `display` of a
string, character or integer LITERAL lowers to `write-string` / `write-char` / `princ`.
The generic printer (`%scheme-print`) costs 58.8 KB of class and 7.1 KB of wasm. It writes
a symbol's spelling character by character instead of building it: with
`(coerce list 'string)` in that path the same program was 70.2 KB / 17.5 KB.

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
`write-shared` labels only cycles, like `write`.

## Not here yet (each its own follow-up)

`syntax-rules`/`define-syntax` (a shadow-aware walk like `substituteSymbolMacros`),
`define-library`, `guard`/`raise` (onto `handler-case`), `parameterize` (the special-`let`
restore), bytevectors (the `(unsigned-byte 8)` pack), ports beyond the current output port
(`%STREAM` instances), `eval`, `(scheme char)` and the other libraries, `|...|`
identifiers, reading `+inf.0`/`+nan.0`, internal `define-record-type`, re-entrant continuations,
proper tail calls in general. Each is refused by name where it can be.

## Tests

`SchemeSpecE2eTest` over `scheme-spec.yaml` (one case per table row and per procedure
group, all four backends in `./mvnw test`; the wasm legs need `wasmtime` on `PATH`),
`SchemeLoweringTest` (the table as emitted forms), `SchemeSessionTest` (what a session emits,
when a buffer is complete), `SchemeReaderTest`, `SchemeNamesTest`,
`SchemeBuiltinsTest` (every `:function` evaluates, every helper a template names exists),
`RontoLispCliTest` (`aSchemeFileIsPickedByItsExtension`, `aCommonLispProgramLoadsASchemeFile`,
`aSchemeSyntaxErrorNamesItsPositionOnEveryPath`, `aSchemeProgramIsRefusedByTheScalarBackend`,
`anUncaughtSchemeErrorReportsItsMessageAndIrritants`,
`aSchemeTranscendentalWithAComplexAnswerIsRefusedByName`, the `theSchemeRepl...` transcripts, the
first of which replays its input as a FILE and compares, `aCyclicValueIsEchoedWithoutKillingTheSession`,
`aPipedReplWritesNoPromptForEitherLanguage`, `aTerminalReplPromptsOncePerFreshForm`,
`aPipedReplReportsFailuresOnStandardErrorAndEndsNonZero`, `exitEndsTheSessionWithItsStatusInEitherLanguage`),
`SchemeSpecE2eTest.exitEndsTheProcessWithItsStatusOnEveryBackend` (one program per status; the JVM
leg in a child process, since `exit` there is `System.exit`),
`DocExamplesTest` (a ```` ```scheme ```` fence is a
whole program whose stdout is asserted).
Probes behind the first version of this table: `.todo/artefacts/825-minimal-experimental-scheme-front-end/`.
