# A Lisp list and hash table cross into Java as a collection

Difficulty: Medium

A Common Lisp program calling a loaded `.java` file hands a `List` parameter a vector; a list
is no `List` ("its dispatch has no arm for a cons"), and a hash table is no `Map`
(`.kb/java-frontend.md`, "Across languages"; `doc/{en,ja}/java/index.md`, "Across languages").
So a Lisp caller converts by hand, and a Java method returning a collection hands back an object
the Lisp side cannot walk with its own sequence functions.

- In: a list and a hash table reach a parameter of `List`, `Collection`, `Iterable` or `Map` as
  a view, no copy: reads see the Lisp value, and a structural change the view cannot make (a
  list's `add` at the end, a removal) is decided -- `UnsupportedOperationException`, or
  supported where the representation allows. Where the dispatch gets its arm for a cons costs
  every collection call a test: measure the wasm size and the time of a `List` loop before and
  after on `examples/java/shop`, and keep the arm only where a value can come from Lisp if that
  is cheaper.
- Out: a `List` or `Map` a Java method returns can be read by the Lisp side (`elt`, `length`,
  `map`, `gethash` or documented accessors), without copying where the representation allows.
- Four backends, one java-spec case (`common-lisp-and-java-call-each-other` extended rather than
  a new one); `doc/{en,ja}/java/index.md` "Across languages" and `.kb`.
