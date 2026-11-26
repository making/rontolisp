# make-promise

`(make-promise obj)`

既に `obj` へ force 済みのプロミスを返します。`obj` 自身がプロミスなら、それをそのまま返します。

```scheme
(force (make-promise 42)) ; => 42
(promise? (make-promise 7)) ; => #t
(force (make-promise (make-promise 1))) ; => 1
```
