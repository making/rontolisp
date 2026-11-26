# car

`(car pair)`

`pair` の car（最初の要素）を返します。

仕様との差異: `(car '())` はエラーにならず `()` を返します。それ以外のペアでない値はエラーになり、そのメッセージは Common Lisp の名前で書かれます: `CAR: The value 5 is not of type LIST`。

```scheme
(car '(1 2 3)) ; => 1
(car '((a b) c)) ; => (a b)
(car '(a . b)) ; => a
```
