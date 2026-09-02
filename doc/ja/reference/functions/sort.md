# sort

`(sort sequence predicate)`

`predicate` を使って `sequence` をソートします。`predicate` は 2 引数の比較関数で、第 1 引数が第 2 引数より前に来るべきときに非 nil を返します。リストは破壊的にソートされ、コンスセルはその場で並べ替えられるため、元の変数ではなく戻り値を使用してください。ベクタや、プログラムが作った (リテラルではない) 文字列も同様にその場でソートされ、同じオブジェクトとして返ります -- フィルポインタやアジャスタブルなものは、そのフィルポインタとアジャスタブルフラグを保持します。プログラムテキストのリテラル文字列はその場で書き換えられないため、ソートすると新しい文字列が返ります。いずれの場合も、元の変数ではなく戻り値を使用してください。`predicate` で等しいとみなされる要素の相対的な順序は未規定です。順序が重要な場合は [`stable-sort`](stable-sort.md) を使用してください。

```lisp
(sort (list 3 1 2) #'<) ; => (1 2 3)
```

```lisp
(sort "cab" #'char<) ; => "abc"
```

```lisp
(let ((v (make-array 3 :adjustable t :fill-pointer 3 :initial-contents '(3 1 2))))
  (let ((s (sort v #'<)))
    (list (fill-pointer s) (eq v s)))) ; => (3 T)
```
