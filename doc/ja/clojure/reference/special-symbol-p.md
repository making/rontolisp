# special-symbol?

`(special-symbol? x)`

`clojure.core/special-symbol?`: オラクルの特殊形式（`if`・`def`・`let*`・`fn*`・`quote`・`var`・`recur`・`try` など）を名指すシンボルなら `true` を返します。それらの上のマクロ（`let`・`fn`・`loop`）は `false` です。値としては1引数の関数です。

```clojure
(println (special-symbol? 'if) (special-symbol? 'let))  ; true false
```
