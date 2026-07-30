# print-object

`(print-object object stream)`

プリンタが参照するジェネリック関数です。[`defclass`](../special-forms/defclass.md) クラスや [`defstruct`](../special-forms/defstruct.md) 型に対してこの関数の [`defmethod`](../special-forms/defmethod.md) を定義すると、`print`、`princ`、`prin1`、`princ-to-string`、`prin1-to-string`、および [`format`](../macros/format.md) の `~A`/`~S` が、その型のインスタンスを組み込みの `#S(...)` / `#<...>` 構文ではなくそのメソッドを通して出力するようになります。メソッドは渡されたストリームに書き込み、戻り値は無視されます。本体には通常 [`print-unreadable-object`](../macros/print-unreadable-object.md) を使います。

システム提供のメソッドはありません: どのメソッドも特定化していない型は組み込みの表示のままで、`print-object` メソッドを定義していないプログラムの出力は従来どおりです。

組み込み表示のうち `#S(...)`/`#<...>` でない唯一の例外がコンディションです: `princ`/`princ-to-string`/`~A` はその [`:report`](../macros/define-condition.md) を出力します。コンディションクラスに `print-object` メソッドがあれば、どちらのエスケープモードでもそのレポートより優先されます。

`*print-escape*` は呼び出しの周りで束縛されます — `prin1`/`print`/`~S` では `t`、`princ`/`~A` では `nil` — ので、これを見て分岐する移植性のあるメソッド (可読形式か素の形式かを切り替える Common Lisp のイディオム) はここでも同じように動作します。`*print-readably*` は常に `nil` です。

```lisp
(defstruct po-uri text)
(defmethod print-object ((u po-uri) stream)
  (if (and (null *print-readably*) (null *print-escape*))
      (write-string (po-uri-text u) stream)
      (format stream "#<URI ~A>" (po-uri-text u))))
(list (princ-to-string (make-po-uri :text "/x")) (prin1-to-string (make-po-uri :text "/x")))
; => ("/x" "#<URI /x>")
```

lite: メソッドが参照されるのは印字オペレータに直接渡された値だけで、印字されるリストやベクタの内部にネストした値には適用されません — `(print (list obj))` の `obj` は組み込みの構文のままです。

```lisp
(defstruct po-node value)
(defmethod print-object ((n po-node) stream)
  (print-unreadable-object (n stream :type t)
    (princ (po-node-value n) stream)))
(princ-to-string (make-po-node :value 42)) ; => "#<PO-NODE 42>"
```
