# rontolisp:futurep

`(rontolisp:futurep value)`

`value` が future — [`rontolisp:async-defun`](../special-forms/rontolisp-async-defun.md)
で定義した関数の呼び出し、[`rontolisp:fetch`](rontolisp-fetch.md)、
[`rontolisp:stream-read`](rontolisp-stream-read.md) が返す値 — なら `t`、
それ以外なら `nil` を返します。

```lisp
(rontolisp:async-defun f () 1)
(rontolisp:futurep (f))    ; => T
(rontolisp:futurep 42)     ; => NIL
```

future は不透明な値です: リーダ構文はなく、`#<FUTURE>` と印字されます。
確定値は [`rontolisp:await`](../special-forms/rontolisp-await.md) で取得します。

```lisp
(f)   ; => #<FUTURE>
```
