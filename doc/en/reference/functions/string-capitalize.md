# string-capitalize

`(string-capitalize string-designator)`

Returns a new string in which the first letter of each word is uppercased and the remaining letters of each word are lowercased, where words are runs of alphanumeric characters separated by other characters. The original string is unchanged. The argument is a [string designator](string.md), so a symbol, a keyword or a character is also accepted -- a symbol's name is used and a keyword's leading colon is dropped, so `(string-capitalize :foo-bar)` returns `"Foo-Bar"` and `(string-capitalize nil)` returns `"Nil"`. Anything that is not one of those three types is an error. As with the other case operators the fold is full-Unicode and identical on every backend, and a word constituent is any Unicode letter or digit, so `(string-capitalize "élan vital")` returns `"Élan Vital"`.

```lisp
(string-capitalize "hello world") ; => "Hello World"
```
