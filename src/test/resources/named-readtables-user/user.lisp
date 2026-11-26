;; Loaded by NamedReadtablesE2eTest: the rest of this file reads with the readtable
;; it selects, on every backend.
(named-readtables:in-readtable nr-e2e)
(defparameter cl-user::*read* (list !foo '!(a b) (quote |Mixed|) 'Mixed))
