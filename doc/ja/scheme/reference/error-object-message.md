# error-object-message

`(error-object-message error-object)`

エラーオブジェクトのメッセージ（`error` の最初の引数）を返します。組み込み手続きのエラーでは報告全体で、その文面は R7RS の規定外であり、バックエンドによって異なることがあります。エラーオブジェクトでないものを渡すとエラーです。

```scheme
(guard (e (#t (error-object-message e))) (error "bad thing:" 1 2)) ; => "bad thing:"
```
