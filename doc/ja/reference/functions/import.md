# import

`(import symbols &optional package)`

`symbols`（シンボルまたはそのリスト）を `package`（省略時は現在のパッケージ）で**修飾なし**にアクセスできるようにします。以降、修飾のない `name` はインポート先パッケージの新しいシンボルではなく、インポートされたシンボルに解決されます。`t` を返します。[`defpackage`](../special-forms/defpackage.md) の `:import-from` 節の実行時版です。引数はパッケージ修飾子を保ったまま渡します -- それがシンボルの出自を示すからです。修飾のないシンボルはすでに現在のパッケージのものなので、インポートしても何も起こりません。存在しないパッケージはエラーです（`No such package: NOSUCH`）。

rontolisp ではパッケージは読み込み/コンパイル時に解決されるため（[パッケージ](../packages.md)を参照）、リテラルなトップレベル呼び出しは `in-package` と同様にコンパイル時に消費され、それ以降のフォームに効果を及ぼします -- これがすべてのバックエンドで動作する理由です。実行時に計算される呼び出し（実行時に組み立てたシンボル）はインタープリタのみで動作します。

```lisp
(defpackage #:importer (:use #:cl) (:export #:shout))
(in-package #:importer)
(defun shout () "HI")
(in-package #:cl-user)
(import 'importer:shout)
(shout) ; => "HI"
```
