;;;; The page net/serve-html.lisp serves, embedded at READ time. This file is
;;;; (load)ed by serve-html.lisp -- never compiled or run on its own -- so the
;;;; #. datum below runs with this file's load context: *load-truename* names
;;;; this source's truename on every backend (the interpreter binds it
;;;; around the load; the compile paths bracket the spliced file), and the
;;;; default page resolves beside it. Pick the file with HTML_FILE at BUILD time (a
;;;; working-directory-relative path, the historical spelling); the artifact
;;;; keeps NO runtime filesystem/environment capability.

(defparameter *html*
  ;; Read at READ time: on the compile path the file is slurped on the build
  ;; host, so the page travels inside the artifact as a literal.
  #.(uiop:read-file-string
     (or (uiop:getenv "HTML_FILE")
         (merge-pathnames "page.html"
                          (or *compile-file-pathname* *load-truename*)))))
