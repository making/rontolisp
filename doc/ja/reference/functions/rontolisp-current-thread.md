# rontolisp:current-thread

`(rontolisp:current-thread)`

呼び出したスレッド自身の**不透明なスレッドハンドル**を返します。
[`rontolisp:make-thread`](rontolisp-make-thread.md) で生成したスレッドに限らず、
メインスレッドやリクエスト処理スレッドを含む任意のスレッドで動作します。また
`eq` 安定です: 同じスレッドから繰り返し呼んでも同じハンドルが返るため、`eq`
ハッシュテーブルのキーに使えます。この性質は `bt2:current-thread` シム (そして
それを介した `dbi` のスレッド別コネクションキャッシュ) が依存しているものです。
[`rontolisp:make-thread`](rontolisp-make-thread.md) で生成したスレッドの中では、
`make-thread` が返したハンドルそのものです。

```lisp
(let ((h (rontolisp:current-thread)))
  (list (rontolisp:threadp h)
        (eq h (rontolisp:current-thread))
        (rontolisp:thread-alive-p h))) ; => (T T T)
```

スレッドはインタープリタと JVM バックエンドで実動します。両 WASM バックエンドは
構造上シングルスレッドであり、この関数をコンパイルしません。
`bordeaux-threads`/`bt2` シムの `current-thread` は呼び出し時に明確なエラーを
送出します。

## 制限事項

- 自分自身のハンドルを [`rontolisp:join-thread`](rontolisp-join-thread.md) に渡すと
  永久にブロックします (自分自身への join は upstream でも同じです)。
