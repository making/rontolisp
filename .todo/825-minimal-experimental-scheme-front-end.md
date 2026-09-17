# A minimal, experimental Scheme front end: enough of R7RS `(scheme base)` to smoke every backend

Difficulty: High

Needs `.todo/824` (the source-language seam) first.

Goal: `rontolisp hello.scm` runs on the interpreter, and `-o` compiles it for the JVM,
wasm and `--component`, for a subset of R7RS-small just large enough to prove the approach
on each backend. **Status is EXPERIMENTAL**: partial conformance by design, no
compatibility promise, and every user-facing surface says so (CLI help, the doc page's
title line). `--no-gc` is out of scope -- it has no cons cell and no closure.

## Approach: lower to core forms, change no backend

A Scheme program is read, macro-expanded and LOWERED to the Common Lisp core forms the
pipeline already consumes, then joins it (`CompileFrontend.expand`, `LispEvaluator`)
unchanged. There is no IR beneath those forms to target instead: both backends dispatch on
the canonical operator NAME (`switch (sym.name())`, 513 case labels in `JvmExprCompiler`,
522 in `WasmExprCompiler`), and `FreeVarAnalyzer` is a hand walker over the same names, so
an unknown head is a silent three-way divergence, not an error.

Probed by hand on all four backends, 2026-09-17 --
`.todo/artefacts/825-minimal-experimental-scheme-front-end/` (`README.md` has the outputs,
`run.sh` reruns them). What the probes settled:

| Scheme | lowers to | note |
|---|---|---|
| identifier `foo` | symbol named `foo` (verbatim, lowercase) | canonical names are upcased, so it cannot hit a `LispNames` case label |
| identifier with NO lowercase letter (`CAR`, `X`, `+`) | must be renamed when user-BOUND | `(defun \|CAR\| ...)` REPLACES the built-in; probe `uppercase-identifier.lisp` |
| `'()` | `NIL` | `&rest` lists, `apply` and every list primitive terminate with it |
| `#f` | a distinct non-NIL value | probed as the symbol `\|#f\|`: `eq`-testable, `princ`s as `#f`. Forgeable through `string->symbol`; an `%obj-new` singleton is the alternative -- decide by measuring size and speed on wasm |
| `#t` | `T` | |
| `(if c a b)` | `(if (eq c %false) b a)` | a CL predicate directly in test position fuses: `(if (pair? x) ..)` -> `(if (consp x) ..)`; in value position it is wrapped `(if (consp x) #t #f)` |
| top-level `(define x v)` | top-level `(setq x v)` | NEVER `defvar`: that makes the name special, and a `let` of it leaks into callees (probe `global-define.lisp.in`: 2 vs Scheme's 1) |
| top-level `(define (f ..) ..)` never `set!` | `defun` | keeps the direct call and the tree shaker |
| any other procedure binding | variable + `funcall`; a procedure name in value position -> `#'name` | Lisp-1 over Lisp-2; every built-in already has a first-class wrapper (`BuiltinFunctionWrappers`) |
| `(lambda args ..)`, `(lambda (a . r) ..)` | `&rest` | normalize the dotted list in the front end: `LambdaLists.parse` reads through `cons.toList()` and DROPS an improper tail |
| named `let`, `do`, self tail call | `tagbody`/`go` | 1,000,000 iterations pass on all four |
| `call/cc` | `block` + a closure doing `return-from` | escape-only, one-shot; crosses lambdas through `CrossLambdaExitLowering` |
| `dynamic-wind` | `before`, then `unwind-protect` | the exit half fires on every exit channel; re-entry does not exist |

`symbol?` must exclude `T`, `NIL` and the `#f` value; `boolean?` is `#t`/`#f` only;
`null?` is `null`. Source positions: the lowering must honour the cons-identity rule in
`.kb/source-positions.md` or errors lose their `file:line:column`.

## The minimal subset

- **Reader** (its own, case-sensitive; do not bend `LispLexer`): `#t`/`#f`/`#true`/`#false`,
  `'()`, integers, decimals, rationals, `#\a`/`#\space`/`#\newline`, strings with
  `\n \t \" \\`, `#( )` vectors, dotted pairs, `'` `` ` `` `,` `,@`, `;`, `#;`, `#| |#`.
- **Syntax**: `define` (both forms; internal defines as `letrec*`), `lambda`, `if`, `cond`
  (with `else`), `case`, `and`, `or`, `when`, `unless`, `let`, `let*`, `letrec`, `letrec*`,
  named `let`, `do`, `begin`, `set!`, `quote`, `quasiquote`, `let-values`,
  `define-record-type` (onto the six `%obj-*` primitives, as `defstruct` already is).
  `(import (scheme base) (scheme write))` is accepted and checked against the names
  below; nothing else is importable yet.
- **Procedures**: `eq? eqv? equal?`; `+ - * / = < > <= >= quotient remainder modulo abs
  min max zero? positive? negative? odd? even? number? integer? exact? inexact? exact
  inexact number->string string->number`; `not boolean?`; `cons car cdr set-car! set-cdr!
  caar cadr cdar cddr list length append reverse list-tail list-ref memq memv member assq
  assv assoc null? pair? list?`; `symbol? symbol->string string->symbol`; `char?
  char->integer integer->char char=? char<?`; `string? make-string string-length
  string-ref string-set! string=? string<? substring string-append string-copy
  string->list list->string`; `vector? make-vector vector vector-length vector-ref
  vector-set! vector->list list->vector vector-fill!`; `procedure? apply map for-each
  call/cc call-with-current-continuation dynamic-wind values call-with-values error`;
  `display write newline write-char write-string`.
- **Prelude in Lisp, no emitter changes**: `write`/`display` (leaves `#t` `#f` `()`,
  lowercase character names, `\n` escapes, bare case-sensitive symbols -- today's leaves
  are `T` `NIL` `|foo|`, probe `printer-leaves.lisp`), and an `equal?` that recurses into
  vectors (`equal` compares arrays by identity). Splice it the way `LispPreludeLibrary` is,
  inside `CompileFrontend.expand`, only for a Scheme program.

## Stated deviations (write them on the doc page)

- Tail calls are proper only where the front end makes a loop. Mutual and higher-order
  tail calls use stack: `even?`/`odd?` at 10,000 overflows the JVM's default stack, at
  100,000 the interpreter's and wasm's.
- `call/cc` is escape-only. No re-entry, so no generators or coroutines through it.
- `call-with-values` is efficient only over a syntactically visible producer
  (`.kb/multiple-values.md`); anything else goes through a list.
- Not in this item: `syntax-rules`, `define-library`, `guard`/`raise`, `parameterize`,
  bytevectors, ports beyond the current output port, `eval`, `(scheme char)` and the other
  libraries. Each is its own follow-up once this lands. Notes for them: `guard` maps onto
  `handler-case`, `parameterize` onto the special-`let` restore, bytevectors onto the
  `(unsigned-byte 8)` pack, ports onto `%STREAM` instances, `syntax-rules` onto a
  shadow-aware walk like `substituteSymbolMacros`.
- Errors and condition reports spell Common Lisp names (`CAR`), and a `#f` handed to a
  Common Lisp library is true there.

## Placement

A new package `am.ik.rontolisp.scheme` (reader, expander, lowering), depending on the AST
types and `reader` only; `cli`, `eval` and the web source set reach it through the seam
from `.todo/824`. It reads files through `SourceLoader` only, so the playground gets it.
Add it to the dependency graph in `CLAUDE.md` and to `PackageCycleTest`.

## Done when

- A Scheme corpus -- the `ci-spec.yaml` idea, a separate file -- runs on interpreter, JVM,
  wasm and component with identical output, one case per row of the lowering table and per
  procedure group above, including the 1,000,000-iteration loop and the escape through
  `dynamic-wind`.
- The existing suite is untouched: no Common Lisp program's emitted bytes change
  (`.kb/emitted-output-determinism.md`), and `-Pweb compile` / `-Pnative package` pass.
- A new `.kb/` file, scheme-frontend.md, holds the lowering table, the traps above and the measured
  tail-call depths; `doc/en` + `doc/ja` get one page, marked experimental, listing the
  subset and the deviations.
