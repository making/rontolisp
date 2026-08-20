# torch:module

`(torch:module kind fields forward-fn)`

新しいモジュール、すなわち `torch` パッケージにおけるパラメータを保持し合成可能なオブジェクトを返します。`kind` はレイヤーを表すキーワード、`fields` はパラメータ・バッファ・サブモジュール・ハイパーパラメータのすべてを保持する**キーワード**/値のプロパティリスト、`forward-fn` は [`torch:forward`](torch-forward.md) が `(funcall forward-fn module args...)` として適用する関数です。

fields プロパティリストがモジュールのパラメータ登録そのものです。[`torch:parameters`](torch-parameters.md) がこれを走査するため、レイヤーの forward はクロージャに閉じ込めた変数ではなく [`torch:field`](torch-field.md) でパラメータを読み戻さなければなりません。`requires-grad` でないテンソルを持つフィールドはバッファ扱いで、走査からは除かれます。組み込みレイヤー ([`torch:linear`](torch-linear.md) など) はこの関数の普通の呼び出し元です。

```lisp
(defparameter *scale*
  (torch:module :scale (list :gain (torch:parameter '(2.0 3.0)))
                (lambda (self x) (torch:mul x (torch:field self :gain)))))
(torch:data (torch:forward *scale* (torch:tensor '(1.0 10.0)))) ; => #f(2.0 30.0)
(length (torch:parameters *scale*))                             ; => 1
```
