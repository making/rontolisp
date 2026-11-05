package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.macro.LispMacroExpander;
import org.jspecify.annotations.Nullable;

/**
 * The compile paths' check that a stream operator's argument is a stream: a call site
 * whose stream argument is not a literal designator gets it wrapped in
 * {@code (%check-stream x 'op designator-p)}, which answers {@code x} or signals
 * {@code op}'s {@code STREAM} type-error ({@code .kb/error-handling.md}, "A wrong-type
 * argument names its operator"). A binding of a standard stream variable is checked the
 * same way, which is when SBCL's declared type refuses it, so a stream-less call needs no
 * check.
 *
 * <p>
 * It runs on the resolved program, so {@code foo::read} is no {@code read}, and after the
 * front end's Gray and pushback rewrites, whose helpers stand in for the operators. A
 * {@code %}-named top-level defun is left alone: an internal helper's stream was checked
 * where its caller named it.
 *
 * <p>
 * {@code format} also takes a string with a fill pointer, and a {@code write-string} of
 * its text would not. In a program that can hold one, which the prelude's splice of
 * {@code %format-write} says, a computed destination goes through
 * {@link #formatThroughDestination} instead ({@code .kb/format.md}, "A string with a fill
 * pointer").
 */
public final class StreamOperandChecks {

	private StreamOperandChecks() {
	}

	/**
	 * The operator a call head stands for and where its stream argument is.
	 *
	 * @param operator the reported name
	 * @param streamIndex the stream argument's index in the call form
	 * @param designator whether nil designates a standard stream rather than being
	 * refused
	 */
	private record Site(String operator, int streamIndex, boolean designator) {
	}

	private static final Map<String, Site> SITES = sites();

	private static final Map<String, Site> CHECKED_OPERATORS = plainOperators();

	private static final Set<String> STANDARD_STREAM_VARS = Set.of(LispNames.STANDARD_OUTPUT_VAR,
			LispNames.STANDARD_INPUT_VAR, LispNames.ERROR_OUTPUT_VAR, LispNames.TRACE_OUTPUT_VAR,
			LispNames.DEBUG_IO_VAR, LispNames.QUERY_IO_VAR, LispNames.TERMINAL_IO_VAR);

	private static final Set<String> DEFINERS = Set.of(LispNames.DEFUN, LispNames.DEFGENERIC, LispNames.DEFMETHOD,
			LispNames.DEFMACRO);

	private static Map<String, Site> plainOperators() {
		Map<String, Site> table = new HashMap<>();
		// nil designates a standard stream for the binary operators too, here where SBCL
		// refuses it (.kb/read-load-streams.md, "Binary streams and binary standard
		// I/O").
		for (String op : List.of(LispNames.WRITE_CHAR, LispNames.WRITE_STRING, LispNames.WRITE_LINE, LispNames.PRINC,
				LispNames.PRIN1, LispNames.PRINT, LispNames.UNREAD_CHAR, LispNames.PEEK_CHAR, LispNames.WRITE_BYTE,
				LispNames.READ_SEQUENCE, LispNames.WRITE_SEQUENCE)) {
			table.put(op, new Site(op, 2, true));
		}
		for (String op : List.of(LispNames.TERPRI, LispNames.FRESH_LINE, LispNames.FORCE_OUTPUT,
				LispNames.FINISH_OUTPUT, LispNames.CLEAR_OUTPUT, LispNames.FORMAT, LispNames.READ_CHAR,
				LispNames.READ_CHAR_NO_HANG, LispNames.READ_LINE, LispNames.LISTEN, LispNames.READ_BYTE)) {
			table.put(op, new Site(op, 1, true));
		}
		for (String op : List.of(LispNames.FILE_POSITION, LispNames.FILE_LENGTH, LispNames.INPUT_STREAM_P,
				LispNames.OUTPUT_STREAM_P, LispNames.OPEN_STREAM_P, LispNames.STREAM_ELEMENT_TYPE)) {
			table.put(op, new Site(op, 1, false));
		}
		return Map.copyOf(table);
	}

	private static Map<String, Site> sites() {
		Map<String, Site> table = new HashMap<>(plainOperators());
		helper(table, "%GRAY-WRITE-STRING-DISPATCH", LispNames.WRITE_STRING);
		helper(table, "%GRAY-WRITE-STRING-BOUNDS-DISPATCH", LispNames.WRITE_STRING);
		helper(table, "%GRAY-WRITE-CHAR-DISPATCH", LispNames.WRITE_CHAR);
		helper(table, "%GRAY-WRITE-LINE-DISPATCH", LispNames.WRITE_LINE);
		helper(table, "%GRAY-WRITE-LINE-BOUNDS-DISPATCH", LispNames.WRITE_LINE);
		helper(table, "%GRAY-PRINC-DISPATCH", LispNames.PRINC);
		helper(table, "%GRAY-PRIN1-DISPATCH", LispNames.PRIN1);
		helper(table, "%GRAY-PRINT-DISPATCH", LispNames.PRINT);
		helper(table, "%GRAY-TERPRI-DISPATCH", LispNames.TERPRI);
		helper(table, "%GRAY-FRESH-LINE-DISPATCH", LispNames.FRESH_LINE);
		helper(table, "%GRAY-FORCE-OUTPUT-DISPATCH", LispNames.FORCE_OUTPUT);
		helper(table, "%GRAY-FINISH-OUTPUT-DISPATCH", LispNames.FINISH_OUTPUT);
		helper(table, "%GRAY-CLEAR-OUTPUT-DISPATCH", LispNames.CLEAR_OUTPUT);
		helper(table, "%GRAY-WRITE-BYTE-DISPATCH", LispNames.WRITE_BYTE);
		helper(table, "%GRAY-READ-BYTE-DISPATCH", LispNames.READ_BYTE);
		helper(table, "%GRAY-READ-CHAR-DISPATCH", LispNames.READ_CHAR);
		helper(table, "%GRAY-READ-CHAR-NO-HANG-DISPATCH", LispNames.READ_CHAR_NO_HANG);
		helper(table, "%GRAY-READ-LINE-DISPATCH", LispNames.READ_LINE);
		helper(table, "%GRAY-PEEK-CHAR-DISPATCH", LispNames.PEEK_CHAR);
		helper(table, "%GRAY-UNREAD-CHAR-DISPATCH", LispNames.UNREAD_CHAR);
		helper(table, "%GRAY-LISTEN-DISPATCH", LispNames.LISTEN);
		helper(table, "%GRAY-READ-SEQUENCE-DISPATCH", LispNames.READ_SEQUENCE);
		helper(table, "%GRAY-WRITE-SEQUENCE-DISPATCH", LispNames.WRITE_SEQUENCE);
		helper(table, "%GRAY-FILE-POSITION-DISPATCH", LispNames.FILE_POSITION);
		helper(table, "%GRAY-FILE-POSITION-SET-DISPATCH", LispNames.FILE_POSITION);
		helper(table, "%GRAY-BROADCAST-FILE-LENGTH", LispNames.FILE_LENGTH);
		helper(table, "%GRAY-INPUT-STREAM-P-DISPATCH", LispNames.INPUT_STREAM_P);
		helper(table, "%GRAY-OUTPUT-STREAM-P-DISPATCH", LispNames.OUTPUT_STREAM_P);
		helper(table, "%GRAY-OPEN-STREAM-P-DISPATCH", LispNames.OPEN_STREAM_P);
		helper(table, "%GRAY-STREAM-ELEMENT-TYPE-DISPATCH", LispNames.STREAM_ELEMENT_TYPE);
		helper(table, LispNames.UNREAD_READ_CHAR_INTERNAL, LispNames.READ_CHAR);
		helper(table, LispNames.UNREAD_PEEK_CHAR_INTERNAL, LispNames.PEEK_CHAR);
		helper(table, LispNames.UNREAD_READ_LINE_INTERNAL, LispNames.READ_LINE);
		helper(table, LispNames.UNREAD_LISTEN_INTERNAL, LispNames.LISTEN);
		helper(table, LispNames.UNREAD_CHAR_PUSH_INTERNAL, LispNames.UNREAD_CHAR);
		helper(table, LispNames.UNREAD_FILE_POSITION_INTERNAL, LispNames.FILE_POSITION);
		helper(table, LispNames.UNREAD_FILE_POSITION_SET_INTERNAL, LispNames.FILE_POSITION);
		return Map.copyOf(table);
	}

	// A helper keeps the shape of the operator it stands for, under its internal name.
	private static void helper(Map<String, Site> table, String helper, String operator) {
		Site site = Objects.requireNonNull(table.get(operator));
		table.put(PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, helper),
				new Site(operator, site.streamIndex(), site.designator()));
	}

	/**
	 * The stream operators the interpreter checks at its function seam, each to whether
	 * nil designates a standard stream.
	 * @return the operator names to their designator flags
	 */
	public static Map<String, Boolean> designatorByOperator() {
		Map<String, Boolean> flags = new HashMap<>();
		CHECKED_OPERATORS.forEach((op, site) -> flags.put(op, site.designator()));
		return Map.copyOf(flags);
	}

	/**
	 * The argument index of a checked operator's stream.
	 * @param operator the operator
	 * @return the index among the arguments, the first being 0
	 */
	public static int streamArgument(String operator) {
		return Objects.requireNonNull(CHECKED_OPERATORS.get(operator)).streamIndex() - 1;
	}

	/**
	 * Whether the symbol names a standard stream variable, whose binding is checked.
	 * @param name the variable's symbol name
	 * @return whether it does
	 */
	public static boolean isStandardStreamVariable(String name) {
		return STANDARD_STREAM_VARS.contains(name);
	}

	/**
	 * A call whose lowering writes through another operator, with its stream checked
	 * first, so its report names the operator called: the interpreter's expansions of
	 * {@code format} and the print family when a condition report or a method routes
	 * them.
	 * @param call the call
	 * @return the call to expand
	 */
	public static LispCons checkedCall(LispCons call) {
		if (call.car() instanceof LispSymbol head && call.isProperList()) {
			Site site = CHECKED_OPERATORS.get(head.name());
			List<LispVal> parts = call.toList();
			if (site != null && parts.size() > site.streamIndex()
					&& !needsNoCheck(parts.get(site.streamIndex()), site.designator())) {
				List<LispVal> out = new ArrayList<>(parts);
				out.set(site.streamIndex(), checked(parts.get(site.streamIndex()), site.operator(), site.designator()));
				return (LispCons) list(out.toArray(LispVal[]::new));
			}
		}
		return call;
	}

	/**
	 * The check of a {@code format} destination, for a rewrite that binds it and writes
	 * through {@code write-string}.
	 * @param destination the destination's form
	 * @return the form
	 */
	public static LispVal checkedFormatDestination(LispVal destination) {
		return needsNoCheck(destination, true) ? destination : checked(destination, LispNames.FORMAT, true);
	}

	/**
	 * Whether a {@code format} destination's form can hold a value that takes a run-time
	 * check.
	 * @param destination the destination's form
	 * @return whether it can
	 */
	public static boolean formatDestinationIsComputed(LispVal destination) {
		return !needsNoCheck(destination, true);
	}

	/**
	 * Whether the prelude spliced {@code %format-write}, so {@code format} can meet a
	 * string with a fill pointer in this program.
	 * @param program the top-level forms
	 * @return whether it did
	 */
	public static boolean formatTakesStrings(List<LispVal> program) {
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& LispNames.DEFUN.equals(head.name()) && cons.cdr() instanceof LispCons args
					&& args.car() instanceof LispSymbol name && LispNames.FORMAT_WRITE_INTERNAL.equals(name.name())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A {@code format} call with a computed destination, as what takes a string with a
	 * fill pointer too: {@code (%format-write (%format-destination D) (format nil C
	 * ARGS...) FRESH)}, {@code FRESH} what the destination is asked of a {@code ~&} that
	 * opens the control. The destination is evaluated and checked first, as everywhere,
	 * and the text is written once, after the arguments. Any other call is returned as it
	 * is.
	 * @param call the call
	 * @return the call to evaluate
	 */
	public static LispCons formatThroughDestination(LispCons call) {
		if (call.car() instanceof LispSymbol head && LispNames.FORMAT.equals(head.name()) && call.isProperList()) {
			List<LispVal> parts = call.toList();
			if (parts.size() >= 3 && formatDestinationIsComputed(parts.get(1))) {
				LispVal distributed = LispMacroExpander.distributeFormat(call);
				return (LispCons) (distributed != null ? distributed : formatThroughDestination(call, parts));
			}
		}
		return call;
	}

	/**
	 * {@link #formatThroughDestination(LispCons)} of a call's parts, its subforms already
	 * rewritten by the caller.
	 * @param call the call they replace
	 * @param parts the head, the destination, the control, undistributed, and the
	 * arguments
	 * @return the replacing form
	 */
	public static LispVal formatThroughDestination(LispCons call, List<LispVal> parts) {
		List<LispVal> render = new ArrayList<>(parts);
		render.set(1, LispNil.INSTANCE);
		LispVal destination = list(new LispSymbol(LispNames.FORMAT_DESTINATION_INTERNAL), parts.get(1));
		if (parts.get(2) instanceof LispString) {
			LispVal startsFresh = LispMacroExpander.formatStartsFresh(parts.get(2));
			return SourceProvenance.inherit(call,
					list(new LispSymbol(LispNames.FORMAT_WRITE_INTERNAL), destination,
							SourceProvenance.inherit(call, list(render.toArray(LispVal[]::new))),
							startsFresh == null ? LispNil.INSTANCE : startsFresh));
		}
		// A computed control is held, evaluated after the destination.
		LispSymbol held = new LispSymbol(FORMAT_DESTINATION_VAR);
		LispSymbol control = new LispSymbol(FORMAT_CONTROL_VAR);
		render.set(2, control);
		return SourceProvenance.inherit(call,
				list(new LispSymbol(LispNames.LET_STAR), list(list(held, destination), list(control, parts.get(2))),
						list(new LispSymbol(LispNames.FORMAT_WRITE_INTERNAL), held,
								SourceProvenance.inherit(call, list(render.toArray(LispVal[]::new))),
								list(new LispSymbol(LispNames.FMT_STARTS_FRESH), control))));
	}

	private static final String FORMAT_DESTINATION_VAR = "__format_destination";

	private static final String FORMAT_CONTROL_VAR = "__format_held_control";

	/**
	 * Adds the checks to a program.
	 * @param program the resolved top-level forms
	 * @return the forms with their stream arguments checked
	 */
	public static List<LispVal> process(List<LispVal> program) {
		Set<String> defined = new HashSet<>();
		for (LispVal form : program) {
			collectDefined(form, defined);
		}
		Rewriter rewriter = new Rewriter(Set.copyOf(defined), formatTakesStrings(program));
		List<LispVal> out = new ArrayList<>(program.size());
		for (LispVal form : program) {
			out.add(isInternalDefun(form) ? form : CallSiteWalk.walk(form, rewriter));
		}
		return out;
	}

	private static void collectDefined(LispVal form, Set<String> defined) {
		LispVal rest = form;
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head && DEFINERS.contains(head.name())
				&& cons.cdr() instanceof LispCons args && args.car() instanceof LispSymbol name
				&& CHECKED_OPERATORS.containsKey(name.name())) {
			defined.add(name.name());
		}
		while (rest instanceof LispCons cons) {
			collectDefined(cons.car(), defined);
			rest = cons.cdr();
		}
	}

	private static boolean isInternalDefun(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& LispNames.DEFUN.equals(head.name()) && cons.cdr() instanceof LispCons args
				&& args.car() instanceof LispSymbol name) {
			PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(name.name());
			return (qualified == null ? name.name() : qualified.member()).startsWith("%");
		}
		return false;
	}

	/**
	 * The check of one value.
	 * @param value the stream argument's form
	 * @param operator the name the report gives
	 * @param designator whether nil passes
	 * @return the form
	 */
	private static LispVal checked(LispVal value, String operator, boolean designator) {
		return list(new LispSymbol(LispNames.CHECK_STREAM_INTERNAL), value,
				list(new LispSymbol(LispNames.UNSPELLED_QUOTE), new LispSymbol(operator)),
				designator ? LispTrue.INSTANCE : LispNil.INSTANCE);
	}

	// A form that cannot hold anything but what a check would pass: a call of the check
	// itself, t, a read of a standard stream variable, whose bindings are checked, and,
	// where nil designates a stream, nil. A keyword is what a stream-less keyword call
	// spells there and is the lowering's to refuse.
	private static boolean needsNoCheck(LispVal value, boolean designator) {
		if (value instanceof LispTrue || (designator && value instanceof LispNil)) {
			return true;
		}
		if (value instanceof LispSymbol symbol) {
			return symbol.name().startsWith(":") || STANDARD_STREAM_VARS.contains(symbol.name());
		}
		return value instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& LispNames.CHECK_STREAM_INTERNAL.equals(head.name());
	}

	private static LispVal list(LispVal... elements) {
		LispVal tail = LispNil.INSTANCE;
		for (int i = elements.length - 1; i >= 0; i--) {
			tail = new LispCons(elements[i], tail);
		}
		return tail;
	}

	private record Rewriter(Set<String> shadowed, boolean formatTakesStrings) implements CallSiteWalk.Rewriter {

		@Override
		public @Nullable LispVal call(LispCons form, LispSymbol head) {
			String name = head.name();
			if (LispNames.FLET.equals(name) || LispNames.LABELS.equals(name) || LispNames.MACROLET.equals(name)) {
				return this.withLocalDefinitions(form);
			}
			if (LispNames.LET.equals(name) || LispNames.LET_STAR.equals(name)) {
				return this.withCheckedBindings(form);
			}
			if (LispNames.SETQ.equals(name) || LispNames.SETF.equals(name)) {
				return this.withCheckedAssignments(form);
			}
			if (LispNames.PRINT_UNREADABLE_OBJECT.equals(name)) {
				return this.withCheckedObjectStream(form);
			}
			Site site = SITES.get(name);
			if (site == null || this.shadowed.contains(name)) {
				return null;
			}
			List<LispVal> parts = form.toList();
			if (parts.size() <= site.streamIndex()) {
				return null;
			}
			LispVal stream = parts.get(site.streamIndex());
			if (needsNoCheck(stream, site.designator())) {
				return null;
			}
			if (this.formatTakesStrings && LispNames.FORMAT.equals(name)) {
				LispVal distributed = LispMacroExpander.distributeFormat(form);
				if (distributed != null) {
					return CallSiteWalk.walk(distributed, this);
				}
			}
			List<LispVal> out = new ArrayList<>(parts.size());
			out.add(head);
			for (int i = 1; i < parts.size(); i++) {
				LispVal walked = CallSiteWalk.walk(parts.get(i), this);
				out.add(walked);
			}
			if (this.formatTakesStrings && LispNames.FORMAT.equals(name) && out.size() >= 3) {
				return formatThroughDestination(form, out);
			}
			out.set(site.streamIndex(), checked(out.get(site.streamIndex()), site.operator(), site.designator()));
			return LispCons.rebuiltList(form, out);
		}

		// (print-unreadable-object (object stream ...) body...): the stream is first
		// written with write-string, and the report says so, as the interpreter's does.
		private @Nullable LispVal withCheckedObjectStream(LispCons form) {
			List<LispVal> parts = form.toList();
			if (parts.size() < 2 || !(parts.get(1) instanceof LispCons specCons) || !specCons.isProperList()) {
				return null;
			}
			List<LispVal> spec = specCons.toList();
			if (spec.size() < 2 || needsNoCheck(spec.get(1), true)) {
				return null;
			}
			List<LispVal> checkedSpec = new ArrayList<>(spec);
			checkedSpec.set(1, checked(spec.get(1), LispNames.WRITE_STRING, true));
			List<LispVal> out = new ArrayList<>(parts);
			out.set(1, list(checkedSpec.toArray(LispVal[]::new)));
			return CallSiteWalk.walk(LispCons.rebuiltList(form, out), this);
		}

		// A local function named like an operator is that function within its form.
		private @Nullable LispVal withLocalDefinitions(LispCons form) {
			List<LispVal> parts = form.toList();
			if (parts.size() < 2) {
				return null;
			}
			Set<String> names = new HashSet<>();
			LispVal rest = parts.get(1);
			while (rest instanceof LispCons cons) {
				if (cons.car() instanceof LispCons def && def.car() instanceof LispSymbol local
						&& SITES.containsKey(local.name()) && !this.shadowed.contains(local.name())) {
					names.add(local.name());
				}
				rest = cons.cdr();
			}
			if (names.isEmpty()) {
				return null;
			}
			names.addAll(this.shadowed);
			return CallSiteWalk.walk(form, new Rewriter(Set.copyOf(names), this.formatTakesStrings));
		}

		private @Nullable LispVal withCheckedBindings(LispCons form) {
			List<LispVal> parts = form.toList();
			if (parts.size() < 2 || !(parts.get(1) instanceof LispCons bindings) || !bindings.isProperList()) {
				return null;
			}
			boolean changed = false;
			List<LispVal> out = new ArrayList<>();
			for (LispVal binding : bindings.toList()) {
				if (binding instanceof LispCons pair && pair.isProperList() && pair.car() instanceof LispSymbol variable
						&& STANDARD_STREAM_VARS.contains(variable.name()) && pair.cdr() instanceof LispCons value
						&& !needsNoCheck(value.car(), false)) {
					out.add(list(variable, checked(value.car(), variable.name(), false)));
					changed = true;
				}
				else {
					out.add(binding);
				}
			}
			if (!changed) {
				return null;
			}
			List<LispVal> rebuilt = new ArrayList<>(parts);
			rebuilt.set(1, list(out.toArray(LispVal[]::new)));
			return CallSiteWalk.walk(LispCons.rebuiltList(form, rebuilt), this);
		}

		private @Nullable LispVal withCheckedAssignments(LispCons form) {
			List<LispVal> parts = form.toList();
			boolean changed = false;
			List<LispVal> out = new ArrayList<>(parts);
			for (int i = 1; i + 1 < parts.size(); i += 2) {
				if (parts.get(i) instanceof LispSymbol variable && STANDARD_STREAM_VARS.contains(variable.name())
						&& !needsNoCheck(parts.get(i + 1), false)) {
					out.set(i + 1, checked(parts.get(i + 1), variable.name(), false));
					changed = true;
				}
			}
			return changed ? CallSiteWalk.walk(LispCons.rebuiltList(form, out), this) : null;
		}

	}

}
