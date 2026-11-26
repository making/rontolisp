# error-object?

`(error-object? obj)`

`obj` がエラーオブジェクト（`error` が発生させるもの、または組み込み手続きのエラーの条件）なら `#t` を返します。それ以外の発生させたオブジェクトは `#f` です。

```scheme
(guard (e (#t (error-object? e))) (error "bad")) ; => #t
(guard (e (#t (error-object? e))) (raise 'oops)) ; => #f
(guard (e (#t (error-object? e))) (+ 1 (car '(a)))) ; => #t
```
