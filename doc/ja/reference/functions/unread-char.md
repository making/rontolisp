# unread-char

`(unread-char character &optional stream)`

直前に読み取った文字である `character` を押し戻し、次の読み取りが再びその文字を返すようにして `nil` を返します。サポートされるのは [Gray ストリーム](../../guides/gray-streams.md)のインスタンスだけです。`rontolisp:stream-unread-char` にディスパッチし、その既定メソッドはプロトコル自身が持つ 1 文字ぶんの押し戻しスロットに文字を保管します。自前でソースを巻き戻せるクラスは、代わりにこのジェネリックを定義します。ストリーム**ハンドル** -- ファイル、文字列入力ストリーム、ソケット -- はどのバックエンドにも押し戻しを持たないため、そちらの経路では文字を黙って捨てずに通知します。

```lisp
(defclass uc-source (rontolisp:fundamental-character-input-stream)
  ((text :initarg :text) (pos :initform 0)))
(defmethod rontolisp:stream-read-char ((s uc-source))
  (let ((text (slot-value s 'text)) (pos (slot-value s 'pos)))
    (if (>= pos (length text))
        :eof
        (progn (setf (slot-value s 'pos) (+ pos 1)) (char text pos)))))
(let* ((s (make-instance 'uc-source :text "ab"))
       (c (read-char s)))
  (unread-char c s)
  (list c (read-char s) (read-char s))) ; => (#\a #\a #\b)
```
