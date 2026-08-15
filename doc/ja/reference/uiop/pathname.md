# uiop/pathname

`uiop/pathname` はパス名の代数 — ファイルシステムに触れずにパス名を組み立て、
分解し、比較するためにライブラリが使う可搬レイヤです。**50 のエクスポートすべてを
実装済み**で、どれもパス名値の上の純粋な計算なので、4 つのバックエンド —
インタプリタ、JVM、2 つの WASM 出力 — で同一に動作します。

どの名前もどちらの綴りでも到達できます: `uiop:subpathname` と
`uiop/pathname:subpathname` は同じ関数です
([uiop パッケージ](../uiop.md#sub-packages))。

rontolisp の[パス名](../data-types.md)は 1 つのフラットな名前文字列を持つため、
成分ごとの代数は名前文字列の計算に集約されます。知っておくべき帰結が 2 つあります:

- **論理パス名は存在しません** (論理ホストを定義できないため)。したがって
  `uiop:logical-pathname-p` はすべてに対して `nil`、`uiop:physical-pathname-p` は
  `pathnamep`、`uiop:physicalize-pathname` は型強制つきの恒等関数で、
  `uiop:make-pathname-logical` は `uiop:not-implemented-error` をシグナルします。
- **どこでも絶対化しません**: `uiop:ensure-absolute-pathname` は、本家がシグナル
  する場面で相対パスをそれ自身として返します — rontolisp は相対パスを実行中ずっと
  ホストの作業ディレクトリ基準で解決するので、与えられたパスがすでにファイルの
  同一性だからです。

## 組み立てとマージ

| 関数 | 何をするか |
|----------|--------------|
| [`uiop:merge-pathnames*`](../functions/uiop-merge-pathnames-star.md) | デフォルト考慮のマージ — 絶対な `specified` が勝ち、相対ならデフォルトのディレクトリに追加されます |
| [`uiop:subpathname`](../functions/uiop-subpathname.md) | 相対サブパスをベースパス名のディレクトリの下にマージします |
| `uiop:subpathname*` | ベースが `nil` なら `nil`、そうでなければベースをディレクトリ形式にしてから `subpathname` |
| `uiop:ensure-directory-pathname` | ディレクトリ形式 (末尾 `/`) のパス名 |
| `uiop:ensure-absolute-pathname` | 絶対パスはそのまま通し、相対パスはデフォルト (パス名、またはそれを返す関数) に対してマージします |
| `uiop:nil-pathname` / `uiop:*nil-pathname*` | 中立なデフォルト — 空のパス名 `#P""` |
| `uiop:pathname-root` | ホストとデバイスのルート — ここでは唯一のルート `#P"/"` |
| `uiop:pathname-host-pathname` | ホストだけを持つパス名 — ホストをモデル化していないため `#P""` |
| `uiop:make-pathname*` | `make-pathname` そのもの。非推奨の綴りで呼ぶ側のために残されています |
| `uiop:make-pathname-component-logical` | `:unspecific` は `nil` に、それ以外はそのまま |
| `uiop:normalize-pathname-directory-component` | ディレクトリ成分を CLHS のリスト形式に (`"foo"` → `(:absolute "foo")`) |
| `uiop:denormalize-pathname-directory-component` | 恒等関数 — 正規化形式がネイティブ形式です |
| `uiop:merge-pathname-directory-components` | マージのディレクトリリスト部分。`:back` の処理も含みます |
| `uiop:*unspecific-pathname-type*` | `nil` — ここでは存在しない成分は `nil` です |

```lisp
(print (uiop:subpathname #P"/tmp/foo/" "bar/baz.txt"))
(print (uiop:subpathname* "/tmp/foo" "x.txt"))
(print (uiop:ensure-absolute-pathname "b.txt" "/tmp/"))
(print (uiop:merge-pathname-directory-components '(:relative :back "x") '(:absolute "a" "b")))
```

```
#P"/tmp/foo/bar/baz.txt"
#P"/tmp/foo/x.txt"
#P"/tmp/b.txt"
(:ABSOLUTE "a" "x")
```

## 述語

`absolute-pathname-p`、`relative-pathname-p`、`file-pathname-p` は真のとき
パース済みの**パス名**を返します (本家と同じく一般化ブーリアン)。残りは
`t`/`nil` を返します。どれもファイルシステムに触れません。

| 関数 | 真になるのは |
|----------|-----------|
| [`uiop:absolute-pathname-p`](../functions/uiop-absolute-pathname-p.md) | 名前文字列が `/` で始まるとき |
| [`uiop:relative-pathname-p`](../functions/uiop-relative-pathname-p.md) | 始まらないとき (空のパス名も含む) |
| [`uiop:directory-pathname-p`](../functions/uiop-directory-pathname-p.md) | ワイルドでなく、名前も型もないとき — 空か `/` で終わるとき |
| [`uiop:file-pathname-p`](../functions/uiop-file-pathname-p.md) | 名前または型の成分があるとき |
| `uiop:hidden-pathname-p` | 名前がドットで始まるとき |
| `uiop:pathname-equal` | 2 つの指定子が同じ名前文字列を持つとき |
| `uiop:logical-pathname-p` | 決して真になりません (論理パス名は存在しません) |
| `uiop:physical-pathname-p` | 引数がパス名のとき |

```lisp
(print (list (uiop:absolute-pathname-p "/a/b") (uiop:relative-pathname-p "a/b")))
(print (list (uiop:directory-pathname-p "/a/b/") (uiop:file-pathname-p "/a/b")))
(print (list (uiop:hidden-pathname-p ".gitignore") (uiop:pathname-equal "/a/b" #P"/a/b")))
```

```
(#P"/a/b" #P"a/b")
(T #P"/a/b")
(T T)
```

## ディレクトリ

| 関数 | 何をするか |
|----------|--------------|
| [`uiop:pathname-directory-pathname`](../functions/uiop-pathname-directory-pathname.md) | パス名のディレクトリ部分。名前と型は落とされます |
| [`uiop:pathname-parent-directory-pathname`](../functions/uiop-pathname-parent-directory-pathname.md) | ディレクトリを 1 段上へ (ルートの親はルート) |

```lisp
(print (uiop:pathname-directory-pathname #P"/a/b/c.txt"))
(print (uiop:pathname-parent-directory-pathname #P"/a/b/c.txt"))
```

```
#P"/a/b/"
#P"/a/"
```

## パース

| 関数 | 何をするか |
|----------|--------------|
| [`uiop:parse-unix-namestring`](../functions/uiop-parse-unix-namestring.md) | Unix 構文の文字列をパス名に: `""` と `"."` の成分は落とされ、`:type` は追加され、`:ensure-directory` はディレクトリ形式を強制します |
| [`uiop:unix-namestring`](../functions/uiop-unix-namestring.md) | Unix 形式の名前文字列 — ここでは名前文字列*そのもの*です |
| [`uiop:split-name-type`](../functions/uiop-split-name-type.md) | ファイル名の NAME と TYPE の 2 値 (最後のドットで分けられ、先頭だけのドットは名前に属します) |
| `uiop:split-unix-namestring-directory-components` | 4 値: `:absolute`/`:relative`、ディレクトリ成分、最後の成分、そして文字列が素のファイル名だったかどうか |

```lisp
(print (uiop:parse-unix-namestring "a//b/./c.txt"))
(print (uiop:parse-unix-namestring "foo/bar" :type "lisp"))
(print (multiple-value-list (uiop:split-name-type "foo.lisp")))
(print (multiple-value-list (uiop:split-unix-namestring-directory-components "/a/b/c.txt")))
```

```
#P"a/b/c.txt"
#P"foo/bar.lisp"
("foo" "lisp")
(:ABSOLUTE ("a" "b") "c.txt" NIL)
```

## ベースに対する相対

| 関数 | 何をするか |
|----------|--------------|
| [`uiop:subpathp`](../functions/uiop-subpathp.md) | 第 1 パス名が第 2 の下にあるとき、マージで元に戻せる相対の残り。そうでなければ `nil` |
| [`uiop:enough-pathname`](../functions/uiop-enough-pathname.md) | 残りがあればそれ、なければパス名自身 |
| `uiop:call-with-enough-pathname` | `enough-pathname` に対して関数を呼びます。`*default-pathname-defaults*` はベースに束縛されます |
| `uiop:with-enough-pathname` | 上のマクロ短縮形 — `(uiop:with-enough-pathname (p :defaults d) ...)` は `p` を再束縛します |
| `uiop:with-pathname-defaults` | マクロ: `*default-pathname-defaults*` を与えたフォームに (省略時は `*nil-pathname*` に) 束縛して本体を実行します |

```lisp
(print (uiop:subpathp #P"/tmp/foo/bar.txt" #P"/tmp/"))
(print (uiop:enough-pathname #P"/x/a.txt" #P"/tmp/"))
(let ((p #P"/tmp/a/b.txt"))
  (uiop:with-enough-pathname (p :defaults #P"/tmp/") (print p)))
(uiop:with-pathname-defaults (#P"/wpd/") (print *default-pathname-defaults*))
```

```
#P"foo/bar.txt"
#P"/x/a.txt"
#P"a/b.txt"
#P"/wpd/"
```

## 制約チェック

[`uiop:ensure-pathname`](../functions/uiop-ensure-pathname.md) は uiop の他の
部分が経由する制約マシンです: 指定子を型強制し (文字列は
`parse-unix-namestring` を通ります)、`:want-*` のチェックと `:ensure-*` の変換を
本家の順序で適用します。チェックの失敗はシグナルするか、カスタムの `:on-error`
関数を呼びます。

```lisp
(print (uiop:ensure-pathname "a/b" :ensure-directory t))
(print (handler-case (uiop:ensure-pathname "/a/b" :want-relative t) (error () :err)))
```

```
#P"a/b/"
:ERR
```

意図的な lite 版です: チェックの失敗は `Invalid pathname ~S: ~A` を報告し
(本家の `~?` 連鎖ではありません)、`:want-logical` は常に失敗し、
`:resolve-symlinks` / `:truenamize` は受け付けて無視されます (シンボリックリンクを
解決するバックエンドはありません)。`:truename` は `probe-file` の答えを返します。

## ワイルドカードと変換

`*wild*` ファミリは、[`directory`](../functions/directory.md) のマッチャが読む
2 つのワイルドカード (`*` と `?`) の上の名前文字列リテラルです。ワイルド定数と
マッチャが食い違うことはありません。

| 名前 | 値 / 何をするか |
|------|----------------------|
| `uiop:*wild*` | `"*"` |
| `uiop:*wild-file*` / `uiop:*wild-file-for-directory*` | `#P"*.*"` |
| `uiop:*wild-directory*` | `#P"*/"` |
| `uiop:*wild-inferiors*` | `#P"**/"` |
| `uiop:*wild-path*` | `#P"**/*.*"` |
| `uiop:wilden` | パス名のディレクトリ以下の任意のサブディレクトリの任意のファイル |
| `uiop:translate-pathname*` | [`translate-pathname`](../functions/translate-pathname.md) の output-translations ラッパ: 関数の宛先は呼び出され、`t` はパスを返し、相対の宛先はまずルートとマージされます |
| `uiop:relativize-directory-component` | `(:absolute ...)` を `(:relative ...)` に |
| `uiop:relativize-pathname-directory` | 先頭の `/` を落としたパス名 |
| `uiop:directory-separator-for-host` | `#\/` |
| `uiop:directorize-pathname-host-device` | 恒等関数 — Unix 形の物理パス名はすでにその形です |
| `uiop:*output-translation-function*` | `'identity` — ここでは output translations は動きません |

```lisp
(print (uiop:wilden #P"/tmp/foo"))
(print (uiop:translate-pathname* #P"/src/a/b.lisp" #P"/src/**/*.*" #P"/out/**/*.*"))
(print (uiop:relativize-pathname-directory #P"/a/b/c.txt"))
```

```
#P"/tmp/**/*.*"
#P"/out/a/b.lisp"
#P"a/b/c.txt"
```

## コンパイル時の畳み込み

`uiop:merge-pathnames*` と同様、引数がリテラル (またはリテラルを束縛した
トップレベル `defparameter` への参照) の `uiop:subpathname` はコンパイルパスで
パス名リテラルへ畳み込まれるため、バンドルされたライブラリのデータファイルパスは
実行時コストゼロです。
