package am.ik.rontolisp;

import java.util.List;
import java.util.SequencedMap;

/**
 * What a compiled program needs at run time to decide whether a package-qualified symbol
 * prints bare -- CLHS 22.1.3.3.1: no qualifier when the symbol is accessible in the
 * current {@code *package*} -- without a package registry
 * ({@link PackageResolver#symbolPrintTable}).
 *
 * <p>
 * The canonical spelling of a symbol already carries its home package and whether it is
 * external ({@code PKG:NAME} / {@code PKG::NAME}), so the structural rule -- bare when
 * the home IS the current package, or when the current package USES the home and the
 * symbol is external -- needs only the use lists ({@link #rows}). Everything the resolver
 * decides differently for a symbol that occurs in the program lands in two correction
 * lists computed against it at compile time: {@link #extra} (accessible although the rule
 * says no: an {@code :import-from}, a re-export through an intermediate package, an
 * {@code export} after the definition) and {@link #excluded} (not accessible although the
 * rule says yes: a {@code :shadow}, an earlier used package exporting the same name, an
 * {@code unexport}). A symbol interned at run time that no correction names follows the
 * rule.
 *
 * <p>
 * A bare spelling carries no home: it is a {@code cl} symbol or a {@code cl-user} one.
 * The {@code cl-user} ones a program can print are {@link #clUserSymbols}; such a symbol
 * prints {@link #CL_USER_PREFIX qualified} outside {@code cl-user}. Any other bare symbol
 * prints bare, except a standard name under one of the {@link #noClPackages}, which
 * prints {@link #CL_PREFIX qualified} unless the resolver reads the name as {@code cl}'s
 * in every package ({@link PackageRegistry#readsClInEveryPackage}). {@link #bareExtra}
 * and {@link #bareExcluded} correct that rule for the bare symbols the program spells and
 * the standard names a package imports, shadows or inherits from another.
 *
 * <p>
 * Every map is insertion-ordered ({@code SequencedMap}) because the tables are emitted
 * ({@code .kb/emitted-output-determinism.md}).
 *
 * @param rows the upcased canonical name of every registered package (the
 * {@code (string *package*)} of its package value) mapped to the qualifier spelling of
 * the package itself followed by the qualifier spellings of the packages it uses
 * ({@code cl} omitted: a {@code cl} symbol is never spelled qualified)
 * @param extra per upcased package name, the symbols (canonical spellings) that print
 * bare there although the structural rule says otherwise
 * @param excluded per upcased package name, the symbols that print qualified there
 * although the structural rule says otherwise
 * @param clUserPristine whether {@code cl-user} still uses only {@code cl} and imports
 * nothing, so that under it no qualified symbol can print bare and the raw conversion is
 * exact
 * @param noClPackages the upcased names of the packages that do not read the standard
 * names as {@code cl}'s
 * @param clUserSymbols the names of the {@code cl-user} symbols the program can print:
 * spelled in quoted data, or naming a structure, class or condition
 * @param bareExtra per upcased package name, the bare symbols that print bare there
 * although the rule says otherwise
 * @param bareExcluded per upcased package name, the bare symbols that print qualified
 * there although the rule says otherwise
 */
public record SymbolPrintTable(SequencedMap<String, List<String>> rows, SequencedMap<String, List<String>> extra,
		SequencedMap<String, List<String>> excluded, boolean clUserPristine, List<String> noClPackages,
		List<String> clUserSymbols, SequencedMap<String, List<String>> bareExtra,
		SequencedMap<String, List<String>> bareExcluded) {

	/**
	 * What a {@code cl} symbol prints in front of its name where it needs a qualifier.
	 */
	public static final String CL_PREFIX = "COMMON-LISP:";

	/**
	 * What a {@code cl-user} symbol prints in front of its name where it needs a
	 * qualifier.
	 */
	public static final String CL_USER_PREFIX = "COMMON-LISP-USER::";

}
