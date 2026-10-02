# reduce

`(reduce f coll)` / `(reduce f val coll)`

`coll` の seq ビューへ `f` を左から右へ1要素ずつ畳み込むため、lazy な入力も最後まで畳み込みます。3 アリティは Clojure の引数順 -- 関数、初期値、コレクション -- を取ります。2 アリティは種なしで畳み込み、空のコレクションには `(f)` を、要素1つのコレクションにはその要素を返します。[`reduced`](reduced.md) が返ると畳み込みを止めてその値を返します。

```clojure
(println (reduce + '(1 2 3)))  ; 6
(println (reduce + 0 [1 2 3])) ; 6
(println (reduce (fn [a x] (if (> a 3) (reduced a) (+ a x))) [1 2 3 4 5])) ; 6
```
