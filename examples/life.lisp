;;;; Conway's Game of Life in rontolisp
;;;; A 30x24 toroidal world seeded with several classic patterns -- a glider, a
;;;; lightweight spaceship (LWSS), a blinker, a toad, a beacon, and a pulsar --
;;;; advanced through several generations. The grid is a 2-D array of cells
;;;; (1 = alive, 0 = dead) created with make-array; the world wraps at every edge,
;;;; so the moving ships re-enter from the opposite side. Plain integer arithmetic
;;;; and O(1) aref, so it runs identically on all four backends.
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

;;; Total number of live cells in the grid.
(defun population (grid rows cols)
  (let ((sum 0) (r 0))
    (while (< r rows)
      (let ((c 0))
        (while (< c cols)
          (setq sum (+ sum (aref grid r c)))
          (setq c (+ c 1))))
      (setq r (+ r 1)))
    sum))

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

;;; Stamp a list of (row col) offsets into the grid, rooted at (top, left).
(defun stamp (grid top left coords)
  (dolist (rc coords)
    (setf (aref grid (+ top (car rc)) (+ left (car (cdr rc)))) 1)))

(defparameter *rows* 24)
(defparameter *cols* 30)
(defparameter *grid* (make-grid *rows* *cols*))

;;; A glider, crawling down-right from the top-left corner.
(stamp *grid* 1 1 '((0 1) (1 2) (2 0) (2 1) (2 2)))

;;; A lightweight spaceship (LWSS), gliding left along the top band.
(stamp *grid* 2 18 '((0 1) (0 4) (1 0) (2 0) (2 4) (3 0) (3 1) (3 2) (3 3)))

;;; A vertical blinker (period 2).
(stamp *grid* 10 4 '((0 0) (1 0) (2 0)))

;;; A toad (period 2).
(stamp *grid* 14 4 '((0 1) (0 2) (0 3) (1 0) (1 1) (1 2)))

;;; A beacon (period 2).
(stamp *grid* 18 4 '((0 0) (0 1) (1 0) (2 3) (3 2) (3 3)))

;;; A pulsar (period 3), the iconic large oscillator, centred on the right side.
(stamp *grid* 5 14
       '((0 2) (0 3) (0 4) (0 8) (0 9) (0 10)
         (2 0) (2 5) (2 7) (2 12)
         (3 0) (3 5) (3 7) (3 12)
         (4 0) (4 5) (4 7) (4 12)
         (5 2) (5 3) (5 4) (5 8) (5 9) (5 10)
         (7 2) (7 3) (7 4) (7 8) (7 9) (7 10)
         (8 0) (8 5) (8 7) (8 12)
         (9 0) (9 5) (9 7) (9 12)
         (10 0) (10 5) (10 7) (10 12)
         (12 2) (12 3) (12 4) (12 8) (12 9) (12 10)))

(let ((g *grid*) (gen 0))
  (while (<= gen 6)
    (format t "Generation ~d (population ~d):~%" gen (population g *rows* *cols*))
    (print-grid g *rows* *cols*)
    (terpri)
    (setq g (next-gen g *rows* *cols*))
    (setq gen (+ gen 1))))
