# reify

`(reify Protocol (method [target & args] body...) ...)`

評価ごとに新しい値を答えます。使い切りのマップにメソッドを添えたものであり、
`proxy` ではありません（`proxy` は `java:` サーフェスのまま）。メソッドの行は
フォームに属し、オラクルの `reify` フォームごとに 1 つのクラスと同じく、各プロトコルの
表に一度だけ格納されます。各インスタンスは自分の評価が作ったメソッドを持ち、
それらはその評価のローカルを閉じ込めます。2つのインスタンスが `=` になることは
決してありません（オラクル同様）。それ以外の `=` は同一性です。メソッド群は `extend-type` 同様プロトコル名の下に
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
| `java.lang.CharSequence` | `count`（`length`）、`nth`、`seq` と seq を辿るすべての関数（`charAt`）、`seqable?`。`re-find`・`re-seq`・`re-matches`・`re-matcher`・`clojure.string`・文字列の `.contains` と `.replace` はその文字を並べた文字列を読みます |
| `Object` | `str` と印字（`toString`）、`=`（`equals`）、`.hashCode` と `IHashEq` がない場合の `hash`（`hashCode`） |

インタフェースへの `instance?` と、そのメソッドのインスタンス呼び出し（`(.count x)`）も型に
届きます。そのインタフェースへ拡張したプロトコルも同様です。本体が書かなかったメソッドを呼ぶと
オラクルの `AbstractMethodError` になり（`java.util` の default メソッドはインタフェース側の実装の
ままで、呼ぶと名前を挙げて拒否されます）、それ以外のインタフェース（`IChunkedSeq`、
`java.util.Deque` など）は名前を挙げて拒否されます。本体が実装した Java のインタフェース
（`Runnable`、`Comparable`、`Iterable`、`java.util` のコレクション）と上書きした `Object` の
メソッドは、Java から見た値にも及びます。値は Java のメンバへ、それらを実装したオブジェクト
として渡ります（[Java interop](interop.md)）。仕様との差異:`toString` を上書きした値は
`#object[user$reify "text"]` と印字され、オラクルのクラス番号と同一性ハッシュを持ちません。
正規表現の関数と `clojure.string` は `CharSequence` を呼び出しごとに `length` と `charAt` で
全体を一度読みます。オラクルのマッチャは照合に要るところまでしか読まず、`clojure.string` の
関数の多くは `toString` を呼びます。

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
| `IHashEq` | `hash`（`hasheq`）、要素にしたときのコレクションのハッシュ関数 |
| `java.io.Serializable`, `IEditableCollection`、トランジェント | `instance?` とインスタンス呼び出し。`transient` は `asTransient` を、`persistent!` と `!` 付きの操作はトランジェントのインターフェースのメソッドを呼びます |

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

## マップのキーと集合の要素

型自身がハッシュ（`IHashEq` の `hasheq`、`Object` の `hashCode`）と等価性
（`IPersistentCollection` の `equiv`、`Object` の `equals`、またはコアのコレクションが読む
マップ・集合・シーケンシャルのインタフェース）を定める値は、オラクルのハッシュマップと同じく、
マップのキーや集合の要素として `=` で照合されます。マップ・集合・シーケンシャルの型の値は、
それと `=` なコアのコレクションで検索でき、コアのコレクションを格納したマップや集合もその値で
検索できます（集合の要素にした `data.priority-map` など）。それ以外の型の値は、その型の等価性が
`=` と判定する値で検索できます。コアのキーと同じく、先に格納したキーが残ります。型自身が
ハッシュを定めない値は同一性で照合されます。オラクルのハッシュマップも、そうした値を同一性に
基づくハッシュで振り分けるためです。

仕様との差異:ここではどのマップもオラクルのハッシュマップと同じ方法でキーを照合します。
オラクルの配列マップ（8 エントリまで）はハッシュを使わずにキーを比べるので、型自身がハッシュを
定めない値も `=` な値で検索でき、`java.util.Collection` でも `Map` でもない検索キーは `equals`
だけで比べます。マップ・集合・シーケンシャルの型の値は `hasheq` ではなく中身で振り分けられる
ため、`hasheq` が `=` と食い違う型の値も検索できます。それ以外の型の値は型自身のハッシュで
振り分けられるため、その `equiv` が等しいと答えるコアのコレクションでは検索できません。

```clojure
(deftype Money [cents]
  Object
  (equals [_ o] (and (instance? Money o) (= cents (.-cents ^Money o))))
  (hashCode [_] cents))
(contains? #{(Money. 5)} (Money. 5)) ; => true
(get {(Money. 5) :five} (Money. 5)) ; => :five
(deftype Pair [a b]
  clojure.lang.Sequential
  clojure.lang.Seqable (seq [_] (list a b))
  clojure.lang.IHashEq (hasheq [_] (hash [a b])))
(contains? #{[1 2]} (Pair. 1 2)) ; => true
(get {(Pair. 1 2) :pair} '(1 2)) ; => :pair
```
