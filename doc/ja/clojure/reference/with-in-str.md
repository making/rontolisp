# with-in-str

`(with-in-str s body...)`

`*in*` を文字列 `s` 上のリーダに束縛して本体を実行し、本体の値を返します。
[read-line](read-line.md)、[read](read.md)、`(.read *in*)` はその文字列を読みます。
`*in*` の `binding` と同じく、中では `#'*in*` がスレッド束縛されています。

```clojure
(println (with-in-str "one\ntwo" [(read-line) (read-line) (read-line)])) ; [one two nil]
(println (with-in-str "(+ 1 2) :k" [(read) (read)])) ; [(+ 1 2) :k]
```
