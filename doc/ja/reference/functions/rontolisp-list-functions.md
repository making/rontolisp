# rontolisp:list-functions

`(rontolisp:list-functions &optional package)`

パッケージの関数シンボルをアルファベット順にソートして返します。省略可能な
パッケージ指定子はキーワード、裸のシンボル、クォートされたシンボル、または文字列
(`:cl`、`cl`、`'cl`、`"cl"`) で、デフォルトは `:cl` です。ある名前は `#'name` を
通じて関数値として使用できるときに限り関数として列挙されます。`:cl-user` の場合は
ユーザー定義の `defun` を列挙します。存在しないパッケージはエラーです。詳しくは
[パッケージのイントロスペクション](../packages.md#package-introspection) を参照してください。

```lisp
(rontolisp:list-functions :rontolisp) ; => (AWAIT CATCH FETCH FINALLY HTTP-HANDLER JSON-PARSE JSON-STRINGIFY LIST-FUNCTIONS LIST-MACROS LIST-SPECIAL-FORMS MAKE-MUTEX MUTEX-ACQUIRE MUTEX-RELEASE QUERY-PARAM QUERY-PARAMS RANDOM-BYTES TCP-ACCEPT TCP-CONNECT TCP-LISTEN TCP-LOCAL-ADDRESS TCP-LOCAL-PORT TCP-PEER-ADDRESS TCP-PEER-PORT THEN THEN* TLS-CONNECT TLS-LISTEN TLS-LISTEN-PEM TLS-UPGRADE URL-DECODE URL-ENCODE URL-PATH URL-QUERY VERSION WIT-ERROR-PAYLOAD WIT-PROVIDE)
```
