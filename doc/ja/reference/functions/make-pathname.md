# make-pathname

`(make-pathname &key directory name type defaults)`

構成要素からパス名を組み立てます。rontolisp のパス名はパス名文字列そのものなので、
結果は文字列です。`:directory` (Common Lisp のリスト形式 — `:absolute` または
`:relative` に続けて階層ごとに 1 つの文字列 — かディレクトリのパス名文字列)、`:name`
(型を除いたファイル名)、`:type` (ドットを除いた拡張子) を指定します。`:host`、
`:device`、`:version`、`:case` は受け付けて捨てられ、それ以外のキーワードも同様です。
パス名文字列にはそうした構成要素がなく、また移植性レイヤからの呼び出しがそのまま
動くようにするためです。

`:defaults` は、呼び出しで**指定されなかった**構成要素をすべて補いますが、これは
**構成要素ごとの補完であってマージではありません**。指定した構成要素は defaults の
ものと組み合わされるのではなく**置き換え**、明示的に指定した `nil` は「デフォルトを
使う」ではなく「その構成要素なし」を意味します。これが Common Lisp の規則であり、
指定した `:directory` は defaults のディレクトリの下に入れ子にはなりません。

| 呼び出し | 結果 |
|------|--------|
| `(make-pathname :name "b" :defaults "d/a.sql")` | `"d/b.sql"` |
| `(make-pathname :name "b" :type nil :defaults "d/a.sql")` | `"d/b"` |
| `(make-pathname :type "txt" :defaults "d/a.sql")` | `"d/a.txt"` |
| `(make-pathname :directory (list :relative "m") :name "b" :defaults "d/a.sql")` | `"m/b.sql"` |
| `(make-pathname :directory (list :absolute "u" "s") :name "b" :type "c")` | `"/u/s/b.c"` |

用途は隣接するファイルに名前を付けることです。[`pathname-name`](pathname-name.md) と
[`pathname-type`](pathname-type.md) がパス名文字列を分解する規則と、この関数が組み立てる
規則は同じものです。構成要素を置き換えるのではなく 2 つのパスを組み合わせたい場合は
[`merge-pathnames`](merge-pathnames.md) を使ってください。

```lisp
(make-pathname :name "20260101.down" :defaults "db/20260101.up.sql")   ; => "db/20260101.down.sql"
```

## バックエンドサポート

4 バックエンドすべてで、実行時の関数として動作します。rontolisp ソースで 1 つだけ
定義されています。コンパイル済みバックエンドでは、これに加えてキーワードと値がすべて
リテラルの呼び出しはビルド時にリテラルのパス名文字列へ畳み込まれます
([`asdf:system-relative-pathname`](asdf-system-relative-pathname.md) の結果が成果物の
中で定数になるのはこのためです)。それ以外の呼び出し — 計算された `:defaults` や
`:name` など — は関数として実行されます。2 つの実装は同じ規則を実現しています。
