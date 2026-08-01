# nsubstitute-if-not

`(nsubstitute-if-not new predicate list &key key)`

[`substitute-if-not`](substitute-if-not.md) の破壊的な版です。述語が*満たさない*と判定した要素を持つコンスの `car` をその場で書き換え、(変更されうる) 元のリストを返します。リスト専用です。コンスセル再利用の意味論は [`nsubstitute-if`](nsubstitute-if.md) と共通です。

```lisp
(nsubstitute-if-not 0 #'oddp (list 1 2 3 4 5)) ; => (1 0 3 0 5)
```
