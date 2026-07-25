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
	 * marker-stripped spelling. {@code princ}/{@code ~A} keep the qualifier (see
	 * {@link #display()}); {@code symbol-name} and the string-designator coercions must
	 * not, or name surgery like ironclad's
	 * {@code (intern (concatenate ... (string digest-name) ...))} re-qualifies an
	 * already-qualified spelling.
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
		return this.name;
	}

	@Override
	public String display() {
		return displayName(this.name);
	}

}
