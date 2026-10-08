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
| `clojure.core.protocols/IKVReduce`、`kv-reduce` | `(kv-reduce amap f init)`: プログラムが自分の型に拡張できるプロトコルとしての `reduce-kv` |
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

## 違い

- `CollReduce` と `coll-reduce`（2 つのアリティを持つプロトコルメソッド）、`iterator-reduce!` は
  組み込まれておらず、名前を使うとそのことを告げるエラーになります。
- `reduce` と `reduce-kv` は `InternalReduce` や `IKVReduce` を参照しません。拡張を使うには
  `internal-reduce` や `kv-reduce` を直接呼びます。
- 例外は自分自身にデータ化されます（Clojure は `Throwable->map` のマップを返します）。名前空間や
  クラスも自分自身になります。
