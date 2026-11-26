(defsystem #:cl-colors2
  :description "Simple color library for Common Lisp"
  :version     "0.7.0"
  :author      "Tamas K Papp, cage, Artyom Bologov (aartaka)"
  :maintainer  "cage"
  :bug-tracker "https://codeberg.org/cage/cl-colors2/issues"
  :license     "Boost Software License - Version 1.0"
  :serial t
  :components ((:file "package")
               (:file "colors")
               (:file "colornames-x11")
               (:file "colornames-svg")
               (:file "colornames-gdk")
               (:file "hexcolors")
               (:file "print"))
  :depends-on (:alexandria
               :cl-ppcre
               :parse-number)
  :in-order-to ((test-op (load-op cl-colors2/tests))))

(defsystem #:cl-colors2/tests
  :description "Unit tests for CL-COLORS2."
  :author "Tamas K Papp, cage"
  :license "Boost Software License - Version 1.0"
  :serial t
  :components ((:file "test"))
  :depends-on (:cl-colors2
               :clunit2))
