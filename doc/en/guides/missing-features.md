# Unsupported Common Lisp Features

rontolisp is a deliberately small subset of Common Lisp that runs identically on
three backends (interpreter, JVM, WASM). To keep the language compilable to plain
bytecode without a runtime metaobject protocol, many features of full Common Lisp
are intentionally left out.

This page lists **only what is missing or partial**. For what *is* available, see
the [Language Reference](../reference/special-forms.md), or list it at runtime
with `rontolisp:list-special-forms`, `rontolisp:list-macros`, and
`rontolisp:list-functions`.

| Feature | Status |
| --- | --- |
| restarts | available; no debugger integration (`break`, `*debugger-hook*`) and no condition-restart association |
| `symbol-macrolet` | not available (`macrolet` is) |
| `&environment` | accepted in a `defmacro` lambda list but always bound to `nil` (there is no macro-expansion environment object). `&whole` works, in `defmacro` and `destructuring-bind` alike |
| `loop` (extended) | partial (see below) |
| CLOS | partial (static subset + a definition-time MOP subset) |
| `defstruct` `:include` | single inheritance only; slot-overrides `(:include parent (slot default) ...)` work |
| `declare` / `declaim` / `proclaim` / `the` | never change a result; on WASM an array `type` declaration directs the element-accessor emission (smaller, faster modules), everywhere else parsed no-ops |
| `typep` / `subtypep` / `coerce` / `concatenate` | literal (quoted) type specifiers only; `coerce` targets `'list` / `'vector` / `'string` (or a float type), `concatenate` builds those same three sequence families |
| `make-package` / `export` / `import` / `rename-package` (runtime) | not available; `use-package` is a read/compile-time directive like `in-package`; `defpackage` `:shadow` / `:shadowing-import-from` are errors |
| `progv` | interpreter only (compile error on the JVM/WASM backends) |
| `eval-when` | treated as `progn` (no phase distinction) |
| `#:name` | reads as a plain symbol, without gensym-style freshness |
| `*modules*` | not available (`require`/`provide` are) |
| complex numbers | not available |
| `catch` / `throw` / `unwind-protect` / conditions under `--no-gc` | compile error (available on every other backend) |

## Multiple values

[`values`](../reference/functions/values.md) and its consumers are available,
including the values of user functions. The remaining deviations from Common
Lisp:

- a producer that calls `values` in a **non-tail** position and then returns
  normally may leave stale extra values behind, so keep `values` in result
  position (a consumer clears the values it received, so only a `values` call
  that nothing consumes can leave leftovers);
- `funcall #'values` (the first-class value) yields the primary value only in
  compiled programs;
- `multiple-value-call` with a built-in `#'name` keeps the wrapper's fixed
  arity — pass a user function or `lambda` for other argument counts;
- other built-ins with secondary values in CL (`read-from-string`,
  `macroexpand-1`, `subtypep`, ...) remain single-value —
  [`find-symbol`](../reference/functions/find-symbol.md) and
  [`intern`](../reference/functions/intern.md) do answer the accessibility
  status.

## Non-local exit

[`catch`](../reference/special-forms/catch.md) /
[`throw`](../reference/special-forms/throw.md),
[`block`](../reference/macros/block.md) /
[`return-from`](../reference/macros/return-from.md) and
[`tagbody`](../reference/special-forms/tagbody.md) /
[`go`](../reference/special-forms/go.md) are available, with two gaps on the
**compiled** backends (the interpreter is unaffected):

- a `return-from` that would cross an `flet`/`labels` local function is not yet
  supported (one crossing a `lambda` is, as a non-local exit);
- `go` must target a tag of a `tagbody` that lexically encloses it; the
  interpreter additionally supports dynamic `go` across function-call
  boundaries, i.e. a tag established by the *caller*. A tag reached from inside
  a nested `lambda` -- the shape a
  [`handler-bind`](../reference/macros/handler-bind.md) handler that resumes the
  protected loop with a `go` produces, and what quri's `:lenient`
  percent-decoding does -- is lowered like a cross-`lambda` `return-from`: a
  non-local exit that re-enters the `tagbody` at the tag and carries on.

A cross-`lambda` `return-from` or `go`, `catch`/`throw`, `unwind-protect`, and
condition catching all compile in exception-handling mode, so the emitted
wasm-GC modules need `wasmtime -W exceptions=y` (37+); under `--no-gc`
`catch`/`throw`, `unwind-protect` and the condition forms are a compile error.

## Restarts

The condition system is complete through the restart layer:
[`handler-bind`](../reference/macros/handler-bind.md) handlers run at the signal
point before unwinding, [`restart-case`](../reference/macros/restart-case.md) /
[`restart-bind`](../reference/macros/restart-bind.md) /
[`with-simple-restart`](../reference/macros/with-simple-restart.md) establish
restarts, and [`find-restart`](../reference/functions/find-restart.md) /
[`invoke-restart`](../reference/functions/invoke-restart.md) /
[`compute-restarts`](../reference/functions/compute-restarts.md) /
[`muffle-warning`](../reference/functions/muffle-warning.md) /
[`abort`](../reference/functions/abort.md) /
[`continue`](../reference/functions/continue.md) drive them;
[`cerror`](../reference/macros/cerror.md) is continuable. What is missing is the
**interactive debugger**: `break` and `*debugger-hook*` do not exist, a restart's
`:report` is stored but never rendered and its `:interactive` function never
runs, and restarts are not associated with conditions (the optional condition
argument of `find-restart`/`compute-restarts` is ignored).
[`check-type`](../reference/macros/check-type.md) /
[`assert`](../reference/macros/assert.md) /
[`ccase`](../reference/macros/ccase.md) still signal without offering a
`store-value` restart. Under `--no-gc` the restart forms degrade to the primary
form (that backend has no condition objects at all); on the wasm-GC backends
only **signaled** conditions are catchable — a runtime trap still aborts.

## The `loop` macro

A bounded subset of the extended [`loop`](../reference/macros/loop.md) is
available: numeric/list stepping (`for`), string stepping (`for ... across`),
the common accumulators (`collect`, `append`, `sum`, `count`, `maximize`,
`minimize`, ...), and simple control clauses (`while`/`until`, `repeat`,
`when`/`unless`, `finally`, `return`). Out of scope are destructuring, parallel
`and` between `for` clauses, `being`, the anaphoric `it`, `named`/`loop-finish`,
and `thereis`/`always`/`never`.

## Structures and objects

[`defstruct`](../reference/special-forms/defstruct.md) supports `:include`
inheritance in its single-inheritance form only. Slot-overrides work:
`(:include parent (slot new-default) ...)` re-defaults an inherited slot in the
child's layout while it keeps its inherited index, so the parent's accessors
still read it. An instance prints in the standard `#S(...)` syntax, and
a `#S(...)` literal reads back into an instance -- in source and through the
runtime `read` / `read-from-string` on every backend (a compiled program's
reader has frontend parity; only `#.`, `#+`/`#-` and `#n=`/`#n#` signal there).
A structure that carries a `(:print-object fn)` / `(:print-function fn)` option
prints through that function instead; both options are supported.

CLOS is a **static subset**
([`defclass`](../reference/special-forms/defclass.md),
[`defgeneric`](../reference/special-forms/defgeneric.md) /
[`defmethod`](../reference/special-forms/defmethod.md) dispatching on the first
argument, [`make-instance`](../reference/macros/make-instance.md) and
[`slot-value`](../reference/macros/slot-value.md) with literal quoted names).
A slot written with no `:initform` starts UNBOUND, as in CL:
[`slot-boundp`](../reference/macros/slot-boundp.md) reports it,
[`slot-makunbound`](../reference/macros/slot-makunbound.md) restores it, and a
read signals `unbound-slot`.
[`change-class`](../reference/macros/change-class.md) changes an instance's class
in place, both classes being literal. A **definition-time MOP subset** is in:
[`find-class`](../reference/functions/find-class.md) and
[`class-of`](../reference/functions/class-of.md) answer real `standard-class`
metaobjects, [`allocate-instance`](../reference/functions/allocate-instance.md)
works, and a `(:metaclass M)` class option runs the class-definition protocol at
definition time (see [`defclass`](../reference/special-forms/defclass.md)) — this
is what loads postmodern's DAO layer verbatim. Multiple inheritance works
(class precedence list, slot merge across superclasses). Out of scope: runtime
class construction
(`ensure-class` from computed data, a non-top-level `defclass`, `add-method`,
`compute-applicable-methods`, class redefinition,
`update-instance-for-different-class`) — the class and method sets of a compiled
program are fixed at compile time.

## User-defined packages

[`defpackage`](../reference/special-forms/defpackage.md) is a literal,
top-level, read/compile-time directive supporting `:use`, `:export`,
`:nicknames` and `:import-from` (`:documentation`/`:size` are accepted and
ignored). `:shadow` and `:shadowing-import-from` are errors (there is no symbol
shadowing). `use-package` exists as the same kind of read/compile-time
directive `in-package` is (a literal top-level call widens the current package's
use list), but there is no other **runtime** package manipulation:
`make-package`, `export`, `import` and `rename-package` are not available, so a
package's set of exported symbols is fixed when it is defined.
When several used packages export the same name, the first package in `:use`
order wins instead of signaling a conflict.

## Dynamic (special) variables

Dynamic binding through `let`/`let*` is supported, with two limitations on the
**compiled** backends (the interpreter is unaffected):
[`progv`](../reference/special-forms/progv.md) (runtime-computed lists of
symbols) is a compile error, and while normal exit and a
`return`/`return-from` that unwinds *across* a special `let` boundary both
restore the binding, an error caught by a handler outside the `let` (a `go`
across it, and on the WASM backends a `return` that also crosses an
`unwind-protect`/`handler-case`) does not.

## Numeric tower

rontolisp supports integers (including arbitrary-precision bignums), ratios
(`1/3`), and double floats, but **not complex numbers**. A negative square root
yields a float `NaN` rather than a complex result:

```console
> (sqrt -1)
NaN      ; full Common Lisp would return #C(0.0 1.0)
```

## Other omissions

- lambda lists: an extended `defmacro` lambda list (`&whole`, `&optional`,
  `&key`, `&aux`, nested destructuring patterns) routes through
  `destructuring-bind`, which is deliberately lenient -- a missing argument is
  `nil` and a surplus one is ignored rather than signalling; and a function is
  limited to 10 physical parameters on the funcall/apply path.
- user macros are unknown to the runtime `eval` of compiled programs, and a
  `lambda` built at runtime by that `eval` does not parse lambda-list keywords
  (see [Compiled eval Limitations](eval-limitations.md)).
- the PRETTY PRINTER produces the text a wide enough line holds, but never
  changes the LAYOUT: no rontolisp stream carries a column, so a logical block
  never wraps, every conditional line break (`pprint-newline` with `:linear` /
  `:fill` / `:miser`, the format directives `~_` / `~:_` / `~@_` / `~i`) is a
  no-op, and `*print-right-margin*` / `*print-miser-width*` / `*print-lines*` are
  accepted and ignored. Only `(pprint-newline :mandatory)` and `~:@_` break a
  line. Every other `*print-*` variable exists and holds the value the printer
  really behaves as -- binding one to a non-default value is what has no effect,
  except for `*print-escape*` / `*print-readably*` / `*print-pretty*`. The
  ordinary printing operators do not consult `*print-pprint-dispatch*`: an entry
  fires where the program calls the entry function itself.
- `#.` read-time eval is skipped with a warning inside `.asd` files.
- built-in macro names (`cond`, `case`, `when`, `setf`, `push`, ...) cannot be
  redefined; list them with `(rontolisp:list-macros)`.

This list is not exhaustive; rontolisp implements a focused core rather than the
full standard.
