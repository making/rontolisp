# rontolisp:then*

`(rontolisp:then* future &rest functions)`

`rontolisp:then` の可変長チェーン糖衣: 値を各 `function` に順に通し、
手で連ねたら生じる括弧の入れ子を回避します。各関数は 1 つ前の段の
(必要に応じて `await` で平坦化された) 確定値を受け取り、future を
返す段は次段の読み取り時に平坦化されます。コールバックが 0 個の
場合は入力 future をそのまま返します (仕様上の縮退恒等関数)。

```lisp
(rontolisp:async-defun produce () 40)
(rontolisp:await (rontolisp:then* (produce) #'1+ #'1+))   ; => 42
```

第 1 引数が future 以外の場合は `type-error` になります。

命名について: これは `then` + `*` (CL の慣習で 2 引数演算子の可変長
版) であり、Java の `CompletableFuture` の `thenCompose`/`thenApply`
とは違います。ここでは `await` が読み取り時に平坦化するので、両者の
区別は同じ形に潰れます。

## バックエンドのサポート

[`rontolisp:then`](rontolisp-then.md) と同じ: インタプリタ、JVM、
WASM `--component`。Preview 1 WASM は成功パスのみ。`--no-gc` は
コンパイル時に拒否します。
