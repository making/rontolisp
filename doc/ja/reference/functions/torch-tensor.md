# torch:tensor

`(torch:tensor x &key requires-grad element-type)`

数値 (ランク 0 のスカラーテンソル)、リスト (フラット、または同じ長さの行のリスト)、配列、linalg 配列、または別のテンソル (データをコピーします) から、新しい葉テンソル -- `torch` パッケージの微分可能な値 -- を返します。`:requires-grad t` を渡すと [`torch:backward`](torch-backward.md) が勾配を書き込むパラメータとして印を付け、`:element-type 'single-float` はパックド単精度 (`#f`) データを作ります。

テンソル自体を print すると生のレコードが表示されるため、例では [`torch:data`](torch-data.md) で読み戻します (保持するテープのクロージャには可搬な印字形式がありません)。

```lisp
(torch:data (torch:tensor '(1 2 3)))                              ; => #d(1.0 2.0 3.0)
(torch:data (torch:tensor 2.5))                                   ; => 2.5
(torch:data (torch:tensor #(1 2) :element-type 'single-float))    ; => #f(1.0 2.0)
(torch:requires-grad-p (torch:tensor '(1.0) :requires-grad t))    ; => T
```
