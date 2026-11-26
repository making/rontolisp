# clojure.zip

ジッパーによる関数的な木の編集を提供する名前空間です。`clojure.zip` を require すると使えます。
Clojure の同名の名前空間について文書化された振る舞いをもとに rontolisp 向けに書いた Clojure ソースで、
すべてのバックエンドで同じように動きます。

ロケーション（loc）は、注目しているノードとそこまでの経路からなるベクタです。そのメタデータには、
木を木として扱うための 3 つの関数が入っています。移動や編集は新しい loc を返し、`root` はすべての
編集を反映した木を組み立て直します。

| var | 振る舞い |
|---|---|
| `zipper` | `(zipper branch? children make-node root)`: 枝を `branch?` で判定し、子を `children` で取り出し、ノードと子のシーケンスから `make-node` で組み立て直す木の、`root` にある loc |
| `vector-zip`、`seq-zip`、`xml-zip` | 入れ子のベクタ、入れ子のシーケンス、`:tag`・`:attrs`・`:content` を持つマップに対するジッパー |
| `node`、`branch?`、`children`、`make-node` | 注目しているノード、それが枝かどうか、その子、ジッパーの方法で組み立て直したノード |
| `path`、`lefts`、`rights` | 根から親までのノード、左の兄弟、右の兄弟 |
| `down`、`up`、`left`、`right`、`leftmost`、`rightmost` | 移動。行き先がなければ `nil`（`leftmost`/`rightmost` は loc 自身を返す） |
| `root` | すべての編集を反映した根のノード |
| `replace`、`edit`、`insert-left`、`insert-right`、`insert-child`、`append-child`、`remove` | 編集。`remove` は、深さ優先の巡回で削除したノードの直前にあたる loc を返す |
| `next`、`prev`、`end?` | 深さ優先の巡回。最後のノードの次は終端の loc で、`end?` がそれを判定する |

```clojure
(require '[clojure.zip :as z])
(def loc (z/vector-zip [1 [2 3] 4]))
(-> loc z/down z/right z/node) ; => [2 3]
(-> loc z/down z/right z/down (z/edit * 10) z/root) ; => [1 [20 3] 4]
(-> loc z/down (z/insert-right :x) z/root) ; => [1 :x [2 3] 4]
(-> loc z/down z/right z/remove z/root) ; => [1 4]
(loop [l loc acc []] (if (z/end? l) acc (recur (z/next l) (conj acc (z/node l)))))
; => [[1 [2 3] 4] 1 [2 3] 2 3 4]
(-> (z/seq-zip '(+ 1 (* 2 3))) z/down z/rightmost z/down (z/replace '/) z/root)
; => (+ 1 (/ 2 3))
```
