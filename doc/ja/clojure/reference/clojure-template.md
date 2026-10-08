# clojure.template

式のテンプレート、つまり式の引数シンボルに値を代入する名前空間です。`clojure.template` を require
すると使えます。Clojure の同名の名前空間について文書化された振る舞いをもとに rontolisp 向けに書いた
Clojure ソースで、すべてのバックエンドで同じように動きます。

| var | 振る舞い |
|---|---|
| `apply-template` | `(apply-template argv expr values)`: `expr` に含まれる `argv` の各シンボルを、どの深さでも `values` の同じ位置の値に置き換える。`argv` の要素はすべてシンボルでなければならない |
| `do-template` | `(do-template argv expr & values)`: 値を `argv` と同じ長さの組に分け、組ごとに置き換えた `expr` を並べた `do` ブロックになる（長さの足りない最後の組は捨てる） |

```clojure
(require '[clojure.template :as t])
(t/apply-template '[x y] '(+ x (* y y)) '[1 2])
; => (+ 1 (* 2 2))
(macroexpand '(t/do-template [x y] (+ y x) 2 4 3 5))
; => (do (+ 4 2) (+ 5 3))
```

```clojure
(require '[clojure.template :refer [do-template]])
(do-template [op n] (println (op n 10)) + 1 - 2 * 3)
```

```
11
-8
30
```
