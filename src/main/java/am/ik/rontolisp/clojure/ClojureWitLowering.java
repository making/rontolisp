package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * below an {@code import} lowers against vars that exist. A value the two languages spell
 * differently crosses through a wrapper defun emitted only when the program names the
 * member, so a {@code --component} build still imports exactly the members the program
 * calls: a {@code bool} and a {@code list<u8>} (a byte array) inline
 * ({@link ClojureWasmLowering.Crossing}), anything richer -- a record as a map, an enum
 * or a variant's case as a keyword, a payload as {@code [:case payload]}, flags as a set,
 * a tuple or a list as a vector, a {@code result} argument as {@code [:ok v]} /
 * {@code [:error e]} -- through the {@code clojure.lisp} walker over a descriptor of the
 * type, and a {@code result}'s error arm as an {@code ExceptionInfo} holding the error
 * value under {@link #ERROR_KEY}. A {@code provide} converts the other way round, so a
 * Clojure provider sees Clojure values. A member with no Clojure value (a stream, a
 * future, an {@code async func}) is refused by name where a program reaches it.
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

	/**
	 * The {@code ex-data} key a WIT {@code result}'s error arm carries the error value
	 * under, both ways: what a Clojure caller catches and a Clojure provider throws. The
	 * runtime spells it too ({@code rontolisp::%clojure-wit-arm-p}).
	 */
	static final String ERROR_KEY = "rontolisp.wit/error";

	private static final String WIT_IMPORT = "RONTOLISP:WIT-IMPORT";

	private static final String WIT_EXPORT = "RONTOLISP:WIT-EXPORT";

	private static final String WIT_PROVIDE = "RONTOLISP:WIT-PROVIDE";

	private static final String WIT_ERROR = "RONTOLISP:WIT-ERROR";

	private static final String WIT_ERROR_PAYLOAD = "RONTOLISP:WIT-ERROR-PAYLOAD";

	private static final String WIT_PROVIDERS = "RONTOLISP::*WIT-PROVIDERS*";

	private static final String OUT = "RONTOLISP::%CLOJURE-WIT-OUT";

	private static final String IN = "RONTOLISP::%CLOJURE-WIT-IN";

	private static final String ANSWER = "RONTOLISP::%CLOJURE-WIT-ANSWER";

	private static final String RAISE = "RONTOLISP::%CLOJURE-WIT-RAISE";

	private static final String SERVE = "RONTOLISP::%CLOJURE-WIT-SERVE";

	private static final String ARM_P = "RONTOLISP::%CLOJURE-WIT-ARM-P";

	private static final String ARM_PAYLOAD = "RONTOLISP::%CLOJURE-WIT-ARM-PAYLOAD";

	private static final String ARM_MESSAGE = "RONTOLISP::%CLOJURE-WIT-ARM-MESSAGE";

	/** The descriptor of a {@code bool}. */
	private static final LispSymbol BOOL = new LispSymbol(":BOOL");

	/**
	 * The descriptor of a {@code list<u8>}, a byte array: a producer of the byte-array
	 * family, since a wrapper reading it may answer one.
	 */
	static final LispSymbol BYTE_ARRAY = new LispSymbol(":BYTE-ARRAY");

	private ClojureWitLowering() {
	}

	/**
	 * One interface the program imported.
	 *
	 * @param id its canonical id
	 * @param ns the namespace its members are the vars of
	 * @param from the Preview 1 module the import named, or null
	 * @param fieldStyle the field style the import named, or null
	 * @param served what a {@code provide} of it converts: the table its adapter reads
	 * ({@code rontolisp::%clojure-wit-serve}), or null when every member's values are
	 * spelled alike in both languages
	 * @param raises whether a member answers a {@code result}, whose error arm the
	 * adapter turns from a Clojure exception into {@code rontolisp:wit-error}
	 */
	record Imported(String id, String ns, @Nullable String from, @Nullable String fieldStyle, @Nullable LispVal served,
			boolean raises) {
	}

	/**
	 * A member's conversion wrapper, emitted once the program names its var.
	 *
	 * @param form the defun
	 * @param types the descriptor globals it reads, emitted ahead of it
	 * @param raises whether it throws a {@code result}'s error arm as an
	 * {@code ExceptionInfo}, which the program's exception runtime then has to build
	 */
	record Wrapper(LispVal form, List<String> types, boolean raises) {
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
		final Map<String, Wrapper> wrappers = new LinkedHashMap<>();

		/** The wrappers emitted so far. */
		final Set<String> wrappersEmitted = new HashSet<>();

		/**
		 * The global holding each descriptor a wrapper reads, by the descriptor's
		 * spelling: one per distinct type, however many members cross it.
		 */
		final Map<String, String> typeNames = new HashMap<>();

		/** The top-level {@code setq} of each descriptor global, by its name. */
		final Map<String, LispVal> typeDefinitions = new HashMap<>();

		/** The descriptor globals emitted so far. */
		final Set<String> typesEmitted = new HashSet<>();

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
	 * A {@code rontolisp.wit} var as a value: none, the oracle's wording for a macro.
	 * {@code provide} converts its provider's values by the interface's WIT, which only a
	 * written interface names when the program compiles.
	 * @param var the var name
	 * @return never
	 */
	static LispVal value(String var) {
		if (!VARS.contains(var)) {
			throw new LispReadException("unknown name: " + NAMESPACE + "/" + var);
		}
		throw new LispReadException("Can't take value of a macro: #'" + NAMESPACE + "/" + var);
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
			ctx.wit.imports.put(wit.id(), bind(ctx, wit, ns, directivePath, iface, from, fieldStyle, path.value()));
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
	 * wrapper. A member with no Clojure value is not bound, and a reference to it is
	 * refused by name.
	 * @return the import, with what a {@code provide} of the interface converts
	 */
	private static Imported bind(ClojureLowering ctx, ClojureBoundary.WitInterface wit, String ns, String directivePath,
			String iface, @Nullable String from, @Nullable String fieldStyle, String path) {
		ctx.createdNamespaces.add(ns);
		ClojureNsState state = ctx.namespaces.computeIfAbsent(ns, k -> new ClojureNsState());
		Map<String, String> names = new LinkedHashMap<>();
		List<LispVal> served = new ArrayList<>();
		boolean raises = false;
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
			Crossings crossings = crossings(member);
			String bound = crossings.converts() ? var.name() + "%wit" : var.name();
			if (crossings.walks()) {
				ctx.wit.wrappers.put(var.name(), richWrapper(ctx, var, bound, wit.id(), member.name(), crossings));
			}
			else if (crossings.converts()) {
				List<ClojureWasmLowering.Crossing> params = new ArrayList<>();
				for (LispVal param : crossings.params()) {
					params.add(outCrossing(param));
				}
				ctx.wit.wrappers.put(var.name(), new Wrapper(ClojureWasmLowering.importWrapper(ctx, var,
						new LispSymbol(bound), params, inCrossing(crossings.result())), List.of(), false));
			}
			if (crossings.serves()) {
				served.add(servedEntry(member.name(), crossings));
				raises |= crossings.error() != null;
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
		// a list<u8> is a byte array here, so it crosses as its octets -- a component's
		// lift and a core module's import alike -- not as the text a string is
		directive.add(new LispSymbol(":OCTETS"));
		directive.add(new LispSymbol("T"));
		ctx.hoisted.add(ClojureLowerUtil.list(directive));
		LispVal servedTable = served.isEmpty() ? null
				: ClojureLowerUtil.cons(ClojureCollectionLowering.keywordDatum(ERROR_KEY), served);
		return new Imported(wit.id(), ns, from, fieldStyle, servedTable, raises);
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
	 * future does not map onto a Clojure one yet), or a type with no Clojure value (a
	 * stream or a future, anywhere inside it).
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
			String kind = unsupported(param.type());
			if (kind != null) {
				return name + " takes " + param.type().wit() + " (parameter '" + param.name() + "'), " + kind
						+ ", which the Clojure tier does not carry yet" + where;
			}
		}
		ClojureBoundary.Type result = member.result();
		String kind = result == null ? null : unsupported(result);
		if (result != null && kind != null) {
			return name + " answers " + result.wit() + ", " + kind + ", which the Clojure tier does not carry yet"
					+ where;
		}
		return null;
	}

	/**
	 * What makes a type one with no Clojure value, or null when it has one: a stream or a
	 * future (their async canonical ABI answers no Clojure future), or a type with no
	 * representation at all, itself or anywhere inside it.
	 */
	private static @Nullable String unsupported(ClojureBoundary.Type type) {
		switch (type.rep()) {
			case STREAM_HANDLE -> {
				return "a stream";
			}
			case FUTURE_HANDLE -> {
				return "a future";
			}
			case UNSUPPORTED -> {
				return "a type with no rontolisp value";
			}
			default -> {
			}
		}
		List<ClojureBoundary.Type> nested = new ArrayList<>();
		if (type.element() != null) {
			nested.add(type.element());
		}
		if (type.error() != null) {
			nested.add(type.error());
		}
		for (ClojureBoundary.Part part : type.parts()) {
			if (part.type() != null) {
				nested.add(part.type());
			}
		}
		for (ClojureBoundary.Type inner : nested) {
			String kind = unsupported(inner);
			if (kind != null) {
				String outer = switch (type.rep()) {
					case PLIST -> "a record";
					case TAGGED_LIST -> "a variant";
					case LIST -> "a list";
					case TUPLE_LIST -> "a tuple";
					case RESULT -> "a result";
					default -> "an option";
				};
				return outer + " carrying " + kind;
			}
		}
		return null;
	}

	/**
	 * How a member's values cross, as descriptors ({@link #descriptor}).
	 *
	 * @param params each parameter's
	 * @param result the result's -- a {@code result}'s ok arm -- or {@code NIL} for none
	 * @param error a {@code result}'s error arm ({@code NIL} when it has no payload), or
	 * null when the member answers no {@code result}
	 */
	private record Crossings(List<LispVal> params, LispVal result, @Nullable LispVal error) {

		/**
		 * Whether a value needs the runtime walker: one richer than a {@code bool}, or a
		 * {@code result}, whose error arm the wrapper throws as an exception.
		 */
		boolean walks() {
			if (this.error != null || !inline(this.result)) {
				return true;
			}
			for (LispVal param : this.params) {
				if (!inline(param)) {
					return true;
				}
			}
			return false;
		}

		/**
		 * Whether a caller's value is spelled differently in the two languages: an
		 * argument going out, an answer coming back (a host's {@code option<bool>} answer
		 * is already one, {@code nil} its absent value).
		 */
		boolean converts() {
			if (walks() || this.result == BOOL || this.result == BYTE_ARRAY) {
				return true;
			}
			for (LispVal param : this.params) {
				if (param == BOOL || param == BYTE_ARRAY || isOptionOf(param, BOOL)) {
					return true;
				}
			}
			return false;
		}

		/**
		 * Whether a provider's value is spelled differently: an argument coming in, an
		 * answer going out -- a Clojure provider's {@code false} for an
		 * {@code option<bool>} the boundary's {@code nil}.
		 */
		boolean serves() {
			if (walks() || this.result == BOOL || this.result == BYTE_ARRAY || isOptionOf(this.result, BOOL)) {
				return true;
			}
			for (LispVal param : this.params) {
				if (param == BOOL || param == BYTE_ARRAY) {
					return true;
				}
			}
			return false;
		}

	}

	private static Crossings crossings(ClojureBoundary.Function member) {
		List<LispVal> params = new ArrayList<>();
		for (ClojureBoundary.Param param : member.params()) {
			params.add(Objects.requireNonNull(descriptor(param.type())));
		}
		ClojureBoundary.Type result = member.result();
		if (result != null && result.rep() == ClojureBoundary.Rep.RESULT) {
			ClojureBoundary.Type ok = result.element();
			ClojureBoundary.Type error = result.error();
			return new Crossings(params,
					ok == null ? ClojureLowering.NIL_CONST : Objects.requireNonNull(descriptor(ok)),
					error == null ? ClojureLowering.NIL_CONST : Objects.requireNonNull(descriptor(error)));
		}
		return new Crossings(params,
				result == null ? ClojureLowering.NIL_CONST : Objects.requireNonNull(descriptor(result)), null);
	}

	/**
	 * The descriptor of a type, which the {@code clojure.lisp} walker
	 * ({@code rontolisp::%clojure-wit-out} / {@code -in}) converts a value by:
	 * {@code NIL} for a value both languages spell alike (a number, a character, a
	 * string, a handle), {@code :BOOL}, {@code :BYTE-ARRAY} for a {@code list<u8>} (a
	 * byte array, the boundary's octets), {@code (:OPTION . d)}, {@code (:LIST . d)},
	 * {@code (:TUPLE "wit" d ...)}, {@code (:RECORD "wit" (kw :KW d) ...)},
	 * {@code (:VARIANT "wit" (kw :KW [d]) ...)} for a variant or an enum (a case without
	 * payload holds no descriptor), {@code (:RESULT "wit" (kw :OK [d]) (kw :ERROR [d]))}
	 * and {@code (:FLAGS "wit" (kw :KW) ...)} -- each {@code kw} the Clojure keyword of a
	 * label, each {@code :KW} the boundary's. Null when the type has no Clojure value.
	 */
	private static @Nullable LispVal descriptor(ClojureBoundary.Type type) {
		LispString wit = LispString.literal(type.wit());
		switch (type.rep()) {
			case INT, BIGNUM_INT, FLOAT, STRING, CHARACTER, HANDLE -> {
				return ClojureLowering.NIL_CONST;
			}
			case BYTE_STRING -> {
				return BYTE_ARRAY;
			}
			case BOOLEAN -> {
				return BOOL;
			}
			case NIL_OR_VALUE, LIST -> {
				LispVal element = type.element() == null ? null : descriptor(type.element());
				return element == null ? null : new LispCons(
						new LispSymbol(type.rep() == ClojureBoundary.Rep.LIST ? ":LIST" : ":OPTION"), element);
			}
			case TUPLE_LIST -> {
				List<LispVal> out = new ArrayList<>(List.of(new LispSymbol(":TUPLE"), wit));
				for (ClojureBoundary.Part part : type.parts()) {
					LispVal element = part.type() == null ? null : descriptor(part.type());
					if (element == null) {
						return null;
					}
					out.add(element);
				}
				return ClojureLowerUtil.list(out);
			}
			case PLIST, KEYWORD, TAGGED_LIST, KEYWORD_LIST -> {
				String head = switch (type.rep()) {
					case PLIST -> ":RECORD";
					case KEYWORD_LIST -> ":FLAGS";
					default -> ":VARIANT";
				};
				List<LispVal> out = new ArrayList<>(List.of(new LispSymbol(head), wit));
				for (ClojureBoundary.Part part : type.parts()) {
					LispVal entry = labelled(part.label(), part.type());
					if (entry == null) {
						return null;
					}
					out.add(entry);
				}
				return ClojureLowerUtil.list(out);
			}
			case RESULT -> {
				LispVal ok = labelled("ok", type.element());
				LispVal error = labelled("error", type.error());
				return ok == null || error == null ? null
						: ClojureLowerUtil.list(new LispSymbol(":RESULT"), wit, ok, error);
			}
			default -> {
				return null;
			}
		}
	}

	/**
	 * {@code (kw :KW)} for a label, {@code (kw :KW d)} for a label carrying a value of
	 * the type; null when that type has no Clojure value. The boundary's keyword is the
	 * label upcased, as the component lift and lower spell it
	 * ({@code WasmComponentImportCompiler}).
	 */
	private static @Nullable LispVal labelled(String label, ClojureBoundary.@Nullable Type type) {
		LispVal keyword = ClojureCollectionLowering.keywordDatum(label);
		LispSymbol boundary = new LispSymbol(":" + label.toUpperCase(Locale.ROOT));
		if (type == null) {
			return ClojureLowerUtil.list(keyword, boundary);
		}
		LispVal descriptor = descriptor(type);
		return descriptor == null ? null : ClojureLowerUtil.list(keyword, boundary, descriptor);
	}

	/**
	 * Whether a descriptor's value crosses without the walker: as itself, or as a
	 * {@code bool} or a byte array (inline, {@link ClojureWasmLowering.Crossing}).
	 */
	private static boolean inline(LispVal descriptor) {
		return descriptor == ClojureLowering.NIL_CONST || descriptor == BOOL || descriptor == BYTE_ARRAY
				|| isOptionOf(descriptor, ClojureLowering.NIL_CONST) || isOptionOf(descriptor, BOOL);
	}

	private static boolean isOptionOf(LispVal descriptor, LispVal element) {
		return descriptor instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& head.name().equals(":OPTION") && cons.cdr() == element;
	}

	/** The inline crossing of an argument whose descriptor is {@link #inline}. */
	private static ClojureWasmLowering.Crossing outCrossing(LispVal descriptor) {
		if (descriptor == BOOL) {
			return ClojureWasmLowering.Crossing.BOOL;
		}
		if (descriptor == BYTE_ARRAY) {
			return ClojureWasmLowering.Crossing.BYTES;
		}
		return isOptionOf(descriptor, BOOL) ? ClojureWasmLowering.Crossing.NILLABLE_BOOL
				: ClojureWasmLowering.Crossing.PLAIN;
	}

	/**
	 * The inline crossing of a result whose descriptor is {@link #inline}: an
	 * {@code option<bool>} keeps {@code nil} as its absent value.
	 */
	private static ClojureWasmLowering.Crossing inCrossing(LispVal descriptor) {
		if (descriptor == BYTE_ARRAY) {
			return ClojureWasmLowering.Crossing.BYTES;
		}
		return descriptor == BOOL ? ClojureWasmLowering.Crossing.BOOL : ClojureWasmLowering.Crossing.PLAIN;
	}

	/** A Clojure argument as the boundary's value, the walker reading its descriptor. */
	private static LispVal toHost(ClojureLowering ctx, LispVal descriptor, LispVal value, List<String> types) {
		if (inline(descriptor)) {
			return ClojureWasmLowering.toHost(ctx, outCrossing(descriptor), value);
		}
		return ClojureLowerUtil.list(new LispSymbol(OUT), typeArg(ctx, descriptor, types), value);
	}

	/** The boundary's answer as the Clojure value, the walker reading its descriptor. */
	private static LispVal fromHost(ClojureLowering ctx, LispVal descriptor, LispVal value, List<String> types) {
		if (inline(descriptor)) {
			return ClojureWasmLowering.fromHost(ctx, inCrossing(descriptor), value);
		}
		return ClojureLowerUtil.list(new LispSymbol(IN), typeArg(ctx, descriptor, types), value);
	}

	/**
	 * A descriptor as a wrapper reads it: {@code NIL} and {@code :BOOL} as themselves,
	 * anything else the global holding it -- one per distinct type, so the members
	 * crossing one type (an interface's error variant) share its constant.
	 */
	private static LispVal typeArg(ClojureLowering ctx, LispVal descriptor, List<String> types) {
		if (!(descriptor instanceof LispCons)) {
			return descriptor;
		}
		String spelling = descriptor.print();
		String name = ctx.wit.typeNames.get(spelling);
		if (name == null) {
			name = "c%wit%type%" + ctx.wit.typeNames.size();
			ctx.wit.typeNames.put(spelling, name);
			ctx.wit.typeDefinitions.put(name, ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), new LispSymbol(name),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), descriptor)));
		}
		if (!types.contains(name)) {
			types.add(name);
		}
		return new LispSymbol(name);
	}

	/**
	 * The error arm's spec: {@code (key . d)}, the {@code ex-data} key the arm's value is
	 * thrown under and the arm's descriptor. The key travels in the program, so a program
	 * that can throw one prints a map of it the way it prints any map of qualified keys
	 * ({@code ClojureArms.Family.NAMESPACE_MAP}).
	 */
	private static LispVal errorSpec(LispVal error) {
		return new LispCons(ClojureCollectionLowering.keywordDatum(ERROR_KEY), error);
	}

	/**
	 * {@code (defun var (p ...) ...)}: the Clojure var of a member some value of which
	 * needs the walker, each argument converted to the boundary's value and the answer
	 * back. A {@code result}'s error arm throws an {@code ExceptionInfo} holding its
	 * value ({@code rontolisp::%clojure-wit-raise}): on a WASM build the wrapper calls
	 * the raw binding, which answers the result's envelope ({@code <bound>%raw},
	 * {@code WitImportDirective}), so nothing catches; on the interpreter and the JVM the
	 * provider signals the arm as {@code rontolisp:wit-error}, which the wrapper catches
	 * -- unless the interface has no provider, whose own refusal goes on as it is.
	 */
	private static Wrapper richWrapper(ClojureLowering ctx, LispSymbol var, String bound, String iface, String member,
			Crossings crossings) {
		List<String> types = new ArrayList<>();
		List<LispVal> lambdaList = new ArrayList<>();
		List<LispVal> call = new ArrayList<>();
		LispVal error = crossings.error();
		boolean envelope = error != null && !ctx.hostTarget;
		call.add(new LispSymbol(envelope ? bound + "%raw" : bound));
		for (LispVal param : crossings.params()) {
			LispSymbol temp = ctx.freshTemp();
			lambdaList.add(temp);
			call.add(toHost(ctx, param, temp, types));
		}
		LispVal body = ClojureLowerUtil.list(call);
		if (error == null) {
			body = fromHost(ctx, crossings.result(), body, types);
		}
		else if (envelope) {
			body = ClojureLowerUtil.list(new LispSymbol(ANSWER), body, typeArg(ctx, crossings.result(), types),
					typeArg(ctx, errorSpec(error), types), LispString.literal(iface), LispString.literal(member));
		}
		else {
			LispSymbol caught = ctx.freshTemp();
			LispVal arm = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), LispString.literal(iface),
							new LispSymbol(WIT_PROVIDERS)),
					ClojureLowerUtil.list(new LispSymbol(RAISE), LispString.literal(iface), LispString.literal(member),
							typeArg(ctx, errorSpec(error), types),
							ClojureLowerUtil.list(new LispSymbol(WIT_ERROR_PAYLOAD), caught), caught),
					ClojureLowerUtil.list(new LispSymbol(ClojureStateLowering.THROW), caught));
			body = fromHost(ctx, crossings.result(),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("handler-case"), body,
							ClojureLowerUtil.list(new LispSymbol(WIT_ERROR), ClojureLowerUtil.list(caught), arm)),
					types);
		}
		return new Wrapper(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), var, ClojureLowerUtil.list(lambdaList), body),
				List.copyOf(types), error != null);
	}

	/**
	 * A member's row of a {@code provide} table: {@code ("member" (d ...) result)}, plus
	 * the error arm's descriptor when it answers a {@code result}.
	 */
	private static LispVal servedEntry(String member, Crossings crossings) {
		LispVal error = crossings.error() == null ? ClojureLowering.NIL_CONST
				: ClojureLowerUtil.list(crossings.error());
		return new LispCons(LispString.literal(member),
				new LispCons(ClojureLowerUtil.list(crossings.params()), new LispCons(crossings.result(), error)));
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
	 * The conversion wrappers of the members the forms name, not emitted yet, each behind
	 * the descriptor globals it reads that no earlier wrapper brought: what makes a value
	 * cross for the vars a program calls, and only those (a member nothing names stays
	 * unbound under {@code --component}). A wrapper throwing a {@code result}'s error arm
	 * needs the program's exception runtime.
	 * @param ctx the hub
	 * @param forms the program's lowered forms
	 * @return the descriptor globals and the wrapper defuns, in import order
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
		for (Map.Entry<String, Wrapper> wrapper : ctx.wit.wrappers.entrySet()) {
			if (named.contains(wrapper.getKey()) && ctx.wit.wrappersEmitted.add(wrapper.getKey())) {
				for (String type : wrapper.getValue().types()) {
					if (ctx.wit.typesEmitted.add(type)) {
						out.add(ctx.wit.typeDefinitions.get(type));
					}
				}
				out.add(wrapper.getValue().form());
				if (wrapper.getValue().raises()) {
					ctx.usedExInfo = true;
					ctx.hostExceptionClasses.add(ClojureStateLowering.EXCEPTION_INFO);
				}
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
	 * like the directive dropped there. The interface is one an import above names, as
	 * that import wrote it or by its id, answering the canonical id the bindings dispatch
	 * on: its WIT is what the provider's values are converted by, so the provider sees
	 * Clojure values ({@link #adapter}).
	 */
	private static LispVal provideCall(ClojureLowering ctx, List<LispVal> items) {
		String what = NAMESPACE + "/provide";
		int n = items.size() - 1;
		if (n != 2) {
			throw new LispReadException("Wrong number of args (" + n + ") passed to: " + what);
		}
		if (!(items.get(1) instanceof LispString written)) {
			throw new LispReadException(what + " takes the interface as a string, the way an import above wrote it, "
					+ "not " + ClojureWasmLowering.shown(items.get(1)));
		}
		String id = ctx.wit.references.get(written.value());
		if (id == null) {
			throw new LispReadException(what + ": " + written.value() + " is no interface an import above binds"
					+ " -- import it first: its WIT gives the values the provider sees");
		}
		LispVal iface = LispString.literal(id);
		if (!ctx.hostTarget) {
			return iface;
		}
		LispVal provider = ClojureBindingLowering.fnValue(ctx, items.get(2));
		if (!ClojureBindingLowering.holdsRealFun(ctx, items.get(2), provider)) {
			provider = ClojureLowering.realFun(provider);
		}
		Imported imported = Objects.requireNonNull(ctx.wit.imports.get(id));
		LispVal served = imported.served();
		return ClojureLowerUtil.list(new LispSymbol(WIT_PROVIDE), iface,
				served == null ? provider : adapter(ctx, served, imported.raises(), provider));
	}

	/**
	 * The provider as the boundary calls it: a function of the member name and the
	 * boundary's values, converting each argument to its Clojure value and the provider's
	 * answer back ({@code rontolisp::%clojure-wit-serve} over the interface's table). A
	 * {@code result}'s error arm a Clojure provider throws -- an {@code ExceptionInfo}
	 * holding {@link #ERROR_KEY}, what a Clojure caller of a WASM import catches -- is
	 * signalled as {@code rontolisp:wit-error} carrying the boundary's value, which is
	 * the arm every caller reads (a Clojure one turns it back).
	 */
	private static LispVal adapter(ClojureLowering ctx, LispVal served, boolean raises, LispVal provider) {
		LispSymbol table = ctx.freshTemp();
		LispSymbol fn = ctx.freshTemp();
		LispSymbol member = ctx.freshTemp();
		LispSymbol args = ctx.freshTemp();
		LispVal serve = ClojureLowerUtil.list(new LispSymbol(SERVE), table, fn, member, args);
		if (raises) {
			// the arm is read from the exception's data
			ctx.usedExInfo = true;
			LispSymbol caught = ctx.freshTemp();
			LispVal signal = ClojureLowerUtil.list(ClojureLowerUtil.sym("error"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), new LispSymbol(WIT_ERROR)),
					new LispSymbol(":PAYLOAD"),
					ClojureLowerUtil.list(new LispSymbol(ARM_PAYLOAD), table, member, caught),
					new LispSymbol(":MESSAGE"), ClojureLowerUtil.list(new LispSymbol(ARM_MESSAGE), caught));
			LispVal type = ClojureLowerUtil.list(ClojureLowerUtil.sym("and"), ClojureLowerUtil.sym("error"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("satisfies"), new LispSymbol(ARM_P)));
			serve = ClojureLowerUtil.list(ClojureLowerUtil.sym("handler-case"), serve,
					ClojureLowerUtil.list(type, ClojureLowerUtil.list(caught), signal));
		}
		LispVal lambda = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(member, ClojureLowerUtil.sym("&rest"), args), serve);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
				ClojureLowerUtil.list(
						ClojureLowerUtil.list(table, ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), served)),
						ClojureLowerUtil.list(fn, provider)),
				lambda);
	}

}
