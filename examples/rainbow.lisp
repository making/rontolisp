;;;; Color a string one character at a time, building a rainbow across it.
;;;;
;;;; The work is split into small, composable functions:
;;;;   rgb-hsv / hsv-rgb     -- color-space conversions (lists of numbers)
;;;;   interpolate-hue       -- shortest-arc interpolation of a hue (degrees)
;;;;   interpolate-hsv       -- interpolate a whole (h s v) color
;;;;   color-at              -- the palette color at a position in [0,1]
;;;;   rainbow-text          -- string -> alist of (character . "#rrggbb")
;;;;   decorate-html         -- that alist -> a string of colored <span>s
;;;;   rainbow-html          -- string -> decorated HTML (the two combined)
;;;;
;;;; Per character we walk the input, map its index to a position p in [0,1],
;;;; sample the anchor palette at p by interpolating in HSV (hue on the shorter
;;;; arc), and emit one <span style='color:#rrggbb'>c</span>. When the string is
;;;; longer or shorter than the palette the colors are interpolated, so any
;;;; length comes out as a smooth gradient.
;;;;
;;;; This uses cons/list/char/string operations, so it needs the GC backends
;;;; (interpreter / JVM / WASM-GC); it is NOT in the --no-gc subset (which has no
;;;; per-character string access). It still runs in Node as a WASM-GC reactor
;;;; built with --no-wasi (see the build/run notes at the bottom).

;;; --- small numeric helpers ---------------------------------------------------

;;; Absolute value of a float (kept local so the example is self-contained).
(defun fabs (x) (if (< x 0.0) (- x) x))

;;; Floating-point modulo a mod m, result in [0,m) for positive m (CL `mod`
;;; semantics: sign of the divisor). Defined here because the GC WASM backend's
;;; `mod` is integer-only -- using it on floats miscompiles -- whereas this
;;; floor-based form lowers to plain float arithmetic on every backend.
(defun fmod (a m) (- a (* m (float (floor (/ a m))))))

;;; Linear interpolation a -> b by fraction f in [0,1].
(defun lerp (a b f) (+ a (* f (- b a))))

;;; A 0..1 float color component to an integer 0..255, clamped.
(defun clamp255 (x)
  (let ((n (round (* x 255.0))))
    (max 0 (min 255 n))))

;;; An integer 0..255 to a two-digit lowercase hex string.
(defun hex2 (n)
  (let ((digits "0123456789abcdef")
        (hi (floor (/ n 16)))
        (lo (mod n 16)))
    (concatenate 'string
      (subseq digits hi (1+ hi))
      (subseq digits lo (1+ lo)))))

;;; An (r g b) list (0..255 each) to a "#rrggbb" CSS color string.
(defun rgb-hex (rgb)
  (concatenate 'string "#"
    (hex2 (first rgb)) (hex2 (second rgb)) (hex2 (third rgb))))

;;; --- color-space conversion --------------------------------------------------

;;; (r g b) with each component 0..255  ->  (h s v) with h in [0,360), s,v in [0,1].
(defun rgb-hsv (c)
  (let* ((r (/ (float (first c)) 255.0))
         (g (/ (float (second c)) 255.0))
         (b (/ (float (third c)) 255.0))
         (mx (max r g b))
         (mn (min r g b))
         (d (- mx mn))
         (v mx)
         (s (if (= mx 0.0) 0.0 (/ d mx)))
         (h (cond ((= d 0.0) 0.0)
                  ((= mx r) (* 60.0 (fmod (/ (- g b) d) 6.0)))
                  ((= mx g) (* 60.0 (+ (/ (- b r) d) 2.0)))
                  (t        (* 60.0 (+ (/ (- r g) d) 4.0))))))
    (list h s v)))

;;; (h s v)  ->  (r g b) with each component an integer 0..255.
(defun hsv-rgb (h s v)
  (let* ((c (* v s))
         (x (* c (- 1.0 (fabs (- (fmod (/ h 60.0) 2.0) 1.0)))))
         (m (- v c))
         ;; (r g b) for this 60-degree sector, before adding the m offset.
         (rgb (cond ((< h 60.0)  (list c x 0.0))
                    ((< h 120.0) (list x c 0.0))
                    ((< h 180.0) (list 0.0 c x))
                    ((< h 240.0) (list 0.0 x c))
                    ((< h 300.0) (list x 0.0 c))
                    (t           (list c 0.0 x)))))
    (list (clamp255 (+ (first rgb) m))
          (clamp255 (+ (second rgb) m))
          (clamp255 (+ (third rgb) m)))))

;;; --- interpolation -----------------------------------------------------------

;;; Interpolate a hue from h0 to h1 by fraction f, going the short way around the
;;; 360-degree color wheel (so red 350 -> 10 passes through 0, not through cyan).
(defun interpolate-hue (h0 h1 f)
  (let* ((raw (- h1 h0))
         ;; the signed delta taken the short way around the 360-degree wheel
         (d (cond ((> raw 180.0) (- raw 360.0))
                  ((< raw -180.0) (+ raw 360.0))
                  (t raw))))
    (fmod (+ h0 (* f d)) 360.0)))

;;; Interpolate a whole color: hue on the short arc, saturation/value linearly.
;;; a and b are (h s v) lists.
(defun interpolate-hsv (a b f)
  (list (interpolate-hue (first a) (first b) f)
        (lerp (second a) (second b) f)
        (lerp (third a) (third b) f)))

;;; --- palette -----------------------------------------------------------------

;;; The anchor colors (RGB 0..255), in gradient order.
(defun rainbow-anchors ()
  (list (list 153 50 204) (list 71 111 240) (list 69 139 116)
        (list 50 205 50) (list 255 215 0) (list 255 127 0)
        (list 238 99 99) (list 238 64 0) (list 205 38 38)
        (list 139 26 26)))

;;; The interpolated "#rrggbb" color at position p in [0,1] across the palette.
(defun color-at (p)
  (let* ((anchors (rainbow-anchors))
         (n (length anchors))
         (pos (* p (float (1- n))))
         ;; segment index, clamped so k and k+1 are valid anchor indices
         (k (max 0 (min (- n 2) (floor pos))))
         (frac (- pos (float k)))
         (a (rgb-hsv (nth k anchors)))
         (b (rgb-hsv (nth (1+ k) anchors)))
         (hsv (interpolate-hsv a b frac)))
    (rgb-hex (hsv-rgb (first hsv) (second hsv) (third hsv)))))

;;; --- the public functions ----------------------------------------------------

;;; The index list (0 1 ... n-1), so the string can be transformed with mapcar
;;; (mapcar needs a list to walk, and a string is not one; the index also drives
;;; the color position). Built by recursion -- no explicit loop.
(defun upto (i n) (if (>= i n) nil (cons i (upto (1+ i) n))))
(defun iota (n) (upto 0 n))

;;; A string to an alist mapping each character to its "#rrggbb" rainbow color.
;;; mapcar over the character indices: index i -> (char . color-at p).
(defun rainbow-text (s)
  (let ((len (length s)))
    (mapcar (lambda (i)
              (cons (char s i)
                    (color-at (if (<= len 1) 0.0 (/ (float i) (float (1- len)))))))
            (iota len))))

;;; HTML-escape one character to safe markup; ordinary characters pass through.
;;; Matched by code point so the reader never sees the tricky #\" / #\' literals.
(defun escape-char (ch)
  (let ((c (char-code ch)))
    (cond ((= c 38) "&amp;")    ; &
          ((= c 60) "&lt;")     ; <
          ((= c 62) "&gt;")     ; >
          ((= c 34) "&quot;")   ; "
          ((= c 39) "&#39;")    ; '
          (t (princ-to-string ch)))))

;;; Escape every HTML-special character in a string (& < > " '), so arbitrary
;;; input is safe to drop into markup. Builds the result by escaping each
;;; character and joining with reduce.
(defun html-escape (s)
  (reduce (lambda (acc i) (concatenate 'string acc (escape-char (char s i))))
          (iota (length s)) :initial-value ""))

;;; One (character . color) pair to its colored <span> string, with the
;;; character HTML-escaped.
(defun span-html (pair)
  (concatenate 'string
    "<span style='color:" (cdr pair) "'>"
    (html-escape (princ-to-string (car pair))) "</span>"))

;;; An alist of (character . color) to a string of colored <span> elements:
;;; mapcar each pair to its span, then reduce the spans into one string. (The
;;; join uses reduce rather than `apply #'concatenate` because concatenate takes
;;; a result-type argument and has no first-class function value in the
;;; compilers; here concatenate stays in call position and is inlined.)
(defun decorate-html (pairs)
  (reduce (lambda (acc span) (concatenate 'string acc span))
          (mapcar #'span-html pairs)
          :initial-value ""))

;;; A string straight to its rainbow HTML (rainbow-text then decorate-html).
(defun rainbow-html (s)
  (decorate-html (rainbow-text s)))

;;; Export rainbow-html as a host-callable WASM function (string in, string out).
;;; A no-op on the interpreter/JVM; under --no-wasi it makes a Node-loadable
;;; WASM-GC reactor.
(rontolisp:wasm-export 'rainbow-html
  :params '(:string)
  :returns :string)
