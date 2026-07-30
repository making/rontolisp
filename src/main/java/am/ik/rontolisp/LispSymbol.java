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
		return this.name;
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
