;;; hello.lisp -- the smallest useful thing: three Lisp functions the Worker
;;; calls like ordinary JavaScript functions.
;;;
;;; `rontolisp:wasm-export` gives each one a host-callable WASM signature. The
;;; scalars need no explanation -- `add(2, 3)` in JavaScript really is a call to
;;; this `add`. `greet` returns a string, which WebAssembly has no type for, so
;;; it comes back as a (pointer, length) pair into the module's linear memory
;;; and src/index.js decodes those bytes.
;;;
;;; Nothing crosses the boundary INTO the module, so the Worker never allocates
;;; anything: no `__ronto_alloc`, no arena bookkeeping. That is what makes this
;;; the simple example. ../httpbin is the same idea with a string argument,
;;; which is where the allocator arrives.
;;;
;;; build.sh compiles this with --no-gc, so the output is a plain MVP module --
;;; ~500 bytes, no wasm-GC, no WASI, no imports of any kind.

(rontolisp:wasm-export 'add :params '(:s32 :s32) :returns :s32)
(rontolisp:wasm-export 'fib :params '(:s32) :returns :s32)
(rontolisp:wasm-export 'greet :params '() :returns :string)

(defun add (a b) (+ a b))

(defun fib (n)
  "The nth Fibonacci number, computed iteratively."
  (let ((a 0) (b 1))
    (dotimes (i n)
      (let ((next (+ a b)))
        (setq a b)
        (setq b next)))
    a))

(defun greet () "Hello from Lisp, compiled to WebAssembly!")
