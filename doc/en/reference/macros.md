# Macros

**Each macro name in the table links to its own page**, with a fuller
description and a runnable example you can evaluate in your browser.

| Macro | Syntax | Description |
|-------|--------|-------------|
| `cond` | `(cond (test1 body1...) ...)` | Conditional with multiple clauses. Returns body of first truthy test |
| `case` | `(case key (k1 body1...) ((k2 k3) body2...) (otherwise body...))` | Dispatch on a key compared with `eql`. Keys are unevaluated; a list key matches any element; `t`/`otherwise` is the default. Returns nil if nothing matches |
| `ecase` | `(ecase key (k1 body1...) ((k2 k3) body2...))` | Exhaustive `case`: no default clause (`t`/`otherwise` are ordinary keys), and an unmatched key signals an `error` |
| `ccase` | `(ccase key (k1 body1...) ...)` | Like `ecase`; an unmatched key signals an `error`. Without a restart system this is identical to `ecase` (not correctable) |
| `and` | `(and expr1 expr2...)` | Short-circuit AND. Returns first nil or last value. `(and)` returns `t` |
| `or` | `(or expr1 expr2...)` | Short-circuit OR. Returns first non-nil value or nil. `(or)` returns `nil` |
| `when` | `(when condition body...)` | Evaluates body when condition is true, returns nil otherwise |
| `unless` | `(unless condition body...)` | Evaluates body when condition is nil, returns nil otherwise |
| `dotimes` | `(dotimes (var count result?) body...)` | Evaluate body with `var` bound to `0`..`count-1`. Returns `result` (or nil) |
| `do` | `(do ((var init step?)...) (end-test result...) body...)` | Iterate with parallel-stepped variables. Returns the `result` forms when `end-test` is true |
| `do*` | `(do* ((var init step?)...) (end-test result...) body...)` | Like `do` but bindings and steps are sequential (`let*`-style): each init/step form sees the variables already updated this iteration |
| `prog1` | `(prog1 first body...)` | Evaluate all forms in order, return the value of `first` |
| `prog2` | `(prog2 first second body...)` | Evaluate all forms in order, return the value of `second` |
| `time` | `(time form)` | Evaluate `form`, print the elapsed real time to standard output (`; Elapsed real time: N ms`), and return the form's value. `N` is an integer of milliseconds on the interpreter/JVM and a float of milliseconds on WASM |
| `psetq` | `(psetq v1 e1 v2 e2 ...)` | Parallel assignment: every right-hand side is evaluated before any variable is assigned. Returns nil |
| `typecase` | `(typecase x (integer body...) (string body...) (t default...))` | Dispatch on the type of `x`. Supported type names: `integer`, `float`, `number`, `rational`, `string`, `symbol`, `keyword`, `cons`, `list`, `null`, `atom` (plus `t`/`otherwise`). Returns nil if nothing matches |
| `etypecase` | `(etypecase x (integer body...) (string body...))` | Exhaustive `typecase`: no default clause, and an object whose type matches no clause signals an `error` |
| `error` | `(error "bad value: ~a" x)` | Signal an error, aborting execution. The first argument must be a literal control string (same directives as `format`: `~a`, `~s`, `~%`). The interpreter and JVM throw an exception carrying the message; WASM traps. Like `format`, it is a macro with no function value (`#'error` is unsupported) |
| `setf` | `(setf place value)` | Generalized assignment. Supports `car`, `cdr`, `nth`, `first`..`fourth`, `rest`, `caXXXr` as places |
| `push` | `(push item place)` | Prepend item to list at place. Returns the new list |
| `pop` | `(pop place)` | Remove and return the first element from list at place |
| `remf` | `(remf place indicator)` | Remove key-value pair from property list at place. Returns `t` if found, `nil` otherwise |
| `let*` | `(let* ((x 1) (y x)) body...)` | Sequential bindings: each init form sees the previous bindings. Expands to nested `let` |
| `dolist` | `(dolist (var list result?) body...)` | Evaluate body with `var` bound to each element. Returns `result` (or nil) with `var` bound to nil |
| `incf` | `(incf place delta?)` | Expands to `(setf place (+ place delta))`. `delta` defaults to 1. Returns the new value |
| `decf` | `(decf place delta?)` | Expands to `(setf place (- place delta))`. `delta` defaults to 1. Returns the new value |
| `format` | `(format t "Hello ~a, ~d!~%" 'world 42)`, `(format nil "~a" x)` | Formatted output to standard output (`t`, returns nil) or to a string (`nil`). See [format](format.md) |
| `with-open-file` | `(with-open-file (s "f.txt" :direction :output) (write-line "hi" s))` | Open a file, bind the stream to `s`, evaluate the body, close the file. Returns the body value. Only the `:direction` option (`:input` default, `:output`) is supported, and it must be a literal keyword |

Macros have no function value: `#'cond` or `(funcall 'setf ...)` is an error. Convenience
accessors and predicates that expand inline in call position (`first`, `rest`, `nth`,
`second`..`fourth`, `1+`, `1-`, `zerop`, `plusp`, `minusp`, `evenp`, `oddp`) are listed
under [Built-in Functions](builtin-functions.md) because they are also usable as function
values (`#'first`).
