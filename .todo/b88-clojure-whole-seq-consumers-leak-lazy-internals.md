# b88. Whole-seq consumers read a lazy input's wrapper internals

Difficulty: Medium

Measured 2026-10-02, oracle `clj` 1.12.6.1673 vs exec jar after b81, with
`(def L (map inc (lazy-seq [1 2 3])))`:

| form | oracle | ronto |
|---|---|---|
| `(frequencies L)` | `{2 1, 3 1, 4 1}` | `{2 1, :C%LAZY 1, (#<procedure>) 1}` |
| `(count (set L))` | `3` | `2` |
| `(group-by odd? L)` | `{false [2 4], true [3]}` | error |
| `(last L)` | `4` | `(#<procedure>)` |
| `(second L)` | `3` | `:C%LAZY` |
| `(butlast L)` | `(2 3)` | `(2 :C%LAZY)` |
| `(sort L)` | `(2 3 4)` | error |
| `(count L)` | `3` | `2` |
| `(nth L 2)` | `4` | `(#<procedure>)` |
| `(zipmap L [:a :b :c])` | `{2 :a, 3 :b, 4 :c}` | `{2 :a, :C%LAZY :b, ...}` |
| `(select-keys {2 :x 4 :y} L)` | `{2 :x, 4 :y}` | `{2 :x}` |
| `(apply + L)` | `9` | error |
| `(reverse L)` | `(4 3 2)` | `((nil 2 ...) :C%LAZY)` |

`vec`, `reduce`, `into` and `for`/`doseq` (b81) already agree.

Root cause: the same as b81. `ClojureSeqLowering.seqForm` (`%clojure-seq`) realizes one
level; a realized lazy cons's cdr is another `(:C%LAZY cell)` wrapper, which CL list
operators (`dolist`, `last`, `butlast`, `cadr`, `length`, `nth`, `reverse`, `apply`,
`mapcar`, `sort`) walk as list structure. The documented "lazy inputs consume one level
-- pass a taken prefix" deviation (`.kb/clojure-frontend.md`) understates it: the answers
are silently wrong, not refused.

## Plan

- Audit the `seqForm` call sites (73 on 2026-10-02) and split them by what they need:
  - whole-collection consumers (`count`, `last`, `butlast`, `sort`, `reverse`, `apply`,
    `frequencies`, `set`, `group-by`, `zipmap`, `select-keys`, ...): a full-realize view
    (one spliced `%clojure-seq-all` walking `%clojure-seq-rest`, answering the input list
    itself when no wrapper is found, so strict inputs stay copy-free);
  - prefix consumers (`second`, `nth`, ...): step with `%clojure-seq-rest`, so an
    infinite input still answers.
- Re-measure the wasm size of a strict-input program per touched verb (b21/b60
  convention); rewrite the `.kb/clojure-frontend.md` deviation bullet.

## Pin

- `clojure-spec.yaml` (all four backends): the table above as one case, plus `second`/
  `nth` over `(iterate inc 0)`.
