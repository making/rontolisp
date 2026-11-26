# ffi:alloc

`(ffi:alloc size)`

外部メモリを `size` バイト確保し、そのポインタを返す。これは `malloc`
であり、確保したメモリは Lisp のどのスコープよりも長生きし、
[`ffi:free`](ffi-free.md) でのみ解放される --- CFFI 自身の契約と同じ。

```lisp
(let ((p (ffi:alloc 8)))
  (ffi:poke p :int 42)
  (prog1 (ffi:peek p :int) (ffi:free p)))
; => 42
```

書き込むまで内容は不定。この例では `prog1` で解放前に値を読み戻している。
