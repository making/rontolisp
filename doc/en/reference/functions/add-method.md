# add-method

`(ensure-generic-function name &key lambda-list generic-function-class method-class argument-precedence-order)`
`(add-method generic-function method)`
`(remove-method generic-function method)`
`(find-method generic-function qualifiers specializers &optional (errorp t))`
`(method-qualifiers method)`
`(compute-applicable-methods generic-function arguments)`

Generic function and method **metaobjects** (AMOP 5.3–5.4). `ensure-generic-function` answers the generic function metaobject of a name: a funcallable instance of `standard-generic-function`, or of the class `:generic-function-class` names or the name's [`defgeneric`](../special-forms/defgeneric.md) names with `(:generic-function-class c)`. It is made on the first request and kept, calling it calls the generic function, and from then on `fdefinition` of a computed name answers it. A name no generic function has gets a new one, which a program calls through its name like any function defined at run time.

`add-method` adds a method metaobject to a generic function and answers the generic function; a method with the same qualifiers and specializers is replaced. A method is a `standard-method` (or an instance of the generic's method class) made with `make-instance` and the initargs `:qualifiers`, `:specializers` (a list of class metaobjects, `(closer-mop:intern-eql-specializer object)` and `(find-class t)`, one per required parameter), `:lambda-list` and `:function`: a function of the argument list and the list of next methods, which it may call through their `closer-mop:method-function`. From then on the method takes part in every call like a [`defmethod`](../special-forms/defmethod.md)'s, by its specializers, in the standard method combination. `remove-method` takes it out again, and `find-method` finds one by its qualifiers and specializers (class names, class metaobjects, `(eql object)`). `add-method` and `remove-method` are generic functions, so a program can define methods on them.

A `defmethod` on a generic function whose `defgeneric` names a `:generic-function-class` or a `:method-class` reaches `add-method` with a method metaobject of the method it defined, so a user `add-method` method sees it (contextl clears its layer caches from one). closer-mop's `generic-function-name`, `generic-function-methods`, `generic-function-method-class`, `generic-function-lambda-list`, `generic-function-argument-precedence-order`, `method-specializers`, `method-lambda-list`, `method-function`, `method-generic-function` and `eql-specializer-object` read the metaobjects. closer-mop's `ensure-method` makes and adds a method from a lambda expression, whose body may call `call-next-method` and `next-method-p`; its function is made with [`compile`](compile.md), so on the compilation path a lambda expression the program builds while it defines a class or a method is compiled code, and any other one is evaluated.

```lisp
(defclass animal () ((name :initarg :name :reader animal-name)))
(defclass dog (animal) ())
(defgeneric speak (x))
(defmethod speak ((x animal)) (list :animal (animal-name x)))
(add-method (ensure-generic-function 'speak)
            (make-instance 'standard-method
                           :qualifiers () :lambda-list '(x)
                           :specializers (list (find-class 'dog))
                           :function (lambda (args next-methods)
                                       (declare (ignore next-methods))
                                       (list :dog (animal-name (first args))))))
(list (speak (make-instance 'dog :name "rex"))
      (speak (make-instance 'animal :name "cat"))) ; => ((:DOG "rex") (:ANIMAL "cat"))
```

`compute-applicable-methods` answers the methods of a generic function that apply to a list of arguments as method metaobjects, most specific first and the same object for a method each time: the methods a `defmethod` defined as well as those `add-method` added. It takes a generic function's metaobject or its `#'name`; a function that is no generic function answers `nil`. It is a generic function too, so a program can define methods on it, but a call of a generic function does not consult it: such a method changes only what a caller of `compute-applicable-methods` sees. A compiled program has these metaobjects where it names `compute-applicable-methods`.

```lisp
(defgeneric size (x))
(defmethod size ((x number)) :number)
(defmethod size :around ((x integer)) (call-next-method))
(defmethod size (x) :anything)
(mapcar #'method-qualifiers (compute-applicable-methods #'size (list 1))) ; => ((:AROUND) NIL NIL)
(mapcar (lambda (method) (class-name (first (closer-mop:method-specializers method))))
        (compute-applicable-methods #'size (list "s"))) ; => (T)
```

## Backend support

All four backends behave alike. On the compiled backends `#'name` and a literal `(fdefinition 'name)` of a generic function the program defines answer its compiled function, not the metaobject. Not supported: `remove-method` of a method a `defmethod` defined takes out its metaobject but not the method, and a generic function with a short-form method combination ignores methods added at run time; `generic-function-methods` lists the methods a `defmethod` defined only for a generic function whose `defgeneric` names its metaobject classes.
