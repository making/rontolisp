# class-of

`(class-of object)`

任意の値のクラスメタオブジェクトを返します — [`find-class`](find-class.md) が返すのと同じメモ化された `standard-class` インスタンスであり、`(eq (class-of x) (find-class 'name))` が成り立ちます。CLOS インスタンスはそのクラスを、`defstruct` インスタンスはその構造体型を(こちらも `standard-class` インスタンスとして — `structure-class` はありません)、それ以外の値は `integer`、`string`、`cons` などの名前を持つスロットなしの組み込みクラスを返し、その集合の外の値(配列など)は `t` になります。名前は [`class-name`](class-name.md) で読み取れます。

```lisp
(defclass point () ((x :initarg :x)))
(list (class-name (class-of 42))
      (class-name (class-of (make-instance 'point)))
      (eq (class-of 42) (find-class 'integer))) ; => (INTEGER POINT T)
```
