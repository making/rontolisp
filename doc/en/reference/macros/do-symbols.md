# do-symbols

`(do-symbols (var [package [result]]) body...)`

Evaluates the body once per symbol ACCESSIBLE in `package` -- the current package
when omitted -- with `var` bound to the symbol, then evaluates `result` with
`var` bound to nil and returns its value (nil when no result form is given).
Accessible means the symbols the package owns, internal and external alike, plus
the exports it inherits through its use list; each is spelled against the package
that owns it, so a package using `cl` yields the bare `cl` names. The package's own
symbols come in sorted order -- sorted by the spelling that names each one's OWNER,
so an inherited name sorts under the package it comes from rather than among the
local ones by bare name.

This is an **interpreter-only** operator, like
[`do-external-symbols`](do-external-symbols.md): the compiled backends carry no
package registry at run time, so a call reaching them is a compile error. Inside a
`#.` read-time form it works everywhere, because the macro-time evaluator resolves
it before compilation.

The example builds packages of its own rather than walking a built-in one, so the
whole set it visits is visible on the page. `ASHARED` and `ZSHARED` are reached only
because `ds-demo` uses `ds-base`, which is exactly what
[`do-external-symbols`](do-external-symbols.md) leaves out -- and they come FIRST
because the sort is on `ds-base:ashared`, not on `ashared`.

```lisp
(defpackage :ds-base (:export :ashared :zshared))
(defpackage :ds-demo (:use :ds-base) (:export :alpha :mine))
(let ((names nil))
  (do-symbols (s :ds-demo) (push (symbol-name s) names))
  (nreverse names)) ; => ("ASHARED" "ZSHARED" "ALPHA" "MINE")
```
