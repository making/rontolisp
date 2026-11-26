# raise-continuable

`(raise-continuable obj)`

`raise` と同じように `obj` を発生させますが、最も内側のハンドラの値が `raise-continuable` 呼び出しの値になり、プログラムはそこから続きます。`guard` に捕捉された場合は `raise` と同じです。

```scheme
(with-exception-handler (lambda (e) 10) (lambda () (+ 1 (raise-continuable 'oops)))) ; => 11
(with-exception-handler (lambda (e) (* e 2)) (lambda () (list (raise-continuable 1) (raise-continuable 2)))) ; => (2 4)
```
