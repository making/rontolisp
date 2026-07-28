# rontolisp:mutex-acquire

`(rontolisp:mutex-acquire mutex)`

呼び出しスレッドが `mutex`([`rontolisp:make-mutex`](rontolisp-make-mutex.md) が作成)を
保持するまでブロックし、その mutex を返します。通常は
[`rontolisp:with-mutex`](../macros/rontolisp-with-mutex.md) を使ってください。ボディが
エラーをシグナルして脱出した場合でもロックを解放します。対応する
[`rontolisp:mutex-release`](rontolisp-mutex-release.md) がスキップされた素の
`mutex-acquire` は、ロックを永久に保持したままにします。

ロックは再入可能なので、すでに保持しているスレッドは即座に再獲得でき、獲得回数分だけ解放
する必要があります。両方の WASM バックエンドではスレッドは 1 つだけなので、これは引数を
返すだけの no-op です。

```lisp
(let ((m (rontolisp:make-mutex)))
  (rontolisp:mutex-acquire m)
  (unwind-protect :critical
    (rontolisp:mutex-release m)))  ; => :CRITICAL
```

## 制限

- 非ブロッキング獲得やタイムアウト付き獲得はありません。
- mutex ハンドルでない値はエラーです。
