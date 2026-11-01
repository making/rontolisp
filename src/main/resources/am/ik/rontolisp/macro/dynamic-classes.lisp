;; Classes made at RUN time: a class metaobject a program instantiates from a
;; metaclass after the static class tables were built -- contextl's layer
;; contexts, (make-instance 'standard-layer-class :direct-superclasses (list a
;; b)). Such a class has no tag, layout or ancestor set of its own, so its
;; instances (allocate-instance, class-prototype) are %DYNAMIC-OBJECT%s
;; carrying the class metaobject and its class precedence list, and what a
;; static %obj-is tag list cannot answer consults that list: the dispatcher arm
;; generateDispatcher adds to every generic with a class specializer
;; (%dynamic-class-key, %dynamic-applicable, %dynamic-dispatch), class-of and
;; subtypep. The instance has no slots of its own.
;;
;; Injected only into programs carrying a :metaclass defclass and an
;; allocate-instance reference (LispMacroExpander gate); the interpreter
;; evaluates the forms when it first meets a class made at run time. Canonical
;; shape, like mop-protocol.lisp; metaobject slots are read by name.

;; Class metaobject -> its precedence list, most recent first.
(defvar %dynamic-cpls% nil)

(defun %dynamic-class-p (x)
  ;; A class metaobject find-class does not answer for its own name.
  (if (%obj-p x)
      (if (typep x 'class)
          (not (eq x (find-class (slot-value x 'name) nil)))
          nil)
      nil))

(defun %dynamic-cpl-tie (built candidates)
  ;; Of several candidates, the one with a direct subclass rightmost in the
  ;; list built so far (BUILT holds it most recent first) -- CLHS 4.3.5.
  (let ((pick nil))
    (dolist (c built)
      (if pick
          nil
          (dolist (s (slot-value c 'direct-superclasses))
            (if (and (null pick) (member s candidates :test #'eq))
                (setq pick s)
                nil))))
    (if pick pick (car candidates))))

(defun %dynamic-compute-cpl (class)
  ;; CLHS 4.3.5: the topological sort of the class and its superclasses under
  ;; every class's local precedence order.
  (let ((classes nil) (pending (list class)) (pairs nil) (built nil))
    (loop while pending
          do
            (let ((c (car pending)))
              (setq pending (cdr pending))
              (if (member c classes :test #'eq)
                  nil
                  (let ((prev c))
                    (setq classes (cons c classes))
                    (dolist (s (slot-value c 'direct-superclasses))
                      (setq pairs (cons (cons prev s) pairs))
                      (setq prev s)
                      (setq pending (append pending (list s))))))))
    (setq classes (reverse classes))
    (loop while classes
          do
            (let ((candidates
                   (remove-if (lambda (c) (find c pairs :key #'cdr :test #'eq))
                              classes)))
              (if (null candidates)
                  (error "No consistent class precedence list for ~S" class)
                  nil)
              (let ((next
                     (if (cdr candidates)
                         (%dynamic-cpl-tie built candidates)
                         (car candidates))))
                (setq built (cons next built))
                (setq classes (remove next classes :test #'eq))
                (setq pairs (remove-if (lambda (p) (eq (car p) next)) pairs)))))
    (reverse built)))

(defun %dynamic-cpl (class)
  (let ((hit (assoc class %dynamic-cpls% :test #'eq)))
    (if hit
        (cdr hit)
        (let ((cpl (%dynamic-compute-cpl class)))
          (setq %dynamic-cpls% (cons (cons class cpl) %dynamic-cpls%))
          cpl))))

(defun %dynamic-allocate (class)
  (%obj-new '|%class-%DYNAMIC-OBJECT%| class (%dynamic-cpl class)))

(defun %dynamic-subtypep (sub super)
  ;; subtypep where a class made at run time is involved: SUB is a subtype of
  ;; SUPER when SUPER is in SUB's precedence list; t and standard-object cover
  ;; every such class.
  (let ((sub (if (symbolp sub) (find-class sub) sub))
        (super (if (symbolp super) (find-class super) super)))
    (if (%dynamic-class-p sub)
        (if (member super (%dynamic-cpl sub) :test #'eq)
            t
            (if (member (slot-value super 'name) '(t standard-object)) t nil))
        (eq sub super))))

(defun %dynamic-typep (instance spec)
  ;; typep of a dynamic INSTANCE against a type name or class metaobject: whether
  ;; the class is in its precedence list; t, atom and standard-object hold for
  ;; every one, and no other type a class does not name does.
  (let ((class (if (symbolp spec) (find-class spec nil) spec)))
    (cond ((or (eq spec t) (eq spec 'atom)) t)
          ((null class) nil)
          ((member class (%obj-ref instance 1) :test #'eq) t)
          ((member (slot-value class 'name) '(t standard-object)) t)
          (t nil))))

(defun %dynamic-class-key (instance class)
  ;; The precedence key of a class specializer for a dynamic instance: 10000
  ;; plus the class's position in the instance's precedence list (below every
  ;; type specializer's key), nil when the class is not in it.
  (let ((position (position class (%obj-ref instance 1) :test #'eq)))
    (if position (+ 10000 position) nil)))

;; Built-in class name -> its static rank (LispMacroExpander.specializerRank),
;; set by the generic-function protocol, whose run-time methods may name one.
(defvar %class-key-ranks% nil)

(defun %instance-cpl (x)
  ;; The class precedence list of an instance's class, computed over the class
  ;; metaobjects; nil for a value that is no instance.
  (if (%obj-is x '|%class-%DYNAMIC-OBJECT%|)
      (%obj-ref x 1)
      (if (%obj-p x)
          (let ((class (class-of x)))
            (if (%obj-p class) (%dynamic-cpl class) nil))
          nil)))

(defun %class-key (instance class)
  ;; The precedence key of a class specializer for any argument, on the scale of
  ;; %dynamic-class-key: 10000 plus the class's position in the argument's class
  ;; precedence list, the static rank times 1000 for a built-in class the
  ;; argument is of (t: 1000000), nil when the class does not apply.
  (let* ((name (slot-value class 'name))
         (position
          (if (member name '(t standard-object structure-object))
              nil
              (let ((cpl (%instance-cpl instance)))
                (if cpl (position class cpl :test #'eq) nil)))))
    (cond (position (+ 10000 position))
          ((eq name t) 1000000)
          ((typep instance class)
           (let ((rank (assoc name %class-key-ranks% :test #'equal)))
             (* 1000 (if rank (cdr rank) 220))))
          (t nil))))

(defun %dynamic-applicable (entries)
  ;; ENTRIES holds one (qualifier function key...) per applicable method, nil
  ;; per inapplicable one. Nil unless a primary method applies -- the static
  ;; dispatch then answers, as for a call no class made at run time reaches.
  (let ((applicable (remove nil entries)) (primary nil))
    (dolist (e applicable) (if (null (car e)) (setq primary t) nil))
    (if primary applicable nil)))

(defun %dynamic-more-specific-p (a b)
  ;; Leftmost argument first; a smaller key is more specific.
  (let ((x (cdr (cdr a))) (y (cdr (cdr b))) (answer nil) (done nil))
    (loop while (and x (not done))
          do
            (cond ((< (car x) (car y))
                   (setq answer t)
                   (setq done t))
                  ((> (car x) (car y)) (setq done t))
                  (t
                   (setq x (cdr x))
                   (setq y (cdr y)))))
    answer))

(defun %dynamic-chain (functions base)
  ;; Each method function receives the rest of the chain as its next method,
  ;; BASE after the last one.
  (if (null functions)
      base
      (let ((fn (car functions)) (next (%dynamic-chain (cdr functions) base)))
        (lambda (&rest args) (apply fn next args)))))

(defun %dynamic-dispatch (entries args)
  ;; The standard method combination over the applicable ENTRIES, most
  ;; specific first (a stable sort keeps definition order between equals).
  (let ((sorted (stable-sort (copy-list entries) #'%dynamic-more-specific-p))
        (arounds nil)
        (befores nil)
        (primaries nil)
        (afters nil))
    (dolist (e (reverse sorted))
      (let ((q (car e)) (fn (car (cdr e))))
        (cond ((eq q :around) (setq arounds (cons fn arounds)))
              ((eq q :before) (setq befores (cons fn befores)))
              ((eq q :after) (setq afters (cons fn afters)))
              (t (setq primaries (cons fn primaries))))))
    (let* ((primary (%dynamic-chain primaries nil))
           (core
            (if (or befores afters)
                (lambda (&rest a)
                  (dolist (b befores) (apply b nil a))
                  (multiple-value-prog1 (apply primary a)
                    (dolist (f (reverse afters)) (apply f nil a))))
                primary)))
      (apply (if arounds (%dynamic-chain arounds core) core) args))))
