package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * How a class whose Clojure program lowers as it runs -- the HOST -- and the classes
 * compiled for the forms it lowers then -- its FRAGMENTS -- link: a fragment calls the
 * functions and reads the globals the host or an earlier fragment defines through their
 * own methods and fields, by name, and shares the host's run-time state (its streams, its
 * condition channel, its markers, the layouts its instances carry), so a value one makes
 * is the other's ({@code .kb/jvm-run-time-lowering.md}).
 *
 * <p>
 * The host carries its {@link Table} ({@link Table#encode()}), and every fragment compile
 * answers the table of what it defined ({@link JvmLispCompiler#linkTable()}), which joins
 * the ones the next fragment links against.
 */
public final class JvmLinkage {

	private JvmLinkage() {
	}

	/**
	 * A function a fragment calls by name.
	 *
	 * @param owner the internal name of the class whose static method it is
	 * @param paramCount its physical parameters (the rest list one)
	 * @param variadic whether the last one takes the rest
	 * @param optionals its physical optionals
	 * @param bounces whether it takes the value-tail depth and may answer a bounce
	 * @param id the id its function values carry, which its class's dispatchers take
	 * @param wrapper whether it is a built-in's function value the class injected (a
	 * wrapper), not a definition of the program: a later fragment takes the value from
	 * the class and generates no wrapper of its own, while a call of the name stays the
	 * built-in's
	 */
	public record Function(String owner, int paramCount, boolean variadic, int optionals, boolean bounces, int id,
			boolean wrapper) {
	}

	/**
	 * A global variable a fragment reads and assigns by name.
	 *
	 * @param owner the internal name of the class whose static field holds it
	 * @param special whether it is special (a binding of it is dynamic)
	 * @param threadBound whether a binding of it goes through its {@code _d$} ThreadLocal
	 * @param checked whether a read of it checks for the unbound marker
	 */
	public record Global(String owner, boolean special, boolean threadBound, boolean checked) {
	}

	/**
	 * A class (a {@code defclass}, a {@code define-condition}, the built-in condition
	 * hierarchy) a later fragment's type tests and slot reads know: what the fragment's
	 * registry needs so a {@code handler-case} clause or a {@code typep} there takes an
	 * instance the host made.
	 *
	 * @param superclasses its direct superclasses, in local precedence order
	 * @param cpl its class precedence list
	 * @param ancestors the class and its ancestors
	 * @param slots its effective slots' names, in layout order
	 * @param slotBases the same slots' package-stripped names
	 */
	public record ClassLink(List<String> superclasses, List<String> cpl, List<String> ancestors, List<String> slots,
			List<String> slotBases) {

		public ClassLink {
			superclasses = List.copyOf(superclasses);
			cpl = List.copyOf(cpl);
			ancestors = List.copyOf(ancestors);
			slots = List.copyOf(slots);
			slotBases = List.copyOf(slotBases);
		}

		/** The lists the encoding writes, in order. */
		List<List<String>> parts() {
			return List.of(this.superclasses, this.cpl, this.ancestors, this.slots, this.slotBases);
		}
	}

	/**
	 * A program-wide shape a class compiles under that a value or a condition carries
	 * into another: a fragment compiles under every one its host or an earlier fragment
	 * took, whatever its own forms use, since what it takes may come from them.
	 */
	public enum Mode {

		/**
		 * The restart system: signal hooks and the landing pads' handlers-ran records.
		 */
		RESTART,

		/**
		 * {@code handler-case} clause types on the dynamic stack {@code signal} reads.
		 */
		SIGNAL_CLAUSE_MATCH,

		/** A non-local exit a {@code handler-case} region must let pass. */
		BLOCK_EXIT,

		/** Instances: the predicates' and {@code typep}'s instance arms. */
		INSTANCES,

		/** A landing pad, which a program error carries its instance to. */
		LANDING_PAD,

		/** Stream values. */
		STREAM_VALUES,

		/** The printer-control variables every printing operator applies. */
		PRINT_CONTROLS,

		/** A printer-control variable named, not only {@code *package*}. */
		PRINT_CONTROL_VARIABLES,

		/**
		 * {@code equalp} tables: the hash helpers fold such a table's keys, wherever it
		 * was made.
		 */
		HASH_EQUALP,

		/**
		 * {@code eq}/{@code eql} tables: the hash helpers key such a table by identity,
		 * wherever it was made (a fragment putting into the host's table must find what
		 * the host puts and gets).
		 */
		HASH_IDENTITY,

		/** The array helpers. */
		ARRAYS,

		/** Packed float arrays, which the array operations dispatch on. */
		FLOAT_ARRAYS,

		/** Packed integer vectors, which the array operations dispatch on. */
		INT_ARRAYS,

		/** Complex numbers, which the arithmetic takes. */
		COMPLEX

	}

	/**
	 * What a class defines that a later fragment links against.
	 *
	 * @param functions its functions by canonical name
	 * @param globals its global variables by canonical name
	 * @param fields the static fields a later class shares rather than declares again
	 * ({@link #shared}) -- its globals' fields, its run-time state, its layouts -- by
	 * {@code name:descriptor}, to the internal name of the class declaring each
	 * @param funcIdEnd one past the highest function id the class's function values carry
	 * @param classes the classes its registry knew, by canonical name
	 * @param modes the program-wide shapes it compiled under
	 */
	public record Table(Map<String, Function> functions, Map<String, Global> globals, Map<String, String> fields,
			int funcIdEnd, Map<String, ClassLink> classes, Set<Mode> modes) {

		/** A table of nothing. */
		public static final Table EMPTY = new Table(Map.of(), Map.of(), Map.of(), 0, Map.of(), Set.of());

		public Table {
			functions = Map.copyOf(functions);
			globals = Map.copyOf(globals);
			fields = Map.copyOf(fields);
			classes = Map.copyOf(classes);
			modes = modes.isEmpty() ? Set.of() : java.util.Collections.unmodifiableSet(java.util.EnumSet.copyOf(modes));
		}

		/**
		 * This table with a later class's on top: a later definition of a name wins, and
		 * the modes add up.
		 * @param later the later class's table
		 * @return the merged table
		 */
		public Table merge(Table later) {
			Map<String, Function> fns = new LinkedHashMap<>(this.functions);
			fns.putAll(later.functions);
			Map<String, Global> gs = new LinkedHashMap<>(this.globals);
			gs.putAll(later.globals);
			Map<String, String> fs = new LinkedHashMap<>(this.fields);
			fs.putAll(later.fields);
			Map<String, ClassLink> cs = new LinkedHashMap<>(this.classes);
			cs.putAll(later.classes);
			Set<Mode> ms = java.util.EnumSet.noneOf(Mode.class);
			ms.addAll(this.modes);
			ms.addAll(later.modes);
			return new Table(fns, gs, fs, Math.max(this.funcIdEnd, later.funcIdEnd), cs, ms);
		}

		/**
		 * This table with each function's class corrected to the one that declares its
		 * method: a class too large for one class file keeps a function in one of its
		 * {@code $PartN} classes.
		 * @param declaring each declared method, {@code name + descriptor}, to the
		 * internal name of its class
		 * @return the table
		 */
		public Table located(Map<String, String> declaring) {
			Map<String, Function> fns = new LinkedHashMap<>();
			this.functions.forEach((name, fn) -> {
				String owner = declaring.get(methodName(name) + descriptor(fn));
				fns.put(name, owner == null ? fn : new Function(owner, fn.paramCount(), fn.variadic(), fn.optionals(),
						fn.bounces(), fn.id(), fn.wrapper()));
			});
			return new Table(fns, this.globals, this.fields, this.funcIdEnd, this.classes, this.modes);
		}

		/**
		 * The table as one string, which the host class carries: its entries in name
		 * order, so one program's class is one sequence of bytes
		 * ({@code .kb/emitted-output-determinism.md}).
		 * @return the encoded table
		 */
		public String encode() {
			List<String> fields = new ArrayList<>();
			fields.add(VERSION);
			fields.add(Integer.toString(this.funcIdEnd));
			fields.add(Integer.toString(this.functions.size()));
			new TreeMap<>(this.functions).forEach((name, fn) -> {
				fields.add(name);
				fields.add(fn.owner());
				fields.add(fn.paramCount() + "," + fn.optionals() + "," + (fn.variadic() ? 1 : 0) + ","
						+ (fn.bounces() ? 1 : 0) + "," + fn.id() + "," + (fn.wrapper() ? 1 : 0));
			});
			fields.add(Integer.toString(this.globals.size()));
			new TreeMap<>(this.globals).forEach((name, g) -> {
				fields.add(name);
				fields.add(g.owner());
				fields.add((g.special() ? "s" : "") + (g.threadBound() ? "t" : "") + (g.checked() ? "c" : "") + ".");
			});
			fields.add(Integer.toString(this.fields.size()));
			new TreeMap<>(this.fields).forEach((field, owner) -> {
				fields.add(field);
				fields.add(owner);
			});
			fields.add(Integer.toString(this.classes.size()));
			new TreeMap<>(this.classes).forEach((name, c) -> {
				fields.add(name);
				for (List<String> names : c.parts()) {
					fields.add(Integer.toString(names.size()));
					fields.addAll(names);
				}
			});
			// a fixed order: the enum's
			fields.add(Integer.toString(this.modes.size()));
			for (Mode mode : Mode.values()) {
				if (this.modes.contains(mode)) {
					fields.add(mode.name());
				}
			}
			StringBuilder out = new StringBuilder();
			for (String field : fields) {
				out.append(field.length()).append(':').append(field);
			}
			return out.toString();
		}

		/**
		 * The table an {@link #encode()} string holds.
		 * @param text the encoded table
		 * @return the table
		 * @throws IllegalArgumentException when the text is no table of this version
		 */
		public static Table decode(String text) {
			Fields in = new Fields(text);
			if (!VERSION.equals(in.next())) {
				throw new IllegalArgumentException("not a " + VERSION);
			}
			int funcIdEnd = Integer.parseInt(in.next());
			Map<String, Function> functions = new LinkedHashMap<>();
			int functionCount = Integer.parseInt(in.next());
			for (int i = 0; i < functionCount; i++) {
				String name = in.next();
				String owner = in.next();
				String[] shape = in.next().split(",");
				functions.put(name,
						new Function(owner, Integer.parseInt(shape[0]), shape[2].equals("1"),
								Integer.parseInt(shape[1]), shape[3].equals("1"), Integer.parseInt(shape[4]),
								shape[5].equals("1")));
			}
			Map<String, Global> globals = new LinkedHashMap<>();
			int globalCount = Integer.parseInt(in.next());
			for (int i = 0; i < globalCount; i++) {
				String name = in.next();
				String owner = in.next();
				String flags = in.next();
				globals.put(name, new Global(owner, flags.contains("s"), flags.contains("t"), flags.contains("c")));
			}
			Map<String, String> fields = new LinkedHashMap<>();
			int fieldCount = Integer.parseInt(in.next());
			for (int i = 0; i < fieldCount; i++) {
				fields.put(in.next(), in.next());
			}
			Map<String, ClassLink> classes = new LinkedHashMap<>();
			int classCount = Integer.parseInt(in.next());
			for (int i = 0; i < classCount; i++) {
				String name = in.next();
				List<List<String>> parts = new ArrayList<>();
				for (int part = 0; part < 5; part++) {
					List<String> names = new ArrayList<>();
					int count = Integer.parseInt(in.next());
					for (int j = 0; j < count; j++) {
						names.add(in.next());
					}
					parts.add(names);
				}
				classes.put(name, new ClassLink(parts.get(0), parts.get(1), parts.get(2), parts.get(3), parts.get(4)));
			}
			Set<Mode> modes = java.util.EnumSet.noneOf(Mode.class);
			int modeCount = Integer.parseInt(in.next());
			for (int i = 0; i < modeCount; i++) {
				modes.add(Mode.valueOf(in.next()));
			}
			return new Table(functions, globals, fields, funcIdEnd, classes, modes);
		}

	}

	/**
	 * The method a function compiles to.
	 * @param name the function's canonical name
	 * @return its method's name
	 */
	public static String methodName(String name) {
		return JvmLispCompiler.mangleMethodName(name);
	}

	/**
	 * A function's method descriptor: an {@code Object} per physical parameter, the
	 * value-tail depth after them when it may answer a bounce.
	 * @param fn the function
	 * @return the descriptor
	 */
	public static String descriptor(Function fn) {
		return "(" + "Ljava/lang/Object;".repeat(fn.paramCount()) + (fn.bounces() ? "I" : "") + ")Ljava/lang/Object;";
	}

	/**
	 * Whether a later class shares a static field of this name with the class declaring
	 * it rather than declaring its own: a global variable's ({@code _g$}), a special's
	 * per-thread binding ({@code _d$}), an instance layout ({@code _ly$}), and the
	 * run-time state a value or a condition carries from one class into another -- the
	 * stream table, the output column, the gensym counter, the condition, non-local exit
	 * and wrong-type channels, the unbound and unsupplied markers, the multiple-value
	 * register, the thread handle cache.
	 * @param name a static field's name
	 * @return whether it is shared
	 */
	public static boolean shared(String name) {
		return name.startsWith("_g$") || name.startsWith("_d$") || name.startsWith("_ly$")
				|| SHARED_STATE.contains(name);
	}

	private static final Set<String> SHARED_STATE = Set.of("_stdinReader", "_streams", "_streamCount", "_streamPaths",
			"_streamPositions", "_col", "_gensymCtr", "_condTl", "_hcDepthTl", "_nleTl", "_teTl", "_jexMap", "_jsigTl",
			"_unbound", "_unsupplied", "_mvTl", "_mvOwner", "_mvBox", "_curThreadTl", "_handoffTl", "_renderPath",
			"_renderDepth");

	/**
	 * What a fragment compile links against.
	 *
	 * @param host the internal name of the host class, whose run-time state the fragment
	 * shares and whose {@link #FOREIGN_APPLY} calls a function value of another class
	 * @param linked what the host and the fragments before this one define
	 * @param funcIdBase the first function id the fragment's own function values carry,
	 * past every id of the classes before it
	 */
	public record Fragment(String host, Table linked, int funcIdBase) {
	}

	/**
	 * The host's static method a class calls a function value of ANOTHER class through
	 * ({@code (Object fn, Object argList, int depth) -> Object}): the run-time half
	 * routes it by its id to the class that made it.
	 */
	public static final String FOREIGN_APPLY = "_foreignApply";

	/** {@link #FOREIGN_APPLY}'s descriptor. */
	public static final String FOREIGN_APPLY_DESC = "(Ljava/lang/Object;Ljava/lang/Object;I)Ljava/lang/Object;";

	/**
	 * The host's static field holding the run-time half's router of a foreign function
	 * value, a {@code MethodHandle} of {@code (Object, Object, int) -> Object} the
	 * run-time half installs.
	 */
	public static final String FOREIGN_FIELD = "_foreign";

	/**
	 * The static method a host's or a fragment's {@code _lookup} asks on a miss
	 * ({@code (Object name) -> Object[]}): the run-time half answers the registry row of
	 * a function of that name a fragment defined -- {@code {Integer id, Integer arity}},
	 * the row's shape -- or null. A host defines it; a fragment calls its host's.
	 */
	public static final String FOREIGN_LOOKUP = "_foreignLookup";

	/** {@link #FOREIGN_LOOKUP}'s descriptor. */
	public static final String FOREIGN_LOOKUP_DESC = "(Ljava/lang/Object;)[Ljava/lang/Object;";

	/**
	 * The host's static field holding the run-time half's {@link #FOREIGN_LOOKUP}, a
	 * {@code MethodHandle} of {@code (Object) -> Object[]}; null until a lowering verb
	 * started the session (before it no fragment defined anything).
	 */
	public static final String FOREIGN_LOOKUP_FIELD = "_foreignNames";

	/**
	 * The static method a host's or a fragment's {@code _funcref} hands an id of another
	 * class to ({@code (int id) -> Object}): the run-time half answers the function value
	 * of that id, the box of the class that made it where that class has one.
	 */
	public static final String FOREIGN_FUNCREF = "_foreignFuncref";

	/** {@link #FOREIGN_FUNCREF}'s descriptor. */
	public static final String FOREIGN_FUNCREF_DESC = "(I)Ljava/lang/Object;";

	/**
	 * The host's static field holding the run-time half's {@link #FOREIGN_FUNCREF}, a
	 * {@code MethodHandle} of {@code (int) -> Object}.
	 */
	public static final String FOREIGN_FUNCREF_FIELD = "_foreignBoxes";

	/** The host's static method answering its run-time image ({@code ()String}). */
	public static final String IMAGE_METHOD = "_rtImage";

	/**
	 * The host's static method answering its encoded {@link Table} ({@code ()String}).
	 */
	public static final String TABLE_METHOD = "_rtLink";

	/**
	 * The host's static method a lowering verb at run time reaches
	 * ({@code (Object verb, Object args, Object meta, Object refuse, Object ns) -> Object}).
	 */
	public static final String LOWER_METHOD = "_lowerAtRunTime";

	/** {@link #LOWER_METHOD}'s descriptor. */
	public static final String LOWER_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;"
			+ "Ljava/lang/Object;)Ljava/lang/Object;";

	/**
	 * The run-time half the host hands a lowering verb to: a class of the compiler the
	 * class carries ({@code cli/JvmRunTimeLowering}), named, never imported -- the
	 * backend depends on no command-line class.
	 */
	public static final String RUN_TIME_CLASS = "am/ik/rontolisp/cli/JvmRunTimeLowering";

	/** The run-time half's entry: {@code lower(Lookup, verb, args, meta, refuse, ns)}. */
	public static final String RUN_TIME_METHOD = "lower";

	/** {@link #RUN_TIME_METHOD}'s descriptor. */
	public static final String RUN_TIME_DESC = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/Object;"
			+ "Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	/** A fragment's static method running its forms and answering the call's value. */
	public static final String RUN_METHOD = "_run";

	/** {@link #RUN_METHOD}'s descriptor. */
	public static final String RUN_DESC = "()Ljava/lang/Object;";

	/** A fragment's static field holding the value of the form that answers. */
	public static final String ANSWER_FIELD = "_answer";

	/**
	 * A fragment's static field holding the values its forms embed
	 * ({@code rontolisp::%run-time-constant}), set before {@link #RUN_METHOD}.
	 */
	public static final String CONSTANTS_FIELD = "_constants";

	private static final String VERSION = "rontolisp-link-4";

	private static final class Fields {

		private final String text;

		private int pos;

		Fields(String text) {
			this.text = text;
		}

		String next() {
			int colon = this.text.indexOf(':', this.pos);
			if (colon < 0) {
				throw new IllegalArgumentException("a truncated link table");
			}
			int length = Integer.parseInt(this.text.substring(this.pos, colon));
			this.pos = colon + 1 + length;
			return this.text.substring(colon + 1, this.pos);
		}

	}

	/**
	 * The method emitting a string constant past one {@code ldc}'s 65535 modified UTF-8
	 * bytes: chunks appended to a {@code StringBuilder}.
	 * @param text the string
	 * @return the chunks, each within one constant
	 */
	static List<String> constantChunks(String text) {
		List<String> chunks = new ArrayList<>();
		// a char takes at most three modified UTF-8 bytes
		int chunk = 65535 / 3;
		int i = 0;
		while (i < text.length()) {
			int end = Math.min(text.length(), i + chunk);
			// a surrogate pair stays in one chunk
			if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
				end--;
			}
			chunks.add(text.substring(i, end));
			i = end;
		}
		if (chunks.isEmpty()) {
			chunks.add("");
		}
		return chunks;
	}

}
