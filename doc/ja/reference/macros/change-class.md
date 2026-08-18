# change-class

`(change-class instance 'class-name initarg value ...)`

既存インスタンスのクラスを**その場で**変更し、そのインスタンスを返します: オブジェクトの同一性は保たれ（他のすべての参照も変更後のクラスを見ます）、新旧のクラスで共通するスロットの値はそのまま残り、新しいクラスが追加したスロットは `:initform` で埋められ、指定された initarg がその上に格納されます。クラス引数はクラス指定子です: リテラルのクォートされた名前、実行時のシンボル、クラスメタオブジェクトのいずれでもよく、`(change-class obj (find-class 'c))` も、変数に入ったクラス名も動作します。

対象外: 周辺の MOP プロトコル（`update-instance-for-different-class` は呼ばれません）。また、継承チェーンが無関係なクラス間で変更した場合、スロット値は名前ではなく位置で保持されます。

```lisp
(defclass cc-connection () ((host :initarg :host :accessor cc-host)))
(defclass cc-pooled (cc-connection) ((kind :initarg :kind :accessor cc-kind :initform :none)))
(let* ((c (make-instance 'cc-connection :host "db"))
       (alias c))
  (change-class c 'cc-pooled :kind :shared)
  (list (type-of alias) (cc-host alias) (cc-kind alias))) ; => (CC-POOLED "db" :SHARED)
```
