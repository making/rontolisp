# invoke-debugger

`(invoke-debugger condition)`

`condition` を通知し、決して戻りません。どのバックエンドにも入り込める対話的デバッガは
無いため、「デバッガに入り、そこで中断を指示された」ことに相当するのは、その条件が
呼び出し元の外側に確立されたハンドラへ届くこと、そしてハンドラが無ければ標準エラーへの
レポートと非ゼロ終了です。[`rontolisp --swank`](../../getting-started/swank.md) の下では、
どのハンドラも処理しない条件は代わりに SLIME のデバッガを開きます。

`*debugger-hook*` が `nil` でなければ、その変数を `nil` に束縛したうえで、条件と変数自身の値を
渡して先に呼び出します。これを参照するのは `invoke-debugger` だけで、どのハンドラも処理しない
条件はフックを呼ばずにプログラムを終了させます。

```lisp
(handler-case (invoke-debugger (make-condition 'simple-error :format-control "boom"))
  (error (e) (princ-to-string e))) ; => "boom"

(block found
  (let ((*debugger-hook* (lambda (c hook) (declare (ignore hook)) (return-from found (princ-to-string c)))))
    (invoke-debugger (make-condition 'simple-error :format-control "boom")))) ; => "boom"
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。`error` の上に書かれた rontolisp ソースによる
1 つの定義があります。
