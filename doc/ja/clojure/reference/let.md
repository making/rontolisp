# let

`(let [binding...] body...)`

束縛を順に評価し（Clojure の `let` は逐次で、`let*` へ低レベル化されます）、各束縛は自分より前のものを見たうえで、本体を評価しその最後の値を返します。束縛パターンは destructuring します。ベクターパターンは seq ビュー経由で位置的に束縛し、マップパターンは `:keys`/`:syms`/`:strs`、明示的なローカル、`:as`、`:or` デフォルトを経由し、入れ子のパターンは再帰します（パターンについては [Syntax](syntax.md)）。形の悪いものは名前を上げて拒否されます。マップパターンは seq を、そのキーワード引数が表すマップとして読み（[seq-to-map-for-destructuring](seq-to-map-for-destructuring.md)）、ベクターはそのまま読みます。

```clojure
(println (let [x 1 y x] y))            ; 1
(println (let [[a & r] [1 2 3]] [a r])) ; [1 (2 3)]
(println (let [{:keys [a b]} {:a 1 :b 2}] [a b])) ; [1 2]
(println (let [[x & {:keys [a]}] [1 :a 2]] [x a])) ; [1 2]
```
