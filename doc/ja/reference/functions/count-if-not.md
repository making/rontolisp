# count-if-not

`(count-if-not predicate sequence &key key start end from-end)`

`predicate` を満たさ**ない** `sequence` の要素数を返します(`count-if` の補集合)。シーケンスはリスト・ベクタ・文字列のいずれでも構いません。`:key` は述語に渡す値を選び、`:start`/`:end` は走査範囲を限定します。`:from-end` は受け付けますが結果は変わりません(述語の呼び出し順序が変わるだけで、個数は変わらないため)。

```lisp
(count-if-not #'evenp '(1 2 3 4 5)) ; => 3
```

```lisp
(count-if-not #'alpha-char-p "ab1c2") ; => 2
```

```lisp
(count-if-not #'oddp '((1) (2) (3)) :key #'car) ; => 1
```
