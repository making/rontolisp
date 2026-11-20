# k58 probes and measurements

Written here; run under JDK 25.0.5. What they showed is in `.kb/java-frontend.md`,
"Resources", and the answers the corpus keeps are lines of `java-spec.yaml`.

## What libraries compute (`Scan.java`)

`java Scan.java <list>` reads every class file of the jars a list names (paths below
`~/.m2/repository`, the newest version of each artifact) with the class-file API and, for
each call naming a resource, follows its name argument back to what pushed it. Run over
the 753 artifacts of a local repository (rontolisp's own left out), 198 of which make such
a call:

| Calls | Total | Constant | Concatenation | Parameter | Call's result | Field |
|---|---|---|---|---|---|---|
| `Class.getResource`, `getResourceAsStream` | 278 | 129 | 69 | 55 | 16 | 9 |
| `ClassLoader` lookups | 396 | 86 | 70 | 147 | 63 | 23 |
| `ResourceBundle.getBundle` | 114 | 61 | 5 | 15 | 15 | 18 |
| `ServiceLoader.load` | 110 | 68 | 0 | 31 | 1 | 10 |

(7 loader lookups take an array element.) The concatenations are mostly a class's name and
`.class`, `META-INF/services/` and a name, `META-INF/maven/<group>/<artifact>/pom.properties`.

## Probes

Each tree runs from its `tree/` directory as `java [-cp ../libcls] app/Main.java`, the
library compiled into `libcls/` first (`javac -d libcls libsrc/lib/*.java`) with the
resources beside it.

- `props/`: `Props.java` the 53 texts through `load`; `Store.java`, `Comments.java` what
  `store` writes; `Orders.java`, `Orders2.java` which map each view's order is
  (simulated against `HashMap`, `Hashtable` and `ConcurrentHashMap` fills);
  `Cap.java` a table's order against a `ConcurrentHashMap` of each capacity;
  `Nulls.java` the refusals.
- `rb1/`: bundles of the tree and of a library, through a program's and a library's
  class; `rb2/`: the fallback, under `-Duser.language=en -Duser.country=US`, `en GB`,
  `fr FR` and `en ''`; `rb3/`: the order of a bundle's keys, UTF-8 and ISO 8859-1 files,
  bundle classes ahead of files; `rb4/`: one object per bundle, a missing key in a chain,
  a `ListResourceBundle`'s keys.
- `sl1/`: the providers a tree's and a library's services file name, each way one fails,
  through an iterator and a stream (`Main.java` as last run; its first version also
  named `app.Missing`, `app.Bad` and `app.Hidden`).
