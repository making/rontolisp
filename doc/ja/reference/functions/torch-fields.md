# torch:fields

`(torch:fields module)`

モジュールまたはオプティマイザのフィールド plist 全体を、新しいリストとして
返します。登録順のフィールド名と、それぞれに続く値です。
[`torch:field`](torch-field.md) が名前で 1 つのフィールドを読むのに対し、
こちらはモジュールツリーをパッケージの外から走査可能にするものです。

`nn.Module.apply` と `nn.Module.named_parameters` に対応するものがないのは、
走査をこの plist と [`torch:module-kind`](torch-module-kind.md) — 各レイヤーが
「何であるか」 — で書くためです。PyTorch のドット区切りのパラメータ名より正確
です。名前に `'ln'` が含まれるかで LayerNorm のパラメータを選ぶと、誰かが
`blend` と名付けたレイヤーまで選んでしまいます。

リストの背骨は新しいので、結果に cons してもモジュールは壊れません。値の方は
生きたパラメータとサブモジュールであり、差し替えは従来どおり
[`torch:set-field`](torch-set-field.md) で行います。

```lisp
(defparameter *layer* (torch:linear 3 2))
(do ((p (torch:fields *layer*) (cddr p)) (acc nil (cons (car p) acc)))
    ((null p) (reverse acc)))                                   ; => (:WEIGHT :BIAS)
(eq (nth 1 (torch:fields *layer*)) (torch:field *layer* :weight)) ; => T
```
