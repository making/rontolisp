# macroexpand-1

`(macroexpand-1 form)`

マクロ呼び出しを 1 回だけ展開し、展開結果をデータとして返します。mangle された
データそのものなので、quote したフォームとの `=` が成り立ち、表示は oracle と同じ
小文字綴りになります。先頭がマクロでないフォームはそのまま返ります。先頭の名前は oracle と
同じく呼び出しが実行される時点の `*ns*` で解決します。修飾のない名前はその名前空間自身の
マクロと refer、別名で修飾した名前はその名前空間の別名を通ります。関数値としても使えます。

```clojure
(defmacro doc-unless2 [c t] (list 'if c nil t))
(println (macroexpand-1 '(doc-unless2 true 1))) ; (if true nil 1)
(println (= (macroexpand-1 '(doc-unless2 true 1)) '(if true nil 1))) ; true
```
