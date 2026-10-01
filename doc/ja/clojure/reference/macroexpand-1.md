# macroexpand-1

`(macroexpand-1 form)`

マクロ呼び出しを 1 回だけ展開し、展開結果をデータとして返します。表示のために
demangle され大文字化されます（mangle されたシンボルは大文字で読み戻されます。
他の綴りと同様です）。先頭がマクロでないフォームはそのまま（同様に demangle されて）
返ります。関数値としても使えます。

```clojure
(defmacro doc-unless2 [c t] (list 'if c nil t))
(println (macroexpand-1 '(doc-unless2 true 1))) ; (IF true nil 1)
```
