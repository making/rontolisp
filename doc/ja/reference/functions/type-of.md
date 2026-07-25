# type-of

`(type-of object)`

値の型名をシンボルで返します。`defstruct`/CLOS インスタンスには構造体/クラスの「名前」を返します ([`class-of`](class-of.md) はインスタンスのクラスタグを返す点が異なります)。それ以外の値には `class-of` と同じ組み込み型名のシンボル (`integer`、`string`、`cons` など) を返し、該当がなければ `t` になります。

```lisp
(type-of 42) ; => INTEGER
```
