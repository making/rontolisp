;;;; Conway's Game of Life -- native macOS / AppKit front-end.
;;;;
;;;; The Swing front-end's twin (examples/jvm/life-gui.lisp): it loads the same
;;;; rendering-free core (life-core.lisp), so ONLY the drawing differs. Where that
;;;; one paints a JPanel grid through `java:`, this one paints a grid of Cocoa
;;;; panels built on the built-in `appkit` package -- a colour, a panel, a label,
;;;; a click, a repeating timer -- through the foreign function API, with no
;;;; reflection, so it also runs in the `rontolisp` native binary, which `java:`
;;;; cannot do:
;;;;
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/macos/life-macos.lisp
;;;;   ./target/rontolisp examples/macos/life-macos.lisp
;;;;   java -jar ...-exec.jar examples/macos/life-macos.lisp -o Life.class --class-name Life && java Life
;;;;
;;;; macOS only, and it needs a display, so it is not in examples.yaml; WASM has
;;;; no foreign function API and refuses (doc/en/guides/objc-appkit.md).
;;;;
;;;; Each timer tick advances the world one generation and repaints the cells that
;;;; changed. A small toroidal world decays to a stable "ash" of still lifes and
;;;; blinkers after a couple of hundred generations, so this demo reseeds itself
;;;; to stay lively -- or you can seed it by hand: a left click on a cell brings
;;;; it to life, a right click kills it, and the button drops a fresh soup in.
;;;; Close the window to stop.

(load "../console/life-core.lisp")
(require :cocoa "cocoa.lisp")

;;; --- geometry and palette ----------------------------------------------------

(defparameter *tile* 16) ; a cell's side
(defparameter *gap* 2)   ; the space between two cells
(defparameter *pad* 14)  ; the window's margin

(defparameter *board-w* (+ (* *cols* *tile*) (* (- *cols* 1) *gap*)))
(defparameter *board-h* (+ (* *rows* *tile*) (* (- *rows* 1) *gap*)))
(defparameter *win-w* (+ *board-w* (* 2 *pad*)))

;; Laid out from the bottom up, the way AppKit measures: board, then the header
;; carrying the button and the status line.
(defparameter *board-y* *pad*)
(defparameter *header-y* (+ *board-y* *board-h* 12))
(defparameter *win-h* (+ *header-y* 30 *pad*))

(defparameter *c-window* (appkit:color 22 24 30))
(defparameter *c-alive* (appkit:color 90 200 250))
(defparameter *c-dead* (appkit:color 34 37 46))
(defparameter *c-status* (appkit:color 150 158 176))

;;; --- the window --------------------------------------------------------------

(defparameter *win*
  (appkit:window "rontolisp life"
                 :width *win-w*
                 :height *win-h*
                 :background *c-window*
                 :dark t))

;; A Life cell shows no text, so the board is panels alone.
(defparameter *board*
  (cocoa:grid *win* *rows* *cols*
              :size *tile*
              :gap *gap*
              :x *pad*
              :y *board-y*
              :radius 3
              :labels nil))

(defparameter *status*
  (appkit:label *win* ""
                :x (+ *pad* 124)
                :y *header-y*
                :width (- *board-w* 124)
                :height 30
                :size 13
                :color *c-status*))

;;; --- the live world ----------------------------------------------------------

(defparameter *g* (life-seed))
(defparameter *gen* 0)

;; What the board is currently showing, so a tick repaints only the cells that
;; changed: -1 everywhere means "nothing painted yet".
(defparameter *shown* (make-array (list *rows* *cols*) :initial-element -1))

(defun render (grid)
  (objc:on-main
   (lambda ()
     (dotimes (r *rows*)
       (dotimes (c *cols*)
         (let ((v (aref grid r c)))
           (unless (= v (aref *shown* r c))
             (setf (aref *shown* r c) v)
             (cocoa:paint *board* r c (if (= v 1) *c-alive* *c-dead*))))))
     nil)))

(defun show-status ()
  (appkit:set-text *status*
                   (concatenate 'string "generation " (princ-to-string *gen*)
                    "   ·   population "
                    (princ-to-string (population *g* *rows* *cols*)))))

;; Sprinkle n random live cells into the grid.
(defun sprinkle (grid n)
  (dotimes (i n) (setf (aref grid (random *rows*) (random *cols*)) 1)))

;; After this many generations the world has usually settled into ash; reseed.
(defparameter *reseed-at* 160)

(defun reseed ()
  (setq *g* (life-seed))
  (sprinkle *g* 140)
  (setq *gen* 0))

;;; --- interaction -------------------------------------------------------------

;; A click edits the world under the simulation: left brings a cell to life,
;; right (or Ctrl-click) kills it. The next tick picks the change up, since the
;; grid IS the state.
(cocoa:on-cell-click *board*
                     (lambda (r c button)
                       (setf (aref *g* r c) (if (= button 3) 0 1))
                       (render *g*)))

(appkit:button *win* "Reseed"
               :x *pad*
               :y *header-y*
               :width 110
               :height 30
               :on-click (lambda ()
                           (reseed)
                           (render *g*)
                           (show-status)))

;;; --- the clock ---------------------------------------------------------------

(render *g*)
(show-status)

;; The tick runs on thread 0, inside AppKit's event loop, so it may paint
;; directly; answering t keeps the timer alive.
(appkit:timer 0.12
              (lambda ()
                (when (>= *gen* *reseed-at*) (reseed))
                (render *g*)
                (show-status)
                (setq *g* (next-gen *g* *rows* *cols*))
                (setq *gen* (+ *gen* 1))
                t))

(format t "life window ~a is open; close it to stop the simulation~%"
        (objc:send *win* "windowNumber"))

;; A script's process ends when its last form returns, so wait for the close.
(appkit:wait *win*)
