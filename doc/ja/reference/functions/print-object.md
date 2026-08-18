# print-object

`(print-object object stream)`

プリンタが参照するジェネリック関数です。[`defclass`](../special-forms/defclass.md) クラスや [`defstruct`](../special-forms/defstruct.md) 型に対してこの関数の [`defmethod`](../special-forms/defmethod.md) を定義すると、`print`、`princ`、`prin1`、`princ-to-string`、`prin1-to-string`、および [`format`](../macros/format.md) の `~A`/`~S` が、その型のインスタンスを組み込みの `#S(...)` / `#<...>` 構文ではなくそのメソッドを通して出力するようになります。メソッドは渡されたストリームに書き込み、戻り値は無視されます。本体には通常 [`print-unreadable-object`](../macros/print-unreadable-object.md) を使います。

**プリンタ**がこのジェネリック関数を経由するのは、いずれかのメソッドが特定化している型に対してだけです。どのメソッドも対象としていない型は組み込みの表示のままで、`print-object` メソッドを定義していないプログラムの出力は従来どおりです。

`(print-object object stream)` を**直接**呼ぶのは別の話で、対象にユーザーメソッドがあってもなくても常に動作します。Common Lisp はすべてのオブジェクトにシステムメソッドを用意しており、rontolisp も同様だからです。オブジェクトの生の表現を書き (`prin1` と `princ` のどちらの綴りかは `*print-escape*` が決めます)、そのオブジェクトを返します。したがって 1 つのクラスにメソッドを定義しても残りのプリンタが失われることはなく、最も特定的でないメソッドからの `(call-next-method)` もここへ到達します。

```lisp
(with-output-to-string (s) (print-object 42 s)) ; => "42"
```

直接呼び出しには制限が 1 つあります。システムメソッドは生の表現を書くため、渡された値の内側にあるインスタンスについてはそのメソッドが参照されません。`print`/`princ`/`prin1` 経由の印字であれば、後述のとおり内側までたどります。

[`defstruct`](../special-forms/defstruct.md) の `(:print-object fn)` / `(:print-function fn)` オプションはこのジェネリック関数に対するメソッドそのものです。したがって両者は交換可能で、同じ型に後から `defmethod` を書けばオプションが定義したメソッドを置き換えます。

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

メソッドはインスタンスが「どこにあるか」によらず参照されます。印字オペレータに直接渡された値だけでなく、印字されるリストやベクタの要素も — 深さを問わず、ドット対の末尾も含めて — メソッドを通ります。

```lisp
(defstruct po-node value)
(defmethod print-object ((n po-node) stream)
  (print-unreadable-object (n stream :type t)
    (princ (po-node-value n) stream)))
(list (princ-to-string (make-po-node :value 42))
      (princ-to-string (list (make-po-node :value 7) (vector (make-po-node :value 8)))))
; => ("#<PO-NODE 42>" "(#<PO-NODE 7> #(#<PO-NODE 8>))")
```

lite: このように走査されるコンテナはリストと汎用の1次元ベクタです。構造体やクラスのスロット、ハッシュテーブル、1次元以外の配列、浮動小数点数専用ベクタに格納された値はそのコンテナ自身のプリンタが出力するため、その型のメソッドは適用されません。
