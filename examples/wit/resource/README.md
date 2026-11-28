# wit/resource -- a resource a component exports, and one that takes it

[`counters.lisp`](counters.lisp) implements the world `counters` of
[`wit/counters.wit`](wit/counters.wit): the interface `registry` declares the
resource `counter`, and the interface `report` takes one in a function of its own.
[`app.lisp`](app.lisp) uses both from another component.
[`reporter.lisp`](reporter.lisp) implements `report` a second time, in a component
of its own that imports `registry` (the world `reporter`).

A resource is implemented with ordinary functions, and the value its constructor
answers is the resource:

| WIT | Lisp |
|---|---|
| `constructor(name: string, start: u32)` | `(defun counter-new (name start) ...)`, answering any value: here a struct |
| `add: func(by: u32) -> u32` | `(defun counter-add (self by) ...)`, `self` that value |
| `merge: static func(a: counter, b: borrow<counter>) -> counter` | `(defun counter-merge (a b) ...)` |
| a handle is released | `(defun counter-drop (self) ...)`, optional |
| `list<borrow<counter>>`, `result<counter, string>` | a list of the values; the value |

The importing side holds handles: `app.lisp` passes them back, releases one with
`reg:counter-drop`, and gives one up where a function takes a `counter` owned
(`merge`'s first argument, `leader`'s list).

## Run it

On the interpreter and the JVM the functions of `counters.lisp` answer `app.lisp`
in process, a handle being the struct itself:

```bash
rontolisp app.lisp
# add      7
# read     (:NAME "apples" :VALUE 7)
# total    17
# summary  "pears is at 10"
# live     2
# merged   (:NAME "apples+pears" :VALUE 17)
# leader   "apples+pears is at 17"
# live     1
# live     0
# leader   "no counter to lead"
```

[`build.sh`](build.sh) builds the two components and plugs the exporting one into
the command, which then prints the same lines:

```bash
RONTOLISP=../../../target/rontolisp ./build.sh
```

It needs [`wac`](https://github.com/bytecodealliance/wac) (`cargo install wac-cli`)
and `wasmtime` 46+.

## A component that takes a resource it imports

`reporter.lisp` exports `report` and imports `registry`, so its `counter` is a
resource the component imports: a handle there is the integer
`rontolisp:wit-import` takes, as in `app.lisp`, and `summary` reads the counter
through `reg:counter-read`. A borrowed handle is lent for the call, and the export
releases it when `summary` has answered.

```lisp
(rontolisp:wit-import "wit/counters.wit"
                      :interface "example:counters/registry"
                      :package reg)

(defun summary (c)
  (let ((reading (reg:counter-read c)))
    (format nil "~a is at ~d" (getf reading :name) (getf reading :value))))

(rontolisp:wit-export "wit/counters.wit" :world reporter :reactor t)
```

The second half of `build.sh` wires three components with
[`composition.wac`](composition.wac): the reporter and the command both import
`registry` from the one `counters` instance, and the command's `report` comes from
the reporter. It prints the same lines again.

`rontolisp counters.lisp -o counters.wasm --component --emit-wit` writes the world
the component has: `registry` with its resource, and `report` naming it with
`use registry.{counter};`.
