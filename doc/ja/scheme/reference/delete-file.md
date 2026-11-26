# delete-file

`(delete-file string)`

`string` という名前のファイルを削除します。存在しないか削除できないファイルは、`file-error?` が `#t` を返すエラーを raise します。

```scheme
(with-output-to-file "hello.txt" (lambda () (display "hello")))
(delete-file "hello.txt")
(file-exists? "hello.txt") ; => #f
(guard (e ((file-error? e) 'no-such-file)) (delete-file "hello.txt")) ; => no-such-file
```
