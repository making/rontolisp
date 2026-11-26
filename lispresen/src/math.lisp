;;;; math.lisp -- formulas: (math expr) laid out from its S-expression.
;;;;
;;;; A formula is a tree of boxes measured from the same width table as the text, in
;;;; milli-em of the font size it is set at, so its layout is computed once at parse time
;;;; and scales with any size: the fit solver sees a box, and the browser draws each glyph
;;;; at the place the model gave it, as it draws a diagram's shapes. The output is an
;;;; inline SVG of <text> elements, rules and the radical sign; its coordinates are em, so
;;;; an inline formula follows the font size of the text around it.
;;;;
;;;; Vertical metrics of Hiragino Sans at 1000 px, measured in Chrome 155 on 2026-10-24
;;;; (DESIGN.md, Formulas): x-height 545, capitals 766, digits 780, descenders 210, the
;;;; axis (the middle of + and =) 276 above the baseline, parentheses 778 above and 159
;;;; below it.

(in-package :lispresen)

(defparameter *math-axis* 276 "The height of the math axis above the baseline.")
(defparameter *math-rule* 50 "The thickness of fraction bars, overbars and radicals.")
(defparameter *inline-ascent-limit*
  1300
  "An inline formula reaching higher above its baseline than this meets the line above.")
(defparameter *inline-descent-limit*
  600
  "An inline formula reaching lower below its baseline than this meets the line below.")

;;; A laid-out box: WIDTH, ASCENT above and DESCENT below its baseline in milli-em,
;;; ITEMS placed from its left edge and baseline (y down), IC the italic correction a
;;; superscript moves right by, CLASS the spacing class of the box among its neighbors.
;;; ITEMS are
;;;   (:text X Y W K STYLE TEXT)     TEXT at scale K (milli), centered in W from X, its
;;;                                  baseline at Y; STYLE :it, :up, :bold or :text
;;;   (:rule X Y W H)                a filled rectangle from its top left corner
;;;   (:poly X1 Y1 X2 Y2 ...)        a filled polygon
;;;   (:stretch X Y W K GLYPH SY C)  GLYPH at scale K, stretched SY/1000 times
;;;                                  vertically about its ink center C above its
;;;                                  baseline, that center at X + W/2, Y
(defstruct mbox width ascent descent items (ic 0) (class :ord))

;;; BOX is the whole formula, SPOKEN its text for assistive technology, DISPLAY true
;;; for a block formula.
(defstruct formula box spoken display expr)

(defun math-form-p (x)
  (and (consp x) (car x) (symbolp (car x)) (string-equal (symbol-name (car x)) "MATH")))

;;; --- symbols -------------------------------------------------------------------------

;;; (NAME TEXT CLASS SPOKEN): the symbols a formula names, TEXT their glyphs.
(defparameter *math-symbols*
  '(("alpha" "α" :ord "alpha") ("beta" "β" :ord "beta") ("gamma" "γ" :ord "gamma")
    ("delta" "δ" :ord "delta") ("epsilon" "ε" :ord "epsilon") ("zeta" "ζ" :ord "zeta")
    ("eta" "η" :ord "eta") ("theta" "θ" :ord "theta") ("iota" "ι" :ord "iota")
    ("kappa" "κ" :ord "kappa") ("lambda" "λ" :ord "lambda") ("mu" "μ" :ord "mu")
    ("nu" "ν" :ord "nu") ("xi" "ξ" :ord "xi") ("pi" "π" :ord "pi") ("rho" "ρ" :ord "rho")
    ("sigma" "σ" :ord "sigma") ("tau" "τ" :ord "tau") ("upsilon" "υ" :ord "upsilon")
    ("phi" "φ" :ord "phi") ("chi" "χ" :ord "chi") ("psi" "ψ" :ord "psi")
    ("omega" "ω" :ord "omega") ("Gamma" "Γ" :ord "capital gamma")
    ("Delta" "Δ" :ord "capital delta") ("Theta" "Θ" :ord "capital theta")
    ("Lambda" "Λ" :ord "capital lambda") ("Xi" "Ξ" :ord "capital xi")
    ("Pi" "Π" :ord "capital pi") ("Sigma" "Σ" :ord "capital sigma")
    ("Phi" "Φ" :ord "capital phi") ("Psi" "Ψ" :ord "capital psi")
    ("Omega" "Ω" :ord "capital omega")
    ("inf" "∞" :ord "infinity") ("infty" "∞" :ord "infinity") ("partial" "∂" :ord "partial")
    ("nabla" "∇" :ord "nabla") ("dots" "…" :ord "dots") ("ldots" "…" :ord "dots")
    ("cdots" "⋯" :ord "dots") ("emptyset" "∅" :ord "the empty set")
    ("forall" "∀" :ord "for all") ("exists" "∃" :ord "there exists")
    ("reals" "ℝ" :ord "the reals") ("naturals" "ℕ" :ord "the naturals")
    ("integers" "ℤ" :ord "the integers") ("rationals" "ℚ" :ord "the rationals")
    ("complex" "ℂ" :ord "the complex numbers") ("ell" "ℓ" :ord "ell")
    ("hbar" "ℏ" :ord "h bar") ("deg" "°" :ord "degrees") ("prime" "′" :ord "prime")
    ("therefore" "∴" :ord "therefore") ("because" "∵" :ord "because")
    ("perp" "⊥" :ord "perpendicular") ("angle" "∠" :ord "angle")
    ("times" "×" :bin "times") ("cdot" "⋅" :bin "dot") ("pm" "±" :bin "plus or minus")
    ("mp" "∓" :bin "minus or plus") ("div" "÷" :bin "divided by") ("circ" "∘" :bin "composed with")
    ("oplus" "⊕" :bin "direct sum") ("otimes" "⊗" :bin "tensor") ("cup" "∪" :bin "union")
    ("cap" "∩" :bin "intersect") ("setminus" "∖" :bin "minus") ("and" "∧" :bin "and")
    ("or" "∨" :bin "or") ("not" "¬" :ord "not")
    ("neq" "≠" :rel "is not equal to") ("leq" "≤" :rel "is at most")
    ("geq" "≥" :rel "is at least") ("approx" "≈" :rel "is approximately")
    ("equiv" "≡" :rel "is equivalent to") ("sim" "∼" :rel "is similar to")
    ("propto" "∝" :rel "is proportional to") ("in" "∈" :rel "is in")
    ("notin" "∉" :rel "is not in") ("subset" "⊂" :rel "is a subset of")
    ("subseteq" "⊆" :rel "is a subset of or equal to") ("supset" "⊃" :rel "is a superset of")
    ("to" "→" :rel "to") ("->" "→" :rel "to") ("<-" "←" :rel "from") ("=>" "⇒" :rel "implies")
    ("implies" "⇒" :rel "implies") ("<=>" "⇔" :rel "if and only if") ("iff" "⇔" :rel "if and only if")
    ("mapsto" "↦" :rel "maps to") ("ll" "≪" :rel "is much less than")
    ("gg" "≫" :rel "is much greater than") ("parallel" "∥" :rel "is parallel to")
    ("=" "=" :rel "equals") ("<" "<" :rel "is less than") (">" ">" :rel "is greater than")
    ("<=" "≤" :rel "is at most") (">=" "≥" :rel "is at least") ("/=" "≠" :rel "is not equal to")
    ("!=" "≠" :rel "is not equal to") ("~" "≈" :rel "is approximately")
    ("+" "+" :bin "plus") ("-" "−" :bin "minus") ("+-" "±" :bin "plus or minus")
    ("mod" "mod" :bin "mod")))

(defparameter *math-functions*
  '("sin" "cos" "tan" "cot" "sec" "csc" "arcsin" "arccos" "arctan" "sinh" "cosh"
    "tanh" "log" "ln" "lg" "exp" "det" "gcd" "max" "min" "dim" "ker" "arg" "Pr")
  "Function names set upright, their argument without parentheses when it is simple.")

(defun math-symbol (name)
  (find name *math-symbols* :key #'first :test #'string=))

;;; The ink of a glyph above and below the baseline, in milli-em, measured on Hiragino
;;; Sans: the symbols formulas use, then a letter by its shape.
(defparameter *glyph-extents*
  '((#\( 778 159) (#\) 778 159) (#\[ 766 149) (#\] 766 149) (#\{ 766 149) (#\} 766 149)
    (#\| 766 204) (#\/ 766 149) (#\< 564 4) (#\> 564 4) (#\= 415 0) (#\+ 551 0)
    (#\, 105 123) (#\. 105 0) (#\! 766 0) (#\: 545 0) (#\; 545 123)
    (#x2212 310 0) (#x2211 773 1) (#x220F 680 0) (#x222B 667 238) (#x221E 451 0)
    (#x2264 680 0) (#x2265 680 0) (#x2260 588 28) (#x2248 455 0) (#x2208 534 0)
    (#x2209 534 0) (#x2192 585 0) (#x2190 585 0) (#x22EF 442 0) (#x2026 442 0)
    (#xD7 515 0) (#x22C5 357 0) (#xB1 627 0) (#x2213 549 14) (#xF7 524 0)
    (#x2202 762 0) (#x2207 768 13) (#x2032 774 0) (#x230A 760 132) (#x230B 760 132)
    (#x2308 760 132) (#x2309 760 132) (#x2016 837 77) (#x27E8 680 98) (#x27E9 680 98)
    (#x2200 785 13) (#x2203 766 0) (#xAC 385 0) (#x2227 613 16) (#x2228 587 43)
    (#x222A 594 10) (#x2229 604 0) (#x2282 534 0) (#x2283 534 0) (#x2286 596 0)
    (#x2287 596 0) (#x2205 781 10) (#x211D 729 0) (#x2115 729 0) (#x2124 729 0)
    (#x211A 729 0) (#x2102 737 19) (#x2218 262 0) (#x2261 479 0) (#x223C 343 0)
    (#x221D 451 0) (#x21D2 515 0) (#x21D4 515 0) (#x21A6 436 0) (#x2113 775 17)
    (#x210F 764 0) (#xB0 781 0) (#x2234 524 0) (#x2235 524 0) (#x2295 781 9)
    (#x2297 781 9) (#x2C6 760 0) (#x2D9 760 0) (#x2DC 760 0) (#x2216 766 149)
    (#x226A 680 0) (#x226B 680 0) (#x22A5 680 0) (#x2225 766 149) (#x2220 680 0)
    (#x3B2 772 119) (#x3B3 551 119) (#x3B4 772 13) (#x3B6 772 119) (#x3B7 551 119)
    (#x3B8 773 12) (#x3BB 771 5) (#x3BC 539 119) (#x3BE 772 119) (#x3C1 551 119)
    (#x3C6 760 119) (#x3C7 551 119) (#x3C8 760 119) (#x3C2 551 119)))

(defun glyph-extent (c)
  "The ink of the character C above and below the baseline, as (ASCENT . DESCENT)."
  (let* ((code (char-code c))
         (known (or (assoc c *glyph-extents*) (assoc code *glyph-extents*))))
    (cond (known (cons (second known) (third known)))
          ((digit-char-p c) (cons 780 0))
          ((find c "gjpqy") (cons (if (char= c #\j) 770 555) 210))
          ((find c "bdfhklt") (cons 770 0))
          ((<= #x3B1 code #x3C9) (cons 552 14))     ; the other Greek small letters
          ((<= #x391 code #x3A9) (cons 770 10))
          ((lower-case-p c) (cons 550 0))
          ((upper-case-p c) (cons 770 0))
          ((char= c #\Space) (cons 0 0))
          ((< code 128) (cons 766 0))
          (t (cons 780 150)))))

;;; --- boxes -------------------------------------------------------------------------

(defun msc (v k)
  "V milli-em scaled by the factor K (in milli), to the nearest milli."
  (floor (+ (* v k) 500) 1000))

(defun level-scale (level)
  "The scale of script LEVEL: 0.7 for a script, 0.5 for a script of a script."
  (case level (0 1000) (1 700) (t 500)))

(defun shift-items (items dx dy)
  (mapcar (lambda (item)
            (if (eq (car item) :poly)
                (cons :poly
                      (loop for (x y) on (cdr item) by #'cddr
                            collect (+ x dx)
                            collect (+ y dy)))
                (list* (first item) (+ (second item) dx) (+ (third item) dy)
                       (cdddr item))))
          items))

(defun text-box (text style k &optional (class :ord))
  "The box of TEXT set at scale K in STYLE: as wide as the width table says, as high
and deep as the ink of its characters."
  (let ((w 0) (asc 0) (desc 0))
    (loop for c across text
          do
            (let ((e (glyph-extent c)))
              (incf w (char-width (char-code c) (if (eq style :bold) :bold :normal)))
              (setf asc (max asc (car e)) desc (max desc (cdr e)))))
    (setf w (msc w k) asc (msc asc k) desc (msc desc k))
    (make-mbox :width w
               :ascent asc
               :descent desc
               :items (list (list :text 0 0 w k style text))
               :ic (if (eq style :it) (msc 100 k) 0)
               :class class)))

(defun space-box (w)
  (make-mbox :width w :ascent 0 :descent 0 :items nil :class :space))

(defun inter-space (a b level)
  "The space between neighbors of classes A and B in milli-em at the base size: TeX's
thin, medium and thick spaces, dropped in scripts but after a comma."
  (flet ((is (x &rest classes) (member x classes)))
    (cond ((or (null a) (is a :open :space) (is b :close :space)) 0)
          ((is a :punct) 167)
          ((is b :punct) 0)
          ((or (is a :text) (is b :text)) 0)
          ((> level 0) 0)
          ((or (is a :bin) (is b :bin)) 222)
          ((or (is a :rel) (is b :rel)) 278)
          ((or (is a :op) (is b :op) (is a :inner) (is b :inner)) 167)
          (t 0))))

(defun row-box (boxes level)
  "BOXES side by side on one baseline, spaced by their classes; a binary operator at the
start or after another operator is set as an ordinary symbol (a unary minus)."
  (if (and boxes (null (cdr boxes)))
      (car boxes)
      (let ((x 0) (asc 0) (desc 0) (items nil) (prev nil) (k (level-scale level)))
        (dolist (b boxes)
          (let ((class (mbox-class b)))
            (when (and (eq class :bin) (or (null prev) (member prev '(:bin :rel :open :op))))
              (setf class :ord))
            (incf x (msc (inter-space prev class level) k))
            (setf items (revappend (shift-items (mbox-items b) x 0) items))
            (incf x (mbox-width b))
            (setf asc (max asc (mbox-ascent b)) desc (max desc (mbox-descent b)))
            (setf prev class)))
        (make-mbox :width x
                   :ascent asc
                   :descent desc
                   :items (nreverse items)
                   :ic (if boxes (mbox-ic (car (last boxes))) 0)
                   ;; A row led by text or a function name keeps that spacing on its left.
                   :class (if (and boxes (member (mbox-class (car boxes)) '(:text :op)))
                              (mbox-class (car boxes))
                              :ord)))))

(defun place (box dx dy)
  "BOX's items moved by DX, DY."
  (shift-items (mbox-items box) dx dy))

(defun frac-box (num den level display &optional (rule t))
  "NUM over DEN, the bar on the axis; in text style the parts are a size smaller."
  (let* ((k (level-scale level))
         (t0 (if rule (msc *math-rule* k) 0))
         (gap (msc (if display 150 100) k))
         (pad (msc 120 k))
         (axis (msc *math-axis* k))
         (w (+ (max (mbox-width num) (mbox-width den)) (* 2 pad)))
         (yn (- (+ axis (floor t0 2) gap (mbox-descent num))))
         (yd (+ (- axis) (ceiling t0 2) gap (mbox-ascent den))))
    (make-mbox :width w
               :ascent (- (mbox-ascent num) yn)
               :descent (+ yd (mbox-descent den))
               :items (append (place num (floor (- w (mbox-width num)) 2) yn)
                              (and rule
                                   (list (list :rule (floor pad 2) (- (+ axis (floor t0 2)))
                                               (- w pad) t0)))
                              (place den (floor (- w (mbox-width den)) 2) yd))
               :class :inner)))

(defun scripts-box (base sub sup level)
  "BASE with its subscript SUB and superscript SUP, either nil."
  (let* ((k (level-scale level))
         (ysup (and sup
                    (- (max (msc 450 k) (- (mbox-ascent base) (msc 200 k))
                            (+ (mbox-descent sup) (msc 200 k))))))
         (ysub (and sub
                    (max (msc 200 k) (+ (mbox-descent base) (msc 120 k))
                         (- (mbox-ascent sub) (msc 440 k))))))
    (when (and sub sup (< (- (- ysub (mbox-ascent sub)) (+ ysup (mbox-descent sup)))
                          (msc 200 k)))
      (setf ysub (+ ysup (mbox-descent sup) (msc 200 k) (mbox-ascent sub))))
    (let* ((x (mbox-width base))
           (w (+ x (max (if sup (+ (mbox-width sup) (mbox-ic base)) 0)
                        (if sub (mbox-width sub) 0))
                 (msc 40 k))))
      (make-mbox :width w
                 :ascent (max (mbox-ascent base) (if sup (- (mbox-ascent sup) ysup) 0))
                 :descent (max (mbox-descent base) (if sub (+ ysub (mbox-descent sub)) 0))
                 :items (append (mbox-items base)
                                (and sup (place sup (+ x (mbox-ic base)) ysup))
                                (and sub (place sub x ysub)))
                 :class (mbox-class base)))))

;;; The radical glyph's ink above and below the baseline and its advance at 1000.
(defparameter *radical-glyph* '(905 74 571))

(defun radical-box (body index level display)
  "BODY under an overbar joined to the radical sign, the glyph stretched vertically to
the body's height as a delimiter is, INDEX (or nil) in its crook."
  (let* ((k (level-scale level))
         (t0 (msc *math-rule* k))
         (gap (msc (if display 120 80) k))
         (top (- (+ (mbox-ascent body) gap t0)))
         (bottom (+ (mbox-descent body) (msc 40 k)))
         (h (- bottom top))
         (sw (msc (third *radical-glyph*) k))
         (ink (msc (+ (first *radical-glyph*) (second *radical-glyph*)) k))
         (sy (max 1000 (ceiling (* h 1000) ink)))
         (center (floor (- (first *radical-glyph*) (second *radical-glyph*)) 2))
         (shift (if index (max 0 (- (mbox-width index) (floor (* sw 9) 20))) 0))
         (w (+ shift sw (mbox-width body) (msc 60 k)))
         (sign (list (list :stretch shift (floor (+ top bottom) 2) sw k "√" sy center)
                     (list :rule (+ shift (msc 540 k)) top
                           (+ (mbox-width body) (msc 91 k)) t0))))
    (make-mbox :width w
               :ascent (- top)
               :descent bottom
               :items (append sign
                              (and index
                                   (place index 0 (- (+ top (floor (* h 45) 100))
                                                     (mbox-descent index))))
                              (place body (+ shift sw) 0))
               :class :ord)))

;;; The ink of a delimiter glyph above and below the baseline at 1000, and its width.
(defun delimiter-glyph (text)
  (let ((e (glyph-extent (char text 0))))
    (values (car e) (cdr e) (char-width (char-code (char text 0)) :normal))))

(defun delimiter-box (text body level)
  "The delimiter TEXT beside BODY: the glyph as it is when the body is no taller than a
line, else stretched vertically about the axis to cover it."
  (let* ((k (level-scale level))
         (axis (msc *math-axis* k))
         (half (max (- (mbox-ascent body) axis) (+ (mbox-descent body) axis))))
    (if (<= half (msc 560 k))
        (text-box text :up k :open)
        (multiple-value-bind (asc desc w) (delimiter-glyph text)
          (let* ((need (* 2 (+ half (msc 60 k))))
                 (ink (msc (+ asc desc) k))
                 (sy (ceiling (* need 1000) ink))
                 (w (msc w k))
                 (center (floor (- asc desc) 2)))
            (make-mbox :width w
                       :ascent (+ axis (floor need 2))
                       :descent (- (ceiling need 2) axis)
                       :items (list (list :stretch 0 (- axis) w k text sy center))
                       :class :open))))))

(defun stretched-p (box)
  (eq (car (first (mbox-items box))) :stretch))

(defun fenced-box (open close body level)
  "BODY between the delimiters OPEN and CLOSE (either nil), which grow with it; a grown
delimiter stands a little clear of the body."
  (let* ((boxes (list body))
         (gap (space-box (msc 80 (level-scale level)))))
    (when close
      (let ((c (delimiter-box close body level)))
        (setf (mbox-class c) :close)
        (setf boxes (append boxes (if (stretched-p c) (list gap c) (list c))))))
    (when open
      (let ((o (delimiter-box open body level)))
        (setf boxes (if (stretched-p o) (list* o gap boxes) (cons o boxes)))))
    (let ((row (row-box boxes level)))
      (setf (mbox-class row) :ord)
      row)))

(defun stack-rows (rows level col-gap row-gap &optional (align :center))
  "ROWS, lists of boxes, in columns as wide as their widest cell, centered on the axis."
  (let* ((k (level-scale level))
         (ncol (reduce #'max (mapcar #'length rows)))
         (widths (make-list ncol :initial-element 0))
         (cg (msc col-gap k))
         (rg (msc row-gap k))
         (height 0)
         (items nil)
         (y 0))
    (dolist (row rows)
      (loop for b in row
            for j from 0
            do (setf (nth j widths) (max (nth j widths) (mbox-width b)))))
    (let ((w (+ (reduce #'+ widths) (* cg (1- ncol)))))
      (dolist (row rows)
        (let ((asc (reduce #'max (mapcar #'mbox-ascent row)))
              (desc (reduce #'max (mapcar #'mbox-descent row))))
          (incf y asc)
          (let ((x 0))
            (loop for b in row
                  for cw in widths
                  do
                    (setf items
                          (revappend (place b (+ x (if (eq align :left)
                                                       0
                                                       (floor (- cw (mbox-width b)) 2)))
                                            y)
                                     items))
                    (incf x (+ cw cg))))
          (incf y (+ desc rg))))
      (setf height (- y rg))
      ;; Centered on the axis.
      (let* ((axis (msc *math-axis* k)) (top (- (+ axis (floor height 2)))))
        (make-mbox :width w
                   :ascent (- top)
                   :descent (+ top height)
                   :items (shift-items (nreverse items) 0 top)
                   :class :ord)))))

(defun bigop-box (glyph lower upper body level display)
  "The large operator GLYPH with its limits LOWER and UPPER (either nil) and BODY: in
display style the operator is larger and its limits stand below and above it, except
an integral's, which sit beside it; in text style they are scripts."
  (let* ((k (level-scale level))
         (big (if display (msc 1400 k) k))
         (op (text-box glyph :up big :op))
         (e (glyph-extent (char glyph 0)))
         (center (msc (floor (- (car e) (cdr e)) 2) big))
         (axis (msc *math-axis* k)))
    ;; The operator's ink centered on the axis.
    (setf (mbox-items op) (place op 0 (- center axis))
          (mbox-ascent op) (+ axis center) (mbox-descent op) (+ (mbox-descent op) (- center axis)))
    (let ((head
           (if (and display (not (string= glyph "∫")) (or lower upper))
               (let* ((gap (msc 100 k))
                      (w (max (mbox-width op) (if lower (mbox-width lower) 0)
                              (if upper (mbox-width upper) 0)))
                      (items (place op (floor (- w (mbox-width op)) 2) 0))
                      (asc (mbox-ascent op))
                      (desc (mbox-descent op)))
                 (when lower
                   (let ((y (+ desc gap (mbox-ascent lower))))
                     (setf items (append items (place lower (floor (- w (mbox-width lower)) 2) y))
                           desc (+ y (mbox-descent lower)))))
                 (when upper
                   (let ((y (- (+ asc gap (mbox-descent upper)))))
                     (setf items (append items (place upper (floor (- w (mbox-width upper)) 2) y))
                           asc (- (mbox-ascent upper) y))))
                 (make-mbox :width w :ascent asc :descent desc :items items :class :op))
               (if (or lower upper)
                   (let ((s (scripts-box op lower upper level)))
                     (setf (mbox-class s) :op)
                     s)
                   op))))
      (row-box (list head body) level))))

(defun accent-box (kind body level)
  "BODY under an overbar, a hat, an arrow or a dot."
  (let* ((k (level-scale level))
         (t0 (msc *math-rule* k))
         (gap (msc 80 k))
         (top (- (+ (mbox-ascent body) gap))))
    (if (eq kind :bar)
        (make-mbox :width (mbox-width body)
                   :ascent (- (- top t0))
                   :descent (mbox-descent body)
                   :items (cons (list :rule 0 (- top t0) (mbox-width body) t0)
                                (mbox-items body))
                   :class :ord)
        (let* ((glyph (case kind (:hat "ˆ") (:vec "→") (t "˙")))
               (scale (if (eq kind :vec) (msc 500 k) k))
               (mark (text-box glyph :up scale))
               ;; The glyph's ink sits above the baseline; set its bottom at TOP.
               (bottom (msc (case kind (:hat 626) (:vec 176) (t 600)) scale))
               (y (+ top bottom))
               (asc (- (mbox-ascent mark) y)))
          (make-mbox :width (mbox-width body)
                     :ascent (max (mbox-ascent body) asc)
                     :descent (mbox-descent body)
                     :items (append (place mark (floor (- (mbox-width body) (mbox-width mark)) 2) y)
                                    (mbox-items body))
                     :class :ord)))))

;;; --- parsing and layout ------------------------------------------------------------

(defun mfail (message)
  (report :error message)
  (throw 'math-fail nil))

(defun math-tag (x)
  "The upper-case name of the operator the form X applies, or nil."
  (and (consp x) (car x) (symbolp (car x)) (string-upcase (symbol-name (car x)))))

(defun simple-arg-p (x)
  "True when X needs no parentheses after a function name: an atom, or a script of one."
  (or (atom x)
      (and (member (math-tag x) '("^" "EXPT" "SUP" "_" "SUB") :test #'equal)
           (atom (second x)))))

(defun operator-form-p (x classes)
  "True when X applies an operator of one of the spacing CLASSES: a sum, a product, a
relation."
  (let ((s (and (math-tag x) (math-symbol (symbol-name (car x))))))
    (and s (member (third s) classes) t)))

(defun additive-form-p (x)
  "True for a sum or difference, which a factor or a subtrahend sets in parentheses."
  (and (operator-form-p x '(:bin))
       (not (member (math-tag x) '("TIMES" "CDOT" "CIRC" "OTIMES" "AND" "OR" "CAP") :test #'equal))))

(defun parens-needed-p (x context)
  "Whether the operand X of CONTEXT is written in parentheses, as a writer would: a sum
as a factor or after a minus sign, any operator form as the base of a script."
  (case context
    (:factor (or (additive-form-p x) (operator-form-p x '(:rel))))
    (:base (or (operator-form-p x '(:bin :rel)) (equal (math-tag x) "*")))
    (t nil)))

(defun lay (x level display)
  "The box of the formula X at script LEVEL, in DISPLAY or text style."
  (let ((k (level-scale level)))
    (labels ((sub (y &optional (l level) (d display)) (lay y l d))
             (operand (y context)
               (if (parens-needed-p y context)
                   (fenced-box "(" ")" (sub y) level)
                   (sub y)))
             (args (x n what)
               (unless (and (listp (cdr x)) (>= (length (cdr x)) n))
                 (mfail (format nil "(~a ...) takes ~a" (string-downcase (math-tag x)) what)))
               (cdr x))
             (row (items) (row-box items level))
             (commas (forms)
               (let ((out nil))
                 (loop for f in forms
                       for first = t then nil
                       do (unless first (push (text-box "," :up k :punct) out))
                         (push (sub f) out))
                 (row (nreverse out))))
             (fenced (open close forms) (fenced-box open close (commas forms) level))
             (identifier (name)
               (if (= (length name) 1)
                   (text-box name (if (alpha-char-p (char name 0)) :it :up) k)
                   (text-box name :up k)))
             (text (s)
               (let* ((s (normalize-text s)) (n (length s))
                      (a (or (position #\Space s :test #'char/=) n))
                      (b (if (< a n) (1+ (position #\Space s :test #'char/= :from-end t)) n))
                      (boxes nil))
                 (when (> a 0) (push (space-box (msc (* 333 a) k)) boxes))
                 (when (< a b) (push (text-box (subseq s a b) :text k :text) boxes))
                 (when (< b n) (push (space-box (msc (* 333 (- n b)) k)) boxes))
                 (row (nreverse boxes)))))
      (cond ((integerp x)
             (if (< x 0)
                 (row (list (text-box "−" :up k) (text-box (format nil "~d" (- x)) :up k)))
                 (text-box (format nil "~d" x) :up k)))
            ((rationalp x)
             (let ((f (frac-box (sub (abs (numerator x)) (if display level (1+ level)))
                                (sub (denominator x) (if display level (1+ level)))
                                level display)))
               (if (< x 0) (row (list (text-box "−" :up k) f)) f)))
            ((floatp x) (mfail "a number in a formula is an integer, a ratio or a string, such as \"0.5\""))
            ((stringp x) (text x))
            ((characterp x) (identifier (string x)))
            ((null x) (mfail "a formula holds no empty list"))
            ((eq x t) (identifier "t"))
            ((keywordp x) (mfail (format nil "unexpected ~a in a formula" (written x))))
            ((symbolp x)
             (let ((s (math-symbol (symbol-name x))))
               (if s
                   (text-box (second s) :up k (third s))
                   (identifier (symbol-name x)))))
            ((not (consp (car x)))
             (let ((tag (math-tag x)))
               (unless tag (mfail (format nil "unexpected ~a in a formula" (written (car x)))))
               (cond
                 ;; Relations and binary operators chain: (= a b c) is a = b = c.
                 ((and (math-symbol (symbol-name (car x)))
                       (member (third (math-symbol (symbol-name (car x)))) '(:rel :bin)))
                  (let* ((s (math-symbol (symbol-name (car x))))
                         (op (second s))
                         (class (third s))
                         (forms (args x 1 "at least one operand")))
                    (if (and (null (cdr forms)) (eq class :bin))
                        ;; Unary: −x, ±x.
                        (row (list (text-box op :up k) (operand (car forms) :factor)))
                        (progn
                          (when (null (cdr forms))
                            (mfail (format nil "(~a ...) takes two operands or more"
                                           (string-downcase tag))))
                          (row (loop for f in forms
                                     for first = t then nil
                                     unless first
                                       collect (text-box op :up k class)
                                     ;; A sum after a minus sign is parenthesized.
                                     collect (if (and (not first) (equal tag "-"))
                                                 (operand f :factor)
                                                 (sub f))))))))
                 ((equal tag "NOT")
                  (let ((forms (args x 1 "its content")))
                    (row (list (text-box "¬" :up k) (sub (car forms))))))
                 ((equal tag "*")
                  (let ((forms (args x 1 "at least one factor")) (out nil) (prev nil))
                    (dolist (f forms)
                      (when (and prev (integerp prev) (integerp f))
                        (push (text-box "⋅" :up k :bin) out))
                      (push (operand f :factor) out)
                      (setf prev f))
                    (row (nreverse out))))
                 ((member tag '("/" "FRAC") :test #'equal)
                  (let ((forms (args x 2 "a numerator and a denominator")))
                    (when (cddr forms) (mfail "(/ ...) takes a numerator and a denominator"))
                    (let ((l (if display level (1+ level))))
                      (frac-box (sub (first forms) l) (sub (second forms) l) level display))))
                 ((member tag '("BINOM" "CHOOSE") :test #'equal)
                  (let* ((forms (args x 2 "n and k"))
                         (l (if display level (1+ level)))
                         (f (frac-box (sub (first forms) l) (sub (second forms) l) level display nil)))
                    (fenced-box "(" ")" f level)))
                 ((member tag '("^" "EXPT" "SUP" "_" "SUB") :test #'equal)
                  (let* ((forms (args x 2 "a base and a script"))
                         (base (first forms))
                         (script (sub (second forms) (1+ level) nil))
                         (sup (member tag '("^" "EXPT" "SUP") :test #'equal)))
                    (when (cddr forms) (mfail (format nil "(~a ...) takes a base and a script" (string-downcase tag))))
                    ;; (^ (_ x i) 2) sets both scripts on x.
                    (if (and (consp base)
                             (member (math-tag base) (if sup '("_" "SUB") '("^" "EXPT" "SUP")) :test #'equal)
                             (= (length base) 3))
                        (let ((other (sub (third base) (1+ level) nil)))
                          (scripts-box (operand (second base) :base) (if sup other script) (if sup script other) level))
                        (scripts-box (operand base :base) (if sup nil script) (if sup script nil) level))))
                 ((equal tag "SQRT")
                  (let ((forms (args x 1 "the radicand")))
                    (radical-box (sub (car forms)) nil level display)))
                 ((equal tag "ROOT")
                  (let ((forms (args x 2 "the index and the radicand")))
                    (radical-box (sub (second forms)) (sub (first forms) 2 nil) level display)))
                 ((equal tag "ABS") (fenced "|" "|" (args x 1 "its content")))
                 ((equal tag "NORM") (fenced "‖" "‖" (args x 1 "its content")))
                 ((equal tag "FLOOR") (fenced "⌊" "⌋" (args x 1 "its content")))
                 ((equal tag "CEIL") (fenced "⌈" "⌉" (args x 1 "its content")))
                 ((equal tag "PAREN") (fenced "(" ")" (args x 1 "its content")))
                 ((equal tag "BRACKET") (fenced "[" "]" (args x 1 "its content")))
                 ((equal tag "BRACE") (fenced "{" "}" (args x 1 "its content")))
                 ((equal tag "SET") (fenced "{" "}" (args x 1 "its elements")))
                 ((equal tag "TUPLE") (fenced "(" ")" (args x 1 "its elements")))
                 ((equal tag "ANGLE") (fenced "⟨" "⟩" (args x 1 "its content")))
                 ((equal tag "COMMA") (commas (args x 1 "its items")))
                 ((member tag '("SUM" "PROD" "INT" "BIGCUP" "BIGCAP") :test #'equal)
                  (let* ((forms (args x 1 "a body, after its limits"))
                         (n (length forms))
                         (glyph (cond ((equal tag "SUM") "∑") ((equal tag "PROD") "∏")
                                      ((equal tag "INT") "∫") ((equal tag "BIGCUP") "∪")
                                      (t "∩"))))
                    (when (> n 3) (mfail (format nil "(~a ...) takes a lower limit, an upper limit and a body" (string-downcase tag))))
                    (bigop-box glyph
                               (and (> n 1) (sub (first forms) (1+ level) nil))
                               (and (> n 2) (sub (second forms) (1+ level) nil))
                               (sub (car (last forms))) level display)))
                 ((member tag '("LIM" "ARGMAX" "ARGMIN") :test #'equal)
                  (let* ((forms (args x 2 "a limit and a body"))
                         (name (text-box (string-downcase tag) :up k :op))
                         (limit (sub (first forms) (1+ level) nil))
                         (head (if display
                                   (let* ((w (max (mbox-width name) (mbox-width limit)))
                                          (y (+ (mbox-descent name) (msc 150 k) (mbox-ascent limit))))
                                     (make-mbox :width w :ascent (mbox-ascent name)
                                                :descent (+ y (mbox-descent limit))
                                                :items (append (place name (floor (- w (mbox-width name)) 2) 0)
                                                               (place limit (floor (- w (mbox-width limit)) 2) y))
                                                :class :op))
                                   (let ((s (scripts-box name limit nil level)))
                                     (setf (mbox-class s) :op)
                                     s))))
                    (row (list head (sub (second forms))))))
                 ((member tag '("MATRIX" "BMATRIX" "VMATRIX") :test #'equal)
                  (let ((rows (args x 1 "rows of cells")))
                    (dolist (r rows)
                      (unless (consp r)
                        (mfail (format nil "a row of (~a ...) is a list of cells" (string-downcase tag)))))
                    (let ((m (stack-rows (mapcar (lambda (r) (mapcar #'sub r)) rows) level 500 300)))
                      (cond ((equal tag "BMATRIX") (fenced-box "[" "]" m level))
                            ((equal tag "VMATRIX") (fenced-box "|" "|" m level))
                            (t (fenced-box "(" ")" m level))))))
                 ((equal tag "CASES")
                  (let ((rows (args x 1 "rows of a value and its condition")))
                    (dolist (r rows)
                      (unless (and (consp r) (<= 1 (length r) 2))
                        (mfail "a row of (cases ...) is (value condition)")))
                    (fenced-box "{" nil
                                (stack-rows (mapcar (lambda (r) (mapcar #'sub r)) rows) level 1000 200 :left)
                                level)))
                 ((member tag '("BAR" "HAT" "VEC" "DOT") :test #'equal)
                  (let ((forms (args x 1 "its content")))
                    (accent-box (intern tag :keyword) (sub (car forms)) level)))
                 ((member tag '("BOLD" "B") :test #'equal)
                  (let ((forms (args x 1 "its content")))
                    (if (and (null (cdr forms)) (or (symbolp (car forms)) (characterp (car forms))))
                        (text-box (if (characterp (car forms)) (string (car forms)) (symbol-name (car forms))) :bold k)
                        (let ((b (row (mapcar #'sub forms))))
                          (setf (mbox-items b)
                                (mapcar (lambda (item)
                                          (if (eq (car item) :text)
                                              (append (subseq item 0 5) (list :bold) (nthcdr 6 item))
                                              item))
                                        (mbox-items b)))
                          b))))
                 ((equal tag "TEXT")
                  (let ((forms (args x 1 "a string")))
                    (unless (every #'stringp forms) (mfail "(text ...) takes strings"))
                    (text (apply #'concatenate 'string forms))))
                 ((equal tag "PRIME")
                  (let ((forms (args x 1 "its content")))
                    (row (list (operand (car forms) :base) (text-box "′" :up k)))))
                 ((member tag '("!" "FACTORIAL") :test #'equal)
                  (let ((forms (args x 1 "its content")))
                    (row (list (operand (car forms) :base) (text-box "!" :up k)))))
                 ((member tag '("DERIV" "PARTIAL") :test #'equal)
                  (let* ((forms (args x 2 "the function and the variable"))
                         (d (if (equal tag "DERIV") "d" "∂"))
                         (l (if display level (1+ level))))
                    (frac-box (row-box (list (text-box d :up (level-scale l)) (sub (first forms) l)) l)
                              (row-box (list (text-box d :up (level-scale l)) (sub (second forms) l)) l)
                              level display)))
                 ((member tag *math-functions* :test #'string-equal)
                  (let* ((forms (args x 1 "an argument"))
                         (fname (find tag *math-functions* :test #'string-equal)))
                    (if (and (null (cdr forms)) (simple-arg-p (car forms)))
                        (row (list (text-box fname :up k :op) (sub (car forms))))
                        (row (list (text-box fname :up k) (fenced "(" ")" forms))))))
                 ;; Anything else is a function applied to its arguments: f(x, y).
                 (t (row (list (sub (car x)) (fenced "(" ")" (cdr x))))))))
            (t (row (list (sub (car x)) (fenced "(" ")" (cdr x)))))))))

;;; --- the spoken form ---------------------------------------------------------------

(defun spoken (x)
  "The formula X read aloud, the text of its aria-label."
  (labels ((join (forms sep) (format nil (concatenate 'string "~{~a~^" sep "~}") (mapcar #'spoken forms)))
           (wrap (y) (if (and (consp y) (cdr y)) (format nil "(~a)" (spoken y)) (spoken y))))
    (cond ((integerp x) (format nil "~d" x))
          ((rationalp x) (format nil "~d over ~d" (numerator x) (denominator x)))
          ((stringp x) (string-trim " " x))
          ((characterp x) (string x))
          ((null x) "")
          ((eq x t) "t")
          ((symbolp x) (let ((s (math-symbol (symbol-name x)))) (if s (fourth s) (symbol-name x))))
          ((consp (car x)) (format nil "~a of ~a" (spoken (car x)) (join (cdr x) ", ")))
          (t
           (let* ((tag (math-tag x)) (s (and tag (math-symbol (symbol-name (car x))))) (a (cdr x)))
             (cond ((null tag) "")
                   ((and s (member (third s) '(:rel :bin)))
                    (if (and (null (cdr a)) (eq (third s) :bin))
                        (format nil "~a ~a" (fourth s) (wrap (car a)))
                        (join a (format nil " ~a " (fourth s)))))
                   ((equal tag "*") (join a " "))
                   ((member tag '("/" "FRAC") :test #'equal) (format nil "~a over ~a" (wrap (first a)) (wrap (second a))))
                   ((member tag '("BINOM" "CHOOSE") :test #'equal) (format nil "~a choose ~a" (wrap (first a)) (wrap (second a))))
                   ((member tag '("^" "EXPT" "SUP") :test #'equal)
                    (cond ((eql (second a) 2) (format nil "~a squared" (wrap (first a))))
                          ((eql (second a) 3) (format nil "~a cubed" (wrap (first a))))
                          (t (format nil "~a to the ~a" (wrap (first a)) (wrap (second a))))))
                   ((member tag '("_" "SUB") :test #'equal) (format nil "~a sub ~a" (wrap (first a)) (wrap (second a))))
                   ((equal tag "SQRT") (format nil "the square root of ~a" (spoken (first a))))
                   ((equal tag "ROOT") (format nil "the ~ath root of ~a" (spoken (first a)) (spoken (second a))))
                   ((equal tag "ABS") (format nil "the absolute value of ~a" (spoken (first a))))
                   ((equal tag "NORM") (format nil "the norm of ~a" (spoken (first a))))
                   ((equal tag "FLOOR") (format nil "the floor of ~a" (spoken (first a))))
                   ((equal tag "CEIL") (format nil "the ceiling of ~a" (spoken (first a))))
                   ((member tag '("PAREN" "BRACKET" "BRACE" "TUPLE") :test #'equal) (format nil "(~a)" (join a ", ")))
                   ((equal tag "SET") (format nil "the set of ~a" (join a ", ")))
                   ((equal tag "ANGLE") (join a ", "))
                   ((equal tag "COMMA") (join a ", "))
                   ((member tag '("SUM" "PROD" "INT" "BIGCUP" "BIGCAP") :test #'equal)
                    (let ((what (cond ((equal tag "SUM") "the sum") ((equal tag "PROD") "the product")
                                      ((equal tag "INT") "the integral") ((equal tag "BIGCUP") "the union")
                                      (t "the intersection"))))
                      (format nil "~a~@[ from ~a~]~@[ to ~a~] of ~a" what
                              (and (> (length a) 1) (spoken (first a)))
                              (and (> (length a) 2) (spoken (second a)))
                              (spoken (car (last a))))))
                   ((member tag '("LIM" "ARGMAX" "ARGMIN") :test #'equal)
                    (format nil "~a ~a of ~a" (cond ((equal tag "LIM") "the limit as") ((equal tag "ARGMAX") "arg max over") (t "arg min over"))
                            (spoken (first a)) (spoken (second a))))
                   ((member tag '("MATRIX" "BMATRIX" "VMATRIX") :test #'equal)
                    (format nil "~a matrix: ~{~a~^; ~}" (if (equal tag "VMATRIX") "the determinant of the" "the")
                            (mapcar (lambda (r) (join r ", ")) a)))
                   ((equal tag "CASES")
                    (format nil "~{~a~^; ~}"
                            (mapcar (lambda (r) (if (cdr r) (format nil "~a if ~a" (spoken (first r)) (spoken (second r))) (spoken (first r)))) a)))
                   ((equal tag "BAR") (format nil "~a bar" (wrap (first a))))
                   ((equal tag "HAT") (format nil "~a hat" (wrap (first a))))
                   ((equal tag "VEC") (format nil "vector ~a" (wrap (first a))))
                   ((equal tag "DOT") (format nil "~a dot" (wrap (first a))))
                   ((member tag '("BOLD" "B" "TEXT") :test #'equal) (join a " "))
                   ((equal tag "PRIME") (format nil "~a prime" (wrap (first a))))
                   ((member tag '("!" "FACTORIAL") :test #'equal) (format nil "~a factorial" (wrap (first a))))
                   ((equal tag "DERIV") (format nil "the derivative of ~a with respect to ~a" (spoken (first a)) (spoken (second a))))
                   ((equal tag "PARTIAL") (format nil "the partial derivative of ~a with respect to ~a" (spoken (first a)) (spoken (second a))))
                   ((and (member tag *math-functions* :test #'string-equal) (null (cdr a)) (simple-arg-p (car a)))
                    (format nil "~a ~a" (find tag *math-functions* :test #'string-equal) (spoken (car a))))
                   (t (format nil "~a of ~a" (symbol-name (car x)) (join a ", ")))))))))

;;; --- the element -------------------------------------------------------------------

(defun parse-math (form display)
  "The formula of (math expr...), laid out; nil after reporting a malformed one. Several
expressions stand side by side."
  (let ((exprs (cdr form)))
    (when (null exprs)
      (report :error "(math ...) needs an expression")
      (return-from parse-math nil))
    (let ((box
           (catch 'math-fail
             (row-box (mapcar (lambda (x) (lay x 0 display)) exprs) 0))))
      (when box
        (when (and (not display)
                   (or (> (mbox-ascent box) *inline-ascent-limit*)
                       (> (mbox-descent box) *inline-descent-limit*)))
          (report :warning
                  "the formula is taller than a line of text; set it as a block of its own"))
        (make-formula :box box
                      :spoken (format nil "~{~a~^ ~}" (mapcar #'spoken exprs))
                      :display display
                      :expr exprs)))))

(defun formula-width (f s)
  "The px width of the formula F at S px."
  (ceiling (* (mbox-width (formula-box f)) s) 1000))

(defun formula-height (f s)
  (let ((b (formula-box f))) (ceiling (* (+ (mbox-ascent b) (mbox-descent b)) s) 1000)))

;;; --- emission ----------------------------------------------------------------------

(defun write-em (v out)
  "V milli-em as a CSS length in em, with no float printed: 1234 is 1.234em."
  (when (< v 0) (write-char #\- out))
  (format out "~d.~3,'0dem" (floor (abs v) 1000) (mod (abs v) 1000)))

(defun write-formula (f out)
  "The formula F as an inline SVG: an inline one is a box 1 px high whose top edge is on
the baseline (a negative bottom margin, since the baseline of an inline SVG is its bottom
margin edge), its glyphs drawn around it, as SVG content is ink and not boxes and so
changes no line; a block one is as tall as its box, which the stylesheet centers. Every
coordinate is in em, so an inline formula takes the font size of the text around it."
  (let* ((box (formula-box f))
         (display (formula-display f))
         (asc (mbox-ascent box))
         (base (if display asc 0)))
    (write-string "<svg class=\"mx\" role=\"img\"" out)
    (write-attribute "aria-label" (formula-spoken f) out)
    (write-string " style=\"width:" out)
    (write-em (mbox-width box) out)
    (if display
        (progn
          (write-string ";height:" out)
          (write-em (+ asc (mbox-descent box)) out))
        (write-string ";height:1px;margin-bottom:-1px" out))
    (write-string "\">" out)
    (dolist (item (mbox-items box))
      (case (car item)
        (:text
         (destructuring-bind (x y w k style text) (cdr item)
           ;; A text element's em is its own font size.
           (write-string "<text x=\"" out)
           (write-em (floor (* (+ x (floor w 2)) 1000) k) out)
           (write-string "\" y=\"" out)
           (write-em (floor (* (+ y base) 1000) k) out)
           (write-string "\"" out)
           (unless (= k 1000)
             (write-string " font-size=\"" out)
             (write-em k out)
             (write-string "\"" out))
           (case style
             (:it (write-string " class=\"i\"" out))
             (:bold (write-string " class=\"b\"" out)))
           (write-string ">" out)
           (write-escaped text out)
           (write-string "</text>" out)))
        (:rule
         (destructuring-bind (x y w h) (cdr item)
           (write-string "<rect x=\"" out)
           (write-em x out)
           (write-string "\" y=\"" out)
           (write-em (+ y base) out)
           (write-string "\" width=\"" out)
           (write-em w out)
           (write-string "\" height=\"" out)
           (write-em h out)
           (write-string "\"/>" out)))
        (:poly
         (write-string "<polygon points=\"" out)
         (loop for (x y) on (cdr item) by #'cddr
               for first = t then nil
               do (unless first (write-char #\Space out))
                  (write-em x out)
                  (write-char #\, out)
                  (write-em (+ y base) out))
         (write-string "\"/>" out))
        (:stretch
         (destructuring-bind (x y w k glyph sy center) (cdr item)
           ;; The scale is about the origin, so the baseline is set where the stretched
           ;; ink center lands at Y: the unscaled center is CENTER above the baseline.
           (write-string "<text x=\"" out)
           (write-em (floor (* (+ x (floor w 2)) 1000) k) out)
           (write-string "\" y=\"" out)
           (write-em (floor (* (+ (floor (* (+ y base) 1000) sy) (msc center k)) 1000) k) out)
           (format out "\" transform=\"scale(1 ~d.~3,'0d)\"" (floor sy 1000) (mod sy 1000))
           (unless (= k 1000)
             (write-string " font-size=\"" out)
             (write-em k out)
             (write-string "\"" out))
           (write-string ">" out)
           (write-escaped glyph out)
           (write-string "</text>" out)))))
    (write-string "</svg>" out)))
