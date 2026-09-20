# import

`(import symbols &optional package)`

`symbols`(シンボルまたはそのリスト)を `package`(省略時は現在のパッケージ)で**修飾なし**にアクセスできるようにします。以降、修飾のない `name` はインポート先パッケージの新しいシンボルではなくインポートされたシンボルに解決され、[`find-symbol`](find-symbol.md) はそれをステータス `:internal` で返します。`t` を返します。[`defpackage`](../special-forms/defpackage.md) の `:import-from` 節の実行時版です。各シンボルはそのホームからインポートされます -- パッケージ修飾付きのシンボルは修飾子が指すパッケージから、修飾のないシンボルは `cl-user`(標準名なら `cl`)から -- そして、パッケージがすでに所有しているシンボルでは何も起こりません。同名の**別の**シンボルがすでにパッケージに存在する場合(所有またはインポート済み。継承しているだけなら該当しません)は名前衝突となり、捕捉可能な `package-error` を通知します。代わりに押しのけるのが [`shadowing-import`](shadowing-import.md) です。存在しないパッケージはエラーです(`No such package: NOSUCH`)。

rontolisp ではパッケージは読み込み/コンパイル時に解決されるため([パッケージ](../packages.md)を参照)、リテラルなトップレベル呼び出しは `in-package` と同様にコンパイル時に消費され、それ以降のフォームに効果を及ぼします -- これがすべてのバックエンドで動作する理由です。実行時に計算される呼び出し(実行時に組み立てたシンボル)はインタープリタで動作し、プログラムが [`make-package`](make-package.md) で作ったパッケージについてはメンバーテーブルがインポートを記録するため、すべてのバックエンドで動作します。

```lisp
(defpackage #:importer (:use #:cl) (:export #:shout))
(in-package #:importer)
(defun shout () "HI")
(in-package #:cl-user)
(import 'importer:shout)
(shout) ; => "HI"
```

```lisp
(make-package :imp-demo :use nil)
(import 'my-sym :imp-demo) ; => T
(multiple-value-list (find-symbol "MY-SYM" :imp-demo)) ; => (MY-SYM :INTERNAL)
(intern "OTHER" :imp-demo)
(handler-case (import 'other :imp-demo) (package-error (c) (package-error-package c))) ; => :IMP-DEMO
```
