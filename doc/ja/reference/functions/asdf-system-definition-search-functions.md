# asdf:\*system-definition-search-functions\*

システム定義の探索で順に呼ぶ関数のリストです。各関数にシステム名を文字列で渡し、答えが返るまで
続けます。関数はロードする `.asd` のパス名、またはそのまま登録する `asdf:system` を返します。
nil なら次の関数に進み、それ以外の値は `type-error` です。Quicklisp やローカルの探索フックは、
この変数を通じて ASDF に接続します。初期値は本物の ASDF と同じ 3 つです:

```lisp
(mapcar #'symbol-name asdf:*system-definition-search-functions*)
; => ("SYSDEF-PACKAGE-INFERRED-SYSTEM-SEARCH" "SYSDEF-CENTRAL-REGISTRY-SEARCH" "SYSDEF-SOURCE-REGISTRY-SEARCH")
```

```console
(defun vendor-search (name)
  (when (equal name "my-lib") #P"/opt/vendor/my-lib/my-lib.asd"))
(push 'vendor-search asdf:*system-definition-search-functions*)
(asdf:load-system "my-lib")
```

`asdf:search-for-system-definition` がこの走査を行い、`asdf:locate-system` はその上で本物の ASDF の
5 つの値を返します。リストに `sysdef-source-registry-search` がなければ
[探索パス](../../guides/asdf-systems.md#the-system-search-path)も探索しません。rontolisp に組み込みの
システムは、リストの内容にかかわらず見つかります。

## バックエンドサポート

インタプリタは探索のたびにリストを走査します。コンパイルパスはプログラムの実行前、つまりプログラムの
関数が存在する前のスプライス中にシステムを探すので、リテラルの `load-system` は追加された関数を
呼びません。リストの名前を含むプログラムでは、スプライスしていない名前に対する `asdf:find-system` が
実行時にリストを走査します。関数が返したシステムは登録しますが、パス名を返すとエラーを通知します。
コンパイル済みプログラムはファイルをロードできないためです。
