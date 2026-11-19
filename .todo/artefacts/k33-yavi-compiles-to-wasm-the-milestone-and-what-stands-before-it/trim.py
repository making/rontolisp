# Cuts from a COPY of YAVI's core/UntypedInput.java what a schema of a string and an
# integer never asks for, to measure what the one table costs such a schema.
# usage: trim.py <UntypedInput.java> a|b|c
#   a: the table of the types read from their text, the java.util.Date / java.sql arm,
#      the Year arm of the conversion of a number
#   b: a, and the conversion of a number through BigDecimal (a number of another type
#      is then a mismatch)
#   c: the URI entry of the table alone
import re
import sys

path, level = sys.argv[1], sys.argv[2]
source = open(path, encoding='utf-8').read()


def cut(pattern, replacement):
    global source
    source, n = re.subn(pattern, replacement, source, count=1, flags=re.S)
    assert n == 1, pattern


if level == 'c':
    cut(r', Map\.entry\(URI\.class, URI::create\)\);', ');')
else:
    cut(r'PARSERS = Map\.ofEntries\(.*?URI::create\)\);', 'PARSERS = Map.of();')
    cut(r'\tprivate static final Pattern CANONICAL_UUID = Pattern\n[^\n]*\n', '')
    cut(r'\tprivate static UUID parseUuid\(String string\) \{.*?\n\t\}\n', '')
    cut(r'\t\tif \(value instanceof java\.util\.Date date\) \{.*?\n\t\t\}\n', '')
    cut(r'\tprivate static boolean isSqlType\(Class<\?> type\) \{.*?\n\t\}\n', '')
    cut(r'\tprivate static final class SqlTypes \{.*?\n\t\}\n', '')
    cut(r'\t\t\tif \(type == Year\.class\) \{.*?\n\t\t\t\}\n', '')
    cut(r'catch \(ArithmeticException \| DateTimeException e\)', 'catch (ArithmeticException e)')
    if level == 'b':
        cut(r'\tprivate static Object convertNumber\(Number number, Class<\?> type\) \{.*?\n\t\}\n',
            '\tprivate static Object convertNumber(Number number, Class<?> type) {\n\t\treturn MISMATCH;\n\t}\n')
        cut(r'\tprivate static @Nullable BigDecimal decimal\(Number number\) \{.*?\n\t\}\n', '')
        cut(r'\n\t\t\t\t\|\| type == BigInteger\.class\) \{', ') {')
open(path, 'w', encoding='utf-8').write(source)
