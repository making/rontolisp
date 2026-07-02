# Special Forms

**Each form name in the table links to its own page**, with a fuller description
and a runnable example you can evaluate in your browser.

| Form | Syntax | Description |
|------|--------|-------------|
| `quote` | `(quote expr)` or `'expr` | Returns the expression unevaluated |
| `if` | `(if cond then else?)` | Conditional. `nil` is false, everything else is true |
| `let` | `(let ((x 1) (y 2)) body...)` | Local variable bindings |
| `lambda` | `(lambda (params...) body...)` | Anonymous function |
| `progn` | `(progn expr1 expr2...)` | Evaluate expressions in sequence, return the last |
| `setq` | `(setq name value ...)` | Assign values to variables; accepts multiple `name value` pairs, assigned left to right, and returns the last value |
| `while` | `(while test body...)` | Evaluate body repeatedly while test is non-nil. Returns nil |
| `return` | `(return value?)` | Non-local exit from the nearest enclosing loop (`do`/`dolist`/`dotimes`/`loop`), which evaluates to `value` (or nil) |
| `defun` | `(defun name (params...) body...)` | Define a function in the function namespace. Returns the function name |
| `defmacro` | `(defmacro name (params...) body...)` | Define a user macro; a call is expanded (the body runs with unevaluated argument forms bound) and the expansion is evaluated. Supports `&rest`/`&body`. Returns the name |
| `defvar` | `(defvar name value?)` | Define a global variable, binding `value` only if `name` is not already bound (idempotent). With no `value`, leaves it unbound. Returns the name |
| `defparameter` | `(defparameter name value)` | Define a global variable, **always** (re)binding `value` even if `name` is already bound. Returns the name |
| `defconstant` | `(defconstant name value)` | Like `defparameter` (rontolisp does not enforce constancy). Returns the name |
| `function` | `(function name)` or `#'name` | Look up a function in the function namespace and return it as a value |

rontolisp is a **Lisp-2** like Common Lisp: functions and variables live in separate
namespaces. A bare symbol evaluates as a variable (`car` alone is an unbound-variable
error), a symbol in call position resolves in the function namespace only (a variable
named `car` does not shadow the function `car`), and a function is obtained as a value
with `#'name`, `(function name)` or `(symbol-function 'name)`. See
[Function Namespace and First-Class Functions](function-namespace.md).
