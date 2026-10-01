# macroexpand-1

`(macroexpand-1 form)`

Expands a macro call once and answers the expansion as data, demangled and uppercased
for printing (mangled symbols read back uppercased, like every other spelling here).
A form whose head names no macro answers itself, demangled the same way. Also names
a function value.

```clojure
(defmacro doc-unless2 [c t] (list 'if c nil t))
(println (macroexpand-1 '(doc-unless2 true 1))) ; (IF true nil 1)
```
