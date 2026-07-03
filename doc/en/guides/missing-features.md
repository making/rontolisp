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
| `values` / `multiple-value-bind` | not available |
| `block` / `return-from` / `tagbody` / `go` | not available |
| `catch` / `throw` / `unwind-protect` | not available |
| conditions & restarts (`handler-case`, ...) | not available |
| `flet` / `labels` / `macrolet` | not available |
| `loop` (extended) | partial (simple-loop subset) |
| `defstruct` | available (see [`defstruct`](../reference/special-forms/defstruct.md)); options/`:include` are not |
| CLOS | not available |
| `declare` / `the` / `typep` | not available |
| `coerce` | partial (literal `'list` / `'vector` / `'string` result types; see [`coerce`](../reference/functions/coerce.md)) |
| `defpackage` (user packages) | partial (`:use`/`:export` only; see [`defpackage`](../reference/special-forms/defpackage.md)) |
| `make-package` / `export` / `use-package` (runtime) | not available |
| `require` / `provide` | available (see [`require`](../reference/functions/require.md)); the `*modules*` variable is not available |
| dynamic (special) binding via `let` | lexical only |
| complex numbers | not available |

## User-defined macros (`defmacro`)

User macros **are** supported — see [`defmacro`](../reference/special-forms/defmacro.md)
for the details, including the backquote template syntax and the limitations
(no `&optional`/`&key`, no nested backquote, unknown to the runtime `eval` of
compiled programs). The built-in macro set (`cond`, `case`, `when`, `unless`,
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

Structures **are** available with
[`defstruct`](../reference/special-forms/defstruct.md), which generates a
keyword constructor, a predicate, a copier and `setf`-able accessors. The
`defstruct` options syntax (`:conc-name`, `:constructor`, ...), `:include`
inheritance, and the `#S(...)` print/read syntax are not supported. There is no
object system (`defclass`, `defgeneric`, `defmethod`, `make-instance`).

## Type declarations, `typep`, and `coerce`

Type declarations are not parsed: `declare`, `declaim`, `proclaim`, and `the` are
not available, and neither is the runtime helper `typep`.
[`coerce`](../reference/functions/coerce.md) **is** available for the literal
result types `'list`, `'vector` and `'string` (the result type must be a quoted
literal, like `map`'s); other result types are not supported.

## User-defined packages

New packages **can** be defined with
[`defpackage`](../reference/special-forms/defpackage.md), as a literal,
top-level, read/compile-time directive supporting the `:use` and `:export`
clauses only (see
[Packages](../reference/packages.md#user-defined-packages-defpackage)). The
other `defpackage` clauses (`:nicknames`, `:shadow`, `:import-from`,
`:documentation`, ...) are errors, and there is no **runtime** package
manipulation: `make-package`, `export`, `import`, `use-package`,
`find-package`, and `rename-package` are not available. A package's set of
exported (external) symbols is fixed when it is defined; the single/double
colon qualifiers (`pkg:name` for external symbols, `pkg::name` for internal
ones) work as in Common Lisp (see
[Packages](../reference/packages.md#external-and-internal-symbols)). When
several used packages export the same name, the first package in `:use` order
wins instead of signaling a conflict.

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
