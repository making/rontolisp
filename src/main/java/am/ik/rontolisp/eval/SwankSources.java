package am.ik.rontolisp.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.CallSiteWalk;
import am.ik.rontolisp.eval.CommonLispSwank.Location;

/**
 * Where the code an editor compiled defines things and what it calls, binds, sets,
 * references and expands, for SWANK's {@code find-definitions-for-emacs} ({@code M-.})
 * and {@code xref}. A compile request ({@link CommonLispSwank#compileString},
 * {@link CommonLispSwank#compileFile}) hands its forms as resolved, once they ran, to
 * {@link #record}, whose walk also finds the calls of functions nothing defines. A
 * definition recompiled replaces what the last compile recorded for it.
 *
 * <p>
 * A definition the editor did not compile -- loaded, or typed at the REPL -- is found
 * through the evaluator's own record of a {@code defun}, {@code defmacro} or
 * {@code defgeneric} ({@link LispEvaluator#definition}), at the file and line its form
 * was read from when it came from a file. Built-in operators have no definitions.
 */
public final class SwankSources {

	/** How many characters of a file a definition's snippet carries, as SWANK's. */
	private static final int SNIPPET_LENGTH = 256;

	/** The macros whose every argument is a form, which the walk may look into. */
	private static final Set<String> FORM_MACROS = Set.of(LispNames.WHEN, LispNames.UNLESS, LispNames.AND, LispNames.OR,
			LispNames.PROG1, LispNames.PROG2, "RETURN", LispNames.DEFVAR, LispNames.DEFPARAMETER, LispNames.DEFCONSTANT,
			LispNames.IGNORE_ERRORS, LispNames.TIME, LispNames.LOCALLY, LispNames.MULTIPLE_VALUE_LIST,
			LispNames.MULTIPLE_VALUE_CALL, LispNames.NTH_VALUE, LispNames.MULTIPLE_VALUE_PROG1,
			LispNames.WITH_STANDARD_IO_SYNTAX, LispNames.PSETQ, LispNames.ERROR, LispNames.CERROR, LispNames.WARN,
			LispNames.SIGNAL, LispNames.FORMAT, LispNames.MAKE_INSTANCE, LispNames.SLOT_VALUE, LispNames.TYPEP,
			LispNames.COMPLEMENT, LispNames.MAKE_CONDITION, LispNames.EVAL_WHEN);

	/**
	 * The built-in operators whose shape {@link CallSiteWalk} knows, or whose arguments
	 * are all forms: the walk looks into these and no other built-in operator.
	 */
	private static final Set<String> WALKED_OPERATORS = walkedOperators();

	private static Set<String> walkedOperators() {
		Set<String> names = new java.util.HashSet<>(FORM_MACROS);
		names.addAll(List.of(LispNames.IF, LispNames.PROGN, LispNames.SETQ, LispNames.BLOCK, LispNames.RETURN_FROM,
				LispNames.CATCH, LispNames.THROW, LispNames.UNWIND_PROTECT, "TAGBODY", "PROGV",
				LispNames.LOAD_TIME_VALUE, LispNames.QUOTE, LispNames.DEFMACRO, LispNames.DECLARE, LispNames.DEFUN,
				LispNames.LAMBDA, LispNames.DEFMETHOD, LispNames.LET, LispNames.LET_STAR, LispNames.PROG,
				LispNames.PROG_STAR, LispNames.SYMBOL_MACROLET, LispNames.HANDLER_BIND, LispNames.RESTART_BIND,
				LispNames.DO, LispNames.DO_STAR, LispNames.DOLIST, LispNames.DOTIMES, LispNames.DO_SYMBOLS,
				LispNames.DO_EXTERNAL_SYMBOLS, LispNames.DO_ALL_SYMBOLS, LispNames.WITH_OPEN_FILE,
				LispNames.WITH_OPEN_STREAM, LispNames.WITH_OUTPUT_TO_STRING, LispNames.WITH_INPUT_FROM_STRING,
				LispNames.MULTIPLE_VALUE_BIND, LispNames.MULTIPLE_VALUE_SETQ, LispNames.DESTRUCTURING_BIND,
				LispNames.WITH_SLOTS, LispNames.WITH_ACCESSORS, LispNames.THE, LispNames.CASE, LispNames.ECASE,
				LispNames.CCASE, LispNames.TYPECASE, LispNames.ETYPECASE, LispNames.CTYPECASE, LispNames.HANDLER_CASE,
				LispNames.RESTART_CASE, LispNames.COND, LispNames.SETF, LispNames.PSETF, LispNames.INCF, LispNames.DECF,
				LispNames.POP, LispNames.REMF, LispNames.PUSH, LispNames.PUSHNEW, LispNames.ROTATEF, LispNames.SHIFTF,
				LispNames.DEFCLASS, LispNames.DEFINE_CONDITION, LispNames.DEFSTRUCT, LispNames.DEFGENERIC,
				LispNames.LOOP));
		return Set.copyOf(names);
	}

	/** What an {@code xref} asks about a name. */
	private enum Use {

		/** A call of a function, or a {@code #'} of one. */
		CALL,

		/** A special variable read. */
		REFERENCE,

		/** A special variable bound. */
		BIND,

		/** A special variable assigned. */
		SET,

		/** A user macro's call. */
		MACROEXPAND

	}

	/**
	 * A place in compiled code.
	 *
	 * @param location where
	 * @param snippet the text there, which the editor searches for when the position is
	 * off, or null
	 */
	public record Place(Location location, @Nullable String snippet) {
	}

	/**
	 * A definition.
	 *
	 * @param operator the defining operator's name ({@code DEFUN}, {@code DEFMETHOD} ...)
	 * @param name the defined name: a symbol, a {@code (setf name)} list, or a string for
	 * a package
	 * @param qualifiers a method's qualifiers
	 * @param specializers a method's specializers, or a generic function's lambda list
	 * @param place where it is
	 */
	private record Def(String operator, LispVal name, List<LispVal> qualifiers, @Nullable List<LispVal> specializers,
			Place place) {

		boolean method() {
			return LispNames.DEFMETHOD.equals(this.operator);
		}

	}

	/**
	 * A use of a name inside a definition.
	 *
	 * @param use what kind of use
	 * @param target the used name's key ({@link #key})
	 * @param container the definition it is in
	 * @param place where it is
	 */
	private record Ref(Use use, String target, Def container, Place place) {
	}

	/**
	 * One {@code find-definitions-for-emacs} or {@code xref} entry.
	 *
	 * @param label the definition's or the user's description, as SWANK prints it
	 * @param place where it is, or null when that is unknown
	 * @param error why there is no place, when there is none
	 */
	public record Entry(String label, @Nullable Place place, @Nullable String error) {
	}

	private final CommonLispSwank lisp;

	private final LispEvaluator evaluator;

	/** Each name's definitions the editor compiled, by the definition's key. */
	private final Map<String, Map<String, Def>> definitions = new LinkedHashMap<>();

	/** The uses in each definition the editor compiled, by the definition's key. */
	private final Map<String, List<Ref>> uses = new LinkedHashMap<>();

	SwankSources(CommonLispSwank lisp, LispEvaluator evaluator) {
		this.lisp = lisp;
		this.evaluator = evaluator;
	}

	// ------------------------------------------------------------------------------------
	// Recording

	/**
	 * One compiled text's top-level forms, as resolved, with where each starts.
	 *
	 * @param code the text
	 * @param forms the forms
	 * @param starts each form's offset in the text
	 * @param place the location of an offset in the text
	 * @param buffer whether the text is a buffer's (a snippet is then the datum at the
	 * place) rather than a file's (the next {@value #SNIPPET_LENGTH} characters)
	 */
	record Compiled(String code, List<LispVal> forms, List<Integer> starts, IntFunction<Location> place,
			boolean buffer) {
	}

	/**
	 * Records what a compiled text defines and uses, and answers the functions it calls
	 * that nothing defines, in the order first called: a call is a list in a code
	 * position ({@link CallSiteWalk}) headed by a symbol that names no function, macro or
	 * special operator and binds no local function; a {@code #'name} counts too. A user
	 * macro's call is expanded ({@link LispEvaluator#expandUserMacro}, memoized per call
	 * site, so the expander does not run again when the call runs) and the expansion
	 * walked; a built-in macro whose arguments the walk does not know is not looked into.
	 * @param compiled the text and its forms
	 * @return the canonical names of the undefined functions
	 */
	synchronized List<String> record(Compiled compiled) {
		Set<String> undefined = new LinkedHashSet<>();
		for (int i = 0; i < compiled.forms().size(); i++) {
			int start = compiled.starts().get(i);
			int end = i + 1 < compiled.starts().size() ? compiled.starts().get(i + 1) : compiled.code().length();
			Text text = new Text(compiled, start, Math.max(start, end), compiled.forms().get(i));
			CallSiteWalk.walk(compiled.forms().get(i), new Walk(text, undefined, Set.of(), null, null));
		}
		return List.copyOf(undefined);
	}

	/**
	 * One top-level form's text: where it starts and ends, the conses read from it, and
	 * how far each operator's search has come.
	 */
	private static final class Text {

		final Compiled compiled;

		final int start;

		final int end;

		final LispVal form;

		final Set<LispVal> source = Collections.newSetFromMap(new IdentityHashMap<>());

		final Map<String, Integer> cursors = new HashMap<>();

		Text(Compiled compiled, int start, int end, LispVal form) {
			this.compiled = compiled;
			this.start = start;
			this.end = end;
			this.form = form;
			collect(form);
		}

		private void collect(LispVal value) {
			for (LispVal rest = value; rest instanceof LispCons cell && this.source.add(cell); rest = cell.cdr()) {
				collect(cell.car());
			}
		}

		Place place(int offset) {
			String code = this.compiled.code();
			String snippet = this.compiled.buffer() ? code.substring(offset, datumEnd(code, offset))
					: code.substring(offset, Math.min(code.length(), offset + SNIPPET_LENGTH));
			return new Place(this.compiled.place().apply(offset), snippet);
		}

		/**
		 * Where the next {@code (token} of the form's text starts, past the last one
		 * found for the same token, or -1.
		 */
		int next(String token) {
			String key = token.toLowerCase(Locale.ROOT);
			int from = this.cursors.getOrDefault(key, this.start);
			int at = find(this.compiled.code(), "(" + key, from, this.end);
			if (at >= 0) {
				this.cursors.put(key, at + 1);
			}
			return at;
		}

	}

	/** The walk {@link #record} takes, under the local functions in scope. */
	private final class Walk implements CallSiteWalk.Rewriter {

		private final Text text;

		private final Set<String> undefined;

		private final Set<String> local;

		/** The definition the walk is inside, or null at top level. */
		private final @Nullable Def container;

		/** Where the macro call whose expansion is walked starts, or null. */
		private final @Nullable Integer expansion;

		/** The definition form this walk was made for, which it takes apart by shape. */
		private @Nullable LispVal own;

		Walk(Text text, Set<String> undefined, Set<String> local, @Nullable Def container,
				@Nullable Integer expansion) {
			this.text = text;
			this.undefined = undefined;
			this.local = local;
			this.container = container;
			this.expansion = expansion;
		}

		private Walk with(Set<String> local) {
			return new Walk(this.text, this.undefined, local, this.container, this.expansion);
		}

		@Override
		public @Nullable LispVal call(LispCons form, LispSymbol head) {
			String name = head.name();
			if (form == this.own || name.startsWith(":") || this.local.contains(name)) {
				return null;
			}
			String member = LispSymbol.memberName(name).toUpperCase(Locale.ROOT);
			boolean builtin = PackageRegistry.specialOperatorNames().contains(name);
			if (builtin && LispNames.FUNCTION.equals(member)) {
				if (form.cdr() instanceof LispCons arg && arg.car() instanceof LispSymbol fn) {
					called(fn.name(), this.expansion != null && !this.text.source.contains(form) ? this.expansion
							: tokenOffset(fn));
				}
				return null;
			}
			if (builtin && (LispNames.FLET.equals(member) || LispNames.LABELS.equals(member)
					|| LispNames.MACROLET.equals(member))) {
				return localFunctions(form, !LispNames.FLET.equals(member));
			}
			if (builtin && definition(form, member)) {
				return form;
			}
			if (SwankSources.this.evaluator.isUserMacro(name)) {
				int at = offset(form, member);
				use(Use.MACROEXPAND, name, at);
				expand(form, at);
				return form;
			}
			if (builtin) {
				return WALKED_OPERATORS.contains(member) ? variables(form, member) : form;
			}
			called(name, offset(form, member));
			return null;
		}

		@Override
		public void variable(LispSymbol symbol) {
			if (special(symbol.name())) {
				use(Use.REFERENCE, symbol.name(), this.expansion != null ? this.expansion : tokenOffset(symbol));
			}
		}

		private void called(String name, int at) {
			check(name);
			use(Use.CALL, name, at);
		}

		/** A user macro's call expanded once, memoized, and the expansion walked. */
		private void expand(LispCons form, int at) {
			LispVal expanded;
			try {
				expanded = SwankSources.this.evaluator.expandUserMacro(form);
			}
			catch (RuntimeException ex) {
				return;
			}
			CallSiteWalk.walk(expanded, new Walk(this.text, this.undefined, this.local, this.container, at));
		}

		/**
		 * The bindings and assignments of special variables a built-in form makes; the
		 * walk goes on into it unless it took the form apart itself.
		 */
		private @Nullable LispVal variables(LispCons form, String member) {
			List<LispVal> parts = form.toList();
			switch (member) {
				case LispNames.LET, LispNames.LET_STAR -> {
					if (parts.size() > 1) {
						for (LispVal binding = parts.get(1); binding instanceof LispCons cell; binding = cell.cdr()) {
							LispVal variable = cell.car() instanceof LispCons pair ? pair.car() : cell.car();
							if (variable instanceof LispSymbol symbol && special(symbol.name())) {
								use(Use.BIND, symbol.name(), offset(form, member));
							}
						}
					}
					return null;
				}
				case LispNames.SETQ, LispNames.PSETQ, LispNames.SETF, LispNames.PSETF -> {
					for (int i = 1; i < parts.size(); i++) {
						LispVal part = parts.get(i);
						if (i % 2 == 0) {
							CallSiteWalk.walk(part, this);
						}
						else if (part instanceof LispSymbol symbol) {
							if (special(symbol.name())) {
								use(Use.SET, symbol.name(), offset(form, member));
							}
						}
						else if (part instanceof LispCons place && place.car() instanceof LispSymbol) {
							CallSiteWalk.arguments(place.cdr(), this);
						}
						else {
							CallSiteWalk.walk(part, this);
						}
					}
					return form;
				}
				default -> {
					return null;
				}
			}
		}

		private LispVal localFunctions(LispCons form, boolean recursive) {
			Set<String> names = new LinkedHashSet<>(this.local);
			if (form.cdr() instanceof LispCons rest) {
				for (LispVal def = rest.car(); def instanceof LispCons cell; def = cell.cdr()) {
					if (cell.car() instanceof LispCons binding && binding.car() instanceof LispSymbol name) {
						names.add(name.name());
					}
				}
				Walk inner = with(names);
				for (LispVal def = rest.car(); def instanceof LispCons cell; def = cell.cdr()) {
					if (cell.car() instanceof LispCons binding && binding.cdr() instanceof LispCons lambda) {
						CallSiteWalk.lambda(new LispCons(new LispSymbol(LispNames.LAMBDA), lambda),
								recursive ? inner : this);
					}
				}
				CallSiteWalk.arguments(rest.cdr(), inner);
			}
			return form;
		}

		private void check(String name) {
			if (name.startsWith(":") || this.local.contains(name) || PackageRegistry.isClSymbol(name)
					|| PackageRegistry.isClMemberName(name) || LispSymbol.memberName(name).startsWith("%")
					|| SwankSources.this.evaluator.fbound(name)) {
				return;
			}
			this.undefined.add(name);
		}

		/**
		 * Records what a definition form defines and walks its body inside it; false when
		 * the form defines nothing this records.
		 */
		private boolean definition(LispCons form, String member) {
			List<LispVal> parts = form.toList();
			if (parts.size() < 2) {
				return false;
			}
			LispVal name = parts.get(1);
			switch (member) {
				case LispNames.DEFUN, LispNames.DEFMACRO -> {
					Def def = define(member, name, List.of(), null, form);
					if (def == null) {
						return false;
					}
					inside(def, LispNames.DEFMACRO.equals(member)
							? new LispCons(new LispSymbol(LispNames.LAMBDA), ((LispCons) form.cdr()).cdr()) : form);
					return true;
				}
				case LispNames.DEFMETHOD -> {
					int at = 2;
					List<LispVal> qualifiers = new ArrayList<>();
					while (at < parts.size() && !(parts.get(at) instanceof LispCons)
							&& !(parts.get(at) instanceof LispNil)) {
						qualifiers.add(parts.get(at++));
					}
					Def def = define(member, name, qualifiers,
							at < parts.size() ? specializers(parts.get(at)) : List.of(), form);
					if (def == null) {
						return false;
					}
					inside(def, form);
					return true;
				}
				case LispNames.DEFGENERIC -> {
					define(member, name, List.of(), parts.size() > 2 ? list(parts.get(2)) : List.of(), form);
					return false;
				}
				case LispNames.DEFVAR, LispNames.DEFPARAMETER -> {
					define(LispNames.DEFVAR, name, List.of(), null, form);
					return false;
				}
				case LispNames.DEFCONSTANT, LispNames.DEFTYPE -> {
					define(member, name, List.of(), null, form);
					return false;
				}
				case LispNames.DEFCLASS, LispNames.DEFINE_CONDITION -> {
					Def def = define(member, name, List.of(), null, form);
					if (def != null && parts.size() > 3) {
						accessors(name, parts.get(3));
					}
					return false;
				}
				case LispNames.DEFSTRUCT -> {
					structure(form, name);
					return false;
				}
				case LispNames.DEFPACKAGE -> {
					String pkg = packageName(name);
					if (pkg != null) {
						define(member, LispString.literal(pkg), List.of(), null, form);
					}
					return false;
				}
				default -> {
					return false;
				}
			}
		}

		private void inside(Def def, LispCons form) {
			Walk body = new Walk(this.text, this.undefined, this.local, def, this.expansion);
			body.own = form;
			CallSiteWalk.walk(form, body);
		}

		/** A method lambda list's specializers: each required parameter's class. */
		private List<LispVal> specializers(LispVal lambdaList) {
			List<LispVal> out = new ArrayList<>();
			for (LispVal rest = lambdaList; rest instanceof LispCons cell; rest = cell.cdr()) {
				if (cell.car() instanceof LispSymbol symbol && symbol.name().startsWith("&")) {
					break;
				}
				out.add(cell.car() instanceof LispCons pair && pair.cdr() instanceof LispCons type ? type.car()
						: new LispSymbol("T"));
			}
			return out;
		}

		/** A class's slot accessors, readers and writers: methods at their slots. */
		private void accessors(LispVal className, LispVal slots) {
			for (LispVal rest = slots; rest instanceof LispCons cell; rest = cell.cdr()) {
				if (!(cell.car() instanceof LispCons slot)) {
					continue;
				}
				List<LispVal> options = slot.toList();
				for (int i = 1; i + 1 < options.size(); i += 2) {
					if (!(options.get(i) instanceof LispSymbol key)) {
						continue;
					}
					LispVal fn = options.get(i + 1);
					int at = slotOffset(fn);
					switch (key.name()) {
						case ":ACCESSOR" -> {
							method(fn, List.of(className), at);
							method(list(new LispSymbol(LispNames.SETF), fn), List.of(new LispSymbol("T"), className),
									at);
						}
						case ":READER" -> method(fn, List.of(className), at);
						case ":WRITER" -> method(fn, List.of(new LispSymbol("T"), className), at);
						default -> {
							// an initarg, initform, type, allocation or documentation
						}
					}
				}
			}
		}

		private void method(LispVal name, List<LispVal> specializers, int at) {
			put(new Def(LispNames.DEFMETHOD, name, List.of(), specializers, this.text.place(at)));
		}

		/** Where the slot specification naming an accessor starts. */
		private int slotOffset(LispVal accessor) {
			String code = this.text.compiled.code();
			int found = accessor instanceof LispSymbol symbol
					? findToken(code, LispSymbol.memberName(symbol.name()), this.text.start, this.text.end) : -1;
			if (found < 0) {
				return this.text.start;
			}
			int depth = 0;
			for (int i = found; i > this.text.start; i--) {
				char c = code.charAt(i);
				if (c == ')') {
					depth++;
				}
				else if (c == '(') {
					if (depth == 0) {
						return i;
					}
					depth--;
				}
			}
			return this.text.start;
		}

		/** A structure's type and its constructor, predicate, copier and accessors. */
		private void structure(LispCons form, LispVal spec) {
			LispVal name = spec instanceof LispCons cell ? cell.car() : spec;
			if (!(name instanceof LispSymbol symbol)) {
				return;
			}
			int at = definitionOffset(form, LispNames.DEFSTRUCT);
			Place place = this.text.place(at);
			put(new Def(LispNames.DEFSTRUCT, name, List.of(), null, place));
			String member = LispSymbol.memberName(symbol.name());
			String conc = member + "-";
			List<String> functions = new ArrayList<>();
			boolean constructor = true;
			boolean predicate = true;
			boolean copier = true;
			if (spec instanceof LispCons cell) {
				for (LispVal rest = cell.cdr(); rest instanceof LispCons option; rest = option.cdr()) {
					List<LispVal> parts = option.car() instanceof LispCons list ? list.toList() : List.of(option.car());
					String key = parts.getFirst() instanceof LispSymbol k ? k.name() : "";
					LispVal value = parts.size() > 1 ? parts.get(1) : null;
					String text = value instanceof LispSymbol s ? LispSymbol.memberName(s.name())
							: value instanceof LispString s ? s.value() : null;
					switch (key) {
						case ":CONC-NAME" -> conc = text == null ? "" : text;
						case ":CONSTRUCTOR" -> {
							constructor = parts.size() == 1;
							if (text != null && !(value instanceof LispNil)) {
								functions.add(text);
							}
						}
						case ":PREDICATE" -> {
							predicate = parts.size() == 1;
							if (text != null && !(value instanceof LispNil)) {
								functions.add(text);
							}
						}
						case ":COPIER" -> {
							copier = parts.size() == 1;
							if (text != null && !(value instanceof LispNil)) {
								functions.add(text);
							}
						}
						default -> {
							// :include, :type, :named, :print-function ...
						}
					}
				}
			}
			if (constructor) {
				functions.add("MAKE-" + member);
			}
			if (predicate) {
				functions.add(member + "-P");
			}
			if (copier) {
				functions.add("COPY-" + member);
			}
			List<LispVal> parts = form.toList();
			for (int i = 2; i < parts.size(); i++) {
				LispVal slot = parts.get(i) instanceof LispCons cell ? cell.car() : parts.get(i);
				if (slot instanceof LispSymbol slotName) {
					functions.add(conc + LispSymbol.memberName(slotName.name()));
				}
			}
			for (String function : functions) {
				String spelling = SwankSources.this.lisp.homedLike(symbol.name(), function);
				put(new Def(LispNames.DEFUN, new LispSymbol(spelling), List.of(), null, place));
			}
		}

		private @Nullable Def define(String operator, LispVal name, List<LispVal> qualifiers,
				@Nullable List<LispVal> specializers, LispCons form) {
			if (!(name instanceof LispSymbol) && !(name instanceof LispString)
					&& !(name instanceof LispCons cell && cell.car() instanceof LispSymbol)) {
				return null;
			}
			Def def = new Def(operator, name, qualifiers, specializers,
					this.text.place(definitionOffset(form, LispSymbol.memberName(operator))));
			put(def);
			return def;
		}

		private int definitionOffset(LispCons form, String operator) {
			if (form == this.text.form || this.expansion != null) {
				return this.expansion != null ? this.expansion : this.text.start;
			}
			int at = this.text.next(operator);
			return at < 0 ? this.text.start : at;
		}

		/** Where a form of the source starts, or the macro call it was expanded from. */
		private int offset(LispCons form, String member) {
			if (this.expansion != null && !this.text.source.contains(form)) {
				return this.expansion;
			}
			int at = this.text.next(member);
			return at < 0 ? this.text.start : at;
		}

		private int tokenOffset(LispSymbol symbol) {
			int at = findToken(this.text.compiled.code(), LispSymbol.memberName(symbol.name()), this.text.start,
					this.text.end);
			return at < 0 ? this.text.start : at;
		}

		private void use(Use use, String target, int offset) {
			Def in = this.container;
			if (in == null || offset < 0) {
				return;
			}
			SwankSources.this.uses.computeIfAbsent(key(in), k -> new ArrayList<>())
				.add(new Ref(use, target, in, this.text.place(offset)));
		}

	}

	private boolean special(String name) {
		return !name.startsWith(":") && !PackageRegistry.isClSymbol(name) && !PackageRegistry.isClMemberName(name)
				&& this.evaluator.isSpecialVariable(name);
	}

	private void put(Def def) {
		String key = key(def);
		this.definitions.computeIfAbsent(nameKey(def.name()), k -> new LinkedHashMap<>()).put(key, def);
		// a definition compiled again drops the uses the last compile found in it
		this.uses.remove(key);
	}

	private static String key(Def def) {
		return def.operator() + " " + def.name().print() + " " + def.qualifiers() + " "
				+ (def.method() ? String.valueOf(def.specializers()) : "");
	}

	/** The key a name's definitions are found by: a symbol's or a setf function's. */
	private static String nameKey(LispVal name) {
		return switch (name) {
			case LispSymbol symbol -> symbol.name();
			case LispString pkg -> "#package " + pkg.value();
			case LispCons cell when cell.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol fn ->
				"(SETF " + fn.name() + ")";
			default -> name.print();
		};
	}

	private static @Nullable String packageName(LispVal designator) {
		return switch (designator) {
			case LispSymbol symbol ->
				LispSymbol.memberName(symbol.name().startsWith("#:") ? symbol.name().substring(2) : symbol.name())
					.toUpperCase(Locale.ROOT);
			case LispString string -> string.value();
			default -> null;
		};
	}

	// ------------------------------------------------------------------------------------
	// Queries

	/**
	 * The definitions of what a name names, as SWANK's {@code find-definitions-for-emacs}
	 * answers: each with its description ({@code (DEFUN NAME)}) and where it is. A name
	 * is tried as written, then with SWANK's trimmings ({@code foo,} {@code #:foo}), then
	 * as {@code (setf name)}; a package's name finds its {@code defpackage}.
	 * @param text the name as the editor sent it
	 * @return the definitions
	 */
	synchronized List<Entry> findDefinitions(String text) {
		for (String candidate : List.of(text, trimRight(text), trimLeft(text), trimLeft(trimRight(text)))) {
			List<Entry> found = definitionsOf(candidate);
			if (found != null) {
				return found;
			}
		}
		String lower = text.toLowerCase(Locale.ROOT);
		if (lower.startsWith("(setf ") && text.endsWith(")")) {
			String spelling = spelling(text.substring(6, text.length() - 1).trim());
			if (spelling != null) {
				return entries(this.definitions.get("(SETF " + spelling + ")"));
			}
		}
		return List.of();
	}

	/**
	 * The canonical spelling of the symbol a name names: the one accessible in the
	 * calling thread's package, else, for a name that only names a class or a structure
	 * the editor compiled (which interns no definition the image knows), the spelling its
	 * definitions are recorded under.
	 */
	private @Nullable String spelling(String text) {
		String found = this.lisp.symbolNamed(text);
		if (found != null) {
			return found;
		}
		for (String guess : this.lisp.spellingsOf(text)) {
			if (this.definitions.containsKey(guess) || this.definitions.containsKey("(SETF " + guess + ")")) {
				return guess;
			}
		}
		return null;
	}

	private static String trimRight(String text) {
		int end = text.length();
		while (end > 0 && ",:.>".indexOf(text.charAt(end - 1)) >= 0) {
			end--;
		}
		return text.substring(0, end);
	}

	private static String trimLeft(String text) {
		int start = 0;
		while (start < text.length() && "#:<".indexOf(text.charAt(start)) >= 0) {
			start++;
		}
		return text.substring(start);
	}

	/** A name's definitions, or null when it names no symbol and no package. */
	private @Nullable List<Entry> definitionsOf(String text) {
		if (text.isEmpty()) {
			return null;
		}
		String spelling = spelling(text);
		String pkg = this.lisp.guessPackage(text);
		if (spelling == null && pkg == null) {
			return null;
		}
		List<Entry> out = new ArrayList<>();
		if (spelling != null) {
			Map<String, Def> compiled = this.definitions.get(spelling);
			out.addAll(entries(compiled));
			if (compiled == null) {
				Entry loaded = loaded(spelling);
				if (loaded != null) {
					out.addFirst(loaded);
				}
			}
		}
		if (pkg != null) {
			out.addAll(entries(this.definitions.get("#package " + this.lisp.packageName(pkg))));
		}
		return out;
	}

	private List<Entry> entries(@Nullable Map<String, Def> defs) {
		List<Entry> out = new ArrayList<>();
		if (defs != null) {
			for (Def def : defs.values()) {
				out.add(new Entry(dspec(def), def.place(), null));
			}
		}
		return out;
	}

	/**
	 * A definition the evaluator recorded but the editor did not compile: at the file and
	 * line it was read from, else no place known.
	 */
	private @Nullable Entry loaded(String spelling) {
		LispEvaluator.Definition definition = this.evaluator.definition(spelling);
		if (definition == null || PackageRegistry.isClMemberName(spelling)) {
			return null;
		}
		String label = "(" + definition.operator() + " " + this.lisp.print(new LispSymbol(spelling))
				+ (LispNames.DEFGENERIC.equals(definition.operator()) ? " " + this.lisp.print(definition.lambdaList())
						: "")
				+ ")";
		String file = definition.file();
		if (file != null) {
			Place place = filePlace(file, definition.line());
			if (place != null) {
				return new Entry(label, place, null);
			}
		}
		String kind = LispNames.DEFMACRO.equals(definition.operator()) ? "macro" : "function";
		return new Entry(label, null,
				"Error: DEFINITION-SOURCE of " + kind + " " + LispSymbol.memberName(spelling).toUpperCase(Locale.ROOT)
						+ " did not contain meaningful information.");
	}

	/**
	 * Where a line of a file starts its form: its first character that is not a blank.
	 */
	private static @Nullable Place filePlace(String file, int line) {
		Path path = Path.of(file).toAbsolutePath();
		String code;
		try {
			code = Files.readString(path, StandardCharsets.UTF_8);
		}
		catch (IOException | RuntimeException ex) {
			return null;
		}
		int offset = 0;
		for (int l = 1; l < line; l++) {
			int newline = code.indexOf('\n', offset);
			if (newline < 0) {
				return null;
			}
			offset = newline + 1;
		}
		while (offset < code.length() && (code.charAt(offset) == ' ' || code.charAt(offset) == '\t')) {
			offset++;
		}
		return new Place(new Location.File(path.toString(), offset + 1),
				code.substring(offset, Math.min(code.length(), offset + SNIPPET_LENGTH)));
	}

	/**
	 * The uses an {@code xref} asks for, as SWANK's answers: {@code :calls},
	 * {@code :references}, {@code :binds}, {@code :sets} and {@code :macroexpands} at
	 * each use, labelled with the definition it is in; {@code :callers} each such
	 * definition at its place; {@code :calls-who} and {@code :callees} the definitions of
	 * what a definition calls; {@code :specializes} the methods specialized on a class.
	 * @param type the kind, without its colon
	 * @param text the name as the editor sent it
	 * @return the entries, or null when the kind is not one of these
	 */
	synchronized @Nullable List<Entry> xref(String type, String text) {
		if (!answers(type)) {
			return null;
		}
		Use use = switch (type.toLowerCase(Locale.ROOT)) {
			case "calls", "callers" -> Use.CALL;
			case "references" -> Use.REFERENCE;
			case "binds" -> Use.BIND;
			case "sets" -> Use.SET;
			case "macroexpands" -> Use.MACROEXPAND;
			default -> null;
		};
		String spelling = spelling(text.trim());
		if (spelling == null) {
			return List.of();
		}
		List<Entry> out = new ArrayList<>();
		switch (type.toLowerCase(Locale.ROOT)) {
			case "callers" -> {
				Set<String> seen = new LinkedHashSet<>();
				for (Ref ref : refs(use, spelling)) {
					if (seen.add(key(ref.container()))) {
						out.add(new Entry(caller(ref.container()), ref.container().place(), null));
					}
				}
			}
			case "calls-who", "callees" -> {
				Set<String> seen = new LinkedHashSet<>();
				for (Map.Entry<String, List<Ref>> each : this.uses.entrySet()) {
					for (Ref ref : each.getValue()) {
						if (ref.use() == Use.CALL && ref.container().name() instanceof LispSymbol in
								&& in.name().equals(spelling) && seen.add(ref.target())) {
							for (Entry definition : entries(this.definitions.get(ref.target()))) {
								out.add(new Entry(this.lisp.print(new LispSymbol(ref.target())), definition.place(),
										null));
							}
						}
					}
				}
			}
			case "specializes" -> {
				for (Map<String, Def> defs : this.definitions.values()) {
					for (Def def : defs.values()) {
						List<LispVal> specializers = def.specializers();
						if (def.method() && specializers != null && specializers.stream()
							.anyMatch(s -> s instanceof LispSymbol symbol && symbol.name().equals(spelling))) {
							out.add(new Entry("(DEFMETHOD " + this.lisp.print(def.name()) + ")", def.place(), null));
						}
					}
				}
			}
			default -> {
				for (Ref ref : refs(use, spelling)) {
					out.add(new Entry(caller(ref.container()), ref.place(), null));
				}
			}
		}
		return out;
	}

	/** Whether {@link #xref} answers a kind. */
	static boolean answers(String type) {
		return Set
			.of("calls", "callers", "references", "binds", "sets", "macroexpands", "calls-who", "callees",
					"specializes")
			.contains(type.toLowerCase(Locale.ROOT));
	}

	private List<Ref> refs(@Nullable Use use, String target) {
		List<Ref> out = new ArrayList<>();
		for (List<Ref> each : this.uses.values()) {
			for (Ref ref : each) {
				if (ref.use() == use && ref.target().equals(target)) {
					out.add(ref);
				}
			}
		}
		return out;
	}

	/**
	 * A definition as {@code find-definitions-for-emacs} describes it:
	 * {@code (DEFUN NAME)}, {@code (DEFMETHOD NAME QUALIFIER... SPECIALIZER...)},
	 * {@code (DEFGENERIC NAME LAMBDA-LIST)}, {@code (DEFPACKAGE #:NAME)}.
	 */
	private String dspec(Def def) {
		StringBuilder text = new StringBuilder("(").append(def.operator()).append(' ');
		if (def.name() instanceof LispString pkg) {
			text.append("#:").append(pkg.value());
		}
		else {
			text.append(this.lisp.print(def.name()));
		}
		for (LispVal qualifier : def.qualifiers()) {
			text.append(' ').append(this.lisp.print(qualifier));
		}
		List<LispVal> specializers = def.specializers();
		if (specializers != null) {
			if (def.method()) {
				for (LispVal specializer : specializers) {
					text.append(' ').append(this.lisp.print(specializer));
				}
			}
			else {
				text.append(' ').append(this.lisp.print(list(specializers)));
			}
		}
		return text.append(')').toString();
	}

	/** A definition as an {@code xref} entry names what a use is in. */
	private String caller(Def def) {
		if (!def.method()) {
			return this.lisp.print(def.name());
		}
		StringBuilder text = new StringBuilder("(DEFMETHOD ").append(this.lisp.print(def.name()));
		for (LispVal qualifier : def.qualifiers()) {
			text.append(' ').append(this.lisp.print(qualifier));
		}
		List<LispVal> specializers = def.specializers();
		return text.append(' ')
			.append(this.lisp.print(list(specializers == null ? List.of() : specializers)))
			.append(')')
			.toString();
	}

	// ------------------------------------------------------------------------------------
	// Text

	private static LispVal list(List<LispVal> items) {
		LispVal out = LispNil.INSTANCE;
		for (int i = items.size() - 1; i >= 0; i--) {
			out = new LispCons(items.get(i), out);
		}
		return out;
	}

	private static LispVal list(LispVal... items) {
		return list(List.of(items));
	}

	private static List<LispVal> list(LispVal value) {
		return value instanceof LispCons cell ? cell.toList() : List.of();
	}

	/**
	 * Where {@code needle} (lower case) next occurs in the text between two offsets as a
	 * whole token, ignoring case, or -1.
	 */
	private static int find(String code, String needle, int from, int to) {
		String lower = code.toLowerCase(Locale.ROOT);
		for (int at = lower.indexOf(needle, from); at >= 0 && at < to; at = lower.indexOf(needle, at + 1)) {
			int after = at + needle.length();
			if (after >= code.length() || delimiter(code.charAt(after))) {
				return at;
			}
		}
		return -1;
	}

	/** Where a symbol's name next occurs as a token, ignoring case, or -1. */
	private static int findToken(String code, String member, int from, int to) {
		String lower = code.toLowerCase(Locale.ROOT);
		String needle = member.toLowerCase(Locale.ROOT);
		for (int at = lower.indexOf(needle, from); at >= 0 && at < to; at = lower.indexOf(needle, at + 1)) {
			int after = at + needle.length();
			boolean before = at == 0 || delimiter(code.charAt(at - 1)) || code.charAt(at - 1) == ':'
					|| code.charAt(at - 1) == '\'' || code.charAt(at - 1) == '`' || code.charAt(at - 1) == ',';
			if (before && (after >= code.length() || delimiter(code.charAt(after)))) {
				return at;
			}
		}
		return -1;
	}

	private static boolean delimiter(char c) {
		return Character.isWhitespace(c) || c == '(' || c == ')' || c == '"' || c == ';' || c == '\'';
	}

	/**
	 * Where the datum starting at an offset ends: its closing parenthesis, or its token.
	 */
	static int datumEnd(String code, int start) {
		int depth = 0;
		int i = start;
		int n = code.length();
		while (i < n) {
			char c = code.charAt(i);
			if (c == '"') {
				i++;
				while (i < n && code.charAt(i) != '"') {
					i += code.charAt(i) == '\\' ? 2 : 1;
				}
				i++;
			}
			else if (c == ';') {
				while (i < n && code.charAt(i) != '\n') {
					i++;
				}
			}
			else if (c == '#' && i + 1 < n && code.charAt(i + 1) == '\\') {
				i += 3;
			}
			else if (c == '|') {
				i++;
				while (i < n && code.charAt(i) != '|') {
					i++;
				}
				i++;
			}
			else if (c == '(') {
				depth++;
				i++;
			}
			else if (c == ')') {
				depth--;
				i++;
				if (depth <= 0) {
					return i;
				}
			}
			else if (depth == 0 && i > start && delimiter(c)) {
				return i;
			}
			else {
				i++;
			}
		}
		return Math.min(i, n);
	}

}
