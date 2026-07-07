;;;; minesweeper-swing.lisp -- Minesweeper desktop front-end (Java / Swing).
;;;;
;;;; The rules are shared verbatim with the browser build: this file loads the
;;;; same minesweeper-core.lisp, so ONLY the drawing differs. Where the WASM
;;;; front-end (minesweeper-wasm.lisp) renders the board to HTML, this one paints a
;;;; Swing grid of clickable labels through the reusable `swing` package
;;;; (../../jvm/swing.lisp).
;;;;
;;;; Swing runs on the JVM -- interpret this file, or compile it to a .class (the
;;;; WASM backend cannot lower a java object) -- and needs a display. Run it from
;;;; anywhere; the load and the require resolve relative to this file (the
;;;; compile path inlines them):
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/browser/minesweeper/minesweeper-swing.lisp
;;;;   java -jar ...-exec.jar examples/browser/minesweeper/minesweeper-swing.lisp -o Minesweeper.class && java Minesweeper
;;;;
;;;; Left-click opens a cell, right-click flags it, and any click after the game
;;;; ends starts a fresh board. Unlike the entropy-free WASM reactor, the
;;;; interpreter has `random`, so this front-end lays its own mines -- keeping
;;;; them off the very first click so the opening move is always safe.

(load "minesweeper-core.lisp")
(require :swing "../../jvm/swing.lisp")

;;; --- board configuration (Beginner) ------------------------------------------

(defparameter *w* 9)
(defparameter *h* 9)
(defparameter *mines-count* 10)
(defparameter *cell-size* 34)

;;; The live game state and whether the mines have been laid yet (deferred until
;;; the first click so it can be made safe).
(defparameter *state* nil)
(defparameter *started* nil)

;;; --- palette -----------------------------------------------------------------

(defparameter *c-hidden* (swing:rgb 188 192 200))   ; a covered cell
(defparameter *c-open*   (swing:rgb 225 227 232))    ; an uncovered cell
(defparameter *c-boom*   (swing:rgb 214 69 65))      ; the mine you stepped on

(defparameter *fg-mine*  (swing:rgb 24 24 28))
(defparameter *fg-flag*  (swing:rgb 200 44 44))
(defparameter *fg-wrong* (swing:rgb 150 44 44))

(defparameter *glyph-mine*  "✸")
(defparameter *glyph-flag*  "⚑")
(defparameter *glyph-wrong* "✗")

;;; Classic per-number text colours (1 blue, 2 green, 3 red, ...).
(defun number-color (n)
  (cond
    ((= n 1) (swing:rgb 25 60 210))
    ((= n 2) (swing:rgb 20 130 40))
    ((= n 3) (swing:rgb 210 40 40))
    ((= n 4) (swing:rgb 20 20 140))
    ((= n 5) (swing:rgb 140 30 30))
    ((= n 6) (swing:rgb 20 130 130))
    ((= n 7) (swing:rgb 24 24 28))
    (t (swing:rgb 90 90 90))))

;;; --- the drawing layer (the only part that differs from the WASM build) ------

;; The window: rows = height, cols = width. Created up front so the drawing
;; functions can refer to it.
(defparameter *win*
  (swing:label-grid-window "rontolisp minesweeper" *h* *w* *cell-size*))

;; Paint one cell from the current state. This mirrors the WASM front-end's
;; cell-html, but sets a Swing label's background / colour / text instead of
;; emitting a <div>.
(defun paint-cell (i r c)
  (let* ((state *state*)
         (over (> (game-status state) 0))
         (is-mine (= (nth i (st-mines state)) 1))
         (is-rev  (= (nth i (st-revealed state)) 1))
         (is-flag (= (nth i (st-flags state)) 1)))
    (swing:cell-text *win* r c "")
    (cond
      ;; The mine you actually stepped on.
      ((and is-rev is-mine)
       (swing:paint *win* r c *c-boom*)
       (swing:cell-fg *win* r c *fg-mine*)
       (swing:cell-text *win* r c *glyph-mine*))
      ;; A normally revealed cell: blank, or a neighbour count 1..8.
      (is-rev
       (swing:paint *win* r c *c-open*)
       (let ((cnt (adjacent-count (st-mines state) i *w* *h*)))
         (when (> cnt 0)
           (swing:cell-fg *win* r c (number-color cnt))
           (swing:cell-text *win* r c (princ-to-string cnt)))))
      ;; Game over: reveal every remaining mine.
      ((and over is-mine)
       (swing:paint *win* r c *c-open*)
       (swing:cell-fg *win* r c *fg-mine*)
       (swing:cell-text *win* r c *glyph-mine*))
      ;; Game over: a flag that turned out to be wrong.
      ((and over is-flag)
       (swing:paint *win* r c *c-open*)
       (swing:cell-fg *win* r c *fg-wrong*)
       (swing:cell-text *win* r c *glyph-wrong*))
      ;; A flag still standing.
      (is-flag
       (swing:paint *win* r c *c-hidden*)
       (swing:cell-fg *win* r c *fg-flag*)
       (swing:cell-text *win* r c *glyph-flag*))
      ;; An ordinary covered cell.
      (t
       (swing:paint *win* r c *c-hidden*)))))

(defun update-status ()
  (let ((s (game-status *state*)))
    (swing:status *win*
                  (cond
                    ((= s 1) "  You win!  Click any cell for a new game.")
                    ((= s 2) "  Boom!  Click any cell for a new game.")
                    (t (concatenate 'string
                                    "  Mines remaining: "
                                    (princ-to-string
                                     (if *started* (mines-remaining *state*)
                                         *mines-count*))
                                    "     (left: open   right: flag)"))))))

(defun draw ()
  (dotimes (i (* *w* *h*))
    (paint-cell i (floor (/ i *w*)) (mod i *w*)))
  (update-status))

;;; --- host-side entropy (the shared core owns the placement rule) -------------

;; A random permutation of 0..n-1 (Fisher-Yates). This is the only host-specific
;; piece of mine layout: the first-click-safe placement RULE lives in the shared
;; core (place-mines); each front-end merely supplies a random ordering. The
;; browser does this shuffle in JavaScript because the --no-wasi reactor has no
;; `random`; here we use the interpreter's.
(defun shuffle-indices (n)
  (let ((v (make-array n)))
    (dotimes (i n) (setf (aref v i) i))
    (let ((i (- n 1)))
      (while (> i 0)
        (let ((j (random (+ i 1)))
              (tmp (aref v i)))
          (setf (aref v i) (aref v j))
          (setf (aref v j) tmp))
        (setq i (- i 1))))
    (let ((order nil))
      (dotimes (i n) (push (aref v (- n 1 i)) order))
      order)))

;; The first-click-safe mine layout, built by the shared core rule over a random
;; ordering -- exactly what the browser does, only the shuffle differs.
(defun make-mines (w h count safe)
  (place-mines w h count safe (shuffle-indices (* w h))))

;; Start (or restart) with an empty, mine-free board; mines are laid on the
;; first click.
(defun reset ()
  (setq *started* nil)
  (setq *state* (new-game *w* *h* (zeros (* *w* *h*))))
  (draw))

;; The single click handler bound to every cell: (row col button).
(defun on-click (r c button)
  (let ((idx (+ (* r *w*) c)))
    (cond
      ;; Any click once the game is over starts a new one.
      ((> (game-status *state*) 0) (reset))
      ;; Right-click toggles a flag.
      ((= button 3)
       (setq *state* (toggle-flag *state* idx))
       (draw))
      ;; Left-click opens; the first one lays the (safe) mines.
      (t
       (unless *started*
         (setq *state* (new-game *w* *h* (make-mines *w* *h* *mines-count* idx)))
         (setq *started* t))
       (setq *state* (reveal *state* idx))
       (draw)))))

;;; --- wire it up --------------------------------------------------------------

(swing:on-cell-click *win* (function on-click))
(reset)

(print "minesweeper window is open; close it to quit")
