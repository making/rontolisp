# リーダーのケース（大文字化）

rontolisp のリーダーは標準の Common Lisp と同じようにシンボルを大文字化します:
シンボルトークンのエスケープされていないすべての文字は読み取り中に大文字へ
変換され(`:upcase` リードテーブルケース)、ソース中の `foo`・`Foo`・`FOO` は
すべて同じシンボル `FOO` を指します。エスケープされた文字はケースを保ちます:
`|mixed Case|` や `\(` はそのまま読まれます。

大文字化された名前が正規の綴りであり、小文字の綴りへ畳み込むことはありません。
標準の名前・`t`/`nil`・ラムダリストマーカー・組み込みパッケージのメンバーは、
他のすべてと同様にすべて大文字です:

- 標準の `cl` の名前(`defun` も `DEFUN` も `DEFUN` として読まれ、`list` は
  `LIST` になる)。型指定子や条件型の名前(`HASH-TABLE`、`TYPE-ERROR`)を含む
- `T` / `NIL` / `PI` などの読み取り時定数
- ラムダリストマーカー(`&OPTIONAL`、`&KEY`、...)
- 組み込みパッケージの接頭辞とそのメンバー(`rl:fetch` は `RL:FETCH`、
  `ql:quickload` は `QL:QUICKLOAD` として読まれる)
- キーワード / `#:` デジグネータ(`(in-package :cl-user)` は `:CL-USER`、
  `(:use #:cl)` は `#:CL` として読まれる)

ユーザーのシンボル、ユーザーのパッケージ、データキーワードも同じように
大文字化され、Common Lisp とまったく同じように一貫します:

```lisp
(defun greet (name) (format nil "Hello, ~a!" name))
(greet "world") ; => "Hello, world!"
'foo ; => FOO
(symbol-name 'foo) ; => "FOO"
(symbol-name 'car) ; => "CAR"
(eq 'foo 'FOO) ; => T
(cdr (assoc :note '((:note . "hi")))) ; => "hi"
```

エスケープされた名前はケースを保つため、大文字化された名前とは*別の*シンボル
になります。これは Common Lisp と同じで、`|car|` は `CAR` ではありません。

組み込みのキーワード引数はケースを無視してマッチし(`:test` が使える所では
`:TEST` も使えます)、`(intern "TIME")` は標準の `TIME` を指します。これにより
`(intern (string-upcase ...))` の名前合成イディオムが本体側の参照と一致し、
assoc-utils の `with-keys` のようなマクロが動きます:

```console
$ cat keys.lisp
(ql:quickload :assoc-utils)
(print (assoc-utils:with-keys ("name") '(("name" . "eitaro"))
         name))
$ rontolisp keys.lisp
"eitaro"
```

`load`・`asdf:load-system`・`ql:quickload` で読み込まれるライブラリも同じ
ように読まれるため、ライブラリの定義とユーザー側の参照は一貫して大文字化され
ます。シンボルのシステムデジグネータは ASDF の `coerce-name` と同様に
小文字化されます(`(ql:quickload :ASSOC-UTILS)` は `assoc-utils` システムを
見つけます)。

実行時のリーダーも大文字化を行うため、実行時に読み取ったデータはソースに
書いた同じデータと同じように振る舞います: `read` と `read-from-string` は
ユーザーのシンボルを大文字化します。これはインタプリタ・JVM・両方の WASM
バックエンドで同一です。

```lisp
(read-from-string "foo") ; => FOO
(symbol-name (read-from-string "foo")) ; => "FOO"
(eq (read-from-string "list") 'list) ; => T
(eval (read-from-string "(reverse (list 1 2 3))")) ; => (3 2 1)
```

## 印字する側: `*print-case*`

プリンタは格納されている（大文字の）綴りをそのまま書き出します。これが
`*print-case*` の標準値 `:upcase` の意味です。この変数を束縛すると、印字操作が
綴るすべてのシンボルの大小文字が変換されます。対象は `princ`、`prin1`、
`print`、`princ-to-string`、`prin1-to-string`、`write-to-string`、`write` と
format の `~A` / `~S` ディレクティブで、インタプリタ・JVM・2 つの WASM
バックエンドすべてで同じように動作します。

```lisp
(let ((*print-case* :downcase)) (princ-to-string 'add-test)) ; => "add-test"
(let ((*print-case* :capitalize)) (princ-to-string 'add-test)) ; => "Add-Test"
(let ((*print-case* :downcase)) (format nil "~a ~s" 'foo :foo)) ; => "foo :foo"
(let ((*print-case* :downcase)) (prin1-to-string '(foo "Str" nil))) ; => "(foo \"Str\" nil)"
```

変換されるのはシンボルだけです。文字列は自身の文字をそのまま保ち、文字は文字と
して印字されます。`nil` / `t` はシンボルなので変換されます。`:capitalize` は各語
の先頭文字をそのまま残し、語の残りを小文字にします（語は英数字の連なりです）。
小文字を大文字にすることはありません。標準が変換するのは大文字だけであり、
そこが `string-capitalize` との違いです。

差異: 構造体・CLOS インスタンス・ハッシュテーブル・階数が 1 以外の配列の中に
入れ子になったシンボルは、格納された綴りのままになります。変換が辿るのはリストと
ベクタで、これらのコンテナは通常のレンダラに委ねられます。

## Common Lisp との相違点

- `intern`・`make-symbol`・`find-symbol` は名前をそのまま受け取ります(別の
  インターン表はなく、シンボルはその名前そのものです)。標準シンボルの名前は
  `"CAR"` なので `(find-symbol "car")` は `NIL` であり、`(make-symbol "X")` を
  2 回呼ぶと `eq` なシンボルになります。読み取りには影響しません --
  ソース中の `car` は `CAR` に大文字化されます。
- メンバーを混在ケースで綴るキーワード / `#:` デジグネータは、その正確な
  (混在ケースの)シンボルを指します。組み込みメンバーは大文字で綴るか
  (`#:CL`)、素の名前をリーダーに大文字化させてください。
