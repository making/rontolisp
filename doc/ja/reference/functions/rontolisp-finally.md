# rontolisp:finally

`(rontolisp:finally future thunk)`

入力の元の確定 (値でもエラーでも) を運ぶ新しい future を返し、future
がどちらの結末を生んでも 0 引数の `thunk` をちょうど 1 回実行します。
thunk の戻り値は捨てられます。thunk 自身がシグナルを上げた condition
は、保留中の結末を **置き換えます** (`unwind-protect` と同じ挙動)。

```lisp
(defvar *cleanup-log* nil)
(rontolisp:async-defun produce () 5)
(let ((v (rontolisp:await
           (rontolisp:finally (produce)
                              (lambda () (push :done *cleanup-log*))))))
  (list v (reverse *cleanup-log*)))   ; => (5 (:DONE))
```

呼び出し先から受け取った future の成功経路とエラー経路の両方で必ず
発火させたい後始末 (リソース解放、メトリクス記録、カウンタ減算など)
のために使います。

第 1 引数が future 以外の場合は `type-error` になります。

## バックエンドのサポート

[`rontolisp:then`](rontolisp-then.md) と同じ: インタプリタ、JVM、
WASM `--component`。Preview 1 WASM は成功パスのみ (エラー経路には
component バックエンドが提供する future 化されたエラー-at-await 契約
が必要です)。`--no-gc` はコンパイル時に拒否します。
