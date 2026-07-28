# with-accessors

`(with-accessors ((var accessor)...) instance body...)`

各 `var` を、本体中で `(accessor instance)` を表すシンボルマクロ的な「場所」として束縛します — [`with-slots`](with-slots.md) のアクセサ呼び出し版です。インスタンスフォームは一度だけ評価されます。読み取りはアクセサを呼び出し、束縛名への [`setf`](setf.md)/`push`/`incf` はアクセサの `setf` 場所を通して書き込まれるため、アクセサだけを公開しているクラスに `slot-value` は不要です。

lite: 置換は本体のテキストに対して行われます（クォートされたデータはスキップされます）。名前をシャドウする内側の束縛も置換されます。

```lisp
(defclass wa-point () ((x :initarg :x :accessor wa-x) (y :initarg :y :accessor wa-y)))
(let ((p (make-instance 'wa-point :x 3 :y 4)))
  (with-accessors ((x wa-x) (y wa-y)) p
    (setf x (+ x y))
    (list x y))) ; => (7 4)
```
