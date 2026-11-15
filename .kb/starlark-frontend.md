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
  (identifier escaping, a module's prefix), `StarlarkFiles` (the modules `load` reads,
  "Modules" below), `Starlark` (the facade).
- Reached ONLY through the seam: `eval/SourceLanguage.STARLARK`, picked for `.star` or by
  `--source-language starlark` (`star`), per FILE (`.kb/source-language.md`). A file is
  lowered whole; a REPL's buffers are lowered one at a time as one file's run ("A
  session").
- The run-time half is Common Lisp source, `eval/starlark.lisp`, plus the definitions
  `Starlark.runtimeForms` generates from the front end's tables (the attributes of each
  type, `repr`'s escape table), handled the `UrlLibrary` way by `eval/StarlarkLibrary`: the interpreter loads it on the first resolution of a
  `rontolisp::%starlark-` FUNCTION, `CompileFrontend.expand` splices it beside
  `SchemeLibrary` (inside the prelude, whose string comparisons and `search` it is written
  over) and `LibraryDefunPruner` drops what stays unreachable -- its five `defstruct`s are
  expanded by NAME like Scheme's (`starlarkStructNames`), so a program carries the
  accessors it reaches. The name gate is the spelling: `mayDefine` admits a member
  starting `%STARLARK-` (`.kb/source-language.md`, "The run-time libraries' name gate")
  and the one public name, `rontolisp:starlark-call` ("Across languages").
  The playground's compile chain splices it inside `SchemeLibrary` the same way
  (`RontoPlayground.frontend`).

## The decisions (2026-11-15)

- **`None` is `:none`, `True` and `False` are `T` and `NIL`.** A comparison, `not` and the
  membership test then answer what `if` tests, with no conversion; `None` is a constant,
  not a variable read; a list is a structure, so `NIL` is never an empty collection. A
  `bool` is told from an `int` by type (`1 == True` is false, `{1: .., True: ..}` two
  keys). The three markers the run time uses for itself are keywords no program can spell:
  `:%unset` (an optional built-in parameter the call did not fill), `:%required` (a
  parameter with no default) and `:%unbound` (a variable not assigned yet, "Names").
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
  program ([error-handling.md](error-handling.md)). Where: "Positions" below.

## The lowering table

| Starlark | core forms |
|---|---|
| `None`, `True`, `False` | `:none`, `T`, `NIL` (a name, so a file may rebind it) |
| int, float, string | integer (bignum), double, string literal; `-1` is the literal |
| list, tuple, dict, range, function | structures of `starlark.lisp`: a list an adjustable vector plus the cell it was made under ("Freezing") and an iteration count, a tuple a simple vector, a dict an `equal` hash table from a key's normal form to `(key . value)` plus the same two |
| `[a, b]`, `(a, b)`, `{k: v}` | `(%starlark-make-list a b)`, `-make-tuple`, `-make-dict` (a key written twice is an error) |
| the same, every element a constant | `(%starlark-list-of '(1 "s" :none))`, `-tuple-of`, `-dict-of '(k v ..)`: quoted data, the value made anew from it each time; with such an expression as an element `(%starlark-literal '(:%list 1 (:%tuple 2) (:%dict "k" 3)))` |
| top-level `x = v` | top-level `(setq x v)`, never `defvar` (a special would leak into callees) |
| top-level `def` standing at the top level, the only binding of its name | `(defun f (a b [args] [kwargs]) ..)` AHEAD of every statement (lowered where the `def` stands), plus at its place `(setq f (%starlark-make-function "f" '("a" "b") defaults 2 nil nil #'f))`; a default that is no constant is `(setq sl%(FILE)%SL-DEFAULTn expr)` inside `defaults` |
| any other `def`, `lambda` | the function value over a `(lambda (params.. [args] [kwargs]) ..)` |
| function body | `(block %SL-FN (let ((assigned-name :%unbound) ..) ..))`; the block only when a `return` leaves from the middle, the `let` only with locals; a `return` ending the body is the body's value |
| a read of a name not assigned on every path to it | `(%starlark-local x "x")` / `(%starlark-global x "x")`, refusing `:%unbound`; a global so read starts as `(setq x :%unbound)` ahead of the statements |
| function body under `--starlark-standard spec` | `(%starlark-enter FLAG "f") (let ((r body)) (%starlark-leave FLAG) r)`, `FLAG` the global `sl%(FILE)%SL-ACTIVEn`, set to `(list nil)` ahead of the statements |
| `for` | `(let* ((seq it) (items (%starlark-iterate seq)) (count (length items)) (index 0)) (tagbody top (if (< index count) (progn assign (setq index (+ index 1)) body.. (go top)))) (%starlark-iterated seq))`; `(block %SL-BREAK ..)` around the tagbody and `(block %SL-CONTINUE ..)` around the body only when the body has that exit |
| `for i in range(a, b, c)` (the built-in, positional arguments) | `(let* ((next a) (stop (%starlark-loop-stop next b))) (tagbody top (if (< next stop) (progn (setq i next) (setq next (+ next c)) body.. (go top)))))`: no range, no vector, nothing to give back; `>` for a negative constant step; `(stop b)` with no check when both bounds are known ints, a constant `b` in the test itself; a step that is no constant is `(step (%starlark-loop-step next stop c))` and the test `(if (< 0 step) (< next stop) (> next stop))`; a step written `0` stays the generic loop, whose `range` refuses it |
| `return v` inside loops | `(let ((r v)) (%starlark-iterated seq-inner) .. (return-from %SL-FN r))` -- every loop of the function gives its iterable back first, so no `unwind-protect` and no EH mode on wasm |
| comprehension | the same loops around `(vector-push-extend elt acc)` / `(%starlark-set-index acc k v)`, its variables in a `let` of its own INSIDE the first loop's `let*`, so the first iterable reads the enclosing block's variables (`[x for x in range(x)]`) |
| `a + b`, `a == b`, `a[i]`, `a[i:j]`, `x in y` | `(%starlark-add a b)`, `-equal`, `-index`, `-slice`, `-in`: generic, dispatching at run time; `!=` and `not in` are `(not ..)` of them |
| `+ - * // %`, a comparison, `==`, `!=` over operands that MAY be ints | `(if (and (integerp a) (integerp b)) (+ a b) (%starlark-add a b))`, an operand that is more than a variable bound first; `//` and `%` only by a constant that is not zero ("What the lowering knows") |
| the same and `& \| ^ << >> ~`, unary `-`/`+`, over KNOWN ints | `(+ a b)`, `(< a b)`, `(= a b)`, `(mod a 7)`, `(values (floor a 2))`, `(logand a b)`, `(ash a 3)`, `(- a)`: no test, no helper (a shift only by a constant count that is not negative) |
| `x == None`, `x != True` | `(eq x :none)`, `(not (eq x t))`: `None`, `True` and `False` equal themselves only |
| `if c:`, `c ? ..` | `(if (%starlark-truth c) ..)`; a known Boolean (a comparison, `not`, `in`, `True`/`False`, a variable only such values are bound to, `bool(..)`, `s.startswith(..)`) is the test as it stands, a known int `(not (= c 0))`; `and`/`or` of tests are `and`/`or` |
| `a and b` as a value | `(let ((v a)) (if (%starlark-truth v) b v))`; `(if a b nil)` when `a` is a known Boolean |
| `a[i] = v`, `a, b = v` | `(%starlark-set-index a i v)`; `(let ((u (%starlark-unpack v 2))) (setq a (aref u 0)) ..)` |
| `a, b = b, a + b` (a tuple or list of targets, a tuple or list expression of as many) | `(let ((e1 b) (e2 (+ a b))) (setq a e1) (setq b e2))`: no tuple; every element before the first target, pairing through nested ones |
| `x += v`, `a[i] += v` | `(setq x (%starlark-add-in-place x v))` (a list is extended in place), tested or inline like the binary operator; the target's operands bound once when they are more than a variable |
| call of a top-level `def` with a direct entry whose arguments fit | `(f a b)`, bound while lowering like a built-in's: positional and named arguments at their parameters, a default the constant it is written as or the global its `def` statement set, surplus ones as `(%starlark-make-tuple ..)` / `(%starlark-make-dict "k" v ..)`; `(progn (%starlark-global f "f") (f a b))` where the `def` may not have run |
| call of a built-in function whose arguments fit its signature | `(%starlark-b-sorted xs :none t)`: bound while lowering, defaults filled in |
| `x.m(..)` whose arguments fit | `(%starlark-m-append x v)`: the helper takes the receiver first and tests it |
| any other call | `(%starlark-call f (list pos..) (list "name" v ..))`, `*args` and `**kwargs` appended by `%starlark-elements` / `%starlark-keywords` |
| a built-in as a value, `x.m` uncalled | `(%starlark-builtin "len" '("x") nil 1 1 nil nil #'%starlark-b-len)`, `(%starlark-method x '("list") "append" ..)`: function values the binder takes |
| `x.nope`, `x.nope(..)` | `(%starlark-no-attribute x "nope")`: no type has it, so the arguments are never evaluated |
| a file | `(setq sl%(FILE)%SL-FILE (%starlark-file-begin))` ahead of its statements, `(%starlark-file-end sl%(FILE)%SL-FILE)` after them ("Freezing") |
| `load("m.star", "f", y = "x")` | the module's `defun`s and `(defvar sl%(m.star)%SL-FILE nil)` with the program's definitions; where the first load of it stands `(setq STATE (if STATE t (%starlark-file-begin)))`, each of its statements as `(if (consp STATE) stmt)`, `(if (consp STATE) (setq STATE (%starlark-file-end STATE)))`; then `(setq f sl%(m.star)f) (setq y sl%(m.star)x)`, through `(%starlark-loaded v "x" "m.star")` where a path of the module may not assign it ("Modules") |
| `load("@cl-user", f = "add-two")` | `(setq f (%starlark-foreign "cl-user:add-two" #'add-two))`; a call of `f` with positional arguments only is `(add-two a b)` ("Across languages") |
| `b".."` | refused at its position |

## Names

Resolved while lowering (`StarlarkLowering.reference`): the innermost function or
comprehension binding the name ANYWHERE in its block, else the file's globals (bound
anywhere at the top level, also inside a top-level `if`/`for`, or by a `load` statement),
else `None`/`True`/`False`, else a built-in function, else `undefined: NAME` as a positioned
`LispReadException` before anything runs. A dot's name is not resolved.

**Definite assignment runs with the lowering** (`StarlarkLowering.Flow`, 2026-11-15): the
names assigned on every path to where the lowering stands, threaded through the statements
in source order -- an `if` joins its branches (a branch ending in `return`/`break`/
`continue` is dead and joins as everything), a `for` body's assignments are gone after the
loop (it may run no time), a comprehension's clauses assign their targets for the clauses
after them. A read of a name the flow has is the bare variable; any other is the checked
read above: `local variable x referenced before assignment`, `global variable x ...` (Go's
texts). A function or comprehension body starts with the flow where its value is MADE,
less its own names, plus its parameters and its own name: a variable is never unassigned
again (Starlark has no `del`), and a direct `defun` runs only once its `def` ran, because a
direct call above the `def` checks the global. Over the conformance corpus 7 reads of
~9,200 variable spellings stay checked; `bench.star`, the prototype's demo and
`examples/starlark/` have none.

## Standards (`--starlark-standard`, 2026-11-15)

`starlark/StarlarkStandard`, a member of `eval/SourceStandards` like `--scheme-standard`
(program-wide, `.kb/source-language.md`; `JvmSourceCompiler.starlarkStandard`).
`rontolisp` (default) lets a global be bound again, `if`/`for` stand at the top level and a
function recurse -- Go's options; `spec` makes the first two errors before anything runs
(`if statement not within a function`, `for loop not within a function`, `cannot reassign
global x declared at FILE:L:C`, an augmented assignment counting as a binding) and the
third a run-time one (`function f called recursively`). The default is the permissive
one, as Scheme's is its own dialect: the bench (`fib`), the prototype's demo (a top-level
`for`) and the docs' examples use the relaxed forms.

- Measured: the conformance corpus is written for Go's DEFAULT (strict) options ("go
  implementation doesn't allow top level reassignments" chunks comment it out), and every
  chunk passes under `spec` as under the default, so `#conformance` reads it under `spec`
  -- that runs the recursion check over `yin`/`yang` (recursively defined, never
  recurring).
- Recursion is one flag per DECLARATION (a `def` or `lambda`), as the spec words it ("a
  function value with the same declaration"), so a fresh closure per step (the Y
  combinator) is caught. A global holding a cons, not a dynamic binding: a special's
  binding is a protected region on every backend and forces EH mode on wasm. The global
  is spelled behind the file's prefix (`sl%(FILE)%SL-ACTIVEn`): two files of one program
  number their declarations alike, and a shared flag made `helper` of a module "recursive"
  under `helper` of the program (pinned by the `standalone:` module case, read under
  `spec`). The flag is set back on every normal exit; a call that is running is also a
  frame ("Frames"), so after an error `rontolisp:starlark-call` sets it back. The flags
  are made anew ahead of the statements, so a later program (the corpus's next chunk)
  starts clean.

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
- **A `def` with a direct entry is bound the same way** (2026-11-15): `signature(def)` is a
  `Signature` over its `defun` -- every top-level `def` bound once has one, `*args` and
  `**kwargs` included -- and `direct` places positional and named arguments, defaults and
  the surplus tuple and dict. A default is evaluated when the `def` statement runs, so
  the call needs its VALUE: a default written as a constant (a number, a string, `None`,
  `True`, `False`) is that constant at the call site; any other is left by the `def`
  statement in a global of the file's, `sl%(FILE)%SL-DEFAULTn`, which the call reads --
  the one object the function value's `defaults` hold, so a mutable default is shared by
  both tiers. A direct call runs only once the `def` ran (it checks the global `f`
  otherwise), so the global is set. A module's export carries the signature. A call that
  does not fit (wrong count, unknown name, `*`/`**` argument, a surplus name twice) is
  the binder's.
- **The binder's shortcut** (`%starlark-fills`): a call with no named argument of a
  function with no `*args`/`**kwargs` that takes exactly as many, each by position, hands
  the argument list to the body as it is -- what a function VALUE called through a
  parameter is (`f(x)` inside a higher-order `def`).
- **A direct `defun` goes ahead of every statement.** A compiled program has it from the
  start and the interpreter only once evaluated, so `f(1)` written above `def f` ran on
  three backends and failed on one; hoisted, all four have it, and a call the `def` may not
  precede reads the global `f` through the check first (`global variable f referenced
  before assignment`, the specification's error).
- **Arguments are evaluated as written** though both tiers place them otherwise (a named
  argument at its parameter, `*args` before the names, the value of `a[i] = v` last):
  `inSourceOrder` binds each operand that is more than a constant or a variable to a
  `let*` variable, in source order, exactly when the form would evaluate two of them the
  other way round. A variable needs none: no call can assign it.

## What the lowering knows (2026-11-15)

**Invariant: a fast path answers what the generic helper answers for every value it is
taken for, and it is made of core forms: no backend learns a Starlark name.** The integer
forms are the ones the backends already fuse ([jvm-int-fusion.md](jvm-int-fusion.md),
[wasm-int-fusion.md](wasm-int-fusion.md)).

- **Kinds** (`StarlarkLowering.Kind`, `kind`): INT, BOOL (exactly `T`/`NIL`), OTHER (no
  int: a float, a string, a list ..), ANY. Read off the spelling -- a literal, an
  operator's result (`int op int`, a comparison, `not`), a built-in whose every return is
  one (`StarlarkBuiltins`' `-> int` / `-> bool`: `len`, `int`, `hash`, `find`, `index`,
  `count`, `bool`, `any`, `all`, `hasattr`, `startswith`, `isdigit` ..) -- and off
  variables.
- **A variable is an int when every binding of it in its block is one** (`bound`, `infer`):
  assignments (pairwise through `a, b = b, a + b`), augmented assignments, `for` and
  comprehension targets (an int over `range(..)`, the index of `for i, x in
  enumerate(..)`). All names of a block are assumed ints and the assumption is dropped
  name by name until every binding agrees; then the same for Booleans. Sound because
  nothing outside the block binds its names: Starlark has no `global`/`nonlocal`, so a
  nested function only reads them. A parameter is ANY. A file's globals are inferred the
  same way, a `load`ed name being ANY -- but not in a session, where a later buffer binds
  anything. A variable still `:%unbound` is no hole: a read the flow does not vouch for
  goes through the checked read first. A Common Lisp file that `setq`s a Starlark global
  to another type gets Common Lisp's type error for Starlark's: no contract.
- **Three tiers per operator** (`operation`): both operands known ints -> the Common Lisp
  operator; both MAY be ints -> the operator behind `(integerp ..)` tests of the unknown
  ones, the helper as the other branch (`TESTED`: `+ - * // %`, the comparisons, `==`,
  `!=`); an operand known to be no int -> the helper alone (`"a" + s`, `x * 2.0`).
  Integers are of any size on every backend, so `(+ a b)` IS Starlark's `+`. `//` and
  `%` only by a constant that is not zero, a shift only by a constant count that is not
  negative: the errors (`integer modulo by zero`, `negative shift count`) stay the
  helper's. The bitwise operators are inline over known ints only (`|` is also a dict
  union).
- Not made, not measured: narrowing by a test (`type(x) == "int"`), the kind a `def`
  returns, the kinds of a list's elements, an inline `x[i]`.

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
is ASCII-only on wasm, `.todo/269`), while `islower`/`isupper`/`istitle`/`title` tell a
letter's case by which of `char-upcase`/`char-downcase` changes it (both: titlecase), the
same on four backends for the corpus's `ǅ`/`ǉ` lines; a dict walks in insertion order because the host table
does, a key stored again keeping its place and one removed and stored again going last
(`.kb/hash-tables.md`, "Representation"); a key's normal form (`%starlark-key`) makes `1`
and `1.0` one key and a tuple the list of its elements' forms; a float key is rounded only
when finite.

Written to Go's Starlark (`go.starlark.net`, its `library.go`/`value.go` read
2026-11-15; Java Starlark, `starlark_deploy.jar`, as the second opinion):

- **A float prints as Go's `%g`** (`%starlark-format-float`): the shortest digits that read
  back (taken from the printer's text, the one Schubfach selection of all four backends,
  `.kb/format.md`), an exponent below `1e-4` and from `1e+06` on (`1e+100`, `1e+06`,
  `100000.0`), `.0` when neither point nor exponent shows, `+inf`, `-inf`, `nan`. Java prints
  `1000000.0` and `123456789.0`; the spec's own `%g` row (`1.2e+12`, `1200`) is Go's rule.
  `%e`/`%f` are 6 decimals rounded exactly, half to even, over `integer-decode-float`'s
  integers (no float arithmetic); `%g` is `str`.
- `float(string)`: sign, `inf`/`infinity`/`nan` in any case, else digits, point, exponent,
  converted as the exact rational (the Clojure parser's way); `float` of an int from
  `2^1024 - 2^970` on is `int too large to convert to float`, as is int-float arithmetic.
- **Numbers compare exactly and NaN is one value**: an int against a float compares with
  the float's `rational`, NaN above `+inf` and equal to itself (`%starlark-number-compare`);
  every NaN is one dict key (`:nan`).
- A `def`/`lambda` is a dict key by identity, a built-in by name and receiver -- what makes
  two of them equal.
- `s.elems()`, `elem_ords`, `codepoints`, `codepoint_ords` answer a
  `%starlark-string-iterable` (type `string.elems` or `string.codepoints`, repr
  `"ab".elems()`), iterable and nothing else: no `len`, no index, unhashable. Elements and
  code points are one thing in this string model, so the four differ in name only.
- `repr` of a string is Go's `syntax.Quote`: `\a \b \f \n \r \t \v`, `\xHH` for another C0
  control or DEL, `\uHHHH`/`\UHHHHHHHH` for a code point Go's `strconv.IsPrint` refuses.
  Past U+00A0 that is a range table of the JDK's control, format, private-use, surrogate and
  separator categories (`%starlark-unprintable-ranges`, 80-odd numbers); an UNASSIGNED code
  point prints as itself, where Go escapes it -- the table would grow by ~700 ranges.
- `dir`/`getattr`/`hasattr` read `%starlark-attribute-names` and `%starlark-attribute`,
  generated from `StarlarkBuiltins`' method rows (`Starlark.runtimeForms`, appended by
  `StarlarkLibrary.forms`, like `Scheme.runtimeForms`): a type's attributes are its methods,
  and `getattr` answers the bound method the lowering's `x.m` builds. A program reaching
  `getattr` carries every method helper.
- `str.format` is Go's `string_format` ported: manual and automatic numbering refuse each
  other, `!s`/`!r`, no `x.y`, `a[i]`, nested field or format spec. `splitlines` splits at
  `\n`, `\r` and `\r\n` as the spec says (Go: `\n` only).
- Messages: an iterable a built-in wants is `got T, want iterable`, a `for` over a
  non-iterable `T value is not iterable`, a `*` argument `argument after * must be
  iterable, not T`, a missing argument `function f missing 2 arguments (a, b)` for a `def`
  and `NAME: missing argument for P` for a built-in.

`insert`, `pop` and `remove` move a list's elements inside its own vector
(`%starlark-delete-at`, 2026-11-15; they rebuilt it through a Common Lisp list before, so
a stack's `pop()` was linear). Nothing else holds the vector meanwhile: a loop over the
list counts on it and the mutation is refused, `%starlark-unpack` and the built-ins copy.

A loop counts itself on the list or dict it walks (`%starlark-iterate` /
`%starlark-iterated`) and a mutation meanwhile is refused. The count is given back on every
normal exit -- the loop's end, `break`, a `return` -- by the lowering, and after an error by
the boundary ("Frames").

## Frames: what a call that does not return gives back (2026-11-15)

**Decision: the boundary resets, and the boundary is `rontolisp:starlark-call`.** Go gives
an iterator back with `defer`; here no loop has an `unwind-protect` (EH mode on wasm for
every program), so what is counted is also pushed on one global list,
`rontolisp::%starlark-frames`: the list or dict a loop walks, and under `spec` the flag of
a declaration a call of which runs. The normal exits pop (`%starlark-pop-frame`, the
innermost one). `rontolisp:starlark-call` remembers the list and the mutability cell,
calls inside an `unwind-protect`, and on any exit gives back every frame above its mark
(`%starlark-unwind`: a loop's count down, a flag off). Only a program that calls Starlark
from Common Lisp carries the `unwind-protect`: `print(1 + 2)` grew by 566 B of class and
174 B of wasm with all of this section and "Freezing".

- Measured on the `standalone:` case `common-lisp-and-starlark-call-each-other`: called
  through `rontolisp::%starlark-call` (no boundary) instead, the call after the caught
  error is `cannot append to list during iteration`, and under `spec`
  `function fail_at called recursively`; through the door both calls answer.
- NOT reset: an error some handler takes outside the door -- around `(load "x.star")`.
  The file did not finish; its values stay unfrozen and its loops counted.
- One thread: the frames and the mutability are globals.
- A session's buffer starts with `(%starlark-unwind nil)`, which gives everything back ("A
  session").

## Modules (`load`, 2026-11-15)

**A module is a file lowering of its own whose globals are spelled behind its path, lowered
with the file that loads it.** `StarlarkLowering.load`/`module`: the file is read through
`starlark/StarlarkFiles` (`eval/SourceLanguage.starlarkFiles` over the site's
`SourceLoader`, relative to the loading file's directory; `StarlarkFiles.NONE` refuses
every file), parsed and lowered by a child `StarlarkLowering` sharing the `Session` (the
modules by key, the files being lowered, every file's definitions). Read at lowering time,
so a compiled program carries its modules.

- **Names**: a module's global `helper` is `sl%(lib/util.star)helper`
  (`StarlarkNames.filePrefix`): the key is the resolved path with `/` separators, `.` and
  `name/..` removed, and `%`, `:`, `)` escaped (`%%`, `%c`, `%r`; a colon is a package
  marker to the pipeline). No identifier's spelling starts `sl%` (its third character is
  a `%`; Scheme's library names start `s%%(`). The program's own globals stay unprefixed
  -- what a Common Lisp file reads. The key is the path AS RESOLVED, so one file reached
  by an absolute and a relative spelling is two modules; rejected: a key relative to the
  entry file (two program files in different directories would each run the module) and
  a number per lowering (two lowerings of one program would share numbers).
- **Once per program**: a module is lowered once per lowering (`Session.modules`) and its
  statements stand where the first `load` of it stands, in emission order. Across files
  lowered APART (a Common Lisp file loading two `.star` files that load one module:
  inlined on the compile path, loaded at run time on the interpreter) both emit them, and
  the state decides: `(defvar STATE nil)`; unset, the first form starts the run and
  `STATE` is the file's mutability cell (a cons) while it lasts, then `T`. A state that
  is set stays set, so a module that stopped at an error is not run again. Each statement
  is its own top-level form behind `(if (consp STATE) ..)`, not one `progn`: a top-level
  form is a JVM method (`.kb/jvm-method-size-limits.md`). The `defun`s are bare and may
  be emitted twice.
- **What `load` binds is a global of the loading file, assigned the module's**: a copy,
  not an alias -- a checked read's `(setq x :%unbound)` would otherwise clear the
  module's variable. A module's globals do not change once it ran, so the copy is the
  value. Such a name is the file's own: no third file loads it (`loadedOnly`), and under
  `spec` an assignment of it is `cannot reassign local x declared at ..`, a `load` over a
  binding `cannot reassign top-level x` (Go's texts).
- **Direct across files**: a name only a `load` binds to a module's direct `def` is called
  as `(sl%(m.star)f a b)`, behind the same checked read of the loading file's global.
- **Before anything runs** (`LispReadException` at the statement or the name):
  `cannot load m.star: file not found`, `cannot load m.star: cycle in load graph` (a file
  being lowered, the program's included), `load: name x not found in module m.star`,
  `load: names with leading underscores are not exported: _x`, a name that is no
  identifier without a local name, `load statement within a conditional` / `a loop`. A
  global a path of the module may not assign (bound in a top-level `if`) is the same
  `not found` error when the `load` runs (`%starlark-loaded`).
- A function of a module is reported under its symbol: `in sl%(words.star)shout`.

## Freezing (2026-11-15)

**A list or dict holds the cell it was made under, and a file's end sets its cell.**
`rontolisp::%starlark-mutability` is the current cell; `%starlark-file-begin` puts
`(nil . previous)` in front and answers it, the constructors store the current one in the
`mutability` slot, `%starlark-file-end` sets the cell's car and goes back to its cdr. So
freezing is one `rplaca`, and what a closure of the file captured is frozen with no walk.
Every file is bracketed, the program's too (the specification freezes "whether directly by
the application or indirectly through a load"): a Common Lisp file that loaded a `.star`
file finds its lists frozen.

- Rejected: the walk from the globals (Go's `Freeze`). It needs each function value to
  hand out what it captured (a second closure per nested `def`), a flag on a function to
  end a cycle, and is exponential over a tuple DAG.
- The difference from the specification's reachability: a value made while the file ran
  that no global reaches is frozen too. A value the file's function makes when called
  later is made under the caller's cell.
- **A frozen list is a dict key** (`%starlark-key`: `(:list . keys)`), as `spec.md`
  `c0af0ea` says ("not hashable, unless they have become immutable due to freezing", with
  a comment proposing to drop it). Go and Java refuse it ("even a frozen list is
  unhashable"). A frozen dict stays unhashable: its normal form would have to be
  independent of insertion order, and a `def` key has no order.
- The token is a global of the file's (`sl%(FILE)%SL-FILE`), not the current cell: an
  error some handler took inside a file's run leaves another file's cell current, and the
  end must set its own.

## Across languages (2026-11-15)

**Nothing is converted; the door is a call, in both directions.**

- **Values**: `None` is `:none`, `True`/`False` are `T`/`NIL`, numbers and strings are
  themselves, a list, tuple, dict, range and function are the structures. A Common Lisp
  value with no Starlark type is `"unknown"` (`<unknown>`, true, `eq`, unhashable); a
  Common Lisp function is `"builtin_function_or_method"`, callable, `<built-in function>`.
  `NIL` is `False`, so an empty Common Lisp list is no Starlark list -- why a Common Lisp
  list is not made iterable.
- **Common Lisp -> Starlark: the function VALUE**, the variable `|name|` of a `(load
  ..)`ed file, through `(rontolisp:starlark-call f arg..)`: positional arguments, bound
  by `%starlark-bind` (defaults, `*args`, the binder's messages). The direct `defun`
  under the same spelling exists for a top-level `def` bound once only, takes every
  parameter (then the `*args` tuple and the `**kwargs` dict) and may change with the
  lowering: no contract. It is the one public name of
  `starlark.lisp` (`LispNames.STARLARK_CALL`, a `rontolisp` external,
  `StarlarkLibrary.mayDefine`); it is also "Frames"' boundary.
- **Starlark -> Common Lisp: `load("@package", local = "symbol")`**. The specification
  leaves a module string to the application; `@` + a package name binds the symbol's
  FUNCTION (the string upcased, verbatim between vertical bars; `cl-user` symbols bare,
  any other `PKG::NAME`) as a built-in value named `package:symbol`, and a call with
  positional arguments only is the direct Common Lisp call. This is the door for
  host-predeclared names too: the host defines the function, the file loads it.
  Rejected: an undefined name falling through to a Common Lisp function (Scheme's rule;
  `undefined: x` is a static error the corpus expects) and `load("lib.lisp", ..)` reading
  a file (the host loads its own files; refused as `file not found` only when missing --
  any path is read as Starlark).
- **Named arguments do not cross**: `cl:gcd got an unexpected keyword argument a`.
- **Sequences cross by spreading**: `cl_list(*xs)` with `load("@cl", cl_list = "list")`,
  `(apply #'rontolisp:starlark-call f list)`. No converter is shipped.

## A session (2026-11-15)

**A session's buffers are one file's run** (`starlark/StarlarkSession`,
`StarlarkLowering.interact`), asked through `eval/SourceSession`, so the CLI REPL and the
playground share it. Prompt `starlark> `.

- **`isComplete`**: the parser does not run out of input (an open bracket or string, a
  block header with no block yet -- an error at the end of input's OUTDENT counts as end of
  file, `StarlarkParser.error`), and when the LAST statement is a `def`/`if`/`for` the last
  line is blank, since its block may go on (Python's prompt). A pasted `def` plus a call is
  complete at the call.
- **Steps**: a setup step, then one per statement. An expression statement is the echoed
  step, a literal too (a file drops one as a docstring); the echo is `repr`, nothing for
  `None`.
- **Setup**: the definitions of the modules this buffer lowered; `(%starlark-unwind nil)`;
  in the first buffer `(setq %SL-FILE (%starlark-file-begin))`, after it `(setq
  %starlark-mutability %SL-FILE)` (an error inside a module's run left the module's cell
  current); every global this buffer binds first set to `:%unbound`; the `spec` flags. No
  `file-end`: no buffer freezes what an earlier one made (a module's values freeze at its
  end).
- **Names**: an earlier buffer's global is a global of this one, read through the checked
  read -- that buffer may have stopped before assigning it (`global variable w referenced
  before assignment`, not a Lisp unbound variable). A name no buffer bound yet is
  `undefined: x` while lowering, as in Go's REPL; a function keeps what its names resolved
  to when it was lowered (a built-in a later global shadows stays the built-in there).
- **No direct call of a global**: a later buffer may bind `twice` again and an earlier
  `quad` calls the new value (late binding). Every `def` is a function value and every
  call of a global goes through `%starlark-call`; built-ins and methods stay direct, and
  a module's own direct `defun`s stay (its globals do not change after its run).
- **Modules**: the lowering's `Session` persists, so a later `load` finds the module
  lowered. Every buffer loading it emits its statements again behind its state, which
  runs them once (the emitting buffer may have stopped before them). Its exports are read
  through `%starlark-loaded` (its run may have stopped at an error: `load: name x not
  found in module m.star`).
- A buffer whose lowering fails leaves the session as it was: the modules it lowered are
  dropped, its globals not taken. The generated-name counter is the session's
  (`%SL-ACTIVEn` are globals).
- `--starlark-standard spec`: the top-level rules apply per buffer; a global bound in two
  buffers is no error.

## Measured (2026-11-15)

Linux x86_64, Xeon E5-2697A v4, Java 25.0.5, wasmtime 49.0.0. The prototype is the Python
lowering and Lisp run time that first measured the design,
`.todo/artefacts/j45-starlark-front-end-prototype/` (`README.md`).

- The prototype's `demo.star`: identical on the four backends and to Java Starlark
  (`starlark_deploy.jar`, with the top-level `for` in a function and `split(" ")`).
  `.class` 161,211 B, `.wasm` 146,660 B, component 150,710 B; `print(1 + 2)` alone 62,136 /
  51,673 / 55,336 B (the prototype, its library unpruned: 161,152 / 144,876 / 148,794 B for
  the demo).
- Those sizes are of the first lowering. Re-measured at `85dd248a6` (after the corpus's
  run-time additions: a float's `%g`, `format`, the `repr` table, positions): demo
  284,890 / 263,265 / 266,992 B, `print(1 + 2)` 177,793 / 178,269 / 181,868 B. With the
  file brackets, frames and modules of this file: 287,299 / 264,760 / 268,413 B and
  178,359 / 178,443 / 182,071 B. `bench.star` is unchanged by them (JVM 0.30-0.34 s, wasm
  0.28-0.34 s, three runs each, before and after). What tripled `print(1 + 2)` between
  the two measurements was not measured.
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
- The same corpus as `StarlarkSpecE2eTest#conformance` (in the tree, 39 files, 430 chunks),
  the MESSAGE now matched as well: **392 of 430** before this file's run-time additions,
  **426 of 430 on each of the four backends** after (2026-11-15), every file printing the
  same on all four; the run takes about 30 s in process. Then **429 of 430** with the
  checked reads, read under `--starlark-standard spec`. The one left: `go/builtins.star:64`
  (`sorted(1)`, whose patterns are `Error in sorted`, the traceback line Go and Java print,
  no message).
- A list, tuple or dict expression is ONE call: 1,500 integer elements compile for the
  JVM, 3,000 are `method _top$0: method code exceeds the JVM's 65535-byte limit: 97862`
  (`.kb/jvm-method-size-limits.md`); the interpreter and wasm take them. Since the typed
  fast paths (below) that holds for elements that are not all constants (3,000 reads of
  a variable: `113078`); constants are quoted data, and a list of 100,000 integers, and
  one program of a 3,000-integer list, a list of 3,000 `("k", n)` tuples and a
  3,000-entry dict (`.class` 579,948 B), print the same on the four.

### Typed fast paths (2026-11-15)

Every candidate was measured first as a hand-edited lowering, per backend, and made only
where it paid; the programs and the script are
`.todo/artefacts/j51-starlark-typed-fast-paths/`. Seconds, JVM / WASM preview 1 / component, the best of three runs of the
compiled program (`java Prog`; `wasmtime run` of a precompiled `.cwasm`, so no Cranelift
time), same machine as above:

| program | before | after | by |
|---|---|---|---|
| `fib(35)` | 0.67 / 1.13 / 1.14 | 0.32 / 0.41 / 0.46 | the tested integer operators |
| `total += i % 7` over `range(30,000,000)` | 0.62 / 8.61 / 9.01 | 0.18 / 0.68 / 0.66 | the counting loop, known ints |
| 6,000,000 calls `f(i)`, `f(i, c = 3)` of `def f(a, b = 1, c = 2)` | 2.79 / 10.39 / 10.38 | 0.22 / 0.24 / 0.23 | direct calls with defaults and names |
| 10,000,000 `a, b = b, (a + b) % 1000000007` | 0.88 / 14.69 / 14.67 | 0.34 / 0.23 / 0.23 | no tuple, known ints |
| 3,000,000 `[1, .. 8]` and `"c" in ("a", "b", "c")` | 2.34 / 7.17 / 7.23 | 1.44 / 4.48 / 4.49 | quoted data |
| 20,000,000 `if flag and i:` / `if not flag or t:` | 0.57 / 5.14 / 5.01 | 0.16 / 0.39 / 0.35 | known Booleans and ints as their own truth |
| 30,000 `append` then `pop()`, 3,000 `insert(0, x)`, 3,000 `remove(x)` | 21.79 / 60.88 / 61.55 | 0.38 / 1.01 / 1.01 | list methods in place |
| 3,000,000 calls `f(i)` of a function handed as a parameter | 1.42 / 3.22 / 3.20 | 0.38 / 0.50 / 0.49 | `%starlark-fills` (0.95 / 1.93 / 1.94 without it) |
| Collatz steps below 100,000 plus a sieve to 2,000,000 | 2.89 / 14.80 / 14.32 | 2.09 / 2.73 / 2.49 | all of them; `flags[j]` stays generic |

- The hand-edited steps of the `range` loop: the counter alone 0.32 / 3.72 (the range's
  vector is the cost: the vector loop WITH inline arithmetic stayed 0.65 / 8.06), plus
  inline arithmetic 0.35 / 0.95. Of `fib(35)`: tests only where one operand is a
  literal 0.40 / 0.48, every operator tested 0.28 / 0.44, plain Common Lisp 0.21 / 0.28.
  Its `if .. return` written as one `if` with no block: 0.63 / 0.98 -- not made.
- Sizes (`.class` / `.wasm` / component, before -> after): the prototype's `demo.star`
  287,305 / 264,760 / 268,413 -> 289,662 / 264,109 / 267,848; `print(1 + 2)` 178,363 /
  178,443 / 182,071 -> 174,530 / 176,175 / 179,914 (no `%starlark-add`); `bench.star`
  210,963 / 204,087 / 207,654 -> 191,378 / 190,650 / 194,481; `examples/starlark/
  targets.star` 275,597 / 258,448 / 262,222 -> 274,842 / 259,013 / 262,721. A tested
  operator costs its site a few dozen bytes, which is why operands that both may be ints
  are tested too, not only an operand beside a literal. A quoted `[1, 2]` is 56 B of
  class smaller than the call and one byte of wasm larger, so every constant expression
  is quoted, from one element on.
- `bench.star` as in the table above (wall time, three runs): interpreter 2.55-2.63 s,
  JVM 0.15-0.16 s, WASM 0.07 s once wasmtime's cache holds the module (0.20 s the first
  run; before: 0.29-0.33 s and 0.39 s), component the same.

## Positions

A static error is a `LispReadException` at `file:line:column` (reader, names, the `spec`
rules). A run-time one is reported as every language's is ([error-handling.md](error-handling.md)):
`  at FILE:LINE in FUNCTION` under the `Unhandled condition:` line, on the interpreter and
the JVM, and on wasm under `--report-locations` -- no column, which no location line has.
`StarlarkLowering.located` places every statement's and expression's form at its node: a
`SourceProvenance.record` (offset from the node's line and column through the source's line
starts) when a compile-path scope is open, else, for a named file, a `LocatedCons` copy. A
direct `def` is a named function (`in f`); a form in any other `def` or a `lambda` belongs
to the direct `def` it is written in, and at the top level to none. Pins:
`UncaughtReportParityTest#aFrontEndsSourceIsLocatedTheSameWay`,
`WasmReportLocationsTest#aFrontEndsProgramNamesTheFunctionHoldingTheForm`.

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

`StarlarkSpecE2eTest#conformance` runs bazelbuild/starlark's `test_suite/testdata`
(`src/test/resources/starlark-conformance/`, Apache-2.0, its `LICENSE` beside it) on four
backends, one program per FILE: every chunk is read and lowered alone behind the corpus
harness's three assertion `def`s (a reader or name error becomes that chunk's report, so
every chunk is parsed), each symbol the lowering spells with a lowercase letter -- every
name of the program, `StarlarkNames` -- is renamed `c<N>%name` so the chunk has globals of
its own, and every top-level statement but a `defun` runs inside `(if (null failure)
(handler-case ..))`, so a chunk stops at its first error and the next one runs. A chunk
passes as `starlark_test.py` judges it, plus nothing printed; its error is matched against
its `###` patterns (lowercased, substring or regular expression, a brace that starts no
repetition read as a brace as Python's `re` reads it), any implementation's variant
accepted; an error is EXPECTED when a pattern names no implementation or names `go` (the
one rust-only pattern, `x9a[1, 2] = 3`, is no error in the spec). `KNOWN_FAILURES` names the
chunks that still fail, each with why; one that passes fails the test until it is taken
off. The compiled legs expand the wrapped forms through `CompileFrontendAccess.lowered`
(`CompileFrontend.expand`, as `run` expands a `.star` file's read).

`StarlarkSpecE2eTest` over `starlark-spec.yaml` (the corpus on four backends, in process:
the prototype's feature tour first, then a case per decision above --
`what-the-lowering-knows-answers-as-the-generic-paths` printed the same under the generic
lowering, but one line --; a `standalone:` case is
a program of its own, read against its `standard:` -- one ending with an uncaught error
must report the same text, one with `files:` is read from a directory holding them, its
`entry:` a `.star` or a `.lisp` file: modules and freezing, and the two languages calling
each other),
`StarlarkLoweringTest` (the table's rows as emitted forms -- a source starting `# spec` is
read against `spec` -- the refusals, `load`'s included, helper arity),
`SourceLanguageTest#starlarkClaimsItsExtension`,
`RontoLispCliStreamsTest#aStarlarkFileRunsCompilesAndIsRefusedWhereItCannot` (and
`--starlark-standard`),
`SchemeLibraryTest#aCommonLispProgramReadsNoFrontEndsLibrary` (the gate),
`DocExamplesTest` (a ` ```starlark ` fence is a whole program, its output the plain block
after it), `ExamplesE2eTest` (`examples/starlark/targets.star`). A session:
`RontoLispCliTest#theStarlarkReplRunsItsBuffersAsOneFile`,
`PlaygroundReplTest#theStarlarkPickKeepsItsGlobalsAndModulesAcrossBuffersAndRunsAProgram`,
`SourceLanguageTest#starlarkClaimsItsExtension` (`isComplete`),
`StarlarkParserTest#nodesCarryTheirPositionAndAnUnfinishedInputSaysSo`.
