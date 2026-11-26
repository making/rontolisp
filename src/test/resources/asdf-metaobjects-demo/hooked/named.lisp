(defparameter *named-hook*
  (list #+meta-demo-named-hook :read-with #-meta-demo-named-hook :read-without
        (hook-features-at-expansion)))
