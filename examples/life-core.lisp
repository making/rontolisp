;;;; life-core.lisp -- Conway's Game of Life, rendering-free core.
;;;;
;;;; Shared by life.lisp (console) and life-gui.lisp (Swing). A 30x24 toroidal
;;;; world is a 2-D array of cells (1 = alive, 0 = dead); the world wraps at every
;;;; edge. Plain integer arithmetic and O(1) aref. `life-seed` returns a fresh grid
;;;; stamped with several classic patterns (glider, LWSS, blinker, toad, beacon,
;;;; pulsar); the drivers decide how to display successive generations.

(defparameter *rows* 24)
(defparameter *cols* 30)

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

;;; Stamp a list of (row col) offsets into the grid, rooted at (top, left).
(defun stamp (grid top left coords)
  (dolist (rc coords)
    (setf (aref grid (+ top (car rc)) (+ left (car (cdr rc)))) 1)))

;;; A fresh world seeded with several classic patterns.
(defun life-seed ()
  (let ((grid (make-grid *rows* *cols*)))
    ;; A glider, crawling down-right from the top-left corner.
    (stamp grid 1 1 '((0 1) (1 2) (2 0) (2 1) (2 2)))
    ;; A lightweight spaceship (LWSS), gliding left along the top band.
    (stamp grid 2 18 '((0 1) (0 4) (1 0) (2 0) (2 4) (3 0) (3 1) (3 2) (3 3)))
    ;; A vertical blinker (period 2).
    (stamp grid 10 4 '((0 0) (1 0) (2 0)))
    ;; A toad (period 2).
    (stamp grid 14 4 '((0 1) (0 2) (0 3) (1 0) (1 1) (1 2)))
    ;; A beacon (period 2).
    (stamp grid 18 4 '((0 0) (0 1) (1 0) (2 3) (3 2) (3 3)))
    ;; A pulsar (period 3), centred on the right side.
    (stamp grid 5 14
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
    grid))
