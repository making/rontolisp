package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LambdaLists;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.macro.LispMacroExpander;

/**
 * The top-level {@code defun}s the program itself replaces or retires by name:
 * {@code (setf (fdefinition 'n) ...)}, {@code (setf (symbol-function 'n) ...)},
 * {@code (compile 'n definition)}, {@code (fmakunbound 'n)}.
 *
 * <p>
 * Both compile backends bind a reference to a top-level {@code defun} when they compile
 * it, so such a write reached only a name computed at run time: a direct call,
 * {@code #'n}, the quoted designator of {@code (funcall 'n ...)} and the literal
 * {@code symbol-function} / {@code fdefinition} all kept the first definition. Such a
 * name gets the one home a setf-only name has: its definition is renamed and installed in
 * the run-time function namespace where the {@code defun} stood, and a forwarder of the
 * name serves its direct calls ({@code LispMacroExpander.symbolFunctionForwarderNames}),
 * so every reference reads what the namespace holds when it runs and a miss is the
 * {@code undefined-function} of a call before the definition ran.
 *
 * <p>
 * A name written only through a computed name, by {@code eval} or by {@code load} is not
 * found here: holding every name a run-time value might spell would move much of such a
 * program into a namespace each reference walks ({@code .kb/symbol-runtime-api.md},
 * "Divergences").
 */
public final class RedefinedDefuns {

	private RedefinedDefuns() {
	}

	/**
	 * The names the program writes by a literal name. Read before
	 * {@code expandTopLevelDefinitions}, which adds the compile runtime's own
	 * {@code compile} -- a {@code (compile 'n ...)} is a write only where {@code compile}
	 * is the standard one -- and the generated definitions: {@code (fmakunbound 'n)}
	 * counts only for the program's own {@code defun}, since a {@code defmethod} after it
	 * binds a generic function's name again and its dispatcher has one definition, where
	 * the name's first {@code defgeneric} or {@code defmethod} stood.
	 * @param program the top-level forms
	 * @return the names, empty when the program writes none
	 */
	public static Set<String> names(List<LispVal> program) {
		Set<String> defined = new HashSet<>();
		for (LispVal form : program) {
			String name = topLevelDefunName(form);
			if (name != null) {
				defined.add(name);
			}
		}
		Set<String> written = new LinkedHashSet<>();
		Set<String> retired = new LinkedHashSet<>();
		for (LispVal form : program) {
			collectWrites(form, !defined.contains(LispNames.COMPILE), written, retired);
		}
		retired.retainAll(defined);
		written.addAll(retired);
		return written;
	}

	/**
	 * Moves each top-level {@code defun} of {@code names} to the function namespace:
	 * {@code (defun n ...)} becomes the definition of an internal name and its
	 * installation ({@link NestedDefunRedefinition#renameAndInstall}), and the forwarder
	 * of the name joins the program. The definitions a pass generated count, a generic
	 * function's dispatcher and a slot accessor's among them. A name with a forwarder
	 * already has that home, and a name no {@code defun} defines stays as it is.
	 * @param program the whole program, after every pass that can introduce a top-level
	 * defun of its own
	 * @param names the {@link #names} of the program
	 * @return the rewritten program, or the same list when no {@code defun} is moved
	 * @throws UnsupportedOperationException when such a name is exported
	 */
	public static List<LispVal> rewrite(List<LispVal> program, Set<String> names) {
		if (names.isEmpty()) {
			return program;
		}
		Set<String> forwarders = LispMacroExpander.symbolFunctionForwarderNames(program);
		Map<String, Integer> moved = new LinkedHashMap<>();
		List<LispVal> rewritten = new ArrayList<>(program.size());
		for (LispVal form : program) {
			String name = topLevelDefunName(form);
			if (name != null && names.contains(name) && !forwarders.contains(name)) {
				NestedDefunRedefinition.renameAndInstall((LispCons) form, name, moved.merge(name, 1, Integer::sum),
						rewritten);
			}
			else {
				rewritten.add(form);
			}
		}
		if (moved.isEmpty()) {
			return program;
		}
		NestedDefunRedefinition.rejectExportCollision(program, moved.keySet(), "replaced by name");
		rewritten.addAll(LispMacroExpander.symbolFunctionForwarderDefuns(List.copyOf(moved.keySet())));
		return rewritten;
	}

	private static void collectWrites(LispVal form, boolean standardCompile, Set<String> written, Set<String> retired) {
		LispVal node = form;
		while (node instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol head) {
				String op = member(head.name());
				if (LispNames.isQuote(op)) {
					return;
				}
				collectWrite(op, cons, standardCompile, written, retired);
			}
			collectWrites(cons.car(), standardCompile, written, retired);
			node = cons.cdr();
		}
	}

	private static void collectWrite(String op, LispCons call, boolean standardCompile, Set<String> written,
			Set<String> retired) {
		if (!call.isProperList()) {
			return;
		}
		List<LispVal> parts = call.toList();
		switch (op) {
			case LispNames.SETF, LispNames.PSETF -> {
				for (int i = 1; i + 1 < parts.size(); i += 2) {
					addPlaceName(parts.get(i), written);
				}
			}
			case LispNames.ROTATEF, LispNames.SHIFTF -> {
				for (LispVal place : parts.subList(1, parts.size())) {
					addPlaceName(place, written);
				}
			}
			case LispNames.FMAKUNBOUND -> addLiteralName(parts, retired);
			case LispNames.COMPILE -> {
				// Without a definition, compile installs nothing.
				if (standardCompile && parts.size() > 2 && parts.get(2) != LispNil.INSTANCE) {
					addLiteralName(parts, written);
				}
			}
			default -> {
			}
		}
	}

	private static void addPlaceName(LispVal place, Set<String> written) {
		if (place instanceof LispCons cons && cons.car() instanceof LispSymbol accessor && cons.isProperList()) {
			String op = member(accessor.name());
			if (LispNames.SYMBOL_FUNCTION.equals(op) || LispNames.FDEFINITION.equals(op)) {
				addLiteralName(cons.toList(), written);
			}
		}
	}

	/** The name a quoted first argument spells, a {@code (setf n)} list as its writer. */
	private static void addLiteralName(List<LispVal> call, Set<String> written) {
		if (call.size() > 1 && call.get(1) instanceof LispCons quote && quote.car() instanceof LispSymbol head
				&& LispNames.isQuote(member(head.name())) && quote.cdr() instanceof LispCons datum) {
			String name = functionName(datum.car());
			if (name != null) {
				written.add(name);
			}
		}
	}

	private static @Nullable String topLevelDefunName(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& LispNames.DEFUN.equals(member(head.name())) && cons.cdr() instanceof LispCons nameCell
				&& nameCell.cdr() instanceof LispCons ? functionName(nameCell.car()) : null;
	}

	private static @Nullable String functionName(LispVal name) {
		if (name instanceof LispSymbol symbol) {
			return symbol.name();
		}
		LispSymbol place = LambdaLists.setfFunctionPlaceName(name);
		return place == null ? null : LispMacroExpander.setfFunctionName(place.name());
	}

	/** The package-stripped member of a possibly qualified name. */
	private static String member(String name) {
		PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(name);
		return qualified == null ? name : qualified.member();
	}

}
