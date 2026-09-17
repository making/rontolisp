;; An identifier with no lowercase letter is spelled like a canonical name.
(defun |CAR| (|x|) (list 'mine |x|))
(print (|CAR| '(1 2)))
(print (let ((|LIST| 5)) (list |LIST| |LIST|)))
