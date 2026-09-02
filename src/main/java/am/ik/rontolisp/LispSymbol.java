package am.ik.rontolisp;

/**
 * A symbol value.
 *
 * @param name the symbol name
 */
public record LispSymbol(String name) implements LispVal {

	/**
	 * Returns whether this symbol is a keyword (starts with {@code :}).
	 * @return {@code true} if the symbol name starts with ':'
	 */
	public boolean isKeyword() {
		return !this.name.isEmpty() && this.name.charAt(0) == ':';
	}

	/**
	 * Returns the symbol name without the leading package marker: a keyword's {@code :}
	 * and an uninterned symbol's {@code #:} are markers, not part of the name. This is
	 * what {@code symbol-name}, {@code princ} and {@code ~A} yield (matching CL apart
	 * from case, which stays as read); {@code prin1} keeps the marker via
	 * {@link #print()}.
	 * @param name the stored symbol name
	 * @return the name without a leading ':' or '#:'
	 */
	public static String displayName(String name) {
		if (name.startsWith("#:")) {
			return name.substring(2);
		}
		if (name.startsWith(":")) {
			return name.substring(1);
		}
		return name;
	}

	/**
	 * Returns the CL {@code symbol-name} spelling: the member part of a package-qualified
	 * name ({@code (symbol-name 'foo::bar)} is {@code "BAR"} -- the qualifier is where
	 * the symbol lives, not part of its name), and otherwise the {@link #displayName}
	 * marker-stripped spelling. This is also what {@code princ}/{@code ~A} yield (see
	 * {@link #display()}, CLHS 22.1.3.3: with {@code *print-escape*} false, only the
	 * characters of the symbol's name are output); {@code prin1} keeps the qualifier via
	 * {@link #print()}.
	 * @param name the stored symbol name
	 * @return the package-stripped, marker-stripped name
	 */
	public static String memberName(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		if (qn != null) {
			return qn.member();
		}
		return displayName(name);
	}

	@Override
	public String print() {
		if (this.isKeyword()) {
			return ":" + escape(displayName(this.name));
		}
		if (this.name.startsWith("#:")) {
			return "#:" + escape(this.name.substring(2));
		}
		int colon = qualifierEnd(this.name);
		if (colon > 0) {
			return this.name.substring(0, colon) + escape(this.name.substring(colon));
		}
		return escape(this.name);
	}

	/**
	 * The end (exclusive) of a {@code pkg:} / {@code pkg::} qualifier prefix in a
	 * package-qualified name, matching {@link PackageRegistry#splitQualified} closely
	 * enough for {@link #print()} to leave the qualifier text untouched -- unlike that
	 * method this keeps the ORIGINAL package spelling (a nickname is not normalized),
	 * since escaping is only about the member part.
	 * @param name the stored symbol name
	 * @return the index just past the qualifier's colon(s), or {@code -1} when
	 * {@code name} carries no package qualifier
	 */
	private static int qualifierEnd(String name) {
		int idx = name.indexOf(':');
		if (idx <= 0) {
			return -1;
		}
		return idx + 1 < name.length() && name.charAt(idx + 1) == ':' ? idx + 2 : idx + 1;
	}

	/**
	 * The {@code prin1} spelling of one symbol NAME component (a keyword's member, an
	 * uninterned symbol's member, a package-qualified name's member, or a whole
	 * unqualified name): wrapped in {@code |...|}, with every {@code |} and {@code \}
	 * inside escaped by a preceding {@code \}, when {@link #needsEscape} says the bare
	 * spelling would not read back as this name (CLHS 22.1.3.3). Otherwise returned
	 * verbatim -- the common case, so this only allocates when escaping is needed.
	 * @param member the name component to spell
	 * @return the read-back-safe spelling of {@code member}
	 */
	private static String escape(String member) {
		if (!needsEscape(member)) {
			return member;
		}
		StringBuilder sb = new StringBuilder(member.length() + 2);
		sb.append('|');
		for (int i = 0; i < member.length(); i++) {
			char c = member.charAt(i);
			if (c == '|' || c == '\\') {
				sb.append('\\');
			}
			sb.append(c);
		}
		return sb.append('|').toString();
	}

	/**
	 * Whether {@code member} needs {@code |...|} escaping to read back as itself (CLHS
	 * 22.1.3.3): the empty name, a name holding a character the reader would not accept
	 * inside a bare symbol token ({@link LispSymbol}'s own mirror of
	 * {@code LispLexer.isSymbolChar}'s terminating-character set, plus {@code |} and
	 * {@code \}, which are reader escape syntax rather than terminating characters
	 * there), or a name a bare re-read would not reproduce under the reader's
	 * {@code :upcase} folding. This is independent of the CURRENT {@code *print-case*}
	 * binding, which only recases an unescaped spelling after this decision is made
	 * (CLHS: an escaped symbol's spelling is never recased).
	 *
	 * <p>
	 * The case check is scoped to ASCII {@code a}-{@code z} rather than the reader's
	 * general {@code Character.toUpperCase(char)} fold (which does cover the full Unicode
	 * range {@code LispLexer.readSymbol} folds by): the compiled backends would otherwise
	 * need the {@code Character.toUpperCase(int)} range table
	 * ({@code WasmCaseFoldRuntimeBuilder}) reachable from every program that prints any
	 * symbol at all, not just one that calls {@code string-upcase}, which is the
	 * unconditional-emission bloat the printer-control-variable gating elsewhere in this
	 * file (`.kb/pretty-printer.md`) exists to avoid. A name whose only non-constituent
	 * characters are non-ASCII lowercase letters (e.g. Latin-1 {@code à}) therefore
	 * prints unescaped and mis-reads under the default readtable -- a known, narrow gap;
	 * re-evaluate together with a use for the general fold on a path every printed
	 * program already pays for.
	 * @param member the name component to test
	 * @return {@code true} when {@code member} must be {@code |...|}-escaped
	 */
	private static boolean needsEscape(String member) {
		if (member.isEmpty()) {
			return true;
		}
		for (int i = 0; i < member.length(); i++) {
			char c = member.charAt(i);
			if ((c >= 'a' && c <= 'z') || !isBareConstituent(c)) {
				return true;
			}
		}
		return false;
	}

	// Mirrors LispLexer.isSymbolChar's terminating-character set (reader pkg, which
	// this root package must not depend on -- .kb/pretty-printer.md) restricted to the
	// ASCII whitespace LispLexer's Character.isWhitespace(c) recognizes for these
	// characters (see needsEscape's Javadoc on the matching ASCII-only case scope),
	// plus '|' and '\': reader-special even though isSymbolChar does not exclude them,
	// since a literal occurrence would otherwise be read as escape syntax rather than
	// as itself.
	private static boolean isBareConstituent(char c) {
		return c != ' ' && c != '\t' && c != '\n' && c != '\r' && c != '\f' && c != '(' && c != ')' && c != '\''
				&& c != '"' && c != ';' && c != ',' && c != '`' && c != '|' && c != '\\';
	}

	/**
	 * The {@code princ}/{@code ~A} spelling: the symbol's NAME, with neither the package
	 * qualifier nor a keyword/gensym marker. CLHS 22.1.3.3 -- with {@code *print-escape*}
	 * false only the characters of the name are output -- so {@code (princ 'quri:uri)}
	 * writes {@code URI}, not {@code QURI:URI}. This is load-bearing beyond printing: a
	 * library that synthesizes a function name with
	 * {@code (intern (format nil "~:@(~a-~a~)" name :string))} (quri's
	 * {@code defun-with-array-parsing}) interns the qualifier into the NAME when
	 * {@code ~A} leaks it, defining its function under a name no call site resolves to.
	 */
	@Override
	public String display() {
		return memberName(this.name);
	}

}
