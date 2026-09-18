# car

`(car pair)`

`pair` の car（最初の要素）を返します。

仕様との差異: `(car '())` はエラーにならず `()` を返します。それ以外のペアでない値はエラーになり、そのメッセージは Common Lisp の名前で書かれます: `car expects a cons cell, got: 5`。

```scheme
(car '(1 2 3)) ; => 1
(car '((a b) c)) ; => (a b)
(car '(a . b)) ; => a
```
