# rontolisp:wait-for

`(rontolisp:wait-for milliseconds)`

指定したミリ秒数 (非負整数) 後に `nil` で確定する future を返します。
タイマーは即座に開始されるため、await しても遅延するのは *await している*
コードだけで、他の非同期処理は動き続けます。ブロッキングで秒単位の
`cl:sleep` に対する、非同期版の対応物です。

```lisp
(rontolisp:await (rontolisp:wait-for 100))   ; => nil
```

タイマーは並行に走ります: 同時に開始した 2 つの future は開始順ではなく
遅延順に確定し、両方を await しても合計ではなく長い方の遅延程度しか
かかりません。

```lisp
(rontolisp:async-defun delayed (ms tag)
  (rontolisp:await (rontolisp:wait-for ms))
  tag)
(let ((slow (delayed 200 "slow"))
      (fast (delayed 20 "fast")))
  (list (rontolisp:await fast) (rontolisp:await slow)))   ; => ("fast" "slow")
```

## バックエンドのサポート

`rontolisp:wait-for` はインタプリタ、JVM バックエンド、WASM `--component`
に存在します (`--component` ではホストタイマー
`wasi:clocks/monotonic-clock@0.3.0` の `wait-for` に低下され、イベント
ループが解決する保留中の future になるため、タイマーはそこでも本当に
並行します)。Preview 1 WASM はコンパイル時に拒否します (ホストタイマー
がありません)。
