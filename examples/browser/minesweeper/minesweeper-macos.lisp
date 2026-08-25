;;;; minesweeper-macos.lisp -- Minesweeper desktop front-end (native macOS / AppKit).
;;;;
;;;; The third front-end over one rulebook: this file loads the same
;;;; minesweeper-core.lisp the browser build (minesweeper-wasm.lisp) and the
;;;; Swing build (minesweeper-swing.lisp) load, so ONLY the drawing differs.
;;;; Where the Swing one paints a JLabel grid through `java:`, this one builds a
;;;; real Cocoa window out of the built-in `objc` / `appkit` packages -- through
;;;; the foreign function API, with no reflection -- so it also runs in the
;;;; `rontolisp` native binary, which `java:` cannot do:
;;;;
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar \
;;;;     examples/browser/minesweeper/minesweeper-macos.lisp
;;;;   ./target/rontolisp examples/browser/minesweeper/minesweeper-macos.lisp
;;;;   java -jar ...-exec.jar examples/browser/minesweeper/minesweeper-macos.lisp \
;;;;     -o Minesweeper.class && java Minesweeper
;;;;
;;;; macOS only: the interpreter and a compiled JVM class both carry the binding;
;;;; WASM has no foreign function API and refuses (doc/en/guides/objc-appkit.md).
;;;; The rendering layer is the reusable `cocoa` package (../../macos/cocoa.lisp),
;;;; the AppKit counterpart of the Swing build's ../../jvm/swing.lisp.
;;;;
;;;; Left-click opens a cell, right-click (or Ctrl-click) flags it, and the face
;;;; starts a fresh board. Like the Swing build and unlike the entropy-free WASM
;;;; reactor, this front-end has `random`, so it lays its own mines -- keeping
;;;; them off the very first click so the opening move is always safe.

(load "minesweeper-core.lisp")
(require :cocoa "../../macos/cocoa.lisp")

;;; --- board configuration (Beginner) ------------------------------------------

(defparameter *w* 9)
(defparameter *h* 9)
(defparameter *mines-count* 10)

;;; --- geometry ----------------------------------------------------------------

(defparameter *tile* 34) ; a cell's side
(defparameter *gap* 4)   ; the space between two cells
(defparameter *pad* 18)  ; the window's margin

(defparameter *board-w* (+ (* *w* *tile*) (* (- *w* 1) *gap*)))
(defparameter *board-h* (+ (* *h* *tile*) (* (- *h* 1) *gap*)))
(defparameter *win-w* (+ *board-w* (* 2 *pad*)))

;; Laid out from the bottom up, the way AppKit measures: hint line, board,
;; header.
(defparameter *hint-y* 14)
(defparameter *board-y* 46)
(defparameter *header-y* (+ *board-y* *board-h* 16))
(defparameter *win-h* (+ *header-y* 52 16))

;;; --- palette -----------------------------------------------------------------

(defparameter *c-window* (cocoa:rgb 26 29 38))   ; the window itself
(defparameter *c-readout* (cocoa:rgb 16 18 25))  ; the two counter panels
(defparameter *c-digits* (cocoa:rgb 255 176 74)) ; their amber digits
(defparameter *c-face* (cocoa:rgb 246 200 92))   ; the new-game button
(defparameter *c-hint* (cocoa:rgb 138 146 166))  ; the line under the board

;; Covered cells alternate between two shades -- a checkerboard that makes a
;; large board readable without any grid lines.
(defparameter *c-hidden-a* (cocoa:rgb 104 116 146))
(defparameter *c-hidden-b* (cocoa:rgb 94 106 136))
(defparameter *c-open-a* (cocoa:rgb 230 233 241))
(defparameter *c-open-b* (cocoa:rgb 220 224 234))
(defparameter *c-boom* (cocoa:rgb 214 69 65)) ; the mine you stepped on

(defparameter *fg-wrong* (cocoa:rgb 150 40 40))

(defparameter *glyph-mine* "💣")
(defparameter *glyph-flag* "🚩")
(defparameter *glyph-wrong* "✗")

;;; Classic per-number text colours (1 blue, 2 green, 3 red, ...).
(defun number-color (n)
  (cond ((= n 1) (cocoa:rgb 25 60 210))
        ((= n 2) (cocoa:rgb 20 130 40))
        ((= n 3) (cocoa:rgb 210 40 40))
        ((= n 4) (cocoa:rgb 20 20 140))
        ((= n 5) (cocoa:rgb 140 30 30))
        ((= n 6) (cocoa:rgb 20 130 130))
        ((= n 7) (cocoa:rgb 24 24 28))
        (t (cocoa:rgb 90 90 90))))

;;; --- the window (the only part that differs from the Swing build) ------------

(defparameter *win*
  (cocoa:window "rontolisp minesweeper" *win-w* *win-h*
                :background *c-window*
                :dark t))

;; Left: mines remaining. Right: elapsed seconds. Both are a dark rounded panel
;; with amber digits, the way the arcade original counted.
(cocoa:panel *win* *pad* (+ *header-y* 4) 96 44 :fill *c-readout* :radius 10)

(defparameter *mine-readout*
  (cocoa:text *win* "" *pad* (+ *header-y* 4) 96 44 :size 19 :color *c-digits*))

(cocoa:panel *win* (- *win-w* *pad* 96) (+ *header-y* 4) 96 44
             :fill *c-readout*
             :radius 10)

(defparameter *time-readout*
  (cocoa:text *win* "" (- *win-w* *pad* 96) (+ *header-y* 4) 96 44
              :size 19
              :color *c-digits*))

;; Centre: the face. A tile like any other, so a click reaches it the same way.
(defparameter *face-tile*
  (cocoa:panel *win* (/ (- *win-w* 52) 2) *header-y* 52 52
               :fill *c-face*
               :radius 14))

(defparameter *face*
  (cocoa:text *win* "🙂" (/ (- *win-w* 52) 2) *header-y* 52 52 :size 26))

(defparameter *hint*
  (cocoa:text *win* "" *pad* *hint-y* *board-w* 20
              :size 12
              :color *c-hint*
              :bold nil))

(cocoa:grid *win* *h* *w*
            :size *tile*
            :gap *gap*
            :x *pad*
            :y *board-y*
            :font-size 19
            :radius 7)

;;; --- the live game -----------------------------------------------------------

(defparameter *state* nil)
(defparameter *started* nil) ; have the mines been laid yet?
(defparameter *seconds* 0)
(defparameter *generation* 0) ; which board the running timer belongs to

;; Three digits, the way the original's counters read.
(defun digits (n)
  (let ((s (princ-to-string (max 0 (min 999 n)))))
    (cond ((= (length s) 1) (concatenate 'string "00" s))
          ((= (length s) 2) (concatenate 'string "0" s))
          (t s))))

;; Paint one cell from the current state. This mirrors the Swing front-end's
;; paint-cell and the WASM one's cell-html: same rules, a different surface.
(defun paint-cell (i r c)
  (let* ((state *state*)
         (over (> (game-status state) 0))
         (dark (= (mod (+ r c) 2) 1))
         (is-mine (= (nth i (st-mines state)) 1))
         (is-rev (= (nth i (st-revealed state)) 1))
         (is-flag (= (nth i (st-flags state)) 1)))
    (cocoa:cell-text *win* r c "")
    (cond
          ;; The mine you actually stepped on.
          ((and is-rev is-mine)
           (cocoa:paint *win* r c *c-boom*)
           (cocoa:cell-text *win* r c *glyph-mine*))
          ;; A normally revealed cell: blank, or a neighbour count 1..8.
          (is-rev
           (cocoa:paint *win* r c (if dark *c-open-b* *c-open-a*))
           (let ((cnt (adjacent-count (st-mines state) i *w* *h*)))
             (when (> cnt 0)
               (cocoa:cell-fg *win* r c (number-color cnt))
               (cocoa:cell-text *win* r c (princ-to-string cnt)))))
          ;; Game over: reveal every remaining mine -- as a planted flag if the
          ;; board was swept, as a bomb if it was not.
          ((and over is-mine)
           (cocoa:paint *win* r c
                        (if (= (game-status state) 1)
                            (if dark *c-hidden-b* *c-hidden-a*)
                            (if dark *c-open-b* *c-open-a*)))
           (cocoa:cell-text *win* r c
            (if (= (game-status state) 1) *glyph-flag* *glyph-mine*)))
          ;; Game over: a flag that turned out to be wrong.
          ((and over is-flag)
           (cocoa:paint *win* r c (if dark *c-open-b* *c-open-a*))
           (cocoa:cell-fg *win* r c *fg-wrong*)
           (cocoa:cell-text *win* r c *glyph-wrong*))
          ;; A flag still standing.
          (is-flag
           (cocoa:paint *win* r c (if dark *c-hidden-b* *c-hidden-a*))
           (cocoa:cell-text *win* r c *glyph-flag*))
          ;; An ordinary covered cell.
          (t (cocoa:paint *win* r c (if dark *c-hidden-b* *c-hidden-a*))))))

(defun mines-shown ()
  (cond ((= (game-status *state*) 1) 0)
        (*started* (mines-remaining *state*))
        (t *mines-count*)))

(defun face-glyph (status)
  (cond ((= status 1) "😎") ((= status 2) "😵") (t "🙂")))

(defun hint-text (status)
  (cond ((= status 1) "Swept it clean.  Click the face to play again.")
   ((= status 2) "Boom.  Click the face to play again.")
   (t "Left-click opens a cell   ·   right-click (or Ctrl-click) flags it")))

(defun update-header ()
  (let ((s (game-status *state*)))
    (cocoa:set-text *mine-readout*
                    (concatenate 'string "💣 " (digits (mines-shown))))
    (cocoa:set-text *time-readout*
                    (concatenate 'string "⏱ " (digits *seconds*)))
    (cocoa:set-text *face* (face-glyph s))
    (cocoa:set-text *hint* (hint-text s))))

(defun draw ()
  (dotimes (i (* *w* *h*)) (paint-cell i (floor (/ i *w*)) (mod i *w*)))
  (update-header))

;;; --- host-side entropy (the shared core owns the placement rule) -------------

;; A random permutation of 0..n-1 (Fisher-Yates), exactly the Swing build's: the
;; first-click-safe placement RULE lives in the shared core (place-mines), and a
;; front-end supplies only the random ordering. The browser shuffles in
;; JavaScript because the --no-wasi reactor has no `random`; here, as in the
;; Swing build, the interpreter has one.
(defun shuffle-indices (n)
  (let ((v (make-array n)))
    (dotimes (i n) (setf (aref v i) i))
    (let ((i (- n 1)))
      (while (> i 0)
        (let ((j (random (+ i 1))) (tmp (aref v i)))
          (setf (aref v i) (aref v j))
          (setf (aref v j) tmp))
        (setq i (- i 1))))
    (let ((order nil))
      (dotimes (i n) (push (aref v (- n 1 i)) order))
      order)))

(defun make-mines (w h count safe)
  (place-mines w h count safe (shuffle-indices (* w h))))

;;; --- the clock ---------------------------------------------------------------

;; One tick a second, started by the first click and stopped by the end of the
;; game -- or by the next new board, which is what the generation check is for
;; (a timer answering nil invalidates itself).
(defun start-clock ()
  (let ((mine *generation*))
    (cocoa:animate 1
                   (lambda ()
                     (when (and (= mine *generation*)
                                (= (game-status *state*) 0))
                       (setq *seconds* (+ *seconds* 1))
                       (update-header)
                       t)))))

;;; --- interaction -------------------------------------------------------------

;; Start (or restart) with an empty, mine-free board; the mines are laid on the
;; first click, around it.
(defun reset ()
  (setq *generation* (+ *generation* 1))
  (setq *started* nil)
  (setq *seconds* 0)
  (setq *state* (new-game *w* *h* (zeros (* *w* *h*))))
  (draw))

;; The single click handler bound to every cell: (row col button), where the
;; button is 1 for a left click and 3 for a right one.
(defun on-click (r c button)
  (let ((idx (+ (* r *w*) c)))
    (cond
          ;; Any click once the game is over starts a new one.
          ((> (game-status *state*) 0) (reset))
          ;; Right-click toggles a flag.
          ((= button 3)
           (setq *state* (toggle-flag *state* idx))
           (draw))
          ;; Left-click opens; the first one lays the (safe) mines and starts the
          ;; clock.
          (t
           (unless *started*
             (setq *state*
                   (new-game *w* *h* (make-mines *w* *h* *mines-count* idx)))
             (setq *started* t)
             (start-clock))
           (setq *state* (reveal *state* idx))
           (draw)))))

;;; --- wire it up --------------------------------------------------------------

(cocoa:on-cell-click *win* (function on-click))
(cocoa:clickable *face-tile* (lambda (button) (reset)))
(cocoa:clickable *face* (lambda (button) (reset)))
(reset)

(format t "minesweeper window ~a is open; close it to quit~%"
        (objc:send (cocoa:ns-window *win*) "windowNumber"))

;; A script's process ends when its last form returns, so wait for the close.
(cocoa:wait *win*)
