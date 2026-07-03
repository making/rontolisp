# rontolisp:list-functions

`(rontolisp:list-functions &optional package)`

パッケージの関数シンボルをアルファベット順にソートして返します。省略可能な
パッケージ指定子はキーワード、裸のシンボル、クォートされたシンボル、または文字列
(`:cl`、`cl`、`'cl`、`"cl"`) で、デフォルトは `:cl` です。ある名前は `#'name` を
通じて関数値として使用できるときに限り関数として列挙されます。`:cl-user` の場合は
ユーザー定義の `defun` を列挙します。存在しないパッケージはエラーです。詳しくは
[パッケージのイントロスペクション](../packages.md#package-introspection) を参照してください。

```lisp
(rontolisp:list-functions :rontolisp) ; => (await fetch json-parse json-stringify list-functions list-macros list-special-forms promisep tcp-accept tcp-connect tcp-listen tcp-local-port then version)
```
