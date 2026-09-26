# find-symbol

`(find-symbol string [package])`

[`intern`](intern.md) と似ていますが新しく作りません。`package`(省略時はカレントパッケージ)がその名前で**アクセス可能**にしているシンボルを返し、なければ nil を返します。アクセス可能とは、パッケージに**存在する**シンボル([`intern`](intern.md)・[`export`](export.md)・[`import`](import.md)・[`shadowing-import`](shadowing-import.md)・[`shadow`](shadow.md) がメンバーテーブルに記録したもの、あるいはそのパッケージの下で行われた定義 -- `defun` はインターンです)、use しているパッケージから**継承した**シンボル(そのパッケージのエクスポート)、`cl` を通じて届く標準名、キーワードのいずれかです。ソース中でそのパッケージの下に読まれただけのシンボルは記録されず、[`unintern`](unintern.md) が取り除いた名前はもはやパッケージ自身のものではありません。パッケージを指さない指定子(`nil` を含む)は、Common Lisp と同じく [`package-error-package`](package-error-package.md) が指定子である `package-error` を通知します -- オプションのシステムを調べるときは先に [`find-package`](find-package.md) で確かめます。

Common Lisp との差異: コンパイルバックエンド(JVM/WASM)で読み込み/コンパイル時パッケージについて `nil` を返せるのは**リテラル**文字列のときだけです -- 判定はコンパイル時の視界(cl シンボルとプログラム自身の `defun`)に対して畳み込まれるため、実行時に定義された変数やマクロはそこからは見えません(インタプリタはグローバル変数や `defmacro` マクロを含む生きたイメージを調べます)。計算された名前は代わりに intern されるので常にシンボルが返り、そのステータスもイメージではなく生成された綴り(修飾付きなら `:external`、修飾なしなら `:internal`)から決まります。プログラムが [`make-package`](make-package.md) で作ったパッケージは例外で、そのメンバーテーブルはすべてのバックエンドで参照されるため、引数が何であれ intern 前は `nil`、後はそのシンボル、が成り立ちます。関数オブジェクト `#'find-symbol` も同じ 2 つの値を返します。コンパイルバックエンドではその引数は計算された値として扱われます。

パッケージに `common-lisp`(または `cl`)を渡した場合、答えは**標準の**名前一覧になります。CLHS 11.1.2.1 によりこのパッケージは 978 個の標準名すべてをエクスポートするので、rontolisp がその演算子を実装しているかどうかに関わらず名前は見つかります。エクスポートされていることと定義されていることは別の話です -- そうした名前に対して `fboundp`・`macro-function`・`special-operator-p` は依然として `nil` を返し、呼び出せば `undefined-function` が通知され、プログラム側で `defun` することもできます。`cl` を use するパッケージはそれらの名前を同じように継承し(`:inherited`)、`t` と `nil` はそれ自身として返ります。

第 2 の値はそのパッケージにおける ANSI のアクセス可能性ステータス — `:external`、`:inherited`、`:internal`、あるいはパッケージがその名前を提供しないときは `nil` — を返します。2 つの値は同時に `nil` になります:

```lisp
(multiple-value-list (find-symbol "CAR" 'common-lisp)) ; => (CAR :EXTERNAL)
```

```lisp
(multiple-value-list (find-symbol "CAR")) ; => (CAR :INHERITED)
```

```lisp
(find-symbol "car") ; => NIL
```

```lisp
(find-symbol "cond") ; => NIL
```

```lisp
(find-symbol "no-such-name") ; => NIL
```

```lisp
(defun greet (n) n)
(find-symbol "greet") ; => NIL
```

```lisp
(and (find-package :simple-date) (find-symbol "TIMESTAMP" :simple-date)) ; => NIL
```

```lisp
(handler-case (find-symbol "X" "NO-SUCH-PKG")
  (package-error (e) (package-error-package e))) ; => :NO-SUCH-PKG
```

```lisp
(multiple-value-list (find-symbol "FIND-METHOD" 'common-lisp)) ; => (FIND-METHOD :EXTERNAL)
```

```lisp
(fboundp 'find-method) ; => NIL
```

```lisp
(defpackage :fs-demo (:use :cl))
(multiple-value-list (find-symbol "CAR" :fs-demo)) ; => (CAR :INHERITED)
(find-symbol "NEVER-INTERNED" :fs-demo) ; => NIL
(eq (find-symbol "T" :fs-demo) t) ; => T
```
