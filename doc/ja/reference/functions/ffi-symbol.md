# ffi:symbol

`(ffi:symbol library "name")`

開いているライブラリ内のシンボルのアドレスを外部ポインタとして返す。
そのライブラリに無ければ `nil`。`library` は [`ffi:open`](ffi-open.md) が返した
ハンドル。

```lisp
(ffi:pointerp (ffi:symbol (ffi:open) "strlen"))
; => T
```

存在しないシンボルはエラーではなく `nil` になる。省略可能なエントリポイントをバインディングが探索できるのはこのため。
