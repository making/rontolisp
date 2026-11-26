# file-error?

`(file-error? obj)`

`obj` がファイル操作の失敗によるエラーなら `#t` を返します: [(scheme file)](library-file.md) の手続きが開けない・作れない・削除できないファイルのエラーと、プログラムが呼び出す Common Lisp のコードが通知した `file-error` です。

```scheme
(guard (e (#t (file-error? e))) (open-input-file "no-such-file.txt")) ; => #t
(guard (e (#t (file-error? e))) (raise 'oops)) ; => #f
```
