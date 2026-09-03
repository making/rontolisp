# do-external-symbols

`(do-external-symbols (var [package [result]]) body...)`

Evaluates the body once per external (exported) symbol of `package` -- the current package when omitted -- with `var` bound to the symbol, then evaluates `result` with `var` bound to nil and returns its value (nil when no result form is given). The symbols come in sorted order.

This is an **interpreter-only** operator: the compiled backends carry no package registry at run time, so a call reaching them is a compile error. Inside a `#.` read-time form it works everywhere, because the macro-time evaluator resolves it before compilation.

```lisp
(let ((names nil))
  (do-external-symbols (s :rontolisp names) (push (symbol-name s) names))
  (length names)) ; => 98
```
