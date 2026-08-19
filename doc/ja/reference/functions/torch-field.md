# torch:field

`(torch:field module name)`

モジュールの指定フィールドの値を返します。`name` はフィールドのキーワードで、値はパラメータ・バッファ・サブモジュール・サブモジュールのリスト・単なるハイパーパラメータのいずれかです。該当フィールドがなければエラーを通知するため、名前の綴り間違いは静かに `NIL` になるのではなく明示的に失敗します。レイヤーの forward が自分のパラメータを読むのもこの関数です ([`torch:module`](torch-module.md) 参照)。

```lisp
(torch:shape (torch:field (torch:linear 3 2) :weight))  ; => (3 2)
(torch:shape (torch:field (torch:linear 3 2) :bias))    ; => (2)
```
