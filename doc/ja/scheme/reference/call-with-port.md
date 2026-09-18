# call-with-port

`(call-with-port port proc)`

`port` を引数に `proc` を呼び、`proc` が戻ったら `port` を閉じ、`proc` の返したもの（すべての値）を返します。`proc` が戻らなければ `port` は開いたままです。

```scheme
(call-with-port (open-input-string "(1 2)") read) ; => (1 2)
```
