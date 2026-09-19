# file-exists?

`(file-exists? string)`

`string` という名前のファイル（またはディレクトリ）が存在すれば `#t` を返します。

```scheme
(file-exists? "no-such-file.txt") ; => #f
```
