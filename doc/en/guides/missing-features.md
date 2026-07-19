# Unsupported Common Lisp Features

rontolisp is a deliberately small subset of Common Lisp that runs identically on
three backends (interpreter, JVM, WASM). To keep the language compilable to plain
bytecode without a runtime metaobject protocol, many features of full Common Lisp
are intentionally left out.

This page lists the most notable omissions. For what **is** available, see the
[Language Reference](../reference/special-forms.md), or list it at runtime with
`rontolisp:list-special-forms`, `rontolisp:list-macros`, and
`rontolisp:list-functions`.

| Feature | Status |
| --- | --- |
| `defmacro` (user macros) | available (see [`defmacro`](../reference/special-forms/defmacro.md)) |
| `&optional` / `&rest` / `&key` / `&aux` | available in `defun`/`lambda` (see [`defun`](../reference/special-forms/defun.md)); `defmacro` takes `&rest`/`&body` only |
| `&whole` | not available |
| `values` / `multiple-value-bind` | available, including user-function values (see [`multiple-value-bind`](../reference/macros/multiple-value-bind.md)) |
| `block` / `return-from` / `tagbody` / `go` | available ([`block`](../reference/macros/block.md)/[`return-from`](../reference/macros/return-from.md) are named exits on every backend — dynamic extent on the interpreter, lexical (same-function) on the compilers; [`tagbody`](../reference/special-forms/tagbody.md)/[`go`](../reference/special-forms/go.md) likewise, with the compilers supporting the lexical subset) |
| `catch` / `throw` | not available |
| `unwind-protect` | available (see [`unwind-protect`](../reference/special-forms/unwind-protect.md)); on wasm-GC via the exception-handling proposal (`wasmtime -W exceptions=y`); compile error under `--no-gc` |
| conditions (`define-condition`, `handler-case`, `ignore-errors`, `signal`) | available (see [`handler-case`](../reference/macros/handler-case.md)); on wasm-GC catching needs `wasmtime -W exceptions=y`, and runtime traps stay uncatchable there; compile error under `--no-gc`. Restarts (`handler-bind`/`restart-case`) are not available |
| `flet` / `labels` | available (see [`flet`](../reference/macros/flet.md), [`labels`](../reference/macros/labels.md)) |
| `macrolet` | available (see [`macrolet`](../reference/macros/macrolet.md)); `symbol-macrolet` is not available |
| `loop` (extended) | partial (simple-loop subset) |
| `defstruct` | available, including the `:constructor`/`:conc-name`/`:predicate`/`:copier` options and lite BOA constructors (see [`defstruct`](../reference/special-forms/defstruct.md)); `:include` is not |
| CLOS | partial (static subset: [`defclass`](../reference/special-forms/defclass.md), [`defgeneric`](../reference/special-forms/defgeneric.md), [`defmethod`](../reference/special-forms/defmethod.md), [`make-instance`](../reference/macros/make-instance.md), [`slot-value`](../reference/macros/slot-value.md)) |
| `declare` / `declaim` / `proclaim` / `the` | available as parsed no-ops (see [`declare`](../reference/macros/declare.md)) |
| `check-type` / `assert` | available (lite, no restarts; see [`check-type`](../reference/macros/check-type.md)) |
| `eval-when` | available (treated as `progn`; see [`eval-when`](../reference/macros/eval-when.md)) |
| `typep` | available with literal (quoted) type specifiers (see [`typep`](../reference/macros/typep.md)); [`subtypep`](../reference/functions/subtypep.md) too |
| `coerce` | partial (literal `'list` / `'vector` / `'string` result types; see [`coerce`](../reference/functions/coerce.md)) |
| `defpackage` (user packages) | partial (`:use`/`:export`/`:nicknames`/`:import-from`; see [`defpackage`](../reference/special-forms/defpackage.md)) |
| `make-package` / `export` / `use-package` (runtime) | not available |
| `#+` / `#-` / `*features*` / `#\| ... \|#` | available (see [Data Types](../reference/data-types.md#comments-feature-conditionals-and-features)) |
| `#.` read-time eval / `#:` fresh uninterned symbols | partial (`#.` is available — each datum evaluates just before its top-level form, against the compile-time evaluator on the compile path; skipped with a warning in `.asd` files; `#:name` reads as a plain symbol, accepted as a designator, without gensym-style freshness) |
| `require` / `provide` | available (see [`require`](../reference/functions/require.md)); the `*modules*` variable is not available |
| dynamic (special) binding via `let` | available (`defvar`/`defparameter`/`declaim special` proclaim a name special; `progv` is interpreter only) |
| complex numbers | not available |

## User-defined macros (`defmacro`)

User macros **are** supported — see [`defmacro`](../reference/special-forms/defmacro.md)
for the details, including the backquote template syntax (nested backquote
included) and the limitations (`&whole`/`&environment` are unsupported, and
macros are unknown to the runtime `eval` of compiled programs). The built-in
macro set (`cond`, `case`, `when`, `unless`,
`dotimes`, `dolist`, `do`, `setf`, `push`, `pop`, `incf`, ...) can be listed with
`(rontolisp:list-macros)`; those names cannot be redefined.

## Lambda list keywords (`&optional`, `&rest`, `&key`, `&aux`)

`defun` and `lambda` support `&optional`, `&rest`, `&key`, `&allow-other-keys`,
and `&aux` — see [`defun`](../reference/special-forms/defun.md) for the details.
The remaining gaps: `&whole` is not available, a `defmacro` lambda list still
accepts only required parameters plus one trailing `&rest`/`&body`, a function
is limited to 7 physical parameters on the funcall/apply path, and a `lambda`
built at runtime by the compiled `eval` does not parse lambda-list keywords
(see [Compiled eval Limitations](eval-limitations.md)).

## Multiple values (`values`, `multiple-value-bind`)

Multiple values **are** available: [`values`](../reference/functions/values.md),
[`values-list`](../reference/functions/values-list.md),
[`multiple-value-bind`](../reference/macros/multiple-value-bind.md),
[`multiple-value-list`](../reference/macros/multiple-value-list.md),
[`multiple-value-call`](../reference/macros/multiple-value-call.md) and
[`nth-value`](../reference/macros/nth-value.md), plus the secondary values of
`floor`/`ceiling`/`round`/`truncate` (remainder), `gethash` (present-p) and
`parse-integer` (stop position), and the optional divisor argument of the
`floor` family. A `(values ...)` in result position of a **user function**
reaches the caller's consumer through an internal channel, so the common CL
idioms work:

```console
> (defun two () (values 1 2))
> (multiple-value-bind (a b) (two) (list a b))
(1 2)
```

The remaining deviations from Common Lisp:

- a producer that calls `values` in a **non-tail** position and then returns
  normally may leave stale extra values behind, so keep `values` in result
  position;
- `funcall #'values` (the first-class value) yields the primary value only in
  compiled programs;
- `multiple-value-call` with a built-in `#'name` keeps the wrapper's fixed
  arity — pass a user function or `lambda` for other argument counts;
- other built-ins with secondary values in CL (`read-from-string`,
  `macroexpand-1`, `intern`, ...) remain single-value.

## Non-local exit and control flow

Named-block and label-and-jump control flow is available; only dynamically
scoped `catch`/`throw` is not:

- [`block`](../reference/macros/block.md) /
  [`return-from`](../reference/macros/return-from.md) — **available**. A
  `defun`/`defmethod` body is an implicit block named after the
  function/generic, and [`return`](../reference/macros/return.md) exits the
  **nearest** enclosing iteration block established by
  `do` / `do*` / `dolist` / `dotimes`. On the interpreter the named exit is
  dynamic (it crosses closures called within the block's extent); the
  JVM/WASM compilers support the lexical subset — the target block must
  lexically enclose the `return-from` in the same function, so a
  `return-from` inside a lambda whose name matches no enclosing block exits
  that lambda instead.
- [`tagbody`](../reference/special-forms/tagbody.md) /
  [`go`](../reference/special-forms/go.md) — **available**, along with
  [`prog`](../reference/macros/prog.md) /
  [`prog*`](../reference/macros/prog-star.md). The JVM/WASM compilers support
  the lexical subset (a `go` must target a tag of a lexically enclosing
  `tagbody` in the same function); the interpreter additionally supports
  dynamic `go` across function boundaries.
- `catch` / `throw` — no dynamically scoped exits.

```lisp
(block done (return-from done 1) 2) ; => 1
```

[`unwind-protect`](../reference/special-forms/unwind-protect.md) (cleanup on
every exit — normal return, `error` unwind, `return`/`return-from`) **is**
available on every backend except `--no-gc`; on the wasm-GC backends it uses
the WebAssembly exception-handling proposal, so the emitted module needs
`wasmtime -W exceptions=y` (37+) and a runtime trap still skips the cleanups
there.

## Conditions and restarts

The condition-system core **is** available: condition types are CLOS-subset
classes over the built-in hierarchy (`condition` > `serious-condition` >
`error`, `warning`) defined by
[`define-condition`](../reference/macros/define-condition.md) (with `:report`),
constructed by [`make-condition`](../reference/macros/make-condition.md) or the
typed [`error`](../reference/macros/error.md)/[`signal`](../reference/macros/signal.md)
designators, and caught by type with
[`handler-case`](../reference/macros/handler-case.md) /
[`ignore-errors`](../reference/macros/ignore-errors.md) on every backend except
`--no-gc` (a compile error there). On the wasm-GC backends catching uses the
WebAssembly exception-handling proposal (`wasmtime -W exceptions=y`, 37+), and
only **signaled** conditions are catchable — a runtime trap still aborts.

```lisp
(handler-case (error "boom") (error (e) :caught)) ; => :caught
```

The **restart system** is not available: `handler-bind`, `restart-case`
(accepted as a no-op that keeps only the primary form), `restart-bind`,
`invoke-restart`, `with-simple-restart`, `cerror`, `abort`, `continue` and
`break` are absent, and `check-type`/`assert` signal without offering a
re-store restart.

## Local macros (`macrolet`)

Local functions **are** available -- see [`flet`](../reference/macros/flet.md)
and [`labels`](../reference/macros/labels.md). Local macros
([`macrolet`](../reference/macros/macrolet.md)) **are** available too -- the
compile path expands them away before the compilers run, and the interpreter
expands them natively; `symbol-macrolet` is not available.

## The `loop` macro

A bounded subset of the extended `loop` **is** available — see
[`loop`](../reference/macros/loop.md). It covers numeric/list stepping (`for`),
string stepping (`for ... across`), the common accumulators (`collect`,
`append`, `sum`, `count`, `maximize`, `minimize`, ...), and simple control
clauses (`while`/`until`, `repeat`, `when`/`unless`, `finally`, `return`). Out of
scope are destructuring, parallel `and` between `for` clauses, `being`, the
anaphoric `it`, `named`/`loop-finish`, and `thereis`/`always`/`never`. The other iteration forms
(`do`, `dolist`, `dotimes`, `while`) remain available.

## Structures and objects (`defstruct`, CLOS)

Structures **are** available with
[`defstruct`](../reference/special-forms/defstruct.md), which generates a
keyword constructor, a predicate, a copier and `setf`-able accessors, and
supports the `:constructor`/`:conc-name`/`:predicate`/`:copier` options and
lite BOA constructors (a slot named by the lambda list reads that parameter,
the rest evaluate their initforms). `:include` inheritance and the `#S(...)`
print/read syntax are not supported.

A **static CLOS subset** is available:
[`defclass`](../reference/special-forms/defclass.md) (single inheritance,
`:initarg`/`:initform`/`:reader`/`:accessor` slot options),
[`make-instance`](../reference/macros/make-instance.md) and
[`slot-value`](../reference/macros/slot-value.md) (both with literal quoted
names), and [`defgeneric`](../reference/special-forms/defgeneric.md) /
[`defmethod`](../reference/special-forms/defmethod.md) dispatching on the first
argument (`eql`, class, and built-in-type specializers), including standard
method combination — `:before`/`:after`/`:around` qualifiers, `call-next-method`,
and `next-method-p` (for class and default methods). Out of scope: multiple
inheritance, specializers on later arguments, `slot-boundp`, and the MOP /
runtime class operations (`find-class`, `change-class`, `add-method`, class
redefinition) — the class and method sets of a compiled program are fixed at
compile time.

## Type declarations, `typep`, and `coerce`

Type declarations **are** accepted as parsed no-ops:
[`declare`](../reference/macros/declare.md),
[`declaim`](../reference/macros/declaim.md),
[`proclaim`](../reference/macros/proclaim.md), and
[`the`](../reference/macros/the.md) all parse and have no effect, so annotated
sources load unchanged. [`check-type`](../reference/macros/check-type.md) and
[`assert`](../reference/macros/assert.md) provide actual runtime checks (lite,
without restarts). The runtime helper [`typep`](../reference/macros/typep.md)
**is** available with a literal (quoted) type specifier, as is
[`subtypep`](../reference/functions/subtypep.md).
[`coerce`](../reference/functions/coerce.md) **is** available for the literal
result types `'list`, `'vector` and `'string` (the result type must be a quoted
literal, like `map`'s); other result types are not supported.

## User-defined packages

New packages **can** be defined with
[`defpackage`](../reference/special-forms/defpackage.md), as a literal,
top-level, read/compile-time directive supporting the `:use`, `:export`,
`:nicknames` and `:import-from` clauses (`:documentation`/`:size` are accepted
and ignored; see
[Packages](../reference/packages.md#user-defined-packages-defpackage)).
`:shadow` and `:shadowing-import-from` are errors (there is no symbol
shadowing), and there is no **runtime** package manipulation: `make-package`,
`export`, `import`, `use-package`, `find-package`, and `rename-package` are
not available. A package's set of exported (external) symbols is fixed when it
is defined; the single/double colon qualifiers (`pkg:name` for external
symbols, `pkg::name` for internal ones) work as in Common Lisp (see
[Packages](../reference/packages.md#external-and-internal-symbols)). The
standard nicknames `common-lisp`/`common-lisp-user` resolve to `cl`/`cl-user`,
and `#:name` designators are accepted. When several used packages export the
same name, the first package in `:use` order wins instead of signaling a
conflict.

## Dynamic (special) variable binding

Dynamic (special) variable binding **is** supported. `defvar`, `defparameter`,
and `defconstant` proclaim their variable special (as does
`(declaim (special *x*))`), and a `let`/`let*` of a special name establishes a
dynamic binding — visible to functions called within the extent and restored on
exit — rather than a lexical one:

```console
> (defvar *factor* 1)
> (defun scale (n) (* n *factor*))
> (let ((*factor* 10)) (scale 5))
50
```

The bindings are per thread of control, so concurrent
[`rontolisp:http-handler`](../reference/functions/rontolisp-http-handler.md)
requests never see each other's. Two limitations remain on the **compiled**
backends (the interpreter is unaffected): [`progv`](../reference/special-forms/progv.md)
(runtime-computed lists of symbols) is interpreter-only and a compile error on
the JVM/WASM backends, and a `return`/`return-from` that unwinds *across* a
special `let` boundary does not restore the global there (normal exit and error
abort are fine).

## Numeric tower

rontolisp supports integers (including arbitrary-precision bignums), ratios
(`1/3`), and double floats, but **not complex numbers**. A negative square root
yields a float `NaN` rather than a complex result:

```console
> (sqrt -1)
NaN      ; full Common Lisp would return #C(0.0 1.0)
```

## Other omissions

`symbol-macrolet` is not available, and `progv` is interpreter-only (a compile
error on the JVM/WASM backends); [`eval-when`](../reference/macros/eval-when.md)
**is** available, treated as `progn`.
This list is not exhaustive; rontolisp implements a focused core rather than the
full standard.
