(in-package :cl-colors2)

(defparameter *x11-colors-list* '())

(defparameter *svg-colors-list* '())

(defparameter *svg-extended-colors-list* '())

(defparameter *gdk-colors-list* '())

(defparameter *colors-list* nil)

;;; color representations

(deftype unit-real ()
  "Real number in [0,1]."
  '(real 0 1))

(defstruct (rgb (:constructor rgb (red green blue)))
  "RGB color."
  (red   nil :type unit-real :read-only t)
  (green nil :type unit-real :read-only t)
  (blue  nil :type unit-real :read-only t))

(defmethod make-load-form ((p rgb) &optional env)
  (declare (ignore env))
  (make-load-form-saving-slots p))

(defun gray (value)
  "Create an RGB representation of a gray color (value in [0,1)."
  (rgb value value value))

(defstruct (hsv (:constructor hsv (hue saturation value)))
  "HSV color."
  (hue        nil :type (real 0 360) :read-only t)
  (saturation nil :type unit-real    :read-only t)
  (value      nil :type unit-real    :read-only t))

(defmethod make-load-form ((p hsv) &optional env)
  (declare (ignore env))
  (make-load-form-saving-slots p))

(defun normalize-hue (hue)
  "Normalize hue to the interval [0,360)."
  (mod hue 360))

(defstruct (hsl (:constructor hsl (hue saturation lightness)))
  "HSL color."
  (hue        nil :type (real 0 360) :read-only t)
  (saturation nil :type unit-real    :read-only t)
  (lightness  nil :type unit-real    :read-only t))

(defmethod make-load-form ((p hsl) &optional env)
  (declare (ignore env))
  (make-load-form-saving-slots p))

;;; conversions

(defun rgb-to-hsv (rgb &optional (undefined-hue 0))
  "Convert RGB to HSV representation.  When hue is undefined (saturation is
zero), UNDEFINED-HUE will be assigned."
  (flet ((normalize (constant right left delta)
           (let ((hue (+ constant (/ (* 60 (- right left)) delta))))
             (if (minusp hue)
                 (+ hue 360)
                 hue))))
  (let* ((red   (rgb-red   rgb))
         (green (rgb-green rgb))
         (blue  (rgb-blue  rgb))
         (value (max red green blue))
         (delta (- value (min red green blue)))
         (saturation (if (plusp value)
                         (/ delta value)
                         0))
         (hue (cond
                ((zerop saturation) undefined-hue) ; undefined
                ((= red value) (normalize 0 green blue delta)) ; dominant red
                ((= green value) (normalize 120 blue red delta)) ; dominant green
                (t (normalize 240 red green delta)))))
    (hsv hue saturation value))))

(defun hsv-to-rgb (hsv)
  "Convert HSV to RGB representation.  When SATURATION is zero, HUE is
ignored."
  (let* ((hue        (hsv-hue        hsv))
         (saturation (hsv-saturation hsv))
         (value      (hsv-value      hsv)))
    ;; if saturation=0, color is on the gray line
    (when (zerop saturation)
      (return-from hsv-to-rgb (gray value)))
    ;; nonzero saturation: normalize hue to [0,6)
    (let* ((h (/ (normalize-hue hue) 60)))
      (multiple-value-bind (quotient remainder)
          (floor h)
        (let* ((p (* value (- 1 saturation)))
               (q (* value (- 1 (* saturation remainder))))
               (r (* value (- 1 (* saturation (- 1 remainder))))))
          (multiple-value-bind (red green blue)
              (case quotient
                (0 (values value r p))
                (1 (values q value p))
                (2 (values p value r))
                (3 (values p q value))
                (4 (values r p value))
                (t (values value p q)))
            (rgb red green blue)))))))

(defun hsv-to-hsl (hsv)
  "Convert HSV to HSL representation."
  (let* ((hue            (hsv-hue        hsv))
         (saturation-hsv (hsv-saturation hsv))
         (value          (hsv-value      hsv))
         (lightness      (* value
                            (- 1
                               (/ saturation-hsv 2))))
         (saturation-hsl (if (or (eps= lightness 0)
                                 (eps= lightness 1))
                             0
                             (/ (- value lightness)
                                (min lightness
                                     (- 1 lightness))))))
    (hsl hue saturation-hsl lightness)))

(defun hsl-to-hsv (hsl)
  "Convert HSV to HSL representation."
  (let* ((hue            (hsl-hue        hsl))
         (lightness      (hsl-lightness  hsl))
         (saturation-hsl (hsl-saturation hsl))
         (value          (+ lightness
                            (* saturation-hsl
                               (min lightness (- 1 lightness)))))
         (saturation-hsv (if (eps= value 0)
                             0
                             (* 2
                                (- 1 (/ lightness
                                        value))))))
    (hsv hue saturation-hsv value)))

(defun hex-to-rgb (string)
  "Parse hexadecimal notation (e.g. ff0000 or f00 for red) into an RGB color."
  (multiple-value-bind (width max)
      (case (length string)
        ((3 4 5) (values 1 15))
        ((6 7) (values 2 255))
        ;; Alpha-aware hex: ignore alpha.
        ((8 9) (values 2 255))
        (t (error "string ~A doesn't have length 3 or 6, can't parse as ~
                       RGB specification" string)))
    (let ((string (if (eql #\# (elt string 0))
                      (subseq string 1)
                      string)))
      (flet ((parse (index)
               (/ (parse-integer string
                                 :start (* index width)
                                 :end   (* (1+ index) width)
                                 :radix 16)
                  max)))
        (rgb (parse 0) (parse 1) (parse 2))))))

(defun rgb/a-to-rgb (string)
  (warn "rgb/a-to-rgb is deprecated, please use parse-rgb/a-to-rgb, instead.")
  (parse-rgb/a-to-rgb string))

(defparameter *rgb-css-scanner*
  (cl-ppcre:create-scanner "[^(]+\\(\\s*(([0-9]{0,3})(\\.[0-9]+)?)(%?)\\s*,?\\s*(([0-9]{0,3})(\\.[0-9]+)?)(%?)\\s*,?\\s*(([0-9]{0,3})(\\.[0-9]+)?)(%?)(\\s*(/|,)\\s*((([0-9]{1})(\\.[0-9]+))|(none)))?"))

(defun parse-rgb/a-to-rgb (string)
  (multiple-value-bind (matched weights-as-string)
      (cl-ppcre:scan-to-strings *rgb-css-scanner* string)
    (when matched
      (let* ((percent-weights-p    (string/= (elt weights-as-string 3)
                                             ""))
             (normalization-factor (if percent-weights-p
                                       100
                                       255))
             (weights (map 'list
                           (lambda (a) (ignore-errors (parse-number:parse-number a)))
                           weights-as-string))
             (r       (/ (elt weights 0) normalization-factor))
             (g       (/ (elt weights 4) normalization-factor))
             (b       (/ (elt weights 8) normalization-factor))
             (alpha   (ignore-errors (alexandria::clamp (elt weights 14) 0 1))))
        (values (rgb r g b)
                (or alpha 1)
                (if alpha t nil))))))

(defun parse-hs*-to-rgb (string color-struct-constructor)
  (multiple-value-bind (matched weights-as-string)
      (cl-ppcre:scan-to-strings "[^(]+\\(\\s*(([0-9]{0,3})(\\.[0-9]+)?)\\s*,?\\s*(([0-9]{0,3})(\\.[0-9]+)?)%?\\s*,?\\s*(([0-9]{0,3})(\\.[0-9]+)?)%?"
                                string)
    (when matched
      (let ((weights (map 'list
                          (lambda (a) (ignore-errors (parse-number:parse-number a)))
                          weights-as-string)))
        (as-rgb (funcall color-struct-constructor
                         (alexandria:clamp 0 (elt weights 0) 360)
                         (/ (elt weights 3) 100)
                         (/ (elt weights 6) 100)))))))

(defun parse-hsv-to-rgb (string)
  (parse-hs*-to-rgb string #'hsv))

(defun parse-hsl-to-rgb (string)
  (parse-hs*-to-rgb string #'hsl))

;;; conversion with generic functions

(defun %lookup-colors-list (string list)
  (let ((hiphenated-name (cl-ppcre:regex-replace-all "\\p{White_Space}+" string "-")))
    (symbol-value (cdr (assoc hiphenated-name list :test #'equalp)))))

(defun lookup-colors-list (string colors-list)
  (if (null colors-list)
      (or (%lookup-colors-list string *x11-colors-list*)
          (%lookup-colors-list string *svg-colors-list*)
          (%lookup-colors-list string *svg-extended-colors-list*)
          (%lookup-colors-list string *gdk-colors-list*))
      (%lookup-colors-list string colors-list)))

(define-compiler-macro as-rgb (&whole form color &key colors-list errorp)
  (declare (notinline as-rgb))
  (if (and (constantp color)
           (not colors-list ))
      (let ((results (as-rgb color :errorp errorp)))
        (or results form))
      form))

(defun parse-as-html-legacy-color-spec (color-string)
  "Parse the color string as a legacy HTML color specification see:
https://html.spec.whatwg.org/multipage/common-microsyntaxes.html#colours.

Note that the fourth rule (\"If input is an ASCII case-insensitive match for one of the named colors, then return the CSS color corresponding to that keyword.\") is ignored, use `as-rgb-instead'"
  (declare (optimize (debug 3) (speed 0)))
  (labels ((hex-digit-p (char)
             (find-if (lambda (a) (char-equal char a))
                      "0123456789abcdef"))
           (hexchar->integer (char)
             (parse-integer (string char) :radix 16))
           (pad-with-0 (color-chars-list)
             "Note: assumes the lenght of the list is max 128 elements"
             (if (or (= (length color-chars-list) 0)
                     (/= (rem (length color-chars-list) 3)
                         0))
                 (pad-with-0 (append color-chars-list '(#\0)))
                 color-chars-list))
           (remove-leading-zeros (chunk)
             (if (and (> (length chunk) 2)
                      (char= (first chunk) #\0))
                 (remove-leading-zeros (subseq chunk 1))
                 chunk))
           (truncate-chunk (chunk)
             (let* ((length-8  (if (> (length chunk) 8)
                                   (subseq chunk (- (length chunk) 8))
                                   chunk))
                    (removed-0 (remove-leading-zeros length-8)))
               (subseq removed-0 0 (min 2 (length removed-0))))))
    (if (string= color-string "")
        (error "Color is the empty string")
        (let ((clean-color (string-trim '(#\Space #\tab #\Newline #\Return)
                                        color-string)))
          (cond
            ((string-equal clean-color "transparent")
             (error "Unable to parse the string ~s" clean-color))
            ((and (= (length color-string) 4)
                  (char= (elt color-string 0) #\#)
                  (hex-digit-p (elt color-string 1))
                  (hex-digit-p (elt color-string 2))
                  (hex-digit-p (elt color-string 3)))
             (rgb (* 17 (hexchar->integer (elt clean-color 1)))
                  (* 17 (hexchar->integer (elt clean-color 2)))
                  (* 17 (hexchar->integer (elt clean-color 3)))))
            (t
             (let* ((replaced-with-00      (loop for char across color-string
                                                 nconc
                                                 (if (> (char-code char) #xffff)
                                                     '(#\0 #\0)
                                                     `(,char))))
                    (truncated             (subseq replaced-with-00
                                                   0
                                                   (min 128
                                                        (length replaced-with-00))))
                    (no-hash               (if (char= (first truncated) #\#)
                                               (subseq truncated 1)
                                               truncated))
                    (clean-no-hex-digit    (loop for char in no-hash
                                                 collect
                                                 (if (hex-digit-p char)
                                                     char
                                                     #\0)))
                    (padded-with-0         (pad-with-0 clean-no-hex-digit))
                    (chunk-size            (/ (length padded-with-0) 3))
                    (chunk-r               (subseq padded-with-0 0 chunk-size))
                    (chunk-g               (subseq padded-with-0 chunk-size (* 2 chunk-size)))
                    (chunk-b               (subseq padded-with-0 (* 2 chunk-size)))
                    (truncated-chunk-r     (truncate-chunk chunk-r))
                    (truncated-chunk-g     (truncate-chunk chunk-g))
                    (truncated-chunk-b     (truncate-chunk chunk-b))
                    (raw-color             (concatenate 'string
                                                        "#"
                                                        (coerce truncated-chunk-r 'string)
                                                        (coerce truncated-chunk-g 'string)
                                                        (coerce truncated-chunk-b 'string))))
               (values (as-rgb raw-color)
                       raw-color))))))))

(defgeneric as-rgb (color &key colors-list errorp)
  (:documentation
   "Coerce an RGB, HSV, integer, keyword, symbol or a string into a RGB structure.

Valid string formats are:

- [#]ff00ff or [#]fff (24 bit or 12bit depth colors in hexadecimal digit format);
- rgb[a](255,255,0,[alpha-channel-value]);
- named colors.

Note: text in square brackets means that the string is optional.

`color-list' argument specify an alist where  of cons cells the car is
a string representing a color name and the cdr the corresponding color
RGB struct.

The default  for the  argument is  the special  variable *color-table*
with, in turn, is bound to nil. When `color-list' is null, the lookup
is       made      on       *x11-colors-list*,      *svg-colors-list*,
*svg-extended-colors-list*     and    *gdk-colors-list*     in    this
order.  Otherwise the  alist passed  as  parameter is  searched for  a
matching color name.

Finally if `errorp' is non-nil (the default is T) failure to parse a
string  representation  of an  rgb  color  will  signal an  error;  if
`errorp' is null a parsing failure makes the function returns nil. If
`object' is not a string the parameter `errorp' is ignored.

Even if:

 - `errorp' in not null and
 - the parsing fails and
 - `color' is not the empty string or its value is not  \"transparent\"

a restart is available to convert to an rgb struct that, someway, represents the value of `color', see: https://html.spec.whatwg.org/multipage/common-microsyntaxes.html#colours")
  (:method ((object rgb) &key (colors-list nil) (errorp t))
    (declare (ignore colors-list errorp))
    object)
  (:method ((object hsv) &key (colors-list nil) (errorp t))
    (declare (ignore colors-list errorp))
    (hsv-to-rgb object))
  (:method ((color hsl) &key (colors-list nil) (errorp t))
    (declare (ignore colors-list errorp))
    (as-rgb (hsl-to-hsv color)))
  (:method ((object symbol) &key (colors-list nil) (errorp t))
    (if (keywordp object)
        (as-rgb (format nil "~a" object) :errorp errorp :colors-list colors-list)
        (as-rgb (symbol-value object) :errorp errorp :colors-list colors-list)))
  (:method ((object string) &key (colors-list *colors-list*) (errorp t))
    (let ((named-color nil))
      (flet ((lookup-color-name ()
               (setf named-color (lookup-colors-list object colors-list))
               named-color)
             (every-digits (string &optional (offset 0))
               (every (rcurry #'digit-char-p 16) (subseq string offset))))
        (handler-case
            (cond
              ((or (and (uiop:string-prefix-p "#" object)
                        (every-digits object 1))
                   (and (member (length object) '(3 4 6 8))
                        (every-digits object)))
               (hex-to-rgb object))
              ((ppcre:scan "^rgba?\\s*\\(" object)
               (parse-rgb/a-to-rgb object))
              ((ppcre:scan "^hsv\\s*\\(" object)
               (parse-hsv-to-rgb object))
              ((ppcre:scan "^hsl\\s*\\(" object)
               (parse-hsl-to-rgb object))
              ((lookup-color-name)
               named-color)
              (errorp
               (if (string= object "")
                   (error "Color is the empty string")
                   (restart-case
                       (error (format nil "Color ~s can not be parsed" object))
                     (return-parsing-as-legacy-html-color ()
                       :report "Parse the string as a legacy HTML color specification: see https://html.spec.whatwg.org/multipage/common-microsyntaxes.html#rules-for-parsing-a-legacy-colour-value"
                       (parse-as-html-legacy-color-spec object))))))))))
  (:method ((object integer) &key (colors-list nil) (errorp t))
    (declare (ignore colors-list errorp))
    (rgb (/ (logand (ash object -16) #xff) 255.0)
         (/ (logand (ash object  -8) #xff) 255.0)
         (/ (logand      object      #xff) 255.0))))

(define-compiler-macro as-hsv (&whole form color &optional undefined-hue)
  (declare (notinline as-hsv))
  (if (and (constantp color)
           (constantp undefined-hue)
           (not (stringp color))
           (not (symbolp color)))
      (as-hsv color undefined-hue)
      form))

(defgeneric as-hsv (color &optional undefined-hue)
  (:documentation "Coerce an RGB, an HSV, or a HEX string into a HSV structure. HEX string is parsed as an RGB specification. This function is deprecated, please use AS-HSV*, eventually AS-HSV will became an alias for AS-HSV*")
  (:method ((color rgb) &optional (undefined-hue 0))
    (rgb-to-hsv color undefined-hue))
  (:method ((color hsv) &optional undefined-hue)
    (declare (ignore undefined-hue))
    color)
  (:method ((color hsl) &optional undefined-hue)
    (declare (ignore undefined-hue))
    (hsl-to-hsv color))
  (:method ((object string) &optional (undefined-hue 0))
    (let ((rgb (as-rgb object)))
      (rgb-to-hsv rgb undefined-hue)))
  (:method ((object symbol) &optional (undefined-hue 0))
    (if (keywordp object)
        (let ((name (format nil "~a" object)))
          (rgb-to-hsv name undefined-hue))
        (let ((rgb (symbol-value object)))
          (rgb-to-hsv rgb undefined-hue))))
  (:method ((object integer) &optional (undefined-hue 0))
    (let ((rgb (as-rgb object)))
      (rgb-to-hsv rgb undefined-hue))))

(define-compiler-macro as-hsv* (&whole form color &key undefined-hue errorp colors-list)
  (declare (notinline as-hsv*))
  (if (and (constantp color)
           (constantp undefined-hue)
           (null colors-list))
      (as-hsv* color :undefined-hue undefined-hue :errorp errorp)
      form))

(defgeneric as-hsv* (color &key undefined-hue errorp colors-list)
  (:documentation "Coerce an RGB, an HSV, or a HEX string into a HSV structure. HEX string is parsed as an RGB specification.")
  (:method ((color rgb) &key (undefined-hue 0) (errorp t) (colors-list nil))
    (declare (ignore errorp colors-list))
    (rgb-to-hsv color undefined-hue))
  (:method ((color hsv) &key (undefined-hue 0) (errorp t) (colors-list nil))
    (declare (ignore undefined-hue errorp colors-list))
    color)
  (:method ((object string) &key (undefined-hue 0) (errorp t) (colors-list nil))
    (let ((rgb (as-rgb object :errorp errorp :colors-list colors-list)))
      (rgb-to-hsv rgb undefined-hue)))
  (:method ((object symbol) &key (undefined-hue 0) (errorp t) (colors-list nil))
    (let ((rgb (as-rgb (symbol-value object) :errorp errorp :colors-list colors-list)))
      (rgb-to-hsv rgb undefined-hue)))
  (:method ((object integer) &key (undefined-hue 0) (errorp t) (colors-list nil))
    (let ((rgb (as-rgb object :errorp errorp :colors-list colors-list)))
      (rgb-to-hsv rgb undefined-hue))))

(defgeneric as-hsl (color &key undefined-hue errorp colors-list)
  (:documentation "Coerce an RGB, an HSV, or a HEX string into a HSL structure. HEX string is parsed as an RGB specification.")
  (:method ((color rgb) &key (undefined-hue 0) (errorp t) (colors-list nil))
    (declare (ignore errorp colors-list))
    (hsv-to-hsl (rgb-to-hsv color undefined-hue)))
  (:method ((color hsv) &key (undefined-hue 0) (errorp t) (colors-list nil))
    (declare (ignore undefined-hue errorp colors-list))
    (hsv-to-hsl color))
  (:method ((color hsl) &key (undefined-hue 0) (errorp t) (colors-list nil))
    (declare (ignore undefined-hue errorp colors-list))
    color)
  (:method ((object string) &key (undefined-hue 0) (errorp t) (colors-list nil))
    (let ((rgb (as-rgb object :errorp errorp :colors-list colors-list)))
      (hsv-to-hsl (rgb-to-hsv rgb undefined-hue))))
  (:method ((object symbol) &key (undefined-hue 0) (errorp t) (colors-list nil))
    (let ((rgb (as-rgb (symbol-value object) :errorp errorp :colors-list colors-list)))
      (hsv-to-hsl (rgb-to-hsv rgb undefined-hue))))
  (:method ((object integer) &key (undefined-hue 0) (errorp t) (colors-list nil))
    (let ((rgb (as-rgb object :errorp errorp :colors-list colors-list)))
      (hsv-to-hsl (rgb-to-hsv rgb undefined-hue)))))

;;; combinations

(declaim (inline cc))
(defun cc (a b alpha)
  "Convex combination  (1-ALPHA)*A+ALPHA*B, i.e.  ALPHA is  the weight
of A."
  (declare (type (real 0 1) alpha))
  (+ (* (- 1 alpha) a) (* alpha b)))

(defun rgb-combination (color1 color2 alpha)
  "Color combination in RGB space."
  (flet ((c (c1 c2) (cc c1 c2 alpha)))
    (let ((rgb-1 (as-rgb color1))
          (rgb-2 (as-rgb color2)))
      (rgb (c (rgb-red   rgb-1) (rgb-red   rgb-2))
           (c (rgb-green rgb-1) (rgb-green rgb-2))
           (c (rgb-blue  rgb-1) (rgb-blue  rgb-2))))))

(defun hsv-combination (hsv1 hsv2 alpha &optional (positive? t))
  "Color combination in HSV space.  POSITIVE? determines whether the hue
combination is in the positive or negative direction on the color wheel."
  (flet ((c (c1 c2) (cc c1 c2 alpha)))
    (let* ((hsv-1        (as-hsv hsv1))
           (hsv-2        (as-hsv hsv2))
           (hue-1        (hsv-hue hsv-1))
           (saturation-1 (hsv-saturation hsv-1))
           (value-1      (hsv-value      hsv-1))
           (hue-2        (hsv-hue hsv-2))
           (saturation-2 (hsv-saturation hsv-2))
           (value-2      (hsv-value      hsv-2)))
      (hsv (cond
             ((and positive? (> hue-1 hue-2))
              (normalize-hue (c hue-1 (+ hue-2 360))))
             ((and (not positive?) (< hue-1 hue-2))
              (normalize-hue (c (+ hue-1 360) hue-2)))
             (t (c hue-1 hue-2)))
           (c saturation-1 saturation-2)
           (c value-1 value-2)))))

;; equality

(defun eps= (a b &optional (epsilon 1e-10))
  (<= (abs (- a b)) epsilon))

(defparameter *epsilon* 1e-7)

(defgeneric color-equals  (a b &key tolerance)
  (:documentation "Compare two colors under a given floating point tolerance."))

(defmethod color-equals ((a rgb) (b rgb) &key (tolerance *epsilon*))
  (and (eps= (rgb-red a)
             (rgb-red b)
             tolerance)
       (eps= (rgb-green a)
             (rgb-green b)
             tolerance)
       (eps= (rgb-blue a)
             (rgb-blue b)
             tolerance)))

(defmethod color-equals ((a hsv) (b hsv) &key (tolerance *epsilon*))
  (and (eps= (hsv-hue        a)
             (hsv-hue        b)
             tolerance)
       (eps= (hsv-saturation a)
             (hsv-saturation b)
             tolerance)
       (eps= (hsv-value      a)
             (hsv-value      b)
             tolerance)))

(defmethod color-equals ((a hsl) (b hsl) &key (tolerance *epsilon*))
  (and (eps= (hsl-hue        a)
             (hsl-hue        b)
             tolerance)
       (eps= (hsl-saturation a)
             (hsl-saturation b)
             tolerance)
       (eps= (hsl-lightness  a)
             (hsl-lightness  b)
             tolerance)))

(defmethod color-equals ((a hsv) (b rgb) &key (tolerance *epsilon*))
  (color-equals a (as-hsv b) :tolerance tolerance))

(defmethod color-equals ((a hsv) (b hsl) &key (tolerance *epsilon*))
  (color-equals a (as-hsv b) :tolerance tolerance))

(defmethod color-equals ((a rgb) (b hsv) &key (tolerance *epsilon*))
  (color-equals (as-hsv a) b :tolerance tolerance))

(defmethod color-equals ((a rgb) (b hsl) &key (tolerance *epsilon*))
  (color-equals (as-hsl a) b :tolerance tolerance))

(defmethod color-equals ((a hsl) (b hsv) &key (tolerance *epsilon*))
  (color-equals (as-hsv b) b :tolerance tolerance))

(defmethod color-equals ((a hsl) (b rgb) &key (tolerance *epsilon*))
  (color-equals (as-rgb a) b :tolerance tolerance))

;;; macros used by the auto generated files

(defun colorname->constant-name (name)
  (symbolicate #\+
               (cl-ppcre:regex-replace-all "\\s+" (string-upcase name) "-")
               #\+))

(defmacro define-rgb-color (name red green blue)
  "Macro for defining color constants.  Used by the automatically generated color file."
  (let ((constant-name (colorname->constant-name name)))
    `(progn
       (define-constant ,constant-name (rgb ,red ,green ,blue)
         :test #'equalp :documentation ,(format nil "X11 color ~A." name)))))
