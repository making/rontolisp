# string-set!

`(string-set! string k char)`

`string` の添字 `k` に `char` を格納し、未規定値を返します。文字列リテラルも含め、すべての文字列が変更可能です。R7RS ではリテラルの変更はエラーですが、rontolisp は拒否しません。

```scheme
(let ((s (make-string 3 #\a))) (string-set! s 1 #\b) s) ; => "aba"
(define s (make-string 3 #\a))
(string-set! s 1 #\b)
s ; => "aba"
```
