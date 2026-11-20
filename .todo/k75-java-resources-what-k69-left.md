# k75. Java resources: what k69 left

Difficulty: Medium

k69 gave the subset `java.net.URL`, `getResource` / `getResources`, the loaders above the
program's, `ServiceLoader` of a computed class and member classes as bundles
(`.kb/java-frontend.md`, "Resources"). Still refused or missing:

- The playground: its uploads list as no directory, so a computed name finds none of them.
  Listing them through the upload loader's `listDirectory` also changes `probe-file`,
  `directory` and the Clojure roots of the other languages there; measure that first.
- A bundle class a computed base name finds that the program reaches nowhere else (k58
  makes the bundle classes the program has). Declaring every class of the tree to find
  them may lower classes the program never uses: measure the cost on a tree with many.
- `ResourceBundle.Control` and `getBundle(.., Module)`; `Properties.loadFromXML` /
  `storeToXML`.
- The candidate locales of a script and a variant, and the Norwegian and Chinese cases the
  documentation states, once `Locale` has such locales (`.todo/k71`, its bullet on a locale
  that is not English).
- A directory as a resource: the launcher answers a URL of it and a listing through its
  stream (`deviations.md`); the subset answers `null`.
