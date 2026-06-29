# typecase

`(typecase x (integer body...) (string body...) (t default...))`

`x` を一度だけ評価し、`x` が満たす型名を持つ最初の節を選択して、その節の本体を評価し、最後の値を返します。サポートされる型名は `integer`、`float`、`number`、`rational`、`string`、`symbol`、`keyword`、`cons`、`list`、`null`、`atom` と、最後のデフォルト節としての `t`／`otherwise` です。どの節にも一致せずデフォルトもない場合、`typecase` は nil を返します。

```lisp
(let ((x "hi")) (typecase x (integer 'int) (string 'str) (t 'other))) ; => str
```
