# deftype

`(deftype name lambda-list body...)`

`name` を後続の [`typep`](typep.md)/[`typecase`](typecase.md) の型テストで使える型として定義し、`nil` を返します。本体は、その名前が表す型指定子を返します (`(satisfies predicate)` の本体は指定した述語を呼び出します。名前がさらに別の型名へ展開される形も可能です)。引数ありの `deftype` では、複合型指定子 `(name args...)` は、ラムダリストを `args` に束縛して本体が返す型指定子を表します。初期値形式のない `&optional`/`&key` パラメータは `*` になります。パラメータがすべて省略可能な場合、名前だけの指定は `(name)` と同じです。

```lisp
(deftype my-even () '(satisfies evenp))
(list (typep 4 'my-even) (typep 3 'my-even)) ; => (T NIL)
```

```lisp
(deftype vec-of (n) `(simple-vector ,n))
(list (typep (vector 1 2) '(vec-of 2)) (typep (vector 1 2 3) '(vec-of 2))) ; => (T NIL)
```
