# clojure.core.reducers

`clojure.core.protocols` の `CollReduce` プロトコルの上に作ったレデューサーとフォルダーです。
レデューサーはコレクションの畳み込み可能なビューで、畳み込みが渡す畳み込み関数を変換します。
そのため `(into [] (r/map inc (r/filter odd? v)))` は途中のコレクションを作りません。フォルダーは
さらに fold でき、`fold` はベクターを部分に分けて畳み込み、部分の結果を結合します。
`clojure.core.reducers` を require すると使えます。Clojure の同名の名前空間について文書化された
振る舞いをもとに rontolisp 向けに書いた Clojure ソースで、すべてのバックエンドで同じように動きます。

| var | 振る舞い |
|---|---|
| `map`、`filter`、`remove`、`mapcat`、`flatten` | `(map f coll)`: `coll` の各要素 `x` について `(f x)` を渡すフォルダー（マップのエントリは `(f k v)`）。`filter`・`remove` は `(pred x)` で残すか落とし、`mapcat` は各 `(f x)` の要素を、`flatten` は順次コレクションの入れ子の要素を渡す。`coll` を省くと、それを待つ関数を返す |
| `take`、`drop`、`take-while` | `(take n coll)`: `n` 要素の後で終わる、先頭 `n` 要素を除く、または `pred` が退ける最初の要素で終わる `coll` のレデューサー。`coll` を省くと、それを待つ関数を返す |
| `reduce` | `(reduce f coll)` / `(reduce f init coll)`: `clojure.core/reduce` と同じだが、初期値を省くと `(f)` が初期値になり、マップとレコードは `kv-reduce` を通して `(f acc k v)` で畳み込む |
| `fold` | `(fold reducef coll)` / `(fold combinef reducef coll)` / `(fold n combinef reducef coll)`: ベクターを `n` 要素（既定は512）以下になるまで半分に分け、各部分を `(combinef)` から畳み込み、部分の結果を `combinef`（省くと `reducef`）で順に結合する。ほかのコレクションは `(combinef)` から一度だけ畳み込み、マップとレコードは `kv-reduce` を通す。`combinef` は結合的で、`(combinef)` がその単位元でなければならない |
| `CollFold`、`coll-fold` | `fold` がディスパッチするプロトコル。`nil`、ベクター、`Object` へ拡張済みで、フォルダーは元のコレクションの上でこれを実装する |
| `reducer`、`folder` | `(reducer coll xf)`: 畳み込みのたびに畳み込み関数を `xf` に通す、畳み込み可能なコレクションとしての `coll`。`folder` は fold もできる |
| `cat` | `(cat)`: 新しいアキュムレーター。`(cat left right)`: 2つのコレクションを順に持つもの（片方が空ならもう片方）。`(cat ctor)`: 単位元が `(ctor)` の結合関数 |
| `append!` | `(append! acc x)`: `x` をアキュムレーター `acc` に追加し、`acc` を返す |
| `foldcat` | `(foldcat coll)`: `(fold cat append! coll)`。`coll` の畳み込みが渡す要素を1つのアキュムレーターに集める |
| `monoid` | `(monoid op ctor)`: 単位元が `(ctor)` である `op` の結合関数 |

アキュムレーターは、要素数、seq、表示、畳み込みのどれもベクターとして振る舞います。
レデューサーは seq ではなく、Clojure と同じく `seq`・`count`・`first` は拒否されます。

```clojure
(require '[clojure.core.reducers :as r])
(into [] (r/map inc (r/filter odd? [1 2 3 4 5])))
; => [2 4 6]
(r/fold + (r/map inc (vec (range 1000))))
; => 500500
(r/fold 2 (fn ([] []) ([a b] (conj a b))) conj [1 2 3 4 5])
; => [1 2 [3 [4 5]]]
(r/reduce (fn [acc k v] (+ acc v)) 0 {:a 1 :b 2})
; => 3
(into [] (r/take 2 (r/mapcat (fn [x] [x x]) [1 2 3])))
; => [1 1]
(r/foldcat (r/remove even? [1 2 3]))
; => [1 3]
```

## 違い

- fork/join プールはありません。`fold` は部分を順に1つずつ畳み込みます。`pool` と `fjtask` は
  組み込まれておらず、名前を使うとそのことを告げるエラーになります。
- 空でない2つのコレクションの `cat` は両方を持つ1つのアキュムレーターを返します。Clojure は
  2つを葉に持つ木である `Cat` を返し、その fold は半分ずつを fold して結合します。アキュムレーターの
  fold は、Clojure の `ArrayList` と同じく全体を一度に畳み込みます。`->Cat` は組み込まれていません。
  アキュムレーターはここではベクターです（`vector?` は true。Clojure の `ArrayList` は Clojure の
  コレクションではありません）。
