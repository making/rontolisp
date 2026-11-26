# rontolisp:json-span

`(rontolisp:json-span document &rest path)` / `(rontolisp:json-span address size &rest path)`

Finds where a value is in a JSON document's bytes, without making Lisp values of
the document. It checks the whole document as
[`rontolisp:json-parse`](rontolisp-json-parse.md) would, follows the path, and
answers byte offsets from the document's first byte. An edit is then a splice of
those bytes: insert, replace or delete text there.

The document is a string (its UTF-8 bytes) or an `(unsigned-byte 8)` vector. On
the wasm-GC backends it can also be the `size` bytes at an `address` of the
module's own linear memory, such as the body a Proxy-Wasm host placed there: they
are read where they are and never copied, so a body of several megabytes is
searched in the memory it already takes.

A path is keys and indexes: a string names an object's key, a non-negative
integer an array's element. No path names the document itself.

```lisp
(multiple-value-list (rontolisp:json-span "{\"a\": [1, {\"b\": true}]}" "a" 1 "b"))   ; => (16 20 NIL 11 20)
(multiple-value-list (rontolisp:json-span "{\"a\": [1, {\"b\": true}]}"))   ; => (0 23 1 NIL NIL)
(multiple-value-list (rontolisp:json-span "{\"a\": [1, {\"b\": true}]}" "z"))   ; => (NIL)
```

## Values

| Value | What it is |
|-------|------------|
| `start` | the offset of the value's first byte |
| `end` | the offset past its last byte |
| `count` | an object's members or an array's elements; `nil` for any other value |
| `cut-start`, `cut-end` | the bytes that deleting the member takes: its key or element and one comma beside it, or everything inside its container when it is the only one; `nil` for the document itself |

A path that leads nowhere answers `nil`: a key or an index that is not there, or
a value the path goes through that is no container of that kind. A key repeated
in an object answers its last occurrence, the one `json-parse` keeps. Offsets
count bytes on every backend, as a host's buffer does, so in a string of
characters past ASCII they are not character indexes: use an octet vector to cut
the text.

```lisp
(multiple-value-list (rontolisp:json-span "{\"é\": 1}" "é"))   ; => (7 8 NIL 1 8)
(multiple-value-list (rontolisp:json-span (rontolisp:string-to-octets "[10, 20]") 1))   ; => (5 7 NIL 3 7)
```

## Editing

Replace a value between `start` and `end`; insert into a container before its
closing bracket at `(- end 1)`, writing a comma when `count` is not zero; delete a
member between `cut-start` and `cut-end`.

```lisp
(defun json-replace (doc path text)
  (multiple-value-bind (start end) (apply #'rontolisp:json-span doc path)
    (concatenate 'string (subseq doc 0 start) text (subseq doc end))))
(json-replace "{\"id\": 7, \"tags\": []}" '("id") "8")   ; => "{\"id\": 8, \"tags\": []}"
(let ((doc "{\"id\": 7, \"tags\": [\"a\"]}"))
  (multiple-value-bind (start end count) (rontolisp:json-span doc "tags")
    (declare (ignore start))
    (concatenate 'string (subseq doc 0 (- end 1)) (if (plusp count) ", " "") "\"b\""
                 (subseq doc (- end 1)))))   ; => "{\"id\": 7, \"tags\": [\"a\", \"b\"]}"
(let ((doc "{\"id\": 7, \"tags\": []}"))
  (multiple-value-bind (start end count cut-start cut-end) (rontolisp:json-span doc "id")
    (declare (ignore start end count))
    (concatenate 'string (subseq doc 0 cut-start) (subseq doc cut-end))))   ; => "{\"tags\": []}"
```

A Proxy-Wasm filter edits the host's buffer in place the same way, by
`set-buffer`'s `:start` and `:size`, which count bytes too
([the SDK](https://github.com/making/rontolisp/tree/develop/examples/proxy-wasm)):

```console
(proxy-wasm:with-buffer (address size :response-body 0 body-size)
  (multiple-value-bind (start end members)
      (handler-case (rontolisp:json-span address size) (error () nil))
    ;; an object: a field before its closing brace
    (when (and members (eql (proxy-wasm:load8 (+ address start)) 123))
      (proxy-wasm:set-buffer :response-body
                             (concatenate 'string (if (plusp members) "," "")
                                          "\"seen\":true")
                             :start (- end 1) :size 0))))
```

Given the body as an octet vector in the place of `address size`, as a test of
the filter's logic on the interpreter or the JVM gives it, the call answers the
same offsets. To read a value rather than its bytes, use
[`rontolisp:json-value`](rontolisp-json-value.md).

## Errors

A document `json-parse` refuses signals that error, under this function's name, so
a handler tells "not JSON" from "no such value", which is `nil`. So does a path
segment that is neither a string nor a non-negative integer, and an address on the
interpreter or the JVM, which have no linear memory.

```console
CL-USER> (rontolisp:json-span "{\"a\": 1,}" "a")
Error: json-span: expected an object key string
```

On wasm-GC, catch it with `handler-case` returning `nil`, not with
`ignore-errors` under `multiple-value-bind`: the condition `ignore-errors`
answers as its second value is bound to `end` there, and arithmetic on it keeps
the condition printer, `format` among it, in the module, which made the filter
above 88 KB larger.

## Backend support

Works on every backend and in every WASM mode. The interpreter and the JVM run a
scan written in rontolisp itself over the document's octets; the wasm-GC backends
compile a native one into the program when it is used, which reads a string's
bytes or linear memory in place (an octet vector is copied once). On wasm-GC the
scan costs about 18 instructions a byte of a document of small members, and 6 a
byte inside a string.
