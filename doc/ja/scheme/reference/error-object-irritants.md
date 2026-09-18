# error-object-irritants

`(error-object-irritants error-object)`

エラーオブジェクトの irritant（`error` にメッセージの後で渡した引数）をリストで返します。組み込み手続きのエラーには irritant はありません。エラーオブジェクトでないものを渡すとエラーです。

```scheme
(guard (e (#t (error-object-irritants e))) (error "bad thing:" 1 2)) ; => (1 2)
(guard (e (#t (error-object-irritants e))) (error "only a message")) ; => ()
```
