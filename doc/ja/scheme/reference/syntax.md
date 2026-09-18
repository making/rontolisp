# 構文

フロントエンドが実装している構文キーワードです。`import` 以外はすべて `(scheme base)` がエクスポートします。`import` はプログラムの構文で、どのライブラリもエクスポートしません。`delay` と `delay-force` は [(scheme lazy)](library-lazy.md) に、`cons-stream` は [*Structure and Interpretation of Computer Programs*（SICP）互換の名前](library-sicp.md) に載っています。名前で拒否されるキーワードは[仕様との差異](../deviations.md)にあります。

| 名前 | 例 | 結果 |
|---|---|---|
| `quote` | `(quote (a b c))` | `(a b c)` |
| `quasiquote` | `` `(1 ,(+ 1 1) 3) `` | `(1 2 3)` |
| `unquote` | `` `(1 ,(+ 1 1)) `` | `(1 2)` |
| `unquote-splicing` | `` `(1 ,@(list 2 3) 4) `` | `(1 2 3 4)` |
| `lambda` | `((lambda (x y) (+ x y)) 3 4)` | `7` |
| `if` | `(if (> 3 2) 'yes 'no)` | `yes` |
| `set!` | `(let ((x 1)) (set! x 2) x)` | `2` |
| `begin` | `(let ((x 1)) (begin (set! x (* x 10)) (+ x 1)))` | `11` |
| `let` | `(let ((a 1) (b 2)) (+ a b))` | `3` |
| `let*` | `(let* ((a 1) (b (+ a 1))) (list a b))` | `(1 2)` |
| `letrec` | `(letrec ((ev? (lambda (n) (if (= n 0) #t (od? (- n 1))))) (od? (lambda (n) (if (= n 0) #f (ev? (- n 1)))))) (ev? 10))` | `#t` |
| `letrec*` | `(letrec* ((a 1) (b (+ a 1))) (list a b))` | `(1 2)` |
| `do` | `(do ((i 0 (+ i 1)) (sum 0 (+ sum i))) ((= i 5) sum))` | `10` |
| `cond` | `(cond ((> 1 2) 'a) ((> 2 1) 'b) (else 'c))` | `b` |
| `case` | `(case (* 2 3) ((2 3 5 7) 'prime) ((1 4 6 8 9) 'composite) (else 'other))` | `composite` |
| `and` | `(and 1 2 'last)` | `last` |
| `or` | `(or #f 2 3)` | `2` |
| `when` | `(when (> 2 1) 'a 'b)` | `b` |
| `unless` | `(unless (> 1 2) 'ran)` | `ran` |
| `define` | `(let () (define x 2) (* x 3))` | `6` |
| `define-values` | `(let () (define-values (q r) (values 17 5)) (list q r))` | `(17 5)` |
| `define-record-type` | `(define-record-type point (make-point x y) point? (x point-x set-point-x!) (y point-y))` | `make-point`、`point?`、`point-x`、`set-point-x!`、`point-y` を定義 |
| `let-values` | `(let-values (((q r) (values 17 5)) ((s) (values 'x))) (list q r s))` | `(17 5 x)` |
| `let*-values` | `(let*-values (((a b) (values 1 2)) ((c) (values (+ a b)))) (list a b c))` | `(1 2 3)` |
| `else` | `(cond ((> 1 2) 'a) (else 'b))` | `b` |
| `=>` | `(cond ((assv 'b '((a 1) (b 2))) => cadr) (else #f))` | `2` |
| `import` | `(import (scheme base) (scheme write))` | プログラムからその 2 つのライブラリが見える |
