# define

`(define variable expression)` `(define (name formals...) body...)` `(define (name . rest) body...)`

`variable` を `expression` の値に束縛します。2 つ目と 3 つ目の形は手続きを定義するもので、`(define name (lambda formals body...))` と同じです。トップレベルではグローバルな束縛を作るか置き換え、`square` のような組み込みの名前をユーザーが定義すると組み込みより優先されます。本体の先頭では内部定義となり、本体の内部定義は `letrec*` と同じように振る舞います。定義には値がないので REPL は何も表示しません。カリー化した形 `(define ((f a) b) ...)` には対応していません。

```scheme
(let () (define x 2) (* x 3)) ; => 6
(define (square2 n) (* n n))
(square2 5) ; => 25
```
