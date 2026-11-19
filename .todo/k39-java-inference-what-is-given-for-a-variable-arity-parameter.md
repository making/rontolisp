# k39. Java front end: inference of what is given for a variable arity parameter

Difficulty: Medium

Found 2026-11-20 while measuring YAVI's schema example behind k30 (the edits around
`core/UntypedInput`'s table left it one entry). Neither shape is on the milestone's
path (`.kb/java-frontend.md`, "YAVI compiled to wasm": the table has twelve entries); both
are Java a compiler takes.

1. **One argument that is a call holding a lambda or a method reference, given for a
   variable arity parameter**:

   ```java
   Map<String, Function<String, Object>> m = Map.ofEntries(Map.entry("a", s -> s));
   List<Map.Entry<String, Function<String, Object>>> l = Arrays.asList(Map.entry("a", Integer::valueOf));
   ```

   is `incompatible types: cannot infer type-variable(s) V` at the lambda. Two entries
   lower, and so does `Map.ofEntries(Map.entry("a", 1))`. Lead: with one argument the
   phases that take it as the array itself come first; the call waiting for its
   parameter's type (`late`, `.kb/java-frontend.md`, "Inference") is lowered against
   `Entry<..>[]` and its failure is reported, where it should say only that the
   candidate does not fit in that phase.
2. **An array of a type with wildcards given for another's variable arity parameter**:

   ```java
   @SafeVarargs
   static <K, W> Map<K, W> all(Map.Entry<? extends K, ? extends W>... entries) {
       return Map.ofEntries(entries);
   }
   ```

   is `method ofEntries in interface Map<K,V> cannot be applied to given types;
   required: Entry<? extends K,? extends V>[]; found: Entry<? extends K,? extends W>[]`.
   An array's element type is not captured, so a wildcard meets a wildcard
   (`Inference.lowerBound`'s array arm): `? extends K` given for `? extends K'` says
   `K <: K'`.

Pin each as a line of the corpus case
`a-type-argument-is-found-from-the-arguments-the-target-and-the-lambdas`.
