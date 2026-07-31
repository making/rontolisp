# uiop:add-package-local-nickname

`(uiop:add-package-local-nickname nickname package &optional scope-package)`

`nickname` を `package` の短縮名として登録し、以降 `nickname:symbol` が `package:symbol` と同様に解決されるようにします -- 長いパッケージ名を短縮するためにライブラリが推奨するイディオムです（例: jzon の `(uiop:add-package-local-nickname '#:jzon '#:com.inuoe.jzon)`）。対象パッケージの名前シンボルを返します。[`defpackage`](../special-forms/defpackage.md) の `:local-nicknames` 節はパッケージ定義時に同じ登録を行います。

lite 版: ニックネームは**グローバル**です -- rontolisp にパッケージごとのニックネームスコープはないため、省略可能な第 3 引数（ニックネームをスコープするパッケージ）は受理して無視され、衝突規則は `defpackage` の `:nicknames` と同じです。JVM / WASM コンパイルパスでは呼び出しはリテラルなトップレベルフォーム（リテラルな designator 引数）である必要があり、`defpackage` と同様にコンパイル時に消費されます。実行時に計算される呼び出しはインタープリタのみで動作します。

```lisp
(defpackage #:com.example.deeply.nested (:use #:cl) (:export #:answer))
(in-package #:com.example.deeply.nested)
(defun answer () 42)
(in-package #:cl-user)
(uiop:add-package-local-nickname '#:nick '#:com.example.deeply.nested)
(nick:answer) ; => 42
```
