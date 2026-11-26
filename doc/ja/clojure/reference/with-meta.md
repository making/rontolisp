# with-meta

`(with-meta obj metadata)`

マップ `metadata`（なしなら `nil`）を持つ `obj` のコピーを返します。[meta](meta.md) で
読み戻せます。元の値は自分のメタデータを保ち、`=` はメタデータを無視します。メタデータを
持てるのはマップ、ベクター、リスト、セット、レコード、`reify` 値、遅延シーケンス、関数、
シンボルです。文字列、数値、キーワードなどはオラクル同様シグナルを上げます。シンボルの
コピーは元のシンボルと `=` で、ハッシュも同じですが、`identical?` は区別します。シンボルを
取る関数（`name`、`symbol?`、`case`、マップの検索、表示など）はすべてシンボルとして読みます。
マップのキーやセットの要素になったシンボルはメタデータを持ちません（オラクルは持ちます）。

メタデータがディスパッチを変えるのは `:extend-via-metadata true` と宣言したプロトコルだけです
（[defprotocol](defprotocol.md) 参照）。`assoc` は元のメタデータを保ちます。コピーから導いた
それ以外の値（`conj`、`dissoc` など）はメタデータなしで始まります（オラクルは引き継ぎます）。

名前やローカルについたリーダーメタデータ（`^:private`、`^:dynamic`、`^{...}` attr マップ、
型ヒント）は解析して捨てられ、`^:dynamic` を読むのは `binding` だけです。ベクター・マップ・
セットのリテラルについたものはオラクルのリーダー同様に付きます。`^:k [1]` は `{:k true}` を
持ちます。クォートしたシンボルやコレクション、マクロの引数もリーダーメタデータを持ちます。

`*print-meta*` が真の間、`pr`、`prn`、`pr-str`、`str` は空でないメタデータを値の前に
書きます（`^{:k 1} [1 2]`、`:tag` だけなら `^String [1]`）。`print` と `println` は
書かず、シンボルの `str` も書きません。

```clojure
(def v (with-meta [1 2] {:tag :x}))
(println v (meta v))  ; [1 2] {:tag :x}
(println (meta [1 2])) ; nil
(def s (with-meta 'x {:k 1}))
(println s (meta s) (meta 'x) (= s 'x)) ; x {:k 1} nil true
```

```clojure
(binding [*print-meta* true] (prn (with-meta [1 2] {:k 1})))
```

```
^{:k 1} [1 2]
```
