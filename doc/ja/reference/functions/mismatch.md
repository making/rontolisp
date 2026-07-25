# mismatch

`(mismatch sequence1 sequence2 &key test key start1 end1 start2 end2 from-end)`

2 つの (範囲を限定した) シーケンスが最初に異なる位置の **`sequence1` 上のインデックス**を返します。要素がすべて一致する場合は `nil` を返します。`:test` は要素の比較関数 (既定は `eql`)、`:key` は比較する値の選択関数です。`:start1`/`:end1`/`:start2`/`:end2` で各シーケンスの範囲を限定します。簡易実装として `:from-end` は受け付けますが走査は前方向のままなので、返るインデックスは前方向のものです。

```lisp
(list (mismatch "apple" "apricot") (mismatch '(1 2 3) '(1 2 3))) ; => (2 NIL)
```
