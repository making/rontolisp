# string-ref

`(string-ref string k)`

`string` の 0 始まりの添字 `k` にある文字を返します。範囲外の添字はエラーでプログラムを終了し、そのメッセージは Common Lisp の名前 `CHAR` を綴ります。

```scheme
(string-ref "hello" 1) ; => #\e
(string-ref "hello" 0) ; => #\h
```
