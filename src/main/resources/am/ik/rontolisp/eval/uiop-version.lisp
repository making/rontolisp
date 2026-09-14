;;;; uiop/version -- version comparison and the deprecation conditions.
;;;;
;;;; The whole sub-package, ported from upstream's version.lisp, keeping only the
;;;; names the inventory lists. The five deprecation condition classes are real,
;;;; and with-deprecation -- a built-in Java expansion in LispMacroExpander, since
;;;; a uiop macro cannot live in a resource -- signals the class its version pair
;;;; selects. See .kb/uiop.md.

;;; The contract version. Pinned to the release this port targets (uiop 3.3.7),
;;;; so a library that version-gates on *uiop-version* gets the answer that
;;;; matches the API it will find. It is a CONTRACT, not a claim of completeness.
(defparameter uiop/version:*uiop-version* "3.3.7")

;;; Version strings: a list of natural numbers separated by dots. parse-version
;;; keeps the &optional on-error shape upstream gives it -- it CALLS the handler
;;; with a format string for a malformed version rather than signalling, so
;;; version< on garbage stays non-signalling (on-error nil answers nil).
(defun uiop/version:unparse-version (%uv-version-list)
  (format nil "~{~D~^.~}" %uv-version-list))

(defun uiop/version:parse-version (%pv-version-string &optional %pv-on-error)
  (block nil
    (unless (stringp %pv-version-string)
      (uiop/utility:call-function %pv-on-error "~S: ~S is not a string"
                                  'uiop/version:parse-version
                                  %pv-version-string)
      (return))
    (let ((%pv-len (length %pv-version-string)))
      (unless (and (plusp %pv-len)
                   (digit-char-p (char %pv-version-string (1- %pv-len)))
                   (loop :for %pv-i
                           :from 0
                           :below %pv-len
                         :for %pv-c
                           :across %pv-version-string
                         :always
                           (or (digit-char-p %pv-c)
                               (and (eql %pv-c #\.) (plusp %pv-i)
                                    (not
                                     (eql (char %pv-version-string (1- %pv-i))
                                          #\.))))))
        (uiop/utility:call-function %pv-on-error
         "~S: ~S doesn't follow asdf version numbering convention"
         'uiop/version:parse-version %pv-version-string)
        (return)))
    (let* ((%pv-version-list
            (mapcar #'parse-integer
             (uiop/utility:split-string %pv-version-string :separator ".")))
           (%pv-normalized (uiop/version:unparse-version %pv-version-list)))
      (unless (equal %pv-version-string %pv-normalized)
        (uiop/utility:call-function %pv-on-error "~S: ~S contains leading zeros"
                                    'uiop/version:parse-version
                                    %pv-version-string))
      %pv-version-list)))

(defun uiop/version:next-version (%nv-version)
  (when %nv-version
    (let ((%nv-list (uiop/version:parse-version %nv-version)))
      (incf (car (last %nv-list)))
      (uiop/version:unparse-version %nv-list))))

;;; The comparison table, written over uiop/utility's lexicographic< /
;;; lexicographic<= (.todo/354). version< on malformed input answers nil
;;; (parse-version with on-error nil returns nil, and lexicographic< over nil is
;;; nil), so the comparisons never signal.
(defun uiop/version:version< (%v<1 %v<2)
  (let ((%v1 (uiop/version:parse-version %v<1 nil))
        (%v2 (uiop/version:parse-version %v<2 nil)))
    (uiop/utility:lexicographic< '< %v1 %v2)))

(defun uiop/version:version<= (%v<=1 %v<=2)
  (not (uiop/version:version< %v<=2 %v<=1)))

(defun uiop/version:version= (%v=1 %v=2)
  (and (uiop/version:version<= %v=1 %v=2) (uiop/version:version<= %v=2 %v=1)))

;;; The deprecation family: five condition classes in a hierarchy plus
;;; version-deprecation, which maps a version pair to the current level
;;; (:style-warning / :warning / :error / :delete). with-deprecation (a built-in
;;; Java expansion) signals the class its level selects. The name slot is
;;; inherited by all four subclasses; deprecated-function-name reads it.
(define-condition uiop/version:deprecated-function-condition (condition)
  ((name :initarg :name)))

(defun uiop/version:deprecated-function-name (%dfn-c) (slot-value %dfn-c 'name))

(define-condition uiop/version:deprecated-function-style-warning
    (uiop/version:deprecated-function-condition style-warning)
  ())

(define-condition uiop/version:deprecated-function-warning
    (uiop/version:deprecated-function-condition warning)
  ())

(define-condition uiop/version:deprecated-function-error
    (uiop/version:deprecated-function-condition error)
  ())

(define-condition uiop/version:deprecated-function-should-be-deleted
    (uiop/version:deprecated-function-condition error)
  ())

;;; Maps a version string to the highest deprecation level whose start version
;;; is older than the given one. Each start defaults to the NEXT-VERSION of the
;;; immediate lower level, so passing only the style-warning start chains the
;;; rest. The `and` guards make a nil start (no declared level) skip that check
;;; rather than asking version<= to compare against nil.
(defun uiop/version:version-deprecation (%vd-version &key
                                                     ((:style-warning
                                                       %vd-style-warning) nil)
                                                     ((:warning %vd-warning))
                                                     ((:error %vd-error))
                                                     ((:delete %vd-delete)))
  (let ((%vd-warning
         (or %vd-warning (uiop/version:next-version %vd-style-warning)))
        (%vd-error (or %vd-error (uiop/version:next-version %vd-warning)))
        (%vd-delete (or %vd-delete (uiop/version:next-version %vd-error))))
    (cond
     ((and %vd-delete (uiop/version:version<= %vd-delete %vd-version)) :delete)
     ((and %vd-error (uiop/version:version<= %vd-error %vd-version)) :error)
     ((and %vd-warning (uiop/version:version<= %vd-warning %vd-version))
      :warning)
     ((and %vd-style-warning
           (uiop/version:version<= %vd-style-warning %vd-version))
      :style-warning))))
