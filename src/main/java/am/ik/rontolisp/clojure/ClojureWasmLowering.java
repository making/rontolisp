package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * {@code rontolisp.wasm} of the Clojure lowering: {@code defimport} declares a host
 * function a Clojure var calls, {@code export} -- or a {@code defn}'s
 * {@code {:wasm/export {...}}} metadata -- hands a var to the host. Both LOWER to the
 * Common Lisp directives every backend already reads ({@code rontolisp:wasm-import},
 * {@code rontolisp:wasm-export}); there is no new boundary path. Built in rather than a
 * library because Clojure has no way to name a Common Lisp function.
 *
 * <p>
 * What the lowering adds over the directives is the Clojure side of the crossing:
 * <ul>
 * <li>The host name is the var's name as written (or {@code :as}), always passed
 * explicitly: the directive's default would hand the host the mangled {@code c%ns/name}
 * symbol.</li>
 * <li>A value the two languages spell differently is converted at the boundary by a
 * lowered wrapper: {@code false} is a distinct non-{@code nil} object here, so a
 * {@code :bool} crossing maps it to {@code nil} going out and a host's {@code nil} to
 * {@code false} coming in; an {@code :s-expr} crosses as the Clojure printer's text, read
 * back by the Clojure reader, so a vector, map, keyword or {@code false} round-trips (the
 * directive then declares {@code :string}, the same {@code (ptr, len)} text on every
 * host); a {@code :bytes} crossing hands the host a byte array's octets and wraps the
 * octets it answers as one (an import answering {@code :bytes} fills the byte array
 * passed after its declared parameters). A declaration with no such crossing lowers to
 * exactly the hand-written directive, and an export of a single-arity top-level
 * {@code defn} names the {@code defun} itself; anything else (several arities, a rest
 * parameter, a {@code def}'d function, a multimethod) is exported through a fixed-arity
 * wrapper calling the var.</li>
 * <li>An export is resolved and emitted after the whole program (a session: the buffer)
 * has lowered, so it may name a var defined below it, a redefined {@code defn} exports
 * its newest definition, and the interpreter's {@code wit-export} contract check sees
 * every function.</li>
 * </ul>
 * This slice also holds the export machinery {@link ClojureWitLowering} shares. One slice
 * of {@link ClojureLowering}: every method takes the hub as its first argument and
 * re-enters it for subforms.
 */
final class ClojureWasmLowering {

	/** The namespace this slice lowers. */
	static final String NAMESPACE = "rontolisp.wasm";

	/** The {@code rontolisp.wasm} vars. */
	static final Set<String> VARS = Set.of("defimport", "export");

	/** The metadata key of a {@code defn} the host may call. */
	static final String EXPORT_KEY = ":wasm/export";

	private static final String WASM_IMPORT = "RONTOLISP:WASM-IMPORT";

	private static final String WASM_EXPORT = "RONTOLISP:WASM-EXPORT";

	private ClojureWasmLowering() {
	}

	/**
	 * How a value crosses between a Clojure caller (or callee) and the boundary's Common
	 * Lisp value: unchanged, or through a conversion only a wrapper can make.
	 */
	enum Crossing {

		/** The same value in both languages. */
		PLAIN,

		/**
		 * A boolean: {@code false} becomes {@code nil} on the way to the host, and a
		 * value coming back is read with Clojure truth ({@code nil} and {@code false} are
		 * {@code false}, anything else {@code true}).
		 */
		BOOL,

		/**
		 * A boolean that may be absent (a WIT {@code option<bool>}): {@code false}
		 * becomes {@code nil} on the way to the host, and a value coming back is kept --
		 * {@code nil} is the absent value there.
		 */
		NILLABLE_BOOL,

		/**
		 * An s-expression: the Clojure printer's readable text on the way to the host,
		 * the Clojure reader's value coming back.
		 */
		S_EXPR,

		/**
		 * A byte array: its octets on the way to the host (anything else refused as the
		 * oracle's cast to {@code byte[]}), and the octets coming back as a byte array --
		 * a packed vector as it is, a string (the text a WASM build lifts a WIT
		 * {@code list<u8>} to, a Common Lisp provider's) as its UTF-8 encoding.
		 */
		BYTES,

		/**
		 * A byte array crossing as text (a WIT {@code list<u8>} on a Preview 1 core
		 * module, {@link ClojureBoundary#bytesCrossAsText}): its octets decoded as UTF-8
		 * on the way to the host, and coming back as {@link #BYTES} does.
		 */
		TEXT_BYTES

	}

	/** {@link Crossing#BYTES} on the way to the host. */
	static final String BYTES_TO_HOST = "RONTOLISP::%CLOJURE-BYTES-TO-HOST";

	/**
	 * {@link Crossing#BYTES} and {@link Crossing#TEXT_BYTES} coming back: a producer of
	 * the byte-array family.
	 */
	static final String BYTES_FROM_HOST = "RONTOLISP::%CLOJURE-BYTES-FROM-HOST";

	/** {@link Crossing#TEXT_BYTES} on the way to the host. */
	static final String BYTES_TO_TEXT = "RONTOLISP::%CLOJURE-BYTES-TO-TEXT";

	/**
	 * One declared boundary type.
	 *
	 * @param designator the designator the emitted directive spells (upcased, the
	 * {@code :S-EXPR} crossing as {@code :STRING})
	 * @param crossing how the value crosses on the Clojure side
	 */
	record Designated(String designator, Crossing crossing) {
	}

	/**
	 * One export the program declared, resolved and emitted once the program has lowered.
	 *
	 * @param ns the namespace the declaration sits in, which its var name resolves in
	 * @param var the var's name as written
	 * @param exportName the host-facing name
	 * @param params the parameter types, or {@code null} when the declaration names none
	 * @param result the result type, or {@code null} when the declaration names none
	 * @param at where the declaration sits, for a refusal
	 */
	record PendingExport(String ns, String var, String exportName, @Nullable List<Designated> params,
			@Nullable Designated result, @Nullable SourceLocation at) {
	}

	/** The boundary state one lowering keeps (a session: across its buffers). */
	static final class State {

		/** The exports declared and not yet emitted, in declaration order. */
		final List<PendingExport> exports = new ArrayList<>();

		/**
		 * The parameter shape of each clause of each {@code defn}'s newest definition.
		 */
		final Map<String, List<ClojureLowering.ParamShape>> defnShapes = new HashMap<>();

		/** The {@code defun} symbols of the {@code defn}s lowered at the top level. */
		final Set<String> topLevelDefuns = new HashSet<>();

		/** The export wrapper names given out so far. */
		final Set<String> wrapperNames = new HashSet<>();

		/** Whether the program declared a {@code rontolisp.wasm} export. */
		boolean exported;

	}

	/**
	 * A {@code rontolisp.wasm} call: the declaration recorded (and, for
	 * {@code defimport}, its directive hoisted ahead of the top-level datum), answering
	 * nil -- or the var, at a REPL, for {@code defimport} like a {@code def}.
	 * @param ctx the hub
	 * @param var the var name
	 * @param items the call, head included
	 * @param form the call datum
	 * @return the lowered call
	 */
	static LispVal call(ClojureLowering ctx, String var, List<LispVal> items, @Nullable LispVal form) {
		int n = items.size() - 1;
		if (n != 2) {
			throw new LispReadException("Wrong number of args (" + n + ") passed to: " + NAMESPACE + "/" + var);
		}
		return switch (var) {
			case "defimport" -> defimport(ctx, items);
			case "export" -> export(ctx, items, form);
			default -> throw new LispReadException("unknown name: " + NAMESPACE + "/" + var);
		};
	}

	/**
	 * A {@code rontolisp.wasm} var as a value: both are definitions, which have none (the
	 * oracle's wording for a macro).
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
	 * Whether a head spells {@code defimport}, bare or qualified: what the pre-scan
	 * registers a name for (the aliases are wired only in pass two, so the spelling
	 * decides, like {@code deftest}'s).
	 * @param head the head datum
	 * @return whether it spells {@code defimport}
	 */
	static boolean isDefimportSpelling(LispVal head) {
		if (!(head instanceof LispSymbol s)) {
			return false;
		}
		String name = s.name();
		return name.equals("defimport") || name.endsWith("/defimport") && name.indexOf('/') > 0;
	}

	/**
	 * {@code (defimport name {:from "m" :as "field" :params [...] :returns t})}: the var
	 * interned as a function of the current namespace, bound by a
	 * {@code rontolisp:wasm-import} of its symbol -- or of an internal name behind a
	 * wrapper defun converting the crossing values -- hoisted ahead of the datum.
	 */
	private static LispVal defimport(ClojureLowering ctx, List<LispVal> items) {
		String what = NAMESPACE + "/defimport";
		String name = ClojureLowerUtil.plainName(items.get(1), what);
		ClojureLowerUtil.isTrue(name.indexOf('/') < 0, what + " defines a name of the current namespace, not " + name);
		Map<String, LispVal> options = options(items.get(2), what, List.of(":from", ":as", ":params", ":returns"));
		String from = stringOption(options, ":from", what);
		String as = stringOption(options, ":as", what);
		List<Designated> params = options.containsKey(":params") ? params(ctx, options.get(":params"), what) : null;
		Designated result = options.containsKey(":returns") ? designated(ctx, options.get(":returns"), what, true)
				: null;
		String key = ctx.intern(name, ClojureLowerUtil.nameIsPrivate(items.get(1)));
		ctx.globals.put(key, ClojureLowering.Kind.FUNCTION);
		ctx.macros.remove(key);
		ctx.defnCounts.remove(key);
		ctx.wasm.defnShapes.remove(key);
		ctx.unboundCapable.remove(key);
		ctx.hoisted.addAll(ClojureVarLowering.record(ctx, key, null, items.get(1), null, null, null, false, false));
		LispSymbol var = ClojureLowering.varSym(key);
		List<Designated> declared = new ArrayList<>(params == null ? List.of() : params);
		if (result != null && result.crossing() == Crossing.BYTES) {
			// the read(2) shape: the caller passes the byte array the host fills, and the
			// call answers the value's full length
			declared.add(result);
			result = new Designated(result.designator(), Crossing.PLAIN);
		}
		if (ctx.hostTarget) {
			// the interpreter and the JVM have no WASM host to call: the var is a stub of
			// the declared arity refusing in Clojure's words, where the directive's
			// stub would name the mangled symbol
			List<LispVal> lambdaList = new ArrayList<>();
			for (int i = 0; i < declared.size(); i++) {
				lambdaList.add(ctx.freshTemp());
			}
			ctx.hoisted.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), var, ClojureLowerUtil.list(lambdaList),
					ClojureRefusals.refusal(ClojureRefusals.UNSUPPORTED_OPERATION, LispString.literal(
							name + " is a host function (" + what + "): only a compiled WASM module can call it"))));
			return ctx.nestedDefAnswersVar ? ClojureVarLowering.definedVar(ctx, name) : ClojureLowering.NIL_CONST;
		}
		boolean converts = result != null && result.crossing() != Crossing.PLAIN;
		for (Designated param : declared) {
			converts |= param.crossing() != Crossing.PLAIN;
		}
		LispSymbol bound = converts ? new LispSymbol(var.name() + "%import") : var;
		ctx.hoisted.add(importForm(bound, from, as == null ? name : as, params, result));
		if (converts) {
			List<Crossing> crossings = new ArrayList<>();
			for (Designated param : declared) {
				crossings.add(param.crossing());
			}
			ctx.hoisted
				.add(importWrapper(ctx, var, bound, crossings, result == null ? Crossing.PLAIN : result.crossing()));
		}
		return ctx.nestedDefAnswersVar ? ClojureVarLowering.definedVar(ctx, name) : ClojureLowering.NIL_CONST;
	}

	/**
	 * {@code (rontolisp:wasm-import 'sym [:from "m"] :as "field" [:params '(...)]
	 * [:returns t])}: the hand-written directive, every option the declaration named and
	 * the host name always.
	 */
	private static LispVal importForm(LispSymbol bound, @Nullable String from, String field,
			@Nullable List<Designated> params, @Nullable Designated result) {
		List<LispVal> out = new ArrayList<>();
		out.add(new LispSymbol(WASM_IMPORT));
		out.add(quoted(bound));
		if (from != null) {
			out.add(new LispSymbol(":FROM"));
			out.add(LispString.literal(from));
		}
		out.add(new LispSymbol(":AS"));
		out.add(LispString.literal(field));
		addTypes(out, params, result);
		return ClojureLowerUtil.list(out);
	}

	/**
	 * {@code (defun var (p...) (from-host (bound (to-host p) ...)))}: the Clojure var of
	 * a host function some value of which crosses differently in the two languages.
	 * @param ctx the hub
	 * @param var the var's symbol
	 * @param bound the symbol the directive binds the host function to
	 * @param params how each argument crosses
	 * @param result how the result crosses
	 * @return the defun
	 */
	static LispVal importWrapper(ClojureLowering ctx, LispSymbol var, LispSymbol bound, List<Crossing> params,
			Crossing result) {
		List<LispVal> lambdaList = new ArrayList<>();
		List<LispVal> call = new ArrayList<>();
		call.add(bound);
		for (Crossing crossing : params) {
			LispSymbol param = ctx.freshTemp();
			lambdaList.add(param);
			call.add(toHost(ctx, crossing, param));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), var, ClojureLowerUtil.list(lambdaList),
				fromHost(ctx, result, ClojureLowerUtil.list(call)));
	}

	/**
	 * {@code (export var {:as "name" :params [...] :returns t})}: recorded, resolved and
	 * emitted once the program has lowered.
	 */
	private static LispVal export(ClojureLowering ctx, List<LispVal> items, @Nullable LispVal form) {
		String what = NAMESPACE + "/export";
		String var = ClojureLowerUtil.plainName(items.get(1), what);
		recordExport(ctx, var, items.get(2), form, what);
		return ClojureLowering.NIL_CONST;
	}

	/**
	 * A {@code defn}'s {@code {:wasm/export spec}}, in its attr map or its name's reader
	 * metadata: the export recorded, and both datums answered with the spec quoted, so
	 * the var's metadata holds it as a constant (a literal map would be built at run
	 * time, and a program exporting a {@code defn} would differ from one that does not by
	 * that store).
	 * @param ctx the hub
	 * @param name the {@code defn}'s name
	 * @param nameDatum the name as written, reader metadata included
	 * @param attrMap the attr map datum, or null
	 * @param form the {@code defn} datum
	 * @return the name datum and the attr map, each with its spec quoted
	 */
	static DefnExport defnExport(ClojureLowering ctx, String name, LispVal nameDatum, @Nullable LispVal attrMap,
			LispVal form) {
		String what = "{" + EXPORT_KEY + " ...} on (defn " + name + " ...)";
		boolean[] found = new boolean[1];
		LispVal named = quotedSpecs(ctx, nameDatum, name, form, what, found);
		LispVal attrs = attrMap == null ? null : quotedSpec(ctx, attrMap, name, form, what, found);
		return found[0] ? new DefnExport(named, attrs) : new DefnExport(nameDatum, attrMap);
	}

	/**
	 * A {@code defn}'s name datum and attr map, after {@link #defnExport}.
	 *
	 * @param nameDatum the name datum
	 * @param attrMap the attr map, or null
	 */
	record DefnExport(LispVal nameDatum, @Nullable LispVal attrMap) {
	}

	// The reader spells ^meta name as (%with-meta name meta), nested per layer: each
	// layer's map gets its spec quoted.
	private static LispVal quotedSpecs(ClojureLowering ctx, LispVal nameDatum, String name, LispVal form, String what,
			boolean[] found) {
		List<LispVal> parts = ClojureLowerUtil.items(nameDatum);
		if (parts == null || parts.size() != 3
				|| !ClojureLowerUtil.isSymbolNamed(parts.get(0), ClojureLowerUtil.READER_META)) {
			return nameDatum;
		}
		LispVal inner = quotedSpecs(ctx, parts.get(1), name, form, what, found);
		LispVal meta = ClojureBindingLowering.isAttrMap(parts.get(2))
				? quotedSpec(ctx, parts.get(2), name, form, what, found) : parts.get(2);
		return inner == parts.get(1) && meta == parts.get(2) ? nameDatum
				: ClojureLowerUtil.list(parts.get(0), inner, meta);
	}

	private static LispVal quotedSpec(ClojureLowering ctx, LispVal map, String name, LispVal form, String what,
			boolean[] found) {
		List<LispVal> entries = ClojureLowerUtil.items(map);
		if (entries == null) {
			return map;
		}
		List<LispVal> out = null;
		for (int i = 1; i + 1 < entries.size(); i += 2) {
			if (ClojureLowerUtil.isSymbolNamed(entries.get(i), EXPORT_KEY)) {
				recordExport(ctx, name, entries.get(i + 1), form, what);
				found[0] = true;
				if (out == null) {
					out = new ArrayList<>(entries);
				}
				out.set(i + 1, ClojureLowerUtil.list(new LispSymbol("quote"), entries.get(i + 1)));
			}
		}
		return out == null ? map : ClojureLowerUtil.list(out);
	}

	private static void recordExport(ClojureLowering ctx, String var, LispVal spec, @Nullable LispVal form,
			String what) {
		Map<String, LispVal> options = options(spec, what, List.of(":as", ":params", ":returns"));
		String as = stringOption(options, ":as", what);
		List<Designated> params = options.containsKey(":params") ? params(ctx, options.get(":params"), what) : null;
		Designated result = options.containsKey(":returns") ? designated(ctx, options.get(":returns"), what, true)
				: null;
		int slash = ClojureLowering.qualifierSlash(var);
		String exportName = as != null ? as : slash > 0 ? var.substring(slash + 1) : var;
		ctx.wasm.exports.add(new PendingExport(ctx.currentNs, var, exportName, params, result, locate(ctx, form)));
		ctx.wasm.exported = true;
	}

	/** Where a datum sits, captured now: the reader may be another file's by the end. */
	static @Nullable SourceLocation locate(ClojureLowering ctx, @Nullable LispVal form) {
		return form == null || ctx.reader == null ? null : ctx.reader.locate(form);
	}

	/**
	 * The exports the program declared, as the forms exporting them: each var resolved in
	 * the namespace its declaration sits in, its wrapper (when one is needed) ahead of
	 * its {@code rontolisp:wasm-export}; then the WIT worlds
	 * ({@link ClojureWitLowering}). Called once the program -- or a session's buffer --
	 * has lowered.
	 * @param ctx the hub
	 * @return the forms, to run after everything else
	 */
	static List<LispVal> flush(ClojureLowering ctx) {
		List<LispVal> outerHoisted = ctx.hoisted;
		ctx.hoisted = new ArrayList<>();
		List<LispVal> out = new ArrayList<>();
		// taken off the pending lists first: a refused one is not left for the next
		// buffer of a session to meet again
		List<PendingExport> exports = List.copyOf(ctx.wasm.exports);
		ctx.wasm.exports.clear();
		List<ClojureWitLowering.PendingWorld> worlds = List.copyOf(ctx.wit.worlds);
		ctx.wit.worlds.clear();
		try {
			ClojureWitLowering.refuseCombined(ctx);
			for (PendingExport export : exports) {
				List<Crossing> params = new ArrayList<>();
				List<Designated> declared = export.params() == null ? List.of() : export.params();
				for (Designated param : declared) {
					params.add(param.crossing());
				}
				Crossing result = export.result() == null ? Crossing.PLAIN : export.result().crossing();
				String fn = implementation(ctx, export.ns(), export.var(), export.exportName(), params, result,
						NAMESPACE + "/export", export.at(), out);
				List<LispVal> directive = new ArrayList<>();
				directive.add(new LispSymbol(WASM_EXPORT));
				directive.add(quoted(new LispSymbol(fn)));
				directive.add(new LispSymbol(":AS"));
				directive.add(LispString.literal(export.exportName()));
				addTypes(directive, export.params(), export.result());
				out.add(ClojureLowerUtil.list(directive));
			}
			out.addAll(ClojureWitLowering.flushWorlds(ctx, worlds));
		}
		finally {
			List<LispVal> all = new ArrayList<>(ctx.hoisted);
			all.addAll(out);
			out = all;
			ctx.hoisted = outerHoisted;
		}
		return out;
	}

	/**
	 * The function exporting a var with this many parameters: the {@code defun} of a
	 * single-arity top-level {@code defn} taking exactly those parameters, when no value
	 * converts -- so the export names the definition, as a hand-written directive would
	 * -- else a fixed-arity wrapper calling the var (written into {@code out}), which
	 * reaches any arity of a {@code defn}, a rest parameter, a {@code def}'d function or
	 * a multimethod the way a Clojure call does.
	 * @param ctx the hub
	 * @param ns the namespace the declaration sits in
	 * @param var the var's name as written there
	 * @param exportName the host-facing name
	 * @param params how each parameter crosses
	 * @param result how the result crosses
	 * @param what the declaration, for a refusal
	 * @param at where the declaration sits, for a refusal
	 * @param out where a wrapper goes
	 * @return the exported function's name
	 */
	static String implementation(ClojureLowering ctx, String ns, String var, String exportName, List<Crossing> params,
			Crossing result, String what, @Nullable SourceLocation at, List<LispVal> out) {
		String outerNs = ctx.currentNs;
		ctx.currentNs = ns;
		try {
			String key = ctx.resolveVar(var);
			if (key == null) {
				ClojureNamespaceLowering.refuseMissingVar(ctx, var);
				throw new LispReadException(what + ": " + var + " names no var of " + ns);
			}
			ClojureLowering.Kind kind = ctx.globals.get(key);
			if (kind == ClojureLowering.Kind.MACRO) {
				throw new LispReadException("Can't take value of a macro: #'" + key);
			}
			if (kind == ClojureLowering.Kind.DECLARED && !ctx.session) {
				throw new LispReadException(what + ": #'" + key + " is declared but never defined");
			}
			int arity = params.size();
			List<ClojureLowering.ParamShape> shapes = ctx.wasm.defnShapes.get(key);
			if (shapes != null && !accepts(shapes, arity)) {
				throw new LispReadException(what + ": " + var + " is exported with " + arity + " parameter"
						+ (arity == 1 ? "" : "s") + ", but no arity of (defn " + var + " ...) takes " + arity);
			}
			if (kind == ClojureLowering.Kind.FUNCTION && shapes != null && shapes.size() == 1
					&& !shapes.get(0).variadic() && shapes.get(0).fixed() == arity && result == Crossing.PLAIN
					&& params.stream().allMatch(crossing -> crossing == Crossing.PLAIN)) {
				LispSymbol fn = ctx.symOf(var);
				if (ctx.wasm.topLevelDefuns.contains(fn.name())) {
					return fn.name();
				}
			}
			String wrapper = wrapperName(ctx, key, exportName);
			out.add(exportWrapper(ctx, wrapper, var, params, result));
			return wrapper;
		}
		catch (LispReadException ex) {
			throw ex.location() != null || at == null ? ex : new LispReadException(String.valueOf(ex.getMessage()), at);
		}
		finally {
			ctx.currentNs = outerNs;
		}
	}

	/** Whether some arity of a {@code defn} takes this many arguments. */
	private static boolean accepts(List<ClojureLowering.ParamShape> shapes, int arity) {
		for (ClojureLowering.ParamShape shape : shapes) {
			if (shape.fixed() >= 0 && (shape.variadic() ? arity >= shape.fixed() : arity == shape.fixed())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A wrapper name for one export of a var: the var's symbol behind a lone-{@code %}
	 * suffix no identifier mangles to, numbered when the var is exported again.
	 */
	private static String wrapperName(ClojureLowering ctx, String key, String exportName) {
		String base = ClojureLowering.varSym(key).name() + "%export";
		String name = base;
		for (int n = 2; !ctx.wasm.wrapperNames.add(name); n++) {
			name = base + n;
		}
		return name;
	}

	/**
	 * {@code (defun wrapper (#:wasm-in0 ...) (to-host (let* ((#:wasm-arg0 (from-host
	 * #:wasm-in0)) ...) (var #:wasm-arg0 ...))))}: the call is the lowering of a Clojure
	 * call of the var, so it reaches the var whatever defined it. The {@code #:} names
	 * lower to themselves (a macro gensym's spelling), so no program name can capture
	 * one.
	 */
	private static LispVal exportWrapper(ClojureLowering ctx, String wrapper, String var, List<Crossing> params,
			Crossing result) {
		List<LispVal> lambdaList = new ArrayList<>();
		List<LispVal> bindings = new ArrayList<>();
		List<LispVal> call = new ArrayList<>();
		call.add(new LispSymbol(var));
		for (int i = 0; i < params.size(); i++) {
			LispSymbol in = new LispSymbol("#:wasm-in" + i);
			LispSymbol arg = new LispSymbol("#:wasm-arg" + i);
			lambdaList.add(in);
			bindings.add(ClojureLowerUtil.list(arg, fromHost(ctx, params.get(i), in)));
			call.add(arg);
		}
		LispVal body = ctx.lower(ClojureLowerUtil.list(call));
		if (!bindings.isEmpty()) {
			body = ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(bindings), body);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), new LispSymbol(wrapper),
				ClojureLowerUtil.list(lambdaList), toHost(ctx, result, body));
	}

	/**
	 * A Clojure value as the boundary's Common Lisp value.
	 * @param ctx the hub
	 * @param crossing how it crosses
	 * @param value the lowered value
	 * @return the converted value
	 */
	static LispVal toHost(ClojureLowering ctx, Crossing crossing, LispVal value) {
		return switch (crossing) {
			case PLAIN -> value;
			case BOOL, NILLABLE_BOOL -> {
				LispSymbol temp = ctx.freshTemp();
				yield ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(ClojureLowerUtil.list(temp, value)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), temp, ctx.falseVariable),
								ClojureLowering.NIL_CONST, temp));
			}
			case S_EXPR ->
				ClojureStringLowering.strOf(ctx, value, LispString.literal("nil"), ClojureLowering.TRUE_CONST);
			case BYTES -> ClojureLowerUtil.list(new LispSymbol(BYTES_TO_HOST), value);
			case TEXT_BYTES -> ClojureLowerUtil.list(new LispSymbol(BYTES_TO_TEXT), value);
		};
	}

	/**
	 * A boundary's Common Lisp value as the Clojure value.
	 * @param ctx the hub
	 * @param crossing how it crosses
	 * @param value the lowered value
	 * @return the converted value
	 */
	static LispVal fromHost(ClojureLowering ctx, Crossing crossing, LispVal value) {
		return switch (crossing) {
			case PLAIN, NILLABLE_BOOL -> value;
			case BOOL -> ctx.ifFalsey(value, ClojureLowering.TRUE_CONST, ctx.falseVariable);
			case S_EXPR -> ClojureReadLowering.readStringOf(ctx, value);
			case BYTES, TEXT_BYTES -> ClojureLowerUtil.list(new LispSymbol(BYTES_FROM_HOST), value);
		};
	}

	/** The {@code :params}/{@code :returns} options of a directive, as declared. */
	private static void addTypes(List<LispVal> out, @Nullable List<Designated> params, @Nullable Designated result) {
		if (params != null) {
			List<LispVal> designators = new ArrayList<>();
			for (Designated param : params) {
				designators.add(new LispSymbol(param.designator()));
			}
			out.add(new LispSymbol(":PARAMS"));
			out.add(quoted(ClojureLowerUtil.list(designators)));
		}
		if (result != null) {
			out.add(new LispSymbol(":RETURNS"));
			out.add(new LispSymbol(result.designator()));
		}
	}

	private static LispVal quoted(LispVal value) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), value);
	}

	/**
	 * A datum as a refusal shows it: in Clojure notation where the source renderer spells
	 * it, else as the reader holds it.
	 * @param datum the datum
	 * @return its spelling
	 */
	static String shown(LispVal datum) {
		String source = ClojureStringLowering.prSource(datum);
		return source != null ? source : datum.print();
	}

	/**
	 * An options map literal: its keyword keys, each one of the allowed (an
	 * {@code :async} one refused by name until a Clojure future maps onto
	 * {@code rontolisp:await}'s), to the value datums.
	 * @param datum the map datum
	 * @param what the declaration, for a refusal
	 * @param allowed the keys it takes
	 * @return the options, in the order written
	 */
	static Map<String, LispVal> options(LispVal datum, String what, List<String> allowed) {
		List<LispVal> items = ClojureLowerUtil.items(datum);
		if (items == null || items.isEmpty() || !ClojureLowerUtil.isSymbolNamed(items.get(0), "%hash-map")) {
			throw new LispReadException(what + " takes an options map, not " + shown(datum));
		}
		Map<String, LispVal> out = new LinkedHashMap<>();
		for (int i = 1; i + 1 < items.size(); i += 2) {
			String key = items.get(i) instanceof LispSymbol k && k.name().startsWith(":") ? k.name() : null;
			LispVal value = items.get(i + 1);
			if (":async".equals(key) && !ClojureLowerUtil.isSymbolNamed(value, "false")
					&& !ClojureLowerUtil.isSymbolNamed(value, "nil")) {
				throw new LispReadException(what + ": :async is not supported yet -- a Clojure future does not map "
						+ "onto the rontolisp:await future a suspending crossing answers");
			}
			if (":async".equals(key)) {
				continue;
			}
			if (key == null || !allowed.contains(key)) {
				throw new LispReadException(what + ": unknown option " + (key != null ? key : shown(items.get(i)))
						+ " (it takes " + String.join(" ", allowed) + ")");
			}
			out.put(key, value);
		}
		return out;
	}

	/** A string option's value, or null when the map does not name it. */
	static @Nullable String stringOption(Map<String, LispVal> options, String key, String what) {
		LispVal value = options.get(key);
		if (value == null) {
			return null;
		}
		if (!(value instanceof LispString string)) {
			throw new LispReadException(what + ": " + key + " takes a string, not " + shown(value));
		}
		return string.value();
	}

	/** A {@code :params} vector of keyword designators. */
	private static List<Designated> params(ClojureLowering ctx, @Nullable LispVal datum, String what) {
		List<LispVal> items = datum == null ? null : ClojureLowerUtil.items(datum);
		if (items == null || items.isEmpty() || items.get(0) != ClojureReader.VECTOR) {
			throw new LispReadException(
					what + ": :params takes a vector of type keywords, not " + (datum == null ? "nil" : shown(datum)));
		}
		List<Designated> out = new ArrayList<>();
		for (LispVal type : items.subList(1, items.size())) {
			out.add(designated(ctx, type, what, false));
		}
		return out;
	}

	/**
	 * One type keyword: the boundary's vocabulary ({@link ClojureBoundary#designators}),
	 * {@code :void} for a result.
	 */
	private static Designated designated(ClojureLowering ctx, LispVal datum, String what, boolean result) {
		if (!(datum instanceof LispSymbol keyword) || !keyword.name().startsWith(":")) {
			throw new LispReadException(what + " takes type keywords, not " + shown(datum));
		}
		String upper = keyword.name().toUpperCase(Locale.ROOT);
		if (result && upper.equals(":VOID")) {
			return new Designated(upper, Crossing.PLAIN);
		}
		Set<String> designators = ctx.boundary.designators();
		if (!designators.contains(upper)) {
			Set<String> known = new TreeSet<>();
			for (String one : designators) {
				known.add(one.toLowerCase(Locale.ROOT));
			}
			throw new LispReadException(what + ": unknown type " + keyword.name() + " (the boundary carries "
					+ String.join(" ", known) + (result ? " :void" : "") + ")");
		}
		return switch (upper) {
			case ":BOOL" -> new Designated(upper, Crossing.BOOL);
			case ":S-EXPR" -> new Designated(":STRING", Crossing.S_EXPR);
			case ":BYTES" -> new Designated(upper, Crossing.BYTES);
			default -> new Designated(upper, Crossing.PLAIN);
		};
	}

}
