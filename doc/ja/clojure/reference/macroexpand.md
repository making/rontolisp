# macroexpand

`(macroexpand form)`

先頭がマクロである限り展開を繰り返し（fixpoint）、結果を `macroexpand-1` と同様に
demangle されたデータとして返します。関数値としても使えます。

```clojure
(defmacro doc-mchain ([x f] (list '. x f)) ([x f & m] (concat (list 'doc-mchain (list '. x f)) m)))
(println (macroexpand '(doc-mchain a b c))) ; (. (. A B) C)
```
