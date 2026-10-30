# functionp

`(functionp object)`

`object` が関数値 — `(lambda ...)`、`#'name`、`symbol-function` の結果 — または funcallable インスタンス（`closer-mop:funcallable-standard-class` のクラスのインスタンス）であれば `t` を、それ以外のオブジェクトには `nil` を返します。Lisp-2 では裸のシンボルは決して関数ではないため、`(functionp 'car)` は `nil`、`(functionp #'car)` は `t` です。

名前が登録された関数値は `#<function NAME>`、無名のもの（`lambda`、`flet`、`labels`）は `#<lambda>` と表示される。NAME は登録名の通りに大文字で、自パッケージ内では裸の名前、エクスポート経由なら `PKG:`、内部シンボルなら `PKG::` が付く。このテキストはすべてのバックエンドで同一であり、識別子やアドレスは含まれない。

```lisp
(functionp #'car) ; => T
```

```lisp
(functionp (lambda (x) x)) ; => T
```

```lisp
(functionp 'car) ; => NIL
```
