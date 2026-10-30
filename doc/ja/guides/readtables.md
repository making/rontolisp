# リードテーブル

リードテーブルは4つのバックエンドすべてで値です。`*readtable*` がそれを保持し、[`copy-readtable`](../reference/functions/copy-readtable.md) が複製し、[`readtable-case`](../reference/functions/readtable-case.md)・[`set-macro-character`](../reference/functions/set-macro-character.md)・[`set-dispatch-macro-character`](../reference/functions/set-dispatch-macro-character.md)・[`make-dispatch-macro-character`](../reference/functions/make-dispatch-macro-character.md)・[`set-syntax-from-char`](../reference/functions/set-syntax-from-char.md) が変更します。named-readtables の `defreadtable` はこれらで構築します。

```lisp
(let ((*readtable* (copy-readtable nil)))
  (set-macro-character #\! (lambda (s c) (list 'not (read s t nil t))))
  (read-from-string "!done"))
; => (NOT DONE)
```

## リードテーブルを使う読み込み

ソースはフォーム単位で読み、各フォームはそこまで読み進んだ時点のリードテーブルで読みます。

- インタプリタでは、プログラムとそれがロードする各ファイルが対象です。`*readtable*` を変更するフォーム(named-readtables の `in-readtable`、またはその `setf`)は、そのファイルの残りの読み方を変えます。
- コンパイルパス(`-o`)でも同じように読みます。それより前のフォームは `compile-file` と同じ規則で評価します。`:compile-toplevel` を含む `eval-when` 内の変更(`in-readtable` の展開形がこれです)はファイルの残りを読むのに使われ、先にロードした ASDF システムのファイルはロード済みとして扱います。リーダーマクロ関数はコンパイル時に実行し、その戻り値はコンパイル済みプログラムが保持できるデータでなければなりません。クロージャやハッシュテーブルは読み込みエラーです。同じコンパイル内で選ぶ `defreadtable` は、named-readtables のドキュメントが求めるとおり `eval-when` で囲みます。

`load` はすべてのバックエンドで `*readtable*` を束縛するため、変更はファイルの終わりで消えます。インタプリタでは、`read-from-string`、および文字列ストリームに対する `read` と [`read-delimited-list`](../reference/functions/read-delimited-list.md) も `*readtable*` に従います。

```console
$ cat bang.lisp
(eval-when (:compile-toplevel :load-toplevel :execute)
  (setf *readtable* (copy-readtable nil))
  (set-macro-character #\! (lambda (s c) (list 'not (read s t nil t)))))
(print !nil)
$ rontolisp bang.lisp -o bang.wasm && wasmtime run bang.wasm

T
```

リーダーマクロ関数は、読み込み中のテキストを対象とし、マクロ文字の直後に位置付けられた文字列入力ストリームを受け取ります。関数が読んだシンボルは現在のパッケージに intern されます。値を1つも返さない関数は、その文字をコメントの開始にします。大文字小文字の変換は `readtable-case` に従います。

そのため、自分のソースをリードテーブル経由で読むライブラリがロードできます。named-readtables・cl-interpol(`#?"Hello, ${name}"`)・cl-csv は4つのバックエンドすべてで、jonathan(cl-syntax 経由)はインタプリタと JVM で、cl-annot(`@export`)はインタプリタでロードできます。

## 未対応

- コンパイル済みバックエンドの実行時 `read` と `read-from-string` は `*readtable*` を無視します。
- ファイルストリームや標準入力からの `read` は標準の構文を使います。
- `with-standard-io-syntax` は `*readtable*` を再束縛しません。
- `(get-dispatch-macro-character #\# #\()` のような標準のディスパッチ関数は直接呼び出せません。比較と設定はできます。
