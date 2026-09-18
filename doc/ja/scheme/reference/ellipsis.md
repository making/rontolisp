# ...

`pattern ...` `template ...`

`syntax-rules` の補助構文です。パターン中では直前の部分パターンに 0 回以上一致し、テンプレート中では一致した回数だけ直前の部分テンプレートを繰り返します。単独では意味を持ちません。

```scheme
(let-syntax ((my-list (syntax-rules () ((_ e ...) (list e ...))))) (my-list 1 2 3)) ; => (1 2 3)
(define-syntax my-list-of-lists
  (syntax-rules ()
    ((_ (a ...) ...) '((a ... end) ...))))
(my-list-of-lists (1 2) (3)) ; => ((1 2 end) (3 end))
```
