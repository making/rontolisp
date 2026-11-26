;; A grovel file: read by the groveller, which writes a C program from it, compiles
;; and runs that, and loads the Lisp it prints.
(in-package :grovel-demo)

(include "errno.h" "time.h")

(constant (enoent "ENOENT"))

(constant (absent "GROVEL_DEMO_NEVER_DEFINED") :optional t)

(ctype time-t "time_t")

(cstruct timespec "struct timespec"
  (sec "tv_sec" :type time-t)
  (nsec "tv_nsec" :type :long))

(cenum (clock-id :base-type :int)
  ((:realtime "CLOCK_REALTIME")))
