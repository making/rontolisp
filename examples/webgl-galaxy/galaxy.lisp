;;;; galaxy.lisp -- a spiral galaxy computed in Lisp, rendered by WebGL.
;;;;
;;;; This is the rontolisp:wasm-import showcase: the page (index.html) exposes
;;;; two host functions from JavaScript under the import module "gl", and this
;;;; program calls them like ordinary Lisp functions. Per animation frame the
;;;; browser calls the exported `frame`, and Lisp answers with one
;;;; (draw-particle x y hue size) call per star; the page batches those into a
;;;; single additive-blended WebGL point draw.
;;;;
;;;; Compiled ahead of time to a --no-wasi reactor (build.sh), so the module
;;;; imports nothing but the two "gl" functions and instantiates in any
;;;; wasm-GC-capable browser with a two-line import object.
;;;;
;;;; The galaxy is the classic density-wave toy model: every star follows a
;;;; fixed ellipse, and the ellipses' orientations twist with radius, so the
;;;; crowded parts of neighbouring orbits line up into spiral arms. Nothing is
;;;; random: star i is scattered with the golden angle, so the disc looks
;;;; even without an entropy source (a --no-wasi reactor has none).

;; The host functions provided by the page. :as maps the Lisp name to the
;; JavaScript property; :from names the import-object key.
(rontolisp:wasm-import 'draw-particle :from "gl" :as "drawParticle"
                       :params '(:float :float :float :float) :returns :void)
(rontolisp:wasm-import 'aspect-ratio :from "gl" :as "aspectRatio"
                       :params '() :returns :float)

;; The WASM backend has no transcendental built-ins, so borrow the host's:
;; these two lines are literally Math.sin / Math.cos on the JavaScript side.
(rontolisp:wasm-import 'sin :from "math" :params '(:float) :returns :float)
(rontolisp:wasm-import 'cos :from "math" :params '(:float) :returns :float)

;; Per-star orbit parameters, filled by `init`.
(defvar *n* 0)
(defvar *radius* nil)                   ; semi-major axis of the orbit
(defvar *phase* nil)                    ; where on the orbit the star starts
(defvar *speed* nil)                    ; angular speed (inner orbits run faster)
(defvar *tilt* nil)                     ; orientation of the ellipse

;; The golden angle scatters the stars' orbit phases evenly; sqrt(2)-1
;; scatters their radii. (The radius sequence must not be built on the golden
;; ratio: the golden angle is 2*pi*(1-phi), so frac(i*phi) would be an exact
;; function of the phase and every star would land on one curve.) The two
;; low-discrepancy sequences are independent, so the disc fills smoothly
;; without an entropy source.
(defconstant +golden-angle+ 2.399963229728653)

(defconstant +radius-step+ 0.414213562373095)

;; How strongly the ellipse orientation twists with radius; this is what
;; winds the orbits into spiral arms.
(defconstant +twist+ 5.4)

;; The fractional part of X (X non-negative).
(defun frac (x)
  (- x (floor x)))

(defun init (n)
  (setq *n* n)
  (setq *radius* (make-array n))
  (setq *phase* (make-array n))
  (setq *speed* (make-array n))
  (setq *tilt* (make-array n))
  (dotimes (i n)
    (let* ((u (frac (* (+ i 1) +radius-step+)))
           ;; sqrt biases the stars toward the bright core
           (r (+ 0.03 (* 0.95 (sqrt u))))
           ;; a third sequence breaks up the residual lattice at the rim
           (jitter (* 0.5 (frac (* (+ i 1) 0.754877666)))))
      (setf (aref *radius* i) r)
      (setf (aref *phase* i) (+ (* i +golden-angle+) jitter))
      (setf (aref *speed* i) (/ 0.5 (+ 0.15 r)))
      (setf (aref *tilt* i) (* r +twist+)))))

(defun frame (tm)
  (let ((aspect (aspect-ratio)))
    (dotimes (i *n*)
      (let* ((r (aref *radius* i))
             (theta (+ (aref *phase* i) (* tm (aref *speed* i))))
             ;; the star's position on its (axis-aligned) ellipse
             (ex (* r (cos theta)))
             (ey (* r 0.55 (sin theta)))
             ;; rotate the ellipse: per-radius twist plus a slow global spin
             (rot (+ (aref *tilt* i) (* tm 0.04)))
             (c (cos rot))
             (s (sin rot))
             (x (- (* ex c) (* ey s)))
             (y (+ (* ex s) (* ey c)))
             ;; the page maps 0 -> warm core white, 1 -> violet rim
             (hue (+ r (* 0.1 (frac (* i 0.754877666)))))
             (size (+ 1.8 (* 3.4 (- 1.0 r) (- 1.0 r)))))
        (draw-particle (/ x aspect) y hue size)))))

(rontolisp:wasm-export 'init :params '(:int) :returns :void)
(rontolisp:wasm-export 'frame :params '(:float) :returns :void)
