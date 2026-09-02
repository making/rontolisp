# nsubstitute-if

`(nsubstitute-if new predicate list &key key)`

[`substitute-if`](substitute-if.md) の破壊的な版です。述語を満たす要素を持つコンスの `car` をその場で書き換え、(変更されうる) 元のリストを返します。コンスセルを再利用するため、そのリストへの他の参照も変更を観測します。ベクタや文字列にはその場で書き換えられるコンスセルがないため、`substitute-if` と同様に新しいシーケンスとして返ります。

```lisp
(nsubstitute-if 0 #'oddp (list 1 2 3 4 5)) ; => (0 2 0 4 0)
```

```lisp
(let* ((a (list 1 2 3)) (b a)) (nsubstitute-if 0 #'oddp a) b) ; => (0 2 0)
```

```lisp
(nsubstitute-if 0 #'oddp (vector 1 2 3)) ; => #(0 2 0)
```
