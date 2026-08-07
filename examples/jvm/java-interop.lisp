;; Building a Swing UI directly through the java interop package -- no bespoke
;; wrapper per widget. The full Swing API is reachable by reflection.

(defvar *frame* (java:new "javax.swing.JFrame" "java interop PoC"))
(defvar *label* (java:new "javax.swing.JLabel" "click count: 0"))
(defvar *button* (java:new "javax.swing.JButton" "Increment"))
(defvar *panel*
  (java:new "javax.swing.JPanel" (java:new "java.awt.BorderLayout" 12 12)))

(defvar *count* 0)

;; The ActionListener is a rontolisp lambda turned into a java interface via a
;; dynamic proxy. It is invoked as (lambda method-name event...) on every click.
(java:call *button* "addActionListener"
           (java:proxy "java.awt.event.ActionListener"
                       (lambda (method event)
                         (setq *count* (+ *count* 1))
                         (java:call *label* "setText"
                                    (concatenate 'string "click count: "
                                                 (princ-to-string *count*))))))

;; Center constants etc. are just static fields.
(java:call *panel* "add" *label* (java:field "java.awt.BorderLayout" "CENTER"))
(java:call *panel* "add" *button* (java:field "java.awt.BorderLayout" "SOUTH"))

(java:call *frame* "setContentPane" *panel*)
(java:call *frame* "setDefaultCloseOperation"
           (java:field "javax.swing.WindowConstants" "DISPOSE_ON_CLOSE"))
(java:call *frame* "setSize" 360 180)
(java:call *frame* "setLocationRelativeTo" nil)
(java:call *frame* "setVisible" t)

(print "java-interop window is open; click Increment")
