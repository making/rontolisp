# wit/shapes -- records, variants and lists across a component's exports

[`shapes.lisp`](shapes.lisp) implements the world `shapes` of
[`wit/shapes.wit`](wit/shapes.wit): an interface of functions over records, an
enum, flags, a variant, lists, an option, a tuple and a result, and one function
of the world's own. [`app.lisp`](app.lisp) calls that interface from another component.
No function names a boundary type; the WIT states them, and each value arrives as
the Lisp value its WIT type maps to:

| WIT | Lisp |
|---|---|
| `record point { x: s32, y: s32 }` | `(:x 1 :y 2)` |
| `enum color { red, green, blue }` | `:green` |
| `flags style { filled, dashed, bold }` | `(:filled :bold)` |
| `variant shape { circle(f64), rect(point), dot }` | `(:circle . 2.0d0)`, `(:rect :x 3 :y 4)`, `:dot` |
| `list<point>`, `tuple<point, point>` | a list |
| `option<string>` | the string, or `nil` |
| `result<f64, string>` | the float; the error arm is a `rontolisp:wit-error` signalled with the string |

## Run it

On the interpreter and the JVM the functions of `shapes.lisp` answer `app.lisp` in
process:

```bash
rontolisp app.lisp
# flip     (:X 2 :Y 1)
# squares  ((:X 1 :Y 1) (:X 2 :Y 4) (:X 3 :Y 9))
# bounds   ((:X 1 :Y 1) (:X 3 :Y 9))
# bounds   NIL
# area     12.0
# area     12.0
# area     "a dot has no area"
# caption  "a filled bold green dot called origin"
# caption  "a red circle"
# embolden (:DASHED :BOLD)
```

As components, a host calls an export of `shapes.wasm` directly:

```bash
rontolisp shapes.lisp -o shapes.wasm --component
wasmtime run --invoke 'flip({x: 1, y: 2})' shapes.wasm
# {x: 2, y: 1}
wasmtime run --invoke 'area(dot)' shapes.wasm
# err("a dot has no area")
wasmtime run --invoke 'summarize([1.5, 2.5])' shapes.wasm
# {count: 2, total: 4}
```

[`Shapes.java`](Shapes.java) implements the same world as a Java class, the WIT's
types its member records, enum and sealed interface; its component answers the
same calls:

```bash
rontolisp Shapes.java --java-source-path ../../../rontolisp-java-api/src/main/java \
  -o shapes.wasm --component
```

[`build.sh`](build.sh) plugs the Lisp one into the command, which then prints the
lines above (`SHAPES=Shapes.java ./build.sh` plugs the Java one):

```bash
RONTOLISP=../../../target/rontolisp ./build.sh
```

It needs [`wac`](https://github.com/bytecodealliance/wac) (`cargo install wac-cli`)
and `wasmtime` 46+.
