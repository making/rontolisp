(defparameter *string-hook*
  (list #+meta-demo-tool-hooked :read-with #-meta-demo-tool-hooked :read-without
        (hook-features-at-expansion)))
