package am.ik.rontolisp.clojure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispHashTable;
import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * Protocol forms of the Clojure lowering: protocols, records, reify and extension.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureProtocolLowering {

	private ClojureProtocolLowering() {
	}

	/**
	 * The tag heading a record value: a record is
	 * {@code (LIST :C%RECORD (:C%KEYWORD "Name") (fields...) table "ns.Name")}, beside
	 * the {@code (:C%SET table)} and {@code (:C%KEYWORD spelling)} wrappers; the trailing
	 * class name is what the printer spells ({@code #ns.Name{...}}). The table is an
	 * {@code equal} table like every map, so every backend prints, hashes and compares it
	 * through the runtime they already share; no backend learns a new value shape.
	 */
	static final LispSymbol RECORD_TAG = new LispSymbol(":C%RECORD");

	/**
	 * The tag heading a deftype value: the same shape as a record, class name included,
	 * but opaque to the map verbs (reads miss, writers signal, {@code =} is identity),
	 * like the oracle.
	 */
	static final LispSymbol TYPE_TAG = new LispSymbol(":C%TYPE");

	/**
	 * The tag heading a reify value: {@code (LIST :C%REIFY (gensym))}, one fresh tag per
	 * evaluation, so two instances never share a dispatch row. Opaque like a deftype.
	 */
	static final LispSymbol REIFY_TAG = new LispSymbol(":C%REIFY");

	/**
	 * The runtime reader of a value's protocol-dispatch tag, as spelled in programs.
	 */
	static final String PROTOCOL_TAG = "C%PROTOCOL-TAG";

	/**
	 * Whether the form holds a record: a cons headed by the tag with a field list and a
	 * table behind it. The full shape check keeps user data from misfiring the test, like
	 * {@link #isSetForm}.
	 */
	static LispVal isRecordForm(LispVal form) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), form),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), form), RECORD_TAG),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cddr"), form)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cadddr"), form)));
	}

	/** Whether the form holds a deftype value: the same shape check over its tag. */
	static LispVal isDeftypeForm(LispVal form) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), form),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), form), TYPE_TAG),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cddr"), form)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cadddr"), form)));
	}

	/** Whether the form holds a reify value: a cons headed by its tag. */
	static LispVal isReifyForm(LispVal form) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), form),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), form), REIFY_TAG));
	}

	/** Whether the form holds any typed value: a record, a deftype or a reify. */
	static LispVal isTypedForm(LispVal form) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("or"), isRecordForm(form), isDeftypeForm(form),
				isReifyForm(form));
	}

	/** The dispatch tag inside a record or deftype value: {@code (CADR form)}. */
	static LispVal typedTagOf(LispVal form) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), form);
	}

	/** The entry table inside a record or deftype value: {@code (CADDDR form)}. */
	static LispVal typedTableOf(LispVal form) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("cadddr"), form);
	}

	/**
	 * The declared field keywords inside a record or deftype value: {@code (CADDR form)}.
	 */
	static LispVal typedFieldsOf(LispVal form) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("caddr"), form);
	}

	/**
	 * The host class name inside a record or deftype value: {@code (NTH 4 form)}, the
	 * string a record's literal spells and an instance-call refusal names.
	 */
	static LispVal typedClassOf(LispVal form) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("nth"), new LispInteger(4), form);
	}

	/** A record's construction: {@code (LIST :C%RECORD tag fields table className)}. */
	static LispVal wrapRecord(LispVal tag, LispVal fields, LispVal table, LispVal className) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), RECORD_TAG, tag, fields, table, className);
	}

	/**
	 * A rebuilt entry table back in the record it came from: the same tag, fields and
	 * class name.
	 */
	static LispVal rewrapRecord(LispVal record, LispVal table) {
		return wrapRecord(typedTagOf(record), typedFieldsOf(record), table, typedClassOf(record));
	}

	/**
	 * A deftype's construction: {@code (LIST :C%TYPE tag fields table className slots?)}.
	 * The table holds the immutable fields ({@code .-field} reads it); a type with
	 * mutable fields appends their slot vector ({@link #DEFTYPE_SLOTS}), which only its
	 * inline methods read and write.
	 */
	static LispVal wrapDeftype(LispVal tag, LispVal fields, LispVal table, LispVal className, @Nullable LispVal slots) {
		if (slots == null) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), TYPE_TAG, tag, fields, table, className);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), TYPE_TAG, tag, fields, table, className, slots);
	}

	/** The index of a deftype's mutable-field slot vector, behind its class name. */
	static final int DEFTYPE_SLOTS = 5;

	/** A type's dispatch tag as data: the keyword of its spelling. */
	static LispVal typeTagForm(String name) {
		return ClojureCollectionLowering.keywordForm(name);
	}

	/**
	 * The protocol runtime, spliced once per program behind the false binding: the tag
	 * reader every dispatcher and {@code satisfies?} shares. A record, deftype or reify
	 * answers its own tag; anything else answers its {@code class} kind keyword, so
	 * extending to a host kind ({@code String}, {@code Number}, ...) dispatches on the
	 * same spelling {@code class} answers. What no branch names (a host object from
	 * interop on the backends that have one) answers a fresh one-list no row can hold, so
	 * the {@code Object} default still catches it instead of signalling.
	 */
	static List<LispVal> protocolRuntime(ClojureLowering ctx) {
		LispSymbol one = new LispSymbol("x");
		List<LispVal> branches = new ArrayList<>();
		branches.add(ClojureLowerUtil.list(isRecordForm(one), typedTagOf(one)));
		branches.add(ClojureLowerUtil.list(isDeftypeForm(one), typedTagOf(one)));
		branches.add(ClojureLowerUtil.list(isReifyForm(one), typedTagOf(one)));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), one),
				ClojureCollectionLowering.keywordForm("nil")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), one, ctx.falseVariable),
				ClojureCollectionLowering.keywordForm("boolean")));
		branches.add(ClojureLowerUtil.list(
				ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), one, ClojureLowering.TRUE_CONST),
				ClojureCollectionLowering.keywordForm("boolean")));
		branches.add(ClojureLowerUtil.list(ClojureFilterLowering.keywordTest(one),
				ClojureCollectionLowering.keywordForm("keyword")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("symbolp"), one),
				ClojureCollectionLowering.keywordForm("symbol")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("characterp"), one),
				ClojureCollectionLowering.keywordForm("char")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("stringp"), one),
				ClojureCollectionLowering.keywordForm("string")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("numberp"), one),
				ClojureCollectionLowering.keywordForm("number")));
		branches.add(ClojureLowerUtil.list(ClojureCollectionLowering.isSetForm(one),
				ClojureCollectionLowering.keywordForm("set")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("hash-table-p"), one),
				ClojureCollectionLowering.keywordForm("map")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("vectorp"), one),
				ClojureCollectionLowering.keywordForm("vector")));
		// a sorted map or set dispatches as a map or set (its wrapper is a cons): an arm
		// a program building no sorted collection sheds
		branches.add(ClojureLowerUtil.list(ClojureSortedLowering.sortedMapTest(one),
				ClojureCollectionLowering.keywordForm("map")));
		branches.add(ClojureLowerUtil.list(ClojureSortedLowering.sortedSetTest(one),
				ClojureCollectionLowering.keywordForm("set")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), one),
				ClojureCollectionLowering.keywordForm("list")));
		branches.add(ClojureLowerUtil.list(ClojureLowerUtil.list(ClojureLowerUtil.sym("functionp"), one),
				ClojureCollectionLowering.keywordForm("function")));
		branches.add(ClojureLowerUtil.list(ClojureStateLowering.isAtomForm(one),
				ClojureCollectionLowering.keywordForm("atom")));
		branches.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)));
		return List.of(ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), new LispSymbol(PROTOCOL_TAG),
				ClojureLowerUtil.list(List.of(one)), ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), branches)));
	}

	/**
	 * The pre-scan half of {@code defprotocol}: registers the protocol (parsed pure, so
	 * parsing twice is harmless) plus the protocol name and every method name, so a
	 * dispatch call may stand above the definition, like {@code defn}.
	 */
	static ClojureLowering.ProtocolDef declareProtocol(ClojureLowering ctx, List<LispVal> items) {
		String name = ClojureLowerUtil.plainName(items.get(1), "defprotocol");
		String key = ctx.intern(name, ClojureLowerUtil.nameIsPrivate(items.get(1)));
		ClojureLowering.ProtocolDef def = parseProtocol(ctx, key, name, items);
		ctx.protocols.put(key, def);
		ctx.globals.put(key, ClojureLowering.Kind.VARIABLE);
		ctx.macros.remove(key); // a definition wins over the macro it shadows
		for (String method : def.methods()) {
			String methodKey = ctx.intern(method, false);
			ctx.globals.put(methodKey, ClojureLowering.Kind.FUNCTION);
			ctx.macros.remove(methodKey); // a definition wins over the macro it shadows
		}
		return def;
	}

	/**
	 * The protocol a name resolves to: the current namespace's own, a referred one, or
	 * one reached through an alias or its namespace's full name; null when it names none.
	 */
	static ClojureLowering.@Nullable ProtocolDef protocolOf(ClojureLowering ctx, String name) {
		String key = ctx.isLocal(name) ? null : ctx.resolveVar(name);
		return key == null ? null : ctx.protocols.get(key);
	}

	/**
	 * The protocols a {@code defrecord}/{@code deftype} body names (var keys) and the
	 * protocol methods it implements, as {@link ClojureLowering#inlineMethodKey}s: read
	 * leniently, since the pre-scan meets the datum before its checks run (the definition
	 * refuses what is malformed), and a group naming no known protocol contributes
	 * nothing. A method belongs to its group's protocol when that one declares it, else
	 * to the first protocol of the body that does, since a method stands under any group
	 * ({@link #typeBody}); one no protocol declares is an interface's or
	 * {@code Object}'s.
	 */
	private record BodyProtocols(Set<String> protocols, Set<String> methods) {

		static BodyProtocols of(ClojureLowering ctx, List<LispVal> items) {
			List<LispVal> body = items.subList(3, items.size());
			Set<String> protocols = new LinkedHashSet<>();
			for (LispVal datum : body) {
				String key = protocolKeyOf(ctx, datum);
				if (key != null) {
					protocols.add(key);
				}
			}
			Set<String> inline = new HashSet<>();
			String protocolKey = null;
			for (LispVal datum : body) {
				if (datum instanceof LispSymbol s && !s.name().startsWith(":")) {
					protocolKey = protocolKeyOf(ctx, datum);
				}
				else if (ClojureLowerUtil.items(datum) instanceof List<LispVal> impl && !impl.isEmpty()
						&& impl.get(0) instanceof LispSymbol method) {
					String owner = declaresMethod(ctx, protocolKey, method.name()) ? protocolKey : null;
					for (String key : protocols) {
						if (owner == null && declaresMethod(ctx, key, method.name())) {
							owner = key;
						}
					}
					if (owner != null) {
						inline.add(ClojureLowering.inlineMethodKey(owner, method.name()));
					}
				}
			}
			return new BodyProtocols(protocols, inline);
		}

		/** Whether the protocol of the var key declares the method. */
		private static boolean declaresMethod(ClojureLowering ctx, @Nullable String protocolKey, String method) {
			ClojureLowering.ProtocolDef def = protocolKey == null ? null : ctx.protocols.get(protocolKey);
			return def != null && def.methods().contains(method);
		}

		/** The var key of the protocol a group symbol names, or null. */
		private static @Nullable String protocolKeyOf(ClojureLowering ctx, LispVal datum) {
			if (!(datum instanceof LispSymbol s) || s.name().startsWith(":")) {
				return null;
			}
			String key = ctx.isLocal(s.name()) ? null : ctx.lookupVar(s.name());
			return key != null && ctx.protocols.containsKey(key) ? key : null;
		}

	}

	/**
	 * The interface a protocol defines, as the oracle names it: its namespace and name
	 * munged ({@code my-app.core/my-p?} is {@code my_app.core.my_p_QMARK_}).
	 */
	static String interfaceName(String protocolKey) {
		int slash = protocolKey.indexOf('/');
		return munge(protocolKey.substring(0, slash)) + "." + munge(protocolKey.substring(slash + 1));
	}

	/** The oracle's {@code munge}: each special character to its word. */
	private static String munge(String name) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			String word = MUNGED.get(c);
			if (word != null) {
				out.append(word);
			}
			else {
				out.append(c);
			}
		}
		return out.toString();
	}

	/** The oracle's {@code Compiler.CHAR_MAP}. */
	private static final Map<Character, String> MUNGED = Map.ofEntries(Map.entry('-', "_"), Map.entry(':', "_COLON_"),
			Map.entry('+', "_PLUS_"), Map.entry('>', "_GT_"), Map.entry('<', "_LT_"), Map.entry('=', "_EQ_"),
			Map.entry('~', "_TILDE_"), Map.entry('!', "_BANG_"), Map.entry('@', "_CIRCA_"), Map.entry('#', "_SHARP_"),
			Map.entry('\'', "_SINGLEQUOTE_"), Map.entry('"', "_DOUBLEQUOTE_"), Map.entry('%', "_PERCENT_"),
			Map.entry('^', "_CARET_"), Map.entry('&', "_AMPERSAND_"), Map.entry('*', "_STAR_"), Map.entry('|', "_BAR_"),
			Map.entry('{', "_LBRACE_"), Map.entry('}', "_RBRACE_"), Map.entry('[', "_LBRACK_"),
			Map.entry(']', "_RBRACK_"), Map.entry('/', "_SLASH_"), Map.entry('\\', "_BSLASH_"),
			Map.entry('?', "_QMARK_"));

	/**
	 * The protocol whose interface is the class name, with its var key, or null.
	 */
	static Map.@Nullable Entry<String, ClojureLowering.ProtocolDef> protocolOfInterface(ClojureLowering ctx,
			String className) {
		for (Map.Entry<String, ClojureLowering.ProtocolDef> protocol : ctx.protocols.entrySet()) {
			if (interfaceName(protocol.getKey()).equals(className)) {
				return protocol;
			}
		}
		return null;
	}

	/**
	 * {@code instance?} of a protocol's interface: a record or deftype whose body names
	 * the protocol (by class, read off the definitions at lowering), or a reify holding a
	 * row under its fresh tag in the protocol's body table (every protocol its body names
	 * has one, and no extension reaches the tag). An extension row is no interface, like
	 * the oracle.
	 */
	static LispVal implementsForm(ClojureLowering ctx, Map.Entry<String, ClojureLowering.ProtocolDef> protocol,
			LispVal value) {
		String protocolKey = protocol.getKey();
		List<String> classes = ctx.types.values()
			.stream()
			.filter(type -> type.protocols().contains(protocolKey))
			.map(ClojureLowering.TypeDef::className)
			.sorted()
			.toList();
		ctx.usedProtocols = true;
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-IMPLEMENTS-P"), value,
				protocol.getValue().inlineTable(),
				classes.isEmpty() ? ClojureLowering.NIL_CONST : ClojureThrowables.quoted(classes));
	}

	/**
	 * The pre-scan half of {@code defrecord}/{@code deftype}: registers the type plus its
	 * constructors in the current namespace, so a constructor call may stand above the
	 * definition, like {@code defn}. The type name itself is no value (the oracle answers
	 * a host class, which no wasm backend has).
	 */
	static ClojureLowering.TypeDef declareRecordType(ClojureLowering ctx, List<LispVal> items) {
		boolean record = ClojureLowerUtil.isSymbolNamed(items.get(0), "defrecord");
		String name = ClojureLowerUtil.plainName(items.get(1), record ? "defrecord" : "deftype");
		List<String> fields = recordFields(ctx, items);
		List<String> mutable = mutableFields(items);
		ClojureLowerUtil.isTrue(!record || mutable.isEmpty(),
				":volatile-mutable or :unsynchronized-mutable not supported for record fields");
		BodyProtocols body = BodyProtocols.of(ctx, items);
		ClojureLowering.TypeDef def = new ClojureLowering.TypeDef(record, fields, name,
				ctx.currentNs.replace('-', '_') + "." + name, mutable, body.methods(), body.protocols());
		ctx.types.put(ClojureLowering.varKey(ctx.currentNs, name), def);
		ctx.globals.put(ctx.intern("->" + name, false), ClojureLowering.Kind.FUNCTION);
		if (record) {
			ctx.globals.put(ctx.intern("map->" + name, false), ClojureLowering.Kind.FUNCTION);
		}
		return def;
	}

	/**
	 * Parses a {@code defprotocol} datum without emitting: the name's method names in
	 * definition order, with the table and default globals. Docstrings and attr maps are
	 * skipped, like {@code defn} and {@code defmulti}; a signature is the method name
	 * over one parameter vector per arity, {@code (m [x] [x y] "doc")}, each taking the
	 * target first, like the oracle's.
	 */
	static ClojureLowering.ProtocolDef parseProtocol(ClojureLowering ctx, String key, String name,
			List<LispVal> items) {
		int at = 2;
		if (at < items.size() && items.get(at) instanceof LispString) {
			at++; // the docstring
		}
		if (at < items.size() && ClojureBindingLowering.isAttrMap(items.get(at))) {
			at++; // the attr map
		}
		boolean viaMetadata = false;
		for (; at + 1 < items.size() && items.get(at) instanceof LispSymbol opt
				&& opt.name().startsWith(":"); at += 2) {
			if (opt.name().equals(":extend-via-metadata")) {
				LispVal flag = items.get(at + 1);
				ClojureLowerUtil.isTrue(
						flag instanceof LispSymbol f
								&& (f.name().equals("true") || f.name().equals("false") || f.name().equals("nil")),
						"extend-via-metadata takes true or false, not "
								+ (flag instanceof LispSymbol named ? named.name() : flag.print()));
				viaMetadata = ((LispSymbol) flag).name().equals("true");
			}
			else {
				throw new LispReadException("defprotocol option " + opt.name() + " is not supported yet");
			}
		}
		Set<String> methods = new LinkedHashSet<>();
		Map<String, Set<Integer>> arities = new HashMap<>();
		for (; at < items.size(); at++) {
			List<LispVal> sig = ClojureLowerUtil.items(items.get(at));
			if (sig == null || sig.isEmpty() || !(sig.get(0) instanceof LispSymbol)) {
				throw new LispReadException("defprotocol takes method signatures, not " + items.get(at).print());
			}
			String method = ((LispSymbol) sig.get(0)).name();
			ClojureLowerUtil.isTrue(!method.startsWith(":"),
					"defprotocol takes method signatures, not " + items.get(at).print());
			ClojureLowerUtil.isTrue(methods.add(method), "Function " + method + " in protocol " + name
					+ " was redefined. Specify all arities in single definition.");
			int marg = 1;
			if (marg < sig.size() && sig.get(marg) instanceof LispString) {
				marg++; // a docstring ahead of the parameter vectors
			}
			Set<Integer> counts = new LinkedHashSet<>();
			for (; marg < sig.size()
					&& ClojureBindingLowering.isVectorDatum(ClojureLowerUtil.stripMeta(sig.get(marg))); marg++) {
				List<LispVal> params = ClojureLowerUtil.bindingItems(ClojureLowerUtil.stripMeta(sig.get(marg)),
						"the method " + method + " of");
				ClojureLowerUtil.isTrue(!params.isEmpty(),
						"Definition of function " + method + " in protocol " + name + " must take at least one arg.");
				ClojureLowerUtil.isTrue(counts.add(params.size()),
						"defprotocol " + name + " declares " + method + " twice with " + params.size() + " parameters");
			}
			ClojureLowerUtil.isTrue(!counts.isEmpty(),
					"a defprotocol method signature is its name over one parameter vector per arity, not "
							+ items.get(at).print());
			arities.put(method, Set.copyOf(counts));
		}
		ClojureLowerUtil.isTrue(!methods.isEmpty(), "defprotocol takes at least one method: " + name);
		String var = ClojureLowering.varSym(key).name();
		return new ClojureLowering.ProtocolDef(methods, arities, new LispSymbol(var + "%methods"),
				new LispSymbol(var + "%default"), viaMetadata ? new LispSymbol(var + "%inline") : null,
				REDUCER_ROWS.get(key));
	}

	/**
	 * The library functions storing a record's, deftype's or reify's row of the two
	 * reducing protocols of {@code clojure.core.protocols}, by the protocol's var key:
	 * {@code reduce} and the verbs built on it hand such a value to its
	 * {@code CollReduce} row, {@code reduce-kv} to its {@code IKVReduce} row
	 * ({@code clojure.lisp}, "CollReduce and IKVReduce"). The store is what makes a value
	 * of the reducible family ({@link ClojureArms.Family#REDUCIBLE}): a program storing
	 * none has the arms of those verbs folded.
	 */
	private static final Map<String, String> REDUCER_ROWS = Map.of("clojure.core.protocols/CollReduce",
			"RONTOLISP::%CLOJURE-COLL-REDUCER-ROW", "clojure.core.protocols/IKVReduce",
			"RONTOLISP::%CLOJURE-KV-REDUCER-ROW");

	/**
	 * {@code (defprotocol name doc? (method [target & args] doc?)...)}: a method-table
	 * global plus an {@code Object}-default global per protocol, one dispatcher
	 * {@code defun} per method, and the protocol name bound to its table. A call
	 * dispatches on the target's tag (exact match, then the {@code Object} row); a miss
	 * with no {@code Object} row signals, like the oracle.
	 */
	static List<LispVal> defprotocolForms(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "defprotocol takes a name and methods");
		String name = ClojureLowerUtil.plainName(items.get(1), "defprotocol");
		ClojureLowering.ProtocolDef def = declareProtocol(ctx, items);
		List<LispVal> forms = new ArrayList<>();
		forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), def.methodsVar(),
				ClojureCollectionLowering.makeTable()));
		forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), def.defaultVar(), ClojureLowering.NIL_CONST));
		if (def.inlineVar() != null) {
			forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), def.inlineVar(),
					ClojureCollectionLowering.makeTable()));
		}
		for (String method : def.methods()) {
			forms.add(dispatcherDefun(ctx, name, method, def));
		}
		forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"),
				ClojureLowering.varSym(ClojureLowering.varKey(ctx.currentNs, name)), def.methodsVar()));
		ctx.usedProtocols = true;
		return forms;
	}

	/**
	 * One protocol-method dispatcher: the {@code &rest} shape {@code defmulti} takes, so
	 * arities (fixed or variadic) fall out of the stored lambda. No arguments signals the
	 * wrong-count error instead of dispatching on nil, like the oracle's arity error. A
	 * protocol declared {@code :extend-via-metadata true} looks in three places, in the
	 * oracle's order: the inline table (a body implementation), the target's metadata
	 * under the protocol-qualified method symbol (invoked like any {@code IFn}), then the
	 * extension rows and the {@code Object} default.
	 */
	static LispVal dispatcherDefun(ClojureLowering ctx, String protocol, String method,
			ClojureLowering.ProtocolDef def) {
		LispSymbol args = ctx.freshTemp();
		LispSymbol tag = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispSymbol inner = ctx.freshTemp();
		LispSymbol found = ctx.freshTemp();
		LispSymbol chosen = ctx.freshTemp();
		// the tag's row, else the Object row of this method, else the miss
		LispVal objectRow = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), def.defaultVar()), miss,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), ClojureCollectionLowering.keywordForm(method),
						def.defaultVar(), miss));
		LispVal extended = ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(
						ClojureLowerUtil.list(inner,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), tag, def.methodsVar(), miss)),
						ClojureLowerUtil.list(found, rowMethod(inner, miss, method)),
						ClojureLowerUtil.list(chosen, ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), found, miss), objectRow, found)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), chosen, miss),
						ClojureRefusals.refusal(ClojureRefusals.ILLEGAL_ARGUMENT,
								LispString.literal("No implementation of method :" + method + " of protocol :"
										+ protocol + " found")),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"), chosen, args)));
		if (def.inlineVar() != null) {
			LispSymbol direct = ctx.freshTemp();
			LispSymbol viaMeta = ctx.freshTemp();
			LispVal metaKey = ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"),
					ClojureLowerUtil.idSym(ClojureLowering.varKey(ctx.currentNs, method)));
			LispVal metaOrExtended = ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(viaMeta,
							ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-META-METHOD"),
									ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args), metaKey)))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), viaMeta, ctx.callableApply(viaMeta, args),
							extended));
			extended = ClojureLowerUtil
				.list(ClojureLowerUtil.sym("let*"),
						ClojureLowerUtil
							.list(List.of(
									ClojureLowerUtil.list(inner,
											ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), tag, def.inlineVar(),
													miss)),
									ClojureLowerUtil.list(direct, rowMethod(inner, miss, method)))),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
								ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), direct, miss), metaOrExtended,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"), direct, args)));
		}
		LispVal lookup = ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(
						ClojureLowerUtil.list(tag,
								ClojureLowerUtil.list(new LispSymbol(PROTOCOL_TAG),
										ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), args))),
						ClojureLowerUtil.list(miss,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)))),
				extended);
		LispVal body = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), args),
				ClojureRefusals.refusal(ClojureRefusals.ARITY,
						LispString.literal("wrong number of arguments passed to: " + method)),
				lookup);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"),
				ClojureLowering.varSym(ClojureLowering.varKey(ctx.currentNs, method)),
				ClojureLowerUtil.list(List.of(ClojureLowering.AMPERSAND_REST, args)), body);
	}

	/**
	 * The method lambda a tag's row holds, or {@code miss}: {@code row} is the tag's
	 * inner table (or {@code miss} when the tag has none).
	 */
	private static LispVal rowMethod(LispSymbol row, LispSymbol miss, String method) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), row, miss), miss, ClojureLowerUtil
					.list(ClojureLowerUtil.sym("gethash"), ClojureCollectionLowering.keywordForm(method), row, miss));
	}

	/**
	 * Stores one method row in a protocol's table: the tag's inner table (made on first
	 * use) mapping the method keyword to the lambda. Later rows win, like the oracle.
	 */
	static LispVal methodStoreForm(ClojureLowering ctx, LispSymbol methodsVar, LispVal key, String method,
			LispVal lambda) {
		LispSymbol inner = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispVal ensure = ClojureLowerUtil
			.list(ClojureLowerUtil.sym("if"), ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), inner, miss),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, methodsVar), ClojureLowerUtil
								.list(ClojureLowerUtil.sym("setf"), inner, ClojureCollectionLowering.makeTable())),
					inner);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(
						ClojureLowerUtil.list(miss,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)),
						ClojureLowerUtil.list(inner,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, methodsVar, miss)))),
				ensure,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"), ClojureLowerUtil
					.list(ClojureLowerUtil.sym("gethash"), ClojureCollectionLowering.keywordForm(method), inner),
						lambda));
	}

	/**
	 * The row a body naming a protocol with no method stores: the tag's inner table, made
	 * when absent, so the type satisfies and extends the protocol and a reify is an
	 * instance of its interface, like the oracle's class implementing it.
	 */
	static LispVal emptyRowForm(LispSymbol table, LispVal key) {
		// a row is a table, never nil, so a miss reads as nil
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("or"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, table),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, table),
						ClojureCollectionLowering.makeTable()));
	}

	/**
	 * The declared fields of a {@code defrecord}/{@code deftype} datum: plain names (type
	 * hints strip, like everywhere else), each once.
	 */
	static List<String> recordFields(ClojureLowering ctx, List<LispVal> items) {
		boolean record = ClojureLowerUtil.isSymbolNamed(items.get(0), "defrecord");
		String what = record ? "defrecord" : "deftype";
		ClojureLowerUtil.isTrue(items.size() >= 3, what + " takes a name and fields");
		List<LispVal> names = ClojureLowerUtil.bindingItems(ClojureLowerUtil.stripMeta(items.get(2)), what);
		List<String> fields = new ArrayList<>();
		for (LispVal datum : names) {
			String field = ClojureLowerUtil.plainName(ClojureLowerUtil.stripMeta(datum), what);
			ClojureLowerUtil.isTrue(!fields.contains(field),
					"duplicate field in " + what + " " + items.get(1).print() + ": " + field);
			fields.add(field);
		}
		return fields;
	}

	/**
	 * The declared fields marked {@code ^:unsynchronized-mutable} or
	 * {@code ^:volatile-mutable}, in declaration order. ClojureScript's {@code ^:mutable}
	 * is no marker here: the oracle keeps such a field immutable.
	 */
	static List<String> mutableFields(List<LispVal> items) {
		List<String> mutable = new ArrayList<>();
		for (LispVal datum : ClojureLowerUtil.bindingItems(ClojureLowerUtil.stripMeta(items.get(2)), "deftype")) {
			if (ClojureLowerUtil.nameHasFlag(datum, ":unsynchronized-mutable")
					|| ClojureLowerUtil.nameHasFlag(datum, ":volatile-mutable")) {
				mutable.add(((LispSymbol) ClojureLowerUtil.stripMeta(datum)).name());
			}
		}
		return mutable;
	}

	/**
	 * {@code (set! target value)}: the assignable targets are a deftype's mutable field
	 * inside the type's own inline method (not inside a closure created there, which
	 * holds a copy) and a {@code ^:dynamic} var inside an enclosing {@code binding} (the
	 * binding-depth counter beside the var says whether it is thread-bound); the write
	 * answers the value, like the oracle. Every other target is the oracle's error: a
	 * local or an immutable field ({@code Cannot assign to non-mutable}), a non-dynamic
	 * global or an unbound dynamic one (the run-time
	 * {@code Can't change/establish root binding}, after the value evaluates). A
	 * {@code clojure.core} special ({@link ClojureCoreSpecials}) assigns like a dynamic
	 * var, a flag {@code clojure.main} binds at the top level too. A host field stays
	 * refused by name.
	 */
	static LispVal setBangOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 3, "Malformed assignment, expecting (set! target val)");
		LispVal target = ClojureLowerUtil.stripMeta(items.get(1));
		List<LispVal> parts = ClojureLowerUtil.items(target);
		if (parts != null && !parts.isEmpty() && parts.get(0) instanceof LispSymbol head
				&& (head.name().equals(".") || head.name().startsWith(".-"))) {
			throw new LispReadException(
					"set! of a host field is not supported yet: the java: surface has no field write");
		}
		if (!(target instanceof LispSymbol s) || s.name().startsWith(":") || s.name().equals("nil")
				|| s.name().equals("true") || s.name().equals("false")) {
			throw new LispReadException("Invalid assignment target");
		}
		String name = s.name();
		ClojureLowering.Kind kind = ctx.localKind(name);
		if (kind == ClojureLowering.Kind.MUTABLE_FIELD) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"), ctx.mutableFieldPlace(name),
					ctx.lower(items.get(2)));
		}
		if (kind != null) {
			throw new LispReadException("Cannot assign to non-mutable: " + name);
		}
		String key = name.startsWith(ClojureCoreNames.PREFIX) ? null : ctx.resolveVar(name);
		String special = key == null ? ClojureCoreSpecials.targetName(name) : null;
		if (special != null) {
			return setSpecial(ctx, special, ctx.lower(items.get(2)));
		}
		if (key != null && !ctx.dynamicVars.contains(key)) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), ctx.lower(items.get(2)),
					ClojureRefusals.refusal(ClojureRefusals.ILLEGAL_STATE,
							LispString.literal("Can't change/establish root binding of: " + name + " with set")));
		}
		if (key != null) {
			// a dynamic var: set the thread-local value inside a binding, else
			// the oracle's root-binding error. The value binds once, so it
			// evaluates exactly once, before the test, like the error above.
			LispVal value = ctx.lower(items.get(2));
			LispSymbol depth = ClojureLowering.boundDepthSym(key);
			LispSymbol temp = ctx.freshTemp();
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(List
				.of(ClojureLowerUtil.list(temp, value))), ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym(">"), depth, new LispInteger(0)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), ClojureLowering.varSym(key), temp),
						ClojureRefusals.refusal(ClojureRefusals.ILLEGAL_STATE,
								LispString.literal("Can't change/establish root binding of: " + name + " with set"))));
		}
		throw new LispReadException("set! of a var is not supported yet: " + name
				+ " (only a deftype's mutable field or a thread-bound dynamic var is assignable)");
	}

	/**
	 * What a top-level {@code set!} sets {@code *assert*} to, when its value is a
	 * literal: false for {@code false} and {@code nil}, true for any other literal; null
	 * when it sets something else (a var of the program's own named so included) or a
	 * computed value, which a lowering cannot know.
	 * @param ctx the hub
	 * @param items the {@code set!} form, head included
	 * @return the literal's truth, or null
	 */
	static @Nullable Boolean assertSetTo(ClojureLowering ctx, List<LispVal> items) {
		if (items.size() != 3 || !(ClojureLowerUtil.stripMeta(items.get(1)) instanceof LispSymbol target)) {
			return null;
		}
		String name = target.name();
		boolean programVar = !name.startsWith(ClojureCoreNames.PREFIX) && ctx.resolveVar(name) != null;
		if (ctx.localKind(name) != null || programVar || !"*assert*".equals(ClojureCoreSpecials.targetName(name))) {
			return null;
		}
		LispVal value = items.get(2);
		if (value instanceof LispSymbol symbol) {
			return switch (symbol.name()) {
				case "false", "nil" -> false;
				case "true" -> true;
				default -> symbol.name().startsWith(":") ? Boolean.TRUE : null;
			};
		}
		return value instanceof LispCons ? null : Boolean.TRUE;
	}

	/**
	 * {@code set!} of a {@code clojure.core} special: a flag {@code clojure.main} binds
	 * is always thread-bound, so it assigns; any other assigns inside a {@code binding}
	 * of it (its counter past zero), else the oracle's root-binding error. The value
	 * evaluates once, before the test.
	 */
	private static LispVal setSpecial(ClojureLowering ctx, String name, LispVal value) {
		ClojureCoreSpecials.Special special = ClojureCoreSpecials.required(name);
		LispVal target = ctx.specialValue(special);
		LispSymbol counter = special.counter();
		if (counter == null) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), target, value);
		}
		ctx.usedSpecials.add(name);
		LispSymbol temp = ctx.freshTemp();
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(temp, value))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym(">"), counter, new LispInteger(0)),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), target, temp),
						ClojureRefusals.refusal(ClojureRefusals.ILLEGAL_STATE,
								LispString.literal("Can't change/establish root binding of: " + name + " with set"))));
	}

	/**
	 * The implementation groups behind {@code extend-type} (each headed by a known
	 * protocol name): every method must belong to its protocol, like the oracle's "Can't
	 * define method not in interfaces". An extension spells several arities as {@code fn}
	 * clauses and a repeated method replaces the earlier one ({@link #extensionMethod}).
	 * An inline body ({@code defrecord}, {@code deftype}, {@code reify}) is
	 * {@link #typeBody}'s.
	 */
	static List<ClojureLowering.ImplGroup> implGroups(ClojureLowering ctx, List<LispVal> rest, String what) {
		List<ClojureLowering.ImplGroup> groups = new ArrayList<>();
		String protocol = null;
		ClojureLowering.ProtocolDef def = null;
		List<ClojureLowering.TypeMethod> methods = null;
		for (LispVal datum : rest) {
			if (datum instanceof LispSymbol s && !s.name().startsWith(":")) {
				if (methods != null) {
					if (protocol == null) {
						throw new LispReadException(what + " takes method implementations");
					}
					groups.add(new ClojureLowering.ImplGroup(protocol, methods));
				}
				protocol = s.name();
				def = protocolOf(ctx, protocol);
				if (def == null) {
					throw new LispReadException("No such protocol: " + protocol);
				}
				methods = new ArrayList<>();
				continue;
			}
			if (def == null || methods == null) {
				throw new LispReadException(what + " methods group under a protocol name, not " + datum.print());
			}
			List<LispVal> impl = ClojureLowerUtil.items(datum);
			if (impl == null || impl.size() < 2 || !(impl.get(0) instanceof LispSymbol)) {
				throw new LispReadException(
						"a method implementation takes a name, parameters and a body, not " + datum.print());
			}
			String method = ((LispSymbol) impl.get(0)).name();
			ClojureLowerUtil.isTrue(def.methods().contains(method), "Can't define method not in interfaces: " + method);
			methods.add(extensionMethod(method, impl));
		}
		if (methods != null) {
			if (protocol == null) {
				throw new LispReadException(what + " takes method implementations");
			}
			groups.add(new ClojureLowering.ImplGroup(protocol, methods));
		}
		return groups;
	}

	/**
	 * A {@code reify}, {@code deftype} or {@code defrecord} body: its protocol groups, in
	 * the order each protocol is first named (a protocol named twice is one group), and
	 * its implementation of the host interfaces it names ({@link ClojureInterfaces}).
	 */
	record TypeBody(List<ClojureLowering.ImplGroup> groups, ClojureInterfaces.InterfaceBody interfaces) {
	}

	/**
	 * Parses a {@code reify}, {@code deftype} or {@code defrecord} body. A group symbol
	 * names a protocol or an interface ({@link ClojureInterfaces#resolve}); a method is
	 * matched by name and parameter count, like the oracle's class, against its group's
	 * protocol first, then the body's interfaces and {@code Object}'s overridable
	 * methods, then the body's other protocols, so a {@code toString} may stand under a
	 * protocol group; one none of them declares is the oracle's "Can't define method not
	 * in interfaces". An interface method takes fixed parameters and each count once; one
	 * an interface the form's class implements itself declares is refused
	 * ({@link ClojureInterfaces#checkMethod}).
	 * @param ctx the hub
	 * @param rest the body: group symbols and method implementations
	 * @param what the defining form
	 * @return the groups and the interface implementation
	 */
	static TypeBody typeBody(ClojureLowering ctx, List<LispVal> rest, String what) {
		Map<String, ClojureLowering.ProtocolDef> protocols = new LinkedHashMap<>();
		List<ClojureInterfaces.HostInterface> named = new ArrayList<>();
		Map<String, List<ClojureLowering.TypeMethod>> byProtocol = new LinkedHashMap<>();
		for (LispVal datum : rest) {
			if (datum instanceof LispSymbol s && !s.name().startsWith(":")) {
				ClojureLowering.ProtocolDef def = protocolOf(ctx, s.name());
				if (def != null) {
					protocols.putIfAbsent(s.name(), def);
					byProtocol.computeIfAbsent(s.name(), ignored -> new ArrayList<>());
					continue;
				}
				ClojureInterfaces.HostInterface one = ClojureInterfaces.resolve(ctx, s.name(), what);
				ClojureInterfaces.checkNamed(what, one);
				if (one != ClojureInterfaces.OBJECT_METHODS && !named.contains(one)) {
					named.add(one);
				}
			}
		}
		List<ClojureInterfaces.HostInterface> closure = ClojureInterfaces.closure(named);
		Map<String, List<ClojureLowering.MethodArity>> interfaceMethods = new LinkedHashMap<>();
		String protocol = null;
		boolean grouped = false;
		for (LispVal datum : rest) {
			if (datum instanceof LispSymbol s && !s.name().startsWith(":")) {
				grouped = true;
				protocol = protocols.containsKey(s.name()) ? s.name() : null;
				continue;
			}
			if (!grouped) {
				throw new LispReadException(
						what + " methods group under a protocol or interface name, not " + datum.print());
			}
			List<LispVal> impl = ClojureLowerUtil.items(datum);
			if (impl == null || impl.size() < 2 || !(impl.get(0) instanceof LispSymbol)) {
				throw new LispReadException(
						"a method implementation takes a name, parameters and a body, not " + datum.print());
			}
			String method = ((LispSymbol) impl.get(0)).name();
			ClojureLowerUtil.isTrue(ClojureBindingLowering.isVectorDatum(ClojureLowerUtil.stripMeta(impl.get(1))),
					"an inline method takes one parameter vector, naming the method again for another arity: "
							+ method);
			int count = ClojureLowerUtil
				.bindingItems(ClojureLowerUtil.stripMeta(impl.get(1)), "the method " + method + " of")
				.size();
			ClojureLowering.ProtocolDef current = protocol == null ? null : protocols.get(protocol);
			if (protocol != null && current != null && declares(current, method, count)) {
				addInlineArity(current, byProtocol.computeIfAbsent(protocol, ignored -> new ArrayList<>()), method,
						impl);
				continue;
			}
			ClojureInterfaces.HostInterface owner = ClojureInterfaces.declaring(closure, method, count);
			if (owner != null) {
				ClojureInterfaces.checkMethod(what, owner, method);
				addInterfaceArity(interfaceMethods, method, impl);
				continue;
			}
			Map.Entry<String, ClojureLowering.ProtocolDef> other = null;
			for (Map.Entry<String, ClojureLowering.ProtocolDef> candidate : protocols.entrySet()) {
				if (other == null && declares(candidate.getValue(), method, count)) {
					other = candidate;
				}
			}
			if (other == null) {
				throw new LispReadException("Can't define method not in interfaces: " + method);
			}
			addInlineArity(other.getValue(), byProtocol.computeIfAbsent(other.getKey(), ignored -> new ArrayList<>()),
					method, impl);
		}
		List<ClojureLowering.ImplGroup> groups = new ArrayList<>();
		byProtocol.forEach((name, methods) -> groups.add(new ClojureLowering.ImplGroup(name, methods)));
		ClojureInterfaces.InterfaceBody interfaces = closure.isEmpty() && interfaceMethods.isEmpty()
				? ClojureInterfaces.InterfaceBody.EMPTY
				: new ClojureInterfaces.InterfaceBody(closure, interfaceMethods);
		return new TypeBody(groups, interfaces);
	}

	/** Whether the protocol declares the method at the parameter count. */
	private static boolean declares(ClojureLowering.ProtocolDef def, String method, int count) {
		Set<Integer> counts = def.arities().get(method);
		return counts != null && counts.contains(count);
	}

	/**
	 * One arity of an interface or {@code Object} method added to the body's: fixed
	 * parameters (a {@code &} would be a parameter named so in the oracle's class, which
	 * no method here spells), each count once.
	 */
	private static void addInterfaceArity(Map<String, List<ClojureLowering.MethodArity>> methods, String method,
			List<LispVal> impl) {
		ClojureLowering.ParamShape shape = ClojureBindingLowering.paramShape(impl.get(1));
		ClojureLowerUtil.isTrue(!shape.variadic(), "an interface method takes fixed parameters: " + method);
		List<ClojureLowering.MethodArity> arities = methods.computeIfAbsent(method, ignored -> new ArrayList<>());
		for (ClojureLowering.MethodArity other : arities) {
			ClojureLowerUtil.isTrue(ClojureBindingLowering.paramShape(other.params()).fixed() != shape.fixed(),
					"duplicate method implementation: " + method);
		}
		arities.add(new ClojureLowering.MethodArity(impl.get(1), impl.subList(2, impl.size())));
	}

	/**
	 * One inline arity {@code (method [params] body...)} added to the group's methods: a
	 * new method, or another arity of one named before. Its parameter count, the target
	 * included, must be one the protocol declares for the method, and each once, like the
	 * oracle's class implementing the interface.
	 */
	private static void addInlineArity(ClojureLowering.ProtocolDef def, List<ClojureLowering.TypeMethod> methods,
			String method, List<LispVal> impl) {
		ClojureLowerUtil.isTrue(ClojureBindingLowering.isVectorDatum(ClojureLowerUtil.stripMeta(impl.get(1))),
				"an inline method takes one parameter vector, naming the method again for another arity: " + method);
		List<LispVal> params = ClojureLowerUtil.bindingItems(ClojureLowerUtil.stripMeta(impl.get(1)),
				"the method " + method + " of");
		ClojureLowerUtil.isTrue(!params.isEmpty(), "a protocol method takes a target and arguments: " + method);
		Set<Integer> declared = def.arities().get(method);
		ClojureLowerUtil.isTrue(declared != null && declared.contains(params.size()),
				"Can't define method not in interfaces: " + method);
		ClojureLowering.MethodArity arity = new ClojureLowering.MethodArity(impl.get(1), impl.subList(2, impl.size()));
		for (int i = 0; i < methods.size(); i++) {
			ClojureLowering.TypeMethod prior = methods.get(i);
			if (!prior.method().equals(method)) {
				continue;
			}
			for (ClojureLowering.MethodArity other : prior.arities()) {
				ClojureLowerUtil.isTrue(ClojureLowerUtil
					.bindingItems(ClojureLowerUtil.stripMeta(other.params()), "the method " + method + " of")
					.size() != params.size(), "duplicate method implementation: " + method);
			}
			List<ClojureLowering.MethodArity> arities = new ArrayList<>(prior.arities());
			arities.add(arity);
			methods.set(i, new ClojureLowering.TypeMethod(method, List.copyOf(arities)));
			return;
		}
		methods.add(new ClojureLowering.TypeMethod(method, List.of(arity)));
	}

	/**
	 * One extension method, {@code (method [params] body...)} or {@code (method ([params]
	 * body...)+)}: the arities of a {@code fn}, each fixed count once and at most one
	 * variadic, like the oracle's {@code fn} the extension stores.
	 */
	static ClojureLowering.TypeMethod extensionMethod(String method, List<LispVal> impl) {
		if (ClojureBindingLowering.isVectorDatum(ClojureLowerUtil.stripMeta(impl.get(1)))) {
			List<LispVal> params = ClojureLowerUtil.bindingItems(ClojureLowerUtil.stripMeta(impl.get(1)),
					"the method " + method + " of");
			ClojureLowerUtil.isTrue(!params.isEmpty(), "a protocol method takes a target and arguments: " + method);
			return new ClojureLowering.TypeMethod(method, impl.get(1), impl.subList(2, impl.size()));
		}
		List<ClojureLowering.MethodArity> arities = new ArrayList<>();
		Set<Integer> fixed = new HashSet<>();
		boolean variadic = false;
		for (LispVal clause : impl.subList(1, impl.size())) {
			List<LispVal> parts = ClojureLowerUtil.items(clause);
			if (parts == null || parts.isEmpty()
					|| !ClojureBindingLowering.isVectorDatum(ClojureLowerUtil.stripMeta(parts.get(0)))) {
				throw new LispReadException("a method implementation takes a parameter vector and a body, or arity"
						+ " clauses ([params] body...), not " + clause.print());
			}
			ClojureLowering.ParamShape shape = ClojureBindingLowering.paramShape(parts.get(0));
			ClojureLowerUtil.isTrue(shape.fixed() != 0, "a protocol method takes a target and arguments: " + method);
			if (shape.variadic()) {
				ClojureLowerUtil.isTrue(!variadic, "Can't have more than 1 variadic overload");
				variadic = true;
			}
			else {
				ClojureLowerUtil.isTrue(fixed.add(shape.fixed()), "Can't have 2 overloads with same arity");
			}
			arities.add(new ClojureLowering.MethodArity(parts.get(0), parts.subList(1, parts.size())));
		}
		return new ClojureLowering.TypeMethod(method, List.copyOf(arities));
	}

	/**
	 * The lambda a method implementation stores: its one arity's, or one dispatching each
	 * call by its count to the lambda of the matching arity ({@link #arityDispatch}).
	 */
	static LispVal methodLambda(ClojureLowering ctx, ClojureLowering.TypeMethod impl) {
		if (impl.arities().size() == 1) {
			ClojureLowering.MethodArity only = impl.arities().get(0);
			return ClojureDispatchLowering.methodLambda(ctx, only.params(), only.body());
		}
		List<LispVal> lambdas = new ArrayList<>();
		for (ClojureLowering.MethodArity arity : impl.arities()) {
			lambdas.add(ClojureDispatchLowering.methodLambda(ctx, arity.params(), arity.body()));
		}
		return arityDispatch(ctx, impl, lambdas);
	}

	/**
	 * A method of several arities: each arity's lambda bound once, behind one lambda
	 * applying the one whose parameter count matches the call's (a fixed count exactly, a
	 * variadic arity from its fixed part on, tried last); any other count is the oracle's
	 * arity error.
	 */
	private static LispVal arityDispatch(ClojureLowering ctx, ClojureLowering.TypeMethod impl, List<LispVal> lambdas) {
		return arityDispatch(ctx, impl, lambdas, ClojureRefusals.refusal(ClojureRefusals.ARITY,
				LispString.literal("wrong number of arguments passed to: " + impl.method())));
	}

	/**
	 * {@link #arityDispatch(ClojureLowering, ClojureLowering.TypeMethod, List)} answering
	 * {@code fallback} for a count no arity takes: an interface method's
	 * {@code AbstractMethodError} ({@link ClojureInterfaces}).
	 */
	private static LispVal arityDispatch(ClojureLowering ctx, ClojureLowering.TypeMethod impl, List<LispVal> lambdas,
			LispVal fallback) {
		List<LispVal> cells = new ArrayList<>();
		List<LispVal> fixedArms = new ArrayList<>();
		List<LispVal> variadicArms = new ArrayList<>();
		LispSymbol args = ctx.freshTemp();
		LispSymbol count = ctx.freshTemp();
		for (int i = 0; i < lambdas.size(); i++) {
			LispSymbol cell = ctx.freshTemp();
			cells.add(ClojureLowerUtil.list(cell, lambdas.get(i)));
			ClojureLowering.ParamShape shape = ClojureBindingLowering.paramShape(impl.arities().get(i).params());
			LispVal call = ClojureLowerUtil.list(ClojureLowerUtil.sym("apply"), cell, args);
			if (shape.variadic()) {
				variadicArms.add(ClojureLowerUtil.list(
						ClojureLowerUtil.list(ClojureLowerUtil.sym(">="), count, new LispInteger(shape.fixed())),
						call));
			}
			else {
				fixedArms.add(ClojureLowerUtil.list(
						ClojureLowerUtil.list(ClojureLowerUtil.sym("="), count, new LispInteger(shape.fixed())), call));
			}
		}
		List<LispVal> arms = new ArrayList<>(fixedArms);
		arms.addAll(variadicArms);
		arms.add(ClojureLowerUtil.list(ClojureLowering.TRUE_CONST, fallback));
		LispVal dispatch = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(List.of(ClojureLowering.AMPERSAND_REST, args)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(count,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("length"), args)))),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("cond"), arms)));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"), ClojureLowerUtil.list(cells), dispatch);
	}

	/**
	 * The store of a method row: through the protocol's
	 * {@link ClojureLowering.ProtocolDef#reducerRow} when it is one of the reducing
	 * protocols and the key is a record's, deftype's or reify's tag ({@code typed}) --
	 * what {@code reduce} and {@code reduce-kv} reach -- else {@link #methodStoreForm}.
	 */
	static LispVal rowStoreForm(ClojureLowering ctx, ClojureLowering.ProtocolDef def, LispSymbol table, LispVal key,
			String method, LispVal lambda, boolean typed) {
		if (typed && def.reducerRow() != null) {
			return ClojureLowerUtil.list(new LispSymbol(def.reducerRow()), table, key, lambda);
		}
		return methodStoreForm(ctx, table, key, method, lambda);
	}

	/**
	 * One method-implementation row: the lambda (with the usual destructuring prologue)
	 * stored in the protocol's table under the target's tag. Inline
	 * {@code defrecord}/{@code deftype} bodies see the fields as locals bound from the
	 * instance table -- an outer scope, so an explicit parameter shadows its field, like
	 * the oracle. A mutable field reads and writes the instance's slot vector instead: a
	 * {@code symbol-macrolet} turns every free reference into {@code (aref slots i)}, so
	 * a read after another method's {@code set!} sees the new value, like the oracle's
	 * field access.
	 */
	static LispVal methodRow(ClojureLowering ctx, String protocol, LispVal key, ClojureLowering.TypeMethod impl,
			@Nullable List<String> fields, List<String> mutable) {
		ClojureLowering.ProtocolDef def = protocolOf(ctx, protocol);
		if (def == null) {
			throw new LispReadException("No such protocol: " + protocol);
		}
		LispVal lambda;
		if (fields == null) {
			lambda = methodLambda(ctx, impl);
		}
		else {
			lambda = inFieldScope(ctx, fields, mutable,
					(slots, places) -> fieldMethodLambda(ctx, impl, fields, slots, places));
		}
		return rowStoreForm(ctx, def, def.inlineTable(), key, impl.method(), lambda, true);
	}

	/**
	 * What a builder makes with a record's or deftype's fields in scope, like an inline
	 * method's: the immutable ones as locals the builder binds from the instance table,
	 * the mutable ones (in the places it is handed) through the slot vector.
	 */
	private static LispVal inFieldScope(ClojureLowering ctx, List<String> fields, List<String> mutable,
			BiFunction<LispSymbol, Map<String, LispVal>, LispVal> build) {
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		LispSymbol slots = ctx.freshTemp();
		Map<String, LispVal> places = new LinkedHashMap<>();
		for (String field : fields) {
			if (mutable.contains(field)) {
				scope.put(field, ClojureLowering.Kind.MUTABLE_FIELD);
				places.put(field, ClojureLowerUtil.list(ClojureLowerUtil.sym("aref"), slots,
						new LispInteger(mutable.indexOf(field))));
			}
			else {
				scope.put(field, ClojureLowering.Kind.VARIABLE);
			}
		}
		Map<String, LispVal> outerPlaces = new LinkedHashMap<>(ctx.mutableFieldPlaces);
		ctx.mutableFieldPlaces.putAll(places);
		try {
			return ctx.inScope(scope, () -> build.apply(slots, places));
		}
		finally {
			ctx.mutableFieldPlaces.clear();
			ctx.mutableFieldPlaces.putAll(outerPlaces);
		}
	}

	/**
	 * The interface rows of a body under the tag ({@link ClojureInterfaces#rowForms}),
	 * each defined method's lambda built like an inline protocol method's -- with a
	 * record's or deftype's fields in scope when {@code fields} is not null -- and
	 * dispatching on the call's count when the interfaces declare several.
	 */
	static List<LispVal> interfaceRows(ClojureLowering ctx, String what, ClojureInterfaces.InterfaceBody body,
			LispVal tag, @Nullable String reifyClass, @Nullable List<String> fields, List<String> mutable) {
		if (body.isEmpty()) {
			return List.of();
		}
		return ClojureInterfaces.rowForms(ctx, what, body, tag, reifyClass, (impl, dispatch, fallback) -> {
			if (fields == null) {
				return interfaceLambda(ctx, impl, dispatch, fallback,
						arity -> ClojureDispatchLowering.methodLambda(ctx, arity.params(), arity.body()));
			}
			return inFieldScope(ctx, fields, mutable, (slots, places) -> interfaceLambda(ctx, impl, dispatch, fallback,
					arity -> fieldArityLambda(ctx, arity, fields, slots, places)));
		});
	}

	/**
	 * An interface method's lambda: its one arity's, or the dispatch over each arity's
	 * answering the fallback for any other count.
	 */
	private static LispVal interfaceLambda(ClojureLowering ctx, ClojureLowering.TypeMethod impl, boolean dispatch,
			LispVal fallback, Function<ClojureLowering.MethodArity, LispVal> arityLambda) {
		if (!dispatch) {
			return arityLambda.apply(impl.arities().get(0));
		}
		List<LispVal> lambdas = new ArrayList<>();
		for (ClojureLowering.MethodArity arity : impl.arities()) {
			lambdas.add(arityLambda.apply(arity));
		}
		return arityDispatch(ctx, impl, lambdas, fallback);
	}

	/**
	 * One inline method's lambda with the fields in scope: its one arity's, or the
	 * dispatch over each arity's ({@link #arityDispatch}), every arity binding the fields
	 * from its own target.
	 */
	static LispVal fieldMethodLambda(ClojureLowering ctx, ClojureLowering.TypeMethod impl, List<String> fields,
			LispSymbol slots, Map<String, LispVal> places) {
		if (impl.arities().size() == 1) {
			return fieldArityLambda(ctx, impl.arities().get(0), fields, slots, places);
		}
		List<LispVal> lambdas = new ArrayList<>();
		for (ClojureLowering.MethodArity arity : impl.arities()) {
			lambdas.add(fieldArityLambda(ctx, arity, fields, slots, places));
		}
		return arityDispatch(ctx, impl, lambdas);
	}

	/**
	 * One inline arity's lambda with the fields in scope: the immutable ones bound from
	 * the instance table, the mutable ones (in {@code places}) through the slot vector.
	 */
	private static LispVal fieldArityLambda(ClojureLowering ctx, ClojureLowering.MethodArity impl, List<String> fields,
			LispSymbol slots, Map<String, LispVal> places) {
		String fresh = ctx.freshRecurName();
		String worker = ClojureBindingLowering.workerName(fresh);
		ClojureLowering.RecurTarget target = new ClojureLowering.RecurTarget(
				ClojureBindingLowering.isVariadicParams(impl.params()) ? worker : fresh, true);
		ClojureLowering.Clause clause = ClojureBindingLowering.clause(ctx, impl.params(), impl.body(), target);
		LispVal inner = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(clause.params()),
				clause.wrapped());
		if (fields.isEmpty()) {
			if (target.used() && clause.variadic()) {
				return ClojureBindingLowering.splitMethodLambda(ctx, fresh, worker, clause, clause.wrapped());
			}
			return target.used() ? ClojureLowering.labelsSelfCall(fresh, inner) : inner;
		}
		LispVal self = clause.params().isEmpty() ? ClojureLowering.NIL_CONST : clause.params().get(0);
		// a field a parameter names stays the parameter: the lambda list binds it
		// outside the field bindings, so binding the field would shadow it back
		Set<String> paramNames = new HashSet<>();
		for (LispVal param : clause.params()) {
			paramNames.add(((LispSymbol) param).name());
		}
		List<LispVal> binds = new ArrayList<>();
		for (String field : fields) {
			if (paramNames.contains(ctx.localSym(field).name())) {
				continue;
			}
			if (!places.containsKey(field)) {
				binds.add(ClojureLowerUtil.list(ctx.localSym(field),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"),
								ClojureCollectionLowering.keywordForm(field), typedTableOf(self),
								ClojureLowering.NIL_CONST)));
			}
		}
		LispVal body = clause.wrapped();
		if (!places.isEmpty()) {
			binds.add(ClojureLowerUtil.list(slots,
					ClojureLowerUtil.list(ClojureLowerUtil.sym("nth"), new LispInteger(DEFTYPE_SLOTS), self)));
			List<LispVal> macros = new ArrayList<>();
			for (Map.Entry<String, LispVal> place : places.entrySet()) {
				if (paramNames.contains(ctx.localSym(place.getKey()).name())) {
					continue;
				}
				macros.add(ClojureLowerUtil.list(ctx.localSym(place.getKey()), place.getValue()));
			}
			body = ClojureLowerUtil.list(ClojureLowerUtil.sym("symbol-macrolet"), ClojureLowerUtil.list(macros), body);
		}
		LispVal fieldBody = ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(binds), body);
		LispVal withFields = ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"),
				ClojureLowerUtil.list(clause.params()), fieldBody);
		if (target.used() && clause.variadic()) {
			return ClojureBindingLowering.splitMethodLambda(ctx, fresh, worker, clause, fieldBody);
		}
		return target.used() ? ClojureLowering.labelsSelfCall(fresh, withFields) : withFields;
	}

	/**
	 * {@code (defrecord Name [fields] Protocol (method [target & args] body...)...
	 * opts?)}: the positional and map constructors plus one table row per inline method.
	 * A trailing keyword names an unsupported option, like {@code defprotocol}'s.
	 */
	static List<LispVal> recordTypeForms(ClojureLowering ctx, List<LispVal> items) {
		boolean record = ClojureLowerUtil.isSymbolNamed(items.get(0), "defrecord");
		String what = record ? "defrecord" : "deftype";
		ClojureLowerUtil.isTrue(items.size() >= 3, what + " takes a name and fields");
		String name = ClojureLowerUtil.plainName(items.get(1), what);
		ClojureLowering.TypeDef def = declareRecordType(ctx, items);
		List<LispVal> rest = new ArrayList<>(items.subList(3, items.size()));
		for (LispVal datum : rest) {
			ClojureLowerUtil.isTrue(!(datum instanceof LispSymbol s && s.name().startsWith(":")),
					what + " option " + datum.print() + " is not supported yet");
		}
		TypeBody body = typeBody(ctx, rest, what);
		List<LispVal> forms = new ArrayList<>();
		forms.add(positionalCtor(ctx, name, def));
		if (record) {
			forms.add(mapCtor(ctx, name, def));
		}
		for (ClojureLowering.ImplGroup group : body.groups()) {
			ClojureLowering.ProtocolDef protocol = protocolOf(ctx, group.protocol());
			if (protocol != null && group.methods().isEmpty()) {
				forms.add(emptyRowForm(protocol.inlineTable(), typeTagForm(name)));
			}
			for (ClojureLowering.TypeMethod impl : group.methods()) {
				forms.add(methodRow(ctx, group.protocol(), typeTagForm(name), impl, def.fields(), def.mutableFields()));
			}
		}
		forms.addAll(interfaceRows(ctx, what, body.interfaces(), typeTagForm(name), null, def.fields(),
				def.mutableFields()));
		ctx.usedProtocols = true;
		return forms;
	}

	/**
	 * The positional constructor: a mangled {@code defun} over the fields, so a wrong
	 * count signals like any other call. The field keywords travel twice (the declared
	 * list for {@code dissoc}'s keep-type rule, the table for the map verbs).
	 */
	static LispVal positionalCtor(ClojureLowering ctx, String name, ClojureLowering.TypeDef def) {
		List<LispVal> params = new ArrayList<>();
		List<LispVal> pairs = new ArrayList<>();
		List<LispVal> keys = new ArrayList<>();
		List<LispVal> slots = new ArrayList<>();
		for (String field : def.fields()) {
			params.add(ctx.localSym(field));
			if (def.mutableFields().contains(field)) {
				slots.add(ctx.localSym(field));
				continue;
			}
			LispVal key = ClojureCollectionLowering.keywordForm(field);
			keys.add(key);
			pairs.add(key);
			pairs.add(ctx.localSym(field));
		}
		LispVal table = ClojureCollectionLowering
			.tableFromPlist(ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), pairs));
		LispVal value = def.record()
				? wrapRecord(typeTagForm(name), ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), keys), table,
						LispString.literal(def.className()))
				: wrapDeftype(typeTagForm(name), ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), keys), table,
						LispString.literal(def.className()),
						slots.isEmpty() ? null : ClojureLowerUtil.cons(ClojureLowerUtil.sym("vector"), slots));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"),
				ClojureLowering.varSym(ClojureLowering.varKey(ctx.currentNs, "->" + name)),
				ClojureLowerUtil.list(params), value);
	}

	/**
	 * The map constructor (records only -- the oracle defines none for deftypes): the
	 * entries copied out of the argument (nil builds empty, a record contributes its
	 * entries), missing fields defaulting to nil, extra entries kept, like the oracle.
	 */
	static LispVal mapCtor(ClojureLowering ctx, String name, ClojureLowering.TypeDef def) {
		LispSymbol src = ctx.freshTemp();
		LispSymbol table = ctx.freshTemp();
		LispSymbol pairs = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		List<LispVal> keys = new ArrayList<>();
		for (String field : def.fields()) {
			keys.add(ClojureCollectionLowering.keywordForm(field));
		}
		List<LispVal> fill = new ArrayList<>();
		for (LispVal key : keys) {
			fill.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, table, miss), miss),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), key, table),
							ClojureLowering.NIL_CONST),
					ClojureLowering.NIL_CONST));
		}
		fill.add(wrapRecord(typeTagForm(name), ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), keys), table,
				LispString.literal(def.className())));
		LispVal whole = ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(src, src),
						ClojureLowerUtil.list(pairs, ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), src,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), isRecordForm(src),
										ClojureCollectionLowering.tablePlist(typedTableOf(src)),
										ClojureCollectionLowering.entriesPlist(src, src)),
								ClojureLowering.NIL_CONST)),
						ClojureLowerUtil.list(miss,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)))),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
						ClojureLowerUtil.list(
								List.of(ClojureLowerUtil.list(table, ClojureCollectionLowering.tableFromPlist(pairs)))),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), fill)));
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"),
				ClojureLowering.varSym(ClojureLowering.varKey(ctx.currentNs, "map->" + name)),
				ClojureLowerUtil.list(List.of(src)), whole);
	}

	/**
	 * One record literal {@code (%record ns.Name body)}: the map constructor over the
	 * quoted map body, or the positional constructor over the quoted vector body -- the
	 * body is data, never evaluated, like the oracle. The class name must be a record the
	 * program defines, spelled as it prints ({@code my_app.core.Name}).
	 */
	static LispVal recordLiteral(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 3 && items.get(1) instanceof LispSymbol,
				"a record literal takes a class name and a body");
		String className = ((LispSymbol) items.get(1)).name();
		ClojureLowering.TypeDef def = null;
		for (ClojureLowering.TypeDef known : ctx.types.values()) {
			if (known.className().equals(className)) {
				def = known;
			}
		}
		if (def == null) {
			throw new LispReadException("a record literal needs a defined record class, not " + className);
		}
		if (!def.record()) {
			throw new LispReadException("a deftype literal is not supported yet: " + className);
		}
		// entry spelling -> value datum: the declared fields first (nil until the
		// body names them), then the extension keys in body order
		Map<String, LispVal> entries = new LinkedHashMap<>();
		for (String field : def.fields()) {
			entries.put(field, new LispSymbol("nil"));
		}
		List<LispVal> body = ClojureLowerUtil.items(items.get(2), List.of());
		if (!body.isEmpty() && ClojureLowerUtil.isSymbolNamed(body.get(0), "%hash-map")) {
			for (int i = 1; i + 1 < body.size(); i += 2) {
				ClojureLowerUtil.isTrue(body.get(i) instanceof LispSymbol k && k.name().startsWith(":"),
						"a record literal takes keyword keys, not " + body.get(i).print());
				String spelling = ClojureCollectionLowering.resolveKeywordSpelling(ctx,
						((LispSymbol) body.get(i)).name());
				entries.put(spelling, body.get(i + 1));
			}
		}
		else {
			ClojureLowerUtil.isTrue(!body.isEmpty() && body.get(0) == ClojureReader.VECTOR,
					"a record literal takes a map or a vector");
			int n = body.size() - 1;
			ClojureLowerUtil.isTrue(n == def.fields().size(),
					"Unexpected number of constructor arguments to class " + className + ": got " + n);
			for (int i = 0; i < n; i++) {
				entries.put(def.fields().get(i), body.get(i + 1));
			}
		}
		// built in place over the quoted values, so a record literal needs no
		// constructor in scope (a macro answer travels as one, too)
		List<LispVal> keys = new ArrayList<>();
		for (String field : def.fields()) {
			keys.add(ClojureCollectionLowering.keywordForm(field));
		}
		List<LispVal> pairs = new ArrayList<>();
		for (Map.Entry<String, LispVal> entry : entries.entrySet()) {
			pairs.add(ClojureCollectionLowering.keywordForm(entry.getKey()));
			pairs.add(ctx.quote(entry.getValue()));
		}
		return wrapRecord(typeTagForm(def.tagSpelling()), ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), keys),
				ClojureCollectionLowering.tableFromPlist(ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), pairs)),
				LispString.literal(className));
	}

	/**
	 * Maps an {@code extend} target name to its dispatch-key form, or null for
	 * {@code Object} (the default row). Record and deftype names answer their tags; host
	 * kinds answer the {@code class} keyword spelling (a {@code java.lang.}/
	 * {@code clojure.lang.} qualified or imported spelling too, like a {@code defmethod}
	 * dispatch value), so dispatch agrees with {@code class}; anything else (a
	 * {@code java.time.Instant}, a {@code Date}, ...) is a named refusal.
	 */
	static @Nullable LispVal extendKeyForm(ClojureLowering ctx, String typeName, String what) {
		if (ClojureDispatchLowering.isObjectClassName(ctx, typeName)) {
			return null;
		}
		if (typeName.equals("nil")) {
			return ClojureCollectionLowering.keywordForm("nil");
		}
		ClojureLowering.TypeDef type = ctx.typeDefOf(typeName);
		if (type != null) {
			return typeTagForm(type.tagSpelling());
		}
		// a package-qualified or imported spelling resolves like a defmethod dispatch
		// value
		String fqn = ClojureNamespaceLowering.resolveClass(ctx, typeName);
		String kind = ClojureDispatchLowering.DISPATCH_CLASS_KEYWORDS.get(fqn.substring(fqn.lastIndexOf('.') + 1));
		if (kind == null) {
			throw new LispReadException(what + " needs a core type, not " + typeName);
		}
		return ClojureCollectionLowering.keywordForm(kind);
	}

	/**
	 * Whether an {@code extend} target names a record or deftype the program defines, so
	 * its key is that type's tag ({@link #extendKeyForm}).
	 */
	static boolean isTypedTarget(ClojureLowering ctx, String typeName) {
		return !ClojureDispatchLowering.isObjectClassName(ctx, typeName) && !typeName.equals("nil")
				&& ctx.typeDefOf(typeName) != null;
	}

	/**
	 * Stores one extension row: under the target's tag, or (for {@code Object}) the
	 * protocol's default global consulted on a miss. The method must belong to the
	 * protocol, like the inline implementations; {@code typed} says the key is a record's
	 * or deftype's tag ({@link #rowStoreForm}).
	 */
	static LispVal extendRow(ClojureLowering ctx, String protocol, @Nullable LispVal key,
			ClojureLowering.TypeMethod impl, boolean typed) {
		ClojureLowering.ProtocolDef def = protocolOf(ctx, protocol);
		if (def == null) {
			throw new LispReadException("No such protocol: " + protocol);
		}
		ClojureLowerUtil.isTrue(def.methods().contains(impl.method()),
				"Can't define method not in interfaces: " + impl.method());
		LispVal lambda = methodLambda(ctx, impl);
		if (key == null) {
			return objectStoreForm(def, impl.method(), lambda);
		}
		return rowStoreForm(ctx, def, def.methodsVar(), key, impl.method(), lambda, typed);
	}

	/**
	 * Stores one {@code Object} row: the method keyword mapped to the lambda in the
	 * protocol's default table, made on first use (nil until then, so a protocol with no
	 * {@code Object} extension satisfies nothing it was not extended to).
	 */
	static LispVal objectStoreForm(ClojureLowering.ProtocolDef def, String method, LispVal lambda) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), def.defaultVar()),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), def.defaultVar(),
								ClojureCollectionLowering.makeTable())),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("setf"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"),
								ClojureCollectionLowering.keywordForm(method), def.defaultVar()),
						lambda));
	}

	/**
	 * {@code (extend-protocol P Type (method [target & args] body...)+ ...)}: one row per
	 * method per type, like {@code defmethod} rows. The type may repeat (later rows win,
	 * like the oracle); a method of several arities spells them as {@code fn} clauses
	 * ({@link #extensionMethod}).
	 */
	static LispVal extendProtocolForm(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "extend-protocol takes a protocol, a type and methods");
		ClojureLowerUtil.isTrue(items.get(1) instanceof LispSymbol, "extend-protocol takes a protocol name");
		String protocol = ((LispSymbol) items.get(1)).name();
		if (protocolOf(ctx, protocol) == null) {
			throw new LispReadException("No such protocol: " + protocol);
		}
		List<LispVal> rows = new ArrayList<>();
		int at = 2;
		while (at < items.size()) {
			ClojureLowerUtil.isTrue(items.get(at) instanceof LispSymbol, "extend-protocol takes a type name");
			String target = ((LispSymbol) items.get(at)).name();
			LispVal key = extendKeyForm(ctx, target, "extend-protocol");
			boolean typed = isTypedTarget(ctx, target);
			at++;
			while (at < items.size() && !(items.get(at) instanceof LispSymbol)) {
				List<LispVal> impl = ClojureLowerUtil.items(items.get(at));
				if (impl == null || impl.size() < 2 || !(impl.get(0) instanceof LispSymbol)) {
					throw new LispReadException("a method implementation takes a name, parameters and a body, not "
							+ items.get(at).print());
				}
				String method = ((LispSymbol) impl.get(0)).name();
				rows.add(extendRow(ctx, protocol, key, extensionMethod(method, impl), typed));
				at++;
			}
		}
		ctx.usedProtocols = true;
		if (rows.isEmpty()) {
			return ClojureLowering.NIL_CONST;
		}
		return ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), rows);
	}

	/**
	 * {@code (extend-type T Protocol (method [target & args] body...)+ ...)}: the same
	 * rows, grouped under protocol names. A bare method group (no protocol) is refused:
	 * there is no interface to check it against.
	 */
	static LispVal extendTypeForm(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "extend-type takes a type, a protocol and methods");
		ClojureLowerUtil.isTrue(items.get(1) instanceof LispSymbol, "extend-type takes a type name");
		String target = ((LispSymbol) items.get(1)).name();
		LispVal key = extendKeyForm(ctx, target, "extend-type");
		boolean typed = isTypedTarget(ctx, target);
		List<ClojureLowering.ImplGroup> groups = implGroups(ctx, items.subList(2, items.size()), "extend-type");
		List<LispVal> rows = new ArrayList<>();
		for (ClojureLowering.ImplGroup group : groups) {
			for (ClojureLowering.TypeMethod impl : group.methods()) {
				rows.add(extendRow(ctx, group.protocol(), key, impl, typed));
			}
		}
		ctx.usedProtocols = true;
		if (rows.isEmpty()) {
			return ClojureLowering.NIL_CONST;
		}
		return ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), rows);
	}

	/**
	 * {@code (extend T Protocol {method fn ...})}: the same rows from a map literal of
	 * method functions. Anything but a literal map is refused (there is nothing to walk
	 * at lower time).
	 */
	static LispVal extendForm(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 4, "extend takes a type, a protocol and a map of methods");
		ClojureLowerUtil.isTrue(items.get(1) instanceof LispSymbol, "extend takes a type name");
		ClojureLowerUtil.isTrue(items.get(2) instanceof LispSymbol, "extend takes a protocol name");
		String target = ((LispSymbol) items.get(1)).name();
		String protocol = ((LispSymbol) items.get(2)).name();
		ClojureLowering.ProtocolDef def = protocolOf(ctx, protocol);
		if (def == null) {
			throw new LispReadException("No such protocol: " + protocol);
		}
		List<LispVal> entries = ClojureLowerUtil.items(items.get(3));
		if (entries == null || entries.isEmpty() || !ClojureLowerUtil.isSymbolNamed(entries.get(0), "%hash-map")
				|| entries.size() % 2 == 0) {
			throw new LispReadException("extend takes a map literal of methods, not " + items.get(3).print());
		}
		LispVal key = extendKeyForm(ctx, target, "extend");
		boolean typed = isTypedTarget(ctx, target);
		List<LispVal> rows = new ArrayList<>();
		for (int i = 1; i < entries.size(); i += 2) {
			ClojureLowerUtil.isTrue(entries.get(i) instanceof LispSymbol k && k.name().startsWith(":"),
					"extend takes keyword method names, not " + entries.get(i).print());
			String method = ((LispSymbol) entries.get(i)).name().substring(1);
			ClojureLowerUtil.isTrue(def.methods().contains(method), "Can't define method not in interfaces: " + method);
			LispVal fun = ClojureBindingLowering.fnValue(ctx, entries.get(i + 1));
			LispVal row = key == null ? objectStoreForm(def, method, fun)
					: rowStoreForm(ctx, def, def.methodsVar(), key, method, fun, typed);
			rows.add(row);
		}
		ctx.usedProtocols = true;
		if (rows.isEmpty()) {
			return ClojureLowering.NIL_CONST;
		}
		return ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), rows);
	}

	/**
	 * {@code (satisfies? Protocol x)}: table membership -- the tag's row, or the
	 * {@code Object} row an extension installed, like the oracle. The protocol is a
	 * literal name, like {@code defmethod}'s multimethod.
	 */
	static LispVal satisfiesOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 3, "satisfies? takes a protocol and a value");
		ClojureLowerUtil.isTrue(items.get(1) instanceof LispSymbol, "satisfies? takes a protocol name");
		String protocol = ((LispSymbol) items.get(1)).name();
		ClojureLowering.ProtocolDef def = protocolOf(ctx, protocol);
		if (def == null) {
			throw new LispReadException("No such protocol: " + protocol);
		}
		LispSymbol tag = ctx.freshTemp();
		LispSymbol miss = ctx.freshTemp();
		LispSymbol inner = ctx.freshTemp();
		LispVal hit = ClojureLowerUtil.list(ClojureLowerUtil.sym("not"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), inner, miss));
		LispVal answer = ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), hit, ClojureLowering.TRUE_CONST,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("null"), def.defaultVar()), ctx.falseVariable,
						ClojureLowering.TRUE_CONST));
		ctx.usedProtocols = true;
		LispVal row = ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), tag, def.methodsVar(), miss);
		if (def.inlineVar() != null) {
			// a body implementation lives in the inline table; metadata never
			// satisfies, like the oracle
			LispSymbol direct = ctx.freshTemp();
			row = ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(direct,
							ClojureLowerUtil.list(ClojureLowerUtil.sym("gethash"), tag, def.inlineVar(), miss)))),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("if"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"), direct, miss), row, direct));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"),
				ClojureLowerUtil.list(List.of(
						ClojureLowerUtil.list(tag,
								ClojureLowerUtil.list(new LispSymbol(PROTOCOL_TAG), ctx.lower(items.get(2)))),
						ClojureLowerUtil.list(miss,
								ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), ClojureLowering.NIL_CONST)),
						ClojureLowerUtil.list(inner, row))),
				answer);
	}

	/**
	 * {@code (reify Protocol (method [target & args] body...)+ ...)}: one fresh tag per
	 * evaluation with a row per method in each protocol's table, answering the opaque
	 * value -- a single-shot map plus methods (never {@code proxy}, which stays the
	 * {@code java:} surface). A method named again over another parameter vector is
	 * another arity of it.
	 */
	static LispVal reifyForm(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "reify takes a protocol and methods");
		TypeBody parsed = typeBody(ctx, items.subList(1, items.size()), "reify");
		LispSymbol self = ctx.freshTemp();
		List<LispVal> prologue = new ArrayList<>();
		prologue.add(ClojureLowerUtil.list(self, ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), REIFY_TAG,
				ClojureLowerUtil.list(ClojureLowerUtil.sym("gensym"), LispString.literal("reify")))));
		List<LispVal> body = new ArrayList<>();
		for (ClojureLowering.ImplGroup group : parsed.groups()) {
			ClojureLowering.ProtocolDef def = protocolOf(ctx, group.protocol());
			if (def == null) {
				throw new LispReadException("No such protocol: " + group.protocol());
			}
			if (group.methods().isEmpty()) {
				body.add(emptyRowForm(def.inlineTable(), ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), self)));
			}
			for (ClojureLowering.TypeMethod impl : group.methods()) {
				LispVal lambda = methodLambda(ctx, impl);
				body.add(rowStoreForm(ctx, def, def.inlineTable(),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), self), impl.method(), lambda, true));
			}
		}
		// a reify prints as the oracle's class of the namespace it is lowered in
		body.addAll(interfaceRows(ctx, "reify", parsed.interfaces(),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("cadr"), self), ctx.currentNs.replace('-', '_') + "$reify",
				null, List.of()));
		body.add(self);
		ctx.usedProtocols = true;
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(prologue),
				ClojureLowerUtil.cons(ClojureLowerUtil.sym("progn"), body));
	}

	// macros: defmacro, lower-time expansion, syntax-quote, macroexpand, gensym

}
