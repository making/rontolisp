# type-of

`(type-of object)`

値の型名をシンボルで返します。`defstruct`/CLOS インスタンスには構造体/クラスの「名前」を返し、それ以外の値には組み込み型名のシンボル (`integer`、`string`、`cons` など) を返し、該当がなければ `t` になります。[`class-of`](class-of.md) がクラスメタオブジェクトとして返すものの「名前だけ」のビューであり、`(type-of x)` と `(class-name (class-of x))` は一致します。別のパッケージで定義されたクラスは、呼び出し側がどのパッケージにいても、パッケージ修飾された名前 (エクスポートされていればコロン 1 個、されていなければ 2 個) を返します。

```lisp
(type-of 42) ; => INTEGER
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
