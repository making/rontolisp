(defvar *win* (java:static "AppKitSpike" "window" "rontolisp REPL GUI" 520 220))
(defvar *lbl* (java:static "AppKitSpike" "label" *win* "no clicks yet" 40 130 440 30))
(defvar *n* 0)
(defvar *btn*
  (java:static "AppKitSpike" "button" *win* "Click me" 40 50 160 40
               (lambda (method)
                 (setq *n* (+ *n* 1))
                 (java:static "AppKitSpike" "setText" *lbl*
                              (format nil "clicked ~a time(s) -- handler written in Lisp" *n*)))))
(format t "windowNumber = ~a~%" (java:static "AppKitSpike" "windowNumber" *win*))
(sleep 2)
(java:static "AppKitSpike" "click" *btn*)
(format t "clicks = ~a~%" *n*)
(sleep 40)
