# Special Forms

**Each form name in the table links to its own page**, with a fuller description
and a runnable example you can evaluate in your browser.

| Form | Syntax | Description |
|------|--------|-------------|
| `quote` | `(quote expr)` or `'expr` | Returns the expression unevaluated |
| `if` | `(if cond then else?)` | Conditional. `nil` is false, everything else is true |
| `let` | `(let ((x 1) (y 2)) body...)` | Local variable bindings (parallel). A name proclaimed special (`defvar`/`declaim`) is bound dynamically instead of lexically |
| `progv` | `(progv symbols values body...)` | Dynamically bind a runtime-computed list of `symbols` to `values` for the body, restored on exit (interpreter only) |
| `lambda` | `(lambda (params...) body...)` | Anonymous function |
| `progn` | `(progn expr1 expr2...)` | Evaluate expressions in sequence, return the last |
| `setq` | `(setq name value ...)` | Assign values to variables; accepts multiple `name value` pairs, assigned left to right, and returns the last value |
| `while` | `(while test body...)` | Evaluate body repeatedly while test is non-nil. Returns nil |
| `return` | `(return value?)` | Non-local exit from the nearest enclosing loop (`do`/`dolist`/`dotimes`/`loop`), which evaluates to `value` (or nil) |
| `unwind-protect` | `(unwind-protect protected cleanup...)` | Evaluate `protected` and run the `cleanup` forms on every exit from it -- normal return, `error` unwind, or `return`/`return-from` (on wasm-GC needs `wasmtime -W exceptions=y`; compile error under `--no-gc`) |
| `defun` | `(defun name (params...) body...)` | Define a function in the function namespace. Returns the function name |
| `defmacro` | `(defmacro name (params...) body...)` | Define a user macro; a call is expanded (the body runs with unevaluated argument forms bound) and the expansion is evaluated. Supports `&rest`/`&body`. Returns the name |
| `defclass` | `(defclass name (super?) ((slot options...)...))` | Define a class (static CLOS subset: single inheritance; `:initarg`/`:initform`/`:reader`/`:accessor` slot options). Returns the name |
| `defgeneric` | `(defgeneric name (param...))` | Define a generic function dispatching on its first argument. Returns the name |
| `defmethod` | `(defmethod name (param...) body...)` | Add a method to a generic function; the first parameter may carry an `(var (eql literal))`, class, or built-in-type specializer. Returns the name |
| `defvar` | `(defvar name value?)` | Define a global variable and proclaim it special, binding `value` only if `name` is not already bound (idempotent). With no `value`, leaves it unbound. Returns the name |
| `defparameter` | `(defparameter name value)` | Define a global variable and proclaim it special, **always** (re)binding `value` even if `name` is already bound. Returns the name |
| `defconstant` | `(defconstant name value)` | Like `defparameter` (rontolisp does not enforce constancy). Returns the name |
| `function` | `(function name)` or `#'name` | Look up a function in the function namespace and return it as a value |
| `defpackage` | `(defpackage name (:use ...) (:export ...))` | Define a new package (a top-level, read/compile-time directive; `:use` and `:export` clauses only). Returns the name |
| `rontolisp:async-defun` | `(rontolisp:async-defun name (params...) body...)` | Define an asynchronous function: calling it starts the body eagerly and returns a future that settles with the body's value (or error) |
| `rontolisp:async-lambda` | `(rontolisp:async-lambda (params...) body...)` | Anonymous asynchronous function; each invocation returns a future |
| `rontolisp:await` | `(rontolisp:await value)` | Suspend the current asynchronous function until a future settles and return its value; a non-future passes through unchanged. Legal only in `async-defun`/`async-lambda` bodies and at top level |

rontolisp is a **Lisp-2** like Common Lisp: functions and variables live in separate
namespaces. A bare symbol evaluates as a variable (`car` alone is an unbound-variable
error), a symbol in call position resolves in the function namespace only (a variable
named `car` does not shadow the function `car`), and a function is obtained as a value
with `#'name`, `(function name)` or `(symbol-function 'name)`. See
[Function Namespace and First-Class Functions](function-namespace.md).
