# string-capitalize

`(string-capitalize string-designator)`

Returns a new string in which the first letter of each word is uppercased and the remaining letters of each word are lowercased, where words are runs of alphanumeric characters separated by other characters. The original string is unchanged. The argument is a string designator, so a symbol or keyword is also accepted -- its name is used and a keyword's leading colon is dropped, so `(string-capitalize :foo-bar)` returns `"Foo-Bar"`. As with the other case operators, the WASM backend folds case using ASCII rules only.

```lisp
(string-capitalize "hello world") ; => "Hello World"
```
