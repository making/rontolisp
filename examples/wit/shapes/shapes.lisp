;;;; shapes -- a world whose exports carry records, variants and lists.
;;;;
;;;; It implements the world `shapes` of wit/shapes.wit: the interface `geometry`
;;;; and the world's own function `summarize`. The types are the WIT's, and each
;;;; arrives as the Lisp value the type mapping settles:
;;;;
;;;;   record           a keyword plist                (:x 1 :y 2)
;;;;   enum             a keyword                      :green
;;;;   variant          a keyword, or (keyword . payload)   :dot  (:circle . 2.0d0)
;;;;   list<T>          a list
;;;;   tuple            a list
;;;;   option<T>        the value, or nil
;;;;   result<T, E>     the value; the error arm is a rontolisp:wit-error signalled
;;;;
;;;; Built as a component whose exports are its entry points:
;;;;
;;;;   rontolisp shapes.lisp -o shapes.wasm --component
;;;;   wasmtime run --invoke 'flip({x: 1, y: 2})' shapes.wasm
;;;;   wasmtime run --invoke 'summarize([1.5, 2.5])' shapes.wasm
;;;;
;;;; app.lisp calls the interface from another component -- see README.md.

;;; WIT: flip: func(p: point) -> point
(defun flip (p) (list :x (getf p :y) :y (getf p :x)))

;;; WIT: squares: func(n: u32) -> list<point>
(defun squares (n) (loop for i from 1 to n collect (list :x i :y (* i i))))

;;; WIT: bounds: func(ps: list<point>) -> option<tuple<point, point>>
(defun bounds (ps)
  (when ps
    (let ((xs (mapcar (lambda (p) (getf p :x)) ps))
          (ys (mapcar (lambda (p) (getf p :y)) ps)))
      (list (list :x (reduce #'min xs) :y (reduce #'min ys))
            (list :x (reduce #'max xs) :y (reduce #'max ys))))))

;;; WIT: area: func(s: shape) -> result<f64, string>
;;; The ok arm is the value returned; the error arm is the condition signalled.
(defun area (s)
  (cond ((eq s :dot) (error 'rontolisp:wit-error :payload "a dot has no area"))
        ((eq (car s) :circle) (* 3.0d0 (cdr s) (cdr s)))
        (t (float (abs (* (getf (cdr s) :x) (getf (cdr s) :y))) 1.0d0))))

;;; WIT: caption: func(t: tagged) -> string
(defun caption (tagged)
  (let ((shape (getf tagged :shape)))
    (format nil "a ~a ~a~@[ called ~a~]"
            (string-downcase (symbol-name (getf tagged :color)))
            (string-downcase (symbol-name (if (consp shape) (car shape) shape)))
            (getf tagged :label))))

;;; WIT: summarize: func(areas: list<f64>) -> summary
(defun summarize (areas)
  (list :count (length areas) :total (reduce #'+ areas :initial-value 0.0d0)))

(rontolisp:wit-export "wit/shapes.wit" :reactor t)
