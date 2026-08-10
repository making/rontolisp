;;; No library at all: three Lisp functions JavaScript calls directly.
;;;
;;; rontolisp:wasm-export gives each one a host-callable signature. A :string
;;; comes back as a (pointer, length) pair into linear memory, which
;;; src/index.js decodes; nothing crosses INTO the module, so the host never
;;; allocates. build.sh compiles this with --no-gc -- a plain MVP module.

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
