# macroexpand-1

`(macroexpand-1 form)`

Expands a macro call once and answers the expansion as data: the mangled data
itself, so `=` against a quoted form holds and printing spells the oracle's
lowercase. A form whose head names no macro answers itself. The head resolves
in `*ns*` when the call runs, like the oracle: a bare name through that namespace's
own macros and refers, an alias-qualified one through its aliases. Also names a
function value.

```clojure
(defmacro doc-unless2 [c t] (list 'if c nil t))
(println (macroexpand-1 '(doc-unless2 true 1))) ; (if true nil 1)
(println (= (macroexpand-1 '(doc-unless2 true 1)) '(if true nil 1))) ; true
```
