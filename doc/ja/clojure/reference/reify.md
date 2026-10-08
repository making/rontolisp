# reify

`(reify Protocol (method [target & args] body...) ...)`

評価ごとに新しいディスパッチ値を答えます。各プロトコルの表に行を持ちます。
使い切りのマップにメソッドを添えたものであり、`proxy` ではありません（`proxy`
は `java:` サーフェスのまま）。各インスタンスは独自のタグでディスパッチする
ため、2つのインスタンスが `=` になることは決してありません（オラクル同様）。
それ以外の `=` は同一性です。メソッド群は `extend-type` 同様プロトコル名の下に
置きます。メソッド名を別のパラメータベクターで書き直すと、そのメソッドの別のアリティを
実装します。

```clojure
(defprotocol P (m [x]))
(def a (reify P (m [_] :a)))
(def b (reify P (m [_] :b)))
(println (m a))       ; :a
(println (m b))       ; :b
(println (= a a))     ; true
(println (= a b))     ; false
(println (satisfies? P a)) ; true
```

`clojure.core.protocols/CollReduce` の `reify` は、`reduce`・`into`・`transduce` がその
`coll-reduce` を通して畳み込むコレクションになります（[clojure.core.reducers](clojure-core-reducers.md)
のレデューサーはこの形で作られています）。

```clojure
(require '[clojure.core.protocols :as p])
(def three (reify p/CollReduce
             (coll-reduce [this f] (p/coll-reduce this f (f)))
             (coll-reduce [_ f init] (reduce f init [1 2 3]))))
(reduce + three) ; => 6
(into [] (map inc) three) ; => [2 3 4]
```

## ホストのインタフェース

本体は、コア関数が参照する `clojure.lang` のインタフェースも実装でき、`Object` の
`toString`・`equals`・`hashCode` も上書きできます。インタフェースはパッケージ付きで綴るか
import します（オラクル同様、`clojure.lang` は既定の import に含まれません）。メソッドは名前と
パラメータ数で本体のすべてのグループと照合されるため、`toString` をプロトコルの下に書くことも
できます。

| インタフェース | 読む関数 |
|---|---|
| `IReduceInit`, `IReduce` | `reduce`（初期値なしは `IReduce` を通す）、`into`、`transduce`、`run!`、`mapv`、`filterv`、`vec`、`set`、`group-by`、`frequencies` |
| `IKVReduce` | `reduce-kv`、`update-vals`、`update-keys` |
| `Seqable` | `seq` と seq を辿るすべての関数（`first`、`map`、`filter`、`doseq`、`for` など）、`seqable?` |
| `Counted` | `count`、`empty?`、`counted?` |
| `Indexed`（`Counted` を含む） | `nth`、ベクタの分配束縛、`indexed?` |
| `ILookup` | `get`、`get-in`、キーワードやシンボルの呼び出し、マップの分配束縛 |
| `IFn`（`Callable` と `Runnable` を含む） | 呼び出し、関数引数（`map`、`filter` など）、`apply`（`applyTo` を通す）、`ifn?` |
| `IDeref` | `deref`、`@` |
| `IMeta`, `IObj` | `meta`、`with-meta`、`vary-meta`（`deftype` のみ。`reify` は自身でメタデータを持ちます） |
| `Object` | `str` と印字（`toString`）、`=`（`equals`）、`.hashCode` |

インタフェースへの `instance?` と、そのメソッドのインスタンス呼び出し（`(.count x)`）も型に
届きます。本体が書かなかったメソッドを呼ぶとオラクルの `AbstractMethodError` になり、それ以外の
インタフェース（`ISeq`、`IPersistentMap`、`java.util.List` など）は名前を挙げて拒否されます。
仕様との差異:`toString` を上書きした値は `#object[user$reify "text"]` と印字され、オラクルの
クラス番号と同一性ハッシュを持ちません。

```clojure
(def three (reify clojure.lang.Counted (count [_] 3)
                  clojure.lang.ILookup
                  (valAt [_ k] (get {:a 1} k))
                  (valAt [_ k nf] (get {:a 1} k nf))))
(count three) ; => 3
(:a three) ; => 1
(get three :b :none) ; => :none
(str (reify Object (toString [_] "custom"))) ; => "custom"
```
