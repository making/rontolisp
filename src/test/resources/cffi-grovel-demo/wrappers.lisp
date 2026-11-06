;; A wrapper file: C functions compiled into the system's own library, with the
;; bindings to them.
(in-package :grovel-demo)

(include "string.h")

(defwrapper ("strlen" wrapped-strlen) ("size_t" :unsigned-long)
  (s ("const char*" :string)))

(defwrapper* ("grovel_demo_twice" twice) :int
  ((x :int))
  "return x * 2;")
