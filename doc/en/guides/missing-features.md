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
| `defmacro` (user macros) | not available |
| `&optional` / `&rest` / `&key` / `&aux` | not available |
| `values` / `multiple-value-bind` | not available |
| `block` / `return-from` / `tagbody` / `go` | not available |
| `catch` / `throw` / `unwind-protect` | not available |
| conditions & restarts (`handler-case`, ...) | not available |
| `flet` / `labels` / `macrolet` | not available |
| `loop` (extended) | partial (simple-loop subset) |
| `defstruct`, CLOS | not available |
| `declare` / `the` / `typep` / `coerce` | not available |
| `defpackage` / `export` / user packages | not available |
| dynamic (special) binding via `let` | lexical only |
| complex numbers | not available |

## User-defined macros (`defmacro`)

Macros cannot be defined in rontolisp. The macro set is fixed and built into the
compiler (`cond`, `case`, `when`, `unless`, `dotimes`, `dolist`, `do`, `setf`,
`push`, `pop`, `incf`, ...). `defmacro` itself is not a defined operator:

```console
> (defmacro square (x) (list '* x x))
The function defmacro is undefined
```

Run `(rontolisp:list-macros)` to see the macros you do have.

## Lambda list keywords (`&optional`, `&rest`, `&key`, `&aux`)

A function takes a **fixed number of positional parameters**. There are no
optional, rest, or keyword parameters.

This is an easy trap: a lambda-list keyword like `&rest` is not rejected — it is
silently treated as an ordinary parameter **named** `&rest`, so
`(defun f (a &rest r) ...)` defines a three-parameter function (`a`, `&rest`, `r`)
rather than a variadic one.

## Multiple values (`values`, `multiple-value-bind`)

There are no multiple return values. A function returns exactly one value.
Consequently `floor`, `truncate`, `round`, and `ceiling` take a **single argument**
and return only the integer — there is no divisor argument and no second
(remainder) value:

```console
> (floor 7 2)
floor expects 1 arguments, got 2
```

## Non-local exit and control flow

Named blocks and arbitrary jumps are not available:

- `block` / `return-from` — there are no named blocks. The only non-local exit is
  `return`, which exits the **nearest** enclosing iteration block established by
  `do` / `do*` / `dolist` / `dotimes`.
- `tagbody` / `go` — no label-and-jump control flow.
- `catch` / `throw` — no dynamically scoped exits.
- `unwind-protect` — there is no cleanup-on-exit guarantee.

```console
> (block done (return-from done 1) 2)
The function block is undefined
```

## Conditions and restarts

There is no condition system. `error` signals and aborts the program, but the
signal **cannot be caught from within the language**: `handler-case`,
`handler-bind`, `ignore-errors`, `restart-case`, `define-condition`, `signal`, and
`warn` are all absent.

```console
> (ignore-errors (error "boom"))
The function ignore-errors is undefined
```

## Local functions (`flet`, `labels`, `macrolet`)

Functions cannot be defined locally. They exist only at top level via `defun` (or
as a `lambda` value bound to a variable).

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

There are no structures (`defstruct`) and no object system (`defclass`,
`defgeneric`, `defmethod`, `make-instance`).

## Type declarations, `typep`, and `coerce`

Type declarations are not parsed: `declare`, `declaim`, `proclaim`, and `the` are
not available, and neither are the runtime helpers `typep` and `coerce`.

## User-defined packages

rontolisp has exactly three built-in packages — `cl`, `cl-user`, and `rontolisp`
(see [Packages](../reference/packages.md)). You cannot create new ones:
`defpackage`, `make-package`, `export`, `import`, and `use-package` are not
available. `in-package` only switches the current package among the three
built-ins.

## Dynamic (special) variable binding

`defvar` and `defparameter` create global variables, but rontolisp binding is
**lexical only** — `let` does not establish a dynamic binding for a special
variable. Rebinding a global in a `let` is not seen by a separately defined
function called within that scope:

```console
> (defvar *factor* 1)
> (defun scale (n) (* n *factor*))
> (let ((*factor* 10)) (scale 5))
5        ; full Common Lisp would return 50
```

## Numeric tower

rontolisp supports integers (including arbitrary-precision bignums), ratios
(`1/3`), and double floats, but **not complex numbers**. A negative square root
yields a float `NaN` rather than a complex result:

```console
> (sqrt -1)
NaN      ; full Common Lisp would return #C(0.0 1.0)
```

## Other omissions

`destructuring-bind`, `eval-when`, `symbol-macrolet`, and `progv` are also not
available. This list is not exhaustive; rontolisp implements a focused core rather
than the full standard.
