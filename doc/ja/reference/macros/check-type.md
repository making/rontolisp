# check-type

`(check-type place typespec [string])`

`place` を評価し、値が指定された型でなければエラーをシグナルします。型に合致していれば nil を返します。これは Common Lisp の `check-type` のライト版です。リスタートシステムが存在しないため、エラーを対話的に修正することはできず、place へ値が再格納されることもありません。

サポートされる型指定子は `typecase` の型名（`integer`、`float`、`number`、`rational`、`string`、`symbol`、`keyword`、`cons`、`list`、`null`、`atom`、`character`、`hash-table`、`boolean`）に加え、複合形式の `(or ...)`、`(and ...)`、`(not ...)`、`(member item...)`、`(eql object)`、`(satisfies function)`、および `(integer 0 9)` のような範囲付き数値型（`*` は無制限、`(bound)` のリスト表記は排他境界）です。省略可能な文字列はエラーメッセージの「of type ...」の部分を置き換えます。

```lisp
(let ((n 5)) (check-type n (integer 0 9)) n) ; => 5
```

検査に失敗すると実行が中断されるため、静的な形で示します。

```console
(let ((n 12)) (check-type n (integer 0 9)))
; error: The value of n is 12, which is not of type (integer 0 9).
```
