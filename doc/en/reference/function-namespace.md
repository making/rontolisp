# Function Namespace

rontolisp is a **Lisp-2**, following Common Lisp: functions and variables live in
separate namespaces.

- A bare symbol evaluates as a **variable**. Evaluating `car` alone is an error
  (`The variable car is unbound` in the interpreter; a compile error in the compilers).
- A symbol in **call position** `(f args...)` resolves in the function namespace only.
  A variable named `car` never shadows the function `car`: `(let ((car 5)) (car (list car 2)))`
  returns `5`.
- A function becomes a **value** through `#'name` (reader syntax for `(function name)`),
  `#'(lambda ...)`, or `(symbol-function 'name)`. This works for built-in operators
  (`#'+`, `#'car`, `#'1+`, `#'cadr`), user `defun`s, and lambdas.
- `funcall`/`mapcar`/`reduce` also accept a **symbol** naming a function (a function
  designator): `(funcall 'car '(1 2))` returns `1`. The compilers support this when the
  symbol is a quoted literal.
- `defun` defines into the function namespace and returns the function name.
  `(setq f (lambda ...))` binds a **variable** to a function value; call it with
  `(funcall f ...)`, not `(f ...)`.
- `#'` of a macro or special operator (e.g. `#'if`, `#'defun`) is an error.

Function values can be passed as arguments, returned from functions, and stored in data
structures in all three execution modes.

**Higher-order functions:**

```lisp
(defun apply-twice (f x) (funcall f (funcall f x)))
(defun square (x) (* x x))
(print (apply-twice #'square 3))    ; => 81
```

**Closures (capture by reference):**

```lisp
(defun make-counter ()
  (let ((n 0))
    (lambda ()
      (setq n (+ n 1))
      n)))
(setq c (make-counter))
(funcall c) ; => 1
(funcall c) ; => 2
(funcall c) ; => 3
```

**Lambda as argument:**

```lisp
(defun apply-twice (f x) (funcall f (funcall f x)))
(print (apply-twice (lambda (x) (+ x 10)) 5))  ; => 25
```

**Built-in operators as first-class values:**

Built-in operators like `+`, `car`, `1+` can be passed to higher-order functions via `#'`:

```lisp
(print (reduce #'+ '(1 2 3 4 5) :initial-value 0))   ; => 15
(print (reduce #'* '(1 2 3 4 5) :initial-value 1))   ; => 120
(print (mapcar #'car '((1 2) (3 4) (5 6))))          ; => (1 3 5)
(print (mapcar #'1+ '(1 2 3)))                       ; => (2 3 4)
(print (funcall #'+ 3 4))                          ; => 7
(setq my-op #'+)
(print (funcall my-op 10 20))                      ; => 30
(print (funcall (symbol-function 'car) '(9 8)))    ; => 9
```

**Compiler restrictions.** In the JVM/WASM compilers, `#'name` resolves against the
functions known at compile time (user `defun`s and built-in operators); `#'mapcar`,
`#'reduce`, `#'apply` and `#'funcall` themselves are not available as values (`#'mapcan`
and `#'sort` are). `symbol-function` requires a quoted symbol literal argument. In
`--dynamic` mode an unresolved `#'name` is deferred to the runtime `eval` environment like
any other unresolved reference. In compiled code `apply`/`funcall` dispatch by the actual
argument count against a fixed-arity wrapper synthesized for each built-in operator. The
naturally variadic operators -- `+`, `-`, `*`, `/`, `list`, `min`, `max` -- have variadic
wrappers, so `(funcall #'+ 1 2 3)`, `(apply #'list ...)` and the like accept any argument
count. Every other multi-argument built-in keeps a fixed wrapper arity: `#'cons`,
`#'append`, `#'gcd` and the comparison chains (`#'<`, `#'=`, ...) are binary, so applying
them to a different count is unsupported on the compile path (matching the
[Compiled `eval` limitations](../guides/eval-limitations.md)); use a user-defined function
or a `lambda` for other arities. The interpreter has no such restriction.

## Redefining a COMMON-LISP function

`(defun random ...)`, `(defun length ...)` and the like are **undefined behavior** in
Common Lisp (CLHS 11.1.2.1.2), and rontolisp's backends genuinely differ:

- the **interpreter** resolves the call through the function cell, so your definition
  runs;
- the **JVM and WASM compilers** recognize the standard operator at the call site and
  compile it inline, so your definition does not run there. They print a compile-time
  warning naming the operator and the position of the first such call site, so the
  divergence is never silent.

`#'random` names your definition on every backend, which is why the warning says the
definition is still reachable that way. To have one definition everywhere, give it a
name of your own or `shadow` the symbol into a package of your own. A `defmethod` on a
built-in name is a different case and does work on every backend: it becomes the
generic's default method.
