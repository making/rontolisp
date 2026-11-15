(DEFUN |loop| (|n|) (LET ((|total| 0) (|i| 0)) (LET* ((%SL-STOP (if (integerp |n|) |n| (error "range: got ~a, want int" (RONTOLISP::%STARLARK-B-TYPE |n|))))) (TAGBODY %SL-TOP5 (IF (< |i| %SL-STOP) (PROGN (SETQ |total| (+ |total| (mod |i| 7))) (SETQ |i| (+ |i| 1)) (GO %SL-TOP5))))) |total|))
(SETQ %SL-FILE (RONTOLISP::%STARLARK-FILE-BEGIN))
(RONTOLISP::%STARLARK-B-PRINT " " (RONTOLISP::%STARLARK-MAKE-TUPLE (|loop| 30000000)))
(RONTOLISP::%STARLARK-FILE-END %SL-FILE)
