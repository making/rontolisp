(defparameter *unhooked*
  (list #+meta-demo-hooked :read-with #-meta-demo-hooked :read-without
        (hook-features-at-expansion)))
