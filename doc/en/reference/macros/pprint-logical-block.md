# pprint-logical-block

`(pprint-logical-block (stream object &key prefix per-line-prefix suffix) body...)`

Writes `prefix`, evaluates the body -- which prints to `stream` -- then writes `suffix`, and returns `nil`. When `object` is not a list it is printed with `write` and the body is skipped, which is Common Lisp's own rule and what makes the macro safe to wrap around a value that may or may not be a list.

A rontolisp stream carries no column, so the block never WRAPS and `:per-line-prefix` is accepted as a synonym of `:prefix` (no line inside the block ever begins on its own). See `pprint` for the rest of that story.

```lisp
(list (with-output-to-string (s)
        (pprint-logical-block (s '(1 2 3) :prefix "<" :suffix ">") (princ "body" s)))
      (with-output-to-string (s)
        (pprint-logical-block (s 5 :prefix "<" :suffix ">") (princ "body" s)))) ; => ("<body>" "5")
```
