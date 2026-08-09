;;; The runtime `format` renderer: the ONE implementation of the directive set
;;; that runs when the control string is not a compile-time literal.
;;;
;;; `(format destination "literal" args...)` is lowered by LispMacroExpander's
;;; static expansion (string pieces, no renderer); every other shape -- a
;;; computed control string, `#'format` taken as a value, `~?`, a condition's
;;; format-control slot, and any literal control the static expansion declines
;;; -- funnels into `%fmt-render` here, so the supported directive set is the
;;; same everywhere instead of a "simple directives only" fallback.
;;;
;;; Written as many small defuns on purpose: one emitted WASM function body must
;;; not grow without bound (.kb/wasm-function-body-size.md).
;;;
;;; The argument cursor is an (all . index) pair rather than a shrinking list, so
;;; `~*` can move backwards and `~n@*` can jump absolutely. Renderers return a
;;; state LIST rather than multiple values: multiple values do not cross a
;;; function boundary on every backend (.kb/multiple-values.md).

;;; ---------------------------------------------------------------- primitives

(defun %fmt-cat (a b) (concatenate 'string a b))

(defun %fmt-repeat (s n)
  (let ((out "") (k 0))
    (while (< k n)
      (setq out (%fmt-cat out s))
      (setq k (+ k 1)))
    out))

(defun %fmt-digitp (c) (and (char>= c #\0) (char<= c #\9)))

(defun %fmt-parse-int (s)
  (let ((i 0) (n (length s)) (neg nil) (acc 0))
    (if (and (> n 0) (char= (char s 0) #\-))
        (progn
          (setq neg t)
          (setq i 1)))
    (while (< i n)
      (setq acc (+ (* acc 10) (- (char-code (char s i)) 48)))
      (setq i (+ i 1)))
    (if neg (- 0 acc) acc)))

(defun %fmt-pow10 (n)
  (let ((r 1) (k 0))
    (while (< k n)
      (setq r (* r 10))
      (setq k (+ k 1)))
    r))

;;; ------------------------------------------------------------ prefix params
;;; A parameter is an integer, a character ('c or a runtime v), or nil for an
;;; omitted slot -- the same three shapes the static parser records.

(defun %fmt-nth (params k) (if (< k (length params)) (nth k params) nil))

(defun %fmt-int (params k default)
  (let ((p (%fmt-nth params k)))
    (cond ((null p) default)
          ((characterp p) (char-code p))
          ((integerp p) p)
          (t default))))

(defun %fmt-pad-char (params k default)
  (let ((p (%fmt-nth params k)))
    (cond ((null p) default)
          ((characterp p) (string p))
          ((stringp p) p)
          (t (princ-to-string p)))))

(defun %fmt-param-start-p (c)
  (or (%fmt-digitp c) (char= c #\-) (char= c #\') (char= c #\v) (char= c #\V)
      (char= c #\#) (char= c #\,)))

;;; (list params pos i)
(defun %fmt-params (ctrl pos end all i)
  (if (or (>= pos end) (not (%fmt-param-start-p (char ctrl pos))))
      (list nil pos i)
      (%fmt-params-loop ctrl pos end all i nil)))

(defun %fmt-params-loop (ctrl pos end all i params)
  (let ((p pos) (idx i) (acc params) (more t))
    (while more
      (let ((st (%fmt-param ctrl p end all idx)))
        (setq acc (append acc (list (nth 0 st))))
        (setq p (nth 1 st))
        (setq idx (nth 2 st))
        (if (and (< p end) (char= (char ctrl p) #\,))
            (setq p (+ p 1))
            (setq more nil))))
    (list acc p idx)))

;;; (list value pos i)
(defun %fmt-param (ctrl pos end all i)
  (let ((c (if (< pos end) (char ctrl pos) #\,)))
    (cond
     ((char= c #\')
      (list (if (< (+ pos 1) end) (char ctrl (+ pos 1)) #\Space) (+ pos 2) i))
     ((or (char= c #\v) (char= c #\V)) (list (nth i all) (+ pos 1) (+ i 1)))
     ((char= c #\#) (list (- (length all) i) (+ pos 1) i))
     ((or (%fmt-digitp c) (char= c #\-))
      (let ((start pos) (p (+ pos 1)))
        (while (and (< p end) (%fmt-digitp (char ctrl p))) (setq p (+ p 1)))
        (list (%fmt-parse-int (subseq ctrl start p)) p i)))
     (t (list nil pos i)))))

;;; --------------------------------------------------------------- the tokens
;;; (list pos params colon at directive-char i) for the directive whose ~ is at
;;; (- pos 1). A control string ending in ~ yields a literal tilde rather than an
;;; error: the renderer must never signal on data (a condition report renders
;;; through it).

(defun %fmt-token (ctrl pos end all i)
  (let* ((st (%fmt-params ctrl pos end all i))
         (p (nth 1 st))
         (colon nil)
         (at nil))
    (while (and (< p end)
                (or (char= (char ctrl p) #\:) (char= (char ctrl p) #\@)))
      (if (char= (char ctrl p) #\:) (setq colon t) (setq at t))
      (setq p (+ p 1)))
    (if (>= p end)
        (list p (nth 0 st) colon at #\~ (nth 2 st))
        (list (+ p 1) (nth 0 st) colon at (char ctrl p) (nth 2 st)))))

;;; (list pos directive-char colon) -- the token shape only, consuming no
;;; arguments. Used by the scanners that locate a composite directive's end.
(defun %fmt-shape (ctrl pos end)
  (let ((tk (%fmt-token ctrl pos end nil 0)))
    (if (char= (nth 4 tk) #\/)
        (list (+ (%fmt-slash-end ctrl (nth 0 tk) end) 1) (nth 4 tk) (nth 2 tk))
        (list (nth 0 tk) (nth 4 tk) (nth 2 tk)))))

;;; The closing directive an opening one expects, or nil when the directive opens
;;; nothing. A closer of a DIFFERENT kind is ordinary text to the scanners below,
;;; so a stray ~] inside a ~{ ... ~} body cannot end the iteration.
(defun %fmt-closer-of (c)
  (cond ((char= c #\() #\))
        ((char= c #\[) #\])
        ((char= c #\{) #\})
        ((char= c #\<) #\>)
        (t nil)))

;;; The index of the / that closes a ~/name/ directive whose name starts at pos,
;;; or end when it is unterminated. The name is arbitrary text, so every scanner
;;; has to step OVER it -- a ~ or a closing directive inside a function name is
;;; not a directive.
(defun %fmt-slash-end (ctrl pos end)
  (let ((p pos) (res -1))
    (while (and (< p end) (< res 0))
      (if (char= (char ctrl p) #\/) (setq res p) (setq p (+ p 1))))
    (if (< res 0) end res)))

;;; The index of the ~ that begins the matching close directive of the composite
;;; whose body starts at pos, or end when it is unterminated. A nested composite
;;; is skipped whole, so nesting needs no depth counter.
(defun %fmt-match (ctrl pos end close)
  (let ((p pos) (res -1))
    (while (and (< p end) (< res 0))
      (if (char= (char ctrl p) #\~)
          (let* ((sh (%fmt-shape ctrl (+ p 1) end))
                 (np (nth 0 sh))
                 (d (nth 1 sh))
                 (inner (%fmt-closer-of d)))
            (cond ((char= d close) (setq res p))
             ((null inner) (setq p np))
             (t (setq p (%fmt-after ctrl (%fmt-match ctrl np end inner) end)))))
          (setq p (+ p 1))))
    (if (< res 0) end res)))

;;; The position just past the closing directive that starts at pos.
(defun %fmt-after (ctrl pos end)
  (if (>= pos end) end (nth 0 (%fmt-shape ctrl (+ pos 1) end))))

;;; (list clauses close-pos); a clause is (start end default-p), default-p being
;;; the : of the ~:; separator that introduced it. Used for ~[ ... ~] and, with
;;; closer #\>, for the ~< ... ~> section list.
(defun %fmt-clauses (ctrl pos end) (%fmt-clauses-until ctrl pos end #\]))

(defun %fmt-clauses-until (ctrl pos end closer)
  (let ((segs nil) (start pos) (p pos) (defnext nil) (close end) (done nil))
    (while (and (< p end) (not done))
      (if (char= (char ctrl p) #\~)
          (let* ((sh (%fmt-shape ctrl (+ p 1) end))
                 (np (nth 0 sh))
                 (d (nth 1 sh))
                 (inner (%fmt-closer-of d)))
            (cond
             ((char= d closer)
              (setq segs (append segs (list (list start p defnext))))
              (setq close p)
              (setq done t))
             ((char= d #\;)
              (setq segs (append segs (list (list start p defnext))))
              (setq defnext (nth 2 sh))
              (setq start np)
              (setq p np))
             ((null inner) (setq p np))
             (t (setq p (%fmt-after ctrl (%fmt-match ctrl np end inner) end)))))
          (setq p (+ p 1))))
    (if (not done) (setq segs (append segs (list (list start end defnext)))))
    (list segs close)))

;;; ------------------------------------------------------------- field output

;;; CL field padding: minpad copies of the pad string first, then whole colinc
;;; chunks until the field is at least mincol wide. With the defaults (colinc 1,
;;; minpad 0) this is the static expansion's pad loop.
(defun %fmt-pad (s mincol colinc minpad pad left)
  (let ((r s) (k 0) (inc (if (< colinc 1) 1 colinc)))
    (while (< k minpad)
      (setq r (if left (%fmt-cat pad r) (%fmt-cat r pad)))
      (setq k (+ k 1)))
    (while (< (length r) mincol)
      (let ((chunk (%fmt-repeat pad inc)))
        (setq r (if left (%fmt-cat chunk r) (%fmt-cat r chunk)))))
    r))

(defun %fmt-group (digits ch interval)
  (let ((rem digits) (out "") (iv (if (< interval 1) 3 interval)))
    (while (> (length rem) iv)
      (setq out (%fmt-cat (%fmt-cat ch (subseq rem (- (length rem) iv))) out))
      (setq rem (subseq rem 0 (- (length rem) iv))))
    (%fmt-cat rem out)))

;;; ~D. A non-number argument prints as if by ~A, as Common Lisp specifies (and
;;; as the condition-report path needs: a report must never signal).
(defun %fmt-dec (n colon comma interval at)
  (if (not (numberp n))
      (princ-to-string n)
      (let* ((str (princ-to-string n))
             (neg (< n 0))
             (dig (if neg (subseq str 1) str))
             (grouped (if colon (%fmt-group dig comma interval) dig)))
        (%fmt-cat (if neg "-" (if at "+" "")) grouped))))

;;; ~X / ~O / ~B / ~R: digits 0-9 then uppercase A-Z (48 = #\0, 55 = #\A - 10).
(defun %fmt-radix (n base colon comma interval at)
  (if (not (integerp n))
      (princ-to-string n)
      (let* ((neg (< n 0)) (m (if neg (- 0 n) n)) (s ""))
        (while (> m 0)
          (let ((d (mod m base)))
            (setq s
             (%fmt-cat (string (code-char (if (< d 10) (+ 48 d) (+ 55 d)))) s)))
          (setq m (truncate (/ m base))))
        (let* ((g (if (string= s "") "0" s))
               (grouped (if colon (%fmt-group g comma interval) g)))
          (%fmt-cat (if neg "-" (if at "+" "")) grouped)))))

;;; ~F / ~$: one call to the %fixed-decimal primitive, which is also what the
;;; static path expands the directive to -- one renderer, so the two paths cannot
;;; disagree about a digit. A non-number argument prints as if by ~A (CLHS
;;; 22.3.3.1); the primitive itself takes numbers only.
(defun %fmt-fixed (x places nbefore at)
  (if (not (numberp x))
      (princ-to-string x)
      (%fixed-decimal x places (if (null nbefore) 1 nbefore) at)))

(defun %fmt-strip-zeros (s)
  (let ((g s))
    (while (and (> (length g) 1) (string= (subseq g (- (length g) 1)) "0"))
      (setq g (subseq g 0 (- (length g) 1))))
    g))

;;; ~E: the magnitude is normalized into [1, 10) by a divide/multiply loop that
;;; tracks the decimal exponent, then rounded through integer scaling so the
;;; digits are identical on every backend.
(defun %fmt-exp (x places strip at expdigits marker)
  (if (not (numberp x))
      (princ-to-string x)
      (let* ((v (* x 1.0)) (neg (< v 0.0)) (a (if neg (- 0.0 v) v)))
        (if (= a 0.0)
            (%fmt-exp-zero places strip at expdigits marker)
            (%fmt-exp-value a neg places strip at expdigits marker)))))

(defun %fmt-exp-zero (places strip at expdigits marker)
  (let ((mant
         (cond ((= places 0) "0")
               (strip "0.0")
               (t (%fmt-cat "0." (%fmt-repeat "0" places)))))
        (ed (if (> expdigits 1) expdigits 1)))
    (%fmt-cat (if at "+" "")
              (%fmt-cat mant
                        (%fmt-cat (string marker)
                                  (%fmt-cat "+" (%fmt-repeat "0" ed)))))))

(defun %fmt-exp-value (a neg places strip at expdigits marker)
  (let ((m a) (ee 0))
    (while (>= m 10.0)
      (setq m (/ m 10.0))
      (setq ee (+ ee 1)))
    (while (< m 1.0)
      (setq m (* m 10.0))
      (setq ee (- ee 1)))
    (let* ((pd (%fmt-pow10 places))
           (sc (round (* m (* pd 1.0))))
           (ovf (>= sc (* pd 10)))
           (sc2 (if ovf pd sc))
           (eef (if ovf (+ ee 1) ee))
           (s (princ-to-string sc2))
           (ip (subseq s 0 1))
           (fr0 (subseq s 1))
           (fr (if strip (%fmt-strip-zeros fr0) fr0))
           (eneg (< eef 0))
           (eabs0 (princ-to-string (if eneg (- 0 eef) eef)))
           (eabs
            (if (> expdigits 0) (%fmt-pad eabs0 expdigits 1 0 "0" t) eabs0))
           (mant (if (= places 0) ip (%fmt-cat (%fmt-cat ip ".") fr))))
      (%fmt-cat (if neg "-" (if at "+" ""))
                (%fmt-cat mant
                          (%fmt-cat (string marker)
                                    (%fmt-cat (if eneg "-" "+") eabs)))))))

;;; ~G: the plain float representation inside [0.1, 1e16) (and for zero), the ~E
;;; default form outside it.
(defun %fmt-general (x at)
  (if (not (numberp x))
      (princ-to-string x)
      (let* ((v (* x 1.0)) (a (if (< v 0.0) (- 0.0 v) v)))
        (if (or (= a 0.0) (and (>= a 0.1) (< a 1.0e16)))
            (if at
                (%fmt-cat (if (< v 0.0) "" "+") (princ-to-string v))
                (princ-to-string v))
            (%fmt-exp v 6 t at 0 #\e)))))

;;; ~@(: downcase, then upcase the first alphabetic character.
(defun %fmt-cap-first (str)
  (let* ((s (string-downcase str)) (n (length s)) (i 0))
    (while (and (< i n) (not (alpha-char-p (char s i)))) (setq i (+ i 1)))
    (if (< i n)
        (%fmt-cat (subseq s 0 i)
         (%fmt-cat (string-upcase (subseq s i (+ i 1))) (subseq s (+ i 1))))
        s)))

;;; The column the accumulated output ends at (for ~& and ~T). A renderer builds
;;; a string, so the column is the text since the last newline -- an empty
;;; accumulator counts as the start of a line.
(defun %fmt-column (out)
  (let ((i (length out)) (col 0) (done nil))
    (while (and (> i 0) (not done))
      (if (char= (char out (- i 1)) #\Newline)
          (setq done t)
          (progn
            (setq col (+ col 1))
            (setq i (- i 1)))))
    col))

(defun %fmt-fresh (out n)
  (let ((first (if (= (%fmt-column out) 0) "" (string #\Newline))))
    (%fmt-cat out (%fmt-cat first (%fmt-repeat (string #\Newline) (- n 1))))))

(defun %fmt-tab (out colnum colinc relative)
  (let* ((col (%fmt-column out))
         (inc (if (< colinc 1) 1 colinc))
         (target
          (if relative
              (let ((base (+ col colnum)))
                (if (= (mod base inc) 0)
                    base
                    (* (+ (truncate (/ base inc)) 1) inc)))
              (if (< col colnum)
                  colnum
                  (+ colnum (* inc (+ (truncate (/ (- col colnum) inc)) 1)))))))
    (%fmt-cat out (%fmt-repeat " " (- target col)))))

;;; ------------------------------------------------------------- the renderer

;;; The entry point: control string x argument list -> string.
(defun %fmt-render (ctrl args)
  (if (stringp ctrl)
      (nth 0 (%fmt-run ctrl 0 (length ctrl) args 0))
      (princ-to-string ctrl)))

;;; Renders ctrl[start, end) with the argument cursor at i.
;;; Returns (list out i escaped).
(defun %fmt-run (ctrl start end all i)
  (let ((st (list "" start i nil)))
    (while (and (< (nth 1 st) end) (null (nth 3 st)))
      (setq st (%fmt-step ctrl end all st)))
    (list (nth 0 st) (nth 2 st) (nth 3 st))))

;;; One literal character or one directive. State is (out pos i escaped).
(defun %fmt-step (ctrl end all st)
  (let ((out (nth 0 st)) (pos (nth 1 st)) (i (nth 2 st)))
    (if (char= (char ctrl pos) #\~)
        (%fmt-directive ctrl end all out (+ pos 1) i)
        (list (%fmt-cat out (string (char ctrl pos))) (+ pos 1) i nil))))

(defun %fmt-directive (ctrl end all out pos i)
  (let* ((tk (%fmt-token ctrl pos end all i))
         (np (nth 0 tk))
         (params (nth 1 tk))
         (colon (nth 2 tk))
         (at (nth 3 tk))
         (raw (nth 4 tk))
         (d (char-downcase raw))
         (idx (nth 5 tk)))
    (cond ((%fmt-value-directive-p d)
           (%fmt-value ctrl end all out np idx params colon at d))
          ((char= d #\() (%fmt-case ctrl end all out np idx colon at))
          ((char= d #\[)
           (%fmt-cond-directive ctrl end all out np idx params colon at))
          ((char= d #\{) (%fmt-iterate ctrl end all out np idx params colon at))
          ((char= d #\<) (%fmt-block ctrl end all out np idx at))
          ((char= d #\/) (%fmt-user-function ctrl end all out np idx colon at))
          (t (%fmt-control ctrl end all out np idx params colon at d raw)))))

(defun %fmt-value-directive-p (d)
  (or (char= d #\a) (char= d #\s) (char= d #\d) (char= d #\x) (char= d #\o)
      (char= d #\b) (char= d #\r) (char= d #\c) (char= d #\f) (char= d #\e)
      (char= d #\g) (char= d #\$) (char= d #\p) (char= d #\?)))

;;; Directives that consume no argument (and the ones that only move the cursor).
(defun %fmt-control (ctrl end all out pos i params colon at d raw)
  (cond ((char= d #\~)
         (list (%fmt-cat out (%fmt-repeat "~" (%fmt-int params 0 1))) pos i
               nil))
        ((char= d #\%)
         (list
          (%fmt-cat out (%fmt-repeat (string #\Newline) (%fmt-int params 0 1)))
          pos i nil))
        ((char= d #\&) (list (%fmt-fresh out (%fmt-int params 0 1)) pos i nil))
        ((char= d #\|)
         (list
          (%fmt-cat out (%fmt-repeat (string #\Page) (%fmt-int params 0 1))) pos
          i nil))
        ((char= d #\Newline)
         (list (if at (%fmt-cat out (string #\Newline)) out)
               (%fmt-skip-indent ctrl end pos colon) i nil))
        ((char= d #\t)
         (list (%fmt-tab out (%fmt-int params 0 1) (%fmt-int params 1 1) at) pos
               i nil))
        ((char= d #\*) (list out pos (%fmt-jump all i params colon at) nil))
        ((char= d #\^) (list out pos i (%fmt-escape all i params)))
        ;; Conditional newline. Only the MANDATORY kind (~:@_) breaks a line: the
        ;; three others need the stream's current column to decide, and no backend
        ;; tracks one (.kb/pretty-printer.md). ~i (indent) is inert for the same
        ;; reason. Both obey *print-pretty*, like pprint-newline.
        ((char= d #\_)
         (list (if (and colon at *print-pretty*)
                   (%fmt-cat out (string #\Newline))
                   out) pos i nil))
        ((char= d #\i) (list out pos i nil))
        (t (list (%fmt-cat out (%fmt-cat "~" (string raw))) pos i nil))))

;;; ------------------------------------------------------------ logical block

;;; ~<...~> is JUSTIFICATION and ~<...~:> a LOGICAL BLOCK; the closing directive
;;; decides which. What separates them in CL -- padding a justification out to
;;; :mincol, wrapping a logical block at the right margin -- both need the
;;; stream's current column, and no backend tracks one (.kb/pretty-printer.md),
;;; so neither happens here: the TEXT is exactly what a wide enough line holds.
;;; The SECTIONS still differ, and that is not cosmetic:
;;;   - justification: every ~; section is a segment consuming arguments in turn;
;;;   - logical block: with 2+ sections the first is the prefix (a ~@; separator
;;;     makes it a per-line prefix, which without line breaks is the same text)
;;;     and, with 3, the last is the suffix. Both are literal text and consume no
;;;     argument.
;;; A logical block without @ takes ONE argument -- a LIST -- and renders its
;;; body against that list as the whole argument list; with @ the body continues
;;; on the enclosing arguments. esrap's context report is the plain form:
;;; (format s "~2@T~<~@;~A~:>" (list line)) prints the line, not the list.
(defun %fmt-block (ctrl end all out pos i at)
  (let* ((cl (%fmt-clauses-until ctrl pos end #\>))
         (segs (nth 0 cl))
         (close (nth 1 cl))
         (after (%fmt-after ctrl close end))
         (blockp (nth 2 (%fmt-shape ctrl (+ close 1) end))))
    (if blockp
        (%fmt-logical-block ctrl all out segs i after at)
        (%fmt-justify ctrl all out segs i after))))

;;; Justification: the segments share one argument cursor, left to right.
(defun %fmt-justify (ctrl all out segs i after)
  (let ((acc out) (cur i) (rest segs))
    (while rest
      (let ((r (%fmt-run ctrl (nth 0 (car rest)) (nth 1 (car rest)) all cur)))
        (setq acc (%fmt-cat acc (nth 0 r)))
        (setq cur (nth 1 r))
        (setq rest (cdr rest))))
    (list acc after cur nil)))

(defun %fmt-logical-block (ctrl all out segs i after at)
  (let* ((n (length segs))
         (body (if (> n 1) (nth 1 segs) (nth 0 segs)))
         (pre (if (> n 1) (nth 0 segs) nil))
         (suf (if (> n 2) (nth 2 segs) nil))
         ;; Without @ the block's arguments are the elements of ONE argument.
         (items (if at all (nth i all)))
         (start (if at i 0))
         (r (%fmt-run ctrl (nth 0 body) (nth 1 body) items start))
         (acc out))
    (if pre
        (setq acc
              (%fmt-cat acc
               (nth 0 (%fmt-run ctrl (nth 0 pre) (nth 1 pre) items start)))))
    (setq acc (%fmt-cat acc (nth 0 r)))
    (if suf
        (setq acc
              (%fmt-cat acc
               (nth 0 (%fmt-run ctrl (nth 0 suf) (nth 1 suf) items start)))))
    (list acc after (if at (nth 1 r) (+ i 1)) nil)))

;;; The ~/name/ arm -- %fmt-user-function -- is NOT here: it is the one part of
;;; the renderer that resolves a function out of a name built at run time, so it
;;; is injected separately (format-render-slash.lisp, or its stub) by
;;; macro/FormatRenderer. The scanners above still recognize the directive; only
;;; the call is elsewhere.

;;; ~<newline>: the newline is swallowed; the default also swallows the leading
;;; whitespace of the next line, ~@ keeps the newline, ~: keeps the whitespace.
(defun %fmt-skip-indent (ctrl end pos colon)
  (let ((p pos))
    (if (not colon)
        (while (and (< p end)
                (or (char= (char ctrl p) #\Space) (char= (char ctrl p) #\Tab)))
          (setq p (+ p 1))))
    p))

(defun %fmt-jump (all i params colon at)
  (let ((n (%fmt-int params 0 (if at 0 1))))
    (cond (at n) (colon (let ((k (- i n))) (if (< k 0) 0 k))) (t (+ i n)))))

;;; ~^ fires when the current argument list is exhausted; ~n^ when n is zero and
;;; ~n,m^ when n and m are equal.
(defun %fmt-escape (all i params)
  (cond ((null params) (if (>= i (length all)) t nil))
        ((null (%fmt-nth params 1)) (if (= (%fmt-int params 0 0) 0) t nil))
        (t (if (= (%fmt-int params 0 0) (%fmt-int params 1 0)) t nil))))

;;; ---------------------------------------------------------- value directives

(defun %fmt-value (ctrl end all out pos i params colon at d)
  (cond ((char= d #\?) (%fmt-recursive ctrl end all out pos i at))
        ((char= d #\p) (%fmt-plural out pos all i colon at))
        (t (list (%fmt-cat out (%fmt-field (nth i all) params colon at d)) pos
                 (+ i 1) nil))))

;;; ~? / ~@?: the next argument is a control string. Plain ~? takes its arguments
;;; from the following argument (a list); ~@? takes them from the remaining
;;; arguments of the current level and consumes what it uses.
(defun %fmt-recursive (ctrl end all out pos i at)
  (let ((inner (nth i all)))
    (if at
        (let ((r (%fmt-run inner 0 (length inner) all (+ i 1))))
          (list (%fmt-cat out (nth 0 r)) pos (nth 1 r) nil))
        (list (%fmt-cat out (%fmt-render inner (nth (+ i 1) all))) pos (+ i 2)
              nil))))

;;; ~P: ~:P re-uses the preceding argument instead of consuming a new one.
(defun %fmt-plural (out pos all i colon at)
  (let* ((k (if colon (- i 1) i))
         (v (nth (if (< k 0) 0 k) all))
         (one (and (numberp v) (= v 1)))
         (s (if at (if one "y" "ies") (if one "" "s"))))
    (list (%fmt-cat out s) pos (if colon i (+ i 1)) nil)))

;;; One padded field. The directive-specific rendering happens in %fmt-body; the
;;; width/pad parameters live at different indices per directive.
(defun %fmt-field (v params colon at d)
  (cond
   ((or (char= d #\a) (char= d #\s)) (%fmt-field-aesthetic v params colon at d))
   ((char= d #\c) (%fmt-char-directive v colon at))
   ((char= d #\f) (%fmt-field-fixed v params at))
   ((char= d #\e) (%fmt-field-exp v params at))
   ((char= d #\g) (%fmt-general v at))
   ((char= d #\$) (%fmt-field-money v params at))
   ((char= d #\r) (%fmt-field-radix v params colon at))
   ((char= d #\d) (%fmt-field-decimal v params colon at))
   (t (%fmt-field-integer v params colon at d))))

;;; ~mincol,colinc,minpad,padchar A / S -- padded on the right (left with @).
(defun %fmt-field-aesthetic (v params colon at d)
  (let* ((base0 (if (char= d #\s) (prin1-to-string v) (princ-to-string v)))
         (base (if (and colon (null v)) "()" base0)))
    (%fmt-pad base (%fmt-int params 0 0) (%fmt-int params 1 1)
              (%fmt-int params 2 0) (%fmt-pad-char params 3 " ") at)))

;;; ~mincol,padchar,commachar,comma-interval D -- numbers pad on the left.
(defun %fmt-field-decimal (v params colon at)
  (%fmt-pad
   (%fmt-dec v colon (%fmt-pad-char params 2 ",") (%fmt-int params 3 3) at)
   (%fmt-int params 0 0) 1 0 (%fmt-pad-char params 1 " ") t))

(defun %fmt-field-integer (v params colon at d)
  (let ((base (cond ((char= d #\x) 16) ((char= d #\o) 8) (t 2))))
    (%fmt-pad (%fmt-radix v base colon (%fmt-pad-char params 2 ",")
                          (%fmt-int params 3 3) at) (%fmt-int params 0 0) 1 0
              (%fmt-pad-char params 1 " ") t)))

;;; ~radix,mincol,padchar,commachar,comma-interval R. Without a radix parameter
;;; Common Lisp spells the number in English; rontolisp prints the decimal digits
;;; instead (see the doc's format limitations).
(defun %fmt-field-radix (v params colon at)
  (let ((base (%fmt-int params 0 10)))
    (%fmt-pad (%fmt-radix v base colon (%fmt-pad-char params 3 ",")
                          (%fmt-int params 4 3) at) (%fmt-int params 1 0) 1 0
              (%fmt-pad-char params 2 " ") t)))

;;; ~w,d,k,overflowchar,padchar F
(defun %fmt-field-fixed (v params at)
  (let* ((k (%fmt-int params 2 0))
         (scaled (if (or (= k 0) (not (numberp v))) v (* v (expt 10.0 k))))
         (d (%fmt-nth params 1))
         (base
          (if (null d)
              (princ-to-string scaled)
              (%fmt-fixed scaled (%fmt-int params 1 0) nil at)))
         (padded
          (%fmt-pad base (%fmt-int params 0 0) 1 0 (%fmt-pad-char params 4 " ")
                    t)))
    (%fmt-overflow padded params 0 3)))

;;; ~w,d,e,k,overflowchar,padchar,exponentchar E. The scale factor k is fixed at
;;; 1 (the default), as in the static expansion.
(defun %fmt-field-exp (v params at)
  (let* ((d (%fmt-nth params 1))
         (places (if (null d) 6 (%fmt-int params 1 6)))
         (base
          (%fmt-exp v places (null d) at (%fmt-int params 2 0)
                    (%fmt-marker params 6)))
         (padded
          (%fmt-pad base (%fmt-int params 0 0) 1 0 (%fmt-pad-char params 5 " ")
                    t)))
    (%fmt-overflow padded params 0 4)))

(defun %fmt-marker (params k)
  (let ((p (%fmt-nth params k))) (if (characterp p) p #\e)))

;;; ~d,n,w,padchar $
(defun %fmt-field-money (v params at)
  (let* ((base (%fmt-fixed v (%fmt-int params 0 2) (%fmt-int params 1 1) at)))
    (%fmt-pad base (%fmt-int params 2 0) 1 0 (%fmt-pad-char params 3 " ") t)))

;;; A field wider than w collapses to w copies of the overflow character.
(defun %fmt-overflow (s params width-idx ovf-idx)
  (let ((ovf (%fmt-nth params ovf-idx)) (w (%fmt-nth params width-idx)))
    (if (and (characterp ovf) (integerp w) (> (length s) w))
        (%fmt-repeat (string ovf) w)
        s)))

;;; ~C: the glyph, ~@C the #\ reader syntax, ~:C the character name for
;;; non-graphic characters.
(defun %fmt-char-directive (v colon at)
  (cond (at (prin1-to-string v))
   (colon (let ((s (prin1-to-string v))) (if (> (length s) 2) (subseq s 2) s)))
   (t (princ-to-string v))))

;;; ------------------------------------------------------------- composites

;;; ~(...~): case conversion of the processed body.
(defun %fmt-case (ctrl end all out pos i colon at)
  (let* ((close (%fmt-match ctrl pos end #\)))
         (r (%fmt-run ctrl pos close all i))
         (body (nth 0 r))
         (conv
          (cond ((and colon at) (string-upcase body))
                (colon (string-capitalize body))
                (at (%fmt-cap-first body))
                (t (string-downcase body)))))
    (list (%fmt-cat out conv) (%fmt-after ctrl close end) (nth 1 r) (nth 2 r))))

;;; ~[...~]: ~@[ processes its clause only for a true argument (which it leaves
;;; for the clause to consume), ~:[ selects false/true, otherwise the argument
;;; (or the ~n[ / ~#[ parameter) selects a clause by index.
(defun %fmt-cond-directive (ctrl end all out pos i params colon at)
  (let* ((cl (%fmt-clauses ctrl pos end))
         (segs (nth 0 cl))
         (after (%fmt-after ctrl (nth 1 cl) end)))
    (cond (at (%fmt-cond-at ctrl all out after segs i))
          (colon (%fmt-cond-colon ctrl all out after segs i))
          (t (%fmt-cond-index ctrl all out after segs i params)))))

(defun %fmt-cond-at (ctrl all out after segs i)
  (if (null (nth i all))
      (list out after (+ i 1) nil)
      (%fmt-clause ctrl all out after (nth 0 segs) i)))

(defun %fmt-cond-colon (ctrl all out after segs i)
  (%fmt-clause ctrl all out after (nth (if (nth i all) 1 0) segs) (+ i 1)))

(defun %fmt-cond-index (ctrl all out after segs i params)
  (let* ((given (%fmt-nth params 0))
         (sel (if (null given) (nth i all) given))
         (ni (if (null given) (+ i 1) i)))
    (%fmt-clause ctrl all out after (%fmt-select segs sel) ni)))

(defun %fmt-select (segs k)
  (if (and (integerp k) (>= k 0) (< k (length segs)))
      (nth k segs)
      (%fmt-default segs)))

(defun %fmt-default (segs)
  (let ((rest segs) (found nil))
    (while (consp rest)
      (if (nth 2 (car rest)) (setq found (car rest)))
      (setq rest (cdr rest)))
    found))

(defun %fmt-clause (ctrl all out after seg i)
  (if (null seg)
      (list out after i nil)
      (let ((r (%fmt-run ctrl (nth 0 seg) (nth 1 seg) all i)))
        (list (%fmt-cat out (nth 0 r)) after (nth 1 r) (nth 2 r)))))

;;; ~{...~}: the four iteration shapes. An empty body takes the control string
;;; from the next argument, ~n{ caps the passes, and ~:} runs the body at least
;;; once. A ~^ inside the body ends the iteration without propagating.
(defun %fmt-iterate (ctrl end all out pos i params colon at)
  (let* ((close (%fmt-match ctrl pos end #\}))
         (sh (%fmt-shape ctrl (+ close 1) end))
         (force (nth 2 sh))
         (after (nth 0 sh))
         (empty (= pos close))
         (body (if empty (nth i all) ctrl))
         (bstart (if empty 0 pos))
         (bend (if empty (length body) close))
         (bi (if empty (+ i 1) i))
         (maxp (%fmt-nth params 0))
         (maxn (if (integerp maxp) maxp -1)))
    (if at
        (%fmt-iterate-args body bstart bend all out after bi maxn force colon)
        (%fmt-iterate-list body bstart bend (nth bi all) out after (+ bi 1) maxn
                           force colon))))

;;; ~{ over one list argument (~:{ over a list of sublists).
(defun %fmt-iterate-list (ctrl start end items out after i maxn force sublists)
  (let ((acc out) (cur 0) (passes 0) (go t))
    (while go
      (if (and (>= cur (length items)) (not (and force (= passes 0))))
          (setq go nil)
          (if (and (>= maxn 0) (>= passes maxn))
              (setq go nil)
              (let* ((r
                      (if sublists
                          (%fmt-run ctrl start end (nth cur items) 0)
                          (%fmt-run ctrl start end items cur)))
                     (next (if sublists (+ cur 1) (nth 1 r))))
                (setq acc (%fmt-cat acc (nth 0 r)))
                (setq passes (+ passes 1))
                (if (or (nth 2 r) (<= next cur) (>= next (length items)))
                    (setq go nil))
                (setq cur next)))))
    (list acc after i nil)))

;;; ~@{ over the remaining arguments (~:@{ treating each as a sublist).
(defun %fmt-iterate-args (ctrl start end all out after i maxn force sublists)
  (let ((acc out) (cur i) (passes 0) (go t))
    (while go
      (if (and (>= cur (length all)) (not (and force (= passes 0))))
          (setq go nil)
          (if (and (>= maxn 0) (>= passes maxn))
              (setq go nil)
              (let* ((r
                      (if sublists
                          (%fmt-run ctrl start end (nth cur all) 0)
                          (%fmt-run ctrl start end all cur)))
                     (next (if sublists (+ cur 1) (nth 1 r))))
                (setq acc (%fmt-cat acc (nth 0 r)))
                (setq passes (+ passes 1))
                (if (or (nth 2 r) (<= next cur) (>= next (length all)))
                    (setq go nil))
                (setq cur next)))))
    (list acc after cur nil)))
