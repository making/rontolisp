# do-external-symbols

`(do-external-symbols (var [package [result]]) body...)`

Evaluates the body once per external (exported) symbol of `package` -- the current package when omitted -- with `var` bound to the symbol, then evaluates `result` with `var` bound to nil and returns its value (nil when no result form is given). The symbols come in sorted order.

This is an **interpreter-only** operator: the compiled backends carry no package registry at run time, so a call reaching them is a compile error. Inside a `#.` read-time form it works everywhere, because the macro-time evaluator resolves it before compilation.

The example builds packages of its own rather than walking a built-in one, so the whole set it visits is visible on the page. The pair is built the same way as the one [`do-symbols`](do-symbols.md) walks -- a base package that exports two names and a second package that uses it and exports two of its own -- so the two pages differ by exactly one rule: what a package INHERITS is not what it exports, and `ASHARED` and `ZSHARED` are absent here.

```lisp
(defpackage :des-base (:export :ashared :zshared))
(defpackage :des-demo (:use :des-base) (:export :alpha :mine))
(let ((names nil))
  (do-external-symbols (s :des-demo) (push (symbol-name s) names))
  (nreverse names)) ; => ("ALPHA" "MINE")
```
