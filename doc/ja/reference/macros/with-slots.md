# with-slots

`(with-slots (slot-or-pair...) instance body...)`

CLOS サブセットインスタンス([`defclass`](../special-forms/defclass.md) / [`define-condition`](define-condition.md))のスロット名を、本体のためのシンボルマクロ的な「場所」として束縛します: 各エントリはスロット名、または `var` をスロット `slot` に束縛する `(var slot)` ペアです。インスタンスフォームは一度だけ評価されます。読み取りはスロットを参照し、束縛名への [`setf`](setf.md)/`push`/`incf` はスロットへ書き戻されます(置換は本体のテキストに対して行われるため、スロット変数をシャドウする内側の束縛も置換されます)。

lite: 本体の中で実行時に「生成される」コード(スロット名に言及する `macrolet` テンプレートなど)は、エントリ時点のスロット値を保持するフォールバック束縛を通じて名前を解決します — 読み取りは機能しますが、そのような生成コードからの書き込みはローカルコピーのみを更新します。

`with-slots` は束縛するだけで、エントリ時にスロットを読み取ることはありません。そのため `:initform` を持たないスロットに代入するだけの本体も動作し、上記のフォールバック束縛はそのようなスロットに対して `nil` を保持します。本体が実際に行う読み取りは、これまでどおり `unbound-slot` をシグナルします。

```lisp
(defclass buffered () ((buffer)))
(let ((b (make-instance 'buffered)))
  (with-slots (buffer) b (setf buffer (list 1 2)))
  (slot-value b 'buffer)) ; => (1 2)
```

```lisp
(defclass ws-point () ((x :initarg :x) (y :initarg :y)))
(with-slots (x (why y)) (make-instance 'ws-point :x 3 :y 4) (list x why)) ; => (3 4)
```

```lisp
(defclass counter () ((n :initform 0)))
(let ((c (make-instance 'counter)))
  (with-slots (n) c (incf n) (incf n))
  (slot-value c 'n)) ; => 2
```
