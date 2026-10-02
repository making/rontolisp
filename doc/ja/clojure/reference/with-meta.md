# with-meta

`(with-meta obj metadata)`

マップ `metadata`（なしなら `nil`）を持つ `obj` のコピーを返します。[meta](meta.md) で
読み戻せます。元の値は自分のメタデータを保ち、`=` はメタデータを無視します。メタデータを
持てるのはマップ、ベクター、リスト、セット、レコード、`reify` 値、遅延シーケンス、関数です。
文字列、数値、キーワードなどはオラクル同様シグナルを上げます。シンボルはメタデータを持たず、
そのまま返ります。

メタデータがディスパッチを変えるのは `:extend-via-metadata true` と宣言したプロトコルだけです
（[defprotocol](defprotocol.md) 参照）。コピーから導いた値（`assoc`、`conj` など）は
メタデータなしで始まります（オラクルは引き継ぎます）。

名前やローカルについたリーダーメタデータ（`^:private`、`^:dynamic`、`^{...}` attr マップ、
型ヒント）は解析して捨てられ、`^:dynamic` を読むのは `binding` だけです。ベクター・マップ・
セットのリテラルについたものはオラクルのリーダー同様に付きます。`^:k [1]` は `{:k true}` を
持ちます。

```clojure
(def v (with-meta [1 2] {:tag :x}))
(println v (meta v))  ; [1 2] {:tag :x}
(println (meta [1 2])) ; nil
```
