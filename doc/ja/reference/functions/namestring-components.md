# file-namestring directory-namestring host-namestring

`(file-namestring pathname)` -- `(directory-namestring pathname)` -- `(host-namestring pathname)`

ネームストリングを構成する文字列成分です。`file-namestring` は名前と型の部分（最後の `/` より後ろ、`/` がなければ全体）、`directory-namestring` はその手前の部分（最後の `/` を含む）を返します。両者はちょうど補い合う関係にあり、連結すると必ず [`namestring`](namestring.md) に戻ります。`host-namestring` は常に `""` です。rontolisp のネームストリングはホスト構文を持たず、[`pathname-host`](pathname-host.md) が同じ不在を `nil` で表すのに対し、Common Lisp はここでは文字列を要求するためです。3 つとも、パス名指定子のどちらの綴りも受け付け、それ以外は `namestring` と同じ位置でシグナルします。

```lisp
(list (file-namestring #P"/a/b/c.txt")
      (directory-namestring #P"/a/b/c.txt")
      (host-namestring #P"/a/b/c.txt"))
; => ("c.txt" "/a/b/" "")
```

ディレクトリを指すネームストリングにはファイル部分がなく、`/` を含まないネームストリングにはディレクトリ部分がありません。

```lisp
(list (file-namestring "/a/b/") (directory-namestring "a.txt"))
; => ("" "")
```

ドットファイル先頭のドットは名前に属します。これは [`pathname-name`](pathname-name.md) と同じ規則で、`(file-namestring "/a/.bashrc")` は `".bashrc"` になります。

## バックエンド対応

4 バックエンドすべて。rontolisp ソースによる 1 つの定義で、参照されたプログラムにのみ差し込まれます。
