# read-from-string

`(read-from-string string &optional (eof-error-p t) eof-value &key (start 0) end preserve-whitespace)`

Parses and returns one datum from the given string. It reuses the same reader as [`read`](read.md), so on the compiled backends it accepts the same frontend-parity syntax (`#S(...)`, `#(...)`, `#\a`, ratios, radix integers, ... -- with `#.`, `#+`/`#-` and reader labels signaling), and `(read-from-string (prin1-to-string x))` round-trips. The interpreter evaluates a `#.` datum in place (binding `*read-eval*` to `nil` makes it signal, per the standard). An input holding no datum signals `end-of-file`; a malformed datum signals `reader-error`. Both carry a string-input stream over the offending text (`stream-error-stream`). A datum the text ends inside of also signals `end-of-file`, and a `)` that closes nothing, a `.` with nothing before it in a list (`( . a)`) and more than one object after the dot (`(a . b c)`) signal `reader-error`, on every backend. On the compiled backends these two conditions carry no stream (`stream-error-stream` answers `nil`), and the other malformed data (a bad `#x` digit, an unknown character name, ...) signal a catchable `simple-error`. Works on all four backends and is usable as a first-class value (`#'read-from-string`).

```lisp
(read-from-string "(+ 1 2)") ; => (+ 1 2)
```

The result is the parsed list `(+ 1 2)` as data, not its evaluation; pass it to `eval` if you want the value `3`.

Symbols are read with the reader's [upcasing](../../guides/reader-case.md), identically on every backend: your symbols and the standard names alike read upcased (there is no fold to a lowercase spelling).

```lisp
(read-from-string "foo") ; => FOO
```


## The optional and keyword arguments

The arguments after the string are Common Lisp's, on all four backends, in call position and through `#'read-from-string`:

- `:start` / `:end` bound the text read; the stop index counts from the start of the whole string. A bad bound (negative, not an integer, a `nil` start, past the length or the fill pointer, a start past the end) signals the `type-error` [`subseq`](subseq.md) signals for it, before anything is read.
- An input (or window) holding no datum -- only whitespace and comments -- signals `end-of-file` when `eof-error-p` is true and answers `eof-value` otherwise, with the window's end as the stop index. A datum the text ends inside of signals `end-of-file` whatever `eof-error-p` says, and a `)` that closes nothing, a `.` with nothing before it in a list and more than one object after the dot signal `reader-error`.
- `:preserve-whitespace` true leaves a whitespace terminator unread, so the stop index points at it.
- An unknown keyword or an odd keyword list signals `program-error`; `:allow-other-keys` and a repeated keyword (the first one wins) behave as in Common Lisp.

```lisp
(multiple-value-list (read-from-string " 12 34" t nil :start 3)) ; => (34 6)
(multiple-value-list (read-from-string "   " nil :none)) ; => (:NONE 3)
(multiple-value-list (read-from-string "123  " t nil :preserve-whitespace t)) ; => (123 3)
```

Such a call reads its datum the way [`read`](read.md) does, so it resolves a `#+`/`#-` guard on every backend, against the live `*features*`: a guard that fails skips the form behind it and the call answers the next datum. The one-argument call resolves guards on the interpreter only; the compiled readers signal on any `#+`/`#-` there.

```lisp
(multiple-value-list (read-from-string "#+nope (a b) c" nil nil)) ; => (C 14)
```

## The stop index, and `*read-suppress*`

Like Common Lisp's, `read-from-string` answers a SECOND value: the index of the first character it did not read. A whitespace terminator is consumed with the datum it terminates -- a list or a character literal exactly like a token -- and a terminating macro character is given back, so `"abc  def"` stops at 4 and `"(1 2) x"` at 6 (past the space after the `)`). The index counts characters, not bytes: `"日本 x"` stops at 3, and a supplementary-plane character counts as one, on every backend. Ask for it with a [multiple-value](../macros/multiple-value-bind.md) consumer; a caller that wants only the datum pays nothing for it. The function object `#'read-from-string` answers the same two values. Only the first datum is read: text after it is never examined, so `"5.)"` and `"abc)"` read `5` and `ABC` on every backend, and a malformed tail after a complete datum is not an error.

```lisp
(multiple-value-list (read-from-string "abc")) ; => (ABC 3)
(multiple-value-list (read-from-string "abc  def")) ; => (ABC 4)
(nth-value 1 (read-from-string "(1 2) x")) ; => 6
```

Binding `*read-suppress*` to true makes the reader consume exactly the characters a real read would and answer `nil`, suppressing every error the datum would otherwise signal -- an unknown package, a bogus character name, an out-of-range digit. This is what a `#+`/`#-` guard does to the form it skips, and it is the interpreter's behavior: the runtime reader of compiled output has no suppressed mode.

```lisp
(let ((*read-suppress* t)) (read-from-string "nonexistent-package::foo")) ; => NIL
(let ((*read-suppress* t)) (multiple-value-list (read-from-string "123.45"))) ; => (NIL 6)
```
