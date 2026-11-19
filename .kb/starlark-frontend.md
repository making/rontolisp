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
  over) and `LibraryDefunPruner` drops what stays unreachable -- its seven `defstruct`s are
  expanded by NAME like Scheme's (`starlarkStructNames`), so a program carries the
  accessors it reaches. The name gate is the spelling: `mayDefine` admits a member
  starting `%STARLARK-` (`.kb/source-language.md`, "The run-time libraries' name gate")
  and the one public name, `rontolisp:starlark-call` ("Across languages").
  The playground's compile buttons run the same `expand` (`RontoPlayground.frontend`).

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
| list, tuple, dict, set, bytes, range, function | structures of `starlark.lisp`: a list an adjustable vector plus the cell it was made under ("Freezing") and an iteration count, a tuple a simple vector, a dict an `equal` hash table from a key's normal form to `(key . value)` plus the same two, a set a dict of its own, a bytes an `(unsigned-byte 8)` vector ("Bytes and sets") |
| `[a, b]`, `(a, b)`, `{k: v}` | `(%starlark-make-list a b)`, `-make-tuple`, `-make-dict` (a key written twice is an error) |
| the same, every element a constant | `(%starlark-list-of '(1 "s" :none))`, `-tuple-of`, `-dict-of '(k v ..)`: quoted data, the value made anew from it each time; with such an expression as an element `(%starlark-literal '(:%list 1 (:%tuple 2) (:%dict "k" 3)))` |
| top-level `x = v` | top-level `(setq x v)`, never `defvar` (a special would leak into callees) |
| top-level `def` standing at the top level, the only binding of its name | `(defun f (a b [args] [kwargs]) ..)` AHEAD of every statement (lowered where the `def` stands), plus at its place `(setq f (%starlark-make-function "f" '("a" "b") defaults 2 nil nil #'f))`; a default that is no constant is `(setq sl%(FILE)%SL-DEFAULTn expr)` inside `defaults` |
| any other `def`, `lambda` | the function value over a `(lambda (params.. [args] [kwargs]) ..)` |
| function body | `(block %SL-FN (let ((assigned-name :%unbound) ..) ..))`; the block only when a `return` leaves from the middle, the `let` only with locals; a `return` ending the body is the body's value |
| a read of a name not assigned on every path to it | `(%starlark-local x "x")` / `(%starlark-global x "x")`, refusing `:%unbound`; a global so read starts as `(setq x :%unbound)` ahead of the statements |
| function body under `--starlark-standard spec` | `(%starlark-enter FLAG "f") (let ((r body)) (%starlark-leave FLAG) r)`, `FLAG` the global `sl%(FILE)%SL-ACTIVEn`, set to `(list nil)` ahead of the statements |
| `for` | `(let* ((seq it) (items (%starlark-iterate seq)) (count (length items)) (index 0)) (while (< index count) assign (setq index (+ index 1)) body..) (%starlark-iterated seq))`; `(block %SL-BREAK ..)` around the `while` and `(block %SL-CONTINUE ..)` around the body only when the body has that exit. `while` is the core form `loop` expands to; until 2026-11-16 the loop was a `tagbody` and a `go`, on wasm a `br_table` over a boxed counter every round |
| `for i in range(a, b, c)` (the built-in, positional arguments) | `(let* ((next a) (stop (%starlark-loop-stop next b))) (while (< next stop) (setq i next) (setq next (+ next c)) body..))`: no range, no vector, nothing to give back; `>` for a negative constant step; `(stop b)` with no check when both bounds are known ints, a constant `b` in the test itself; a step that is no constant is `(step (%starlark-loop-step next stop c))` and the test `(if (< 0 step) (< next stop) (> next stop))`; a step written `0` stays the generic loop, whose `range` refuses it |
| `return v` inside loops | `(let ((r v)) (%starlark-iterated seq-inner) .. (return-from %SL-FN r))` -- every loop of the function gives its iterable back first, so no `unwind-protect` and no EH mode on wasm |
| comprehension | the same loops around `(vector-push-extend elt acc)` / `(%starlark-dict-put acc k v)` (the dict is the comprehension's own, so nothing of `a[k] = v`'s dispatch is asked, and an int or a string key is stored with one look-up: `(setf (gethash k table) (cons k v))`, the cell made anew for a key stored before), its variables in a `let` of its own INSIDE the first loop's `let*`, so the first iterable reads the enclosing block's variables (`[x for x in range(x)]`). `acc` is `(%starlark-vector)`, room for four; a list comprehension of ONE loop over an iterable and no condition pushes an element for each the loop walks, so its `acc` is `(%starlark-vector-of count)`, the loop's own count, and no push grows it (2026-11-17, "A program a user wrote") |
| `a + b`, `a == b`, `a[i]`, `a[i:j]`, `x in y` | `(%starlark-add a b)`, `-equal`, `-index`, `-slice`, `-in`: generic, dispatching at run time; `!=` and `not in` are `(not ..)` of them |
| `+ - * // %`, a comparison, `==`, `!=` over operands that MAY be ints | `(if (and (integerp a) (integerp b)) (+ a b) (%starlark-add a b))`, an operand that is more than a variable bound first; `//` and `%` by a divisor that is no constant test it too, `(if (and (integerp a) (integerp b) (not (= b 0))) (mod a b) (%starlark-mod a b))`, also over known ints ("What the lowering knows") |
| `s + t` over KNOWN strings; over one known string | `(%string-concat s t)`; `(if (stringp t) (%string-concat s t) (%starlark-add s t))` |
| `str(i)` over a KNOWN int; a template's `{}`, `%s`, `%d`, `%i` of one | `(%decimal-piece i)`: what `%starlark-b-str` and `%starlark-conversion` answer for an int, with no call of them (2026-11-16). On wasm a chain of `%string-concat` over such pieces is one string made once, the digits written where they go ([wasm-gc-strings.md](wasm-gc-strings.md), "A chain of concatenations is one string") |
| `"{}-{!r}".format(a, b)`, `"%d: %s" % (a, b)`: a template written as a literal over positional arguments or a tuple expression | `(%string-concat (%string-concat (%starlark-b-str a) "-") (%starlark-b-repr b))`: the template is read while lowering (`format`, `interpolation`), every argument evaluated first, in order (bound when it is more than a variable), then one piece per field -- `%starlark-b-str`, `-b-repr`, `(%starlark-conversion a #\d)` for the other `%` conversions -- joined, past eight pieces by `(%starlark-concat-pieces (list ..) n)` (four until 2026-11-17: wasm takes a chain four pieces a call of `_str_cat`, so five to eight are two or three calls and no list); a template whose reading ends in an error, a named field, a format spec, more than 64 pieces, any other template is the run time's |
| `"t%d" % x`, the right operand no tuple expression (2026-11-17) | the template read for ONE argument, which `x` is unless its value is a tuple: `(%string-concat "t" (%decimal-piece x))` for a known int, Boolean or string, else `(if (%starlark-one-argument-p x) <the pieces over x> (%starlark-mod "t%d" x))`, `x` bound first when it is more than a variable; a template that does not read for one argument is the run time's |
| the same and `& \| ^ << >> ~`, unary `-`/`+`, over KNOWN ints | `(+ a b)`, `(< a b)`, `(= a b)`, `(mod a 7)`, `(ash a -1)` for `a // 2` (a power of two; any other divisor `(/ (- a (mod a 7)) 7)`, never `floor`: "Against CPython"), `(logand a b)`, `(ash a 3)`, `(- a)`: no test, no helper (a shift only by a constant count that is not negative) |
| `x == None`, `x != True` | `(eq x :none)`, `(not (eq x t))`: `None`, `True` and `False` equal themselves only |
| `if c:`, `c ? ..` | `(if (%starlark-truth c) ..)`; a known Boolean (a comparison, `not`, `in`, `True`/`False`, a variable only such values are bound to, `bool(..)`, `s.startswith(..)`) is the test as it stands, a known int `(not (= c 0))`; `and`/`or` of tests are `and`/`or` |
| `a and b` as a value | `(let ((v a)) (if (%starlark-truth v) b v))`; `(if a b nil)` when `a` is a known Boolean |
| `a[i] = v`, `a, b = v` | `(%starlark-set-index a i v)`; `(let ((u (%starlark-unpack v 2))) (setq a (aref u 0)) ..)` |
| `a, b = b, a + b` (a tuple or list of targets, a tuple or list expression of as many) | `(let ((e1 b) (e2 (+ a b))) (setq a e1) (setq b e2))`: no tuple; every element before the first target, pairing through nested ones |
| `x += v`, `a[i] += v` | `(setq x (%starlark-add-in-place x v))` (a list is extended in place), tested or inline like the binary operator; the target's operands bound once when they are more than a variable; `a[i] op= v` with a `v` that runs nothing (a constant, a variable) is `(let ((cell (%starlark-item-cell a i))) (if cell (rplacd cell (op (cdr cell) v)) (%starlark-set-index a i (op (%starlark-index a i) v))))` -- the cell of a dict nothing forbids writing that holds the key, nil for anything else, so a dict's item is one look-up and every refusal stays the generic pair's; `-=`, `\|=`, `&=`, `^=` the same through `-sub-in-place`, `-bit-or-in-place` .. (a set, and for `\|=` a dict, updated in place) |
| `s += piece`, `s = s + piece` in a `for` whose body spells `s` nowhere else (its pieces included), `s` a local assigned before the loop, no int or Boolean, spelled by no `def` or `lambda` of the function | `(let ((pieces (list s))) loop (setq s (%starlark-concat-reversed pieces)))`, each append `(setq pieces (cons piece pieces))`; `(if (stringp s) (list s))` and `(if pieces ..)` around both when `s` is not known to be a string, the append as it stands when it holds none; a piece that is no string `(%starlark-add-in-place (%starlark-concat-reversed pieces) piece)`, which refuses it. Joined at the loop's end and after `break`; a `return` or an error leaves a local nobody reads. The outermost such loop collects. A global never: a Common Lisp caller or a later buffer reads it |
| call of a top-level `def` with a direct entry whose arguments fit | `(f a b)`, bound while lowering like a built-in's: positional and named arguments at their parameters, a default the constant it is written as or the global its `def` statement set, surplus ones as `(%starlark-make-tuple ..)` / `(%starlark-make-dict "k" v ..)`; `(progn (%starlark-global f "f") (f a b))` where the `def` may not have run |
| call of a built-in function whose arguments fit its signature | `(%starlark-b-sorted xs :none t)`: bound while lowering, defaults filled in |
| `x.m(..)` whose arguments fit | `(%starlark-m-append x v)`: the helper takes the receiver first and tests it |
| any other call | `(%starlark-call f (list pos..) (list "name" v ..))`, `*args` and `**kwargs` appended by `%starlark-elements` / `%starlark-keywords` |
| a built-in as a value, `x.m` uncalled | `(%starlark-builtin "len" '("x") nil 1 1 nil nil #'%starlark-b-len)`, `(%starlark-method x '("list") "append" ..)`: function values the binder takes |
| `x.nope`, `x.nope(..)` | `(%starlark-no-attribute x "nope")`: no type has it, so the arguments are never evaluated |
| a file | `(setq sl%(FILE)%SL-FILE (%starlark-file-begin))` ahead of its statements, `(%starlark-file-end sl%(FILE)%SL-FILE)` after them ("Freezing") |
| `load("m.star", "f", y = "x")` | the module's `defun`s and `(defvar sl%(m.star)%SL-FILE nil)` with the program's definitions; where the first load of it stands `(setq STATE (if STATE t (%starlark-file-begin)))`, each of its statements as `(if (consp STATE) stmt)`, `(if (consp STATE) (setq STATE (%starlark-file-end STATE)))`; then `(setq f sl%(m.star)f) (setq y sl%(m.star)x)`, through `(%starlark-loaded v "x" "m.star")` where a path of the module may not assign it ("Modules") |
| `load("@cl-user", f = "add-two")` | `(setq f (%starlark-foreign "cl-user:add-two" #'add-two))`; a call of `f` with positional arguments only is `(add-two a b)` ("Across languages") |
| `b".."` | `(%starlark-bytes-literal #(104 105))`: the reader's `byte[]` as a packed octet vector (`LispIntVector.wrapOctets`, `.kb/packed-integer-vectors.md`) |

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

- **Kinds** (`StarlarkLowering.Kind`, `kind`): INT, BOOL (exactly `T`/`NIL`), STRING
  (2026-11-16), OTHER (no int: a float, a string, a list ..), ANY. Read off the spelling
  -- a literal, an operator's result (`int op int`, a comparison, `not`, `string +
  string`, `string % x`), a built-in whose every return is one (`StarlarkBuiltins`'
  `-> int` / `-> bool` / `-> string`: `len`, `int`, `hash`, `find`, `index`, `count`,
  `bool`, `any`, `all`, `hasattr`, `startswith`, `isdigit`, `str`, `repr`, `type`,
  `join`, `format`, `replace`, `strip`, `upper` ..) -- and off variables.
- **A variable is an int when every binding of it in its block is one** (`bound`, `infer`):
  assignments (pairwise through `a, b = b, a + b`), augmented assignments, `for` and
  comprehension targets (an int over `range(..)`, the index of `for i, x in
  enumerate(..)`). All names of a block are assumed ints and the assumption is dropped
  name by name until every binding agrees; then the same for Booleans, then for strings
  (`s = ""` .. `s += "ab"`). Sound because
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
  `%` by a constant that is not zero as they stand, by any other divisor behind `(not (=
  b 0))` (2026-11-16: `(i * 7) % w` over a variable `w` 57 -> 27 ns on wasm, 14 -> 13 on
  the JVM), a shift only by a constant count that is not negative: the errors (`integer
  modulo by zero`, `negative shift count`) stay the helper's. The bitwise operators are
  inline over known ints only (`|` is also a dict union). `+` with a known string is
  `%string-concat`, behind `(stringp ..)` of an operand that is not known to be one.
- Not made, not measured: narrowing by a test (`type(x) == "int"`), the kind a `def`
  returns, the kinds of a list's elements, an inline `x[i]`.

## The run-time library's rules

`starlark.lisp` keeps eight, the first four stated at its top:

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
- **A string handed to the program is the host's IMMUTABLE string** (2026-11-16). What
  Common Lisp's producers answer -- `subseq`, `concatenate`, the case and trim functions,
  `coerce`, a string stream's contents -- is a fresh MUTABLE one, on the compiled
  backends a vector with an element per character
  ([string-write-runtime.md](string-write-runtime.md)) that every later comparison, hash
  and concatenation of it walks: `"w" + str(i)` took 10 us on wasm through `concatenate`
  and 0.2 us through `%string-concat`, and a string key so made hashed at 250 ns on the
  JVM. So two strings are joined by `%string-concat`, a slice is `(%substring s start
  end)` (the slice with no character vector made of it), a case fold
  `%string-upcase-piece` / `%string-downcase-piece`, a string stream's text
  `%string-stream-piece`, an int `%decimal-piece`, two strings are ordered by
  `%string-order` ([string-search-runtime.md](string-search-runtime.md)), and what a
  helper builds any other way is wrapped in `%normalize-string`. Several pieces are joined by halves (`%starlark-concat-pieces`,
  `-join-range`): a character is copied log N times, and no string stream is made, whose
  fixed cost a few pieces do not repay. `%starlark-concat-reversed` takes a string stream
  and `%string-stream-piece` past 64 pieces ("A string a loop appends to").
- **An `error`'s control is a literal string.** A computed one (`(error message ..)`, a
  `(concatenate ..)` control) bails the condition narrowing and keeps the run-time format
  renderer in every program (~50 KB of wasm, "Measured"): `%starlark-elements-or` takes the
  text before and after the type instead of a control.
- **A string method tests its receiver at its head and names itself only to refuse**
  (2026-11-16): `(if (not (stringp s)) (%starlark-no-attribute s "split"))`, and the same
  for a string argument. A literal is evaluated where it stands -- on wasm a call of
  `_str_lit` and a table read then, a global's read since "A literal is a global" -- so
  `(%starlark-string-receiver s "split")` made the name on every call to hand it to a
  check that passes; `_str_lit` was 7% of `methods.star`.
  A list or dict method's `-receiver` call sits behind its own test the same way
  (`%starlark-m-append`; `pop()` of a list's last element since 2026-11-16).
- **A whole sequence is the backend's when it has a function for it** (2026-11-16):
  `split` by a separator, `join`, `count` and `sorted` ask `%string-split`,
  `%string-join`, `%string-count` and `%sort-by-keys` first
  ([bulk-primitives.md](bulk-primitives.md)) and run their own loop --
  `%starlark-split-vector`, `-join-vector`, `-merge-sorted-vector`, a search an
  occurrence -- for nil,
  which is every call on the interpreter and the JVM. The loop is a `defun` of its own,
  so on wasm it is one function nothing reaches for a string. `extend` and `+=` of lists
  give the vector its room once and move the elements with one `replace`
  (`%starlark-push-all`: a full vector grows at its next push by what the push asks for,
  here what it lacks and as much again as it has at least).
- **A list written to be appended to starts with room for four** (2026-11-17):
  `%starlark-vector` and `[]` (`%starlark-list-of` of no element). A vector made with no
  room grows at its first, second and third push -- the growth policy doubles from a
  floor of one (`.kb/adjustable-arrays.md`, "The growth policy"), and that capacity is
  what Common Lisp's `array-dimension` reads, so the policy stays -- and the lists a
  build file appends to mostly hold three elements or fewer. A literal with elements is
  made at their number.

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

## Bytes and sets (2026-11-15)

**A set is a structure over a dict of its own** (`%starlark-set`, slot `dict`), the
elements its keys, every value `:none`: hashing (`%starlark-key`), order, the frozen cell,
the loop count, frames and `%starlark-unwind` are the dict's, unchanged. A loop over a set
counts on its dict (`%starlark-iterate` recurs on it). Rejected: `(:include
%starlark-dict)` -- every `dict-p` dispatch (`get`, `keys`, `**`, `x[k]`, `dict(x)`, `|`)
would take a set for a dict unless guarded one by one. **A bytes is a structure over an
`(unsigned-byte 8)` vector** (`%starlark-bytes`, slot `octets`), so it is neither a
string nor a list on any backend; the literal's vector is shared, nothing writes it.

- Spec, where Go differs: `b[i]` is an int (Go: a one-byte bytes); `<` .. on sets is
  `unsupported comparison` (Go: subset/superset); `set()` prints `set()` (Java, Python;
  Go `set([])`). Go's: `repr` of a bytes is `syntax.Quote(.., true)` -- valid UTF-8 as
  a string's text, any other octet `\xHH` (one decoder, `%starlark-utf8-decode`, serves
  it and `str`, which gives U+FFFD per such octet); `hash` of a bytes is 32-bit FNV-1a,
  non-negative; the messages (`int in bytes: .. out of range ..`, `'in bytes' requires
  ..`, `bytes: at index i, ..`, `pop: empty set`).
- `bytes(str)` is the UTF-8 of the string's code points (a surrogate code as U+FFFD's), so
  the spec's `bytes("hello 😃"[:-1])` example (a broken sequence) cannot arise.
- `|=` of two dicts updates in place, as the spec and Go's `INPLACE_PIPE` say; until this
  item it rebound a new dict. Mutating `|=`, `&=`, `-=`, `^=` check the target as a method
  does (`cannot apply |= to frozen set`).
- `dict|set.update(*args, **kwargs)` is one helper (the table's rule for a shared name):
  `d.update(1, 2)` is now the helper's `update: got 2 arguments, want at most 1`.
- `set.pop()` lists the keys first (O(n)), like `dict.popitem`: `maphash` cannot stop
  early without a non-local exit, which is EH mode on wasm.
- Sizes (`.class` / `.wasm` / component, before -> after; base `9e5d00733`): `print(1 +
  2)` 174,520 / 176,175 / 179,914 -> 184,183 / 184,176 / 187,837 (+9.7 / +8.0 / +7.9 KB:
  `%starlark-write`, which `print` reaches through `str`, dispatches on both types and
  carries the UTF-8 decoder and the set writer); the prototype's `demo.star` 289,665 /
  264,109 / 267,848 -> 309,218 / 285,364 / 288,950; `bench.star` 191,366 / 190,650 /
  194,481 -> 213,420 / 215,306 / 219,037; `examples/starlark/targets.star` 274,854 /
  259,013 / 262,721 -> 294,885 / 281,584 / 285,176. A generic operator pays for its new
  branch: `def f(n): return n - 1` plus `print(f(3))` 177,199 / 177,354 -> 194,998 /
  195,369 (`.class` / `.wasm`; `%starlark-sub`'s set branch brings the set operations and
  `%starlark-key`), with `n + 1` 179,607 / 179,206 -> 192,869 / 193,356 (the bytes
  branch). What every program paid besides: "Measured", "The fixed cost".

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
  independent of insertion order, and a `def` key has no order. A frozen set too, for the
  same reason (the item asked to decide it as for a list, 2026-11-15).
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
  0.28-0.34 s, three runs each, before and after). What tripled `print(1 + 2)`: "The
  fixed cost" below.
- `bench.star` (`fib(27)` plus a 10^6-iteration `for` over `range`), wall time of one run,
  three runs each:

  | runner | prototype | this lowering |
  |---|---|---|
  | interpreter | 5.51 s | 4.33-4.98 s |
  | JVM `.class` | 0.45-0.50 s | 0.29-0.36 s |
  | WASM preview 1 | 0.39-0.40 s | 0.28-0.37 s |
  | WASM component | 0.35-0.38 s | 0.28-0.33 s |
  | CPython 3.12 | 0.10-0.13 s | |

  The prototype's row "Java Starlark 0.26-0.29 s" was the time to an error: Java Starlark
  forbids recursion and `bench.star` exits 1 with `function 'fib' called recursively`
  (found 2026-11-16). The other implementations: "Against the other implementations" below.

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

### The fixed cost (2026-11-15)

Measured on the wasm module's call graph (`-Drontolisp.wasm.debug-func-sizes` names each
function index, `wasm-tools print` gives the calls) as a dominator tree: the bytes a
function alone keeps reachable. `print(1 + 2)` at `999694f05`, 150,961 B of function
bodies: `%condition-report-str` kept 53.6 KB, of it the run-time format renderer (`%fmt-*`,
93 functions) 50.9 KB; `%starlark-write` 30.0 KB, of it `%starlark-format-float` 22.2 KB;
`%print-object-str` (`%pos-walk`) 8.7 KB. The commits between the two measurements above
were not bisected one by one: the tree names what a program keeps, which is what a
bisection was to find.

- The chain: every Starlark program is in EH mode on wasm (`%starlark-b-repr` and
  `%starlark-join-strings` are `with-output-to-string`, an `unwind-protect`), which is
  what prints an uncaught error's message there (outside EH mode it is a bare trap). So
  messages are rendered and any constructible condition routes reports, as on the JVM
  always. The float writer reaches the prelude's `integer-decode-float` /
  `float-precision` / `float-sign`, whose `check-type` signalled `:format-control
  "..~s.." :format-arguments (list x)`: the renderer. Independently,
  `%starlark-elements-or`'s computed `(error message ..)` bailed the narrowing.
- Made: `check-type` renders its report at the signal
  ([declarations-type-checks.md](declarations-type-checks.md)), and the run-time library's
  fourth rule ("The run-time library's rules"). Either alone gains nothing: only the
  `check-type` change leaves `print(1 + 2)` at 184,430 / 184,224 / 187,922 B, only the
  library's 178,124 / 179,508 / 183,398 B.
- Sizes (`.class` / `.wasm` / component, before -> after): `print(1 + 2)` 184,193 /
  184,176 / 187,837 -> 107,738 / 98,133 / 101,658; the prototype's `demo.star` 309,215 /
  285,364 / 288,950 -> 237,401 / 206,596 / 210,570; `bench.star` 213,414 / 215,306 /
  219,037 -> 137,893 / 127,685 / 131,399; `examples/starlark/targets.star` 294,873 /
  281,584 / 285,176 -> 223,222 / 202,872 / 206,806. Output unchanged on the four.
- Left, not made: `%starlark-write` dispatches on every type, so a program printing any
  value keeps the float writer (24.5 KB of the 82.8 KB of function bodies `print(1 + 2)`
  has now). Dropping it needs a proof that no float can exist (a literal, `/`, `float`,
  a value from `load("@package")`).

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

### Against the other implementations (2026-11-16)

At `82f86e5ba`, seconds, the best of three runs of the whole process (start-up included),
`time.sh` for rontolisp; Go Starlark (`go.starlark.net` `4f4267fb140d`, `cmd/starlark
-recursion`; its start-up 0.01 s), Java Starlark, CPython 3.12. Programs: `bench.star` of
the prototype, the rest of `.todo/artefacts/j51-starlark-typed-fast-paths/`. Every runner
printed the same output.

Java Starlark is `starlark_deploy.jar` of bazelbuild/starlark `c0af0ea`'s `test_suite`
behind `~/.local/bin/starlark-java`, a runner over
`~/.local/share/starlark-java/RecursiveMain.java`: `cmd.Main`'s file options with
`StarlarkSemantics`' `allow_recursion` and `experimental_enable_starlark_set` set. Its
own `cmd.Main` forbids recursion, as the spec lets an implementation, and ends
`bench.star` and `fib.star` with `function 'fib' called recursively`. Its start-up alone
is 0.30 s. The Java column was measured through the runner the same day, with the
"before" run of "Against CPython" below.

| program | JVM | WASM | component | Go | Java | CPython |
|---|---|---|---|---|---|---|
| `bench.star` | 0.16 | 0.07 | 0.07 | 0.54 | 0.82 | 0.12 |
| `fib.star` (`fib(35)`) | 0.28 | 0.45 | 0.44 | 22.02 | 4.55 | 1.48 |
| `loop.star` (`range(30,000,000)`) | 0.20 | 0.69 | 0.64 | 3.80 | 2.41 | 1.82 |
| `calls.star` | 0.22 | 0.27 | 0.30 | 7.31 | 2.03 | 0.75 |
| `mixed.star` (Collatz, sieve) | 2.15 | 2.53 | 2.57 | 4.60 | 3.15 | 1.22 |

Go is the implementation the error texts follow, so it is the one to compare against; Java
Starlark is a second opinion on semantics only. rontolisp compiles ahead of time and the
three others interpret, so this is not a like-for-like contest. What `mixed.star` lost
to CPython on is below: the `//`, not `flags[j]`.

### Against CPython (2026-11-16)

**Goal: a program compiled to the JVM, WASM preview 1 and the component runs in less wall
time than `python3` on the same file** (best of three, the whole process). Met on
the JVM for five of the eleven (`fib`, `loop`, `calls`, `mixed`, `listidx`) and on
wasm and the component for four (`bench`, `fib`, `loop`, `calls`) -- five with `mixed` since
"The list element on wasm", six with `listidx` since "An int test compiled twice",
seven with `dicts` since "Where the time is", ten with `comps`, `strings` and `sorting`
since "A sequence at a time" and all eleven with `methods` since "A literal is a global",
all below; on the JVM six with `dicts` since "Where the time is", and no more since "The
JVM's arms", which brought three of the other five nearer. Where it is not -- the JVM's
five -- the cost left is a backend's and is named below with the item that carries it.
**The eleven are not what a build file does**: a program in the shape of
`examples/starlark/targets.star` is 2.3 times CPython on wasm and 3.8 on the JVM ("A
program a user wrote").

The five programs above cover integers, calls and `range`. Six were added, each about a
second of CPython, each printing a checksum CPython, Go and Java Starlark agree on
(`.todo/artefacts/j54-starlark-faster-than-cpython/`, with `time.sh`): `listidx.star`
(`a[i]`, `a[i] = x`, `a[i] += x`), `dicts.star` (get, set, `in`, the three iterations, int
and string keys), `strings.star` (`+`, `+=`, `str`, `join`, `split`, `format`, `%`),
`comps.star` (list and dict comprehensions), `sorting.star` (`sorted` of ints and of
strings, by a key, reversed; `min`, `max`, `reversed`), `methods.star` (methods on
parameters and list elements). Seconds, before (`72cf29cc8`) -> after; Go and Java
Starlark do not change:

| program | JVM | WASM | component | CPython | Go | Java |
|---|---|---|---|---|---|---|
| `bench.star` | 0.15 -> 0.15 | 0.07 -> 0.07 | 0.07 -> 0.07 | 0.14 | 0.54 | 0.82 |
| `fib.star` | 0.30 -> 0.31 | 0.46 -> 0.41 | 0.41 -> 0.44 | 1.46 | 21.80 | 4.55 |
| `loop.star` | 0.19 -> 0.18 | 0.69 -> 0.68 | 0.67 -> 0.62 | 1.63 | 3.86 | 2.41 |
| `calls.star` | 0.19 -> 0.21 | 0.25 -> 0.28 | 0.27 -> 0.25 | 0.76 | 7.36 | 2.03 |
| `mixed.star` | 2.08 -> 0.64 | 2.79 -> 1.60 | 2.55 -> 1.58 | 1.21 | 4.58 | 3.15 |
| `listidx.star` | 1.37 -> 1.08 | 7.10 -> 4.83 | 6.42 -> 4.90 | 1.20 | 3.55 | 2.51 |
| `dicts.star` | 0.99 -> 1.02 | 2.33 -> 1.67 | 2.16 -> 1.73 | 0.92 | 4.63 | 2.10 |
| `comps.star` | 1.68 -> 1.95 | 2.45 -> 2.34 | 2.49 -> 2.29 | 0.91 | 7.99 | 1.78 |
| `methods.star` | 4.13 -> 2.10 | 6.21 -> 3.34 | 6.27 -> 3.36 | 1.12 | 9.31 | 3.64 |
| `strings.star` | 8.88 -> 2.68 | 26.88 -> 3.30 | 27.78 -> 3.29 | 1.14 | 8.07 | 3.23 |
| `sorting.star` | 9.59 -> 3.92 | 133.99 -> 5.11 | 133.25 -> 5.13 | 0.98 | 6.65 | 2.49 |

The JVM's `comps.star` ranges from 1.45 to 2.13 over five runs, before and after alike.
Sizes (`.class` / `.wasm` / component): `print(1 + 2)` 107,727 / 98,133 / 101,658 ->
108,363 / 99,723 / 103,275; the prototype's `demo.star` 237,576 / 206,596 / 210,570 -> 220,520 / 205,384 / 209,384;
`examples/starlark/targets.star` 223,512 / 202,923 / 206,857 -> 216,399 / 203,655 / 207,618;
`strings.star` 172,896 / 153,788 / 157,694 -> 137,934 / 132,151 / 135,952 (no template is read at
run time); `listidx.star` 167,159 / 156,160 / 160,029 -> 168,790 / 165,246 / 169,145 (`replace`'s
run time, which a list copy now reaches).

Found, by sample profiles (JFR's `jdk.ExecutionSample` every 1 ms; `wasmtime run
--profile=guest`, whose function numbers `-Drontolisp.wasm.debug-func-sizes` names) and
by plain Common Lisp loops over each primitive:

- **Every string the library made was a mutable character vector** ("The run-time
  library's rules"): `sorting.star` at a third of its size was 44 s on wasm, 79% of it
  `%string-compare` over two character vectors.
- **`//` was a ratio.** `(values (floor a b))` is `(floor (/ a b))` on every backend: 142
  ns for `x // 2` on the JVM and 59 on wasm, against 39 and 17 for `(ash x -1)`; 40% of
  `mixed.star`'s JVM samples were `BigInteger`. `floor` itself no longer makes a ratio of two
  integers (2026-11-16: 16 ns on the JVM, 9 on wasm, `.kb/linalg-simd.md`, "Two integers").
- **The library went through Common Lisp lists**: `[0] * n` (a list, then a push per
  element, where `make-array .. :initial-element` is one call), `sorted`
  (`stable-sort` over decorated conses behind a `funcall`, and `string<` then `string>`
  per comparison), `keys`/`values`/`items`/`list()`/`extend`/`reversed`/`enumerate`
  through `%starlark-elements`, `join`/`format`/`%` through a string stream.
- **`search` behind its keywords**: 31% of `methods.star` on wasm was `SEARCH`, of which
  the backend's own `_str_search` 8.6% and the keyword parsing the rest; on the JVM it is
  the prelude's `elt` loop. `%starlark-search` calls `%string-search` and, where that
  declines, scans itself (the interpreter keeps `search`, the host's own scan there).
- **A template was read on every call**: `"{}-{}".format(i, "x")` and `"%d:%s" % (i, s)`
  together 4.8 us on the JVM and 5.7 on wasm, 1.5 and 0.9 read while lowering (CPython
  0.5).
- **Generic `%` by a variable** was the helper's call: 57 -> 27 ns on wasm tested inline.
- **The JVM's fixed cost**: `print(1)` is 0.10 s (CPython 0.03, wasm 0.03), and
  `dicts.star` at a quarter of its size was 0.53 s where four times the work is 0.92: the
  first 0.4 s is start-up and warm-up. A program CPython runs in a quarter of a second
  cannot win on the JVM; the six are a second each for that reason.
- **wasm calls for every generic operation** (ns per iteration of a counting loop, plain
  Common Lisp): the loop and a `+` alone 9.4, `(aref v i)` of a general vector 28 (three
  calls: the subscript's check, `_idx_ref`, `_arr_get`), a store 52, a `dolist` step 6.5,
  a structure's predicate and one accessor 15 (a call each), `(char-code (char s i))`
  about 30 (a character is an allocation), a comparison of two boxed fixnums a call and a
  true answer another (`_t_sym`). A Starlark `xs[i]` is some nine calls, 74 ns behind a
  `funcall`, where CPython's whole loop step is about 35. A `(declare (type (vector t)
  ..))` of the list's vector changed nothing (368 against 369 ms).
- **The JVM boxes**: `Long.valueOf` is 32% of `comps.star`'s samples. Not the index
  `vector-push-extend` answers (remeasured 2026-11-16, four JFR runs of 169-283 samples
  each: 0-3 samples below `_vectorPushExtend`, 14-20 below `_ubRead`, the boxed read of a
  raw local, i.e. the ELEMENTS a general vector holds as `Long`s). Returning the header's
  own fill-pointer `Long` as the answer, so nothing is boxed for it, moved nothing: `comps`
  JVM 1.9 s before and after, a 100,000-element `vector-push-extend` loop 1.47 s against
  1.49 s (HotSpot already drops a box nobody reads). Not made.
- **A sort is a list's**: 50,000 ints by a merge sort over conses 13 ms on the JVM and 17
  on wasm, over a simple vector 30 and 139, over packed `(unsigned-byte 32)` vectors 79
  and 42; CPython's is 9. So `sorted` merges lists, compares ints inline, and keys that
  are all strings of at most 64 characters as lists of integers read once (four
  characters to an integer when all are below 127).

Made: the run-time library's strings and vectors ("The run-time library's rules"),
`%substring` ([string-write-runtime.md](string-write-runtime.md)), the STRING kind, a
template written as a literal read while lowering, `//` and `%` by a variable tested
inline, `//` as a shift or an exact division, a dict comprehension storing into its own
dict ("The lowering table", "What the lowering knows"). `"abc".replace("", "-")` is
`-a-b-c-` as Go's and CPython's is (it lacked the last one).

Left, each with its reason:

- **`bench.star` on the JVM** (0.15 against 0.11-0.14 over the two runs): the start-up. Restated: on the
  JVM the goal is for a program of a second of CPython; wasm takes the short ones.
- **wasm, the six container programs**: the calls above. The vector read and the
  structure calls: "The list element on wasm" and "The cell names its storage" below;
  strings: "Strings nothing writes, ordered by the backend" below.
- **The JVM, `comps`, `methods`, `strings`, `sorting`**: boxing of the elements (the vector holds `Long`s; above), a string
  framed in quotes (a slice is three allocations), `upper`/`lower` through the mutable
  producers (made since, below), and a sort that chases conses (30% of `sorting.star`'s
  samples are `_car`).
- **`s += piece` in a loop** copied the string so far, as Go's and Java's do; CPython
  extends in place. Made since: "A string a loop appends to" below. `strings.star` still
  accumulates 5,000 pieces, so its row stays comparable.

### The list element on wasm (2026-11-16)

What the item above left to the backend, measured before anything was changed
(`listidx.star`, `wasmtime run --profile=guest`, then hand-edited `.wat` and plain Common
Lisp loops, because the profiler charges a large function's samples to its small
callees): of the three suspects the vector access was the most, 1.7 s of the 4.86 (the
read's `_idx_ref` and `_arr_get`, the store's `_idx_chk`, `_arr_check_rank` and
`_arr_set`) and 0.5 s more for a `stringp` of the vector ahead of every store (19 ns of
`_charvec_p`); the structure calls 0.33 s, the boxed comparison 0.1 s. **And the premise
was half wrong: it is not only the calls.** A type test that passes costs about a
nanosecond and an access made some 35 of them; a call costs 2-5 ns with references live
across it (`.kb/adjustable-arrays.md`, "What an element access costs").

Made, all in the wasm backend at the speed levels unless said: the inline arm of a
general vector's rank-1 read and store (same file); a structure's predicate and reader
as the type test and the slot read at the call site (`.kb/defstruct.md`); two fixnums
compared at the site of an unfused comparison (`.kb/wasm-int-fusion.md`); at every
level, `_charvec_p` reading its marker first and `_seq_len` asking for a general vector
first (`.kb/length-runtime.md`). In the library, `%starlark-set-index` stores through the
accessor's value, not a variable holding it, so no `stringp` is asked (2.67 -> 2.16 s).

Seconds as above (`time.sh`, best of three, same machine and day), before -> after;
the JVM column moves only with the library's store:

| program | JVM | WASM | component | CPython | `.wasm` bytes |
|---|---|---|---|---|---|
| `bench.star` | 0.15 -> 0.16 | 0.07 -> 0.07 | 0.06 -> 0.07 | 0.14 | 127,577 -> 137,197 |
| `fib.star` | 0.32 -> 0.29 | 0.44 -> 0.42 | 0.42 -> 0.44 | 1.48 | 126,721 -> 136,341 |
| `loop.star` | 0.17 -> 0.17 | 0.67 -> 0.66 | 0.62 -> 0.65 | 1.64 | 100,659 -> 105,157 |
| `calls.star` | 0.21 -> 0.20 | 0.24 -> 0.26 | 0.25 -> 0.25 | 0.75 | 110,782 -> 116,866 |
| `mixed.star` | 0.68 -> 0.72 | 1.61 -> 0.92 | 1.58 -> 0.89 | 1.20 | 162,989 -> 174,632 |
| `listidx.star` | 1.12 -> 1.11 | 5.31 -> 2.08 | 4.87 -> 2.03 | 1.21 | 165,246 -> 177,404 |
| `dicts.star` | 0.92 -> 0.98 | 1.70 -> 1.42 | 1.73 -> 1.36 | 0.93 | 166,334 -> 178,803 |
| `comps.star` | 1.62 -> 1.47 | 2.29 -> 1.64 | 2.32 -> 1.68 | 0.87 | 162,627 -> 174,080 |
| `methods.star` | 2.15 -> 2.13 | 3.34 -> 2.91 | 3.36 -> 2.93 | 1.10 | 158,307 -> 169,836 |
| `strings.star` | 2.45 -> 2.42 | 3.31 -> 3.22 | 3.31 -> 3.22 | 1.16 | 132,151 -> 137,809 |
| `sorting.star` | 3.78 -> 3.80 | 5.02 -> 3.97 | 5.07 -> 3.98 | 0.96 | 173,787 -> 188,237 |

- Step by step on `listidx.star` (wasm, user seconds): 4.86; the array arm 3.15; the
  structure calls 2.82; the comparison 2.77 (2.17 -> 2.07 measured again at the end);
  `_seq_len`'s order 2.67; the store without `stringp` 2.16; `_seq_len` reading its
  header once 2.06.
- **The bytes**: a Starlark module is 4-9% larger (the library's own element accesses,
  structure reads and comparisons carry the arms whether the program runs them or not):
  `print(1 + 2)` 99,723 -> 104,221, `examples/starlark/targets.star` 203,655 -> 222,307.
  `--optimize=size` declines all of it (`targets.star` 150,231 -> 150,139 there).
- Left then, made since ("The cell names its storage" below): per `xs[i]` a call of
  `%starlark-index`, a boxed read of the loop variable (`_ub_read`), `length` of the
  items (`_seq_len`: a call and seven tests) and the arm's nine tests, three hops through
  `(ref null eq)` fields. `listidx` was still 1.7 times CPython on wasm, the JVM under it.

### The cell names its storage (2026-11-16)

What the section above left, each candidate measured first on the module of the day
edited as text (`.todo/artefacts/j61-wasm-vector-cell-fields/`): the arm's nine tests
were 0.33 s of `listidx.star`'s 2.06, the `length` of the items 0.2-0.3 s, a second cast
per structure slot 0.1 s, and the boxed read of the loop variable 0.03 s of `loop.star`'s
0.69.

Made, in the wasm backend: the cell of a rank-1 general vector names its storage and its
meta cons in typed fields, so an element access is one cast of the array and
`vector-push-extend` with room another (`.kb/adjustable-arrays.md`, "What an element
access costs"); `length` reads the fill pointer off the same field, in `_seq_len` and
inline where the site knows a general vector (`.kb/length-runtime.md`); an instance's
slots field is of the slots array's own type (`.kb/defstruct.md`); `(vector t)` proves
the general representation (`.kb/declarations-type-checks.md`). In the library: the
`items` slot of a list and of a tuple is `:type (vector t)`, which is what tells every
`(length items)` and `(aref items i)` of the file what it holds, and `%starlark-set-index`
binds the items once (a variable place of that type is asked nothing).

Seconds as above (`time.sh`, best of three, same machine, the two builds an hour apart),
before -> after; the JVM column does not move (1.13 for `listidx`):

| program | WASM | component | CPython | `.wasm` bytes |
|---|---|---|---|---|
| `bench.star` | 0.07 -> 0.07 | 0.07 -> 0.07 | 0.14 | 138,491 -> 136,334 |
| `fib.star` | 0.40 -> 0.40 | 0.40 -> 0.41 | 1.45 | 137,635 -> 135,478 |
| `loop.star` | 0.64 -> 0.69 | 0.63 -> 0.67 | 1.74 | 105,905 -> 104,673 |
| `calls.star` | 0.29 -> 0.29 | 0.25 -> 0.25 | 0.76 | 118,993 -> 117,532 |
| `mixed.star` | 0.90 -> 0.72 | 0.84 -> 0.68 | 1.30 | 176,579 -> 174,162 |
| `listidx.star` | 2.04 -> 1.24 | 2.03 -> 1.27 | 1.25 | 179,622 -> 177,050 |
| `dicts.star` | 1.32 -> 1.35 | 1.34 -> 1.31 | 0.96 | 180,984 -> 178,573 |
| `comps.star` | 1.68 -> 1.16 | 1.71 -> 1.14 | 0.94 | 175,749 -> 174,042 |
| `methods.star` | 2.97 -> 2.84 | 2.97 -> 2.70 | 1.14 | 172,597 -> 170,608 |
| `strings.star` | 3.18 -> 3.11 | 3.20 -> 3.26 | 1.16 | 139,921 -> 139,227 |
| `sorting.star` | 3.11 -> 2.93 | 3.13 -> 2.96 | 0.96 | 186,996 -> 184,463 |

The four programs with no list (`bench`, `fib`, `loop`, `calls`) are the run's noise:
0.05 s either way.

- Step by step on `listidx.star` (wasm, user seconds): 2.07; the fields under the arm
  1.68; `_seq_len` reading the meta field 1.51; the typed slots field 1.35; `length`
  inline at the sites the slot's type reaches 1.30; the items bound once in
  `%starlark-set-index` 1.21.
- **The bytes go down**: the arm is 58 B where the walk was 111. `print(1 + 2)` 104,969
  -> 103,734, `examples/starlark/targets.star` 220,897 -> 217,397.
- **Not made, measured**: the boxed read of the loop variable (`for i in range(n)` copies
  the counter, a raw local, into the variable through `_ub_read`): every `_ub_read` of
  `loop.star` inline is 0.69 -> 0.66 s for 24 B at each of 88 sites, and one call an
  iteration of `listidx.star`. The clear of the multiple-value channel an inlined
  structure reader keeps (`.kb/defstruct.md`): 0.02-0.03 s of `listidx.star`'s 1.21 with
  all thirteen of the two index helpers removed.
- **Left then, measured and made since** ("An int test compiled twice" below): `listidx`
  was CPython's time (1.22-1.27 against 1.20-1.25 over the runs), not under it. Read off
  the module's text, not measured one by one: an element paid the call of
  `%starlark-index` / `-set-index`, the instance test repeated at each reader of one
  variable (five in a store), the generic `+` behind an `integerp` test of two variables
  (one call where a fused tree is inline, `.kb/wasm-int-fusion.md`) and the `br_table` of
  the loop's `tagbody`.

### An int test compiled twice (2026-11-16)

What "The cell names its storage" left to `.todo/j62`, each candidate measured first on
the module of the day edited as text, or read off its machine code (`wasmtime objdump`),
`.todo/artefacts/j62-wasm-fixnum-versions/`. **The premise overturned: no construct of an
element access is the cost, their number is.** A hand-written head of sixty instructions
in front of each index helper was `listidx.star` 1.20 -> 0.79 s, and what the item named
was little of it: the repeated instance casts and their channel clears 0.01 s, the
subscript's repeated tests 0.04, the cell's second cast 0.02, all three together 0.14 of
the 0.41; the compiled helper is two hundred instructions (`.kb/wasm-int-fusion.md`, "An
integer test compiled twice", for what each costs).

Made, in the wasm backend at the speed levels: an `if` over `integerp` tests of variables
is compiled twice, the copy for fixnums over unboxed locals -- which is every operator
the lowering tests (`(if (and (integerp a) (integerp b)) (+ a b) (%starlark-add a b))`:
six type tests and a call of the generic addition before, two fixnum tests and an
`i64.add` now) and the subscript test of the index helpers; an `if` in a test is its arms
as tests, so `if n < 2:` boxes no `t`; a `mod`, `rem` and `*` by a literal are inline in
a fused tree (`loop.star`'s `i % 7` was a call and a 64-bit division); at every level a
hash table's chain asks `ref.eq` before `_equal` (`.kb/hash-tables.md`). In the library:
`%starlark-index` and `%starlark-set-index` hold the list case alone -- a list nothing
forbids writing, subscripted by an int within it -- and hand everything else to
`%starlark-index-of` / `%starlark-set-index-of`, the general functions they were, so the
compiled head is a few tests and one access with no frame of the general function's size
around them (`listidx.star` 1.23 -> 1.15 s by that alone).

Seconds as above (`time.sh`, best of three, same machine, the two builds run in turn),
before -> after; the JVM column is the library's heads and its own spread from run to run
(`sorting.star` 2.70-3.11 before and 2.96-3.39 after over seven runs each):

| program | JVM | WASM | component | CPython | `.wasm` bytes |
|---|---|---|---|---|---|
| `bench.star` | 0.15 -> 0.16 | 0.07 -> 0.05 | 0.06 -> 0.05 | 0.11 | 136,334 -> 137,273 |
| `fib.star` | 0.27 -> 0.27 | 0.43 -> 0.21 | 0.41 -> 0.17 | 1.44 | 135,478 -> 136,407 |
| `loop.star` | 0.18 -> 0.18 | 0.67 -> 0.39 | 0.66 -> 0.37 | 1.60 | 104,673 -> 104,883 |
| `calls.star` | 0.27 -> 0.27 | 0.29 -> 0.17 | 0.24 -> 0.14 | 0.75 | 117,532 -> 118,225 |
| `mixed.star` | 0.69 -> 0.72 | 0.71 -> 0.56 | 0.64 -> 0.51 | 1.20 | 174,162 -> 177,760 |
| `listidx.star` | 1.15 -> 1.18 | 1.21 -> 0.95 | 1.19 -> 0.93 | 1.18 | 177,050 -> 180,624 |
| `dicts.star` | 0.99 -> 1.03 | 1.27 -> 1.17 | 1.24 -> 1.15 | 0.89 | 178,573 -> 181,253 |
| `comps.star` | 2.01 -> 1.76 | 1.11 -> 1.03 | 1.09 -> 1.04 | 0.90 | 174,042 -> 176,817 |
| `methods.star` | 1.98 -> 1.92 | 2.59 -> 2.54 | 2.63 -> 2.52 | 1.10 | 170,608 -> 173,219 |
| `strings.star` | 2.39 -> 2.60 | 3.04 -> 3.03 | 3.08 -> 3.03 | 1.13 | 139,227 -> 139,497 |
| `sorting.star` | 2.70 -> 2.99 | 2.98 -> 2.80 | 2.95 -> 2.77 | 0.98 | 184,463 -> 186,618 |

- Step by step (wasm, user seconds of the precompiled module, best of five): `.kb/wasm-int-fusion.md`.
- **Six of the eleven are under CPython on wasm** (`bench`, `fib`, `loop`, `calls`,
  `mixed`, `listidx`) and on the component; five are not.
- **The bytes**: a module is 0.2-2.1% larger (the copied forms, the two heads). `print(1 + 2)` 103,734 -> 103,934,
  `examples/starlark/targets.star` 217,397 -> 218,785; Common Lisp programs (`.wasm`, default
  level): `size-report/programs/zlib` 152,038 -> 151,982, `bench-report/programs/sort.lisp` 20,656 -> 20,654, `hash.lisp` 10,502 -> 10,552, `sieve.lisp` and `hello_world` unchanged.
- **Not made, measured**: one instance test for several readers of one variable
  (`.kb/defstruct.md`); `_t_sym`, a call per `t` a function answers, as a `global.get`
  (`sorting.star` 2.99 -> 2.87 s, and every entry of a module would have to set the
  global first); the calls on paths the head never takes as tail calls (inside the
  noise).
- **Left then** (`.todo/j63`; what a sampler said of it: "Where the time is" below): the
  head at its call site -- the call, the loop variable boxed for it and unboxed in it,
  `length` boxed, the cell cast twice -- which is what stands between `listidx`'s compiled
  helpers and the hand-written ones; `dicts` (`d[k] += 1` is three `gethash`), and
  `strings`, `methods`, `sorting`, where the cost is the string runtime's. The guest
  profiler is no witness for any of them: it charged 52% of a loop of `gethash` to
  `puthash`, which the loop never calls.

### Strings nothing writes, ordered by the backend (2026-11-16)

`sorted` of strings compared them through `%starlark-compare` and a character loop (on
wasm an allocation per character), or, for keys of at most 64 characters, as lists of
their codes read once; `upper`, `lower`, `capitalize` and `title` and `repr` of a
container went through the public producers and `%normalize-string`, a character vector
made and rendered back per call. Made: `%starlark-less` asks `%string-order` after the
int test and before `%starlark-compare` (nil for anything but two strings); the code-list
sort is gone; the case methods call `%string-upcase-piece` / `-downcase-piece`, `title`
and `repr` take `%string-stream-piece`
([string-write-runtime.md](string-write-runtime.md)).

Seconds as above (`time.sh`, best of three), before -> after:

| program | JVM | WASM | component | CPython |
|---|---|---|---|---|
| `sorting.star` | 3.77 -> 3.01 | 4.22 -> 3.07 | 4.25 -> 3.13 | 0.94 |
| `methods.star` | 2.13 -> 2.01 | 2.93 -> 2.92 | 2.93 -> 2.92 | 1.13 |
| `strings.star` | 2.50 -> 2.55 | 3.36 -> 3.21 | 3.42 -> 3.22 | 1.15 |
| `sorted(words)` alone, 24 times 50,000 | 1.60 -> 1.08 | 1.92 -> 1.13 | 1.96 -> 1.13 | 0.32 |
| `s.upper().lower()` of 19 characters, 1,000,000 times | 1.56 -> 0.39 | 2.19 -> 0.92 | 2.19 -> 0.94 | 0.22 |

- `sorted(words)` with `%string-order` only behind `%starlark-compare` (two calls more per
  comparison): wasm 1.67, the JVM the same 1.02-1.08.
- `methods.star` folds a word of three to five letters 240,000 times: a few hundredths of
  a second of its three.
- Sizes (`.class` / `.wasm` / component): `print(1 + 2)` 108,358 / 104,221 / 107,790 ->
  108,352 / 104,217 / 107,786; `examples/starlark/targets.star` 216,392 / 222,307 / 226,361
  -> 213,285 / 219,198 / 223,252.

### A string a loop appends to (2026-11-16)

`s += "ab"` in `for i in range(n)` inside a `def` (`accumulate` of `strings.star` at three
sizes), seconds as above (`time.sh`, best of three), before -> after:

| pieces | JVM | WASM | component | CPython | Go | Java |
|---|---|---|---|---|---|---|
| 5,000 | 0.14 -> 0.11 | 0.05 -> 0.03 | 0.05 -> 0.03 | 0.03 | 0.02 | 0.34 |
| 20,000 | 0.57 -> 0.12 | 0.10 -> 0.03 | 0.09 -> 0.03 | 0.03 | 0.21 | 0.59 |
| 200,000 | 18.58 -> 0.23 | 5.10 -> 0.05 | -- -> 0.05 | 0.04 | 41 | 10.47 |
| 2,000,000 | -- -> 1.08 | -- -> 0.39 | -- -> 0.36 | 0.17 | -- | -- |

(`--`: not measured; Go's 41 s at 200,000 is the measurement the item started from.)

Before, every append was a `%string-concat` of the whole string. The lowering collects
the pieces of a local nobody reads while the loop runs and joins them once ("The lowering
table"). The join: by halves (`%starlark-concat-pieces`), 2,000,000 pieces were 1.65 s on
the JVM and 3.21 on wasm; through a string stream 0.83 and 0.36 -- and a stream costs a
fixed amount a few pieces do not repay: a function joining 4 pieces, called 1,000,000
times, is 0.57 s on wasm by halves and 1.50 through a stream (16 pieces 0.47 / 0.94, 64
0.42 / 0.63, 256 0.41 / 0.38; the JVM 0.86 / 0.89 at 4, 0.87 / 0.56 at 64). So
`%starlark-concat-reversed` joins up to 64 pieces by halves and more through a stream;
its other callers (`replace`, `format`, `%` read at run time) keep their numbers.
The JVM's 2,000,000 ranges from 0.69 to 1.48 over five runs. Sizes: `print(1 + 2)`
108,352 / 104,217 / 107,786 -> 108,366 / 104,217 / 107,786; `examples/starlark/targets.star` 213,285 / 219,198 / 223,252 -> 213,952 /
219,550 / 223,605 (the stream arm). Pinned by `StarlarkLoweringTest` (the row) and
`starlark-spec.yaml`'s `a-loop-gives-its-iterable-back-on-every-exit` (collected,
captured, a global, a list and an int, `break`, `continue`, nested loops, past 64
pieces) and `common-lisp-and-starlark-call-each-other` (the refused piece).

### Where the time is: a sampler of the machine code (2026-11-16)

What "An int test compiled twice" left to `.todo/j63`: five programs over CPython on wasm,
and a helper's head at its call site as the first thing to make. **The premise overturned
before anything was made.** A sampler of the machine code
(`.todo/artefacts/j63-starlark-under-cpython/`; `.kb/wasm-int-fusion.md`, "Profiling trap":
`ptrace`, no `perf`, the samples per function and per instruction) says the five do not
wait on the index helpers, and names for each what it does wait on -- none of which the
guest profiler or the hand-edited modules had shown:

| program | where its samples were, the build before |
|---|---|
| `dicts` | the wrappers around `gethash` 41% between them (`%starlark-dict-cell` 14%: its key test, the table's `hash-table-p`), `_ht_get` 30%, `_hash` 14% -- six look-ups a round, three of them `if k in d: d[k] += 1` |
| `comps` | the loops themselves 56%, the dict comprehension's store 37% (a miss in `gethash`, then `puthash`) |
| `sorting` | the library's merge sort over conses 68% (`%starlark-less` 15%, the halves walked to be found 9%), `_str_order` 17% |
| `strings` | `str(i)` 17% (the capture mode, then `_str_fresh` a byte a round), `%starlark-conversion` 19%, 15 of them `(find d "eEfFgG")` at its head, `_string_concat` 15%, `join` by halves over 1,200,000 pieces 10%, `len` of a string just made 6% |
| `methods` | `split` 31% (`_str_search` 13%, the slice 9%; its vector grown from nothing is 9% of a split alone), `_str_lit` 7% (among them a method's own name, made to be handed to a check that passes), a collection 8%, `list(xs)` and `extend` 3% (two thirds of them `_idx_ref`, `_arr_check_rank`, `_arr_set`) |

Made, in the library and the lowering, so on every backend:

- **`sorted` of ints compared nothing**: a radix sort in the library
  (`%starlark-radix-sorted`, 512 items or more whose keys are ints spanning less than
  2^32, a key and its item's place moved through `(unsigned-byte 32)` vectors). **Gone
  the same day**: the backend sorts ints and strings itself where it has a sort
  (`%sort-by-keys`, "A sequence at a time" below), and the library keeps the merge sort
  alone for everything else -- it takes its cells off the front as the recursion reaches
  them (`%starlark-sort-cells`: no half is walked to be found) and asks `%string-order`
  first for a list that starts with a string (`%starlark-merge-strings`).
- **`a[k] op= v` on a dict is one look-up** (`%starlark-item-cell`, "The lowering table"),
  a dict comprehension's store one (`%starlark-dict-put`), and a key is asked for an int
  before a string (an int failed the string test's two casts first).
- **A loop is `while`**, not `tagbody` and `go` ("The lowering table"): no `br_table` over
  a boxed counter on wasm, and a counter over a constant bound is a counted loop's
  (`.kb/wasm-counted-loops.md`).
- **`join` of more than 64 strings goes through a string stream**, as
  `%starlark-concat-reversed` does; `split` makes its vector with room for eight and reads
  the separator's length once; a list of a literal's elements is made at their number
  (`%starlark-list-of`); a string method, `get` and `setdefault` test their receiver
  themselves ("The run-time library's rules"); `%starlark-conversion` tests its letter with
  `char=`, an int under `%d` first.

In the wasm backend, for every language: a table operand walked once and a fixnum hashed
where it is read (`.kb/hash-tables.md`); a fixnum's text written straight into its bytes,
a string counted where its builder knows the count, a slice copied array to array
(`.kb/wasm-gc-strings.md`); `replace` between two general vectors as one `array.copy`
(`.kb/sequence-op-runtimes.md`); `_str_order`'s walk (`.kb/string-search-runtime.md`).

Seconds as above (`time.sh`, best of three, same machine, the two builds run in turn),
before -> after:

| program | JVM | WASM | component | CPython | `.wasm` bytes |
|---|---|---|---|---|---|
| `bench.star` | 0.15 -> 0.16 | 0.05 -> 0.05 | 0.05 -> 0.04 | 0.11 | 137,273 -> 138,255 |
| `fib.star` | 0.29 -> 0.29 | 0.17 -> 0.21 | 0.17 -> 0.17 | 1.46 | 136,407 -> 137,416 |
| `loop.star` | 0.18 -> 0.18 | 0.39 -> 0.33 | 0.41 -> 0.35 | 1.63 | 104,883 -> 105,799 |
| `calls.star` | 0.27 -> 0.26 | 0.14 -> 0.13 | 0.14 -> 0.13 | 0.72 | 118,225 -> 119,114 |
| `mixed.star` | 0.68 -> 0.68 | 0.52 -> 0.52 | 0.51 -> 0.48 | 1.23 | 177,760 -> 178,254 |
| `listidx.star` | 1.16 -> 1.09 | 0.93 -> 0.98 | 0.97 -> 0.93 | 1.20 | 180,624 -> 181,499 |
| `dicts.star` | 1.00 -> 0.92 | 1.20 -> 0.83 | 1.15 -> 0.86 | 0.89 | 181,253 -> 182,005 |
| `comps.star` | 1.92 -> 1.65 | 1.02 -> 0.93 | 1.01 -> 0.92 | 0.89 | 176,817 -> 176,608 |
| `methods.star` | 2.12 -> 1.90 | 2.51 -> 2.00 | 2.54 -> 2.00 | 1.09 | 173,219 -> 174,439 |
| `strings.star` | 2.65 -> 2.42 | 3.04 -> 1.50 | 3.03 -> 1.60 | 1.14 | 139,497 -> 140,638 |
| `sorting.star` | 2.72 -> 3.10 | 2.85 -> 2.14 | 2.84 -> 2.01 | 1.00 | 186,618 -> 208,637 |

- **Seven of the eleven are under CPython on wasm** and on the component (`dicts` with
  them, 0.83 against 0.89); four are not -- `comps` 0.93 / 0.89, `methods` 2.00 / 1.09,
  `strings` 1.50 / 1.14, `sorting` 2.14 / 1.00 -- and what each waited on was made
  since ("A sequence at a time" and "A literal is a global" below). The JVM column moves with the library: `dicts`, `comps`, `methods` and
  `strings` down, `sorting` up (below).
- Step by step (wasm, user seconds of the precompiled module): `dicts.star` 1.15 -> 0.84
  with the look-ups; `sorting.star` 2.77 -> 1.97 the radix sort -> 1.88 its helpers typed;
  `strings.star` 2.80 -> 1.70 `join` through the stream and no `find` -> 1.56 a fixnum's
  text -> 1.45 the counts -> 1.35 a literal list at its size; `methods.star` 2.45 -> 2.38
  until `split`'s vector and the bulk copy, 1.95 with them.
- The parts (`parts/*.star` of the artefacts, user seconds, before -> after against
  CPython): `sorted` of ints both ways 0.98 -> 0.54 / 0.43, by a key 0.73 -> 0.40 / 0.34,
  of strings 1.22 -> 1.07 / 0.35; `list(xs)` and `extend` 1.31 -> 0.43 / 0.25, `pop`
  0.61 -> 0.34 / 0.17, `split` 0.77 -> 0.56 / 0.25; `+` and `str` 0.48 -> 0.35 / 0.50,
  `join` 1.55 -> 0.54 / 0.19, `format` and `%` 0.87 -> 0.34 / 0.50.
- **A sorted string is four cache misses a comparison.** A third of `_str_order`'s samples
  under `sorted(words)` are the four instructions that first load an operand's header --
  two string structs, two byte arrays; 50,000 strings are 4 MB -- so its walk's shape
  moved nothing (`.kb/string-search-runtime.md`), and `sorted` of strings stayed three
  times CPython's until a key was read once ("A sequence at a time").
- **The JVM paid the radix sort's compilation** (while the library had it). Warm, a pass
  over 50,000 keys was 1.4-2.4 ms there; but C2 made 109 KB of code of the pass's 495
  bytes (the packed vector's generic accessors inlined) in 0.7 s and made it again after
  each branch it had not seen, ten seconds of compiler threads over `sorting.star`'s 24
  rounds: 2.72 -> 3.10 s in the table's run, 2.9-3.6 against 3.2-4.0 over four runs of
  each in turn. With C1 alone (`-XX:TieredStopAtLevel=1`) the same program was 10.6 ->
  8.9 s.
- **The bytes** (while the library had it): a program that called `sorted` carried the
  radix sort (9 KB of wasm) and, with its first packed vector, the arms that vector opens
  in every generic access of the library (10 KB; over general vectors declared `(vector
  t)` the sort of ints was a fifth slower, 0.63 against 0.50 s for `parts/o1`, and that
  much smaller). Sizes (`.class` /
  `.wasm` / component): `examples/starlark/targets.star` 215,209 / 218,785 / 222,841 ->
  253,163 / 246,745 / 250,676, at `--optimize=size` 149,376 -> 166,818 B of wasm;
  `print(1 + 2)` 109,440 / 103,934 / 107,503 -> 109,443 / 104,877 / 108,446. Common Lisp
  programs (`.wasm`, default level): `size-report/programs/zlib` 151,982 -> 152,940,
  `bench-report/programs/sort.lisp` 20,654 -> 21,410, `string.lisp` 24,843 -> 25,380,
  `hash.lisp` 10,552 -> 10,568, `sieve.lisp` and `hello_world` unchanged; their times do
  not move (`sort` 0.37 / 0.39 s, `hash` 0.50 / 0.48, `string` 0.74 / 0.73).
- **Not made, measured**: a merge sort over packed vectors in place of the one over
  conses (`ms.lisp`, `ms2.lisp` of the artefacts: 25 ms a sort of 50,000 ints, 21 with
  every index masked into a known range, the merge over conses 20 and the radix sort 8
  -- a store's subscript still reads an unboxed local boxed and each access casts its
  array again); the radix sort's keys in general vectors (a fifth slower, above); the
  items carried beside the keys in vectors declared `(simple-array t (*))`
  (`.kb/declarations-type-checks.md`: 66 ns a move).
- Pinned by `starlark-spec.yaml` `what-the-lowering-knows-answers-as-the-generic-paths`
  (`fast_sort`: 600 and 2,100 ints, negative, by a key both ways with the places of equal
  keys, a span past 2^32, floats and strings on the merge; `fast_items`: an item updated
  through its cell by each operator, a comprehension storing a key twice),
  `common-lisp-and-starlark-call-each-other` (the cell of a frozen dict and of a key that
  is not there), `StarlarkLoweringTest` (the rows), and the conformance corpus
  (`rust/mutation_during_iteration.star`: `dict[k] += 1` in a loop over it); the backend's
  halves by ci-spec `typed-vector-struct-packs-and-replace-bulk`,
  `format-nil-princ-to-string-prin1-to` and `string-comparison-family`.

### A sequence at a time (2026-11-16)

What "Where the time is" left to `.todo/j64`: four programs over CPython on wasm, with a
plan -- a primitive each for `sorted` of strings, `split` and `join`, then a loop's
counter for `comps`. The primitives were made. **The counter's premise overturned**: the
sampler's instruction counts put `comps.star` in the GROWTH of the vector its
comprehensions push into, not in its loops' heads, and three things nobody had named
were in the way of the others -- `stringp` asked of a vector just made, a list extended
an element a call, a number's text and each link of a concatenation a pair of objects.

Made, in the wasm backend, for every language:

- **`%string-split`, `%string-join`, `%sort-by-keys`** ([bulk-primitives.md](bulk-primitives.md)):
  one function each over a string's bytes or a vector's storage; nil on the interpreter
  and the JVM. `sorted` reads a key once -- an int's 64 bits, a string's first seven
  bytes and its length -- and sorts the keys' places by a radix sort, strings past the
  prefix by a merge of their runs. The library's own radix sort and its packed vectors
  are gone (and with them what "Where the time is" wrote of the JVM's compile time and
  of the 19 KB a `sorted` cost).
- **`vector-push-extend` grows with one `array.copy`** (`.kb/adjustable-arrays.md`, "The
  growth policy"): the growth was an element loop that walked the header and cast its
  counter at every element. `comps.star` 0.86 -> 0.66 s by that alone.
- **A `make-array` with no element type proves the general kind**
  (`.kb/declarations-type-checks.md`, source 2), whatever its size and with a fill
  pointer: `(setf (aref v i) x)` on such a `let`-bound `v` asked `stringp` of it at
  every store -- 11% of `list(range(n))`.
- **A chain of `%string-concat` is one string** (`.kb/wasm-gc-strings.md`, "A chain of
  concatenations is one string"): up to four pieces a call of `_str_cat`, a fixnum's
  digits written where they go.

In the library and the lowering, on every backend: `str` of a known int and a template's
`{}`, `%s`, `%d`, `%i` of one are `(%decimal-piece i)` ("The lowering table"); `extend`
and `+=` of lists take their room once and move one block; `pop()` takes the last
element at its head ("The run-time library's rules").

Seconds as above (`time.sh`, best of three, same machine, the two builds run in turn),
before -> after:

| program | JVM | WASM | component | CPython | `.wasm` bytes |
|---|---|---|---|---|---|
| `bench.star` | 0.15 -> 0.16 | 0.04 -> 0.05 | 0.04 -> 0.04 | 0.14 | 138,255 -> 138,184 |
| `fib.star` | 0.27 -> 0.25 | 0.17 -> 0.17 | 0.17 -> 0.22 | 1.46 | 137,416 -> 137,344 |
| `loop.star` | 0.17 -> 0.20 | 0.37 -> 0.34 | 0.37 -> 0.33 | 1.67 | 105,799 -> 106,627 |
| `calls.star` | 0.25 -> 0.25 | 0.14 -> 0.14 | 0.13 -> 0.14 | 0.74 | 119,114 -> 119,573 |
| `mixed.star` | 0.69 -> 0.72 | 0.59 -> 0.52 | 0.53 -> 0.54 | 1.23 | 178,254 -> 177,917 |
| `listidx.star` | 1.22 -> 1.11 | 0.98 -> 1.02 | 0.94 -> 0.96 | 1.18 | 181,499 -> 181,655 |
| `dicts.star` | 0.84 -> 0.82 | 0.87 -> 0.87 | 0.88 -> 0.86 | 0.92 | 182,005 -> 182,183 |
| `comps.star` | 1.75 -> 1.65 | 0.94 -> 0.69 | 0.94 -> 0.70 | 0.90 | 176,608 -> 176,860 |
| `methods.star` | 1.97 -> 1.72 | 2.24 -> 1.45 | 2.02 -> 1.45 | 1.11 | 174,439 -> 175,832 |
| `strings.star` | 2.23 -> 2.48 | 1.54 -> 0.91 | 1.62 -> 0.89 | 1.14 | 140,638 -> 124,965 |
| `sorting.star` | 2.93 -> 3.07 | 1.98 -> 0.73 | 1.99 -> 0.70 | 0.97 | 208,637 -> 195,700 |

- **Ten of the eleven are under CPython on wasm** and on the component; `methods.star`
  is not (1.45 against 1.11); what it waited on is "A literal is a global" below. `dicts` is under
  by a twentieth and was level in one run of three (0.92 / 0.91). The JVM column is its
  spread from run to run: `strings` 2.28-2.43 before and 2.29-2.55 after over four runs
  each, `sorting` 3.09-3.31 and 3.30-3.66 over three (its ints are the merge sort's again).
- Step by step (wasm, user seconds of the precompiled module, a pair measured in turn):
  `sorting.star` 1.92 -> 0.70 with `%sort-by-keys`; `comps.star` 0.91 -> 0.86 the
  `make-array` kind -> 0.66 the growth; `strings.star` 1.03 -> 0.95 the growth -> 0.75
  the chain; `methods.star` 1.67 -> 1.58 the three primitives -> 1.53 `pop` and `extend`
  -> 1.35 the growth.
- The parts (`parts/*.star` of the j63 artefacts, user seconds, before -> after against
  CPython): `sorted` of ints both ways 0.49 -> 0.19 / 0.41, by a key 0.40 -> 0.24 /
  0.31, of strings 0.97 -> 0.19 / 0.29; `split` 0.52 -> 0.20 / 0.22, `join` 0.44 -> 0.24
  / 0.16, both of 1,200,000 pieces 0.78 -> 0.36 / 0.23; `+` and `str` 0.36 -> 0.22 /
  0.43, `format` and `%` 0.28 -> 0.18 / 0.45; `append` through a `def` 0.16 -> 0.12 /
  0.16, `pop` 0.32 -> 0.22 / 0.13, `list(xs)` and `extend` 0.38 -> 0.11 / 0.24; a dict
  through a `def` 0.27 -> 0.31 / 0.55, `upper().lower().strip()` 0.21 -> 0.21 / 0.18,
  `startswith`, `find`, `count` 0.30 -> 0.32 / 0.22.
- **The bytes**: a program that sorts is smaller (`sorting.star` 12,937 B, the library's
  radix sort and the arms its packed vectors opened), one that formats ints too
  (`strings.star` 15,673 B: `str` of a known int no longer reaches `%starlark-b-str`,
  nor through it `repr`); one that does neither carries `_str_cat` and its sites
  (`loop.star` 828 B). Sizes (`.class` / `.wasm` / component): `print(1 + 2)` 109,437 /
  104,877 / 108,446 -> 109,437 / 105,704 / 109,274; `examples/starlark/targets.star`
  253,157 / 246,745 / 250,676 -> 225,876 / 216,807 / 220,720, at `--optimize=size`
  166,818 -> 146,696 B of wasm. Common Lisp programs (`.wasm`, default level):
  `size-report/programs/zlib` 152,940 -> 155,109 (its `format nil` chains),
  `bench-report/programs/sort.lisp` 21,410 -> 21,748, `string.lisp`, `hash.lisp`,
  `sieve.lisp`, `list.lisp` and `hello_world` unchanged; their times do not move (`sort`
  0.33 / 0.35 s, `hash` 0.41 / 0.39, `string` 0.61 / 0.63).
- **Not made, measured**: `_sort_keys` as a merge over the keys' words (14.5 ms a sort of
  50,000 ints, the library's radix sort 10, the radix sort made 3.7: a merge mispredicts
  every other branch); a byte loop for a short operand in `_string_concat` and in a
  split's pieces (slower than `array.copy`, [bulk-primitives.md](bulk-primitives.md));
  `StringValuedForms.certainlyString` as the licence to skip a piece's normalization (a
  piece routed through a printer control is a character vector: the ci-spec corpus
  trapped).
- **Left then, made since** ("A literal is a global" below): `methods.star` -- `find` and
  `count` 16% of it (`_str_search`'s offsets and its second walk for the index), `split`
  12.5% (25 objects a call), a literal evaluated a call (`_str_lit` 6.4%: `:none`,
  `:%unset`, a string argument), the dict's store and look-up 20%, `append` and `pop`
  through a `def` 7% each. Still left: the JVM, where the primitives have no arm.
- Pinned by `starlark-spec.yaml` `what-the-lowering-knows-answers-as-the-generic-paths`
  (`fast_bulk`, `fast_text`), `StarlarkLoweringTest` (the rows of a template and of `str`
  over a known int), ci-spec `format-nil-princ-to-string-prin1-to` (the chain) and the
  conformance corpus; the backend's halves also by ci-spec `vector-push-extend-growth`
  and the array suites (`ArefRankAndSubscriptChecksFixture`).

### A literal is a global (2026-11-16)

What "A sequence at a time" left to `.todo/j65`: `methods.star`, the one program still
over CPython on wasm (1.45 against 1.11), and `dicts.star` under by a twentieth. The
item's plan was made as written -- the literal first, measured on the module edited as
text before it was made (`global.get` for each `_str_lit` call: 1.67 -> 1.54 s); then
`find` and `count`, the dict's store, a string key's hash. The sampler named four more
on the way: a `strip` made a character -- an object -- of every character it looked at,
`upper` and `lower` went through the linear scratch a byte at a time, a `pop()` was
three calls, and a word of a `split` two calls into the engine.

Made, in the wasm backend, for every language:

- **A literal a function names is a global the module's start function fills**
  (`.kb/wasm-gc-strings.md`, "A literal is one object"): a site is `global.get`, where
  it was a call of `_str_lit` and a table read -- 9% of `methods.star`'s samples
  (`:none` a method answers, `:%unset` for each argument a call leaves out, a string
  argument). A literal of a form that signals is built where it stands.
- **`%string-count`** (`.kb/bulk-primitives.md`): one walk of the bytes; a `count` was a
  search an occurrence, 10.8% of `methods.star`. **`%string-search` over a string
  counted at its bytes** takes a bound and gives its answer with no offset call and no
  second walk (`.kb/string-search-runtime.md`).
- **A character read for its code makes no character** (`.kb/characters-code-points.md`):
  `(char-code (char s i))`, `(char= (char s i) c)`.
- **`string-upcase` / `-downcase` of one-byte characters move the bytes**
  (`.kb/wasm-gc-strings.md`, "Character accessors"), **`vector-pop` with an element is
  inline** (`.kb/adjustable-arrays.md`), **a short piece of a split is one
  `array.new_fixed`** (`.kb/bulk-primitives.md`), **a string keeps its hash**
  (`.kb/hash-tables.md`: `dicts.star` 0.85 -> 0.75 s by that alone).

In the library, on every backend: `count` asks `%string-count` first; `strip` reads
codes (`%starlark-cut-p`: white space by comparison, a cutset as a list of codes);
`startswith` / `endswith` make no list of one prefix (`%starlark-affix-of`); `get` asks
the table itself for an int or a string key and `x[k] = v` on a dict nothing forbids
writing goes from `%starlark-set-index` straight to `%starlark-dict-store` (it went
through `-set-index-of`: 4% of `methods.star` in a function that only passed it on);
`append` and `pop` ask `eql` of the loop count.

Seconds as above (`time.sh`, best of three, same machine, the two builds run in turn),
before -> after:

| program | JVM | WASM | component | CPython | `.wasm` bytes |
|---|---|---|---|---|---|
| `bench.star` | 0.16 -> 0.15 | 0.05 -> 0.05 | 0.04 -> 0.04 | 0.13 | 138,184 -> 139,229 |
| `fib.star` | 0.27 -> 0.29 | 0.18 -> 0.21 | 0.17 -> 0.18 | 1.48 | 137,344 -> 138,376 |
| `loop.star` | 0.18 -> 0.18 | 0.35 -> 0.32 | 0.36 -> 0.33 | 1.61 | 106,627 -> 107,597 |
| `calls.star` | 0.26 -> 0.27 | 0.13 -> 0.17 | 0.17 -> 0.17 | 0.73 | 119,573 -> 120,547 |
| `mixed.star` | 0.70 -> 0.68 | 0.55 -> 0.53 | 0.56 -> 0.53 | 1.21 | 177,917 -> 179,126 |
| `listidx.star` | 1.19 -> 1.09 | 0.97 -> 0.99 | 0.96 -> 1.01 | 1.23 | 181,655 -> 182,824 |
| `dicts.star` | 0.88 -> 0.86 | 0.88 -> 0.78 | 0.85 -> 0.79 | 0.92 | 182,183 -> 183,764 |
| `comps.star` | 1.65 -> 1.56 | 0.65 -> 0.70 | 0.69 -> 0.69 | 0.87 | 176,860 -> 177,838 |
| `methods.star` | 1.92 -> 1.83 | 1.42 -> 1.04 | 1.42 -> 1.03 | 1.10 | 175,832 -> 179,502 |
| `strings.star` | 2.25 -> 2.34 | 0.88 -> 0.84 | 0.86 -> 0.84 | 1.15 | 124,965 -> 126,895 |
| `sorting.star` | 3.34 -> 3.38 | 0.68 -> 0.71 | 0.66 -> 0.68 | 0.99 | 195,700 -> 197,276 |

- **All eleven are under CPython on wasm** and on the component: `methods.star` by a
  twentieth (1.04 against 1.10; 1.03-1.08 against 1.11-1.17 over four more runs of the
  pair), `dicts.star` by a seventh (0.78 against 0.92). The JVM column is its spread from
  run to run (the library's changes reach it; the primitives have no arm there), and so
  are the short programs' hundredths (`calls.star` 0.13 -> 0.17: 129-140 ms against
  132-142 over 25 runs of each, the function bodies the same but for two literals).
- Step by step on `methods.star` (wasm, user seconds of the precompiled module, a pair
  measured in turn, CPython 1.08-1.10): 1.41 -> 1.31 the literals -> 1.07 `count`, the
  search's arm, `strip` by codes, `upper` and `lower` by bytes, `get` and the store's
  shorter path -> 1.03 `pop` -> 0.96 a split's short pieces. `dicts.star` 0.85 -> 0.75
  with the string's hash alone (0.86 before it, the other changes within the noise).
- The parts (`parts/*.star` of the j63 artefacts, user seconds, before -> after against
  CPython): `upper().lower().strip()` 0.20 -> 0.11 / 0.16; `startswith`, `endswith`,
  `find`, `count` 0.33 -> 0.19 / 0.23; `split` 0.17 -> 0.15 / 0.28; `append` through a
  `def` 0.12 -> 0.11 / 0.17, `pop` 0.19 -> 0.18 / 0.14 (its `list(range(n))` is most of
  it), a dict through a `def` 0.28 -> 0.30 / 0.55.
- **The samples of `methods.star` now** (the build of this section): `_str_split` 15%,
  `_ht_get` 11%, `%starlark-m-append` 9%, the loop's own body 9%, the dict's store 9%
  and `get` 9% with their look-ups, `pop` 5%, `_str_search` 4%, `_str_count` 3%, an int
  past 2^30 boxed 3%. What a function costs there is its count of type operations --
  a cast, a test, a bounds-checked `array.get`, ten to fifteen machine instructions
  each: `_ht_get` is a dozen of them over arrays of `(ref null eq)` (27 ns a look-up of a
  fixnum key), `%starlark-m-append` some 300 instructions for three guards and a push,
  each of which casts the list's instance again.
- **The bytes**: every Starlark program is about 1 KB larger (`loop.star` 970 B: its
  literals, most of them named once), one with string
  methods 3.7 KB (`methods.star`: `_str_count` 350 B, a split's short pieces 400 B, the
  case conversion's arm, `pop`'s). Sizes (`.class` / `.wasm` / component): `print(1 + 2)`
  109,427 / 105,704 / 109,274 -> 109,427 / 106,669 / 110,204;
  `examples/starlark/targets.star` 225,589 / 216,757 / 220,670 -> 225,583 / 218,468 /
  222,245, at `--optimize=size` 146,646 -> 148,217 B of wasm. Common Lisp programs:
  `.kb/wasm-gc-strings.md`, "A literal is one object" (a Proxy-Wasm filter +1.3%, `zlib`
  -56 B).
- **Not made, measured** (`.todo/j67`): the hash table's entry as a struct with typed
  fields; a structure reader that takes an instance's slots once; a `def` of one call
  inlined (`push`, `lookup`); `(i * 31) % m` by a variable inline (`_fx_mod`, 7.5% of
  `dicts.star`). And the JVM, where `%string-split`, `%string-join`, `%string-count` and
  `%sort-by-keys` answered nil: `sorting.star` 3.38, `strings.star` 2.34, `methods.star`
  1.83, `comps.star` 1.56 against CPython's 0.87-1.15 -- made since, "The JVM's arms"
  below.
- Pinned by `starlark-spec.yaml` `what-the-lowering-knows-answers-as-the-generic-paths`
  (`fast_strings`: `count`, `find` and the affixes over a string of one-byte characters,
  one of several and one never counted, each bound; `strip` by white space and by a
  cutset of several bytes; `upper` and `lower` both ways; a split of pieces of eight
  bytes, nine and several-byte characters; `pop()` to an empty list; `get` and a store
  by an int, a string, a tuple and a float key) and the conformance corpus; the backend's
  halves by ci-spec `eql-of-a-literal-and-a-literal-evaluated-twice`,
  `code-point-characters-beyond-ascii`, `string-case-ops-full-unicode`,
  `displaced-fill-pointer-cross-backend` and
  `search-over-strings-answers-characters-in-either-representation`.

### The JVM's arms (2026-11-16)

What "A literal is a global" left to `.todo/j66`: the JVM, where the bulk primitives
answered nil, with the order to profile first (JFR, `jdk.ExecutionSample` every 1 ms;
`.todo/artefacts/j66-starlark-jvm-bulk-arms/`). The profile agreed with the plan for
`sorting.star`, named two primitives the plan had not -- `%string-search` and
`%replace-bulk`, nil on the JVM as well -- and said `comps.star` waits on none of them:

| program | where the main thread's samples were, the build before |
|---|---|
| `sorting` | `sorted` 93%: the merges over conses 65% (`-merge-sorted` 32%, `-merge-strings` 19%, `-merge-ascending` 13%), the cells made of the vector 12% |
| `strings` | `join` through the string stream 15%, `split` 13.5%, `String.concat` 26.5%, `Long.toString` 16%, `len` of a string just made 12% (`_cpidx`: both memories missed, the string stored into a static field) |
| `methods` | `split` 28% -- `%starlark-search`, the character loop, 19% of the program --, `count` 7%, `find` 4%, `list(xs)` and `extend` 6% in `%replace-runtime-array`'s element loop |
| `comps` | `_vectorPushExtend` 23%, the dict comprehension's store 20% (half of it the table's growth), the loops' own body 31% |

Made, in the JVM backend, for every language (`JvmBulkRuntimeBuilder`,
[bulk-primitives.md](bulk-primitives.md), "JVM"): the arms of `%sort-by-keys`,
`%string-split`, `%string-join` and `%string-count`; of `%string-search`
([string-search-runtime.md](string-search-runtime.md)), so `find`, `in`, `replace` and
the affixes scan no characters; of `%replace-bulk`
([sequence-op-runtimes.md](sequence-op-runtimes.md)), so `list(xs)`, `extend` and a
slice move without the element loop; and a short string counted where it stands
([string-index-cost.md](string-index-cost.md)). Nothing in the library or the lowering
changed: the interpreter still runs the callers' loops and the wasm modules are the
same bytes.

Seconds as above (`time.sh`, best of three, same machine, the two builds run in turn),
before -> after:

| program | JVM | WASM | component | CPython | `.class` bytes |
|---|---|---|---|---|---|
| `bench.star` | 0.15 -> 0.16 | 0.05 -> 0.05 | 0.05 -> 0.05 | 0.11 | 137,740 -> 140,006 |
| `fib.star` | 0.30 -> 0.26 | 0.17 -> 0.20 | 0.17 -> 0.20 | 1.45 | 136,561 -> 138,827 |
| `loop.star` | 0.18 -> 0.17 | 0.33 -> 0.34 | 0.35 -> 0.36 | 1.67 | 111,899 -> 114,175 |
| `calls.star` | 0.28 -> 0.27 | 0.17 -> 0.16 | 0.14 -> 0.14 | 0.74 | 124,697 -> 126,973 |
| `mixed.star` | 0.71 -> 0.73 | 0.56 -> 0.56 | 0.50 -> 0.52 | 1.29 | 168,240 -> 170,667 |
| `listidx.star` | 1.16 -> 1.16 | 0.99 -> 1.02 | 0.99 -> 0.98 | 1.23 | 172,611 -> 175,038 |
| `dicts.star` | 0.89 -> 0.89 | 0.82 -> 0.80 | 0.84 -> 0.83 | 0.97 | 174,654 -> 177,091 |
| `comps.star` | 1.68 -> 1.69 | 0.69 -> 0.68 | 0.71 -> 0.73 | 0.92 | 165,900 -> 168,337 |
| `methods.star` | 1.73 -> 1.39 | 1.12 -> 1.07 | 1.13 -> 1.09 | 1.17 | 163,417 -> 166,580 |
| `strings.star` | 2.32 -> 1.90 | 0.84 -> 0.85 | 0.81 -> 0.82 | 1.14 | 127,097 -> 130,607 |
| `sorting.star` | 3.31 -> 1.33 | 0.70 -> 0.71 | 0.73 -> 0.71 | 0.99 | 209,212 -> 214,701 |

- **The JVM is under CPython for the same six** (`fib`, `loop`, `calls`, `mixed`,
  `listidx`, `dicts`); `sorting` is a third over where it was three times, `methods` a
  fifth over, `strings` two thirds, `comps` as it was. The wasm columns are their spread:
  the modules did not change.
- **The samples now**: `sorting.star` 884 of them where it had 4,931 -- `_sortKeys` 32%
  (`_bulkRadix` 11 of it), the three vectors a `sorted` makes and the copy a key
  function is kept from 15%, `min` and `max` 12%, `reversed` 9%; `methods.star` 1,019
  for 2,360 -- `run`'s own body 20%, `ArrayList.add` 15%, `split` 14%, `lookup` 11%,
  `push` 11%; `strings.star` 1,431 and 1,533 over two runs for 2,363 -- `String.concat`
  30% and 42%, `Long.toString` 13%, `_strJoin` 7% and 10%, `_strSplit` 5% and 6%.
- **What a sort waits on is the compiler** ([bulk-primitives.md](bulk-primitives.md),
  "JVM"): 5.1 ms for a sort of 50,000 ints and one of 50,000 strings from the hundredth
  round on, 11-15 ms before it, and `sorting.star` is 96 sorts in a process whose
  compiler threads take four of its five seconds of CPU.
- **Not made, measured**: a concatenation through one `StringBuilder` made at its size
  (36 ns for `"item"` and a number against the 20 of `substring`, `substring`, `concat`;
  a `char[]` 26; the deprecated Latin-1 `getBytes` pair 17 and wrong for any other
  string).
- **The bytes**: every Starlark class carries `_strSearch`, `_bulkCopy` and their
  helpers, 2.3 KB (`print(1 + 2)` 109,424 -> 111,700 B; `examples/starlark/targets.star`
  225,852 -> 231,747 with the sort, the split and the join). A Common Lisp class that
  reaches neither `search` nor `replace` is the same bytes (`(print (+ 1 2))` 6,193).
- **Left** (`.todo/j67`): `comps.star`'s pushes and its dict's growth, the JVM's string
  that is three allocations a concatenation, and what a wasm operation costs.
- Pinned by `starlark-spec.yaml` `what-the-lowering-knows-answers-as-the-generic-paths`
  (`fast_bulk`, `fast_sort`, `fast_strings`, and `fast_wide`: strings of characters
  below and above 256 and outside the BMP sorted both ways and by a key, joined, split,
  counted and searched at 40 and at 6, a tuple's vector with False among its ints), the
  conformance corpus, and for the two Common Lisp primitives ci-spec
  `search-over-strings-answers-characters-in-either-representation`,
  `typed-vector-struct-packs-and-replace-bulk` and the array suites.

### A program a user wrote (2026-11-17)

What "The JVM's arms" left to `.todo/j67`: the JVM's `comps.star` first (its pushes and its
dict's growth), then what a wasm operation costs, with the order to pick by what a program
a user wrote waits on and to profile first. **Both premises overturned.**

- **The JVM's `comps.star` waits on the compiler, not on a push.** 800 rounds are 5.6-5.9 s
  where 80 are 1.5-1.6: a round is 7 ms once the code is compiled, CPython's 11-12, and a
  second of the 80-round run is spent in the first-tier code. JFR's `jdk.Compilation` and
  `jdk.Deoptimization` (`jfrcomp.py` of `.todo/artefacts/j67-starlark-a-program-a-user-wrote/`;
  the machine's `java` is Oracle GraalVM 25, so the top tier is Graal): `run`, 2,273
  bytes with its five loops, is compiled on-stack three times, 107 ms and 21 KB of code
  while only two loops have run, then 450-660 ms and 165-198 KB once all have, the code
  before each thrown away by a type check its profile had not seen.
- **A program in the shape of the repository's own example is not under CPython
  anywhere.** `graph.star` of the artefacts is `examples/starlark/targets.star` at size:
  2,000 targets made by a loop (dicts of three string keys, lists of up to three names,
  `"t%d" % i`), their build order, what a change rebuilds, a line of text a target, 40
  times. CPython 0.48-0.53 s, wasm 1.40, the component 1.42, the JVM 1.90-1.98, Go 4.91,
  Java Starlark 1.92. The eleven programs build a few large collections; this one makes
  many small ones, and what it waits on is what making and reading them costs:

| part (`parts/*.star`) | where its wasm samples were, the build before |
|---|---|
| the targets made | `%starlark-interpolate` 26% (`"t%d" % j`: the template read at every call, since the right operand is no tuple expression), `%starlark-concat-pieces` 17% (a template of five pieces or more), `%starlark-m-append` 9% and the growth it calls |
| `build_order` | a dict look-up by a string 31% (`%starlark-dict-cell` 12% itself, `_ht_get` 12%, `_string_eq` 6%), `append` 10%, `[]` 6% |
| `rebuilt_by` | its own body 21%, the look-ups 28%, a comprehension's vector grown at each of its first pushes 15% (`_arr_set`, `_vec_sync`, `_fp_hdr`, `_arr_undisplace` under the growth) |

Made, in the library and the lowering, so on every backend ("The lowering table", "The
run-time library's rules"): a list written to be appended to starts with room for four
and a list comprehension of one loop and no condition with room for the loop's count;
`"t%d" % x` over a right operand that is no tuple expression is read while lowering;
five to eight pieces of a template are a chain; `x[k]` of a dict is asked in
`%starlark-index`, where it went on to `%starlark-index-of` past a second test of a
list. In the wasm backend, for every language (`.kb/hash-tables.md`,
"Representation"): two string keys compare their bytes where the chain stands, and a
small record's string key is looked for by identity first.

Seconds (`time.sh`, best of three, the whole process, same machine; the eleven's two
builds an hour apart, `graph.star`'s in turn), before -> after; Go and Java Starlark for
`graph.star` above:

| program | JVM | WASM | component | CPython | `.wasm` bytes |
|---|---|---|---|---|---|
| `bench.star` | 0.16 -> 0.16 | 0.05 -> 0.05 | 0.05 -> 0.04 | 0.14 | 139,229 -> 139,419 |
| `fib.star` | 0.31 -> 0.32 | 0.20 -> 0.17 | 0.21 -> 0.19 | 1.46 | 138,376 -> 138,566 |
| `loop.star` | 0.18 -> 0.18 | 0.35 -> 0.32 | 0.33 -> 0.32 | 1.63 | 107,597 -> 107,597 |
| `calls.star` | 0.26 -> 0.27 | 0.14 -> 0.13 | 0.13 -> 0.14 | 0.75 | 120,547 -> 120,547 |
| `mixed.star` | 0.70 -> 0.72 | 0.55 -> 0.50 | 0.49 -> 0.47 | 1.21 | 179,126 -> 179,422 |
| `listidx.star` | 1.17 -> 1.13 | 1.04 -> 0.94 | 1.03 -> 0.94 | 1.19 | 182,824 -> 183,120 |
| `dicts.star` | 0.83 -> 0.86 | 0.80 -> 0.80 | 0.78 -> 0.80 | 0.90 | 183,764 -> 184,060 |
| `comps.star` | 1.64 -> 1.52 | 0.71 -> 0.67 | 0.75 -> 0.67 | 0.88 | 177,838 -> 178,203 |
| `methods.star` | 1.38 -> 1.44 | 1.08 -> 1.06 | 1.12 -> 1.04 | 1.10 | 179,502 -> 179,854 |
| `strings.star` | 1.82 -> 1.80 | 0.81 -> 0.84 | 0.85 -> 0.84 | 1.16 | 126,895 -> 126,974 |
| `sorting.star` | 1.36 -> 1.34 | 0.69 -> 0.71 | 0.68 -> 0.69 | 0.98 | 197,276 -> 197,544 |
| `graph.star` | 1.90 -> 1.80 | 1.40 -> 1.12 | 1.42 -> 1.09 | 0.48 | 259,665 -> 260,268 |

- **`graph.star` on wasm is 2.3 times CPython where it was 2.9**, and the eleven stay
  under it there (`methods.star` by a twentieth, as before). The JVM's column is its
  spread: no JVM code changed.
- The parts (wasm, user seconds of the precompiled module, before -> after against
  CPython; parts two to five make the targets once): the targets made 0.43 -> 0.24 /
  0.22, `build_order` 0.30 -> 0.27 / 0.11, `rebuilt_by` 0.49 -> 0.33 / 0.15, `describe`
  0.11 -> 0.10 / 0.08, `max`, `format(**d)` and `sorted` 0.10 -> 0.10 / 0.05. Step by
  step on the whole program: 1.38 -> 1.00 the library and the lowering, -> 0.94-1.01 the
  table's two arms; the chain of eight took `describe` from 0.13 to 0.08 and the
  targets from 0.30 to 0.22.
- **A whole-program time on wasm moves with where its collections fall.** At 1,500 /
  2,000 / 2,500 / 3,000 targets the program was 1.04 / 1.40 / 1.76 / 2.17 s before and
  0.72 / 1.12 / 1.08 / 1.50 after, CPython 0.38 / 0.48 / 0.62 / 0.73: the last change
  made it slower at 2,000 (0.94 -> 1.07) and faster at the three other sizes and on
  every part. With no collection (`-C collector=null`, 345 MB) it is 0.92 s against
  1.16: the copying collector is a fifth of the run. Compare builds on the parts or at
  several sizes.
- **The samples of `graph.star` on wasm now**: a dict look-up by a string 25%
  (`%starlark-dict-cell` 10% itself -- some 110 machine instructions for a string key:
  `integerp`'s two failed struct tests, `stringp`'s test and then cast of the same
  value, its bytes' cast and a bounds-checked byte, the table's accessor, `gethash`'s
  three tests of its operand --, `_ht_get` 10%, `_string_eq` 5%, `_ht_sfind` 3%, `_hash`
  2%), `_string_concat` and the pieces 9% (sampled before the chain of eight),
  `rebuilt_by`'s own body 4%, `%starlark-index` 4%, a sort 3%, and 30 functions under 2%
  each.
- **The JVM** (JFR over `graph.star`, 1.9 s of wall time and 10 s of CPU): 18
  compilations of 60 ms or more at the top tier, 5.6 s between them; each of the
  program's five functions compiled two to four times at 60-100 KB of code
  (`make_targets` 536 ms, 101 KB). At ten times the rounds it is 8.0 s against CPython's
  5.4 (wasm 16.7 before this section), so it is over CPython compiled as well: the
  main thread's samples are `make_targets` 15% itself, `%starlark-concat-pieces` 7%,
  `String.concat` 6%, `build_order` 7%, `_length` 4%, `RontoHashTable$Flat.find` 4%,
  `%starlark-list-p` 3%. C2 (`-XX:-UseJVMCICompiler`) is 1.75-1.83 s on the 40 rounds
  and 9.3 on the 400.
- **Not made, measured**: `_vectorPushExtend` storing where it stands for a vector with
  its own boxed elements, with one `ensureCapacity` ahead of the growth's adds (the
  item's plan) -- `comps.star` at 800 rounds 5.69-5.94 -> 5.46-5.70 s, but at its 80
  rounds 1.74-2.11 -> 1.81-2.29 and `methods.star` 1.38-1.45 -> 1.43-1.61 over seven
  runs of each in turn, and `run`'s compilations the same 165-191 KB and 450-600 ms:
  what the 80 rounds wait on is not in the push. A string key asked before an int in
  `%starlark-dict-cell` (nothing measurable on wasm; measured on the JVM only together
  with the push). `wasmtime compile -C inlining=y` (1.09 -> 1.16-1.25 s, the module's
  code 1.76 -> 2.55 MB).
- **The bytes**: a Starlark program that subscripts or formats is 0.2-0.6 KB larger
  (`graph.star` 603 B of wasm). Sizes (`.class` / `.wasm` / component): `print(1 + 2)`
  111,713 / 106,669 / 110,204 before and after; `examples/starlark/targets.star`
  231,478 / 218,468 / 222,245 -> 231,743 / 218,986 / 222,764, at `--optimize=size`
  148,217 -> 148,591 B of wasm. `bench-report/programs/hash.lisp` is the same 10,572 B.
- **Left** (`.todo/j75`): the dict look-up by a string, the collector's fifth and what
  the JVM compiles, with what `.todo/j67` listed for wasm and did not reach.
- Pinned by `starlark-spec.yaml` `what-the-lowering-knows-answers-as-the-generic-paths`
  (`fast_graph`: `%` over an int, a string, a list, None, a Boolean and a tuple held by
  a variable; a small dict and one of twelve keys read by strings made at run time,
  hits and misses; `[]` appended to past its room, a comprehension's list appended to;
  templates of seven and nine pieces), `StarlarkLoweringTest` (the rows of the template
  and of the comprehension), the conformance corpus, and ci-spec's hash-table cases.

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
(`src/test/resources/starlark-conformance/`, Apache-2.0, its `LICENSE` beside it), plus
`spec/bytes.star` and `spec/set.star` written here in its form from `spec.md`'s examples
(the corpus has neither type), on four backends, one program per FILE: every chunk is read and lowered alone behind the corpus
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
