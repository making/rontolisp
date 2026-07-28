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
| restarts (`handler-bind`, `restart-case`, `invoke-restart`, `cerror`, ...) | not available |
| `symbol-macrolet` | not available (`macrolet` is) |
| `&whole` / `&environment` | not available; a `defmacro` lambda list takes required parameters plus one trailing `&rest`/`&body` |
| `loop` (extended) | partial (see below) |
| CLOS | partial (static subset; no MOP) |
| `defstruct` `:include` | single inheritance only; a slot-override `(:include parent (slot default))` is not available |
| `declare` / `declaim` / `proclaim` / `the` | parsed no-ops (no effect on compilation) |
| `typep` / `subtypep` / `coerce` / `concatenate` | literal (quoted) type specifiers only; `coerce` targets `'list` / `'vector` / `'string` (or a float type), `concatenate` builds those same three sequence families |
| `make-package` / `export` / `import` / `use-package` / `find-package` / `rename-package` (runtime) | not available; `defpackage` `:shadow` / `:shadowing-import-from` are errors |
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
  position;
- `funcall #'values` (the first-class value) yields the primary value only in
  compiled programs;
- `multiple-value-call` with a built-in `#'name` keeps the wrapper's fixed
  arity — pass a user function or `lambda` for other argument counts;
- other built-ins with secondary values in CL (`read-from-string`,
  `macroexpand-1`, `intern`, ...) remain single-value.

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
- `go` must target a tag of a lexically enclosing `tagbody` in the same
  function; the interpreter additionally supports dynamic `go` across function
  boundaries.

A cross-`lambda` `return-from`, `catch`/`throw`, `unwind-protect`, and condition
catching all compile in exception-handling mode, so the emitted wasm-GC modules
need `wasmtime -W exceptions=y` (37+); under `--no-gc` `catch`/`throw`,
`unwind-protect` and the condition forms are a compile error.

## Restarts

The condition-system core (`define-condition`, `handler-case`,
`ignore-errors`, `signal`, typed `error`) is available, but the **restart
system** is not: `handler-bind`, `restart-case` (accepted as a no-op that keeps
only the primary form), `restart-bind`, `invoke-restart`,
`with-simple-restart`, `cerror`, `abort`, `continue` and `break` are absent, and
[`check-type`](../reference/macros/check-type.md) /
[`assert`](../reference/macros/assert.md) signal without offering a re-store
restart. On the wasm-GC backends only **signaled** conditions are catchable — a
runtime trap still aborts.

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
inheritance in its single-inheritance form only -- a slot-override
`(:include parent (slot new-default))` is not available. An instance prints in the standard `#S(...)` syntax, and
a `#S(...)` literal reads back into an instance -- in source and through the
runtime `read` / `read-from-string` on every backend (a compiled program's
reader has frontend parity; only `#.`, `#+`/`#-` and `#n=`/`#n#` signal there).

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
in place, both classes being literal. Out of scope: multiple inheritance,
specializers on later arguments, and the MOP / runtime class operations
(`find-class`, `add-method`, `compute-applicable-methods`, class redefinition,
`update-instance-for-different-class`) — the class and method sets of a compiled
program are fixed at compile time.

## User-defined packages

[`defpackage`](../reference/special-forms/defpackage.md) is a literal,
top-level, read/compile-time directive supporting `:use`, `:export`,
`:nicknames` and `:import-from` (`:documentation`/`:size` are accepted and
ignored). `:shadow` and `:shadowing-import-from` are errors (there is no symbol
shadowing), and there is no **runtime** package manipulation: `make-package`,
`export`, `import`, `use-package`, `find-package`, and `rename-package` are not
available, so a package's set of exported symbols is fixed when it is defined.
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

- lambda lists: `&whole` is unavailable, a `defmacro` lambda list accepts only
  required parameters plus one trailing `&rest`/`&body`, and a function is
  limited to 7 physical parameters on the funcall/apply path.
- user macros are unknown to the runtime `eval` of compiled programs, and a
  `lambda` built at runtime by that `eval` does not parse lambda-list keywords
  (see [Compiled eval Limitations](eval-limitations.md)).
- `#.` read-time eval is skipped with a warning inside `.asd` files.
- built-in macro names (`cond`, `case`, `when`, `setf`, `push`, ...) cannot be
  redefined; list them with `(rontolisp:list-macros)`.

This list is not exhaustive; rontolisp implements a focused core rather than the
full standard.
