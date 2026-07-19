# typep

`(typep object 'type-specifier)`

`object` が指定した型かどうかを判定します。lite 版: 型指定子はリテラル (クオートされた) 型に限られます — [`typecase`](typecase.md) がサポートするのと同じ集合 (アトミックな型名、登録済みクラス、引数なしのユーザー [`deftype`](deftype.md) 名、複合指定子 `(or ...)`/`(and ...)`/`(not ...)`/`(member ...)`/`(eql ...)`/`(satisfies ...)`/範囲付き数値型/`(unsigned-byte n)`/`(signed-byte n)`) です。未知の指定子は何にもマッチしません。

```lisp
(typep 5 '(unsigned-byte 8)) ; => t
```

```lisp
(typep 500 '(unsigned-byte 8)) ; => nil
```
