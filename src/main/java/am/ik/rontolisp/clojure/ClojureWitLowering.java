package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * {@code rontolisp.wit} of the Clojure lowering: {@code import} binds a WIT interface's
 * functions as the vars of a namespace an alias (or refers) reach, {@code export}
 * declares the world the program implements, {@code provide} binds the implementation of
 * an imported interface where the program itself provides it (the interpreter, the JVM).
 * They LOWER to {@code rontolisp:wit-import}, {@code rontolisp:wit-export} and
 * {@code rontolisp:wit-provide}, whose per-backend lowerings stay the only ones -- the
 * directives carry the Clojure names through their {@code :names} naming hook, so a
 * member is bound under its var's {@code c%ns/name} symbol and an export is implemented
 * by the function its label's var names.
 *
 * <p>
 * The WIT is read while the program lowers ({@link ClojureFiles}), and its members and
 * exports are described by the host boundary ({@link ClojureBoundary}), so a call site
 * below an {@code import} lowers against vars that exist, and a member outside the
 * Clojure tier -- a value Clojure spells differently from the boundary's Common Lisp
 * value and no conversion exists for yet (a record, a variant, an enum, flags, a tuple, a
 * list) -- is refused by name where a program reaches it. A {@code bool} crosses through
 * a wrapper defun ({@link ClojureWasmLowering.Crossing}), emitted only when the program
 * names the member, so a {@code --component} build still imports exactly the members the
 * program calls.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureWitLowering {

	/** The namespace this slice lowers. */
	static final String NAMESPACE = "rontolisp.wit";

	/** The {@code rontolisp.wit} vars. */
	static final Set<String> VARS = Set.of("import", "export", "provide");

	private static final String WIT_IMPORT = "RONTOLISP:WIT-IMPORT";

	private static final String WIT_EXPORT = "RONTOLISP:WIT-EXPORT";

	private static final String WIT_PROVIDE = "RONTOLISP:WIT-PROVIDE";

	private ClojureWitLowering() {
	}

	/**
	 * One interface the program imported.
	 *
	 * @param id its canonical id
	 * @param ns the namespace its members are the vars of
	 * @param from the Preview 1 module the import named, or null
	 * @param fieldStyle the field style the import named, or null
	 */
	record Imported(String id, String ns, @Nullable String from, @Nullable String fieldStyle) {
	}

	/**
	 * One world the program implements, resolved and emitted once the program has
	 * lowered.
	 *
	 * @param ns the namespace the declaration sits in, which each export's label names a
	 * var of
	 * @param path the WIT path the directive names
	 * @param written the WIT path as the declaration wrote it, for a refusal
	 * @param world the world the declaration names, or null for the file's only one
	 * @param exports the world's exports
	 * @param at where the declaration sits, for a refusal
	 */
	record PendingWorld(String ns, String path, String written, @Nullable String world,
			List<ClojureBoundary.Function> exports, @Nullable SourceLocation at) {
	}

	/** The WIT state one lowering keeps (a session: across its buffers). */
	static final class State {

		/** The interfaces imported so far, by canonical id. */
		final Map<String, Imported> imports = new LinkedHashMap<>();

		/**
		 * The members outside the Clojure tier, by var key: the refusal a reference to
		 * one meets.
		 */
		final Map<String, String> refusals = new HashMap<>();

		/**
		 * The conversion wrapper of each member that has one, by its var's symbol name:
		 * emitted once the program names the var.
		 */
		final Map<String, LispVal> wrappers = new LinkedHashMap<>();

		/** The wrappers emitted so far. */
		final Set<String> wrappersEmitted = new HashSet<>();

		/** The worlds declared and not yet emitted. */
		final List<PendingWorld> worlds = new ArrayList<>();

		/** Every interface reference an import was written with, to its canonical id. */
		final Map<String, String> references = new HashMap<>();

		/** Whether the program declared a world. */
		boolean implemented;

	}

	/**
	 * A {@code rontolisp.wit} call.
	 * @param ctx the hub
	 * @param var the var name
	 * @param items the call, head included
	 * @param form the call datum
	 * @return the lowered call
	 */
	static LispVal call(ClojureLowering ctx, String var, List<LispVal> items, @Nullable LispVal form) {
		return switch (var) {
			case "import" -> importCall(ctx, items, form);
			case "export" -> exportCall(ctx, items, form);
			case "provide" -> provideCall(ctx, items);
			default -> throw new LispReadException("unknown name: " + NAMESPACE + "/" + var);
		};
	}

	/**
	 * A {@code rontolisp.wit} var as a value: {@code provide} a two-argument function,
	 * the two declarations none (the oracle's wording for a macro).
	 * @param ctx the hub
	 * @param var the var name
	 * @return the value form
	 */
	static LispVal value(ClojureLowering ctx, String var) {
		if (!VARS.contains(var)) {
			throw new LispReadException("unknown name: " + NAMESPACE + "/" + var);
		}
		if (!var.equals("provide")) {
			throw new LispReadException("Can't take value of a macro: #'" + NAMESPACE + "/" + var);
		}
		LispSymbol iface = new LispSymbol("#:wit-interface");
		LispSymbol provider = new LispSymbol("#:wit-provider");
		LispVal body = ctx.hostTarget
				? ClojureLowerUtil.list(new LispSymbol(WIT_PROVIDE), iface, ClojureLowering.realFun(provider)) : iface;
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(iface, provider), body);
	}

	/**
	 * {@code (import "path.wit" {:interface "id" :as alias :refer [...] :from "m"
	 * :field-style :kebab})}: the interface's members interned as the vars of its
	 * namespace, the alias and the refers wired into the current one, and the
	 * {@code rontolisp:wit-import} binding them hoisted ahead of the datum -- once per
	 * interface (a second import of it wires its names and binds nothing).
	 */
	private static LispVal importCall(ClojureLowering ctx, List<LispVal> items, @Nullable LispVal form) {
		String what = NAMESPACE + "/import";
		int n = items.size() - 1;
		if (n != 2) {
			throw new LispReadException("Wrong number of args (" + n + ") passed to: " + what);
		}
		if (!(items.get(1) instanceof LispString path)) {
			throw new LispReadException(
					what + " takes a WIT file path string, not " + ClojureWasmLowering.shown(items.get(1)));
		}
		Map<String, LispVal> options = ClojureWasmLowering.options(items.get(2), what,
				List.of(":interface", ":as", ":refer", ":from", ":field-style"));
		String iface = ClojureWasmLowering.stringOption(options, ":interface", what);
		if (iface == null) {
			throw new LispReadException(what + " takes the :interface to bind (\"wasi:keyvalue/store@0.2.0\")");
		}
		String alias = null;
		if (options.containsKey(":as")) {
			if (!(options.get(":as") instanceof LispSymbol named) || named.name().startsWith(":")
					|| named.name().indexOf('/') >= 0) {
				throw new LispReadException(
						what + ": :as takes an alias, not " + ClojureWasmLowering.shown(options.get(":as")));
			}
			alias = named.name();
		}
		LispVal refer = options.get(":refer");
		if (alias == null && refer == null) {
			throw new LispReadException(what + " takes :as or :refer: nothing else reaches the vars it binds");
		}
		String from = ClojureWasmLowering.stringOption(options, ":from", what);
		String fieldStyle = fieldStyle(options.get(":field-style"), what);
		// the path is the importing file's, like load: written as is from the entry
		// file (whose directory every later pass resolves against), resolved from a
		// required namespace's file
		@Nullable String file = ctx.reader == null ? null : ctx.reader.file();
		String readPath = ctx.files.resolve(file == null ? null : ctx.files.parent(file), path.value());
		String directivePath = ctx.loadingNamespaces.isEmpty() ? path.value() : readPath;
		String text = ctx.files.read(readPath);
		if (text == null) {
			throw new LispReadException(what + ": cannot read WIT file " + path.value()
					+ (ctx.files == ClojureFiles.NONE ? " (the program was read without files)" : ""));
		}
		ClojureBoundary.WitInterface wit = ctx.boundary.importInterface(text, path.value(), iface);
		String ns = namespaceOf(wit.id());
		Imported existing = ctx.wit.imports.get(wit.id());
		if (existing != null) {
			if (!Objects.equals(existing.from(), from) || !Objects.equals(existing.fieldStyle(), fieldStyle)) {
				throw new LispReadException(what + ": " + wit.id()
						+ " is imported already, with another :from or :field-style; one program binds it once");
			}
		}
		else {
			bind(ctx, wit, ns, directivePath, iface, from, fieldStyle, path.value());
			ctx.wit.imports.put(wit.id(), new Imported(wit.id(), ns, from, fieldStyle));
		}
		ctx.wit.references.put(iface, wit.id());
		ctx.wit.references.put(wit.id(), wit.id());
		if (alias != null) {
			ctx.ns().aliases.put(alias, ns);
		}
		if (refer != null) {
			refer(ctx, ns, wit.id(), refer, what);
		}
		return ClojureLowering.NIL_CONST;
	}

	/**
	 * The members interned as the namespace's vars and bound by one
	 * {@code rontolisp:wit-import} hoisted ahead of the datum, its {@code :names} table
	 * naming each member's var -- or an internal name behind the var's conversion
	 * wrapper. A member outside the Clojure tier is not bound, and a reference to it is
	 * refused by name.
	 */
	private static void bind(ClojureLowering ctx, ClojureBoundary.WitInterface wit, String ns, String directivePath,
			String iface, @Nullable String from, @Nullable String fieldStyle, String path) {
		ctx.createdNamespaces.add(ns);
		ClojureNsState state = ctx.namespaces.computeIfAbsent(ns, k -> new ClojureNsState());
		Map<String, String> names = new LinkedHashMap<>();
		String firstRefusal = null;
		for (ClojureBoundary.Function member : wit.members()) {
			String key = ClojureLowering.varKey(ns, member.name());
			String refusal = refusal(member, wit.id(), path);
			if (refusal != null) {
				ctx.wit.refusals.put(key, refusal);
				if (firstRefusal == null && member.origin() == ClojureBoundary.Origin.FUNCTION) {
					firstRefusal = refusal;
				}
				continue;
			}
			state.interns.put(member.name(), false);
			ctx.globals.put(key, ClojureLowering.Kind.FUNCTION);
			LispSymbol var = ClojureLowering.varSym(key);
			List<ClojureWasmLowering.Crossing> params = new ArrayList<>();
			boolean converts = false;
			for (ClojureBoundary.Param param : member.params()) {
				ClojureWasmLowering.Crossing crossing = Objects.requireNonNull(paramCrossing(param.type()));
				params.add(crossing);
				converts |= crossing != ClojureWasmLowering.Crossing.PLAIN;
			}
			ClojureWasmLowering.Crossing result = member.result() == null ? ClojureWasmLowering.Crossing.PLAIN
					: Objects.requireNonNull(resultCrossing(member.result()));
			converts |= result != ClojureWasmLowering.Crossing.PLAIN;
			String bound = converts ? var.name() + "%wit" : var.name();
			if (converts) {
				ctx.wit.wrappers.put(var.name(),
						ClojureWasmLowering.importWrapper(ctx, var, new LispSymbol(bound), params, result));
			}
			names.put(member.name(), bound);
		}
		if (names.isEmpty()) {
			throw new LispReadException(NAMESPACE + "/import: no member of " + wit.id() + " crosses into Clojure"
					+ (firstRefusal == null ? "" : ": " + firstRefusal));
		}
		List<LispVal> table = new ArrayList<>();
		for (Map.Entry<String, String> entry : names.entrySet()) {
			table.add(ClojureLowerUtil.list(LispString.literal(entry.getKey()), LispString.literal(entry.getValue())));
		}
		List<LispVal> directive = new ArrayList<>();
		directive.add(new LispSymbol(WIT_IMPORT));
		directive.add(LispString.literal(directivePath));
		directive.add(new LispSymbol(":INTERFACE"));
		directive.add(LispString.literal(iface));
		if (from != null) {
			directive.add(new LispSymbol(":FROM"));
			directive.add(LispString.literal(from));
		}
		if (fieldStyle != null) {
			directive.add(new LispSymbol(":FIELD-STYLE"));
			directive.add(new LispSymbol(fieldStyle));
		}
		directive.add(new LispSymbol(":NAMES"));
		directive.add(ClojureLowerUtil.list(table));
		ctx.hoisted.add(ClojureLowerUtil.list(directive));
	}

	/**
	 * The namespace an interface's members are the vars of: its canonical id with the
	 * {@code /} a var key separates a namespace from its name with spelled {@code .}
	 * ({@code wasi:keyvalue.store@0.2.0}), so no program namespace can be it -- an
	 * interface outside a package behind {@code wit:}, for the same reason.
	 */
	static String namespaceOf(String id) {
		return (id.indexOf(':') < 0 ? "wit:" : "") + id.replace('/', '.');
	}

	private static @Nullable String fieldStyle(@Nullable LispVal value, String what) {
		if (value == null) {
			return null;
		}
		if (ClojureLowerUtil.isSymbolNamed(value, ":camel")) {
			return ":CAMEL";
		}
		if (ClojureLowerUtil.isSymbolNamed(value, ":kebab")) {
			return ":KEBAB";
		}
		throw new LispReadException(
				what + ": :field-style takes :camel or :kebab, not " + ClojureWasmLowering.shown(value));
	}

	/** {@code :refer [names]} or {@code :refer :all}: the members referred. */
	private static void refer(ClojureLowering ctx, String ns, String id, LispVal refer, String what) {
		ClojureNsState state = ctx.namespaces.get(ns);
		List<String> names = new ArrayList<>();
		if (ClojureLowerUtil.isSymbolNamed(refer, ":all")) {
			if (state != null) {
				names.addAll(state.interns.keySet());
			}
		}
		else {
			names.addAll(ClojureNamespaceLowering.referNames(refer, refer));
			for (String name : names) {
				String refusal = ctx.wit.refusals.get(ClojureLowering.varKey(ns, name));
				if (refusal != null) {
					throw new LispReadException(refusal);
				}
				if (state == null || !state.interns.containsKey(name)) {
					throw new LispReadException(what + ": " + name + " is no member of " + id);
				}
			}
		}
		for (String name : names) {
			ctx.ns().refers.put(name, new ClojureLowering.VarRef(ns, name));
		}
	}

	/**
	 * Why a member is not bound in the Clojure tier, or null when it is: a built-in only
	 * a {@code --component} build's async machinery has, an {@code async func} (its
	 * future does not map onto a Clojure one yet), or a type whose Clojure value differs
	 * from the boundary's and no conversion exists for yet.
	 */
	private static @Nullable String refusal(ClojureBoundary.Function member, String id, String path) {
		String where = " (" + path + ":" + member.line() + ")";
		String name = member.name() + " of " + id;
		switch (member.origin()) {
			case ASYNC_BUILTIN -> {
				return name + " is a stream/future built-in, which only a --component build's async canonical ABI "
						+ "has; the Clojure tier binds none" + where;
			}
			case TASK_RETURN -> {
				return name + " is a task-return built-in, which only a --component build's async canonical ABI "
						+ "has; the Clojure tier binds none" + where;
			}
			default -> {
			}
		}
		if (member.async()) {
			return name + " is an async func: the future it answers does not map onto a Clojure future yet" + where;
		}
		for (ClojureBoundary.Param param : member.params()) {
			if (paramCrossing(param.type()) == null) {
				return name + " takes " + param.type().wit() + " (parameter '" + param.name() + "'), "
						+ kind(param.type()) + ", which the Clojure tier does not carry yet" + where;
			}
		}
		ClojureBoundary.Type result = member.result();
		if (result != null && resultCrossing(result) == null) {
			return name + " answers " + result.wit() + ", " + kind(result)
					+ ", which the Clojure tier does not carry yet" + where;
		}
		return null;
	}

	/** Whether the representation is one Clojure spells the same way. */
	private static boolean plain(ClojureBoundary.Rep rep) {
		return switch (rep) {
			case INT, BIGNUM_INT, FLOAT, STRING, CHARACTER, BYTE_STRING, HANDLE -> true;
			default -> false;
		};
	}

	/**
	 * How an argument of this type crosses from a Clojure caller: unchanged, a boolean
	 * ({@code false} to {@code nil}), or null when the Clojure tier does not carry it --
	 * a {@code result} argument among them, whose envelope {@code (:ok . v)} no Clojure
	 * value spells.
	 */
	static ClojureWasmLowering.@Nullable Crossing paramCrossing(ClojureBoundary.Type type) {
		if (type.rep() == ClojureBoundary.Rep.BOOLEAN) {
			return ClojureWasmLowering.Crossing.BOOL;
		}
		if (type.rep() == ClojureBoundary.Rep.NIL_OR_VALUE) {
			ClojureBoundary.Type element = type.element();
			if (element != null && element.rep() == ClojureBoundary.Rep.BOOLEAN) {
				return ClojureWasmLowering.Crossing.NILLABLE_BOOL;
			}
			return element != null && plain(element.rep()) ? ClojureWasmLowering.Crossing.PLAIN : null;
		}
		return plain(type.rep()) ? ClojureWasmLowering.Crossing.PLAIN : null;
	}

	/**
	 * How a result of this type crosses back to a Clojure caller: unchanged, a boolean
	 * (read with Clojure truth), or null when the Clojure tier does not carry it. A
	 * {@code result}'s ok arm is the value (its error arm signals); an
	 * {@code option<bool>} keeps {@code nil} as its absent value.
	 */
	static ClojureWasmLowering.@Nullable Crossing resultCrossing(ClojureBoundary.Type type) {
		if (type.rep() == ClojureBoundary.Rep.RESULT) {
			ClojureBoundary.Type ok = type.element();
			return ok == null ? ClojureWasmLowering.Crossing.PLAIN : resultCrossing(ok);
		}
		if (type.rep() == ClojureBoundary.Rep.NIL_OR_VALUE) {
			ClojureBoundary.Type element = type.element();
			return element != null && (plain(element.rep()) || element.rep() == ClojureBoundary.Rep.BOOLEAN)
					? ClojureWasmLowering.Crossing.PLAIN : null;
		}
		if (type.rep() == ClojureBoundary.Rep.BOOLEAN) {
			return ClojureWasmLowering.Crossing.BOOL;
		}
		return plain(type.rep()) ? ClojureWasmLowering.Crossing.PLAIN : null;
	}

	/** What a type outside the Clojure tier is, for a refusal. */
	private static String kind(ClojureBoundary.Type type) {
		return switch (type.rep()) {
			case PLIST -> "a record";
			case KEYWORD -> "an enum";
			case TAGGED_LIST -> "a variant";
			case KEYWORD_LIST -> "flags";
			case LIST -> "a list";
			case TUPLE_LIST -> "a tuple";
			case STREAM_HANDLE -> "a stream";
			case FUTURE_HANDLE -> "a future";
			case RESULT -> "a result";
			case NIL_OR_VALUE -> "an option of " + (type.element() == null ? "nothing" : kind(type.element()));
			case UNSUPPORTED -> "a type with no rontolisp value";
			default -> "a " + type.rep().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
		};
	}

	/**
	 * Why a reference to a var of an imported interface's namespace is refused, or null:
	 * the member is outside the Clojure tier.
	 * @param ctx the hub
	 * @param ns the namespace
	 * @param var the var name
	 * @return the refusal, or null
	 */
	static @Nullable String refusalOf(ClojureLowering ctx, String ns, String var) {
		return ctx.wit.refusals.get(ClojureLowering.varKey(ns, var));
	}

	/**
	 * The conversion wrappers of the members the forms name, not emitted yet: what makes
	 * a {@code bool} cross for the vars a program calls, and only those (a member nothing
	 * names stays unbound under {@code --component}).
	 * @param ctx the hub
	 * @param forms the program's lowered forms
	 * @return the wrapper defuns, in import order
	 */
	static List<LispVal> referencedWrappers(ClojureLowering ctx, List<LispVal> forms) {
		if (ctx.wit.wrappers.isEmpty()) {
			return List.of();
		}
		Set<String> named = new HashSet<>();
		for (LispVal form : forms) {
			collectSymbols(form, named);
		}
		List<LispVal> out = new ArrayList<>();
		for (Map.Entry<String, LispVal> wrapper : ctx.wit.wrappers.entrySet()) {
			if (named.contains(wrapper.getKey()) && ctx.wit.wrappersEmitted.add(wrapper.getKey())) {
				out.add(wrapper.getValue());
			}
		}
		return out;
	}

	private static void collectSymbols(LispVal form, Set<String> out) {
		LispVal at = form;
		while (at instanceof LispCons cons) {
			collectSymbols(cons.car(), out);
			at = cons.cdr();
		}
		if (at instanceof LispSymbol symbol) {
			out.add(symbol.name());
		}
	}

	/**
	 * {@code (export "path.wit" {:world "name"})}: the world read and checked now, its
	 * exports resolved and emitted once the program has lowered.
	 */
	private static LispVal exportCall(ClojureLowering ctx, List<LispVal> items, @Nullable LispVal form) {
		String what = NAMESPACE + "/export";
		int n = items.size() - 1;
		if (n != 1 && n != 2) {
			throw new LispReadException("Wrong number of args (" + n + ") passed to: " + what);
		}
		if (!(items.get(1) instanceof LispString path)) {
			throw new LispReadException(
					what + " takes a WIT file path string, not " + ClojureWasmLowering.shown(items.get(1)));
		}
		String world = null;
		if (n == 2) {
			Map<String, LispVal> options = ClojureWasmLowering.options(items.get(2), what, List.of(":world"));
			LispVal named = options.get(":world");
			if (named instanceof LispString string) {
				world = string.value();
			}
			else if (named instanceof LispSymbol symbol && !symbol.name().startsWith(":")) {
				world = symbol.name();
			}
			else if (named != null) {
				throw new LispReadException(
						what + ": :world takes a world name, not " + ClojureWasmLowering.shown(named));
			}
		}
		@Nullable String file = ctx.reader == null ? null : ctx.reader.file();
		String readPath = ctx.files.resolve(file == null ? null : ctx.files.parent(file), path.value());
		String directivePath = ctx.loadingNamespaces.isEmpty() ? path.value() : readPath;
		String text = ctx.files.read(readPath);
		if (text == null) {
			throw new LispReadException(what + ": cannot read WIT file " + path.value()
					+ (ctx.files == ClojureFiles.NONE ? " (the program was read without files)" : ""));
		}
		ClojureBoundary.WitWorld described = ctx.boundary.exportWorld(text, path.value(), world);
		for (ClojureBoundary.Function export : described.exports()) {
			if (export.async()) {
				throw new LispReadException(what + ": " + export.name() + " is an async func export, which the "
						+ "Clojure tier does not implement yet (" + path.value() + ":" + export.line() + ")");
			}
		}
		ctx.wit.worlds.add(new PendingWorld(ctx.currentNs, directivePath, path.value(), world, described.exports(),
				ClojureWasmLowering.locate(ctx, form)));
		ctx.wit.implemented = true;
		return ClojureLowering.NIL_CONST;
	}

	/**
	 * A {@code rontolisp.wasm} export beside a world is refused like the compile path
	 * refuses the two directives: the world is the program's export list. A session runs
	 * on the interpreter, which exports nothing and checks a world against what is
	 * defined so far, so it is a file's rule only.
	 * @param ctx the hub
	 */
	static void refuseCombined(ClojureLowering ctx) {
		if (!ctx.session && ctx.wit.implemented && ctx.wasm.exported) {
			throw new LispReadException("rontolisp.wasm/export cannot be combined with rontolisp.wit/export: the WIT "
					+ "world is the program's export list -- declare the export in the world, or drop the world");
		}
	}

	/**
	 * The worlds the program implements, as the forms implementing them: each export's
	 * label resolved to a var of the namespace the declaration sits in, its wrapper (when
	 * one is needed) ahead of the {@code rontolisp:wit-export} whose {@code :names} table
	 * names every implementing function.
	 * @param ctx the hub
	 * @param worlds the worlds declared since the last flush
	 * @return the forms
	 */
	static List<LispVal> flushWorlds(ClojureLowering ctx, List<PendingWorld> worlds) {
		List<LispVal> out = new ArrayList<>();
		for (PendingWorld world : worlds) {
			Map<String, String> names = new LinkedHashMap<>();
			for (ClojureBoundary.Function export : world.exports()) {
				if (names.containsKey(export.name())) {
					continue; // one label, one var: two interfaces' members of a name
				}
				List<ClojureWasmLowering.Crossing> params = new ArrayList<>();
				for (ClojureBoundary.Param param : export.params()) {
					params.add(param.type().rep() == ClojureBoundary.Rep.BOOLEAN ? ClojureWasmLowering.Crossing.BOOL
							: ClojureWasmLowering.Crossing.PLAIN);
				}
				ClojureBoundary.Type result = export.result();
				ClojureWasmLowering.Crossing crossing = result != null && result.rep() == ClojureBoundary.Rep.BOOLEAN
						? ClojureWasmLowering.Crossing.BOOL : ClojureWasmLowering.Crossing.PLAIN;
				// a refusal names the world's line the export is declared on
				names.put(export.name(),
						ClojureWasmLowering.implementation(ctx, world.ns(), export.name(), export.name(), params,
								crossing, NAMESPACE + "/export (" + world.written() + ":" + export.line() + ")",
								world.at(), out));
			}
			List<LispVal> table = new ArrayList<>();
			for (Map.Entry<String, String> entry : names.entrySet()) {
				table.add(ClojureLowerUtil.list(LispString.literal(entry.getKey()),
						LispString.literal(entry.getValue())));
			}
			List<LispVal> directive = new ArrayList<>();
			directive.add(new LispSymbol(WIT_EXPORT));
			directive.add(LispString.literal(world.path()));
			if (world.world() != null) {
				directive.add(new LispSymbol(":WORLD"));
				directive.add(LispString.literal(world.world()));
			}
			directive.add(new LispSymbol(":NAMES"));
			directive.add(ClojureLowerUtil.list(table));
			out.add(ClojureLowerUtil.list(directive));
		}
		return out;
	}

	/**
	 * {@code (provide "interface" provider)}: {@code rontolisp:wit-provide} where the
	 * program provides an interface it imports (the interpreter, the JVM), the provider a
	 * function of the member name (a string) and that member's arguments. A WASM build's
	 * host provides every import, so there it binds nothing and answers the interface,
	 * like the directive dropped there. An interface written as an import named it
	 * answers the canonical id the bindings dispatch on.
	 */
	private static LispVal provideCall(ClojureLowering ctx, List<LispVal> items) {
		int n = items.size() - 1;
		if (n != 2) {
			throw new LispReadException("Wrong number of args (" + n + ") passed to: " + NAMESPACE + "/provide");
		}
		LispVal iface = items.get(1) instanceof LispString written && ctx.wit.references.containsKey(written.value())
				? LispString.literal(ctx.wit.references.get(written.value())) : ctx.lower(items.get(1));
		if (!ctx.hostTarget) {
			return iface;
		}
		LispVal provider = ClojureBindingLowering.fnValue(ctx, items.get(2));
		if (!ClojureBindingLowering.holdsRealFun(ctx, items.get(2), provider)) {
			provider = ClojureLowering.realFun(provider);
		}
		return ClojureLowerUtil.list(new LispSymbol(WIT_PROVIDE), iface, provider);
	}

}
