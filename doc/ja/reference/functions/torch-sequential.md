# torch:sequential

`(torch:sequential &rest layers)`

レイヤーの連鎖 (PyTorch の `nn.Sequential`) を返します。順伝播は引数を各要素に順番に通します。要素はモジュールでも**素の関数**でもよく、活性化関数は `(function torch:relu)` として入ります。活性化関数専用のモジュール型はありません。要素は `:layers` という単一のフィールドに入り、[`torch:parameters`](torch-parameters.md) がそのリストを走査するため、入れ子のパラメータもすべて到達可能です。

このパッケージではモジュールのリスト自体がどこでも有効なフィールド値なので、同じブロックを N 段重ねるのに `ModuleList` 型は要りません。自作の [`torch:module`](torch-module.md) のフィールドにリストを持たせれば走査が見つけます。

```lisp
(defparameter *net*
  (torch:sequential (torch:linear 4 8) (function torch:relu) (torch:linear 8 2)))
(torch:shape (torch:forward *net* (torch:tensor (linalg:zeros '(3 4))))) ; => (3 2)
(length (torch:parameters *net*))                                       ; => 4
```
