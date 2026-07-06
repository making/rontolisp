# setf

`(setf place value [place2 value2 ...])`

汎用的な代入です。`value` を `place` で指定した場所に格納し、その値を返します。単純な変数のほかに、サポートされる place としてリストのアクセサ `car`、`cdr`、`nth`、`first` から `fourth`、`rest`、および `caXXXr` の合成、さらに `elt` (実行時にリスト/配列をディスパッチします。文字列は不変のままです) があり、既存の構造の特定のスロットをその場で変更できます。適切なプリミティブな変更操作（`rplaca`／`rplacd` など）に展開されます。place の部分フォームは値より先に評価されるため、末尾収集イディオム `(setf (cdr tail) (setf tail (list x)))` は古い tail に連結します。

```lisp
(let ((x (list 1 2 3))) (setf (second x) 99) x) ; => (1 99 3)
```

複数の place/value ペアは逐次的に代入され (後のペアは前のペアの効果を参照できます)、最後の値が返されます。

```lisp
(let ((x (list 1 2 3))) (setf (car x) 9 (second x) 8) x) ; => (9 8 3)
```

組み込みの place のほかに、`defstruct` のアクセサ、CLOS の `:accessor`、そしてユーザー定義の *setf 関数* (`(defun (setf name) ...)`) も place になります。`(setf (name arg...) value)` は新しい値を先頭にして書き込み関数を呼び出します。setf 関数の定義については [defun](../special-forms/defun.md) を参照してください。

```lisp
(defvar *mode* :xml)
(defun (setf my-mode) (m) (setq *mode* m))
(setf (my-mode) :html5)
*mode* ; => :html5
```
