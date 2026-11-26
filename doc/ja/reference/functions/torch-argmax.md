# torch:argmax

`(torch:argmax a &key axis)`

微分不可能な演算です。最大要素のインデックス (`linalg:argmax`) をテンソルではなく生の値として返します。ベクトルなら整数インデックス、`:axis` 付きならスライスごとのインデックス配列です。結果は [`torch:gather`](torch-gather.md) / [`torch:index-select`](torch-index-select.md) に渡せ、貪欲デコードはこの値を直接読みます。

```lisp
(torch:argmax (torch:tensor '(1.0 5.0 3.0)))                    ; => 1
(torch:argmax (torch:tensor '((1.0 4.0) (3.0 2.0))) :axis 1)     ; => #d(1.0 0.0)
```
