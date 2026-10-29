# clojure.reflect

A host class's bases, flags and members as data. Require `clojure.reflect` to use it; it is
Clojure source written for rontolisp from the documented behavior of Clojure's namespace, over
the host's `java.lang.reflect`, so it reflects on the interpreter and the JVM. A WebAssembly
target has no host class: there the namespace loads and a reflection throws
`UnsupportedOperationException` when it is called.

| Var | Behavior |
|---|---|
| `type-reflect` | `(type-reflect typeref & options)`: `{:bases :flags :members}` of the type `typeref` names, a class or a symbol. `:bases` is the set of symbols of the superclass and interfaces (`nil` for none), `:flags` the class's flag keywords, `:members` the set of its declared constructors, methods and fields. Options: `:ancestors true` adds `:ancestors`, the symbols of every ancestor, and merges their members into `:members`; `:reflector` the `Reflector` to ask (a `JavaReflector` over the context class loader) |
| `reflect` | `(reflect obj & options)`: `type-reflect` of `obj` when it is a class, else of its class |
| `Constructor`, `Method`, `Field` | The member records: `:name` a symbol, `:return-type`/`:type`/`:declaring-class` symbols, `:parameter-types`/`:exception-types` vectors of symbols, `:flags` a set of keywords. `->Method`, `map->Method` and the like build them |
| `typename` | The type's name as Java spells it: `"java.lang.String"`, an array type with one `[]` per dimension. A symbol spells an array type with one `<>` per dimension |
| `flag-descriptors` | The flags reported: each `{:name :flag :contexts}`, its keyword, its bit and the contexts (`:class`, `:method`, `:field`) it is read in |
| `Reflector`, `do-reflect` | The protocol `type-reflect` asks; `->JavaReflector` makes one over a class loader |
| `TypeReference` | The protocol of `typename`, extended to classes and symbols |
| `ClassResolver`, `resolve-class` | `(resolve-class loader name)`: the class file of the type `name` names, as an input stream, through a class loader |

```console
clojure> (require '[clojure.reflect :as reflect])
nil
clojure> (reflect/type-reflect Runnable)
{:bases nil, :flags #{:public :interface :abstract}, :members #{#clojure.reflect.Method{:name run, :return-type void, :declaring-class java.lang.Runnable, :parameter-types [], :exception-types [], :flags #{:public :abstract}}}}
clojure> (sort (map :name (filter #(contains? (:flags %) :varargs) (:members (reflect/reflect "")))))
(format format formatted join)
clojure> (reflect/typename 'java.lang.String<>)
"java.lang.String[]"
```

## Differences

- `->AsmReflector` is not built in: it reads class files with the ASM library inside
  clojure.jar.
- A Clojure collection's class is a `clojure.lang` class the host does not hold, so `reflect`
  of one throws `ClassNotFoundException`; a typed array is a vector, so `reflect` of
  `(int-array 0)` is that too. Name the array class instead: `(Class/forName "[I")`.
- `Long` and `Double`, and the class of a number, are keywords here; `reflect` and
  `type-reflect` take one as the class it names.
