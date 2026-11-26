# transient

`(transient coll)`

ベクター・マップ・セット `coll` のコピーの上のトランジェントです。`!` 付きの操作
（[`conj!`](conj-bang.md)、[`assoc!`](assoc-bang.md)、[`dissoc!`](dissoc-bang.md)、
[`disj!`](disj-bang.md)、[`pop!`](pop-bang.md)）はそのコピーをその場で編集するので、1回の
操作は1回の編集で済み、[`persistent!`](persistent-bang.md) がそれをコレクションとして返します。
`coll` は変わりません。トランジェントはコレクションではありません。`count`・`get`・`nth`・
`contains?`・`find`・呼び出し・キーワードは読めますが、`seq`（と seq ビューを通る全操作）は
拒否し、`=` と `hash` は同一性で、オラクルの `#object` から同一性ハッシュを除いた形で印字されます。
リスト・ソート済みコレクション・レコード・文字列はオラクルの `ClassCastException`、nil は
`NullPointerException` として拒否されます。`IEditableCollection` を実装する型はその
`asTransient` を返します。値としては1引数関数です。

仕様との差異: `!` 付きの操作はトランジェント自身を返します。オラクルは別のオブジェクトを返す
ことがあります（8エントリーを超えたアレイマップの `assoc!`）。返り値を使うプログラムは同じに
動きます。末尾を過ぎた `nth` はベクターと同じく `nil` を返します。

```clojure
(println (persistent! (conj! (transient [1 2]) 3))) ; [1 2 3]
(let [v [1] t (transient v)] (conj! t 2) (println v (count t))) ; [1] 2
(println (persistent! (reduce conj! (transient #{}) [1 1]))) ; #{1}
```
