# ffi:poke

`(ffi:poke pointer type value &optional offset)`

`pointer` から `offset` バイトの位置に `value` を `type` として書き込み、
その値を返す。`:string` の書き込みは拒否される --- 文字列はどこかに確保する必要が
あり、この動詞は既に所有しているメモリへ書くためのもの。

```lisp
(let ((p (ffi:alloc 8)))
  (prog1 (ffi:poke p :double 1.5) (ffi:free p)))
; => 1.5
```

より狭い型として書いた整数は、C の代入と同じく下位ビットだけが残る。
