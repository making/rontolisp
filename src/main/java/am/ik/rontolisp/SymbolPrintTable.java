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
 */
public record SymbolPrintTable(SequencedMap<String, List<String>> rows, SequencedMap<String, List<String>> extra,
		SequencedMap<String, List<String>> excluded, boolean clUserPristine) {
}
