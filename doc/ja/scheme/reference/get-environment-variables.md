# get-environment-variables

`(get-environment-variables)`

すべての環境変数を、文字列の `(name . value)` ペアの新しいリストで返します。順序はホストのもので、バックエンド間で揃う保証はありません。値に `=` を含んでもよく、ペアは最初の `=` で分かれます。読み取り元は [get-environment-variable](get-environment-variable.md) と同じです。

```console
$ cat env.scm
(import (scheme base) (scheme write) (scheme process-context))
(write (assoc "GREETING" (get-environment-variables)))
$ GREETING=hello rontolisp env.scm
("GREETING" . "hello")
```

```scheme
(write (list? (get-environment-variables)))
```

```
#t
```
