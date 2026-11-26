(in-package :meta-hooks)

(defvar *seen-in-b* *current*)
(setf *current* :b)
(asdf:load-system "meta-demo/nested")
