# typep

`(typep object 'type-specifier)`

`object` が指定した型かどうかを判定します。lite 版: 型指定子は通常リテラル (クオートされた) 型です — [`typecase`](typecase.md) がサポートするのと同じ集合 (アトミックな型名、登録済みクラス、引数なしのユーザー [`deftype`](deftype.md) 名、複合指定子 `(or ...)`/`(and ...)`/`(not ...)`/`(member ...)`/`(eql ...)`/`(satisfies ...)`/範囲付き数値型/`(unsigned-byte n)`/`(signed-byte n)`) です。未知の指定子は何にもマッチしません。

実行時に計算された指定子も、アトミックな型名 (登録済みのクラス / 構造体 / コンディション、または組み込みの型名) かクラスメタオブジェクトであればサポートされます — [`find-class`](../functions/find-class.md) や [`class-of`](../functions/class-of.md) が返すものは自分自身のクラスを指し示します。上記の複合指定子は引き続きリテラルである必要があります。`class` はすべてのクラスメタオブジェクトが属するクラスなので、`(typep x 'class)` が「これはクラスか?」の判定になります。

```lisp
(typep 5 '(unsigned-byte 8)) ; => T
```

```lisp
(typep 500 '(unsigned-byte 8)) ; => NIL
```

```lisp
(defclass animal () ())
(defclass dog (animal) ())
(list (typep (make-instance 'dog) (find-class 'animal))
      (typep (find-class 'dog) 'class)
      (typep 42 'class)) ; => (T T NIL)
```
