# rontolisp:make-mutex

`(rontolisp:make-mutex)`

新しい相互排他ロックを**不透明なハンドル**として返します。渡してよいのは
[`rontolisp:with-mutex`](../macros/rontolisp-with-mutex.md)(または
[`rontolisp:mutex-acquire`](rontolisp-mutex-acquire.md) /
[`rontolisp:mutex-release`](rontolisp-mutex-release.md))だけです。ハンドルの実体は
バックエンドごとに異なるため、表示したり、2 つを `<` で比較したり、算術演算を行ったり
することは移植可能ではありません。ハンドル自身との `eq`/`eql` 比較は機能します。

rontolisp は実際に並行実行されます —
[`rontolisp:http-handler`](rontolisp-http-handler.md)
はインタプリタと JVM バックエンドでリクエストごとに 1 つの仮想スレッドを立てます — ロックが
必要なのはそのためです。両方の WASM バックエンドではスレッドは常に 1 つなので、これらの
プリミティブは no-op になります。同じソースがどこでも動きます。

```lisp
(let ((m (rontolisp:make-mutex)))
  (rontolisp:with-mutex (m) :guarded))  ; => :GUARDED
```

ロックは**再入可能**です。保持しているスレッドは再度獲得でき、獲得した回数だけ解放する
必要があります。

## 制限

- Lisp からスレッドを生成する手段はありません。並行性はランタイム(サーブされる
  リクエストごとに 1 つの仮想スレッド)に由来します。
- ハンドルは不透明でバックエンド依存です — 表示や順序付けをしないでください。
- マクロおよびこれらのプリミティブは関数値を持ちません: `#'rontolisp:make-mutex` は
  エラーです。
