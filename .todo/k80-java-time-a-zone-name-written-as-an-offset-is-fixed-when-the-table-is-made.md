# k80. java.time: a zone's name written as an offset is fixed when the table is made

Difficulty: Low

**Read `.kb/java-frontend.md`, "java.time: the names in words, the localized forms and a day's
periods" first.**

A name a JDK writes as an offset (`GMT-05:00` in the root and in `en-GB`) is the zone's
offset at the moment the JDK runs, not at the instant written (`GMT` for Abidjan in 1900);
`zone-names.txt` holds it as of the JDK that generated the file (`JavaZoneRulesTest`). A
change of a zone's offsets scheduled after that moment and already in the tz database would
make a program's names differ from a JDK's from the change on, with no test failing: a
newer tz database fails the test, a date passing does not. None is pending in tz database
2026e.

To do: ask a JDK which offset such a name is (the standard offset now, and for daylight
time what: New York's `z` is `GMT-04:00` in summer, `v` `GMT-05:00`), then either compute
the name where the program runs from the rules it holds, or make the test fail where a held
region's offsets change between the table's making and the test's run.
