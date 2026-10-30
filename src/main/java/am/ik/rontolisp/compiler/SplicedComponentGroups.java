package am.ik.rontolisp.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import org.jspecify.annotations.Nullable;

/**
 * The compile-path half of the ASDF operation protocol ({@code .kb/asdf.md}, "The
 * operation protocol"). On the interpreter every component load is an
 * {@code asdf:perform} call, so a method a library adds -- asdf-flv's
 * {@code perform :around} binding its file-local variables -- runs around each load. A
 * compiled program has no load to wrap: {@code LoadInliner} spliced the component's forms
 * at compile time. What it does have is the run of those forms, and this pass makes THAT
 * the load.
 *
 * <p>
 * {@code LoadInliner} brackets every component it splices with
 * {@code (%asdf-component "SYSTEM" INDEX)} / {@code (%asdf-component-end)}, and marks the
 * system's own actions with {@code (%asdf-system-op "SYSTEM" :before|:after)}. Once a
 * form of the program has defined an {@code asdf:perform} method, every later bracket
 * lowers to
 *
 * <pre>
 * (cl-user::%asdf-load-spliced "SYSTEM" INDEX (cl:lambda () (cl-user::%run-top-group N)))
 * (cl-user::%top-group N)
 *   ...the component's forms...
 * (cl-user::%top-group-end N)
 * </pre>
 *
 * and every later system mark to a {@code %asdf-perform-system} call. The forms of group
 * {@code N} stay top-level forms for every static pass -- each backend still collects
 * their definitions where they stand -- but their RUN-TIME code is emitted into functions
 * the top level does not call in sequence: only {@code %run-top-group} reaches them, from
 * the thunk the component's load-op is performed with. Before the first definition the
 * brackets are dropped (no method exists yet to run, which is where the interpreter is
 * too), and a program that defines none is byte-identical to one compiled before the
 * brackets existed ({@code AsdfRuntimeLibrary.process} strips them at the end of the
 * splice).
 *
 * <p>
 * Runs before package resolution, beside {@code lowerLoadContextMarkers}: the names it
 * emits are spelled {@code cl-user::}/{@code cl:} so that whatever package the splice
 * point is in, they resolve to the bare canonical names the backends dispatch on. The
 * group markers and {@code %run-top-group} are understood by the JVM and wasm-GC backends
 * only; a backend that cannot run a group refuses the program
 * ({@link #lower(List, String)}).
 */
public final class SplicedComponentGroups {

	private SplicedComponentGroups() {
	}

	/** The seam defun a lowered component bracket calls (asdf runtime, compile seam). */
	private static final String LOAD_SPLICED = "%ASDF-LOAD-SPLICED";

	/** The asdf.lisp driver a lowered system mark calls. */
	private static final String PERFORM_SYSTEM = "%ASDF-PERFORM-SYSTEM";

	/** The asdf.lisp defun whose presence proves the runtime was spliced. */
	private static final String PERFORM_COMPONENT = "%ASDF-PERFORM-COMPONENT";

	/** The qualifiers a {@code perform} spelling may carry and still be ASDF's. */
	private static final Set<String> PERFORM_PACKAGES = Set.of(LispNames.ASDF_PKG, "ASDF/ACTION");

	/**
	 * Whether {@code form} defines a method on {@code asdf:perform} anywhere inside it --
	 * a {@code defmethod} (or a {@code defgeneric}'s {@code :method} clause) of a
	 * {@code perform} that is ASDF's spelling or an unqualified one a package using
	 * {@code asdf} reads as ASDF's. Nested forms count (a method defined inside a
	 * {@code progn}, a {@code let}, a macro's template), since the position of the
	 * definition is all the lowering needs. An unqualified {@code perform} of an
	 * unrelated generic only costs the groups this pass builds; it changes no answer.
	 * @param form a top-level form
	 * @return {@code true} when it defines such a method
	 */
	public static boolean definesPerformMethod(LispVal form) {
		// The spine is a loop and only nesting recurses, so a long form costs no frames.
		for (LispVal val = form; val instanceof LispCons cons; val = cons.cdr()) {
			if (val == form && isPerformDefinition(cons)) {
				return true;
			}
			if (cons.car() instanceof LispCons inner && definesPerformMethod(inner)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isPerformDefinition(LispCons form) {
		if (!(form.car() instanceof LispSymbol head) || !(form.cdr() instanceof LispCons rest)
				|| !isPerform(rest.car())) {
			return false;
		}
		String member = member(head);
		return LispNames.DEFMETHOD.equals(member) || LispNames.DEFGENERIC.equals(member) && hasMethodClause(rest.cdr());
	}

	private static boolean hasMethodClause(LispVal options) {
		for (LispVal val = options; val instanceof LispCons cons; val = cons.cdr()) {
			if (cons.car() instanceof LispCons clause && clause.car() instanceof LispSymbol key
					&& ":METHOD".equals(key.name())) {
				return true;
			}
		}
		return false;
	}

	private static boolean isPerform(LispVal name) {
		if (!(name instanceof LispSymbol sym) || sym.name().startsWith(":")) {
			return false;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
		if (qn == null) {
			return LispNames.PERFORM.equals(sym.name());
		}
		return LispNames.PERFORM.equals(qn.member()) && PERFORM_PACKAGES.contains(qn.pkg());
	}

	private static String member(LispSymbol sym) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
		return qn == null ? sym.name() : qn.member();
	}

	/**
	 * Whether {@code form} is one of {@code LoadInliner}'s component brackets or system
	 * marks.
	 * @param form a top-level form
	 * @return {@code true} for {@code %asdf-component}, {@code %asdf-component-end} and
	 * {@code %asdf-system-op}
	 */
	public static boolean isComponentMarker(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol op
				&& (LispNames.ASDF_COMPONENT.equals(op.name()) || LispNames.ASDF_COMPONENT_END.equals(op.name())
						|| LispNames.ASDF_SYSTEM_OP.equals(op.name()));
	}

	/**
	 * The program without its component brackets and system marks -- what a program that
	 * defines no {@code asdf:perform} method compiles from.
	 * @param program the top-level forms
	 * @return the forms, {@code program} itself when it carries no bracket
	 */
	public static List<LispVal> stripComponentMarkers(List<LispVal> program) {
		if (program.stream().noneMatch(SplicedComponentGroups::isComponentMarker)) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(program.size());
		for (LispVal form : program) {
			if (!isComponentMarker(form)) {
				out.add(form);
			}
		}
		return out;
	}

	/**
	 * Lowers the component brackets and system marks (see the class comment).
	 * @param program the top-level forms, before package resolution
	 * @param refusal what a backend that cannot run a top-level group reports when the
	 * program would need one, or {@code null} for a backend that runs them
	 * @return the program with every bracket lowered or dropped
	 */
	public static List<LispVal> lower(List<LispVal> program, @Nullable String refusal) {
		if (program.stream().noneMatch(SplicedComponentGroups::isComponentMarker)) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(program.size());
		boolean performDefined = false;
		boolean runtimeChecked = false;
		// The open brackets, innermost last: a group number, or -1 for one dropped.
		Deque<Integer> open = new ArrayDeque<>();
		int nextGroup = 0;
		for (LispVal form : program) {
			if (!isComponentMarker(form)) {
				out.add(form);
				performDefined = performDefined || definesPerformMethod(form);
				continue;
			}
			List<LispVal> parts = ((LispCons) form).toList();
			String op = ((LispSymbol) parts.get(0)).name();
			if (LispNames.ASDF_COMPONENT_END.equals(op)) {
				int group = open.isEmpty() ? -1 : open.removeLast();
				if (group >= 0) {
					out.add(list(clUser(LispNames.TOP_GROUP_END), new LispInteger(group)));
				}
				continue;
			}
			if (!performDefined) {
				if (LispNames.ASDF_COMPONENT.equals(op)) {
					open.addLast(-1);
				}
				continue;
			}
			if (refusal != null) {
				throw new UnsupportedOperationException(refusal);
			}
			if (!runtimeChecked) {
				// The runtime is spliced ahead of the program whenever a definition is
				// (AsdfRuntimeLibrary.process); this only fails for a method no scan
				// before expansion could see.
				if (program.stream().noneMatch(SplicedComponentGroups::definesPerformComponent)) {
					throw new IllegalStateException("an asdf:perform method is defined but the ASDF runtime was not"
							+ " spliced: spell the generic asdf:perform where the method is defined");
				}
				runtimeChecked = true;
			}
			if (LispNames.ASDF_SYSTEM_OP.equals(op)) {
				out.add(list(clUser(PERFORM_SYSTEM), parts.get(1), parts.get(2)));
				continue;
			}
			int group = nextGroup++;
			open.addLast(group);
			LispVal thunk = list(new LispSymbol(PackageRegistry.qualify(LispNames.CL_PKG, LispNames.LAMBDA)),
					LispNil.INSTANCE, list(clUser(LispNames.RUN_TOP_GROUP), new LispInteger(group)));
			out.add(list(clUser(LOAD_SPLICED), parts.get(1), parts.get(2), thunk));
			out.add(list(clUser(LispNames.TOP_GROUP), new LispInteger(group)));
		}
		return out;
	}

	private static boolean definesPerformComponent(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol op
				&& LispNames.DEFUN.equals(member(op)) && cons.cdr() instanceof LispCons rest
				&& rest.car() instanceof LispSymbol name && PERFORM_COMPONENT.equals(member(name));
	}

	/**
	 * The group number a resolved {@code (%top-group N)} opens, or {@code null}.
	 * @param form a top-level form, after package resolution
	 * @return the group number, or {@code null} when the form is not the marker
	 */
	public static @Nullable Integer groupOpened(LispVal form) {
		return groupMarker(form, LispNames.TOP_GROUP);
	}

	/**
	 * The group number a resolved {@code (%top-group-end N)} closes, or {@code null}.
	 * @param form a top-level form, after package resolution
	 * @return the group number, or {@code null} when the form is not the marker
	 */
	public static @Nullable Integer groupClosed(LispVal form) {
		return groupMarker(form, LispNames.TOP_GROUP_END);
	}

	/**
	 * The group number a {@code (%run-top-group N)} form runs.
	 * @param form the form, its operator already known to be {@code %run-top-group}
	 * @return the group number
	 */
	public static int runTopGroupIndex(LispCons form) {
		Integer group = groupMarker(form, LispNames.RUN_TOP_GROUP);
		if (group == null) {
			throw new IllegalStateException("malformed " + LispNames.RUN_TOP_GROUP + ": " + form.print());
		}
		return group;
	}

	private static @Nullable Integer groupMarker(LispVal form, String marker) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op && marker.equals(op.name())
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispInteger n
				&& rest.cdr() instanceof LispNil) {
			return (int) n.value();
		}
		return null;
	}

	private static LispSymbol clUser(String name) {
		return new LispSymbol(PackageRegistry.qualifyInternal(LispNames.CL_USER_PKG, name));
	}

	private static LispVal list(LispVal... items) {
		LispVal tail = LispNil.INSTANCE;
		for (int i = items.length - 1; i >= 0; i--) {
			tail = new LispCons(items[i], tail);
		}
		return tail;
	}

	/**
	 * The {@code (%asdf-component "SYSTEM" INDEX)} bracket {@code LoadInliner} opens a
	 * spliced component with.
	 * @param system the system's name
	 * @param index the component's position in the system's load order
	 * @return the marker form
	 */
	public static LispVal componentMarker(String system, int index) {
		return list(new LispSymbol(LispNames.ASDF_COMPONENT), new LispString(system), new LispInteger(index));
	}

	/**
	 * The {@code (%asdf-component-end)} closing the innermost component bracket.
	 * @return the marker form
	 */
	public static LispVal componentEndMarker() {
		return list(new LispSymbol(LispNames.ASDF_COMPONENT_END));
	}

	/**
	 * The {@code (%asdf-system-op "SYSTEM" :before|:after)} mark of a system's own
	 * actions.
	 * @param system the system's name
	 * @param before {@code true} for the mark ahead of the components (prepare-op),
	 * {@code false} for the one behind them (compile-op, load-op)
	 * @return the marker form
	 */
	public static LispVal systemOpMarker(String system, boolean before) {
		return list(new LispSymbol(LispNames.ASDF_SYSTEM_OP), new LispString(system),
				new LispSymbol(before ? ":BEFORE" : ":AFTER"));
	}

}
