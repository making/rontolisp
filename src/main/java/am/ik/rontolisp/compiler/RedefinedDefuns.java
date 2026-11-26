package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LambdaLists;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.macro.WrittenPlaces;

/**
 * The top-level {@code defun}s a program can replace or retire while it runs. Both
 * compile backends bind a reference to a top-level {@code defun} when they compile it, so
 * without this such a write reached only a name computed at run time.
 *
 * <p>
 * <b>A write by a literal name</b>: {@code (setf (fdefinition 'n) ...)},
 * {@code (setf (symbol-function 'n) ...)}, {@code (compile 'n definition)},
 * {@code (fmakunbound 'n)}. Such a name gets the one home a setf-only name has: its
 * definition is renamed and installed in the run-time function namespace where the
 * {@code defun} stood, and every reference reads what the namespace holds when it runs
 * ({@code LispMacroExpander.symbolFunctionForwarderNames}); a miss is the
 * {@code undefined-function} of a call before the definition ran.
 *
 * <p>
 * <b>A write by a name the compile cannot read</b>: the same operators over a computed
 * name, and whatever {@code eval} or a run-time {@code load} runs. Moving every function
 * such a name might be into the namespace is not worth what a direct call would pay, so
 * the {@link #celledDefuns} keep their compiled definition and get a cell: a reference
 * reads it in constant time and takes the compiled definition while the program has
 * written nothing there ({@code .kb/symbol-runtime-api.md}, "A write through a computed
 * name"). Each {@code defun} form leaves {@code %restore-compiled-definition}: from where
 * it stands the compiled definition answers again, whatever a write did before it.
 */
public final class RedefinedDefuns {

	private RedefinedDefuns() {
	}

	/**
	 * The names the program writes by a literal name. Read before
	 * {@code expandTopLevelDefinitions}, which adds the compile runtime's own
	 * {@code compile} -- a {@code (compile 'n ...)} is a write only where {@code compile}
	 * is the standard one -- and the generated definitions. A {@code (fmakunbound 'n)}
	 * counts for a definition the program has: a {@code defun}, or a generic function,
	 * which each later definition of it binds again ({@link #rewrite}).
	 * @param program the top-level forms
	 * @return the names, empty when the program writes none
	 */
	public static Set<String> names(List<LispVal> program) {
		Set<String> defined = new HashSet<>();
		for (LispVal form : program) {
			String name = topLevelDefinitionName(form);
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
	 * Whether the program holds a write of the function namespace whose name the compile
	 * cannot read: a place of {@code fdefinition} or {@code symbol-function} over a
	 * computed name, a {@code fmakunbound} of one, or {@code fmakunbound} as a function
	 * value. With {@code eval} and a run-time {@code load}, which the backends ask about
	 * themselves, it is what gives the {@link #celledDefuns} their cells.
	 * @param program the top-level forms, the compile runtime's {@code compile} included
	 * @return true when such a write is in the program
	 */
	public static boolean writesComputedNames(List<LispVal> program) {
		for (LispVal form : program) {
			if (writesComputedName(form)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The top-level {@code defun}s that keep their compiled definition and read a cell:
	 * those a write by a run-time name can reach, where the program holds such a write
	 * ({@code computedWrites}), each by {@link NestedDefunNamespace}'s rule: every
	 * function when the program evaluates data, else those a value of the program spells.
	 * An internal {@code %} name counts only where it is spelled: nothing the program
	 * reads from data is known to define one, and it is most of what a library brings
	 * along.
	 * <p>
	 * A generic on a compiler-lowered built-in is the other way in: its dispatcher is a
	 * function of another name ({@code ShadowedBuiltins}), so a write of the generic's
	 * name, literal too, reaches its calls through the dispatcher's cell.
	 * @param program the program, after every pass that can introduce a top-level defun
	 * @param written the {@link #names} of the program
	 * @param computedWrites whether the program holds a write by a run-time name
	 * @param dispatchers {@code ShadowedBuiltins.dispatchers}
	 * @return the names, in program order
	 */
	public static Set<String> celledDefuns(List<LispVal> program, Set<String> written, boolean computedWrites,
			Map<String, String> dispatchers) {
		Set<String> celled = new LinkedHashSet<>();
		dispatchers.forEach((generic, dispatcher) -> {
			if (written.contains(generic)) {
				celled.add(dispatcher);
			}
		});
		if (!computedWrites) {
			return celled;
		}
		Set<String> forwarders = LispMacroExpander.symbolFunctionForwarderNames(program);
		Set<String> named = new LinkedHashSet<>();
		Set<String> internal = new LinkedHashSet<>();
		for (LispVal form : program) {
			String name = topLevelDefunName(form);
			if (name != null && !written.contains(name) && !forwarders.contains(name)
					&& LispMacroExpander.genericOfMethodFunction(name) == null
					&& NestedDefunRedefinition.originalName(name).equals(name)) {
				(isInternal(name) ? internal : named).add(name);
			}
		}
		named.addAll(dispatchers.keySet());
		Set<String> reached = RuntimeNameProducers.anyNameResolvable(program) ? new LinkedHashSet<>(named)
				: new LinkedHashSet<>(NestedDefunNamespace.spelledAsValues(program, named));
		reached.addAll(NestedDefunNamespace.spelledAsValues(program, internal));
		for (String name : reached) {
			celled.add(dispatchers.getOrDefault(name, name));
		}
		return celled;
	}

	/**
	 * What the program's definitions become: each top-level {@code defun} of
	 * {@code names} the definition of an internal name and its installation
	 * ({@link NestedDefunRedefinition#renameAndInstall}), with the forwarder of the name
	 * joining the program; each of {@code celled} the {@code defun} and its
	 * {@code %restore-compiled-definition}. The definitions a pass generated count, a
	 * generic function's dispatcher and a slot accessor's among them. A name with a
	 * forwarder already has that home, and a name no {@code defun} defines stays as it
	 * is. A later definition of a generic function binds the name again, as the
	 * interpreter's regenerated dispatcher does, unless the name holds its metaobject.
	 * @param program the whole program, after every pass that can introduce a top-level
	 * defun of its own
	 * @param names the {@link #names} of the program
	 * @param celled the {@link #celledDefuns} of the program
	 * @param dispatchers {@code ShadowedBuiltins.dispatchers}
	 * @param metaobjects whether a name can hold a generic function metaobject
	 * ({@code ClosRegistry.gfProtocol}): a generic's definition then leaves it there
	 * @return the rewritten program, or the same list when nothing changes
	 * @throws UnsupportedOperationException when such a name is exported
	 */
	public static List<LispVal> rewrite(List<LispVal> program, Set<String> names, Set<String> celled,
			Map<String, String> dispatchers, Predicate<String> metaobjects) {
		Map<String, String> generics = new LinkedHashMap<>();
		dispatchers.forEach((generic, dispatcher) -> generics.put(dispatcher, generic));
		Set<String> forwarders = LispMacroExpander.symbolFunctionForwarderNames(program);
		Map<String, Integer> moved = new LinkedHashMap<>();
		List<LispVal> rewritten = new ArrayList<>(program.size());
		boolean changed = false;
		// No name holds a metaobject ahead of the table of them, whose reader would find
		// no table.
		boolean metaobjectsMade = false;
		for (LispVal form : program) {
			metaobjectsMade |= isMetaobjectTable(form);
			String name = topLevelDefunName(form);
			if (name != null && names.contains(name) && !forwarders.contains(name)) {
				NestedDefunRedefinition.renameAndInstall((LispCons) form, name, moved.merge(name, 1, Integer::sum),
						rewritten, metaobjectsMade && metaobjects.test(name));
				changed = true;
			}
			else if (name != null && celled.contains(name)) {
				String spelled = generics.getOrDefault(name, name);
				rewritten.add(form);
				rewritten.add(restore(spelled, metaobjectsMade && metaobjects.test(spelled)));
				changed = true;
			}
			else if (LispMacroExpander.genericDefinedAgainName(form) instanceof String generic) {
				boolean metaobject = metaobjectsMade && metaobjects.test(generic);
				if (moved.containsKey(generic)) {
					rewritten
						.add(NestedDefunRedefinition.install((LispCons) form, generic, moved.get(generic), metaobject));
				}
				else if (celled.contains(dispatchers.getOrDefault(generic, generic))) {
					rewritten.add(restore(generic, metaobject));
				}
				changed = true;
			}
			else {
				rewritten.add(form);
			}
		}
		if (!changed) {
			return program;
		}
		if (!moved.isEmpty()) {
			NestedDefunRedefinition.rejectExportCollision(program, moved.keySet(), "replaced by name");
			rewritten.addAll(LispMacroExpander.symbolFunctionForwarderDefuns(List.copyOf(moved.keySet())));
		}
		return rewritten;
	}

	private static boolean isMetaobjectTable(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& LispNames.DEFVAR.equals(head.name()) && cons.cdr() instanceof LispCons named
				&& named.car() instanceof LispSymbol variable && LispNames.GF_METAOBJECTS_VAR.equals(variable.name());
	}

	private static LispVal restore(String name, boolean metaobject) {
		LispVal quotedName = new LispCons(new LispSymbol(LispNames.QUOTE),
				new LispCons(new LispSymbol(name), LispNil.INSTANCE));
		LispVal restore = new LispCons(new LispSymbol(LispNames.RESTORE_COMPILED_DEFINITION_INTERNAL),
				new LispCons(quotedName, LispNil.INSTANCE));
		return NestedDefunRedefinition.unlessMetaobject(name, restore, metaobject);
	}

	/**
	 * A name the {@code %} convention marks internal: not the writer of a
	 * {@code (setf place)} function, which a program spells as the place.
	 */
	private static boolean isInternal(String name) {
		return member(name).startsWith("%") && ClosRegistry.setfPlaceOfFunctionName(name) == null;
	}

	private static boolean writesComputedName(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol head) {
			String op = member(head.name());
			if (LispNames.isQuote(op) || LispNames.FUNCTION.equals(op)) {
				return namesFmakunbound(cons.cdr());
			}
			if (WrittenPlaces.of(op, cons).stream().anyMatch(RedefinedDefuns::isComputedPlace)
					|| LispNames.FMAKUNBOUND.equals(op) && cons.isProperList() && cons.toList().size() > 1
							&& literalName(cons.toList().get(1)) == null) {
				return true;
			}
		}
		LispVal node = cons;
		while (node instanceof LispCons cell) {
			if (writesComputedName(cell.car())) {
				return true;
			}
			node = cell.cdr();
		}
		return false;
	}

	/** Whether a datum or a {@code #'} form names {@code fmakunbound} as a value. */
	private static boolean namesFmakunbound(LispVal datum) {
		if (datum instanceof LispSymbol symbol) {
			return LispNames.FMAKUNBOUND.equals(member(symbol.name()));
		}
		LispVal node = datum;
		while (node instanceof LispCons cell) {
			if (namesFmakunbound(cell.car())) {
				return true;
			}
			node = cell.cdr();
		}
		return false;
	}

	private static boolean isComputedPlace(LispVal place) {
		if (!(place instanceof LispCons cons) || !(cons.car() instanceof LispSymbol accessor) || !cons.isProperList()) {
			return false;
		}
		String op = member(accessor.name());
		return (LispNames.SYMBOL_FUNCTION.equals(op) || LispNames.FDEFINITION.equals(op)) && cons.toList().size() > 1
				&& literalName(cons.toList().get(1)) == null;
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
		for (LispVal place : WrittenPlaces.of(op, call)) {
			addPlaceName(place, written);
		}
		List<LispVal> parts = call.toList();
		switch (op) {
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
		if (call.size() > 1) {
			String name = literalName(call.get(1));
			if (name != null) {
				written.add(name);
			}
		}
	}

	private static @Nullable String literalName(LispVal arg) {
		return arg instanceof LispCons quote && quote.car() instanceof LispSymbol head
				&& LispNames.isQuote(member(head.name())) && quote.cdr() instanceof LispCons datum
						? functionName(datum.car()) : null;
	}

	private static @Nullable String topLevelDefunName(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& LispNames.DEFUN.equals(member(head.name())) && cons.cdr() instanceof LispCons nameCell
				&& nameCell.cdr() instanceof LispCons ? functionName(nameCell.car()) : null;
	}

	/**
	 * The name a top-level {@code defun}, {@code defgeneric} or {@code defmethod} binds.
	 */
	private static @Nullable String topLevelDefinitionName(LispVal form) {
		String name = topLevelDefunName(form);
		if (name != null) {
			return name;
		}
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& cons.cdr() instanceof LispCons nameCell) {
			String op = member(head.name());
			if (LispNames.DEFGENERIC.equals(op) || LispNames.DEFMETHOD.equals(op)) {
				return functionName(nameCell.car());
			}
		}
		return null;
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
