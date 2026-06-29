# function

`(function name)` or `#'name`

`name` を関数名前空間から検索し、対応する関数を第一級の値として返します。`#'name` は `(function name)` のリーダー省略記法です。引数は名前であり、評価される式ではありません。これは名前付き関数(または `lambda`)を取得して、`funcall`/`apply` や `mapcar` のような高階関数に渡すための方法です。Lisp-2 では裸のシンボルは変数名前空間を参照するため、この操作が必要になります。

```lisp
(funcall (function +) 2 3) ; => 5
```
