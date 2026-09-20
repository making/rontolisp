# intern

`(intern string [package])`

`package`(省略時は**カレントパッケージ**、Common Lisp の `*package*` の意味論)において `string` を名前とするシンボルを返します(ケース変換なし)。アクセス可能なシンボルはそのホームの綴りを保ち、未知の名前はそのパッケージのシンボルになり、**メンバーテーブルに記録されます**。以降の [`find-symbol`](find-symbol.md) はそれを返し、[`do-symbols`](../macros/do-symbols.md) はそれを列挙します。rontolisp のシンボルは名前で比較されるため、結果はクォートされたリテラルを含め同じ綴りのどのシンボルとも `eq` になります — これにより、`(in-package p)` の下でのマクロ時の `(intern (concatenate ...))` がそのファイル内のリテラルな `defun` と同じ関数を指せます。すでにパッケージ修飾を含む文字列 (`"LIB:WIDGET"`) は、その文字列全体を名前とする新しいシンボルではなく、その修飾が指すシンボルを返します。そのため実行時に得られた正規の綴り — 例えば [`type-of`](type-of.md) がクラスから読み取る型名 — はそのまま往復します。`(intern name :keyword)` はキーワードを作り、パッケージ引数は任意のパッケージ指定子 — キーワード、文字列、変数に保持されたパッケージ値 — を受け付けます。存在しないパッケージはエラーになります。`find-symbol` と同様、第 2 の値としてインターン**前**にその名前が持っていたアクセス可能性ステータスを返します(新しい名前では Common Lisp と同じく `nil`)。関数オブジェクト `#'intern` も同じ省略可能なパッケージを受け取り、同じ 2 つの値を返します。

Common Lisp からの相違点: コンパイル系バックエンドでは、読み込み/コンパイル時パッケージ(組み込み、またはコンパイルされたプログラムの `defpackage`)へのパッケージ修飾付き `intern` は常にシングルコロンの外部綴りを生成するため、この方法でインターンした未エクスポートのシンボルはダブルコロンのリテラルと `eq` になりません(関数としての呼び出しは動作します)。プログラムが [`make-package`](make-package.md) で作ったパッケージはすべてのバックエンドでメンバーテーブルを持ち、そこへの intern はインタプリタとまったく同じようにメンバーを記録します。

```lisp
(intern "hello") ; => |hello|
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

```lisp
(make-package :in-demo :use nil)
(find-symbol "FRESH" :in-demo) ; => NIL
(multiple-value-list (intern "FRESH" :in-demo)) ; => (IN-DEMO::FRESH NIL)
(multiple-value-list (intern "FRESH" :in-demo)) ; => (IN-DEMO::FRESH :INTERNAL)
(multiple-value-list (find-symbol "FRESH" :in-demo)) ; => (IN-DEMO::FRESH :INTERNAL)
```
