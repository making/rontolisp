# ffi:errno

`(ffi:errno)`

呼び出しスレッドの直前の [`ffi:call`](ffi-call.md) が残した `errno` の値を
返す。後から取りに行くのではなく呼び出し自身が捕捉するので、呼び出しとこの読み出しの
間に何が起きても上書きされず、スレッドごとに独立している。

```lisp
(progn
  (ffi:call (ffi:symbol (ffi:open) "open") :int '(:string :int) "/no/such/file" 0)
  (ffi:errno))
; => 2
```

`2` は `ENOENT`。他の処理系では補助ライブラリが要るが、ここでは捕捉がすべての呼び出しに組み込まれている。
