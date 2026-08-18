# invoke-debugger

`(invoke-debugger condition)`

`condition` を通知し、決して戻りません。どのバックエンドにも入り込める対話的デバッガは
無いため、「デバッガに入り、そこで中断を指示された」ことに相当するのは、その条件が
呼び出し元の外側に確立されたハンドラへ届くこと、そしてハンドラが無ければ標準エラーへの
レポートと非ゼロ終了です。

```lisp
(handler-case (invoke-debugger (make-condition 'simple-error :format-control "boom"))
  (error (e) (princ-to-string e))) ; => "boom"
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。`error` の上に書かれた rontolisp ソースによる
1 つの定義があります。
