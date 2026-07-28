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
| `loop` | `(loop for i from 1 to n collect (f i))` | A bounded subset of the ANSI `loop`: numeric/list stepping (`for`), accumulation (`collect`/`sum`/`count`/...), and simple control clauses (`while`/`repeat`/`when`/`finally`/`return`). See the page for the full grammar and limitations |
| `prog1` | `(prog1 first body...)` | Evaluate all forms in order, return the value of `first` |
| `multiple-value-prog1` | `(multiple-value-prog1 (floor 17 5) (cleanup))` | Like `prog1` but returns ALL values of the first form |
| `prog2` | `(prog2 first second body...)` | Evaluate all forms in order, return the value of `second` |
| `time` | `(time form)` | Evaluate `form`, print the elapsed real time to standard output (`; Elapsed real time: N ms`), and return the form's value. `N` is an integer of milliseconds on the interpreter/JVM and a float of milliseconds on WASM |
| `psetq` | `(psetq v1 e1 v2 e2 ...)` | Parallel assignment: every right-hand side is evaluated before any variable is assigned. Returns nil |
| `psetf` | `(psetf place1 e1 place2 e2 ...)` | `psetq` generalized to `setf` places: place subforms and values are all evaluated before any assignment. Returns nil |
| `block` | `(block name body...)` | Named block: returns the last form's value or the value of a matching `(return-from name v)`. Dynamic-extent matching on the interpreter; lexical (same-function) matching on the compilers |
| `typecase` | `(typecase x (integer body...) (string body...) (t default...))` | Dispatch on the type of `x`. Supported type names: `integer`, `float`, `number`, `rational`, `string`, `symbol`, `keyword`, `cons`, `list`, `null`, `atom`, `character`, `hash-table`, `boolean` (plus `t`/`otherwise`), and the compound specifiers `(or ...)`/`(and ...)`/`(not ...)`/`(member ...)`/`(eql ...)`/`(satisfies ...)` and ranged numeric types like `(integer 0 9)`. Returns nil if nothing matches |
| `etypecase` | `(etypecase x (integer body...) (string body...))` | Exhaustive `typecase`: no default clause, and an object whose type matches no clause signals an `error` |
| `error` | `(error "bad value: ~a" x)`, `(error 'my-error :v x)`, `(error obj)` | Signal an error, aborting execution unless a [`handler-case`](macros/handler-case.md) catches it. Designators: a literal control string (same directives as `format`), a quoted condition-type symbol with initargs (constructs a typed condition; the `define-condition` `:report` becomes the message), or a condition object. The interpreter and JVM throw an exception carrying the message and the condition; wasm-GC throws a WebAssembly exception when the program contains a catching form, and traps otherwise. Like `format`, it is a macro with no function value (`#'error` is unsupported) |
| `cerror` | `(cerror continue-format datum args...)` | Signal an error with the same condition-designator surface as `error`. Lite: there is no restart machinery, so the error is not continuable and the continue format control is accepted and dropped |
| `signal` | `(signal 'my-condition :v x)` | Signal a **non-fatal** condition (same designators as `error`): raised to an established `handler-case`, otherwise returns nil and continues (always nil under `--no-gc`) |
| `handler-case` | `(handler-case expr (type (var) body...)... (:no-error (v) body...))` | Evaluate `expr`, dispatching a signaled error to the first clause whose condition type matches (rethrown when none does); `:no-error` runs on normal completion. On wasm-GC needs `wasmtime -W exceptions=y`; compile error under `--no-gc` |
| `ignore-errors` | `(ignore-errors form...)` | The forms' value, or nil when an error is signaled; sugar over `handler-case`. On wasm-GC needs `wasmtime -W exceptions=y`; compile error under `--no-gc` |
| `setf` | `(setf place value)` | Generalized assignment. Supports `car`, `cdr`, `nth`, `first`..`fourth`, `rest`, `caXXXr` as places |
| `push` | `(push item place)` | Prepend item to list at place. Returns the new list |
| `pop` | `(pop place)` | Remove and return the first element from list at place |
| `remf` | `(remf place indicator)` | Remove key-value pair from property list at place. Returns `t` if found, `nil` otherwise |
| `let*` | `(let* ((x 1) (y x)) body...)` | Sequential bindings: each init form sees the previous bindings. Expands to nested `let` |
| `dolist` | `(dolist (var list result?) body...)` | Evaluate body with `var` bound to each element. Returns `result` (or nil) with `var` bound to nil |
| `incf` | `(incf place delta?)` | Expands to `(setf place (+ place delta))`. `delta` defaults to 1. Returns the new value |
| `decf` | `(decf place delta?)` | Expands to `(setf place (- place delta))`. `delta` defaults to 1. Returns the new value |
| `format` | `(format t "Hello ~a, ~d!~%" 'world 42)`, `(format nil "~a" x)` | Formatted output to standard output (`t`, returns nil) or to a string (`nil`) |
| `with-open-file` | `(with-open-file (s "f.txt" :direction :output) (write-line "hi" s))` | Open a file, bind the stream to `s`, evaluate the body, close the file. Returns the body value. Supports the `:direction` option (`:input` default, `:output`) and the `:element-type` option (`'character` default, `'(unsigned-byte 8)` for a binary stream); both must be literal |
| `with-open-stream` | `(with-open-stream (s (make-string-input-stream "hi")) (read-line s))` | Bind an ALREADY-OPEN stream, evaluate the body, close it. `with-open-file` without the `open` |
| `check-type` | `(check-type place typespec [string])` | Signal an error when the value of `place` is not of the given type; return nil when it is. Lite version: no restarts, so the place is never re-stored |
| `assert` | `(assert test-form [(place...) [datum args...]])` | Signal an error when `test-form` is false; return nil when it is true. The places list is accepted but ignored (no restarts) |
| `declare` | `(declare declaration...)` | Parsed no-op: evaluates to nil, arguments never evaluated or validated |
| `declaim` | `(declaim declaration...)` | Parsed no-op like `declare`, for file-level declarations |
| `proclaim` | `(proclaim declaration)` | Parsed no-op like `declaim` (deviates from CL: classified as a macro, the argument is not evaluated) |
| `the` | `(the type form)` | Returns the value of `form` unchanged; the type is not checked |
| `eval-when` | `(eval-when (situation...) body...)` | Evaluates the body as a `progn`; every situation is treated as "evaluate now". Top-level bodies are spliced so nested `defun`/`defmacro` definitions are collected |
| `locally` | `(locally declaration... form...)` | Evaluates the body as a `progn`; the leading `declare` forms are dropped (declarations are parsed no-ops) |
| `with-standard-io-syntax` | `(with-standard-io-syntax form...)` | Evaluates the body as a `progn`. Provided so portable code loads: every reader/printer control variable Common Lisp asks it to rebind is, in rontolisp, either resolved before run time (`*package*`), informational (`*read-default-float-format*`) or unread by any reader/printer |
| `write-char` | `(write-char char [stream])` | Write one character (returning it); expands to `write-string` of the one-character string, so file and string streams work |
| `flet` | `(flet ((name lambda-list body...)...) body...)` | Local, non-recursive function bindings (Lisp-2: call position and `#'name`). A definition body sees the outer function of the same name, not its siblings. Lambda lists support the `defun` extensions |
| `labels` | `(labels ((name lambda-list body...)...) body...)` | Like `flet` but the definitions see each other (recursion and mutual recursion) |
| `multiple-value-bind` | `(multiple-value-bind (var...) values-form body...)` | Binds the variables to the values of the producer form. A literal `(values ...)` call, the multi-value built-ins (`floor` family, `gethash`, `parse-integer`) and a user function returning `(values ...)` supply all of their values. Extra variables bind to nil |
| `multiple-value-list` | `(multiple-value-list values-form)` | Collects the producer's values into a list (recognized like `multiple-value-bind`) |
| `multiple-value-call` | `(multiple-value-call function values-form...)` | Calls the function with all values of every producer as the arguments, including a user function's values spread at runtime (deviates from CL: classified as a macro, not a special operator) |
| `nth-value` | `(nth-value n values-form)` | The n-th (0-based) value of the producer, or nil; expands to `nth` over `multiple-value-list` |
| `make-instance` | `(make-instance 'class-name :initarg value ...)` | Create an instance of a [`defclass`](special-forms/defclass.md) class (static CLOS subset). The class name must be a literal quoted symbol |
| `slot-value` | `(slot-value object 'slot-name)` | Read a slot of a [`defclass`](special-forms/defclass.md) instance; a `setf`-able place. The slot name must be a literal quoted symbol |
| `with-slots` | `(with-slots (x (v y)) instance body...)` | Bind slot names as symbol-macro-style places for the body: reads see the slots, and `setf`/`push`/`incf` of a bound name writes back to the slot |
| `rontolisp:with-arena` | `(rontolisp:with-arena () body...)` | Run the body and return its value, naming a memory-reclamation boundary for the non-GC WASM backend (`--no-gc`): everything allocated inside is popped at the end, keeping only the body's value. A plain `progn` on the other backends (a real GC already reclaims) |
| `rontolisp:with-mutex` | `(rontolisp:with-mutex (mutex-form) body...)` | Acquire the mutex, run the body, and release it on every exit (a signalled error included). Real mutual exclusion on the interpreter and the JVM backend, where a served handler runs one virtual thread per request; a no-op on the single-threaded WASM backends |
| `prog` | `(prog ((v init)...) tag-or-form...)` | `let` + `tagbody` inside a block: `go` jumps between the body's tags and `(return x)` exits with `x` |
| `prog*` | `(prog* ((v init)...) tag-or-form...)` | Like `prog` with sequential (`let*`-style) bindings |
| `shiftf` | `(shiftf a b 9)` | Shift place values left, store the last value into the last place, return the first place's old value |
| `load-time-value` | `(load-time-value form)` | Evaluates `form` once per occurrence in the source (lazily, on first use), not once per use |
| `define-compiler-macro` | `(define-compiler-macro name (params...) body...)` | Rewrite calls to `name` at compile time; returning the `&whole` form declines. A hint: it is ignored when the body signals, when `name` is a standard operator, or under `apply`/`funcall` |
| `typep` | `(typep x '(unsigned-byte 8))` | Type test over the `typecase` specifier set; the specifier must be a literal (quoted) type |
| `slot-boundp` | `(slot-boundp obj 'slot)` | `t` for every slot the instance's class defines (lite: slots are always initialized, no unbound state) |
| `slot-makunbound` | `(slot-makunbound obj 'slot)` | Lite: stores nil into the slot and returns the instance |
| `print-unreadable-object` | `(print-unreadable-object (obj stream :type t) body...)` | Writes `#<[class ]...>` around the body's output; returns nil (`:identity` accepted, not printed) |
| `with-package-iterator` | `(with-package-iterator (next pkgs :external) body...)` | Lite: binds the iterator name to a local FUNCTION always reporting no more symbols (no intern table) |
| `do-external-symbols` | `(do-external-symbols (s :rontolisp) (print s))` | Iterate a package's exported symbols (interpreter only: the compiled backends carry no package registry) |

Macros have no function value: `#'cond` or `(funcall 'setf ...)` is an error. Convenience
accessors and predicates that expand inline in call position (`first`, `rest`, `nth`,
`second`..`fourth`, `1+`, `1-`, `zerop`, `plusp`, `minusp`, `evenp`, `oddp`) are listed
under [Functions](functions.md) because they are also usable as function
values (`#'first`).
