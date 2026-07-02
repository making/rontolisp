# CLOS and `defstruct` (the object system)

**Status:** not implemented. Low priority — CLOS is a massive subsystem; `defstruct` is a lighter first step.

## What's missing

RontoLisp has no object system. The following CL operators are absent:

### Defstruct

| Operator | Purpose |
|----------|---------|
| `defstruct` | Define a record-like structure with constructors, accessors, predicates |
| `make-<struct>` | Constructor (generated) |
| `<struct>-<field>` | Accessor (generated) |
| `<struct>-p` | Predicate (generated) |
| `structurep` | Structure predicate |

### CLOS (Common Lisp Object System)

| Operator | Purpose |
|----------|---------|
| `defclass` | Define a class |
| `defmethod` | Define a method |
| `defgeneric` | Define a generic function |
| `make-instance` | Create an instance |
| `slot-value` | Access instance slot |
| `slot-boundp` | Check if slot is bound |
| `slot-makunbound` | Unbind slot |
| `shared-initialize` | Instance initialization |
| `the` (typed) | Type declaration |
| `change-class` | Change instance class |
| `class-name` | Class name |
| `find-class` | Find class by name |
| `ensure-class` | Find or create class |
| `slot-makunbound` | Unbind slot |
| `slot-makunbound` | Unbind slot |
| `standard-method-call` | (Not CL) |
| `call-next-method` | Call next most specific method |
| `compute-applicable-methods` | Compute applicable methods |
| `compute-applicable-methods-using-class` | Compute applicable methods |
| `method-specializers` | Method specializers |
| `generic-function-methods` | Generic function methods |
| `add-method` | Add method to generic |
| `remove-method` | Remove method from generic |
| `method-combination-error` | Method combination error |
| `standard-effective-method-computation` | Standard method combination |
| `eql-specializer` | EQL specializer |
| `make-method` | Create method |
| `initialize-instance` | After method |
| `reinitialize-instance` | After method |
| `update-instance-for-redefined-class` | After method |
| `update-instance-for-different-class` | After method |
| `update-instance-for-lambda-list-change` | After method |
| `direct-superclasses` | Class hierarchy |
| `direct-subclasses` | Class hierarchy |
| `direct-slot-definition-class` | Slot definition |
| `effective-slot-definition-class` | Slot definition |
| `initialize-instance` | After method |
| `instance-access` | (Not CL) |

### Implementation approach

**`defstruct`** (lightweight, high ROI):
A macro that generates:
- A constructor (`make-<struct>`)
- Accessors (`<struct>-<field>`)
- A predicate (`<struct>-p`)
- Internal representation: could be a vector, hash table, or cons-based record.

**CLOS** (deferred — massive scope):
- Requires a class hierarchy, method dispatch, and generic functions.
- JVM: could map to Java classes/interfaces but that defeats the single-class output model.
- WASM GC: struct types could work but are rigid (no runtime subclassing).
- Most likely: a pure-Lisp implementation (like SBCL's CLOS in Lisp).

### Related

- `[[39-condition-system]]` (condition types are CLOS classes)
- `[[35-type-system]]` (`typep` on class names)
- `[[31-lambda-list-extensions]]` (`defstruct` accessors use `&key`)
