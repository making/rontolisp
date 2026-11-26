(in-package :grovel-demo)

(defun report ()
  (list enoent
        (boundp 'absent)
        (cffi:foreign-type-size 'time-t)
        (cffi:foreign-type-size '(:struct timespec))
        (cffi:foreign-slot-offset '(:struct timespec) 'nsec)
        (cffi:foreign-enum-value 'clock-id :realtime)
        (wrapped-strlen "hello")
        (twice 21)))
