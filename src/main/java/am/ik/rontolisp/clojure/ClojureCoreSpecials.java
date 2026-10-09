package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The {@code clojure.core} specials a program reads, binds with {@code binding}, assigns
 * with {@code set!} and takes the var of: the streams ({@code *out*}, {@code *in*},
 * {@code *err*}), the agent var and the flags. Each lowers to one special variable, so
 * {@code binding} is a {@code let*} of it.
 *
 * <p>
 * Measured on the oracle (clj 1.12.6, {@code clj -M file} and the REPL alike):
 * {@code clojure.main} binds the flags marked {@link Special#mainBound} around a script,
 * so {@code thread-bound?} of their var is true and {@code set!} assigns at the top
 * level; it binds none of the others, whose {@code set!} outside a {@code binding} is the
 * {@code Can't change/establish root binding} error. Those carry a binding-depth counter
 * ({@link Special#counter}) that every {@code binding} rebinds one deeper, which the
 * {@code #'} site reads for {@code thread-bound?} and {@code set!} tests; a program
 * reading none sheds the counters ({@link ClojureArms.Family#STREAM_DEPTH}).
 *
 * <p>
 * The printer honours {@code *print-length*}, {@code *print-level*} and
 * {@code *print-readably*} ({@link ClojureArms.Family#PRINT_FLAGS}), {@code *print-meta*}
 * ({@link ClojureArms.Family#PRINT_META}) and {@code *print-namespace-maps*}
 * ({@link ClojureArms.Family#NAMESPACE_MAP}); {@code assert} reads {@code *assert*} where
 * it lowers, after a top-level {@code set!} of it to a literal; the run-time reader reads
 * {@code *data-readers*}, whose root is the program's data readers' map
 * ({@link ClojureDataReaders}), and {@code *default-data-reader-fn*}
 * ({@link ClojureArms.Family#DATA_READERS}). Every other flag is a plain value.
 *
 * <p>
 * What {@code clojure.main} binds around a load: {@code *ns*} holds the namespace
 * {@code ns} and {@code in-ns} switch to, {@code *file*} the file's path (the entry's
 * absolute, a required namespace's below its root) and {@code *source-path*} its name,
 * each rebound around a required namespace's load ({@link ClojureArms.Family#NS_SWITCH}
 * and its siblings: a program reading none sheds the switches). {@code *repl*} is true
 * and bound in a session only; the REPL's {@code *1}/{@code *2}/{@code *3}/{@code *e} are
 * nil in a file and a session records into them.
 */
final class ClojureCoreSpecials {

	private ClojureCoreSpecials() {
	}

	/**
	 * One special.
	 *
	 * @param name the Clojure name
	 * @param symbol the special variable it lowers to
	 * @param root the root value as a Clojure datum, or null when the variable is defined
	 * elsewhere (the Common Lisp streams, the STM runtime's agent var)
	 * @param lowered whether {@code root} is already a lowered form
	 * @param counter the binding-depth counter, or null for a flag {@code clojure.main}
	 * binds
	 * @param reader the library function a read of a stream as a value calls, or null
	 * when the read is the variable itself: at the root {@code *standard-output*} and
	 * {@code *standard-input*} hold the {@code t} designator, Clojure's {@code true}, so
	 * {@code *out*} and {@code *in*} answer a stream value there instead; {@code *err*}
	 * holds one already, and its reader only marks the read as a stream producer
	 * ({@link ClojureArms.Family#STREAM})
	 */
	record Special(String name, LispSymbol symbol, @Nullable LispVal root, boolean lowered,
			@Nullable LispSymbol counter, @Nullable LispSymbol reader) {

		/**
		 * Whether {@code clojure.main} binds it around a script: always thread-bound, so
		 * it has no counter.
		 * @return {@code true} for a {@code clojure.main}-bound flag
		 */
		boolean mainBound() {
			return this.counter == null;
		}

	}

	/** The special {@code *ns*} lowers to. */
	static final LispSymbol NS = new LispSymbol("RONTOLISP::%CLOJURE-NS");

	/** The special {@code *file*} lowers to. */
	static final LispSymbol FILE = new LispSymbol("RONTOLISP::%CLOJURE-FILE");

	/** The special {@code *source-path*} lowers to. */
	static final LispSymbol SOURCE_PATH = new LispSymbol("RONTOLISP::%CLOJURE-SOURCE-PATH");

	/** The library function interning a namespace by name ({@code clojure.lisp}). */
	static final String NS_OBJECT = "RONTOLISP::%CLOJURE-NS-OBJECT";

	/** {@code *file*} where no file is read: a session, a program read without one. */
	static final String NO_SOURCE_PATH = "NO_SOURCE_PATH";

	/** {@code *source-path*} where no file is read. */
	static final String NO_SOURCE_FILE = "NO_SOURCE_FILE";

	private static final Map<String, Special> SPECIALS = table();

	/** Every counter, for {@link ClojureArms.Family#STREAM_DEPTH}. */
	static final Set<String> COUNTERS = SPECIALS.values()
		.stream()
		.filter(s -> s.counter() != null)
		.map(s -> s.counter().name())
		.collect(Collectors.toUnmodifiableSet());

	/** The specials the printer reads ({@link ClojureArms.Family#PRINT_FLAGS}). */
	static final Set<String> PRINT_FLAGS = Set.of(flagSymbol("*print-length*").name(),
			flagSymbol("*print-level*").name(), flagSymbol("*print-readably*").name());

	/**
	 * The special only through which metadata prints
	 * ({@link ClojureArms.Family#PRINT_META}).
	 */
	static final Set<String> PRINT_META = Set.of(flagSymbol("*print-meta*").name());

	private static Map<String, Special> table() {
		Map<String, Special> table = new LinkedHashMap<>();
		stream(table, "*out*", "*STANDARD-OUTPUT*", "RONTOLISP::%CLOJURE-OUT");
		stream(table, "*in*", "*STANDARD-INPUT*", "RONTOLISP::%CLOJURE-IN");
		stream(table, "*err*", "*ERROR-OUTPUT*", "RONTOLISP::%CLOJURE-ERR");
		stream(table, "*agent*", "C%AGENT", null);
		LispSymbol nil = new LispSymbol("nil");
		LispSymbol yes = new LispSymbol("true");
		LispSymbol no = new LispSymbol("false");
		flag(table, "*print-length*", nil, true);
		flag(table, "*print-level*", nil, true);
		flag(table, "*print-readably*", yes, true);
		flag(table, "*print-meta*", no, true);
		flag(table, "*print-namespace-maps*", yes, true);
		flag(table, "*warn-on-reflection*", no, true);
		flag(table, "*unchecked-math*", no, true);
		flag(table, "*assert*", yes, true);
		flag(table, "*read-eval*", yes, true);
		flag(table, "*math-context*", nil, true);
		flag(table, "*data-readers*", ClojureLowerUtil.list(new LispSymbol("%hash-map")), true);
		flag(table, "*default-data-reader-fn*", nil, true);
		flag(table, "*compile-path*", LispString.literal("classes"), true);
		String args = "*command-line-args*";
		// the program's own arguments: the host vector past the program name, nil
		// when there are none
		table.put(args, new Special(args, flagSymbol(args),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("cdr"), ClojureLowerUtil.list(new LispSymbol("%HOST-ARGV"))),
				true, null, null));
		flag(table, "*print-dup*", no, false);
		flag(table, "*flush-on-newline*", yes, false);
		flag(table, "*compile-files*", no, false);
		flag(table, "*clojure-version*",
				ClojureLowerUtil.list(new LispSymbol("%hash-map"), new LispSymbol(":major"), new LispInteger(1),
						new LispSymbol(":minor"), new LispInteger(12), new LispSymbol(":incremental"),
						new LispInteger(6), new LispSymbol(":qualifier"), nil),
				false);
		flag(table, "*verbose-defrecords*", no, false);
		flag(table, "*allow-unresolved-vars*", no, false);
		flag(table, "*compiler-options*", nil, false);
		flag(table, "*fn-loader*", nil, false);
		flag(table, "*reader-resolver*", nil, false);
		flag(table, "*suppress-read*", nil, false);
		flag(table, "*use-context-classloader*", yes, false);
		// the load's own: the roots are the lowering's (rootOf)
		table.put("*ns*", new Special("*ns*", NS, null, false, null, null));
		table.put("*file*", new Special("*file*", FILE, null, false, null, null));
		table.put("*source-path*", new Special("*source-path*", SOURCE_PATH, null, false, null, null));
		table.put("*repl*", new Special("*repl*", flagSymbol("*repl*"), null, false, counterSymbol("*repl*"), null));
		history(table, "*1", "1");
		history(table, "*2", "2");
		history(table, "*3", "3");
		history(table, "*e", "E");
		return table;
	}

	private static void history(Map<String, Special> table, String name, String suffix) {
		table.put(name, new Special(name, new LispSymbol("RONTOLISP::%CLOJURE-HISTORY-" + suffix),
				new LispSymbol("nil"), false, null, null));
	}

	/**
	 * A namespace, interned by name.
	 * @param name the namespace
	 * @return the lowered form
	 */
	static LispVal namespaceObject(String name) {
		return ClojureLowerUtil.list(new LispSymbol(NS_OBJECT), LispString.literal(name));
	}

	/**
	 * The root of a special as a lowered form, or null when the variable is defined
	 * elsewhere: the table's, or for the load's own the lowering's -- {@code user}, the
	 * entry file and its name, whether a session reads, and the program's data readers'
	 * map ({@link ClojureLowering#dataReadersRoot}) where it has one.
	 */
	private static @Nullable LispVal rootOf(ClojureLowering ctx, Special special) {
		LispVal dataReaders = ctx.dataReadersRoot;
		if (special.name().equals("*data-readers*") && dataReaders != null) {
			// the program's data_readers.clj map (ClojureDataReaders)
			return dataReaders;
		}
		return switch (special.name()) {
			case "*ns*" -> namespaceObject("user");
			case "*file*" -> LispString.literal(ctx.rootFile);
			case "*source-path*" -> LispString.literal(ctx.rootSourcePath);
			case "*repl*" -> ctx.session ? ClojureLowering.TRUE_CONST : ctx.lower(new LispSymbol("false"));
			default -> special.root() == null ? null : special.lowered() ? special.root() : ctx.lower(special.root());
		};
	}

	private static void stream(Map<String, Special> table, String name, String symbol, @Nullable String reader) {
		table.put(name, new Special(name, new LispSymbol(symbol), null, false, counterSymbol(name),
				reader == null ? null : new LispSymbol(reader)));
	}

	private static void flag(Map<String, Special> table, String name, LispVal root, boolean mainBound) {
		table.put(name, new Special(name, flagSymbol(name), root, false, mainBound ? null : counterSymbol(name), null));
	}

	private static LispSymbol flagSymbol(String name) {
		return new LispSymbol("RONTOLISP::%CLOJURE-" + bare(name));
	}

	private static LispSymbol counterSymbol(String name) {
		return new LispSymbol("RONTOLISP::%CLOJURE-" + bare(name) + "-DEPTH");
	}

	private static String bare(String name) {
		return name.substring(1, name.length() - 1).toUpperCase(Locale.ROOT);
	}

	/**
	 * The special a {@code clojure.core} name is, or null.
	 * @param name the plain name ({@code *out*}, not {@code clojure.core/*out*})
	 * @return the special, or null
	 */
	static @Nullable Special of(String name) {
		return SPECIALS.get(name);
	}

	/**
	 * The special a {@code clojure.core} name is.
	 * @param name a special's plain name
	 * @return the special
	 */
	static Special required(String name) {
		Special special = SPECIALS.get(name);
		if (special == null) {
			throw new IllegalArgumentException("no clojure.core special: " + name);
		}
		return special;
	}

	/**
	 * A {@code binding}/{@code set!} target's plain special name: the name itself, or the
	 * name a {@code clojure.core/} spelling (a syntax-quote's) qualifies.
	 * @param name the target as written
	 * @return the special's name, or null when it names none
	 */
	static @Nullable String targetName(String name) {
		String plain = name.startsWith(ClojureCoreNames.PREFIX) ? name.substring(ClojureCoreNames.PREFIX.length())
				: name;
		return SPECIALS.containsKey(plain) ? plain : null;
	}

	/**
	 * The definitions of the given specials, in table order: each flag's
	 * {@code (defvar symbol root)} and each counter's {@code (defvar counter 0)}. The
	 * program carries them ahead of everything else, like the false binding, so the
	 * interpreter binds them dynamically before the library loads.
	 * @param ctx the hub, which lowers the roots
	 * @param names the specials' names
	 * @return the forms
	 */
	static List<LispVal> definitions(ClojureLowering ctx, Collection<String> names) {
		List<LispVal> forms = new ArrayList<>();
		for (Special special : SPECIALS.values()) {
			if (!names.contains(special.name())) {
				continue;
			}
			LispVal root = rootOf(ctx, special);
			if (root != null) {
				// the library defines the run-time reader's specials ahead of a compiled
				// program, so the program's own data readers replace its empty root
				boolean replaced = special.name().equals("*data-readers*") && ctx.dataReadersRoot != null;
				forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym(replaced ? "defparameter" : "defvar"),
						special.symbol(), root));
			}
			if (special.counter() != null) {
				// a session binds *repl* around every input, like the oracle's REPL
				int depth = ctx.session && special.name().equals("*repl*") ? 1 : 0;
				forms.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defvar"), special.counter(),
						new LispInteger(depth)));
			}
		}
		return forms;
	}

	/**
	 * Every special's name, for the macro-time evaluator, which defines them all.
	 * @return the names
	 */
	static Collection<String> names() {
		return SPECIALS.keySet();
	}

}
