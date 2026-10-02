# macroexpand-1

`(macroexpand-1 form)`

マクロ呼び出しを 1 回だけ展開し、展開結果をデータとして返します。mangle された
データそのものなので、quote したフォームとの `=` が成り立ち、表示は oracle と同じ
小文字綴りになります。先頭がマクロでないフォームはそのまま返ります。関数値としても使えます。

```clojure
(defmacro doc-unless2 [c t] (list 'if c nil t))
(println (macroexpand-1 '(doc-unless2 true 1))) ; (if true nil 1)
(println (= (macroexpand-1 '(doc-unless2 true 1)) '(if true nil 1))) ; true
```
