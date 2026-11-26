# call/cc

`(call/cc proc)`

現在の継続を唯一の引数として `proc` を呼び出します。継続を値付きで呼ぶと、その値が `call/cc` 呼び出しの値として返ります。継続は脱出専用で、その `call/cc` の実行中に 1 回だけ呼べます。後から再突入することはできないため、ジェネレータやコルーチンは作れません。`call-with-current-continuation` は同じ手続きです。

```scheme
(call/cc (lambda (k) (+ 1 (k 42)))) ; => 42
(call/cc (lambda (k) 5)) ; => 5
```
