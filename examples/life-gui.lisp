;;;; Conway's Game of Life -- Swing front-end.
;;;;
;;;; Loads the rendering-free core (life-core.lisp) and the reusable grid view
;;;; (swing.lisp), then animates successive generations: each timer tick advances
;;;; the world one step, repaints every cell, and updates the status line. A small
;;;; toroidal Life world decays to a stable "ash" of still lifes and blinkers
;;;; after a couple hundred generations, so once it has run long enough this demo
;;;; reseeds with the classic patterns plus a fresh random soup to stay lively.
;;;; Close the window to stop. JVM only (Swing), and needs a display.
;;;;
;;;; Run from anywhere (the loads resolve relative to this file; the compile
;;;; path inlines them):
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/life-gui.lisp
;;;;   java -jar ...-exec.jar examples/life-gui.lisp -o Life.class && java Life

(load "life-core.lisp")
(load "swing.lisp")

(defparameter *color-alive* (swing-rgb 90 200 250))
(defparameter *color-dead* (swing-rgb 28 30 36))

(defparameter *win* (swing-grid-window "rontolisp life" *rows* *cols* 18))

;; The current world and generation counter, advanced by the animation tick.
(defparameter *g* (life-seed))
(defparameter *gen* 0)

;; Repaint every cell from a grid: alive cells cyan, dead cells dark.
(defun render-life (grid)
  (let ((r 0))
    (while (< r *rows*)
      (let ((c 0))
        (while (< c *cols*)
          (swing-paint *win* r c
                       (if (= (aref grid r c) 1) *color-alive* *color-dead*))
          (setq c (+ c 1))))
      (setq r (+ r 1)))))

;; After this many generations the world has usually settled into ash; reseed.
(defparameter *reseed-at* 160)

;; Sprinkle n random live cells into the grid.
(defun sprinkle (grid n)
  (let ((i 0))
    (while (< i n)
      (setf (aref grid (random *rows*) (random *cols*)) 1)
      (setq i (+ i 1)))))

(render-life *g*)

(swing-animate 120
  (lambda ()
    (when (>= *gen* *reseed-at*)
      (setq *g* (life-seed))
      (sprinkle *g* 140)
      (setq *gen* 0))
    (swing-status *win*
                  (concatenate 'string "  generation: " (princ-to-string *gen*)
                               "   population: "
                               (princ-to-string (population *g* *rows* *cols*))))
    (render-life *g*)
    (setq *g* (next-gen *g* *rows* *cols*))
    (setq *gen* (+ *gen* 1))
    t))

(print "life window is open; close it to stop the simulation")
