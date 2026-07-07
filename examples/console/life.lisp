;;;; Conway's Game of Life in rontolisp -- console front-end.
;;;;
;;;; The simulation lives in life-core.lisp; this file only loads it and prints a
;;;; handful of generations as ASCII. life-gui.lisp renders the same core in a
;;;; Swing window (JVM only). The load resolves relative to this file, so
;;;; it runs from anywhere and on all three backends.
;;;;
;;;; Run:
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/console/life.lisp
;;;;   java -jar ...-exec.jar examples/console/life.lisp -o life.wasm && wasmtime run -W gc life.wasm

(load "life-core.lisp")

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

(let ((g (life-seed)) (gen 0))
  (while (<= gen 6)
    (format t "Generation ~d (population ~d):~%" gen (population g *rows* *cols*))
    (print-grid g *rows* *cols*)
    (terpri)
    (setq g (next-gen g *rows* *cols*))
    (setq gen (+ gen 1))))
