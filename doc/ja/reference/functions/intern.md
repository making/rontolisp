# intern

`(intern string)`

`string` を名前とするシンボルを返します(ケース変換なし)。rontolisp のシンボルは名前で比較され、独立したインターンテーブルはないため、結果はクォートされたリテラルを含め同名のどのシンボルとも `eq` になります。インタプリタでは名前は**カレントパッケージ**にインターンされます(Common Lisp の `*package*` の意味論): アクセス可能なシンボルはそのホームの綴りを保ち、未知の名前は `in-package` で選択中のパッケージのシンボルになります — これにより、マクロ時の `(intern (concatenate ...))` がそのファイル内のリテラルな `defun` と同じ関数を指せます。`(intern name :keyword)` はキーワードを作ります。Common Lisp からの相違点: それ以外のパッケージ引数はエラーになり、第 2 の `status` 値はなく、コンパイル系バックエンドではランタイムの `intern` 呼び出しはパッケージを認識しません(名前は与えたとおりに使われます)。

```lisp
(intern "hello") ; => hello
```

```lisp
(eq (intern "foo") 'foo) ; => t
```

```lisp
(defvar *level* 7)
(symbol-value (intern "*level*")) ; => 7
```
