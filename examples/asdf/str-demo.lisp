;; Loads the REAL cl-str (MIT, vindarel) and, under it, cl-unicode (BSD
;; 2-Clause, Dr. Edmund Weitz) and cl-ppcre-unicode via ql:quickload -- network
;; on the first run. Run with:
;;   rontolisp examples/asdf/str-demo.lisp
;;
;; Runs on all four backends, like every other demo here -- but it is the only
;; one with no E2E test of its own, because cl-unicode ships 30 MB of Unicode
;; character database and is not vendored into this repository.  Its tables are
;; a few MB of data: more constants than one .class may name, so they travel as
;; their own printed text in a couple of hundred string literals and are read
;; back at load, and each range table is built the first time its property is
;; asked for.  A compiled artifact is around 5 MB.
;;
;; cl-unicode is also the one library here whose sources are INCOMPLETE as
;; shipped: three of the eight components its system names do not exist in the
;; release, and real ASDF generates them by running a second system that parses
;; the bundled Unicode character database and writes them next to the sources.
;; rontolisp does that build step itself, from the same files, at load time.

(ql:quickload "str")

;; The case family, over cl-change-case.
(print (str:title-case "HELLO LISP!"))
(print (str:camel-case "hello lisp world"))
(print (str:snake-case "HelloLispWorld"))
(print (str:param-case "HelloLispWorld"))

;; Splitting, joining and trimming.
(print (str:words "  foo bar  baz "))
(print (str:join ", " (list "a" "b" "c")))
(print (str:trim "  padded  "))
(print (str:shorten 9 "a longer sentence"))

;; The predicates are Unicode-aware because they run cl-ppcre's \p{L} and
;; \p{N} escapes, which resolve through cl-unicode's property tables -- so a
;; hiragana string is letters and an ASCII-only test would disagree.
(print (str:lettersp "abc"))
(print (str:lettersp "こんにちは"))
(print (str:alphanump "abc123"))
(print (str:remove-punctuation "hello, world!"))

;; cl-unicode's own API, on the same tables.
(print (cl-unicode:general-category #\A))
(print (cl-unicode:script (code-char #x3042)))
(print (cl-unicode:code-block #\A))
(print (cl-unicode:unicode-name #\A))
(print (cl-unicode:character-named "LATIN SMALL LETTER A"))
(print (cl-unicode:numeric-value (code-char #x00BD)))

;; A Hangul syllable's name is COMPUTED from its code point rather than looked
;; up, so the whole 11,172-syllable block costs no table at all.
(print (cl-unicode:unicode-name (code-char #xAC00)))

;; NFC / NFD, as code-point lists (which is what cl-unicode answers).
(print (cl-unicode:normalization-form-d (string (code-char #x00E9))))
(print (cl-unicode:normalization-form-c (list 101 769)))
