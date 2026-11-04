# i32. `with-standard-io-syntax` binds only `*package*`

Difficulty: Medium

Measured 2026-11-04, against SBCL 2.2.9. `LispMacroExpander.expandWithStandardIoSyntax` lets `*package*`
alone, and `doc/en/reference/macros/with-standard-io-syntax.md` records a deviation for three printer
variables. The measurement shows more of them leak.

```lisp
(let ((*print-base* 2)) (with-standard-io-syntax (write-to-string 5)))         ; SBCL "5",    here "101"
(let ((*print-base* 2)) (with-standard-io-syntax *print-base*))                ; SBCL 10,     here 2
(let ((*print-case* :downcase)) (with-standard-io-syntax (prin1-to-string 'foo))) ; SBCL "FOO", here "foo"
(let ((*read-base* 2)) (with-standard-io-syntax *read-base*))                  ; SBCL 10,     here 2
(let ((*print-pretty* t)) (with-standard-io-syntax *print-pretty*))            ; SBCL NIL,    here T
(with-standard-io-syntax *print-readably*)                                     ; SBCL T,      here NIL
```

Consumer: `(asdf:test-system :quri-test)` fails 2 of 6 files. quri's `src/encode.lisp:112` and
`src/uri.lisp:50` format numbers under it, and `t/encode.lisp:27`, `t/quri.lisp:149` bind `*print-base*`
to 2 around them to pin exactly that.

Bind the CLHS 23.1.2 table on all four backends. `*print-readably*` T is the risky one: the printer honors
it, so a body printing an unreadable object starts to signal. Try the cached libraries with it first
(`map-symbol` in contextl prints under this macro, `.kb/clos.md`). This form also decides whether a program
keeps its `*package*` assignments (`.kb/packages.md`). Update the deviation in `doc/en` and `doc/ja`.
