# declare

`(declare declaration...)`

宣言はパースされるだけの no-op です。`declare` フォーム全体は nil に評価され、引数は評価も検証もされないため、標準のあらゆる宣言（`ignore`、`ignorable`、`type`、`optimize`、`inline`、`special` など）を本体のどこにでも書けます。他の Common Lisp 処理系向けに書かれたソースコードを変更なしにロードするための機能であり、どの宣言にも効果はありません。

```lisp
(let ((x 10))
  (declare (type integer x) (optimize (speed 3)))
  (* x 2)) ; => 20
```
