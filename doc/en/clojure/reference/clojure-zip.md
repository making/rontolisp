# clojure.zip

Functional tree editing with zippers. Require `clojure.zip` to use it; it is Clojure source
written for rontolisp from the documented behavior of Clojure's namespace, and runs the same
on every backend.

A location (loc) is a vector of the focused node and the path to it; its metadata holds the
three functions that make the tree a tree. Moving and editing answer new locs, and `root`
rebuilds the tree with every edit applied.

| Var | Behavior |
|---|---|
| `zipper` | `(zipper branch? children make-node root)`: a loc at `root` of a tree whose branches `branch?` recognizes, whose children `children` answers and which `make-node` rebuilds from a node and a seq of children |
| `vector-zip`, `seq-zip`, `xml-zip` | Zippers over nested vectors, nested seqs, and maps of `:tag`, `:attrs` and `:content` |
| `node`, `branch?`, `children`, `make-node` | The focused node, whether it is a branch, its children, a node rebuilt the zipper's way |
| `path`, `lefts`, `rights` | The nodes from the root down to the parent, the left and the right siblings |
| `down`, `up`, `left`, `right`, `leftmost`, `rightmost` | Moves; `nil` where there is nowhere to go (`leftmost`/`rightmost` answer the loc itself) |
| `root` | The root node with every edit applied |
| `replace`, `edit`, `insert-left`, `insert-right`, `insert-child`, `append-child`, `remove` | Edits; `remove` answers the loc before the removed node in a depth-first walk |
| `next`, `prev`, `end?` | A depth-first walk: `next` past the last node answers the end loc, which `end?` recognizes |

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
