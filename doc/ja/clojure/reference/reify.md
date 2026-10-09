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
届きます。そのインタフェースへ拡張したプロトコルも同様です。本体が書かなかったメソッドを呼ぶと
オラクルの `AbstractMethodError` になり（`java.util` の default メソッドはインタフェース側の実装の
ままで、呼ぶと名前を挙げて拒否されます）、それ以外のインタフェース（`IChunkedSeq`、
`java.util.Deque` など）は名前を挙げて拒否されます。仕様との差異:`toString` を上書きした値は
`#object[user$reify "text"]` と印字され、オラクルのクラス番号と同一性ハッシュを持ちません。

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

## コレクションのインタフェース

本体はコレクションも実装できます。コア関数は、オラクルが問い合わせる順にそのメソッドを通して
読みます。インタフェースのスーパーインタフェースも実装に含まれます（`IPersistentMap` は
`Associative`、`Iterable`、`Counted` でもあります）。

| インタフェース | 読む関数 |
|---|---|
| `IPersistentCollection` | `conj`、`into`、`merge`（`cons`）、`empty`、両辺の `=`（`equiv`）、`Counted` でない場合の `count`（seq を最後まで辿る）、`coll?` |
| `Associative` | `assoc`、`update`、`assoc-in`（`assoc`）、`contains?`（`containsKey`）、`find`、`select-keys`（`entryAt`）、`associative?` |
| `IPersistentMap`, `MapEquivalence` | `dissoc`（`without`）、`keys`、`vals`、`reduce-kv`、`map?`、マップとしての印字。マップの `=` が `java.util.Map` として読むのは `MapEquivalence` を持つ場合だけです |
| `IPersistentSet` | `disj`（`disjoin`）、`contains?`、`get` とキーワードの呼び出し（`contains`、`get`）、`set?`、集合としての印字 |
| `IPersistentStack` | `peek`、`pop` |
| `IPersistentVector` | `vector?`、ベクタとしての印字、ベクタの `=`（`count`、`nth`）、`subvec` |
| `ISeq` | `seq` がこれを返すと `first` と `next` で辿ります。`seq?`、seq としての印字 |
| `Sequential`, `IPersistentList` | `sequential?`、`list?`。シーケンシャルな値の `=` と `nth` は seq を辿ります |
| `Reversible` | `rseq`、`reversible?` |
| `IPending` | `realized?` |
| `Sorted` | `subseq`、`rsubseq`（`seqFrom`、`seq`、`comparator`、`entryKey`）、`sorted?` |
| `java.lang.Comparable` | `compare`、`sort`、ソート済みコレクションの既定の順序 |
| `java.lang.Iterable` | `Seqable` でない場合の seq、`reduce`、`into`、`vec`（`iterator`）、`seqable?` |
| `java.util.Iterator` | [iterator-seq](iterator-seq.md)、`Iterable` の seq と畳み込み |
| `java.util.Collection`, `List`, `Set`, `RandomAccess` | `count`（`size`）、`nth`（`RandomAccess` なリストの `get`）、`contains?`（`Set` の `contains`）、`=`、`pr` でのリスト・ベクタ・集合としての印字 |
| `java.util.Map` | `get`、`contains?`、`find`、`count`、`seq`（`entrySet`）、`=`、`pr` でのマップとしての印字 |
| `IHashEq`, `java.io.Serializable`, `IEditableCollection`、トランジェント | `instance?` とインスタンス呼び出しのみ（`hash` はまだなく、トランジェントは拒否されます） |

コアのコレクションへの `(.iterator coll)`、`clojure.lang.SeqIterator`、`clojure.lang.RT/iter` は
seq を辿るイテレータを返し、`clojure.lang.MapEntry` はここでのマップエントリである `[k v]`
ベクタを作ります。仕様との差異:`ISeq` 型への `first`・`next`・`rest` はその `seq` を通して
読みます（オラクルは `first`・`next`・`more` を直接呼びます）。そのため `next` と `rest` は
その seq の残りを返します。チャンク化した seq がないため、関数がメソッドを呼ぶ回数はオラクルと
異なることがあります。こうした値の `str` はオラクルの `Class@hash` ではなく中身を綴り、
`java.util` の型は `print` でも中身を印字します。

```clojure
(deftype Pairs [m]
  clojure.lang.IPersistentMap
  (count [_] (count m))
  (seq [_] (seq m))
  (valAt [_ k] (get m k))
  (valAt [_ k nf] (get m k nf))
  (assoc [_ k v] (Pairs. (assoc m k v)))
  (without [_ k] (Pairs. (dissoc m k)))
  (iterator [_] (.iterator m)))
(def p (Pairs. {:a 1}))
(get (assoc p :b 2) :b) ; => 2
(map? p) ; => true
(reduce (fn [acc [k v]] (+ acc v)) 0 p) ; => 1
(pr-str (dissoc (assoc p :b 2) :a)) ; => "{:b 2}"
```
