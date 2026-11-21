# l08. Java library: a `Locale` of any language

Difficulty: Medium

**Read `.kb/java-frontend-provenance.md` before writing anything.** Split from `.todo/k97`.

The library's `Locale` (`src/main/resources/am/ik/rontolisp/java/library/java/util/Locale.java`)
holds the root and the four English locales and makes no other; every consumer of a locale
either answers those five (`java.time`'s words and patterns, `DecimalStyle`, `WeekFields`)
or drops it (`String.format(Locale, ..)`, `toUpperCase(Locale)`, `printf(Locale, ..)`:
`.kb/java-frontend.md`, "The library"). So `Locale.forLanguageTag`, `Locale.of`,
`new Locale(..)` and `Locale.Builder` are refused by name.

What reaches it: `servlet-httpserver`'s `Fields.locales` (`Locale.forLanguageTag` of each
range of `Accept-Language`), so a Servlet program calling `getLocale()` or `getLocales()` is
refused (`.kb/servlet-httpserver.md`, "What the rontolisp side needs"). The container's Java
is the standard way to make one; the front end has to take it.

- `forLanguageTag` as its documentation states it (well-formed BCP 47 subtags, the first
  ill-formed one ending the tag, the grandfathered tags, `und`), `toLanguageTag`, `of`,
  the constructors, the getters (script, variant, extensions), `toString` with them, and
  the old language codes a JDK maps. Probe a JDK 25 for every text it answers.
- Decide each consumer for a locale it has no data of: the answer a JDK gives where that
  is the root's (an unknown language), a refusal by name where the run time would need
  CLDR data the program does not carry (`String.format(Locale.FRANCE, "%,d", ..)`). Not:
  dropping a locale the library cannot honour, which answers English silently.
- Measure what a program that never makes such a locale pays (nothing, the aim).
