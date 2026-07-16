# rontolisp:promisep

`(rontolisp:promisep value)`

`value` がプロミス — [`rontolisp:fetch`](rontolisp-fetch.md) または
[`rontolisp:then`](rontolisp-then.md) が返す値 — なら `t`、それ以外なら `nil`
を返します。

```lisp
(rontolisp:promisep (rontolisp:then 1 (lambda (x) x)))   ; => t
(rontolisp:promisep 42)                                   ; => nil
```

プロミスは不透明な値です: リーダ構文はなく、`#<FUTURE>` と印字されます。

```lisp
(rontolisp:then 1 (lambda (x) x))   ; => #<FUTURE>
```

## バックエンドのサポート

[`rontolisp:await`](rontolisp-await.md) や
[`rontolisp:then`](rontolisp-then.md) と同様に、すべてのバックエンド・すべての
WASM モード (Preview 1 を含む) で動作します。
