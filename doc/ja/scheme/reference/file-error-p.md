# file-error?

`(file-error? obj)`

`obj` がファイル操作の失敗によるエラーなら `#t` を返します。Scheme フロントエンドにはファイルを開く手続きがないため、`#t` になるのはプログラムが呼び出す Common Lisp のコードが通知したエラーだけです。

```scheme
(guard (e (#t (file-error? e))) (raise 'oops)) ; => #f
```
