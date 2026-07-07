;;;; count-vowels -- share a string with a host through WebAssembly memory
;;;;
;;;; The rontolisp counterpart of Chicory's "Using Memory to share data"
;;;; tutorial (https://chicory.dev/docs/usage/memory), where a Rust
;;;; count_vowels.wasm receives a string by pointer and returns the vowel count.
;;;; Here the module is written in Lisp instead: `count-vowels` takes a :string
;;;; and returns an :int, exported under the C-style name `count_vowels`.
;;;;
;;;; Wasm only speaks integers and floats, so a string crosses the boundary as a
;;;; (pointer, length) pair of raw UTF-8 bytes in the module's linear memory. The
;;;; module therefore exports its `memory` plus a bump allocator
;;;; `__ronto_alloc(size)` -- the host calls `__ronto_alloc(len)` to reserve
;;;; space, writes the bytes there, then calls `count_vowels(ptr, len)`. This is
;;;; exactly the alloc / writeString / call flow of the Chicory tutorial. There
;;;; is no general `dealloc` (`__ronto_alloc` is a bump allocator), but a
;;;; scalar-returning export auto-frees its per-call internal string copy on
;;;; return, so repeated calls on one instance don't leak it -- the host still
;;;; owns its input buffer (reuse one across calls, or discard the instance).
;;;;
;;;; Compiled with --no-gc it is a plain MVP module (no wasm-GC, no WASI imports)
;;;; that instantiates with an empty import object and runs on ANY WebAssembly
;;;; engine, including the pure-Java Chicory runtime -- see CountVowels.java in
;;;; this directory for the Chicory host, or the Node one-liner below.
;;;;
;;;; Build (plain MVP module, no wasm-GC):
;;;;   java -jar ...-exec.jar examples/count-vowels/count-vowels.lisp \
;;;;     -o count_vowels.wasm --no-gc --optimize
;;;;
;;;; Drive it from Node (writes the string into linear memory, reads the count):
;;;;   node -e '(async () => {
;;;;     const ex = (await WebAssembly.instantiate(
;;;;       require("fs").readFileSync("count_vowels.wasm"), {})).instance.exports;
;;;;     const bytes = Buffer.from("Hello, World!");
;;;;     const ptr = ex.__ronto_alloc(bytes.length);
;;;;     new Uint8Array(ex.memory.buffer, ptr, bytes.length).set(bytes);
;;;;     console.log(ex.count_vowels(ptr, bytes.length));   // => 3
;;;;   })()'

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

;;; Export count-vowels to the host as `count_vowels`: one string in, one int out.
(rontolisp:wasm-export 'count-vowels :as "count_vowels"
  :params '(:string) :returns :int)
