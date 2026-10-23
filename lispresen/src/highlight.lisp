;;;; highlight.lisp -- the tokenizers that color code: one for Lisp, Scheme and Clojure,
;;;; a line rule for a console transcript, and one driven by a table of rules for the other
;;;; languages a talk shows next to Lisp.
;;;;
;;;; Classes: hl-c comments, hl-s strings and characters, hl-k keywords, hl-d the head of a
;;;; Lisp form that defines or binds, hl-n numbers and constants.
;;;;
;;;; Lisp: hl-k is a keyword (:name), hl-d the head of a form such as (def..., let, lambda,
;;;; if, ...), hl-n also nil, t, true, false, #t, #f. [], {} and #{} are delimiters like
;;;; (). The player's editor re-highlights with the same rules (HIGHLIGHT in player.lisp),
;;;; so an edited block looks like the deck's.
;;;;
;;;; A console transcript is highlighted by line: the $ of a command line as hl-k, every
;;;; other line, the output, as hl-c.
;;;;
;;;; Every other language is a LANG-RULES: its comment and string delimiters, character
;;;; literals, keywords (hl-k) and constants (hl-n); in JSON and YAML a key is hl-k. It is
;;;; no grammar, only enough to set comments, strings and keywords apart on a slide.

(in-package :lispresen)

(defparameter *form-heads*
  '("lambda" "fn" "let" "let*" "letrec" "letfn" "flet" "labels" "if" "when"
    "unless" "cond" "case" "and" "or" "loop" "do" "dolist" "dotimes" "progn"
    "begin" "quote" "setf" "setq" "set!" "recur" "try" "catch" "handler-case"
    "unwind-protect" "block" "return-from" "multiple-value-bind"
    "destructuring-bind" "ns" "in-package" "require"))

(defparameter *constants*
  '("nil" "t" "true" "false" "#t" "#f" "#true" "#false"))

(defun delimiter-p (c)
  (or (blank-code-p (char-code c)) (find c "()[]{}\";'`,")))

(defun number-token-p (tok)
  (let ((c (char tok 0)))
    (or (digit-char-p c)
        (and (> (length tok) 1) (find c "+-.") (digit-char-p (char tok 1))))))

(defun token-end (text j)
  (let ((n (length text)))
    (loop while (and (< j n) (not (delimiter-p (char text j)))) do (incf j))
    j))

(defun token-class (text i j)
  (let ((tok (string-downcase (subseq text i j))))
    (cond ((char= (char tok 0) #\:) "k")
          ((member tok *constants* :test #'string=) "n")
          ((number-token-p tok) "n")
          ((and (> i 0) (char= (char text (1- i)) #\()
                (or (and (> (length tok) 3) (string= tok "def" :end1 3))
                    (member tok *form-heads* :test #'string=)))
           "d"))))

(defun lisp-segments (text)
  (let ((n (length text)) (i 0) (segments nil))
    (flet ((emit (class end)
             (push (list i end class) segments)
             (setf i end)))
      (loop while (< i n)
            do
              (let ((c (char text i))
                    (next (and (< (1+ i) n) (char text (1+ i)))))
                (cond
                 ((char= c #\;)
                  (emit "c" (or (position #\Newline text :start i) n)))
                 ((and (char= c #\#) (eql next #\|))
                  (let ((end (search "|#" text :start2 (+ i 2))))
                    (emit "c" (if end (+ end 2) n))))
                 ((char= c #\")
                  (let ((j (1+ i)))
                    (loop while (< j n)
                          do
                            (let ((d (char text j)))
                              (cond ((char= d #\\) (incf j 2))
                                    ((char= d #\")
                                     (incf j)
                                     (return))
                                    (t (incf j)))))
                    (emit "s" (min j n))))
                 ((and (char= c #\#) (eql next #\\))
                  (emit "s" (token-end text (min n (+ i 3)))))
                 ((char= c #\\) (emit "s" (token-end text (min n (+ i 2)))))
                 ((delimiter-p c) (emit nil (1+ i)))
                 (t (let ((j (token-end text i)))
                      (emit (token-class text i j) j)))))))
    (nreverse segments)))

(defun console-segments (text)
  (let ((n (length text)) (start 0) (segments nil))
    (loop
      (let ((end (or (position #\Newline text :start start) n)))
        (cond ((prompt-line-p (subseq text start end))
               (push (list start (1+ start) "k") segments)
               (when (< (1+ start) end)
                 (push (list (1+ start) end nil) segments)))
              ((< start end) (push (list start end "c") segments)))
        (when (= end n) (return))
        (push (list end (1+ end) nil) segments)
        (setf start (1+ end))))
    (nreverse segments)))

;;; A language's rules. STRINGS are (OPEN CLOSE ESCAPE MULTILINE) lists, tried in order:
;;; ESCAPE is :backslash (\ escapes the next character), :double (the closing delimiter
;;; written twice stands for itself) or nil; a string without MULTILINE that is not closed
;;; ends at the end of its line, so a stray quote colors one line at most. CHARS is true
;;; for 'x' character literals; a ' that starts none is punctuation (a Rust lifetime).
;;; ANCHORED is :comments when a line comment opens only at the start of a word (shell:
;;; ${#a}), t when a string does too (YAML: it's). KEYWORDS and CONSTANTS are lists of
;;; words, compared ignoring case with FOLD-CASE. WORD holds the characters a word takes
;;; besides letters and digits. With KEYS a string followed by : is hl-k, and so is a word
;;; followed by : and a blank.
(defstruct lang-rules
  names line-comments block-comments strings chars anchored keywords constants
  fold-case (word "_") keys)

(defun words (s)
  "The words of S, a string of words between blanks."
  (let ((words nil) (i 0) (n (length s)))
    (loop
      (loop while (and (< i n) (blank-code-p (char-code (char s i)))) do (incf i))
      (when (= i n) (return (nreverse words)))
      (let ((j i))
        (loop while (and (< j n) (not (blank-code-p (char-code (char s j)))))
              do (incf j))
        (push (subseq s i j) words)
        (setf i j)))))

(defparameter *c-comments*
  '(:line-comments ("//") :block-comments (("/*" . "*/"))))

(defparameter *c-keywords*
  (words "auto break case char const continue default do double else enum extern float
for goto if inline int long register restrict return short signed sizeof static struct
switch typedef union unsigned void volatile while #include #define #undef #if #ifdef
#ifndef #elif #else #endif #pragma"))

(defparameter *js-keywords*
  (words "async await break case catch class const continue debugger default delete do
else export extends finally for from function get if import in instanceof let new of
return set static super switch this throw try typeof var void while with yield"))

(defparameter *js-strings*
  '(("\"" "\"" :backslash nil) ("'" "'" :backslash nil) ("`" "`" :backslash t)))

(defun rules (names &rest options)
  (apply #'make-lang-rules :names names options))

(defparameter *languages*
  (list
   (apply #'rules '("java")
          :strings '(("\"\"\"" "\"\"\"" :backslash t) ("\"" "\"" :backslash nil))
          :chars t :word "_$"
          :keywords (words "abstract assert boolean break byte case catch char class
const continue default do double else enum extends final finally float for goto if
implements import instanceof int interface long native new non-sealed package permits
private protected public record return sealed short static strictfp super switch
synchronized this throw throws transient try var void volatile while yield")
          :constants (words "true false null")
          *c-comments*)
   (apply #'rules '("kotlin" "kt")
          :strings '(("\"\"\"" "\"\"\"" nil t) ("\"" "\"" :backslash nil))
          :chars t
          :keywords (words "abstract annotation as break by catch class companion const
constructor continue crossinline data do else enum external final finally for fun get if
import in init inline inner interface internal is lateinit noinline object open operator
out override package private protected public reified return sealed set super suspend
this throw try typealias val var vararg when where while")
          :constants (words "true false null")
          *c-comments*)
   (apply #'rules '("javascript" "js")
          :strings *js-strings* :word "_$" :keywords *js-keywords*
          :constants (words "true false null undefined NaN Infinity")
          *c-comments*)
   (apply #'rules '("typescript" "ts")
          :strings *js-strings* :word "_$"
          :keywords (append *js-keywords*
                            (words "abstract any as boolean declare enum implements
interface keyof namespace never number private protected public readonly satisfies
string type unknown"))
          :constants (words "true false null undefined NaN Infinity")
          *c-comments*)
   (apply #'rules '("c")
          :strings '(("\"" "\"" :backslash nil)) :chars t :word "_#"
          :keywords *c-keywords* :constants (words "true false NULL")
          *c-comments*)
   (apply #'rules '("cpp" "c++")
          :strings '(("\"" "\"" :backslash nil)) :chars t :word "_#"
          :keywords (append *c-keywords*
                            (words "bool catch class co_await co_return co_yield concept
const_cast constexpr delete dynamic_cast explicit final friend mutable namespace new
noexcept operator override private protected public reinterpret_cast static_cast
template this throw try typename using virtual"))
          :constants (words "true false NULL nullptr")
          *c-comments*)
   (apply #'rules '("go")
          :strings '(("\"" "\"" :backslash nil) ("`" "`" nil t)) :chars t
          :keywords (words "break case chan const continue default defer else
fallthrough for func go goto if import interface map package range return select struct
switch type var")
          :constants (words "true false nil iota")
          *c-comments*)
   (apply #'rules '("rust" "rs")
          :strings '(("\"" "\"" :backslash t)) :chars t
          :keywords (words "as async await break const continue crate dyn else enum
extern fn for if impl in let loop match mod move mut pub ref return self Self static
struct super trait type unsafe use where while")
          :constants (words "true false")
          *c-comments*)
   (rules '("python" "py")
          :line-comments '("#")
          :strings '(("\"\"\"" "\"\"\"" :backslash t) ("'''" "'''" :backslash t)
                     ("\"" "\"" :backslash nil) ("'" "'" :backslash nil))
          :keywords (words "and as assert async await break case class continue def del
elif else except finally for from global if import in is lambda match nonlocal not or
pass raise return try while with yield")
          :constants (words "True False None"))
   (rules '("bash" "sh" "shell" "zsh")
          :line-comments '("#") :anchored :comments
          :strings '(("\"" "\"" :backslash nil) ("'" "'" nil nil))
          :word "_-"
          :keywords (words "case do done elif else esac export fi for function if in
local return select then until while"))
   (rules '("sql")
          :line-comments '("--") :block-comments '(("/*" . "*/"))
          :strings '(("'" "'" :double nil)) :fold-case t
          :keywords (words "add all alter and as asc begin between by case check commit
constraint create cross default delete desc distinct drop else end exists foreign from
full group having in index inner insert intersect into is join key left like limit not
offset on or order outer primary references returning right rollback select set table
then union unique update values view when where with")
          :constants (words "null true false"))
   (rules '("json")
          :strings '(("\"" "\"" :backslash nil)) :keys t
          :constants (words "true false null"))
   (rules '("yaml" "yml")
          :line-comments '("#") :anchored t
          :strings '(("\"" "\"" :backslash nil) ("'" "'" :double nil))
          :word "_-." :keys t
          :constants (words "true false null yes no"))))

(defun language-rules (name)
  (find-if (lambda (rules) (member name (lang-rules-names rules) :test #'string=))
           *languages*))

(defun text-at-p (part text i)
  (let ((end (+ i (length part))))
    (and (<= end (length text)) (string= part text :start2 i :end2 end))))

(defun word-start-p (text i flow)
  "True when I is at the start of TEXT or after a blank, or with FLOW after a [ { or , of
a YAML flow collection."
  (or (= i 0)
      (let ((c (char text (1- i))))
        (or (blank-code-p (char-code c)) (and flow (find c "[{,"))))))

(defun word-char-p (c extra)
  (let ((code (char-code c)))
    (or (<= 48 code 57) (<= 65 code 90) (<= 97 code 122)
        (and (> code 127) (not (blank-code-p code)))
        (find c extra))))

(defun string-end (text i spec)
  "The end of the string that SPEC opens at I in TEXT."
  (destructuring-bind (open close escape multiline) spec
    (let ((n (length text)) (j (+ i (length open))))
      (loop
        (cond ((>= j n) (return n))
              ((and (eq escape :backslash) (char= (char text j) #\\))
               (setf j (min n (+ j 2))))
              ((text-at-p close text j)
               (incf j (length close))
               (unless (and (eq escape :double) (text-at-p close text j))
                 (return j))
               (incf j (length close)))
              ((and (not multiline) (char= (char text j) #\Newline)) (return j))
              (t (incf j)))))))

(defun char-literal-end (text i)
  "The end of the character literal whose ' is at I in TEXT, or nil when none starts
there."
  (let ((n (length text)))
    (cond ((and (< (1+ i) n) (char= (char text (1+ i)) #\\))
           (let ((close (and (< (+ i 3) n)
                             (position #\' text :start (+ i 3) :end (min n (+ i 12))))))
             (and close (not (find #\Newline text :start i :end close))
                  (1+ close))))
          ((and (< (+ i 2) n) (char= (char text (+ i 2)) #\')
                (not (find (char text (1+ i)) '(#\' #\Newline))))
           (+ i 3)))))

(defun string-key-p (text j)
  "True when a : follows J in TEXT past spaces."
  (let ((n (length text)))
    (loop while (and (< j n) (find (char text j) '(#\Space #\Tab))) do (incf j))
    (and (< j n) (char= (char text j) #\:))))

(defun word-key-p (text j)
  "True when a : follows J in TEXT, then a blank or the end."
  (let ((n (length text)))
    (and (< j n) (char= (char text j) #\:)
         (or (= (1+ j) n) (blank-code-p (char-code (char text (1+ j))))))))

(defun word-class (word rules)
  (let ((key (if (lang-rules-fold-case rules) (string-downcase word) word)))
    (cond ((member key (lang-rules-keywords rules) :test #'string=) "k")
          ((member key (lang-rules-constants rules) :test #'string=) "n")
          ((digit-char-p (char word 0)) "n"))))

(defun rule-segments (text rules)
  (let ((n (length text))
        (i 0)
        (segments nil)
        (anchored (lang-rules-anchored rules))
        (keys (lang-rules-keys rules))
        (extra (lang-rules-word rules)))
    (flet ((emit (class end)
             (push (list i end class) segments)
             (setf i end)))
      (loop while (< i n)
            do
              (let ((c (char text i))
                    (block (find-if (lambda (d) (text-at-p (car d) text i))
                                    (lang-rules-block-comments rules)))
                    (str (and (or (not (eq anchored t)) (word-start-p text i t))
                              (find-if (lambda (s) (text-at-p (first s) text i))
                                       (lang-rules-strings rules)))))
                (cond
                 ((and (or (not anchored) (word-start-p text i nil))
                       (find-if (lambda (d) (text-at-p d text i))
                                (lang-rules-line-comments rules)))
                  (emit "c" (or (position #\Newline text :start i) n)))
                 (block
                  (let ((end (search (cdr block) text
                                     :start2 (+ i (length (car block))))))
                    (emit "c" (if end (+ end (length (cdr block))) n))))
                 (str
                  (let ((end (string-end text i str)))
                    (emit (if (and keys (string-key-p text end)) "k" "s") end)))
                 ((and (lang-rules-chars rules) (char= c #\')
                       (char-literal-end text i))
                  (emit "s" (char-literal-end text i)))
                 ((word-char-p c extra)
                  ;; A number takes a . before a digit: 3.14, not the .. of a range.
                  (let ((j (1+ i)) (number (digit-char-p c)))
                    (loop while
                            (and (< j n)
                                 (or (word-char-p (char text j) extra)
                                     (and number (char= (char text j) #\.)
                                          (< (1+ j) n)
                                          (digit-char-p (char text (1+ j))))))
                          do (incf j))
                    (emit (if (and keys (word-key-p text j))
                              "k"
                              (word-class (subseq text i j) rules))
                          j)))
                 (t (emit nil (1+ i)))))))
    (nreverse segments)))

(defun highlight-segments (text lang)
  "TEXT, code in the language LANG, cut into (START END CLASS) segments that cover it in
order, each with its class (nil for none): a token, a delimiter or blank character, or a
console line. One segment without a class when LANG has no rules."
  (let* ((name (and lang (string-downcase lang)))
         (rules (and name (language-rules name))))
    (cond ((member name *runnable-langs* :test #'equal) (lisp-segments text))
          ((equal name "console") (console-segments text))
          (rules (rule-segments text rules))
          (t (list (list 0 (length text) nil))))))

(defun write-highlighted (text lang out)
  "TEXT, code in the language LANG, as HTML with a span around each highlighted token."
  (loop for (start end class) in (highlight-segments text lang)
        do
          (when class (format out "<span class=\"hl-~a\">" class))
          (write-escaped (subseq text start end) out)
          (when class (write-string "</span>" out))))
