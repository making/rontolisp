;;;; Conway's Game of Life in rontolisp
;;;; A glider crawls diagonally across an 8x8 toroidal world. The grid is a 2-D
;;;; array of cells (1 = alive, 0 = dead) created with make-array; the world wraps
;;;; at every edge so the glider re-enters from the opposite side. Plain integer
;;;; arithmetic and O(1) aref, so it runs identically on all four backends.
;;;;
;;;; Run:
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/life.lisp
;;;;   java -jar ...-exec.jar examples/life.lisp -o Prog.class && java Prog
;;;;   java -jar ...-exec.jar examples/life.lisp -o life.wasm && wasmtime run -W gc life.wasm

;;; A fresh rows x cols grid, all dead.
(defun make-grid (rows cols)
  (make-array (list rows cols) :initial-element 0))

;;; The cell at (r, c), wrapping both coordinates onto the torus.
(defun cell-at (grid r c rows cols)
  (aref grid (mod r rows) (mod c cols)))

;;; How many of the eight neighbours of (r, c) are alive.
(defun live-neighbors (grid r c rows cols)
  (let ((sum 0) (dr -1))
    (while (<= dr 1)
      (let ((dc -1))
        (while (<= dc 1)
          (unless (and (= dr 0) (= dc 0))
            (setq sum (+ sum (cell-at grid (+ r dr) (+ c dc) rows cols))))
          (setq dc (+ dc 1))))
      (setq dr (+ dr 1)))
    sum))

;;; The next state of cell (r, c) under Conway's B3/S23 rule.
(defun next-cell (grid r c rows cols)
  (let ((n (live-neighbors grid r c rows cols))
        (alive (= (cell-at grid r c rows cols) 1)))
    (if alive
        (if (or (= n 2) (= n 3)) 1 0)
        (if (= n 3) 1 0))))

;;; Advance the whole grid one generation, returning a new grid.
(defun next-gen (grid rows cols)
  (let ((new (make-grid rows cols)) (r 0))
    (while (< r rows)
      (let ((c 0))
        (while (< c cols)
          (setf (aref new r c) (next-cell grid r c rows cols))
          (setq c (+ c 1))))
      (setq r (+ r 1)))
    new))

;;; Render a grid as ASCII ('#' alive, '.' dead).
(defun print-grid (grid rows cols)
  (let ((r 0))
    (while (< r rows)
      (let ((c 0))
        (while (< c cols)
          (princ (if (= (aref grid r c) 1) "#" "."))
          (setq c (+ c 1))))
      (terpri)
      (setq r (+ r 1)))))

(defparameter *rows* 8)
(defparameter *cols* 8)

;;; Seed a glider in the top-left corner.
(defparameter *grid* (make-grid *rows* *cols*))
(setf (aref *grid* 0 1) 1)
(setf (aref *grid* 1 2) 1)
(setf (aref *grid* 2 0) 1)
(setf (aref *grid* 2 1) 1)
(setf (aref *grid* 2 2) 1)

(let ((g *grid*) (gen 0))
  (while (<= gen 4)
    (format t "Generation ~d:~%" gen)
    (print-grid g *rows* *cols*)
    (terpri)
    (setq g (next-gen g *rows* *cols*))
    (setq gen (+ gen 1))))
