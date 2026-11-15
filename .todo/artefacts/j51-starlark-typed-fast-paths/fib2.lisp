(DEFUN |fib| (|n|) (BLOCK %SL-FN (IF (if (integerp |n|) (< |n| 2) (RONTOLISP::%STARLARK-LESS |n| 2)) (RETURN-FROM %SL-FN |n|)) (let ((a (|fib| (if (integerp |n|) (- |n| 1) (RONTOLISP::%STARLARK-SUB |n| 1)))) (b (|fib| (if (integerp |n|) (- |n| 2) (RONTOLISP::%STARLARK-SUB |n| 2))))) (if (and (integerp a) (integerp b)) (+ a b) (RONTOLISP::%STARLARK-ADD a b)))))
(SETQ %SL-FILE (RONTOLISP::%STARLARK-FILE-BEGIN))
(RONTOLISP::%STARLARK-B-PRINT " " (RONTOLISP::%STARLARK-MAKE-TUPLE (|fib| 35)))
(RONTOLISP::%STARLARK-FILE-END %SL-FILE)
