# do-external-symbols

`(do-external-symbols (var [package [result]]) body...)`

Evaluates the body once per external (exported) symbol of `package` -- the current package when omitted -- with `var` bound to the symbol, then evaluates `result` with `var` bound to nil and returns its value (nil when no result form is given). The symbols come in sorted order.
Over `cl` it visits the standard's 978 external names -- the `car`/`cdr` compositions `caar` to `cddddr` among them -- and `while`.

This works on every backend: the interpreter reads its live registry, and the
compiled backends answer from the package table baked in at compile time (plus
any package a [`make-package`](../functions/make-package.md) call creates at run
time). A `return` in the body exits the whole form -- the loop establishes the
implicit nil block every iteration macro has -- skipping the result form.

The example builds packages of its own rather than walking a built-in one, so the whole set it visits is visible on the page. The pair is built the same way as the one [`do-symbols`](do-symbols.md) walks -- a base package that exports two names and a second package that uses it and exports two of its own -- so the two pages differ by exactly one rule: what a package INHERITS is not what it exports, and `ASHARED` and `ZSHARED` are absent here.

```lisp
(defpackage :des-base (:export :ashared :zshared))
(defpackage :des-demo (:use :des-base) (:export :alpha :mine))
(let ((names nil))
  (do-external-symbols (s :des-demo) (push (symbol-name s) names))
  (nreverse names)) ; => ("ALPHA" "MINE")
```
