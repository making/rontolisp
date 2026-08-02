# intern

`(intern string)`

`string` を名前とするシンボルを返します(ケース変換なし)。rontolisp のシンボルは名前で比較され、独立したインターンテーブルはないため、結果はクォートされたリテラルを含め同名のどのシンボルとも `eq` になります。インタプリタでは名前は**カレントパッケージ**にインターンされます(Common Lisp の `*package*` の意味論): アクセス可能なシンボルはそのホームの綴りを保ち、未知の名前は `in-package` で選択中のパッケージのシンボルになります — これにより、マクロ時の `(intern (concatenate ...))` がそのファイル内のリテラルな `defun` と同じ関数を指せます。`(intern name :keyword)` はキーワードを作り、`(intern name package)` は任意のパッケージ指定子 — キーワード、文字列、変数に保持されたパッケージ値 — を受け付けます。存在しないパッケージはエラーになります。Common Lisp からの相違点: 第 2 の `status` 値はなく、コンパイル系バックエンドではパッケージ修飾付き `intern` は常にシングルコロンの外部綴りを生成するため、この方法でインターンした未エクスポートのシンボルはダブルコロンのリテラルと `eq` になりません(関数としての呼び出しは動作します)。

```lisp
(intern "hello") ; => hello
```

```lisp
(eq (intern "foo") 'foo) ; => NIL
```

```lisp
(defvar *level* 7)
(symbol-value (intern "*LEVEL*")) ; => 7
```

```lisp
(defpackage :evt (:use :cl) (:export :fire))
(in-package :evt)
(defun fire (x) (list :fired x))
(in-package :cl-user)
(funcall (intern "FIRE" :evt) 7) ; => (:FIRED 7)
```
