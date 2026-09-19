# file-exists?

`(file-exists? string)`

Returns `#t` when a file (or a directory) named `string` exists.

```scheme
(file-exists? "no-such-file.txt") ; => #f
```
