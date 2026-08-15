# uiop:ensure-pathname

`(uiop:ensure-pathname pathname &key on-error defaults type dot-dot empty-is-nil want-pathname want-relative want-absolute ensure-absolute ensure-subpath want-file want-directory ensure-directory want-non-wild want-wild wilden want-existing ensure-directories-exist truename &allow-other-keys)`

uiop の他の部分が経由する制約マシンです: 指定子を型強制し (文字列は
[`uiop:parse-unix-namestring`](uiop-parse-unix-namestring.md) を通ります)、
`:want-*` のチェックと `:ensure-*` の変換を本家の順序で適用します。チェックの失敗は
パス名と制約を名指すエラーをシグナルするか、カスタムの `:on-error` 関数を呼びます。

```lisp
(uiop:ensure-pathname "a/b" :ensure-directory t)   ; => #P"a/b/"
```

```lisp
(handler-case (uiop:ensure-pathname "/a/b" :want-relative t)
  (error () :err))   ; => :ERR
```

意図的な lite 版です: レポートは `Invalid pathname ~S: ~A` で、`:want-logical` は
常に失敗し (論理パス名は存在しません)、`:resolve-symlinks` / `:truenamize` は
受け付けて無視され、`:truename` は [`probe-file`](probe-file.md) の答えを返します。

## バックエンドサポート

4 つのバックエンドすべてで動作します (Lisp ソース、`uiop-pathname.lisp`)。
