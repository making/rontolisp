# torch:module-kind

`(torch:module-kind module)`

モジュールの kind キーワード ([`torch:module`](torch-module.md) に渡したもの) を返します。組み込みレイヤーではそれぞれ `:linear` / `:embedding` / `:sequential` / `:layer-norm` / `:dropout` です。

```lisp
(torch:module-kind (torch:linear 2 2))  ; => :LINEAR
(torch:module-kind (torch:sequential))  ; => :SEQUENTIAL
```
