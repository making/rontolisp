;;;; count-vowels -- share a string with a host through WebAssembly memory
;;;;
;;;; The rontolisp counterpart of the classic "share a string through Wasm
;;;; memory" host tutorial (see Endive's memory guide,
;;;; https://endive.run/docs/core/memory), where a count_vowels.wasm receives a
;;;; string by pointer and returns the vowel count. Here the module is written in
;;;; Lisp instead: `count-vowels` takes a :string and returns an :int.
;;;;
;;;; Wasm only speaks integers and floats, so a string crosses the boundary as a
;;;; (pointer, length) pair of raw UTF-8 bytes in the module's linear memory. The
;;;; module therefore exports its `memory` plus a bump allocator
;;;; `__ronto_alloc(size)`: the host reserves space, writes the bytes there, then
;;;; calls `count-vowels(ptr, len)`. This is exactly the alloc / writeString /
;;;; call flow of the tutorial.
;;;;
;;;; A bump allocator never frees, so the real question is who reclaims that
;;;; memory in a host that keeps one instance alive and calls it in a loop. The
;;;; same source answers it three ways, depending on how it is compiled --
;;;; README.md drives each one from Node:
;;;;
;;;;   --no-gc --optimize              the host pops the bump heap itself, with
;;;;                                   the arena API __ronto_alloc_mark/_reset
;;;;   --no-wasi --optimize            wasm-GC: the engine collects the Lisp side;
;;;;                                   the host reuses one input buffer
;;;;   --no-gc --component --optimize  a component: the canonical ABI passes the
;;;;                                   string and post-return frees everything --
;;;;                                   the host writes no memory code at all
;;;;
;;;; The export keeps its Lisp name (a component-model export name must be
;;;; lower-kebab-case, so no :as "count_vowels" rename here), which lets one
;;;; directive serve all three builds.

;;; A character is its code point everywhere (in --no-gc a character simply IS
;;; its i64 code, so char= is an ordinary numeric comparison). Test both cases so
;;; the count is case-insensitive.
(defun vowelp (c)
  (or (char= c #\a) (char= c #\e) (char= c #\i) (char= c #\o) (char= c #\u)
      (char= c #\A) (char= c #\E) (char= c #\I) (char= c #\O) (char= c #\U)))

;;; Count the vowels in s by walking it character by character. Pure compute:
;;; no cons, list, hash or I/O, so it stays inside the --no-gc subset.
(defun count-vowels (s)
  (let ((n 0))
    (dotimes (i (length s))
      (when (vowelp (char s i))
        (setq n (+ n 1))))
    n))

;;; Export count-vowels to the host: one string in, one int out.
(rontolisp:wasm-export 'count-vowels
  :params '(:string) :returns :int)
