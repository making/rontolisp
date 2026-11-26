# environment

`(environment import-set ...)`

`eval` に渡す環境指定子を返します。インポートセットはフロントエンドが知っているライブラリと照合され、未知のライブラリはエラーです。結果はすべての指定子が指す唯一の大域環境（`#[environment]` と表示されます）なので、指定したライブラリ以外の名前も含みます。

```scheme
(environment '(scheme base)) ; => #[environment]
(eval '(if #t 'yes 'no) (environment '(scheme base))) ; => yes
```
