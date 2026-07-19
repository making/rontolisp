# typecase

`(typecase x (integer body...) (string body...) (t default...))`

`x` を一度だけ評価し、`x` が満たす型指定子を持つ最初の節を選択して、その節の本体を評価し、最後の値を返します。サポートされる型名は `integer`、`float`、`number`、`rational`、`string`、`symbol`、`keyword`、`cons`、`list`、`null`、`atom`、`character`、`hash-table`、`boolean` と、最後のデフォルト節としての `t`／`otherwise` です。複合指定子も使えます: `(or ...)`、`(and ...)`、`(not ...)`、`(member item...)`、`(eql object)`、`(satisfies function)`、および `(integer 0 9)` のような範囲付き数値型です（詳細は `check-type` を参照）。引数なしのユーザー [`deftype`](deftype.md) 名も解決されます。どの節にも一致せずデフォルトもない場合、`typecase` は nil を返します。

```lisp
(let ((x "hi")) (typecase x (integer 'int) (string 'str) (t 'other))) ; => str
```

```lisp
(let ((n 5)) (typecase n ((integer 0 9) 'digit) (integer 'int))) ; => digit
```
