# define-library

`(define-library (name...) declaration...)`

どのライブラリもエクスポートしないプログラム構文で、プログラムや他のライブラリがその名前で `import` するライブラリを定義します。名前は識別子と正確な非負整数のリストで、`scheme` で始まる名前は予約されています。宣言は次のとおりです:

- `(export spec...)`。spec は識別子か `(rename internal external)` です;
- `(import import-set...)`。ライブラリ本体から見えるもので、ライブラリが何も `import` しないときは、`import` のないプログラムと同じくすべてが見えます;
- `(begin body...)`;
- `(include "file"...)` と `(include-ci "file"...)`。ファイルの内容を本体に加えます;
- `(include-library-declarations "file"...)`。そのファイルにさらに宣言が入っています;
- `(cond-expand (requirement declaration...)...)`。[cond-expand](cond-expand.md) が選んだ節の宣言です。

本体はそれ自体 1 つのファイルとして変換されます。インポートする側に届くのはエクスポートした名前だけで、それ以外のトップレベルの名前はライブラリの中に閉じるので、プログラムが同じ名前を定義してもかまいません。ライブラリは、インポートするファイルの先頭、その `import` より前にある `define-library` 形式の中から探されるか、ファイルとして探されます: `(import (geometry point))` は、プログラムを開始したファイルのディレクトリにある `geometry/point.sld`、なければ `geometry/point.scm` を読みます。いくつのファイルがインポートしても、ライブラリが実行されるのは最初のファイルがインポートしたときの 1 回だけです。ライブラリは [define-syntax](define-syntax.md) のマクロもエクスポートできます: テンプレートが自由に使う名前は、インポートする側がその名前を何に使っていても、ライブラリの中での意味（非公開の名前も含む）になります。

```scheme
(define-library (counter) (export next!) (import (scheme base)) (begin (define n 0) (define (next!) (set! n (+ n 1)) n)))
(import (scheme base) (scheme write) (counter))
(next!)
(display (next!))
(newline)
```

```
2
```

専用のファイルに置いたライブラリで、非公開の補助手続きと、別名でのエクスポートを使う例です:

```scheme
; file: geometry/point.sld
(define-library (geometry point)
  (export make-point point-x point-y (rename add point-add))
  (import (scheme base))
  (begin
    (define-record-type point (make-point x y) point? (x point-x) (y point-y))
    (define (add a b)
      (make-point (+ (point-x a) (point-x b)) (+ (point-y a) (point-y b))))))
```

```scheme
(import (scheme base) (scheme write) (geometry point))
(define (add a b) 'mine)
(define p (point-add (make-point 1 2) (make-point 10 20)))
(write (list (point-x p) (point-y p) (add 1 2)))
(newline)
```

```
(11 22 mine)
```

ライブラリの非公開の手続きと変数を使うマクロをエクスポートする例です。インポートする側の `count!` はそれを置き換えません:

```scheme
; file: stack/macros.sld
(define-library (stack macros)
  (export push! pushes)
  (import (scheme base))
  (begin
    (define pushes 0)
    (define (count!) (set! pushes (+ pushes 1)))
    (define-syntax push!
      (syntax-rules ()
        ((_ x place) (begin (count!) (set! place (cons x place))))))))
```

```scheme
(import (scheme base) (scheme write) (stack macros))
(define (count!) 'mine)
(define items '())
(push! 1 items)
(push! 2 items)
(write (list items pushes (count!)))
(newline)
```

```
((2 1) 2 mine)
```
