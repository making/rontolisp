#!/bin/sh
# Rebuilds the patched copy of a YAVI source tree the measurement lowers: each edit
# routes around ONE gap of the front end, named in the README beside this file, so that
# the next one shows; a class standing in for one a gap names goes under shim/ beside
# this file. usage: patch.sh <pristine sources dir> <patched dir to make>
set -e
src=$1
out=$2
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$out"
cp -r "$src"/. "$out"/
if [ -d "$here"/shim ]; then
    cp -r "$here"/shim/. "$out"/
fi
# The classic example needs none (2026-11-19). The schema example's five (2026-11-20),
# four since k36 (java.util.UUID is in the library; the numbers keep their places),
# all in what reads an input of unknown type; GNU sed.
untyped="$out"/am/ik/yavi/core/UntypedInput.java
# 1. java.time: the classes read from their text, but Duration
sed -i -e '/Map\.entry(LocalDate\.class/,/Map\.entry(Year\.class/d' \
    -e 's/Map\.entry(Duration\.class, Duration::parse), Map\.entry(Period\.class, Period::parse),/Map.entry(Duration.class, Duration::parse),/' "$untyped"
# 3. java.util.Date and java.sql
sed -i -e '/value instanceof java\.util\.Date date/,/^\t\t}$/d' \
    -e '/private static final class SqlTypes/,/^\t}$/d' "$untyped"
# 4. java.math.BigDecimal and BigInteger: a number of another type is a mismatch
sed -i -e '/private static Object convertNumber/,/^\t}$/c\\tprivate static Object convertNumber(Number number, Class<?> type) {\n\t\treturn MISMATCH;\n\t}' \
    -e '/private static @Nullable BigDecimal decimal/,/^\t}$/d' \
    -e 's/^\t\t\t\t|| type == BigInteger\.class) {$/\t\t\t\t) {/' "$untyped"
# 5. java.util.stream.StreamSupport: a projection keeps every property
sed -i '/return object instanceof Projection<?>projection ? StreamSupport/,/\.toList() : properties;/c\\t\t\treturn properties;' \
    "$out"/am/ik/yavi/input/Projection.java
