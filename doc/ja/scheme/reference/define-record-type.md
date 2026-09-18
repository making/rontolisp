# define-record-type

`(define-record-type name (constructor field...) predicate (field accessor [modifier])...)`

レコード型を定義します。`constructor` は列挙したフィールドからレコードを作り、`predicate` はレコードかどうかを判定し、各 `accessor`（と省略可能な `modifier`）はフィールドを読み（書き）ます。トップレベルでのみ使えます。レコードは Common Lisp の `#S(...)` 構文で書き出され、各フィールドはアクセサの名前で表示されます。`equal?` はレコードを同一性で比較します。modifier は未規定値ではなく格納した値を返します。

```scheme
(define-record-type point (make-point x y) point? (x point-x set-point-x!) (y point-y))
(define p (make-point 3 4))
(set-point-x! p 10)
(write (list (point-x p) (point-y p) (point? p) (point? 5)))
(newline)
(write p)
(newline)
```

```
(10 4 #t #f)
#S(point :point-x 10 :point-y 4)
```
