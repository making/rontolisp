# macroexpand-1

`(macroexpand-1 form)`

マクロ呼び出しを 1 回だけ展開し、展開結果をデータとして返します。mangle された
データそのものなので、quote したフォームとの `=` が成り立ち、表示は oracle と同じ
小文字綴りになります。先頭がマクロでないフォームはそのまま返ります。先頭の名前は oracle と
同じく呼び出しが実行される時点の `*ns*` で解決します。修飾のない名前はその名前空間自身の
マクロと refer、別名で修飾した名前はその名前空間の別名を通ります。core マクロは、その名前を
名前空間が定義・refer・除外していない限り、修飾のない名前で解決します。関数値としても使えます。

core マクロは oracle と同じフォームに展開します。`let` は分配束縛を展開した束縛の上の
`let*` に、`fn` は `fn*` に、`case` はハッシュで引くマップの上の `case*` に、`doseq` と
`for` はチャンク単位の走査になり、[仕様との差異](../deviations.md) に挙げたマクロも同様です。
それ以外の core マクロ（`lazy-seq`、`defmacro` など）はフォームそのものを返します。マクロ
呼び出しはすべてマクロの関数を通ります。この関数はマクロの var が持ち、oracle と同じく
呼び出し・環境・呼び出しの引数をとります。`(#'when '(when a b) nil 'a 'b)` は
`(if a (do b))` を返します。

```clojure
(defmacro doc-unless2 [c t] (list 'if c nil t))
(println (macroexpand-1 '(doc-unless2 true 1))) ; (if true nil 1)
(println (= (macroexpand-1 '(doc-unless2 true 1)) '(if true nil 1))) ; true
(println (macroexpand-1 '(when a b))) ; (if a (do b))
(println (macroexpand-1 '(let [x 1] x))) ; (let* [x 1] x)
```
