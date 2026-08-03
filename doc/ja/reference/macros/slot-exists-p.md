# slot-exists-p

`(slot-exists-p instance 'slot-name)`

インスタンスのクラスがその名前のスロットを宣言しているかどうかを、束縛の有無に関係なく返します。未束縛のスロットは存在します([`slot-boundp`](slot-boundp.md) が束縛の判定)。宣言されていないスロットは存在せず、インスタンスでない値には `nil` を返します。スロット名はどのバックエンドでも実行時に計算されたシンボルで構いません。

```lisp
(defclass se-point () ((x :initarg :x) (y :initform 0)))
(let ((p (make-instance 'se-point)))
  (list (slot-exists-p p 'x) (slot-exists-p p 'z) (slot-exists-p 42 'x))) ; => (T NIL NIL)
```
