;;;; N-Queens solver in rontolisp
;;;; Enumerates every solution to the N-Queens problem and prints one board,
;;;; using plain recursion and backtracking over lists. Written functionally
;;;; (each function takes the board size `n` as an argument) so it compiles on
;;;; all three backends -- the JVM/WASM compilers cannot yet read a global
;;;; special variable from inside a function body. Integer/list only.
;;;;
;;;; Run:
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/console/nqueens.lisp
;;;;   java -jar ...-exec.jar examples/console/nqueens.lisp -o Prog.class && java Prog
;;;;   java -jar ...-exec.jar examples/console/nqueens.lisp -o nqueens.wasm && wasmtime run -W gc nqueens.wasm

;;; A partial placement is a list of column positions, one per already-placed
;;; row, most recent row at the head. The queen `d` rows back sits at (nth (- d 1)
;;; cols). Can a new queen go in `col` without being attacked by one in `cols`?
(defun safep (col cols)
  (let ((ok t) (d 1) (rest cols))
    (while rest
      (let ((c (car rest)))
        (when (or (= c col) (= (abs (- c col)) d)) (setq ok nil)))
      (setq d (+ d 1))
      (setq rest (cdr rest)))
    ok))

;;; All complete boards reachable from `cols` (which already has `row` queens),
;;; as a list of solutions; each solution is a list of column positions by row.
(defun solve (n row cols)
  (if (= row n)
      (list (reverse cols))
      (let ((acc nil) (col 0))
        (while (< col n)
          (when (safep col cols)
            (setq acc (append acc (solve n (+ row 1) (cons col cols)))))
          (setq col (+ col 1)))
        acc)))

(defun queens (n) (solve n 0 nil))

;;; Print a board given a list of column positions, one per row.
(defun print-board (n cols)
  (dolist (c cols)
    (dotimes (i n) (princ (if (= i c) "Q " ". ")))
    (terpri)))

(defparameter *n* 6)
(format t "Solving ~d-Queens...~%" *n*)
(let ((sols (queens *n*)))
  (format t "Total solutions: ~d~%~%" (length sols))
  (format t "First solution found:~%")
  (print-board *n* (car sols)))
