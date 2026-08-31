# type-of

`(type-of object)`

値の型名をシンボルで返します。`defstruct`/CLOS インスタンスには構造体/クラスの「名前」を返し、それ以外の値には組み込み型名のシンボル (`integer`、`string`、`cons` など) を返し、該当がなければ `t` になります。[`class-of`](class-of.md) がクラスメタオブジェクトとして返すものの「名前だけ」のビューであり、`(type-of x)` と `(class-name (class-of x))` は一致します。別のパッケージで定義されたクラスは、呼び出し側がどのパッケージにいても、パッケージ修飾された名前 (エクスポートされていればコロン 1 個、されていなければ 2 個) を返します。

```lisp
(type-of 42) ; => INTEGER
```

配列だけは複合型指定子を返すため、ランクと要素型を読み取れます。要素型が `t` の単純な 1 次元配列は `(simple-vector SIZE)`、フィルポインタ付きまたは `:adjustable t` の配列は `(vector t SIZE)`、それ以外 (次元リストが `nil` になるランク 0 配列を含む) は `(simple-array ELEMENT-TYPE DIMENSIONS)` です。要素型は [`array-element-type`](array-element-type.md) が返す昇格後の型なので、`:element-type 'fixnum` で作った配列は `t` として読み出されます。文字列はアトミックな `string` を返します。

```lisp
(list (type-of (make-array 4))
      (type-of (make-array nil))
      (type-of (make-array '(2 2) :element-type 'double-float))
      (type-of (make-array 4 :element-type '(unsigned-byte 8)))
      (type-of (make-array 4 :fill-pointer 0)))
; => ((SIMPLE-VECTOR 4) (SIMPLE-ARRAY T NIL) (SIMPLE-ARRAY DOUBLE-FLOAT (2 2)) (SIMPLE-ARRAY (UNSIGNED-BYTE 8) (4)) (VECTOR T 4))
```

```lisp
(defpackage :gfx (:use :cl) (:export :sprite))
(in-package :gfx)
(defclass sprite () ())
(defclass hidden () ())
(defpackage :game (:use :cl))
(in-package :game)
(list (type-of (make-instance 'gfx:sprite))
      (type-of (make-instance 'gfx::hidden))) ; => (GFX:SPRITE GFX::HIDDEN)
```
