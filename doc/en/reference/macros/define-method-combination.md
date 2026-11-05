# define-method-combination

`(define-method-combination name [:operator op] [:identity-with-one-argument b] [:documentation d])`

`(define-method-combination name lambda-list (group...) [(:arguments parameter...)] [(:generic-function var)] declaration... [documentation] form...)`

Defines a method combination, which a [`defgeneric`](../special-forms/defgeneric.md) selects with `(:method-combination name argument...)`. Under one, any atoms in a [`defmethod`](../special-forms/defmethod.md) ahead of its lambda list are the method's qualifiers. Define the combination ahead of the generic.

The long form's forms compute the effective method form. They run with `lambda-list` bound to the unevaluated arguments of `:method-combination`, and with each group's variable bound to the applicable methods of that group, most specific first. A group is `(variable selector... [:order form] [:required form] [:description string])`: a method joins the first group it matches, a selector being a list of qualifiers to match (`*` for anything, as an element or as the tail), `*`, or the name of a predicate taking the qualifiers. `:order` evaluates to `:most-specific-first` or `:most-specific-last`, and a true `:required` signals where the generic is called with no method in the group. In the answer, `(call-method m (next...))` calls method `m` with its next methods, an element of which may be `(make-method form)`. `(:arguments parameter...)` binds each variable to a form evaluating to that argument of the generic, a `&rest` variable to the rest list's.

```lisp
(define-method-combination collecting (&optional (order :most-specific-first))
  ((around (:around))
   (primary (collecting) :order order :required t))
  (let ((form `(list ,@(mapcar (lambda (m) `(call-method ,m)) primary))))
    (if around
        `(call-method ,(first around) (,@(rest around) (make-method ,form)))
        form)))
(defgeneric parts (x) (:method-combination collecting :most-specific-last))
(defmethod parts collecting ((x number)) :number)
(defmethod parts collecting ((x integer)) :integer)
(defmethod parts :around ((x integer)) (list :around (call-next-method)))
(parts 1) ; => (:AROUND (:NUMBER :INTEGER))
```

The short form is the same over `(call-method m)` of every method whose qualifier is `name`, passed to the operator `op` (`name` by default). With a true `:identity-with-one-argument` a single method is called alone.

```lisp
(define-method-combination total :operator + :identity-with-one-argument t)
(defgeneric weight (x) (:method-combination total))
(defmethod weight total ((x number)) 1)
(defmethod weight total ((x integer)) 10)
(list (weight 5) (weight 1.5)) ; => (11 1)
```

The forms run as a generic's dispatcher is generated, once per argument shape it tells apart: while the program compiles on the compiled backends, so a function they call has to be defined at that point. A method object there answers `method-qualifiers` and nothing else. Methods added at run time with [`add-method`](../functions/add-method.md) do not join such a generic. Two methods of one group with the same specializers are accepted (SBCL signals where the generic is called).
