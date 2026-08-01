# type-of

`(type-of object)`

値の型名をシンボルで返します。`defstruct`/CLOS インスタンスには構造体/クラスの「名前」を返し、それ以外の値には組み込み型名のシンボル (`integer`、`string`、`cons` など) を返し、該当がなければ `t` になります。[`class-of`](class-of.md) がクラスメタオブジェクトとして返すものの「名前だけ」のビューであり、`(type-of x)` と `(class-name (class-of x))` は一致します。

```lisp
(type-of 42) ; => INTEGER
```
