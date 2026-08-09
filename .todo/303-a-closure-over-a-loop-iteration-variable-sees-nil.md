# A closure over a LOOP iteration variable sees NIL, not the last value

Difficulty: Medium

A lambda built inside a `loop` body captures the `for VAR in`/`for (VARS) in`/
`for (VARS) on` iteration variable as **NIL** once the loop has ended. Every
other CL implementation leaves the variable holding the value it had on the
final iteration, because the sequence cursor is stepped separately from the
variable and the variable is only assigned when the termination test has already
passed. Ours assigns first and tests afterwards, so the closure sees the
one assignment nobody was supposed to observe.

Measured (`probe3.lisp`, SBCL 2.6.5 vs the interpreter — the two compile paths
answer the same as the interpreter, so this is the shared expander):

| form | SBCL | rontolisp |
| --- | --- | --- |
| `(loop for x in '(1 2 3) collect (lambda () x))` | `(3 3 3)` | `(NIL NIL NIL)` |
| `(loop for (a b) in '((1 2) (3 4)) collect (lambda () (list a b)))` | `((3 4) (3 4))` | `((NIL NIL) (NIL NIL))` |
| `(loop for (a b) on '(1 2 3 4) by #'cddr collect (lambda () (list a b)))` | `((3 4) (3 4))` | `((NIL NIL) (NIL NIL))` |
| `(loop for c on '(1 2 3) collect (lambda () (car c)))` | `(NIL NIL NIL)` | `(NIL NIL NIL)` |
| `(loop for x in '(1 2 3) for y = (* x 10) collect (lambda () y))` | `(30 30 30)` | `(30 30 30)` |
| `(dolist (x '(1 2 3)) ... (lambda () x))` | `(1 2 3)` | `(1 2 3)` |

So the bug is exactly the clauses whose variable holds an ELEMENT: `for VAR in`
and both destructuring shapes. `for c on` legitimately ends at NIL (the cursor
IS the variable, and SBCL agrees), and `for y = expr` already keeps its last
value. Note the two right-hand columns disagree on the *value*, not on
freshness: CL's LOOP variables are one binding stepped in place, so SBCL's three
closures all answer `3`. Matching that is enough — do not "fix" this into a
fresh binding per iteration, which would make `dolist` and `loop` disagree in
the other direction.

## Why it matters beyond the table

This is what makes **every ningle route requirement silently stop matching**
(`.todo/300`). ningle compiles a route's requirements once, into closures:

```lisp
(loop for (name val) on requirements by #'cddr
      for fn = (gethash name map)
      if fn
        collect (lambda ()
                  (multiple-value-bind (satisfied res) (funcall fn val)
                    (and satisfied (list name res)))))
```

`fn` is captured correctly (it is a `for ... =` clause); `name` and `val` come
back NIL, so `(funcall fn nil)` answers nil, the requirement reports
unsatisfied, and the route never matches — a 404 with no error anywhere.
`:accept` content negotiation and every user-defined `ningle:requirement` are
affected. The failure mode is the dangerous one: no condition, no warning, just
a route that quietly is not there.

## Work

- Fix the iteration head so the element variable is assigned only after the
  termination test passes (or is left untouched at exhaustion) — in the ONE
  shared `LispMacroExpander` LOOP expansion, so the interpreter, the JVM and
  both WASM backends move together.
- Cover the destructuring form (`for (a b) in`, `for (a b) on ... by #'cddr`)
  as well as the plain one; that is the shape the ningle failure runs through.
- `for c on` must keep answering NIL — it is not a bug, it is the cursor.
- Pin it: a `ci-spec.yaml` case (all four backends) built from the table above,
  plus a `LispEvaluatorTest` unit for the destructuring shape.
- Check the other iteration clauses while the head is open (`across`, `being the
  hash-key/hash-value`, `for ... then`) against SBCL the same way and fix or
  record what diverges.

## Done when

The table above reads identically for SBCL and all four backends, and the
ningle requirement exercise in `.todo/300` matches without ningle being patched.
