# error

`(error message obj ...)`

文字列 `message` と irritant `obj ...` を持つエラーを通知します。`guard` や `with-exception-handler` はまだないためエラーは捕捉できず、プログラムを終了します。表示されるのは `message` と、`write` の形式で書かれた irritant です（`(error "bad thing:" 42 "str")` は `bad thing: 42 "str"` と報告します）。プロセスは終了ステータス 1 で終わります。その前に、囲んでいる `dynamic-wind` の `after` が実行されます。

```scheme
(define (safe-div a b)
  (if (= b 0)
      (error "division by zero:" a)
      (/ a b)))
(display (safe-div 10 4))
(newline)
```

```
5/2
```
