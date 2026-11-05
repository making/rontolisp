# mismatch

`(mismatch sequence1 sequence2 &key test test-not key start1 end1 start2 end2 from-end)`

2 つの (範囲を限定した) シーケンスが最初に異なる位置の **`sequence1` 上のインデックス**を返します。要素がすべて一致する場合は `nil` を返します。`:test` は要素の比較関数 (既定は `eql`)、`:test-not` はその逆、`:key` は比較する値の選択関数です。`:start1`/`:end1`/`:start2`/`:end2` で各シーケンスの範囲を限定し、nil の `:end1`/`:end2` は長さを意味します。負の値、整数でない値（nil の `:start1`/`:start2` を含む）、シーケンスの長さを超える値、終端より後ろの開始位置は、同じ範囲に対して [`subseq`](subseq.md) が通知するのと同じ `type-error` を通知します。`:from-end` を指定すると右から走査し、異なる位置のうち最も右のインデックスに 1 を足した値を返します。

```lisp
(list (mismatch "apple" "apricot") (mismatch '(1 2 3) '(1 2 3))) ; => (2 NIL)
(mismatch "xab" "yab" :from-end t) ; => 1
```
