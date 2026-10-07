# class-of

`(class-of object)`

任意の値のクラスメタオブジェクトを返します — [`find-class`](find-class.md) が返すのと同じメモ化された `standard-class` インスタンスであり、`(eq (class-of x) (find-class 'name))` が成り立ちます。CLOS インスタンスはそのクラスを、`defstruct` インスタンスはその構造体型を(こちらも `standard-class` インスタンスとして — `structure-class` はありません)、それ以外の値は `integer`、`string`、`cons` などの名前を持つスロットなしの組み込みクラスを返し、その集合の外の値は `t` になります。配列はそれを含む最も狭い組み込みクラスを返します — 文字列は `string`、それ以外のランク 1 の配列は `vector`、そのランクより上下の配列は `array` です。組み込みのストリームは、[`type-of`](type-of.md) が返す標準の名前 (`string-stream`、`two-way-stream`、...、`stream`) を持つスロットなしのクラスを返します。名前は [`class-name`](class-name.md) で読み取れます。[`type-of`](type-of.md) はより細かい別の見方で、こちらは変わりません。

```lisp
(defclass point () ((x :initarg :x)))
(list (class-name (class-of 42))
      (class-name (class-of (make-instance 'point)))
      (eq (class-of 42) (find-class 'integer))) ; => (INTEGER POINT T)
```

```lisp
(list (class-name (class-of (make-array 3)))
      (class-name (class-of #2a((1 2) (3 4))))
      (class-name (class-of "ab"))) ; => (VECTOR ARRAY STRING)
```

```lisp
(let ((s (make-string-input-stream "a")))
  (list (class-name (class-of s))
        (eq (class-of s) (find-class 'string-stream)))) ; => (STRING-STREAM T)
```
