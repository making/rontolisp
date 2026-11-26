# array-in-bounds-p

`(array-in-bounds-p array &rest subscripts)`

すべての添字が `array` の有効なインデックスなら真、そうでなければ `nil` を返します。シグナルは送出しません。非配列、階数と合わない添字の個数、負や範囲外の添字はすべて `nil` です。文字列も数えます（階数 1 の文字配列のため）。添字の照合対象はフィルポインタではなく次元です。

```lisp
(array-in-bounds-p (make-array '(2 3)) 1 2) ; => T
```

```lisp
(array-in-bounds-p (make-array '(2 3)) 2 0) ; => NIL
```

```lisp
(array-in-bounds-p "abc" 3) ; => NIL
```
