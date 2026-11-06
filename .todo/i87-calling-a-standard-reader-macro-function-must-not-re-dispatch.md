# i87. Calling a standard reader-macro function must not re-dispatch through `*readtable*`

Difficulty: Medium

`%rt-call-standard` implements a standard macro character's function as "unread the character,
then `read`" (`.kb/readtables.md`). When the current readtable has replaced that character, the
`read` dispatches to the replacement, which calls the standard function again: a stack overflow
(measured 2026-11-06, interpreter):

    (defvar *std* (get-macro-character #\" (copy-readtable nil)))
    (defun my-reader (stream char) (funcall *std* stream char))
    (set-macro-character #\" #'my-reader nil (setf *readtable* (copy-readtable nil)))
    "plain"   ; => stack overflow

Consumer: pythonic-string-reader (`read-multiline-string` falls back to the standard `"`
function), so `40ants-doc` (its `locatives/base.lisp` is `(in-readtable pythonic-string-syntax)`)
stops with a stack overflow. With the fallback patched to rebind `*readtable*`, 40ants-doc loads.

The standard function must read with the STANDARD syntax for its own character (the string
reader reads to the closing `"`, `'` reads one datum and wraps it, `(` reads a list whose
elements still use the current readtable). Check every character `%rt-call-standard` covers,
and the compile-path readers where they call it. ci-spec case: the snippet above plus `'` and `(`.
