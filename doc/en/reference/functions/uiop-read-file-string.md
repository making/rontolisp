# uiop:read-file-string

`(uiop:read-file-string file &rest keys)`

The entire contents of a file as one string -- the one-call spelling of opening
it, reading it to the end and closing it. A missing file signals, exactly as
[`open`](open.md) does.

Lite: real UIOP passes its `&rest` keys through to the open, and here they are
accepted and ignored. The only one that could matter, `:external-format`, has no
rontolisp surface at all -- every backend reads UTF-8.

```console
(let ((sql (uiop:read-file-string "db/20260101.up.sql")))
  (print (length sql)))
```

## Backend support

All four backends -- one definition in rontolisp source, over
`with-open-file` and a chunked [`read-sequence`](read-sequence.md) loop, so it
runs anywhere a file can be opened for input. It deliberately does not size a
single buffer from [`file-length`](file-length.md), which answers `nil` on both
WASM backends.
