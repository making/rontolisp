# deftype

`(deftype name lambda-list body...)`

**引数なし** の `deftype` で本体がリテラル (クオートされた) 型指定子の場合は登録され、定義した名前を後続の [`typep`](typep.md)/[`typecase`](typecase.md) の型テストで型として解決できます (`(satisfies predicate)` の本体は指定した述語を呼び出します。名前がさらに別の型名へ展開される形も可能です)。**引数あり** やその他計算を伴う `deftype` は、パース済み no-op として `nil` を返すままです — 呼び出しごとの展開を行わないため名前は解決できません。これは、その名前が(同じく no-op の)`declaim`/`declare` 宣言の中にのみ現れる、ライブラリでよくある形を通すためのものです。

```lisp
(deftype my-even () '(satisfies evenp))
(list (typep 4 'my-even) (typep 3 'my-even)) ; => (T NIL)
```

```lisp
(deftype array-index (&optional (length 1000)) `(integer 0 (,length))) ; => NIL
```
