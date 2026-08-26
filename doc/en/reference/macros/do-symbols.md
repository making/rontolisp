# do-symbols

`(do-symbols (var [package [result]]) body...)`

Evaluates the body once per symbol ACCESSIBLE in `package` -- the current package
when omitted -- with `var` bound to the symbol, then evaluates `result` with
`var` bound to nil and returns its value (nil when no result form is given).
Accessible means the symbols the package owns, internal and external alike, plus
the exports it inherits through its use list; each is spelled against the package
that owns it, so a package using `cl` yields the bare `cl` names. The symbols come
in sorted order.

This is an **interpreter-only** operator, like
[`do-external-symbols`](do-external-symbols.md): the compiled backends carry no
package registry at run time, so a call reaching them is a compile error. Inside a
`#.` read-time form it works everywhere, because the macro-time evaluator resolves
it before compilation.

```lisp
(let ((n 0))
  (do-symbols (s :rontolisp n) (setq n (1+ n)))) ; => 100
```
