# case-lambda

`(case-lambda (formals body...)...)`

手続きを返します。呼び出しは、引数の個数を受け付ける最初の節をとります -- `(x y)` はちょうど 2 個、`(x . rest)` は 1 個以上、`args` は何個でも。その `formals` を持つ `lambda` と同じように引数を束縛し、`body` を評価します。各 `body` の先頭には定義を書けます。`case-lambda` で定義した手続きが末尾位置で自分自身を呼ぶ場合は、`lambda` で定義した手続きと同じく一定の空間で動きます。

仕様との差異:

- どの節も受け付けない呼び出しは、メッセージが `wrong number of arguments to case-lambda:` に引数が続くエラーオブジェクトを raise します。Gauche は `case lambda` と綴ります。
- `eval` の中の `case-lambda` は名前を挙げて拒否されます。

```scheme
((case-lambda ((x) (list x)) ((x y) (+ x y))) 1 2) ; => 3
(define range
  (case-lambda
    ((end) (range 0 end))
    ((start end) (if (>= start end) '() (cons start (range (+ start 1) end))))))
(range 3) ; => (0 1 2)
(range 2 5) ; => (2 3 4)
(define f (case-lambda ((x) 'one) ((x . rest) (length rest)) (all 'none)))
(list (f) (f 1) (f 1 2 3)) ; => (none one 2)
(guard (e ((error-object? e) (error-object-message e))) ((case-lambda ((x) x)) 1 2)) ; => "wrong number of arguments to case-lambda: (1 2)"
```
