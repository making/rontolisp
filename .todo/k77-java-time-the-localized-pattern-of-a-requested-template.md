# k77. java.time: the localized pattern of a requested template

Difficulty: High

**Read `.kb/java-frontend-provenance.md` first**, and `.kb/java-frontend.md`, "java.time: the
names in words, the localized forms and a day's periods" and "java.time: what k57 left".

`DateTimeFormatter.ofLocalizedPattern(String)`, `DateTimeFormatterBuilder.appendLocalized(String)`
and `getLocalizedDateTimePattern(String, Chronology, Locale)` are refused by name. A JDK 25
answers them from CLDR's skeletons, and does not do what UTS #35's matching describes
(probed by k70, 2026-11-21; the probe's lines are not kept, ask again):

- The template is checked against the documented order of letters; a template out of it
  is `IllegalArgumentException: Requested template "dM" is invalid.`, and one that `j` or `C`
  makes invalid says so (`... is invalid, which translated into "HHH" after the 'j' or 'C'
  substitution.`). A valid one with no match is `DateTimeException: Requested template "s"
  cannot be resolved in the locale "en_US"` (the locale's `toString`, `""` for the root).
- No width is adjusted: `yMMMMEEEEd` is `y MMM d, E` in the root and `E, MMM d, y` in
  `en-US`, but `EEEE, d MMMM y` in `en-GB`, which has that skeleton. `MMMMM` is `LLL`,
  `yyyy` is `G y` (root) / `y G` (`en-US`), `yy`, `hh`, `jj`, `m`, `s`, `QQQ`, `v`, `z`,
  `zzzz` and `GGGGGy` resolve to nothing; `jm` is `HH:mm` in the root and `en-GB`,
  `h:mm a` in `en-US` (U+202F before `a`).
- A bounded enumeration of the valid templates (each letter's counts up to the
  documented maximum, the hour letters two at most) is some six million per locale; a JDK
  did not answer them all in ten minutes.

To do: find the rule a JDK follows (which skeletons a locale kind has, how a template finds
one, what `j`/`C` and the field widths do), checked on a large grid of the four kinds of
locale; ship the data as `Texts` holds the localized patterns (CLDR's, under the Unicode
entry of `THIRD-PARTY-NOTICES.txt`); a program pays only where it calls the members (note a
constant template while lowering, as `ofPattern` notes its letters).
