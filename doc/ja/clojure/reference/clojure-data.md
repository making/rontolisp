# clojure.data

2 つの値を再帰的に比較する名前空間です。`clojure.data` を require すると使えます。Clojure の同名の
名前空間について文書化された振る舞いをもとに rontolisp 向けに書いた Clojure ソースで、すべての
バックエンドで同じように動きます。

| var | 振る舞い |
|---|---|
| `diff` | `(diff a b)`: `[a だけにあるもの b だけにあるもの 両方にあるもの]`。空の部分は `nil`。等しい値は `[nil nil a]` |
| `equality-partition` | `(equality-partition x)`: `diff` が `x` をどう比較するか。`:map`、`:set`、`:sequential`、`:atom` のいずれか |
| `diff-similar` | `(diff-similar a b)`: 同じ区分の 2 つの値の差分 |
| `EqualityPartition`、`Diff` | 上の 2 つの関数のもとになるプロトコル。プログラムは自分の型に拡張できる |

2 つのマップはキーごとに、2 つのシーケンシャルなコレクション（ベクタ、リスト、シーケンス）は
インデックスごとに比較し、共通の位置の値はさらに再帰的に比較します。シーケンシャルな差分の各部分は、
相手側に値がある位置を `nil` で埋めたベクタになり、マップの差分は 3 つの部分をシーケンスとして返します。
2 つのセットは要素ごとに比較します。それ以外の値は文字列も含めて全体として比較し、区分の異なる 2 つの値も
同様です。レコードはマップとして比較します。

```clojure
(require '[clojure.data :as data])
(data/diff {:name "ann" :age 30} {:name "ann" :age 31})
; => ({:age 30} {:age 31} {:name "ann"})
(data/diff [1 2 3] [1 5 3 4])
; => [[nil 2] [nil 5 nil 4] [1 nil 3]]
(data/diff #{:a :b} #{:b :c})
; => [#{:a} #{:c} #{:b}]
(data/diff {:user {:name "ann" :roles [:admin]}} {:user {:name "ann" :roles [:dev]}})
; => ({:user {:roles [:admin]}} {:user {:roles [:dev]}} {:user {:name "ann"}})
(data/diff "abc" "abd")
; => ["abc" "abd" nil]
```
