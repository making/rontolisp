# check-type

`(check-type place typespec [string])`

`place` を評価し、値が指定された型でなければ `simple-type-error` をシグナルします。型に合致していれば nil を返します。このコンディションは `type-error` で、`type-error-datum` は値、`type-error-expected-type` は型指定子です。`simple-condition` でもあるため、`simple-condition` 節で捕捉でき、`simple-condition-format-control` でメッセージを読めます。これは Common Lisp の `check-type` のライト版です。`store-value` リスタートを確立しないため、エラーを対話的に修正することはできず、place へ値が再格納されることもありません。

型指定子は [`typep`](typep.md) が受け取るものすべてです。標準の型名、クラス、コンディション、構造体、`deftype`、複合形式の `(or ...)`、`(and ...)`、`(not ...)`、`(member item...)`、`(eql object)`、`(satisfies function)`、`(mod n)`、および `(integer 0 9)` のような範囲付き数値型（`*` は無制限、`(bound)` のリスト表記は排他境界）で、複合形式は他のどの型指定子も含められます。どこにも定義されていない型指定子と `restart` は検査されず、place を評価して nil を返します。`generic-function`、`structure-class`、`logical-pathname`、`extended-char` は、`typep` がここではそう答えない値も受け入れます。代わりに関数、クラス、パス名、文字を検査します。省略可能な文字列はエラーメッセージの「of type ...」の部分を置き換えます。

```lisp
(let ((n 5)) (check-type n (integer 0 9)) n) ; => 5
(handler-case (let ((n 12)) (check-type n (integer 0 9)))
  (type-error (c) (list (type-error-datum c) (type-error-expected-type c))))
; => (12 (INTEGER 0 9))
```

捕捉されない検査失敗は実行を中断するため、静的な形で示します。

```console
(let ((n 12)) (check-type n (integer 0 9)))
; error: The value of n is 12, which is not of type (integer 0 9).
```
