# rontolisp:random-bytes

`(rontolisp:random-bytes count)`

暗号論的に強い乱数バイト `count` 個 (各要素は 0〜255 の整数) からなるベクタを返します。通常の擬似乱数生成器である [`random`](random.md) とは異なり、すべてのバイトはプラットフォームの暗号用エントロピー源に由来します。インタプリタと JVM では `java.security.SecureRandom`、両方の WASM バックエンドでは WASI の `random_get` ホスト関数 (`--component` では `wasi:random`) です。ノンス、ソルト、セッション識別子の生成に適しているのはこのためです。`--no-wasi` モジュールにはエントロピー源がないため、ビルドに `--host-random` を渡さない限りこの呼び出しはシグナルします([乱数ガイド](../../guides/random.md))。

```lisp
(length (rontolisp:random-bytes 16)) ; => 16
```

値は呼び出しごとに変わるため、ここでは長さのみを示します。
