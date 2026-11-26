# The EXPERIMENTAL Starlark front end (`am.ik.rontolisp.starlark`)

**Invariant: a Starlark file is read, resolved and LOWERED to the Common Lisp core forms the
pipeline already consumes; no backend learns a Starlark name.** Experimental -- partial
conformance to bazelbuild/starlark's `spec.md` by design, no compatibility promise -- and every
user surface says so (`--source-language` help, the title of `doc/*/starlark/index.md`,
`examples/starlark/README.md`). `--no-gc` is refused by name (`CompileFrontend.run`): every
value but a number is a structure, a string or a hash table.

## Where it sits

- `starlark` depends on the AST types and `reader` (`LispReadException`) only, like `scheme`
  and `clojure` (`PackageCycleTest`). Facade `Starlark`; `StarlarkBuiltins` holds the
  built-ins' signatures, `StarlarkNames` identifier escaping, `StarlarkFiles` the modules
  `load` reads.
- Reached ONLY through the seam `eval/SourceLanguage.STARLARK`, per FILE
  (`.kb/source-language.md`). A file is lowered whole; a REPL's buffers one at a time as one
  file's run ("A session").
- The run-time half is Common Lisp source, `eval/starlark.lisp`, plus definitions
  `Starlark.runtimeForms` generates from the front end's tables, handled the `UrlLibrary` way
  by `eval/StarlarkLibrary`: spliced by `CompileFrontend.expand` beside `SchemeLibrary` (inside
  the prelude, whose string comparisons and `search` it is written over), pruned by
  `LibraryDefunPruner`; its `defstruct`s are expanded by NAME like Scheme's
  (`starlarkStructNames`), so a program carries the accessors it reaches. Name gate: `mayDefine`
  admits `%STARLARK-` members (`.kb/source-language.md`, "The run-time libraries' name gate")
  and the one public name, `rontolisp:starlark-call` ("Across languages").

## The decisions

- **`None` is `:none`, `True` and `False` are `T` and `NIL`.** A comparison, `not` and `in`
  then answer what `if` tests, with no conversion; a list is a structure, so `NIL` is never an
  empty collection. A `bool` is told from an `int` by type (`1 == True` is false,
  `{1: .., True: ..}` two keys). Run-time markers no program can spell: `:%unset` (optional
  built-in parameter not filled), `:%required` (no default), `:%unbound` (not assigned yet).
- **Identifier escaping: verbatim when the name has an ASCII lowercase letter, else `s%` +
  the name** (`StarlarkNames`): canonical names are upcased, so `fib` can never be a built-in,
  a lambda-list keyword or `T`; `X`, `T`, `_`, `MAX_SIZE` could. `%` is no identifier
  character, so the prefix is no identifier's start. Locals and globals share the spelling.
  Generated names are `%SL-<KIND><n>` (blocks `%SL-FN`, `%SL-BREAK`, `%SL-CONTINUE`); helpers
  `rontolisp::%starlark-<name>`, a built-in's `-b-<name>`, a method's `-m-<name>`.
- **A string's elements are code points**, the host CHARACTER
  (`.kb/characters-code-points.md`): `len` of one non-BMP character is 1, where the spec's two
  models (K = 8 or 16) answer 4 or 2. Emulating either makes `len`, indexing and slicing a
  walk on every backend and needs a string holding half a character, which the JVM's UTF-16
  and the others' code points disagree about. A literal cannot spell a surrogate. `hash` is
  still the spec's number (`String.hashCode` over UTF-16), computed in `starlark.lisp` --
  `sxhash` differs per backend.
- **No command line**: the spec gives a program none (no `sys`, no `argv`), nor do Go's
  `starlark` command or Bazel; the docs' index says so.
- **`print` writes to `*standard-output*`** (the spec leaves it to the host).
- **An error is a Common Lisp `error` with a Starlark message** (Go's wording where it has
  one): uncaught, `Unhandled condition: ...` on stderr and a non-zero exit on all four, nothing
  on a wasm module that never prints ([error-handling.md](error-handling.md)). Where:
  "Positions".

## The lowering table

| Starlark | core forms |
|---|---|
| `None`, `True`, `False` | `:none`, `T`, `NIL` (a name, so a file may rebind it) |
| int, float, string | integer (bignum), double, string literal; `-1` is the literal |
| list, tuple, dict, set, bytes, range, function | structures of `starlark.lisp`: a list an adjustable vector plus the cell it was made under ("Freezing") and an iteration count, a tuple a simple vector, a dict an `equal` hash table from a key's normal form to `(key . value)` plus the same two, a set a dict of its own, bytes an `(unsigned-byte 8)` vector |
| `[a, b]`, `(a, b)`, `{k: v}` | `(%starlark-make-list a b)`, `-make-tuple`, `-make-dict` (a key written twice is an error) |
| the same, every element a constant | `(%starlark-list-of '(1 "s" :none))`, `-tuple-of`, `-dict-of`, nested `(%starlark-literal '(:%list 1 (:%tuple 2) ..))`: quoted data, the value made anew each time |
| top-level `x = v` | top-level `(setq x v)`, never `defvar` (a special would leak into callees) |
| top-level `def`, the only binding of its name | `(defun f (a b [args] [kwargs]) ..)` AHEAD of every statement, plus at its place `(setq f (%starlark-make-function ..  #'f))`; a non-constant default is `(setq sl%(FILE)%SL-DEFAULTn expr)` |
| any other `def`, `lambda` | the function value over a `lambda` |
| function body | `(block %SL-FN (let ((local :%unbound) ..) ..))`; block only when a `return` leaves from the middle |
| a read not assigned on every path | `(%starlark-local x "x")` / `(%starlark-global x "x")`, refusing `:%unbound` |
| body under `--starlark-standard spec` | `(%starlark-enter FLAG "f") .. (%starlark-leave FLAG)`, `FLAG` the global `sl%(FILE)%SL-ACTIVEn` |
| `for` | `let*` of seq, `(%starlark-iterate seq)`, count, index; a `while` (the core form `loop` expands to; a `tagbody`/`go` was a `br_table` over a boxed counter every round on wasm); `%SL-BREAK`/`%SL-CONTINUE` blocks only when used; `(%starlark-iterated seq)` after |
| `for i in range(a, b, c)` (the built-in, positional) | a counting `while`: no range, no vector; `(%starlark-loop-stop ..)` unless both bounds are known ints; a non-constant step through `%starlark-loop-step` and a sign test; a step written `0` stays generic (its `range` refuses it) |
| `return v` inside loops | every enclosing loop's `%starlark-iterated` first, then `return-from` -- no `unwind-protect`, no EH mode on wasm |
| comprehension | the same loops around `vector-push-extend` / `%starlark-dict-put` (the dict is its own, so no dispatch, one look-up per key); its variables in a `let` INSIDE the first loop's `let*`, so the first iterable reads the enclosing scope (`[x for x in range(x)]`). One loop, no condition: `acc` is `(%starlark-vector-of count)`, never grown |
| `a + b`, `a == b`, `a[i]`, `a[i:j]`, `x in y` | `(%starlark-add a b)`, `-equal`, `-index`, `-slice`, `-in`; `!=`, `not in` are `(not ..)` of them |
| `+ - * // %`, a comparison, `==`, `!=` over operands that MAY be ints | `(if (and (integerp a) (integerp b)) (+ a b) (%starlark-add a b))`; `//`/`%` by a non-constant divisor also test `(not (= b 0))` ("What the lowering knows") |
| `s + t` over KNOWN strings; over one | `(%string-concat s t)`; behind `(stringp t)` |
| `str(i)` over a KNOWN int; a template's `{}`, `%s`, `%d`, `%i` of one | `(%decimal-piece i)`. On wasm a chain of `%string-concat` over such pieces is one string made once ([wasm-gc-strings.md](wasm-gc-strings.md), "A chain of concatenations is one string") |
| `"{}-{!r}".format(a, b)`, `"%d: %s" % (a, b)`: a literal template over positional args or a tuple expression | read while lowering; arguments evaluated first in order, one piece per field (`%starlark-b-str`, `-b-repr`, `%starlark-conversion`), joined by `%string-concat`, past eight pieces `%starlark-concat-pieces` (wasm's `_str_cat` takes four a call, so up to eight is two or three calls and no list); an erroring template, named field, format spec, > 64 pieces: the run time's |
| `"t%d" % x`, right operand no tuple expression | read for ONE argument: direct pieces for a known int, Boolean or string, else behind `(%starlark-one-argument-p x)` with `(%starlark-mod ..)` as the other branch |
| the same and `& \| ^ << >> ~`, unary `-`/`+`, over KNOWN ints | the Common Lisp operator; `a // 2^k` is `ash`, any other divisor `(/ (- a (mod a d)) d)`, never `floor` ("Against CPython"); a shift only by a non-negative constant |
| `x == None`, `x != True` | `(eq x :none)`, `(not (eq x t))` |
| `if c:`, `c ? ..` | `(if (%starlark-truth c) ..)`; a known Boolean is the test as it stands, a known int `(not (= c 0))`; `and`/`or` of tests are `and`/`or` |
| `a and b` as a value | `(let ((v a)) (if (%starlark-truth v) b v))`; `(if a b nil)` for a known Boolean |
| `a[i] = v`, `a, b = v` | `(%starlark-set-index a i v)`; `(%starlark-unpack v 2)` then `aref`s |
| `a, b = b, a + b` (as many targets as elements) | `let` of every element, then the `setq`s: no tuple |
| `x += v`, `a[i] += v` | `(setq x (%starlark-add-in-place x v))` (a list is extended in place), tested or inline like the binary operator; `a[i] op= v` with a `v` that runs nothing goes through `(%starlark-item-cell a i)` -- the cell of a writable dict holding the key, else nil and the generic pair -- so a dict item is one look-up; `-=`, `\|=` .. through `-sub-in-place`, `-bit-or-in-place` .. |
| `s += piece` in a `for` whose body spells `s` nowhere else, `s` a local assigned before, no int/Boolean, spelled by no nested `def`/`lambda` | pieces consed onto a list, `(%starlark-concat-reversed pieces)` at the loop's end and after `break`; a non-string piece goes to `%starlark-add-in-place`, which refuses it. The outermost such loop collects. A global never: a Common Lisp caller or a later buffer reads it |
| call of a top-level `def` with a direct entry whose arguments fit | `(f a b)`, bound while lowering like a built-in's; `(progn (%starlark-global f "f") (f a b))` where the `def` may not have run |
| call of a built-in whose arguments fit | `(%starlark-b-sorted xs :none t)`, defaults filled in |
| `x.m(..)` whose arguments fit | `(%starlark-m-append x v)`: the helper takes the receiver first and tests it |
| any other call | `(%starlark-call f (list pos..) (list "name" v ..))` |
| a built-in as a value, `x.m` uncalled | `%starlark-builtin`, `%starlark-method`: function values the binder takes |
| `x.nope`, `x.nope(..)` | `(%starlark-no-attribute x "nope")`: no type has it, arguments never evaluated |
| a file | `(%starlark-file-begin)` ahead, `(%starlark-file-end ..)` after ("Freezing") |
| `load("m.star", "f", y = "x")` | see "Modules" |
| `load("@cl-user", f = "add-two")` | `(%starlark-foreign "cl-user:add-two" #'add-two)`; a positional-only call is `(add-two a b)` ("Across languages") |
| `b".."` | `(%starlark-bytes-literal #(104 105))`, a packed octet vector (`LispIntVector.wrapOctets`, `.kb/packed-integer-vectors.md`) |

## Names

Resolved while lowering (`StarlarkLowering.reference`): the innermost function or
comprehension binding the name ANYWHERE in its block, else the file's globals (also inside a
top-level `if`/`for`, or by `load`), else `None`/`True`/`False`, else a built-in, else
`undefined: NAME` as a positioned `LispReadException` before anything runs.

**Definite assignment runs with the lowering** (`StarlarkLowering.Flow`): a read the flow
vouches for is the bare variable, any other the checked read (`local variable x referenced
before assignment`, Go's texts). A body starts with the flow where its value is MADE, less its
own names, plus its parameters: a variable is never unassigned again (no `del`), and a direct
`defun` runs only once its `def` ran. Measured: over the conformance corpus 7 reads of ~9,200
stay checked; `bench.star` and `examples/starlark/` have none.

## Standards (`--starlark-standard`)

`starlark/StarlarkStandard`, a member of `eval/SourceStandards` (program-wide,
`.kb/source-language.md`). `rontolisp` (default) allows rebinding a global, top-level
`if`/`for` and recursion -- Go's options; `spec` makes the first two errors before anything
runs (an augmented assignment counts as a binding) and recursion a run-time error. Default
permissive because the bench (`fib`) and the docs' examples use the relaxed forms.

- The conformance corpus is written for Go's strict default, and every chunk passes under
  `spec`, so `#conformance` reads it under `spec` (running the recursion check over
  `yin`/`yang`).
- Recursion is one flag per DECLARATION (`def`/`lambda`), as the spec words it, so a fresh
  closure per step (Y combinator) is caught. A global holding a cons, not a dynamic binding: a
  special's binding is a protected region on every backend and forces EH mode on wasm. Spelled
  behind the file's prefix: a shared flag made a module's `helper` "recursive" under the
  program's `helper` (pinned by the `standalone:` module case, read under `spec`). After an
  error `rontolisp:starlark-call` sets it back ("Frames"); made anew ahead of the statements so
  the corpus's next chunk starts clean.

## The two call tiers

One signature serves both: a function value (`%starlark-function`) holds params, defaults,
how many a positional argument fills, `named-from` (past the positional-only ones for a
built-in, so `dict(pairs = 1)` is `{"pairs": 1}`), varargs, kwargs.

- **General**: `%starlark-call` -> `%starlark-bind`.
- **Direct**: `StarlarkLowering.direct` runs the same binding over the call's syntax (built-in
  signatures in `StarlarkBuiltins`). A call that does not fit goes to the general tier with
  the signature as a value, so the binder's message is the one a program sees and no backend
  meets a Common Lisp arity error. A helper therefore has a FIXED parameter list, no
  `&optional`/`&key` --
  `StarlarkLoweringTest#everyBuiltinsHelperTakesTheParametersItsSignatureDeclares`.
- **A `def` with a direct entry is bound the same way.** A default is evaluated when the `def`
  runs: a constant default is that constant at the call site, any other lives in
  `sl%(FILE)%SL-DEFAULTn` -- the one object the function value's defaults hold, so a mutable
  default is shared by both tiers. A module's export carries the signature.
- **The binder's shortcut** (`%starlark-fills`): exact positional fit, no `*args`/`**kwargs`,
  hands the argument list over as it is -- a function VALUE called through a parameter.
- **A direct `defun` goes ahead of every statement.** Compiled code has it from the start and
  the interpreter only once evaluated, so `f(1)` above `def f` ran on three backends and failed
  on one; hoisted, all four have it, and a call the `def` may not precede checks the global
  `f` first (the spec's error).
- **Arguments are evaluated as written** though both tiers place them otherwise:
  `inSourceOrder` binds operands to `let*` variables exactly when the form would reorder
  them. A variable needs none: no call can assign it.

## What the lowering knows

**Invariant: a fast path answers what the generic helper answers for every value it is
taken for, and it is made of core forms: no backend learns a Starlark name.** The integer
forms are the ones the backends already fuse ([jvm-int-fusion.md](jvm-int-fusion.md),
[wasm-int-fusion.md](wasm-int-fusion.md)).

- **Kinds** (`StarlarkLowering.Kind`): INT, BOOL (exactly `T`/`NIL`), STRING, OTHER (no int),
  ANY. Read off literals, operator results, built-ins whose every return is one
  (`StarlarkBuiltins`' `-> int` / `-> bool` / `-> string`), and variables.
- **A variable is an int when every binding of it in its block is one** (`bound`, `infer`):
  all names assumed ints, dropped one by one until every binding agrees; then Booleans, then
  strings. Sound because nothing outside the block binds its names (no `global`/`nonlocal`).
  A parameter is ANY. Globals inferred the same way, a `load`ed name ANY -- but not in a
  session, where a later buffer binds anything. A Common Lisp file that `setq`s a Starlark
  global to another type gets Common Lisp's type error: no contract.
- **Three tiers per operator** (`operation`): both known ints -> the Common Lisp operator; both
  MAY be ints -> behind `integerp` tests, the helper the other branch; one known no int -> the
  helper alone. Integers are of any size on every backend, so `(+ a b)` IS Starlark's `+`.
  `//`/`%` by a non-constant divisor behind `(not (= b 0))` (`(i * 7) % w`: wasm 57 -> 27 ns,
  JVM 14 -> 13); shifts only by a non-negative constant: the errors stay the helper's. Bitwise
  operators inline over known ints only (`|` is also a dict union).
- Not made, not measured: narrowing by a test (`type(x) == "int"`), the kind a `def` returns,
  the kinds of a list's elements, an inline `x[i]`.

## The run-time library's rules

`starlark.lisp` keeps eight, the first four stated at its top:

- **A structure accessor is called only behind that structure's predicate.** An accessor
  handed an instance of ANOTHER structure passes the instance check (`.kb/defstruct.md`,
  "Accessors check their object"): it reads that structure's slot, or past its slots is an
  error on the interpreter and JVM and an uncatchable `out of bounds array access` trap on
  wasm.
- **A value of the program reaches a Common Lisp function only behind the type test that
  function needs**: a method tests its receiver first (`TYPE has no .NAME field or method`),
  an argument its type. 82 programs each ending in a different run-time error printed the
  same message on four backends.
- **What the lowering calls is a `defun`**: the interpreter loads the file on the first
  resolution of one of its functions, which a structure's constructor, predicate or accessor
  is not (`StarlarkLibrary.functionNames`).
- **A string handed to the program is the host's IMMUTABLE string.** Common Lisp's producers
  (`subseq`, `concatenate`, case/trim functions, `coerce`, string streams) answer a fresh
  MUTABLE one, on the compiled backends a vector per character
  ([string-write-runtime.md](string-write-runtime.md)) that every later comparison, hash and
  concatenation walks: `"w" + str(i)` 10 us on wasm through `concatenate`, 0.2 us through
  `%string-concat`; such a key hashed at 250 ns on the JVM. So: `%string-concat`,
  `%substring`, `%string-upcase-piece`/`-downcase-piece`, `%string-stream-piece`,
  `%decimal-piece`, `%string-order` ([string-search-runtime.md](string-search-runtime.md)),
  else `%normalize-string`. Several pieces are joined by halves (`%starlark-concat-pieces`,
  `-join-range`): a character copied log N times, no string stream (whose fixed cost a few
  pieces do not repay); `%starlark-concat-reversed` uses a string stream past 64 pieces.
- **An `error`'s control is a literal string.** A computed one bails the condition narrowing
  and keeps the run-time format renderer in every program (~50 KB of wasm):
  `%starlark-elements-or` takes the text before and after the type instead.
- **A string method tests its receiver at its head and names itself only to refuse**:
  `(if (not (stringp s)) (%starlark-no-attribute s "split"))`. A literal is evaluated where it
  stands (on wasm a global's read, "A literal is a global"), so passing the name to a check
  that passes cost on every call (`_str_lit` was 7% of `methods.star`). List and dict methods
  the same (`%starlark-m-append`, `pop()` of a list's last element).
- **A whole sequence is the backend's when it has a function for it**: `split` by a
  separator, `join`, `count`, `sorted` ask `%string-split`, `%string-join`, `%string-count`,
  `%sort-by-keys` first ([bulk-primitives.md](bulk-primitives.md)) and run their own loop
  (`%starlark-split-vector`, `-join-vector`, `-merge-sorted-vector`) for nil -- every call on
  the interpreter and the JVM. The loop is a `defun` of its own, so on wasm nothing reaches it
  for a string. `extend` and list `+=` reserve room once and move with one `replace`
  (`%starlark-push-all`).
- **A list written to be appended to starts with room for four** (`%starlark-vector`, `[]`).
  The growth policy doubles from a floor of one (`.kb/adjustable-arrays.md`, "The growth
  policy") and that capacity is what `array-dimension` reads, so the policy stays; the lists
  a build file appends to mostly hold three elements or fewer. A literal with elements is
  made at their number.

Also: `isalpha`/`isdigit`/`isalnum`/`isspace` test ASCII codes themselves (`alpha-char-p` is
ASCII-only on wasm), `islower`/`isupper`/`istitle`/`title` tell case by which of
`char-upcase`/`char-downcase` changes a letter (both: titlecase), the same on four backends
for the corpus's `ǅ`/`ǉ` lines; a dict walks in insertion order because the host table does
(`.kb/hash-tables.md`, "Representation"); a key's normal form (`%starlark-key`) makes `1` and
`1.0` one key and a tuple the list of its elements' forms; a float key is rounded only when
finite.

Written to Go's Starlark (`go.starlark.net`, `library.go`/`value.go`; Java Starlark,
`starlark_deploy.jar`, as the second opinion):

- **A float prints as Go's `%g`** (`%starlark-format-float`): shortest round-trip digits (the
  printer's one Schubfach selection, `.kb/format.md`), exponent below `1e-4` and from `1e+06`,
  `.0` when neither point nor exponent shows, `+inf`, `-inf`, `nan`. Java prints `1000000.0`;
  the spec's own `%g` row is Go's rule. `%e`/`%f` round exactly, half to even, over
  `integer-decode-float`'s integers (no float arithmetic).
- `float(string)` converts the exact rational; `float` of an int from `2^1024 - 2^970` on is
  `int too large to convert to float`, as is int-float arithmetic.
- **Numbers compare exactly and NaN is one value**: int vs float through the float's
  `rational`, NaN above `+inf` and equal to itself (`%starlark-number-compare`); every NaN is
  one dict key (`:nan`).
- A `def`/`lambda` is a dict key by identity, a built-in by name and receiver.
- `s.elems()`, `elem_ords`, `codepoints`, `codepoint_ords` answer a
  `%starlark-string-iterable`: iterable only, no `len`, no index, unhashable. Elements and
  code points are one thing in this string model, so the four differ in name only.
- `repr` of a string is Go's `syntax.Quote`. Past U+00A0 Go's `strconv.IsPrint` is a range
  table of the JDK's control, format, private-use, surrogate and separator categories
  (`%starlark-unprintable-ranges`); an UNASSIGNED code point prints as itself, where Go
  escapes it -- the table would grow by ~700 ranges.
- `dir`/`getattr`/`hasattr` read tables generated from `StarlarkBuiltins`' method rows
  (`Starlark.runtimeForms`). A program reaching `getattr` carries every method helper.
- `str.format` is Go's `string_format` ported (no `x.y`, `a[i]`, nested field or format
  spec). `splitlines` splits at `\n`, `\r` and `\r\n` as the spec says (Go: `\n` only).

`insert`, `pop` and `remove` move a list's elements inside its own vector
(`%starlark-delete-at`). Nothing else holds the vector meanwhile: a loop over the list counts
on it and the mutation is refused; `%starlark-unpack` and the built-ins copy.

A loop counts itself on the list or dict it walks (`%starlark-iterate` /
`%starlark-iterated`) and a mutation meanwhile is refused. The count is given back on every
normal exit by the lowering, and after an error by the boundary ("Frames").

## Bytes and sets

**A set is a structure over a dict of its own** (`%starlark-set`), elements its keys, values
`:none`: hashing, order, freezing, the loop count and frames are the dict's. Rejected:
`(:include %starlark-dict)` -- every `dict-p` dispatch (`get`, `keys`, `**`, `x[k]`,
`dict(x)`, `|`) would take a set for a dict unless guarded one by one. **A bytes is a
structure over an `(unsigned-byte 8)` vector** (`%starlark-bytes`), so it is neither a string
nor a list on any backend; the literal's vector is shared, nothing writes it.

- Spec, where Go differs: `b[i]` is an int (Go: a one-byte bytes); `<` on sets is
  `unsupported comparison` (Go: subset/superset); `set()` prints `set()` (Java, Python; Go
  `set([])`). Go's: `repr` of a bytes (`syntax.Quote(.., true)`; one decoder,
  `%starlark-utf8-decode`, serves it and `str`, U+FFFD per bad octet); `hash` of a bytes is
  32-bit FNV-1a, non-negative; the messages.
- `bytes(str)` is the UTF-8 of the code points (a surrogate code as U+FFFD's), so the spec's
  `bytes("hello 😃"[:-1])` example (a broken sequence) cannot arise.
- `|=` of two dicts updates in place (spec, Go's `INPLACE_PIPE`). Mutating `|=`, `&=`, `-=`,
  `^=` check the target as a method does.
- `dict|set.update` is one helper (the table's rule for a shared name).
- `set.pop()` lists the keys first (O(n)), like `dict.popitem`: `maphash` cannot stop early
  without a non-local exit, which is EH mode on wasm.
- Size: `%starlark-write`, which `print` reaches through `str`, dispatches on both types and
  carries the UTF-8 decoder and the set writer: `print(1 + 2)` +8-10 KB on every backend. A
  generic operator pays for its branch: `%starlark-sub`'s set branch brings the set
  operations and `%starlark-key` (~+18 KB).

## Frames: what a call that does not return gives back

**Decision: the boundary resets, and the boundary is `rontolisp:starlark-call`.** Go gives an
iterator back with `defer`; here no loop has an `unwind-protect` (EH mode on wasm for every
program), so what is counted is also pushed on one global list, `rontolisp::%starlark-frames`:
the list or dict a loop walks, and under `spec` the recursion flag. Normal exits pop.
`rontolisp:starlark-call` calls inside an `unwind-protect` and on any exit gives back every
frame above its mark (`%starlark-unwind`). Only a program that calls Starlark from Common Lisp
carries the `unwind-protect`.

- Pinned by the `standalone:` case `common-lisp-and-starlark-call-each-other`: through
  `rontolisp::%starlark-call` (no boundary) the call after a caught error is `cannot append to
  list during iteration`, under `spec` `function fail_at called recursively`.
- NOT reset: an error some handler takes outside the door -- around `(load "x.star")`. The
  file did not finish; its values stay unfrozen and its loops counted.
- One thread: the frames and the mutability are globals.
- A session's buffer starts with `(%starlark-unwind nil)` ("A session").

## Modules (`load`)

**A module is a file lowering of its own whose globals are spelled behind its path, lowered
with the file that loads it.** `StarlarkLowering.load`/`module`: read through
`starlark/StarlarkFiles` (`eval/SourceLanguage.starlarkFiles` over the site's `SourceLoader`,
relative to the loading file; `StarlarkFiles.NONE` refuses every file), lowered by a child
`StarlarkLowering` sharing the `Session`. Read at lowering time, so a compiled program carries
its modules.

- **Names**: a module's global `helper` is `sl%(lib/util.star)helper`
  (`StarlarkNames.filePrefix`): the normalized resolved path, `%`, `:`, `)` escaped (a colon
  is a package marker to the pipeline). No identifier starts `sl%`; Scheme's library names
  start `s%%(`. The program's own globals stay unprefixed -- what a Common Lisp file reads.
  One file reached by an absolute and a relative spelling is two modules; rejected: a key
  relative to the entry file (two program files in different directories would each run the
  module) and a number per lowering (two lowerings of one program would share numbers).
- **Once per program**: lowered once per lowering (`Session.modules`), its statements where
  the first `load` stands. Files lowered APART (a Common Lisp file loading two `.star` files
  that load one module) both emit them, and `(defvar STATE nil)` decides: the file's
  mutability cell while it runs, then `T`; a set state stays set, so a module that stopped at
  an error is not run again. Each statement is its own top-level form behind
  `(if (consp STATE) ..)`, not one `progn`: a top-level form is a JVM method
  (`.kb/jvm-method-size-limits.md`). The `defun`s are bare and may be emitted twice.
- **What `load` binds is a global of the loading file, assigned the module's**: a copy, not
  an alias -- a checked read's `(setq x :%unbound)` would otherwise clear the module's
  variable; a module's globals do not change once it ran. No third file loads it
  (`loadedOnly`).
- **Direct across files**: a name only a `load` binds to a module's direct `def` is called as
  `(sl%(m.star)f a b)`, behind the checked read.
- Static errors (Go's texts): file not found, cycle in load graph, name not found, leading
  underscore not exported, `load` within a conditional or loop. A global a path of the module
  may not assign is the same `not found` error when the `load` runs (`%starlark-loaded`).
- A module's function is reported under its symbol: `in sl%(words.star)shout`.

## Freezing

**A list or dict holds the cell it was made under, and a file's end sets its cell.**
`rontolisp::%starlark-mutability` is the current cell; `%starlark-file-begin` pushes
`(nil . previous)`, constructors store the current one, `%starlark-file-end` sets the car and
pops. Freezing is one `rplaca`, and what a closure of the file captured is frozen with no
walk. Every file is bracketed, the program's too (the spec freezes "whether directly by the
application or indirectly through a load"): a Common Lisp file that loaded a `.star` file
finds its lists frozen.

- Rejected: the walk from the globals (Go's `Freeze`). It needs each function value to hand
  out what it captured (a second closure per nested `def`), a flag to end a cycle, and is
  exponential over a tuple DAG.
- Difference from the spec's reachability: a value made while the file ran that no global
  reaches is frozen too. A value the file's function makes when called later is made under
  the caller's cell.
- **A frozen list is a dict key** (`(:list . keys)`), as `spec.md` `c0af0ea` says. Go and Java
  refuse it. A frozen dict or set stays unhashable: its normal form would have to be
  independent of insertion order, and a `def` key has no order.
- The token is a global of the file's (`sl%(FILE)%SL-FILE`), not the current cell: an error a
  handler took inside a file's run leaves another file's cell current, and the end must set
  its own.

## Across languages

**Nothing is converted; the door is a call, in both directions.**

- **Values**: numbers and strings are themselves, the rest the structures. A Common Lisp
  value with no Starlark type is `"unknown"` (true, `eq`, unhashable); a Common Lisp function
  is `"builtin_function_or_method"`. `NIL` is `False`, so an empty Common Lisp list is no
  Starlark list -- why a Common Lisp list is not made iterable.
- **Common Lisp -> Starlark: the function VALUE**, the variable `|name|` of a loaded file,
  through `(rontolisp:starlark-call f arg..)`, bound by `%starlark-bind`. The direct `defun`
  under the same spelling may change with the lowering: no contract. The one public name of
  `starlark.lisp` (`LispNames.STARLARK_CALL`, `StarlarkLibrary.mayDefine`); also "Frames"'
  boundary.
- **Starlark -> Common Lisp: `load("@package", local = "symbol")`** binds the symbol's
  FUNCTION as a built-in value named `package:symbol`; a positional-only call is the direct
  Common Lisp call. Also the door for host-predeclared names. Rejected: an undefined name
  falling through to a Common Lisp function (Scheme's rule; `undefined: x` is a static error
  the corpus expects) and `load("lib.lisp", ..)` reading a file (any path is read as
  Starlark).
- **Named arguments do not cross**: `cl:gcd got an unexpected keyword argument a`.
- **Sequences cross by spreading**: `cl_list(*xs)`, `(apply #'rontolisp:starlark-call f list)`.
  No converter is shipped.

## A session

**A session's buffers are one file's run** (`starlark/StarlarkSession`,
`StarlarkLowering.interact`), asked through `eval/SourceSession`, so the CLI REPL and the
playground share it.

- **`isComplete`**: the parser does not run out of input (an error at the end of input's
  OUTDENT counts as end of file, `StarlarkParser.error`), and when the LAST statement is a
  `def`/`if`/`for` the last line is blank (Python's prompt).
- An expression statement is echoed (`repr`, nothing for `None`), a literal too (a file drops
  one as a docstring).
- **Setup**: `(%starlark-unwind nil)`; after the first buffer the mutability is reset to the
  session's cell (an error inside a module's run left the module's cell current); every global
  this buffer binds first set to `:%unbound`. No `file-end`: no buffer freezes what an earlier
  one made.
- **Names**: an earlier buffer's global goes through the checked read -- that buffer may have
  stopped before assigning it. A name no buffer bound yet is `undefined: x` while lowering, as
  in Go's REPL; a function keeps what its names resolved to when lowered.
- **No direct call of a global**: a later buffer may rebind `twice` and an earlier `quad`
  calls the new value (late binding), so every call of a global goes through
  `%starlark-call`; built-ins, methods and a module's own direct `defun`s stay direct.
- **Modules**: the `Session` persists; every buffer loading a module emits its statements
  again behind its state, which runs them once. Exports go through `%starlark-loaded` (its run
  may have stopped at an error).
- A buffer whose lowering fails leaves the session as it was. The generated-name counter is
  the session's (`%SL-ACTIVEn` are globals).
- `--starlark-standard spec`: the top-level rules apply per buffer; a global bound in two
  buffers is no error.

## Measured (2026-11-15)

Linux x86_64, Xeon E5-2697A v4, Java 25.0.5, wasmtime 49.0.0. The prototype was a Python
lowering.

- `bench.star` (`fib(27)` plus a 10^6-iteration `for`): interpreter 2.55-2.63 s, JVM
  0.15-0.16 s, WASM 0.07 s once wasmtime's cache holds the module (0.20 s first run);
  CPython 0.10-0.13 s. Java Starlark's `cmd.Main` forbids recursion: its time on `bench.star`
  is the time to an error.
- Conformance: bazelbuild/starlark `c0af0ea` `test_suite/testdata` as
  `StarlarkSpecE2eTest#conformance` (39 files, 430 chunks, message matched, read under
  `--starlark-standard spec`): **429 of 430 on each of the four backends**, every file
  printing the same on all four, ~30 s in process. The one left: `go/builtins.star:64`
  (`sorted(1)`, whose pattern is the traceback line `Error in sorted`, no message).
- A list, tuple or dict expression that is not all constants is ONE call: 1,500 elements
  compile for the JVM, 3,000 exceed the 65535-byte method limit
  (`.kb/jvm-method-size-limits.md`); the interpreter and wasm take them. All-constant ones are
  quoted data: 100,000 integers print the same on the four.

### The fixed cost (2026-11-15)

Measured on the wasm module's call graph (`-Drontolisp.wasm.debug-func-sizes` names each
function index, `wasm-tools print` gives the calls) as a dominator tree: the bytes a function
alone keeps reachable.

- The chain: every Starlark program is in EH mode on wasm (`%starlark-b-repr` and
  `%starlark-join-strings` are `with-output-to-string`, an `unwind-protect`), which is what
  prints an uncaught error's message there (outside EH mode a bare trap). So messages are
  rendered, and any computed format control keeps the run-time format renderer (`%fmt-*`,
  ~51 KB) alive. Sources were the prelude's `check-type` (reached from the float writer) and
  a computed `(error message ..)`.
- Made: `check-type` renders its report at the signal
  ([declarations-type-checks.md](declarations-type-checks.md)), and the run-time library's
  literal-control rule. Either alone gains nothing; both: `print(1 + 2)` ~184 KB -> ~100 KB
  on every backend.
- Left: `%starlark-write` dispatches on every type, so a program printing any value keeps the
  float writer (~25 KB). Dropping it needs a proof that no float can exist (a literal, `/`,
  `float`, a value from `load("@package")`).

### Typed fast paths (2026-11-15)

Every candidate was measured first as a hand-edited lowering, per backend, and made only where
it paid. Seconds, JVM / WASM preview 1 /
component, best of three, precompiled `.cwasm`:

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

- The `range` loop: the range's vector was the cost (vector loop with inline arithmetic
  0.65 / 8.06, counter alone 0.32 / 3.72). `fib(35)` plain Common Lisp is 0.21 / 0.28; its
  `if .. return` as one `if` with no block: 0.63 / 0.98 -- not made.
- A tested operator costs its site a few dozen bytes, which is why operands that both MAY be
  ints are tested too, not only one beside a literal. A quoted `[1, 2]` is 56 B of class
  smaller than the call and one byte of wasm larger, so every constant expression is quoted,
  from one element on.

### Against the other implementations (2026-11-16)

Whole process, best of three: Go Starlark (`go.starlark.net` `4f4267fb140d`, `cmd/starlark
-recursion`, start-up 0.01 s), Java Starlark, CPython 3.12. Java Starlark is
`starlark_deploy.jar` of `c0af0ea`'s `test_suite` behind `~/.local/bin/starlark-java`, a runner
over `~/.local/share/starlark-java/RecursiveMain.java` setting `allow_recursion` and
`experimental_enable_starlark_set` (its own `cmd.Main` forbids recursion); start-up 0.30 s.

| program | JVM | WASM | component | Go | Java | CPython |
|---|---|---|---|---|---|---|
| `bench.star` | 0.16 | 0.07 | 0.07 | 0.54 | 0.82 | 0.12 |
| `fib.star` (`fib(35)`) | 0.28 | 0.45 | 0.44 | 22.02 | 4.55 | 1.48 |
| `loop.star` (`range(30,000,000)`) | 0.20 | 0.69 | 0.64 | 3.80 | 2.41 | 1.82 |
| `calls.star` | 0.22 | 0.27 | 0.30 | 7.31 | 2.03 | 0.75 |

Go is the implementation the error texts follow, so it is the one to compare against; Java
Starlark is a second opinion on semantics only. rontolisp compiles ahead of time and the
three others interpret: not a like-for-like contest.

### Against CPython (2026-11-16)

**Goal: a program compiled to the JVM, WASM preview 1 and the component runs in less wall
time than `python3` on the same file** (best of three, whole process). Met on wasm and the
component for all eleven programs below; on the JVM for six (`fib`, `loop`, `calls`, `mixed`,
`listidx`, `dicts`). Where it is not -- the JVM's five -- the cost left is a backend's, named
below. **The eleven are not what a build file does**: "A program a user wrote".

The eleven, each about a
second of CPython, each printing a checksum CPython, Go and Java Starlark agree on: `bench`,
`fib`, `loop`, `calls`, `mixed` (Collatz, sieve), `listidx` (`a[i]`, `a[i] = x`,
`a[i] += x`), `dicts`, `strings` (`+`, `+=`, `str`, `join`, `split`, `format`, `%`),
`comps`, `sorting`, `methods` (methods on parameters and list elements). CPython / Go / Java
seconds: `mixed` 1.21 / 4.58 / 3.15, `listidx` 1.20 / 3.55 / 2.51, `dicts` 0.92 / 4.63 /
2.10, `comps` 0.91 / 7.99 / 1.78, `methods` 1.12 / 9.31 / 3.64, `strings` 1.14 / 8.07 /
3.23, `sorting` 0.98 / 6.65 / 2.49. Current rontolisp numbers: "A program a user wrote".

Found, by sample profiles (JFR's `jdk.ExecutionSample` every 1 ms; `wasmtime run
--profile=guest`, whose function numbers `-Drontolisp.wasm.debug-func-sizes` names) and by
plain Common Lisp loops over each primitive:

- **A mutable character vector** where the library made a string: `sorting.star` at a third
  of its size was 44 s on wasm, 79% of it `%string-compare` ("The run-time library's rules").
- **`//` must not be `floor`.** `(values (floor a b))` made a ratio: 142 ns for `x // 2` on
  the JVM and 59 on wasm, against 39 and 17 for `(ash x -1)`. `floor` no longer makes a ratio
  of two integers (16 ns JVM, 9 wasm, `.kb/linalg-simd.md`, "Two integers"), but the
  lowering keeps `ash` / exact division.
- **Common Lisp lists in the library cost**: `[0] * n` as a list then a push per element
  (where `make-array .. :initial-element` is one call), `sorted` as `stable-sort` over
  decorated conses behind a `funcall` with `string<` then `string>` per comparison, the views
  through `%starlark-elements`, `join`/`format`/`%` through a string stream.
- **`search` behind its keywords**: 31% of `methods.star` on wasm was keyword parsing.
  `%starlark-search` calls `%string-search` and, where that declines, scans itself (the
  interpreter keeps `search`).
- **A template read on every call**: `"{}-{}".format(i, "x")` and `"%d:%s" % (i, s)` 4.8 us
  JVM, 5.7 wasm; read while lowering 1.5 and 0.9 (CPython 0.5).
- **The JVM's fixed cost**: `print(1)` is 0.10 s (CPython 0.03, wasm 0.03); the first 0.4 s
  is start-up and warm-up. A program CPython runs in a quarter of a second cannot win on the
  JVM; the programs are a second each for that reason. On the JVM the goal is for a second
  of CPython; wasm takes the short ones.
- **wasm calls for every generic operation** (ns per counting-loop step, plain Common Lisp):
  loop and `+` 9.4, `(aref v i)` of a general vector 28, a store 52, a structure predicate
  plus accessor 15, `(char-code (char s i))` ~30 (a character is an allocation). A Starlark
  `xs[i]` was some nine calls, 74 ns behind a `funcall`, where CPython's whole loop step is
  ~35. `(declare (type (vector t) ..))` of the list's vector changed nothing.
- **The JVM boxes**: `Long.valueOf` is 32% of `comps.star`'s samples -- the ELEMENTS a general
  vector holds as `Long`s, not the index `vector-push-extend` answers (0-3 samples below
  `_vectorPushExtend`); returning the fill-pointer's own `Long` moved nothing (HotSpot drops a
  box nobody reads). Not made.
- **A sort is a list's**: 50,000 ints by a merge sort over conses 13 ms JVM, 17 wasm; over a
  simple vector 30 and 139; packed `(unsigned-byte 32)` 79 and 42; CPython 9. So `sorted`
  compares ints inline and keys that are all strings of at most 64 characters as lists of
  integers read once (four characters to an integer when all are below 127).
- `"abc".replace("", "-")` is `-a-b-c-` as Go's and CPython's.
- The JVM's `comps.star` ranges 1.45-2.13 s over five runs: noise to expect.

### The list element on wasm (2026-11-16)

Profiler caveat: `wasmtime run --profile=guest` charges a large function's samples to its
small callees, so measure by hand-edited `.wat` and plain Common Lisp loops. On
`listidx.star` the vector access was the most (1.7 s of 4.86), a `stringp` of the vector
ahead of every store 0.5 s, structure calls 0.33 s. **Not only the calls**: a passing type
test costs ~1 ns and an access made some 35 of them; a call costs 2-5 ns with references
live across it (`.kb/adjustable-arrays.md`, "What an element access costs").

Made in the wasm backend: the inline arm of a general vector's rank-1 read and store; a
structure predicate and reader inline (`.kb/defstruct.md`); two fixnums compared at the site
of an unfused comparison (`.kb/wasm-int-fusion.md`); `_charvec_p` and `_seq_len` reordered
(`.kb/length-runtime.md`). In the library `%starlark-set-index` stores through the
accessor's value, not a variable holding it, so no `stringp` is asked. `listidx` wasm
4.86 -> 2.06 s. A Starlark module grows 4-9% (the library's own accesses carry the arms);
`--optimize=size` declines all of it.

### The cell names its storage (2026-11-16)

Measured on the module edited as text.
Made in the wasm backend: the cell of a rank-1 general vector names its storage and meta cons
in typed fields, so an element access is one cast of the array
(`.kb/adjustable-arrays.md`, "What an element access costs"); `length` reads the fill pointer
off the same field (`.kb/length-runtime.md`); an instance's slots field has the slots array's
own type (`.kb/defstruct.md`); `(vector t)` proves the general representation
(`.kb/declarations-type-checks.md`). In the library: the `items` slot of a list and a tuple
is `:type (vector t)`, which is what tells every `(length items)` and `(aref items i)` of the
file what it holds, and `%starlark-set-index` binds the items once (a variable place of that
type is asked nothing). `listidx` wasm 2.04 -> 1.24 s, `comps` 1.68 -> 1.16; bytes go down
(the arm is 58 B where the walk was 111).

- **Not made, measured**: inlining the boxed read of the loop variable (`_ub_read`):
  `loop.star` 0.69 -> 0.66 s for 24 B at each of 88 sites. The clear of the multiple-value
  channel an inlined structure reader keeps: 0.02-0.03 s of `listidx.star`'s 1.21.

### An int test compiled twice (2026-11-16)

**No construct of an element access is the
cost, their number is**: a hand-written sixty-instruction head in front of each index helper
was `listidx.star` 1.20 -> 0.79 s, while the repeated casts, subscript tests and channel
clears together were 0.14 of the 0.41; the compiled helper is two hundred instructions
(`.kb/wasm-int-fusion.md`, "An integer test compiled twice").

Made in the wasm backend: an `if` over `integerp` tests of variables is compiled twice, the
copy for fixnums over unboxed locals -- every operator the lowering tests (six type tests and
a generic call -> two fixnum tests and an `i64.add`) and the index helpers' subscript test;
an `if` in a test is its arms as tests; `mod`, `rem`, `*` by a literal inline in a fused tree;
a hash chain asks `ref.eq` before `_equal` (`.kb/hash-tables.md`). In the library:
`%starlark-index` / `%starlark-set-index` hold the list case alone (a writable list
subscripted by an int within it) and hand everything else to `%starlark-index-of` /
`%starlark-set-index-of`, so the compiled head carries no frame of the general function's
size (`listidx` 1.23 -> 1.15 s by that alone). wasm: `fib` 0.43 -> 0.21, `loop` 0.67 ->
0.39, `listidx` 1.21 -> 0.95 s; modules 0.2-2.1% larger.

- **Not made, measured**: one instance test for several readers of one variable
  (`.kb/defstruct.md`); `_t_sym` as a `global.get` (`sorting.star` 2.99 -> 2.87 s, and every
  module entry would have to set the global first); calls on paths the head never takes as
  tail calls (noise).
- The guest profiler is no witness here: it charged 52% of a loop of `gethash` to `puthash`,
  which the loop never calls.

### Strings nothing writes, ordered by the backend (2026-11-16)

`%starlark-less` asks `%string-order` after the int test and before `%starlark-compare` (nil
for anything but two strings); the code-list sort is gone; the case methods call
`%string-upcase-piece` / `-downcase-piece`, `title` and `repr` take `%string-stream-piece`
([string-write-runtime.md](string-write-runtime.md)). `sorted(words)` 24 x 50,000: JVM 1.60
-> 1.08, wasm 1.92 -> 1.13 (CPython 0.32); with `%string-order` only behind
`%starlark-compare` wasm stays 1.67. `s.upper().lower()` 10^6 times: JVM 1.56 -> 0.39, wasm
2.19 -> 0.92 (CPython 0.22).

### A string a loop appends to (2026-11-16)

`s += "ab"` in a `range` loop inside a `def`, seconds:

| pieces | JVM | WASM | CPython | Go | Java |
|---|---|---|---|---|---|
| 200,000, copying each time | 18.58 | 5.10 | 0.04 | 41 | 10.47 |
| 200,000, collected | 0.23 | 0.05 | 0.04 | | |
| 2,000,000, collected | 1.08 | 0.39 | 0.17 | | |

The lowering collects the pieces of a local nobody reads while the loop runs and joins them
once ("The lowering table"). The join: 2,000,000 pieces by halves 1.65 s JVM / 3.21 wasm,
through a string stream 0.83 / 0.36; but a stream's fixed cost: 4 pieces 10^6 times 0.57 s
by halves vs 1.50 by stream on wasm, crossing near 256 pieces (JVM near 64). So
`%starlark-concat-reversed` joins up to 64 pieces by halves and more through a stream. The
JVM's 2,000,000 ranges 0.69-1.48 s. Pinned by `StarlarkLoweringTest` (the row) and
`starlark-spec.yaml`'s `a-loop-gives-its-iterable-back-on-every-exit` (collected, captured, a
global, a list and an int, `break`, `continue`, nested loops, past 64 pieces) and
`common-lisp-and-starlark-call-each-other` (the refused piece).

### Where the time is: a sampler of the machine code (2026-11-16)

**A sampler of the machine code, not the guest profiler or hand-edited modules, names where a
program waits** (`.kb/wasm-int-fusion.md`,
"Profiling trap": `ptrace`, no `perf`, samples per function and per instruction). It
overturned the plan (a helper's head at its call site): the programs did not wait on the
index helpers but on dict wrappers around `gethash` (six look-ups a round), the dict
comprehension's store, a merge sort over conses, `str(i)` and `%starlark-conversion`'s
`find`, `split`'s grown-from-nothing vector, and `_str_lit` (a method's own name made to be
handed to a check that passes).

Made in the library and the lowering: `a[k] op= v` on a dict one look-up
(`%starlark-item-cell`), a dict comprehension's store one (`%starlark-dict-put`), a key asked
for an int before a string; a loop is `while` (a counter over a constant bound is a counted
loop's, `.kb/wasm-counted-loops.md`); `join` of more than 64 strings through a string stream;
`split` reserves room for eight; the merge sort takes its cells off the front as the
recursion reaches them (`%starlark-sort-cells`) and asks `%string-order` first for a list
that starts with a string (`%starlark-merge-strings`). In the wasm backend, for every
language: `.kb/hash-tables.md`, `.kb/wasm-gc-strings.md`, `.kb/sequence-op-runtimes.md`,
`.kb/string-search-runtime.md`. wasm: `dicts` 1.20 -> 0.83, `strings` 3.04 -> 1.50 s.

- **A sorted string is four cache misses a comparison.** A third of `_str_order`'s samples
  under `sorted(words)` load an operand's headers (two structs, two byte arrays; 50,000
  strings are 4 MB), so the walk's shape moved nothing (`.kb/string-search-runtime.md`);
  `sorted` of strings stayed three times CPython's until a key was read once ("A sequence at
  a time").
- **Rejected: a radix sort of ints in the library** (`(unsigned-byte 32)` vectors). On the
  JVM C2 made 109 KB of code of the pass's 495 bytes (the packed vector's generic accessors
  inlined) in 0.7 s and again after each unseen branch: ten seconds of compiler threads over
  `sorting.star`, a net loss. On wasm a program calling `sorted` carried 9 KB for it plus
  10 KB of packed-vector arms in every generic access of the library. Replaced by the
  backend's `%sort-by-keys` ("A sequence at a time").
- **Not made, measured**: a merge sort over packed vectors (25 ms for 50,000 ints, 21 with
  masked indices, conses 20, radix 8); items beside the keys in `(simple-array t (*))`
  vectors (`.kb/declarations-type-checks.md`: 66 ns a move).
- Pinned by `starlark-spec.yaml` `what-the-lowering-knows-answers-as-the-generic-paths`
  (`fast_sort`: 600 and 2,100 ints, negative, by a key both ways with the places of equal
  keys, a span past 2^32, floats and strings on the merge; `fast_items`: an item updated
  through its cell by each operator, a comprehension storing a key twice),
  `common-lisp-and-starlark-call-each-other` (the cell of a frozen dict and of a key that is
  not there), `StarlarkLoweringTest` (the rows), and the conformance corpus
  (`rust/mutation_during_iteration.star`: `dict[k] += 1` in a loop over it); the backend's
  halves by ci-spec `typed-vector-struct-packs-and-replace-bulk`,
  `format-nil-princ-to-string-prin1-to` and `string-comparison-family`.

### A sequence at a time (2026-11-16)

The sampler's instruction counts put `comps.star` in the GROWTH of the vector its
comprehensions push into, not in its loops' heads (a loop-counter plan was dropped).

Made in the wasm backend, for every language:

- **`%string-split`, `%string-join`, `%sort-by-keys`** ([bulk-primitives.md](bulk-primitives.md)):
  one function each over a string's bytes or a vector's storage; nil on the interpreter and
  the JVM. `sorted` reads a key once -- an int's 64 bits, a string's first seven bytes and
  its length -- and radix-sorts the keys' places, strings past the prefix by a merge of runs.
- **`vector-push-extend` grows with one `array.copy`** (`.kb/adjustable-arrays.md`, "The
  growth policy"): `comps.star` 0.86 -> 0.66 s by that alone.
- **A `make-array` with no element type proves the general kind**
  (`.kb/declarations-type-checks.md`, source 2): `(setf (aref v i) x)` on a `let`-bound `v`
  asked `stringp` at every store -- 11% of `list(range(n))`.
- **A chain of `%string-concat` is one string** (`.kb/wasm-gc-strings.md`, "A chain of
  concatenations is one string").

wasm: `sorting` 1.98 -> 0.73, `strings` 1.54 -> 0.91, `methods` 2.24 -> 1.45, `comps` 0.94 ->
0.69 s. A program that sorts or formats ints got smaller (no library radix sort; `str` of a
known int no longer reaches `%starlark-b-str` nor through it `repr`).

- **Not made, measured**: `_sort_keys` as a merge over the keys' words (14.5 ms for 50,000
  ints vs the radix sort's 3.7: a merge mispredicts every other branch); a byte loop for a
  short operand in `_string_concat` and split pieces (slower than `array.copy`,
  [bulk-primitives.md](bulk-primitives.md)); `StringValuedForms.certainlyString` as the
  licence to skip a piece's normalization (a piece routed through a printer control is a
  character vector: the ci-spec corpus trapped).
- Pinned by `starlark-spec.yaml` `what-the-lowering-knows-answers-as-the-generic-paths`
  (`fast_bulk`, `fast_text`), `StarlarkLoweringTest` (the rows of a template and of `str`
  over a known int), ci-spec `format-nil-princ-to-string-prin1-to` (the chain) and the
  conformance corpus; the backend's halves also by ci-spec `vector-push-extend-growth` and
  the array suites (`ArefRankAndSubscriptChecksFixture`).

### A literal is a global (2026-11-16)

Made in the wasm backend, for every language:

- **A literal a function names is a global the module's start function fills**
  (`.kb/wasm-gc-strings.md`, "A literal is one object"): a site is `global.get`, where it was a
  call of `_str_lit` and a table read -- 9% of `methods.star`'s samples (`:none` a method
  answers, `:%unset` for each argument a call leaves out, a string argument). A literal of a
  form that signals is built where it stands.
- **`%string-count`** (`.kb/bulk-primitives.md`): one walk (a search an occurrence was
  10.8% of `methods.star`); `%string-search` over a counted string takes a bound and answers
  with no offset call or second walk (`.kb/string-search-runtime.md`).
- **A character read for its code makes no character** (`.kb/characters-code-points.md`).
- One-byte case conversion moves bytes (`.kb/wasm-gc-strings.md`, "Character accessors"),
  `vector-pop` inline (`.kb/adjustable-arrays.md`), a short split piece one
  `array.new_fixed` (`.kb/bulk-primitives.md`), **a string keeps its hash**
  (`.kb/hash-tables.md`: `dicts.star` 0.85 -> 0.75 s by that alone).

In the library, on every backend: `count` asks `%string-count` first; `strip` reads codes
(`%starlark-cut-p`); `startswith`/`endswith` make no list of one prefix
(`%starlark-affix-of`); `get` asks the table itself for an int or string key, and a store
into a writable dict goes from `%starlark-set-index` straight to `%starlark-dict-store`;
`append` and `pop` ask `eql` of the loop count.

- **All eleven are under CPython on wasm** and on the component: `methods.star` 1.42 -> 1.04
  against 1.10 (by a twentieth), `dicts.star` 0.78 against 0.92. Short programs' hundredths
  are noise (`calls.star` 129-140 ms against 132-142 over 25 runs each). **Still all eleven
  on wasmtime 51.0.0** (2026-11-22, the same `.wasm` precompiled by 49 and 51): `methods`
  1.20 / 1.16 against 1.30, `dicts` 0.90 / 0.92 against 1.03.
- **What a wasm function costs is its count of type operations** -- a cast, a test, a
  bounds-checked `array.get`, ten to fifteen machine instructions each: `_ht_get` is a dozen
  over `(ref null eq)` arrays (27 ns a fixnum-key look-up), `%starlark-m-append` some 300
  instructions for three guards and a push, each casting the list's instance again.
- Size: every Starlark program ~1 KB larger (its literals), one with string methods ~3.7 KB.
- **Not made, measured**: the hash table's entry as a struct with typed fields; a structure
  reader that takes an instance's slots once; a one-call `def` inlined; `(i * 31) % m` by a
  variable inline (`_fx_mod`, 7.5% of `dicts.star`).
- Pinned by `starlark-spec.yaml` `what-the-lowering-knows-answers-as-the-generic-paths`
  (`fast_strings`: `count`, `find` and the affixes over a string of one-byte characters, one
  of several and one never counted, each bound; `strip` by white space and by a cutset of
  several bytes; `upper` and `lower` both ways; a split of pieces of eight bytes, nine and
  several-byte characters; `pop()` to an empty list; `get` and a store by an int, a string, a
  tuple and a float key) and the conformance corpus; the backend's halves by ci-spec
  `eql-of-a-literal-and-a-literal-evaluated-twice`, `code-point-characters-beyond-ascii`,
  `string-case-ops-full-unicode`, `displaced-fill-pointer-cross-backend` and
  `search-over-strings-answers-characters-in-either-representation`.

### The JVM's arms (2026-11-16)

Profiled first (JFR `jdk.ExecutionSample` every 1 ms): `comps.star` waits on none of the bulk
primitives (`_vectorPushExtend` 23%, the dict comprehension's store 20% -- half of it the
table's growth -- the loops' own body 31%).

Made in the JVM backend, for every language (`JvmBulkRuntimeBuilder`,
[bulk-primitives.md](bulk-primitives.md), "JVM"): the arms of `%sort-by-keys`,
`%string-split`, `%string-join`, `%string-count`, `%string-search`
([string-search-runtime.md](string-search-runtime.md)) and `%replace-bulk`
([sequence-op-runtimes.md](sequence-op-runtimes.md)), and a short string counted where it
stands ([string-index-cost.md](string-index-cost.md)). Nothing in the library or the
lowering changed: the interpreter still runs the callers' loops. JVM: `sorting` 3.31 -> 1.33,
`strings` 2.32 -> 1.90, `methods` 1.73 -> 1.39 s.

- **What a sort waits on is the compiler** ([bulk-primitives.md](bulk-primitives.md), "JVM"):
  5.1 ms for a sort of 50,000 ints or strings from the hundredth round on, 11-15 ms before;
  `sorting.star` is 96 sorts in a process whose compiler threads take four of its five CPU
  seconds.
- **Not made, measured**: a concatenation through one `StringBuilder` made at its size (36 ns
  for `"item"` and a number against 20 for `substring`, `substring`, `concat`; a `char[]` 26;
  the deprecated Latin-1 `getBytes` pair 17 and wrong for any other string).
- Size: every Starlark class carries `_strSearch`, `_bulkCopy` and helpers, 2.3 KB; a Common
  Lisp class reaching neither `search` nor `replace` is unchanged.
- Pinned by `starlark-spec.yaml` `what-the-lowering-knows-answers-as-the-generic-paths`
  (`fast_bulk`, `fast_sort`, `fast_strings`, and `fast_wide`: strings of characters below and
  above 256 and outside the BMP sorted both ways and by a key, joined, split, counted and
  searched at 40 and at 6, a tuple's vector with False among its ints), the conformance
  corpus, and for the two Common Lisp primitives ci-spec
  `search-over-strings-answers-characters-in-either-representation`,
  `typed-vector-struct-packs-and-replace-bulk` and the array suites.

### A program a user wrote (2026-11-17)

- **The JVM's `comps.star` waits on the compiler, not on a push.** 800 rounds are 5.6-5.9 s
  where 80 are 1.5-1.6: a round is 7 ms once compiled, CPython's 11-12. JFR's
  `jdk.Compilation` / `jdk.Deoptimization` (the machine's `java` is Oracle
  GraalVM 25, top tier Graal): `run` (2,273 bytes, five loops) is compiled on-stack three
  times, up to 450-660 ms and 165-198 KB, the code before each thrown away by a type check
  its profile had not seen.
- **A program in the shape of the repository's own example is not under CPython anywhere.**
  `graph.star` of the artefacts is `examples/starlark/targets.star` at size (2,000 targets
  made by a loop: dicts of three string keys, lists of up to three names, `"t%d" % i`; build
  order, what a change rebuilds, a line per target; 40 times). The eleven build a few large
  collections; this one makes many small ones.

Made in the library and the lowering ("The lowering table", "The run-time library's
rules"): room for four in an appended-to list and the loop's count in a one-loop
comprehension; `"t%d" % x` over a non-tuple-expression operand read while lowering; five to
eight template pieces a chain; `x[k]` of a dict asked in `%starlark-index`. In the wasm
backend (`.kb/hash-tables.md`, "Representation"): two string keys compare bytes where the
chain stands, and a small record's string key is looked for by identity first.

Seconds, whole process, best of three (`time.sh`), the current state of the eleven:

| program | JVM | WASM | component | CPython |
|---|---|---|---|---|
| `bench.star` | 0.16 | 0.05 | 0.04 | 0.14 |
| `fib.star` | 0.32 | 0.17 | 0.19 | 1.46 |
| `loop.star` | 0.18 | 0.32 | 0.32 | 1.63 |
| `calls.star` | 0.27 | 0.13 | 0.14 | 0.75 |
| `mixed.star` | 0.72 | 0.50 | 0.47 | 1.21 |
| `listidx.star` | 1.13 | 0.94 | 0.94 | 1.19 |
| `dicts.star` | 0.86 | 0.80 | 0.80 | 0.90 |
| `comps.star` | 1.52 | 0.67 | 0.67 | 0.88 |
| `methods.star` | 1.44 | 1.06 | 1.04 | 1.10 |
| `strings.star` | 1.80 | 0.84 | 0.84 | 1.16 |
| `sorting.star` | 1.34 | 0.71 | 0.69 | 0.98 |
| `graph.star` | 1.80 | 1.12 | 1.09 | 0.48 |

`graph.star`: Go 4.91, Java Starlark 1.92.

- **A whole-program time on wasm moves with where its collections fall.** At 1,500 / 2,000 /
  2,500 / 3,000 targets `graph.star` was 0.72 / 1.12 / 1.08 / 1.50 s (CPython 0.38 / 0.48 /
  0.62 / 0.73): one change made it slower at 2,000 and faster at the other sizes and on every
  part. With no collection (`-C collector=null`, 345 MB) 0.92 s against 1.16: the copying
  collector is a fifth of the run. Compare builds on the parts or at several sizes.
- **The samples of `graph.star` on wasm**: a dict look-up by a string 25%
  (`%starlark-dict-cell` 10% itself -- some 110 machine instructions for a string key:
  `integerp`'s two failed struct tests, `stringp`'s test then cast, its bytes' cast and a
  bounds-checked byte, the table's accessor, `gethash`'s three tests --, `_ht_get` 10%,
  `_string_eq` 5%), `_string_concat` and the pieces 9%, and a long tail.
- **The JVM** (JFR over `graph.star`, 1.9 s wall, 10 s CPU): 18 top-tier compilations of 60
  ms or more; each function compiled two to four times at 60-100 KB. At ten times the rounds
  8.0 s against CPython's 5.4, so it is over CPython compiled as well. C2
  (`-XX:-UseJVMCICompiler`) is 1.75-1.83 s on 40 rounds, 9.3 on 400.
- **Not made, measured**: `_vectorPushExtend` storing in place with one `ensureCapacity`
  ahead of the growth -- `comps.star` at 800 rounds 5.69-5.94 -> 5.46-5.70 s, but at 80
  rounds and on `methods.star` worse and `run`'s compilations unchanged: the 80 rounds do not
  wait on the push. A string key asked before an int in `%starlark-dict-cell` (nothing
  measurable on wasm). `wasmtime compile -C inlining=y` (1.09 -> 1.16-1.25 s, code 1.76 ->
  2.55 MB).
- **Left**: the dict look-up by a string, the collector's fifth and what the JVM compiles.
- Pinned by `starlark-spec.yaml` `what-the-lowering-knows-answers-as-the-generic-paths`
  (`fast_graph`: `%` over an int, a string, a list, None, a Boolean and a tuple held by a
  variable; a small dict and one of twelve keys read by strings made at run time, hits and
  misses; `[]` appended to past its room, a comprehension's list appended to; templates of
  seven and nine pieces), `StarlarkLoweringTest` (the rows of the template and of the
  comprehension), the conformance corpus, and ci-spec's hash-table cases.

## Positions

A static error is a `LispReadException` at `file:line:column`. A run-time one is reported as
every language's is ([error-handling.md](error-handling.md)): `  at FILE:LINE in FUNCTION` on
the interpreter and the JVM, and on wasm under `--report-locations` -- no column.
`StarlarkLowering.located` places every form at its node (`SourceProvenance.record` when a
compile-path scope is open, else a `LocatedCons` copy for a named file). A direct `def` is a
named function (`in f`); a form in any other `def` or a `lambda` belongs to the direct `def`
it is written in, and at the top level to none. Pins:
`UncaughtReportParityTest#aFrontEndsSourceIsLocatedTheSameWay`,
`WasmReportLocationsTest#aFrontEndsProgramNamesTheFunctionHoldingTheForm`.

## Reader

`StarlarkLexer` and `StarlarkParser` (recursive descent over the spec's "Grammar
reference") build a `StarlarkSyntax` tree. Errors are positioned `LispReadException`s;
`isEndOfFile()` is true when the input ran out (what a session's `isComplete` asks).

- Lexing is lazy, so the first error in source order is the one reported, lexical or
  syntactic.
- Indentation is spaces only (the spec; Python accepts tabs).
- Literals: a leading zero in an int is an error (the spec grammar has only `0`); an infinite
  float literal is an error; octal and hex string escapes at most 127, Unicode escapes refuse
  surrogates; bytes escapes are raw bytes up to 255. Raw strings keep the backslash before a
  quote or line ending, as Python does.
- Rejected with a position, though Python accepts them: the reserved words (`while`,
  `class`, `import`, `is`, `try`, `with`, `yield`, `global`, `nonlocal`, ...), string
  prefixes other than `r`/`b`/`rb`/`br` (f-strings), chained comparison, chained assignment,
  `for`/`else`, a slice or call as a target, a tuple or list as an augmented target, an
  unparenthesized tuple with a trailing comma, arguments or parameters out of the spec's
  order, a bare `*` with no keyword-only parameter after it.
- Accepted though Python rejects: non-ASCII text in a bytes literal (the spec encodes it as
  UTF-8), a `lambda` as a comprehension's `if` condition (the spec's grammar and Go's parser
  allow it; Python 3.9+ does not).
- Positions: the operator for a unary or binary node, `if` for a conditional, the opening
  bracket for a call, subscript, slice or collection, the field name for a dot, the first
  token otherwise. Columns are 1-based UTF-16 units.

Measured against Python 3.12's `ast` (`astdump.py` in the prototype's directory prints `ast`
as `StarlarkTrees` prints a tree, with `@line:column`): the 430 corpus chunks, the
prototype's `demo.star` and `bench.star` and a 47-statement lexical stress file -- every tree
identical, positions included. The corpus has no float or bytes literal and no syntax Python
accepts and Starlark rejects, so `StarlarkParserTest` pins those by hand.

## Tests

`StarlarkSpecE2eTest#conformance` runs bazelbuild/starlark's `test_suite/testdata`
(`src/test/resources/starlark-conformance/`, Apache-2.0, its `LICENSE` beside it), plus
`spec/bytes.star` and `spec/set.star` written here from `spec.md`'s examples, on four
backends, one program per FILE: each chunk lowered alone, its names renamed `c<N>%name` so it
has globals of its own, each top-level statement behind `(if (null failure) (handler-case
..))` so a chunk stops at its first error and the next runs. An error is matched against the
chunk's `###` patterns (any implementation's variant accepted; EXPECTED when a pattern names
no implementation or names `go`). `KNOWN_FAILURES` names the chunks that still fail, each
with why; one that passes fails the test until it is taken off. The compiled legs expand
through `CompileFrontendAccess.lowered` (`CompileFrontend.expand`).

`StarlarkSpecE2eTest` over `starlark-spec.yaml` (in process, four backends: the prototype's
feature tour, then a case per decision above; a `standalone:` case is a program of its own
read against its `standard:`, with `files:` read from a directory, its `entry:` a `.star` or
a `.lisp` file), `StarlarkLoweringTest` (the table's rows as emitted forms -- a source
starting `# spec` is read against `spec` -- the refusals, helper arity),
`SourceLanguageTest#starlarkClaimsItsExtension`,
`RontoLispCliStreamsTest#aStarlarkFileRunsCompilesAndIsRefusedWhereItCannot` (and
`--starlark-standard`), `SchemeLibraryTest#aCommonLispProgramReadsNoFrontEndsLibrary` (the
gate), `DocExamplesTest` (a ` ```starlark ` fence is a whole program, its output the plain
block after it), `ExamplesE2eTest` (`examples/starlark/targets.star`). A session:
`RontoLispCliTest#theStarlarkReplRunsItsBuffersAsOneFile`,
`PlaygroundReplTest#theStarlarkPickKeepsItsGlobalsAndModulesAcrossBuffersAndRunsAProgram`,
`SourceLanguageTest#starlarkClaimsItsExtension` (`isComplete`),
`StarlarkParserTest#nodesCarryTheirPositionAndAnUnfinishedInputSaysSo`.
