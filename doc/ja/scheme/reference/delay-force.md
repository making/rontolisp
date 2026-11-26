# delay-force

`(delay-force expression)`

force されたときに `expression`（プロミスを返すべき式）を評価し、そのプロミスを続けて force するプロミスを返します。`delay-force` の連鎖は反復的に force されるため、10 万段の遅延ループも一定のスタックで動きます。`expression` がプロミス以外を返したときは、force はその値を返します。

```scheme
(force (delay-force (delay (+ 1 2)))) ; => 3
(define (count-down k) (if (= k 0) (delay 'done) (delay-force (count-down (- k 1)))))
(force (count-down 100000)) ; => done
```
