# restart-bind

`(restart-bind ((restart-name function [:report-function r]...)...) body...)`

[`restart-case`](restart-case.md) のプリミティブ版です: 束縛ごとに 1 つのリスタートレコードを動的リスタートスタックに積んで `body...` を評価しますが、起動されたリスタートは**起動点で `function` を呼び出します** — `restart-bind` フレームへの非局所移動はなく、`invoke-restart` は関数の返り値をそのまま返します(CL のセマンティクス。制御を移したければ関数自身が行います)。関数は起動引数を受け取ります(WASM バックエンドでは最大 10 個)。`:report-function` は受理され保存されます。その他の束縛ごとのキーワードオプションは受理された上で無視されます。`--no-gc` を除くすべてのバックエンドでサポートされます。

```lisp
(let ((hit nil))
  (restart-bind ((poke (lambda (v) (setq hit v))))
    (invoke-restart 'poke 9)
    hit)) ; => 9
```
