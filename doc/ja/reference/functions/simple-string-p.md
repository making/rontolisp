# simple-string-p

`(simple-string-p object)`

`object` が simple な文字列 — フィルポインタを持たず、`:adjustable` でもなく、displaced でもない文字列 — であれば真を返します。リテラル、[`make-string`](make-string.md) の結果、[`copy-seq`](copy-seq.md) の結果は simple です。`:fill-pointer` / `:adjustable t` で作った文字ベクタと displaced な文字列ビューは simple ではありません。答えは `(typep object 'simple-string)` と完全に一致するので、ポータブルな「`simple-string-p` でなければ coerce する」イディオムはコピーが必要な文字列だけを正確にコピーします。

```lisp
(simple-string-p "abc") ; => T
```

```lisp
(simple-string-p (make-array 4 :element-type 'character :fill-pointer 0)) ; => NIL
```

```lisp
(simple-string-p 42) ; => NIL
```
