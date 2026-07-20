# リーダーのケース（大文字化）

rontolisp のリーダーは標準の Common Lisp と同じようにシンボルを大文字化します:
シンボルトークンのエスケープされていないすべての文字は読み取り中に大文字へ
変換され(`:upcase` リードテーブルケース)、ソース中の `foo`・`Foo`・`FOO` は
すべて同じシンボル `FOO` を指します。エスケープされた文字はケースを保ちます:
`|mixed Case|` や `\(` はそのまま読まれます。

rontolisp 独自の点は、すべての組み込み名の**正規の綴り**が小文字であることです。
大文字化の後、正規の綴りが小文字である名前は小文字に畳み込まれるため、
どう綴っても以下はすべて解決されます:

- 標準の `cl` の名前(`DEFUN` も `defun` も `defun` として読まれ、`LIST` は
  `list` になる)。型指定子や条件型の名前(`HASH-TABLE`、`TYPE-ERROR`)を含む
- `T` / `NIL` / `PI` などの読み取り時定数
- ラムダリストマーカー(`&OPTIONAL`、`&KEY`、...)
- 組み込みパッケージの接頭辞とそのメンバー(`RL:FETCH` は `rl:fetch`、
  `QL:QUICKLOAD` は `ql:quickload` として読まれる)
- 組み込みパッケージのキーワード / `#:` デジグネータ
  (`(in-package :CL-USER)`、`(:use #:CL)`)

それ以外のもの -- ユーザーのシンボル、ユーザーのパッケージ、データ
キーワード -- は大文字化されて読まれ、Common Lisp とまったく同じように
一貫します:

```lisp
(defun greet (name) (format nil "Hello, ~a!" name))
(greet "world") ; => "Hello, world!"
'foo ; => FOO
(symbol-name 'foo) ; => "FOO"
(eq 'foo 'FOO) ; => t
(cdr (assoc :note '((:NOTE . "hi")))) ; => "hi"
```

組み込みのキーワード引数はケースを無視してマッチし(`:test` が使える所では
`:TEST` も使えます)、`(intern "TIME")` は標準の `time` を指します --
Common Lisp の upcase の世界と同じ答えです。これにより
`(intern (string-upcase ...))` の名前合成イディオムが本体側の参照と一致し、
assoc-utils の `with-keys` のようなマクロが動きます:

```console
$ cat keys.lisp
(ql:quickload :assoc-utils)
(print (assoc-utils:with-keys ("name") (list (cons "name" "eitaro"))
         name))
$ rontolisp keys.lisp
"eitaro"
```

`load`・`asdf:load-system`・`ql:quickload` で読み込まれるライブラリも同じ
ように読まれるため、ライブラリの定義とユーザー側の参照は一貫して畳み込まれ
ます。例外は `.asd` システム定義です: データとしてパースされるためケース
保存のままで、シンボルのシステムデジグネータは ASDF の `coerce-name` と
同様に小文字化されます(`(ql:quickload :ASSOC-UTILS)` は `assoc-utils` を
見つけます)。

実行時のリーダーも畳み込みを行うため、実行時に読み取ったデータはソースに
書いた同じデータと同じように振る舞います: `read` と `read-from-string` は
ユーザーのシンボルを大文字化し、標準の名前を正規の綴りに畳み込みます。
これはインタプリタ・JVM・両方の WASM バックエンドで同一です。

```lisp
(read-from-string "foo") ; => FOO
(symbol-name (read-from-string "foo")) ; => "FOO"
(eq (read-from-string "list") 'list) ; => t
(eval (read-from-string "(reverse (list 1 2 3))")) ; => (3 2 1)
```

## Common Lisp との相違点

- rontolisp の標準シンボルの正規の綴りは小文字なので、`(symbol-name 'car)`
  は `"car"` です(CL は `"CAR"`)。`'foo` のようなユーザーシンボルは CL と
  同じく `"FOO"` を報告し、印字も同じ規則に従います(`(print 'car)` は
  `car`、`(print 'foo)` は `FOO` と表示)。
- `|car|`(パイプでエスケープされた小文字)は標準の `car` になり、別の
  小文字シンボルにはなりません -- 畳み込みは完成した名前に適用されます。
- `cl` のメンバーを `#:` デジグネータで綴る
  `(:import-from #:cl #:car)` 形式の節は、メンバーが小文字で綴られている
  場合にのみ解決されます。
