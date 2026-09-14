;;;; uiop/backward-driver -- the two backward-compatibility aliases that are real
;;;; here. coerce-pathname is the DEPRECATED alias of parse-unix-namestring
;;;; (.todo/357), and version-compatible-p is the ASDF 1-to-2.32 version check
;;;; written over parse-version and lexicographic<=. The other five members (the
;;;; configuration-directory search) stay not-implemented-error stubs: they need
;;;; uiop/configuration, which nothing implements yet. See .kb/uiop.md.

(defun uiop/backward-driver:coerce-pathname
    (%cp-name &key ((:type %cp-type)) ((:defaults %cp-defaults)))
  (uiop/pathname:parse-unix-namestring %cp-name
                                       :type %cp-type
                                       :defaults %cp-defaults))

;;; Is the provided version a compatible substitution for the required one? Same
;;; major number, and the provided minor sequence is lexicographically >= the
;;; required minor sequence (so a later minor is compatible).
(defun uiop/backward-driver:version-compatible-p (%vcp-provided %vcp-required)
  (let ((%vcp-x (uiop/version:parse-version %vcp-provided nil))
        (%vcp-y (uiop/version:parse-version %vcp-required nil)))
    (and %vcp-x %vcp-y (= (car %vcp-x) (car %vcp-y))
         (uiop/utility:lexicographic<= '< (cdr %vcp-y) (cdr %vcp-x)))))
