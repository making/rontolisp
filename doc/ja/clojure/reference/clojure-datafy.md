# clojure.datafy

値をデータに変換し、データからそれが表すものへたどる名前空間で、`clojure.core.protocols` の
プロトコルの上に成り立っています。`clojure.datafy` を require すると使えます。`clojure.core.protocols`
は Clojure と同じくプログラムより先に読み込まれており、修飾名なら `require` なしで届きます。どちらも
Clojure の同名の名前空間について文書化された振る舞いをもとに rontolisp 向けに書いた Clojure ソースで、
すべてのバックエンドで同じように動きます。

| var | 振る舞い |
|---|---|
| `clojure.datafy/datafy` | `(datafy x)`: `Datafiable` を通した `x` のデータ表現。新しいコレクションが返ったときは、そのメタデータの `:clojure.datafy/obj` に `x` を、`:clojure.datafy/class` にそのクラスのシンボルを入れる。アトムは `[値]` を返す |
| `clojure.datafy/nav` | `(nav coll k v)`: `coll` の `k` の位置にある `v` が表すもの。`Navigable` を通し、拡張されていなければ `v` 自身 |
| `clojure.core.protocols/Datafiable`、`datafy` | `datafy` のもとになるプロトコル。`nil` もほかの値も自分自身を返す。メタデータで拡張できる |
| `clojure.core.protocols/Navigable`、`nav` | `nav` のもとになるプロトコル。メタデータで拡張できる |
| `clojure.core.protocols/CollReduce`、`coll-reduce` | `(coll-reduce coll f)` / `(coll-reduce coll f val)`: コレクション自身による `reduce`。これを拡張した record・deftype・`reify` は、`reduce`・`into`・`transduce` など `reduce` の上に作られた動詞でもこれを通して畳み込まれる（[reduce](reduce.md)） |
| `clojure.core.protocols/IKVReduce`、`kv-reduce` | `(kv-reduce amap f init)`: コレクション自身による `reduce-kv`。これを拡張した record・deftype・`reify` には `reduce-kv`・`update-vals`・`update-keys` が届く |
| `clojure.core.protocols/InternalReduce`、`internal-reduce` | `(internal-reduce s f start)`: プロトコルとしてのシーケンスの `reduce` |

メタデータで拡張できるプロトコルは、値のメタデータのうちメソッドの修飾シンボルのキーにあるメソッドを、
拡張より先に使います。

```clojure
(require '[clojure.datafy :as d] '[clojure.core.protocols :as p])
(def conn (with-meta {:id 7} {`p/datafy (fn [c] {:connection (:id c)})}))
(d/datafy conn) ; => {:connection 7}
(::d/obj (meta (d/datafy conn))) ; => {:id 7}
(d/datafy (atom 5)) ; => [5]
(defrecord Node [id])
(extend-protocol p/Navigable Node
  (nav [n k v] (if (= k :parent) (->Node v) v)))
(d/nav (->Node 1) :parent 0) ; => #user.Node{:id 0}
```

型は自前の `CollReduce` や `IKVReduce` の行を通して畳み込まれます。

```clojure
(require '[clojure.core.protocols :as p])
(defrecord Bag [items])
(extend-protocol p/CollReduce Bag
  (coll-reduce ([b f] (reduce f (:items b))) ([b f init] (reduce f init (:items b)))))
(reduce + 10 (->Bag [1 2 3])) ; => 16
(into [:x] (->Bag [1 2])) ; => [:x 1 2]
(deftype Pair [a b]
  p/IKVReduce
  (kv-reduce [_ f init] (f (f init :a a) :b b)))
(reduce-kv (fn [acc k v] (conj acc k v)) [] (Pair. 1 2)) ; => [:a 1 :b 2]
```

## 違い

- `iterator-reduce!` は組み込まれていません（`java.util.Iterator` を畳み込む関数です）。名前を使うと
  そのことを告げるエラーになります。
- `reduce` と `reduce-kv` が `CollReduce` と `IKVReduce` を参照するのは record・deftype・`reify` に
  対してだけです。どちらかのプロトコルを `nil`、`Object`、コアの種類（`String`、マップ）へ拡張した
  ものには、`coll-reduce` や `kv-reduce` を直接呼んで届きます。Clojure の `reduce` は、自分では
  畳み込まないコレクション（文字列、マップ）についてもその拡張を使います。`reduce` は
  `InternalReduce` を参照しません。
- 例外は自分自身にデータ化されます（Clojure は `Throwable->map` のマップを返します）。名前空間や
  クラスも自分自身になります。
