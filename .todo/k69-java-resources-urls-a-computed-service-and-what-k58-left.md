# k69. Java resources: URLs, a service named by no class literal, and what k58 left

Difficulty: Medium

k58 carries the files a resource name computed where the program runs may find, and has
`Properties`, `ResourceBundle` and `ServiceLoader` of a class literal (`.kb/java-frontend.md`,
"Resources"). Still refused or missing:

- `getResource` / `getResources` (no `java.net.URL` in the subset) and
  `ClassLoader.getParent` (a JDK has the platform loader between the system's and the
  bootstrap one). A resource's URL names the file at the compile (`file:`, `jar:file:..!/`):
  measure what the launcher answers for a tree file, then what `openStream`, `toString`,
  `getPath`, `toURI` give for a carried one; `getResources` is every root holding the name,
  in an order to measure. `new URL(String)` and `URI.toURL()` come with the class.
- `ServiceLoader.load` of a `Class` whose type does not name the service (`Class<?>`, a
  `Class<T>` in a generic helper): 31 of the 110 calls of k58's scan pass a parameter. Every
  services file of the roots and the tree, each provider resolved, chosen by the class at
  run time.
- A member class as a bundle (`Outer$Texts_en`), and a bundle class a computed base name
  finds that the program reaches nowhere else (k58 makes the bundle classes the program has).
- The playground: its uploads list as no directory, so a computed name finds none of them.
  Listing them through the upload loader's `listDirectory` also changes `probe-file`,
  `directory` and the Clojure roots of the other languages there; measure that first.
- `ResourceBundle.Control` and `getBundle(.., Module)`; `Properties.loadFromXML` /
  `storeToXML`.
- The candidate locales of a script and a variant, and the Norwegian and Chinese cases the
  documentation states, once `Locale` has such locales (`.todo/k60`, its bullet on a locale
  that is not English).
