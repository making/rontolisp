# sublis

`(sublis alist tree &key key test test-not)`

`tree` を複製し、`alist` のキーに一致する部分木をそのエントリの値で置き換えて返します。葉だけでなくコンスセル自体も降下前に検索されるため、枝ごと置き換えられます。既定のテストは `eql` で、`:key` は検索前に部分木へ適用されます。

破壊的な `nsublis` は提供していません。

```lisp
(sublis '((a . 1) (b . 2)) '(a (b c) . a)) ; => (1 (2 C) . 1)
```
