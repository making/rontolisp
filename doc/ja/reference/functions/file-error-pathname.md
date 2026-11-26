# file-error-pathname

`(file-error-pathname condition)`

`file-error` コンディションが保持するパス名 — 失敗した操作に渡された指定子そのものです(rontolisp はパスを絶対パスにしません)。[`open`](open.md)、[`delete-file`](delete-file.md)、[`rename-file`](rename-file.md)、[`truename`](truename.md) は 4 つすべてのバックエンドで `file-error` を通知します。

```lisp
(handler-case (delete-file "fep-missing/notes.txt")
  (file-error (e) (namestring (file-error-pathname e)))) ; => "fep-missing/notes.txt"
```
