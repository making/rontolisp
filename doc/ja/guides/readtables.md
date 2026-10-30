# リードテーブル

リードテーブルは4つのバックエンドすべてで値です。`*readtable*` がそれを保持し、[`copy-readtable`](../reference/functions/copy-readtable.md) が複製し、[`readtable-case`](../reference/functions/readtable-case.md)・[`set-macro-character`](../reference/functions/set-macro-character.md)・[`set-dispatch-macro-character`](../reference/functions/set-dispatch-macro-character.md)・[`make-dispatch-macro-character`](../reference/functions/make-dispatch-macro-character.md)・[`set-syntax-from-char`](../reference/functions/set-syntax-from-char.md) が変更します。named-readtables の `defreadtable` はこれらで構築します。

```lisp
(let ((*readtable* (copy-readtable nil)))
  (set-macro-character #\! (lambda (s c) (list 'not (read s t nil t))))
  (read-from-string "!done"))
; => (NOT DONE)
```

## リードテーブルを使う読み込み

インタプリタのリーダーは、読み込む時点のリードテーブルで読みます。

- プログラムと、それがロードする各ファイルはフォーム単位で読みます。`*readtable*` を変更するフォーム(named-readtables の `in-readtable`、または `eval-when` 内の `setf`)は、そのファイルの残りの読み方を変えます。`load` は `*readtable*` を束縛するため、変更はファイルの終わりで消えます。
- `read-from-string`、および文字列ストリームに対する `read` と [`read-delimited-list`](../reference/functions/read-delimited-list.md)。

リーダーマクロ関数は、読み込み中のテキストを対象とし、マクロ文字の直後に位置付けられた文字列入力ストリームを受け取ります。関数が読んだシンボルは現在のパッケージに intern されます。値を1つも返さない関数は、その文字をコメントの開始にします。大文字小文字の変換は `readtable-case` に従います。

そのため、自分のソースをリードテーブル経由で読むライブラリがインタプリタでロードできます。named-readtables、cl-interpol(`#?"Hello, ${name}"`)、cl-annot(`@export`)、cl-syntax、jonathan です。

## 未対応

- コンパイルパス(`-o`)はソースを標準の構文で読みます。プログラムが設定したリーダーマクロはそこでは呼ばれません。
- コンパイル済みバックエンドの実行時 `read` と `read-from-string` は `*readtable*` を無視します。
- ファイルストリームや標準入力からの `read` は標準の構文を使います。
- `with-standard-io-syntax` は `*readtable*` を再束縛しません。
- `(get-dispatch-macro-character #\# #\()` のような標準のディスパッチ関数は直接呼び出せません。比較と設定はできます。
