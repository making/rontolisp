# delay-force

`(delay-force expression)`

Answers a promise that, when forced, evaluates `expression` (which should answer a promise) and forces that promise in turn. A chain of `delay-force`s is forced iteratively, so a lazy loop of 100,000 steps runs in constant stack. If `expression` answers something other than a promise, forcing answers that value.

```scheme
(force (delay-force (delay (+ 1 2)))) ; => 3
(define (count-down k) (if (= k 0) (delay 'done) (delay-force (count-down (- k 1)))))
(force (count-down 100000)) ; => done
```
