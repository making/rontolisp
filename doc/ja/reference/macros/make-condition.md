# make-condition

`(make-condition type &key format-control format-arguments ...)`

簡易版: コンディションシステムがないため構築すべきコンディションオブジェクトは存在せず、展開は `:format-control` の値(なければ型名を含む汎用メッセージ)になります。これはまさに `error` が意図したメッセージでシグナルするのに必要なものです。`:format-arguments` などその他のオプションは破棄されます。

```lisp
(make-condition 'my-error :format-control "something failed") ; => "something failed"
```

```console
> (error (make-condition 'my-error :format-control "something failed"))
Error: something failed
```
