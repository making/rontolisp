# special-operator-p

`(special-operator-p symbol)`

シンボルが ANSI の 25 個の特殊オペレータ — `block`, `catch`, `eval-when`, `flet`, `function`, `go`, `if`, `labels`, `let`, `let*`, `load-time-value`, `locally`, `macrolet`, `multiple-value-call`, `multiple-value-prog1`, `progn`, `progv`, `quote`, `return-from`, `setq`, `symbol-macrolet`, `tagbody`, `the`, `throw`, `unwind-protect` — のいずれかを指すときに真、それ以外は `nil` です。

`nil` になるものには、rontolisp が独自に特殊形式として実装している Common Lisp の**マクロ**(`defun`、`handler-case`、`dolist` など)も含まれます。呼び出し側が知りたいのは「この名前を `apply` してよいか」であり、それらの名前は Common Lisp と同じく [`macro-function`](macro-function.md) の側で答えます。

```lisp
(list (special-operator-p 'if) (special-operator-p 'defun) (special-operator-p 'car)) ; => (T NIL NIL)
```

ライト版: シンボル以外の引数は、Common Lisp が型エラーをシグナルするところで `nil` を返します。
