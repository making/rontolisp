# macroexpand

`(macroexpand form)`

Expands to the fixpoint -- while the head names a macro -- and answers the result
as data, the mangled data itself like `macroexpand-1`. Also names a function value.

```clojure
(defmacro doc-mchain ([x f] (list '. x f)) ([x f & m] (concat (list 'doc-mchain (list '. x f)) m)))
(println (macroexpand '(doc-mchain a b c))) ; (. (. a b) c)
```
