# rontolisp:async-lambda

`(rontolisp:async-lambda (params...) body...)`

[`rontolisp:async-defun`](rontolisp-async-defun.md) の無名版: 評価すると、呼び出しが future を返す関数値になります。パラメータリストは [`lambda`](lambda.md) と同じラムダリストキーワードをサポートし、本体は `async-defun` の本体と同じセマンティクスに従います — 呼び出し時に eager に開始され、[`rontolisp:await`](rontolisp-await.md) を使え、その値 (またはエラー) が返された future を確定させます。

```lisp
(rontolisp:await (funcall (rontolisp:async-lambda (x) (* x 2)) 21))   ; => 42
```

関数値なので他の関数と同様に受け渡しでき、呼び出しごとに新しい future を返します:

```lisp
(let ((double-later (rontolisp:async-lambda (x) (* x 2))))
  (rontolisp:futurep (funcall double-later 3)))   ; => t
```

## バックエンドのサポート

[`rontolisp:async-defun`](rontolisp-async-defun.md) と同一です: インタプリタと JVM では仮想スレッド、WASM `--component` ではコンポーネントの非同期タスク、Preview 1 WASM では即時完了、`--no-gc` ではコンパイルエラーです。
