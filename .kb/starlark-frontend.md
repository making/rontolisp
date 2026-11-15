# The EXPERIMENTAL Starlark front end (`am.ik.rontolisp.starlark`)

**Invariant: a Starlark file is read, resolved and LOWERED to the Common Lisp core forms the
pipeline already consumes; no backend learns a Starlark name.** Status is experimental --
partial conformance to bazelbuild/starlark's `spec.md` by design, no compatibility promise --
and every user surface says so (`--source-language` help, the title of
`doc/*/starlark/index.md`, `examples/starlark/README.md`). `--no-gc` is refused by name
(`CompileFrontend.run`): every value but a number is a structure, a string or a hash table.

## Where it sits

- `starlark` depends on the AST types and `reader` (`LispReadException`) only, like `scheme`
  and `clojure` (`PackageCycleTest`): `StarlarkLexer` + `StarlarkParser` + `StarlarkSyntax`
  (text -> tree, "Reader" below), `StarlarkLowering` (tree -> core forms),
  `StarlarkBuiltins` (the built-in functions' and methods' signatures), `StarlarkNames`
  (identifier escaping), `Starlark` (the facade).
- Reached ONLY through the seam: `eval/SourceLanguage.STARLARK`, picked for `.star` or by
  `--source-language starlark` (`star`), per FILE (`.kb/source-language.md`). A file is
  lowered whole. There is no `SourceSession` yet: its constructor refuses the language, so
  the REPL and the playground do (`-e` reads a whole program and works).
- The run-time half is Common Lisp source, `eval/starlark.lisp`, handled the `UrlLibrary`
  way by `eval/StarlarkLibrary`: the interpreter loads it on the first resolution of a
  `rontolisp::%starlark-` FUNCTION, `CompileFrontend.expand` splices it beside
  `SchemeLibrary` (inside the prelude, whose string comparisons and `search` it is written
  over) and `LibraryDefunPruner` drops what stays unreachable -- its five `defstruct`s are
  expanded by NAME like Scheme's (`starlarkStructNames`), so a program carries the
  accessors it reaches. The name gate is the spelling: `mayDefine` admits a member
  starting `%STARLARK-` (`.kb/source-language.md`, "The run-time libraries' name gate").
  The playground's chain has no splice yet.

## The decisions (2026-11-15)

- **`None` is `:none`, `True` and `False` are `T` and `NIL`.** A comparison, `not` and the
  membership test then answer what `if` tests, with no conversion; `None` is a constant,
  not a variable read; a list is a structure, so `NIL` is never an empty collection. A
  `bool` is told from an `int` by type (`1 == True` is false, `{1: .., True: ..}` two
  keys). The two markers the run time uses for itself are keywords no program can spell:
  `:%unset` (an optional built-in parameter the call did not fill) and `:%required` (a
  parameter with no default).
- **Identifier escaping: verbatim when the name has an ASCII lowercase letter, else `s%` +
  the name** (`StarlarkNames`): canonical names are upcased, so `fib` can never be a
  built-in, a lambda-list keyword or `T`; `X`, `T`, `_`, `MAX_SIZE` could. An identifier
  holds letters, digits and `_` only, so the prefix is no identifier's start. Locals and
  globals share the spelling: a local is a `let` (or parameter) that shadows. What the
  lowering generates is `%SL-<KIND><n>` (and the blocks `%SL-FN`, `%SL-BREAK`,
  `%SL-CONTINUE`); the helpers are `rontolisp::%starlark-<name>`, a built-in function's
  `-b-<name>`, a method's `-m-<name>`.
- **The string model: a string's elements are code points**, the host CHARACTER
  (`.kb/characters-code-points.md`): `len` of one character outside the BMP is 1, where the
  specification's two models (K = 8 or 16) answer 4 or 2. Emulating either would make
  `len`, indexing and slicing a walk on every backend and need a string holding half a
  character, which the JVM's UTF-16 strings and the others' code points disagree about. A
  literal cannot spell a surrogate (the reader refuses the escape). `hash` is the
  specification's number anyway: `java.lang.String.hashCode` over the UTF-16 form,
  computed in `starlark.lisp` -- `sxhash` differs per backend.
- **`print` writes to standard output** (`*standard-output*`), as a program's output: the
  specification leaves it to the host. A Common Lisp caller's `with-output-to-string`
  captures it.
- **An error is a Common Lisp `error` with a Starlark message** (Go's wording where it has
  one), so uncaught it is the pipeline's `Unhandled condition: ...` on standard error and
  a non-zero exit on all four -- and nothing on a wasm module that never prints, as for any
  program ([error-handling.md](error-handling.md)). No position yet.

## The lowering table

| Starlark | core forms |
|---|---|
| `None`, `True`, `False` | `:none`, `T`, `NIL` (a name, so a file may rebind it) |
| int, float, string | integer (bignum), double, string literal; `-1` is the literal |
| list, tuple, dict, range, function | structures of `starlark.lisp`: a list an adjustable vector plus `frozen` and an iteration count, a tuple a simple vector, a dict an `equal` hash table from a key's normal form to `(key . value)` plus the same two |
| `[a, b]`, `(a, b)`, `{k: v}` | `(%starlark-make-list a b)`, `-make-tuple`, `-make-dict` (a key written twice is an error) |
| top-level `x = v` | top-level `(setq x v)`, never `defvar` (a special would leak into callees) |
| top-level `def` standing at the top level, the only binding of its name, no `*` parameter | `(defun f (a b) ..)` AHEAD of every statement, plus at its place `(setq f (%starlark-make-function "f" '("a" "b") defaults 2 nil nil #'f))` |
| any other `def`, `lambda` | the function value over a `(lambda (params.. [args] [kwargs]) ..)` |
| function body | `(block %SL-FN (let ((assigned-name :none) ..) ..))`; the block only when a `return` leaves from the middle, the `let` only with locals; a `return` ending the body is the body's value |
| `for` | `(let* ((seq it) (items (%starlark-iterate seq)) (count (length items)) (index 0)) (tagbody top (if (< index count) (progn assign (setq index (+ index 1)) body.. (go top)))) (%starlark-iterated seq))`; `(block %SL-BREAK ..)` around the tagbody and `(block %SL-CONTINUE ..)` around the body only when the body has that exit |
| `return v` inside loops | `(let ((r v)) (%starlark-iterated seq-inner) .. (return-from %SL-FN r))` -- every loop of the function gives its iterable back first, so no `unwind-protect` and no EH mode on wasm |
| comprehension | the same loops around `(vector-push-extend elt acc)` / `(%starlark-set-index acc k v)`, its variables in a `let` of its own, the first iterable evaluated outside it |
| `a + b`, `a == b`, `a[i]`, `a[i:j]`, `x in y` | `(%starlark-add a b)`, `-equal`, `-index`, `-slice`, `-in`: generic, dispatching at run time; `!=` and `not in` are `(not ..)` of them |
| `if c:`, `c ? ..` | `(if (%starlark-truth c) ..)`; a comparison, `not`, `in` and `True`/`False` are the test as they stand; `and`/`or` of tests are `and`/`or` |
| `a and b` as a value | `(let ((v a)) (if (%starlark-truth v) b v))`; `(if a b nil)` when `a` is a Boolean form |
| `a[i] = v`, `a, b = v` | `(%starlark-set-index a i v)`; `(let ((u (%starlark-unpack v 2))) (setq a (aref u 0)) ..)` |
| `x += v`, `a[i] += v` | `(setq x (%starlark-add-in-place x v))` (a list is extended in place); the target's operands bound once when they are more than a variable |
| call of a top-level `def` with a direct entry, every parameter filled by position | `(f a b)` |
| call of a built-in function whose arguments fit its signature | `(%starlark-b-sorted xs :none t)`: bound while lowering, defaults filled in |
| `x.m(..)` whose arguments fit | `(%starlark-m-append x v)`: the helper takes the receiver first and tests it |
| any other call | `(%starlark-call f (list pos..) (list "name" v ..))`, `*args` and `**kwargs` appended by `%starlark-elements` / `%starlark-keywords` |
| a built-in as a value, `x.m` uncalled | `(%starlark-builtin "len" '("x") nil 1 1 nil nil #'%starlark-b-len)`, `(%starlark-method x '("list") "append" ..)`: function values the binder takes |
| `x.nope`, `x.nope(..)` | `(%starlark-no-attribute x "nope")`: no type has it, so the arguments are never evaluated |
| `load`, `b".."` | refused at their position |

## Names

Resolved while lowering (`StarlarkLowering.reference`): the innermost function or
comprehension binding the name ANYWHERE in its block, else the file's globals (bound
anywhere at the top level, also inside a top-level `if`/`for`), else `None`/`True`/`False`,
else a built-in function, else `undefined: NAME` as a positioned `LispReadException` before
anything runs. A dot's name is not resolved.

Not checked yet, each the same on four backends (measured 2026-11-15): a local read before
its assignment reads `None`; a global read before its assignment is Common Lisp's `The
variable y is unbound`; a global may be rebound, `if`/`for` stand at the top level, and a
function may recurse.

## The two call tiers

One signature serves both: a function value (`%starlark-function`) holds `params`,
`defaults` (`:%required` where an argument is needed), `positional` (how many leading
parameters a positional argument fills), `named-from` (the first parameter a name fills: 0
for a `def`, past the positional-only ones for a built-in, so `dict(pairs = 1)` is
`{"pairs": 1}`), `varargs`, `kwargs`, the Common Lisp function and, for a method, its
receiver. The body takes the parameters, then the tuple of surplus positional arguments,
then the dict of surplus named ones.

- **General**: `%starlark-call` -> `%starlark-bind` makes that argument list, or refuses
  with the count, the unexpected name, the parameter given twice or the missing one.
- **Direct**: `StarlarkLowering.direct` runs the same binding over the call's syntax. A
  built-in's signature is `StarlarkBuiltins` (`"sorted(iterable, /, *, key=None,
  reverse=False)"`, a method's behind its types, `"list|dict.pop(..)"`); a call that does
  not fit (a `*`/`**` argument, a wrong count or name) goes to the general tier with the
  signature as a value, so the binder's message is the one a program sees and no backend
  meets a Common Lisp arity error. A helper therefore has a FIXED parameter list, no
  `&optional`/`&key` -- `StarlarkLoweringTest#everyBuiltinsHelperTakesTheParametersItsSignatureDeclares`.
- A `def` is direct only when every parameter is filled by position (the count equals the
  parameter count). Defaults and names at the call site are not resolved yet.
- **A direct `defun` goes ahead of every statement.** A compiled program has it from the
  start and the interpreter only once evaluated, so `f(1)` written above `def f` ran on
  three backends and failed on one; hoisted, it runs on all four (the specification makes it
  an error).
- **Arguments are evaluated as written** though both tiers place them otherwise (a named
  argument at its parameter, `*args` before the names, the value of `a[i] = v` last):
  `inSourceOrder` binds each operand that is more than a constant or a variable to a
  `let*` variable, in source order, exactly when the form would evaluate two of them the
  other way round. A variable needs none: no call can assign it.

## The run-time library's rules

`starlark.lisp` keeps three, stated at its top:

- **A structure accessor is called only behind that structure's predicate.** An accessor
  handed an instance of ANOTHER structure passes the instance check
  (`.kb/defstruct.md`, "Accessors check their object"): it reads that structure's slot, or
  past its slots is `%OBJ-REF: slot index 2 is outside ..` on the interpreter, `index out
  of bounds` on the JVM and an uncatchable `out of bounds array access` trap on wasm
  (measured 2026-11-15). The prototype's `(1, 2).append(3)` was that.
- **A value of the program reaches a Common Lisp function only behind the type test that
  function needs**: a method tests its receiver first (`TYPE has no .NAME field or
  method`), an argument its type. 82 programs each ending in a different run-time error
  printed the same message on four backends (2026-11-15).
- **What the lowering calls is a `defun`**: the interpreter loads the file on the first
  resolution of one of its functions, which a structure's constructor, predicate or
  accessor is not (`StarlarkLibrary.functionNames`).

Also: `isalpha`/`isdigit`/`isalnum`/`isspace` test ASCII codes themselves (`alpha-char-p`
is ASCII-only on wasm, `.todo/269`); a dict walks in insertion order because the host table
does, a key stored again keeping its place and one removed and stored again going last
(`.kb/hash-tables.md`, "Representation"); a key's normal form (`%starlark-key`) makes `1`
and `1.0` one key and a tuple the list of its elements' forms; a float key is rounded only
when finite.

A loop counts itself on the list or dict it walks (`%starlark-iterate` /
`%starlark-iterated`) and a mutation meanwhile is refused. The count is given back on every
normal exit -- the loop's end, `break`, a `return` -- and NOT on an error: an error ends the
program. A host that catches it and keeps the value (a REPL, a Common Lisp `handler-case`)
will find the list still counted.

## Measured (2026-11-15)

Linux x86_64, Xeon E5-2697A v4, Java 25.0.5, wasmtime 49.0.0. The prototype is the Python
lowering and Lisp run time that first measured the design,
`.todo/artefacts/j45-starlark-front-end-prototype/` (`README.md`).

- The prototype's `demo.star`: identical on the four backends and to Java Starlark
  (`starlark_deploy.jar`, with the top-level `for` in a function and `split(" ")`).
  `.class` 161,211 B, `.wasm` 146,660 B, component 150,710 B; `print(1 + 2)` alone 62,136 /
  51,673 / 55,336 B (the prototype, its library unpruned: 161,152 / 144,876 / 148,794 B for
  the demo).
- `bench.star` (`fib(27)` plus a 10^6-iteration `for` over `range`), wall time of one run,
  three runs each:

  | runner | prototype | this lowering |
  |---|---|---|
  | interpreter | 5.51 s | 4.33-4.98 s |
  | JVM `.class` | 0.45-0.50 s | 0.29-0.36 s |
  | WASM preview 1 | 0.39-0.40 s | 0.28-0.37 s |
  | WASM component | 0.35-0.38 s | 0.28-0.33 s |
  | Java Starlark | 0.26-0.29 s | |
  | CPython 3.12 | 0.10-0.13 s | |

  The two lowerings differ here in an integer test ahead of the float one in the
  arithmetic helpers, helpers without `&key`, and no block where no exit needs one; which
  of them the time went to was not measured.

- bazelbuild/starlark `c0af0ea` `test_suite/testdata`, each chunk a program of its own
  behind `assert_eq`/`assert_ne`/`assert_` written in Starlark, judged as the prototype
  judged (no assertion printed, an error exactly when one is expected, the message not
  matched): **416 of 430** on the interpreter (the prototype: 404), and all 430 print the
  same on the four backends. The 14: `splitlines` (3), `islower` and its siblings (2),
  `title` (1), `dir` (3), `format` mixing automatic and manual numbering (3), a local read
  before its assignment (2).
- A list, tuple or dict expression is ONE call: 1,500 integer elements compile for the
  JVM, 3,000 are `method _top$0: method code exceeds the JVM's 65535-byte limit: 97862`
  (`.kb/jvm-method-size-limits.md`); the interpreter and wasm take them.

## Reader

`StarlarkLexer` (text -> tokens, one per `next()`) and `StarlarkParser` (recursive descent
over the spec's "Grammar reference", precedence climbing for the binary operators) build a
`StarlarkSyntax` tree: one record per production, each with its `SourceLocation`. Errors
are `LispReadException`s with a position; `isEndOfFile()` is true when the input ran out
(unterminated string, open bracket, missing indented block).

- Lexing is lazy, so the first error in source order is the one reported, lexical or
  syntactic.
- Indentation is spaces only (the spec; Python accepts tabs): a tab there is an error. Blank
  and comment-only lines are skipped; inside brackets line endings and indentation are white
  space; backslash-newline joins lines anywhere.
- Literals: ints are `BigInteger` (hex, octal, binary; a leading zero is an error, as the spec
  grammar has only `0`); floats `double` (an infinite literal is an error); strings are Java
  `String`s (octal and hex escapes at most 127, Unicode escapes refuse surrogates); bytes are
  `byte[]` (text as UTF-8, escapes as raw bytes up to 255). A backslash before anything else
  is an error. Raw strings keep the backslash before a quote or line ending, as Python does.
- Rejected with a position, though Python accepts them: the reserved words (`while`,
  `class`, `import`, `is`, `try`, `with`, `yield`, `global`, `nonlocal`, ...) in the lexer,
  string prefixes other than `r`/`b`/`rb`/`br` (f-strings), chained comparison, chained
  assignment, `for`/`else`, a slice or call as a target, a tuple or list as an augmented
  target, an unparenthesized tuple with a trailing comma, arguments out of the order
  positional, keyword, `*args`, `**kwargs`, parameters out of the order required, optional,
  `*`, keyword-only, `**kwargs`. Also rejected: `return` outside a `def`, `break`/`continue`
  outside a loop, `load` inside a `def` or with no symbol, a bare `*` with no keyword-only
  parameter after it.
- Accepted though Python rejects: non-ASCII text in a bytes literal (the spec encodes it as
  UTF-8), a `lambda` as a comprehension's `if` condition (the spec's grammar and Go's
  parser allow it; Python 3.9+ does not).
- Positions: the operator for a unary or binary node, `if` for a conditional, the opening
  bracket for a call, subscript, slice or collection, the field name for a dot, the first
  token for everything else. Columns are 1-based UTF-16 units.
- Parentheses leave no node; `elif` is an `If` alone in the `else` list; `True`, `False`,
  `None` are identifiers.

Measured 2026-11-15 against Python 3.12's `ast` (`astdump.py` in the prototype's directory, above
prints `ast` as `StarlarkTrees` prints a tree, names and literals with `@line:column`):
bazelbuild/starlark `c0af0ea` `test_suite/testdata`, 430 chunks, plus the prototype's
`demo.star` and `bench.star` and a 47-statement lexical stress file: every tree identical,
positions included. The corpus has three syntax-error chunks (two argument-order, one
`6burgle`); both parsers reject all three. It has no float or bytes literal and no syntax
Python accepts and Starlark rejects, so `StarlarkParserTest` pins those by hand.

## Tests

`StarlarkSpecE2eTest` over `starlark-spec.yaml` (the corpus on four backends, in process:
the prototype's feature tour first, then a case per decision above; `standalone:` cases end
with an uncaught error and must report the same text), `StarlarkLoweringTest` (the table's
rows as emitted forms, the refusals, helper arity), `SourceLanguageTest#starlarkClaimsItsExtension`,
`RontoLispCliStreamsTest#aStarlarkFileRunsCompilesAndIsRefusedWhereItCannot`,
`SchemeLibraryTest#aCommonLispProgramReadsNoFrontEndsLibrary` (the gate),
`DocExamplesTest` (a ` ```starlark ` fence is a whole program, its output the plain block
after it), `ExamplesE2eTest` (`examples/starlark/targets.star`).
