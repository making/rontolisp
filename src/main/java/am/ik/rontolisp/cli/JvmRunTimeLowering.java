package am.ik.rontolisp.cli;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.jvm.JvmLinkage;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.eval.ClojureProgramLowering;
import am.ik.rontolisp.eval.SourceStandards;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The run-time half of a JVM class whose Clojure program lowers as it runs (a HOST,
 * {@link JvmLinkage}): the class hands a lowering verb over computed arguments --
 * {@code eval}, a computed {@code resolve} or {@code require}, {@code load-string} ... --
 * here, and this lowers it the way the interpreter does, compiles what it lowers to into
 * a class of its own linked against the host and the classes before it (a FRAGMENT),
 * defines that class beside the host and runs it.
 *
 * <p>
 * The first verb of a host starts its session: the program read and lowered again from
 * the image the class carries ({@link RunTimeImage}), which takes the decisions the
 * compile-time lowering took, so the lowering this keeps names the functions and globals
 * the class holds ({@link ClojureProgramLowering}); and the class's link table, which
 * every fragment's own joins.
 *
 * <p>
 * A fragment is a class of the host's package defined through the host's own lookup, so
 * it reads the host's fields and calls its methods, and a later fragment its. Its
 * function values carry ids past every id before it, so a function value of one class
 * applied by another is routed to the class that made it ({@link #foreignApply}).
 */
public final class JvmRunTimeLowering {

	/**
	 * Each host's session, tied to the host class (a host's loader can be collected).
	 */
	private static final ClassValue<SessionHolder> SESSIONS = new ClassValue<>() {
		@Override
		protected SessionHolder computeValue(Class<?> host) {
			return new SessionHolder();
		}
	};

	/** The classes a lowering failure is thrown as, the oracle's. */
	private static final List<String> COMPILER_EXCEPTION = List.of("clojure.lang.Compiler$CompilerException",
			"java.lang.RuntimeException", "java.lang.Exception", "java.lang.Throwable");

	private JvmRunTimeLowering() {
	}

	/**
	 * One lowering verb at run time, as a host class hands it over.
	 * @param lookup the host's own lookup
	 * @param verb the verb, a Lisp string ({@code eval}, {@code resolve} ...)
	 * @param args the arguments as the run-time library carries them (a list)
	 * @param constants the values the arguments embed, a {@code (count . reversed)} cell
	 * @param refuse the run-time library's refusal of a call that does not lower
	 * @param namespace the value {@code *ns*} holds
	 * @return the value of the call
	 * @throws Throwable what the forms the call lowered to throw, a condition of the
	 * program's
	 */
	public static @Nullable Object lower(MethodHandles.Lookup lookup, Object verb, Object args, Object constants,
			Object refuse, Object namespace) throws Throwable {
		Session session = SESSIONS.get(lookup.lookupClass()).session(lookup);
		return session.lower(verb, args, constants, refuse, namespace);
	}

	/** A host's session, started by its first verb. */
	private static final class SessionHolder {

		private @Nullable Session session;

		synchronized Session session(MethodHandles.Lookup lookup) {
			Session started = this.session;
			if (started == null) {
				started = new Session(lookup);
				this.session = started;
			}
			return started;
		}

	}

	/** One host's run: its lowering, its link table and its fragments. */
	private static final class Session {

		private final MethodHandles.Lookup lookup;

		private final Class<?> host;

		private final String hostName;

		private final ClojureProgramLowering lowering;

		private final MethodHandle apply;

		/** What the host and its fragments define, replaced whole as a fragment joins. */
		private volatile JvmLinkage.Table table;

		private int fragments;

		/**
		 * The function value of an id whose class has no box of its own for it, one per
		 * id, so every class answers the same object.
		 */
		private final Map<Integer, Object[]> boxes = new ConcurrentHashMap<>();

		/**
		 * The reader of the cell of each id's function, found when first needed: a
		 * {@code notinline} function's field, which a redefinition sets
		 * ({@code JvmFunctionCell}), and a reader of null for any other id. An id's
		 * function and its class never change.
		 */
		private final Map<Integer, MethodHandle> cells = new ConcurrentHashMap<>();

		/**
		 * Each class's function ids, and its spread dispatcher, found when first needed:
		 * replaced whole as a fragment joins, so a foreign call reads it without a lock.
		 */
		private volatile List<Range> ranges = List.of();

		Session(MethodHandles.Lookup lookup) {
			this.lookup = lookup;
			this.host = lookup.lookupClass();
			this.hostName = this.host.getName().replace('.', '/');
			try {
				String image = (String) lookup
					.findStatic(this.host, JvmLinkage.IMAGE_METHOD, MethodType.methodType(String.class))
					.invokeExact();
				String table = (String) lookup
					.findStatic(this.host, JvmLinkage.TABLE_METHOD, MethodType.methodType(String.class))
					.invokeExact();
				this.table = located(JvmLinkage.Table.decode(table), this.host);
				this.apply = lookup.findStatic(this.host, "_apply",
						MethodType.methodType(Object.class, Object.class, Object.class));
				RunTimeImage read = RunTimeImage.decode(image);
				// read on a thread of the compiler's stack, like the compile it repeats
				this.lowering = SizedThread.call("rontolisp-run-time-lowering", SizedThread.WORKER_STACK_BYTES,
						() -> ClojureProgramLowering.read(read.source(), Features.JVM, read.entryFile(),
								read.standards(), read.loader(this.host.getClassLoader())));
				this.ranges = List.of(new Range(this.host, 0, this.table.funcIdEnd()));
				install(JvmLinkage.FOREIGN_FIELD, "foreignApply",
						MethodType.methodType(Object.class, Object.class, Object.class, int.class));
				install(JvmLinkage.FOREIGN_LOOKUP_FIELD, "foreignLookup",
						MethodType.methodType(Object[].class, Object.class));
				install(JvmLinkage.FOREIGN_FUNCREF_FIELD, "foreignFuncref",
						MethodType.methodType(Object.class, int.class));
			}
			catch (RuntimeException | Error ex) {
				throw ex;
			}
			catch (Throwable ex) {
				throw new IllegalStateException(this.host.getName() + ": cannot start its run-time lowering", ex);
			}
		}

		/** Sets a host field to a method of this session. */
		private void install(String field, String method, MethodType type) throws Throwable {
			MethodHandle handle = MethodHandles.lookup().findVirtual(Session.class, method, type).bindTo(this);
			this.lookup.findStaticSetter(this.host, field, MethodHandle.class).invoke(handle);
		}

		@Nullable Object lower(Object verb, Object args, Object constants, Object refuse, Object namespace) throws Throwable {
			String verbName = stringValue(verb);
			List<LispVal> transported = new ArrayList<>();
			for (Object arg = args; arg instanceof Object[] cell && cell.length == 2; arg = cell[1]) {
				transported.add(received(cell[0]));
			}
			Object[] embedded = constantsOf(constants);
			Defined defined;
			synchronized (this) {
				List<ClojureProgramLowering.Entry> entries;
				try {
					entries = this.lowering.lower(verbName, transported, namespaceName(namespace),
							index -> list(new LispSymbol(LispNames.RUN_TIME_CONSTANT_QUALIFIED),
									new LispInteger(index)));
				}
				catch (LispReadException ex) {
					String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
					Object classes = null;
					for (int i = COMPILER_EXCEPTION.size() - 1; i >= 0; i--) {
						classes = new Object[] { compiledString(COMPILER_EXCEPTION.get(i)), classes };
					}
					return this.apply.invoke(refuse,
							new Object[] { classes, new Object[] { compiledString(message), null } });
				}
				List<LispVal> program = program(entries);
				if (Boolean.getBoolean("rontolisp.debug.run-time-lowering")) {
					// what each verb lowered to, before the splice
					System.err.println("[run-time-lowering] " + verbName + ":");
					program.forEach(form -> System.err.println("  " + form.print()));
				}
				defined = define(program);
			}
			if (embedded.length > 0) {
				this.lookup.findStaticSetter(defined.fragment(), JvmLinkage.CONSTANTS_FIELD, Object[].class)
					.invoke(embedded);
			}
			return this.lookup
				.findStatic(defined.fragment(), JvmLinkage.RUN_METHOD, MethodType.methodType(Object.class))
				.invoke();
		}

		/**
		 * Compiles the forms into a fragment linked against the host and the fragments
		 * before it, defines it and joins what it defines to the table.
		 */
		private Defined define(List<LispVal> forms) throws IllegalAccessException {
			int base = this.table.funcIdEnd();
			String simple = this.hostName + "$Eval" + (++this.fragments);
			JvmLinkage.Fragment link = new JvmLinkage.Fragment(this.hostName, this.table, base);
			JvmLispCompiler compiler = JvmLispCompiler.builder()
				.className(simple)
				.fragment(link)
				.runtimeFeatures(Features.JVM.names())
				.build();
			JvmLinkage.Table linkedTable = this.table;
			byte[] bytes = SizedThread.call("rontolisp-run-time-compile", SizedThread.WORKER_STACK_BYTES, () -> {
				CompileFrontend.Result frontend = CompileFrontend.expand(
						new CompileFrontend.Loaded(forms, Features.JVM, SourceStandards.DEFAULT, true),
						CompileFrontend.Options.builder().build());
				return compiler.compile(unlinked(frontend.program(), definedNames(forms), linkedTable));
			});
			String dump = System.getProperty("rontolisp.debug.run-time-classes");
			if (dump != null) {
				// each fragment's class file, for javap
				try {
					java.nio.file.Path file = java.nio.file.Path.of(dump, simple.replace('/', '.') + ".class");
					java.nio.file.Files.createDirectories(file.getParent());
					java.nio.file.Files.write(file, bytes);
				}
				catch (java.io.IOException ex) {
					throw new java.io.UncheckedIOException(ex);
				}
			}
			Class<?> fragment = this.lookup.defineClass(bytes);
			String hostPackage = packageOf(this.hostName);
			for (Map.Entry<String, byte[]> file : compiler.runtimeClassFiles().entrySet()) {
				String path = file.getKey();
				if (path.endsWith(".class") && packageOf(path).equals(hostPackage)) {
					this.lookup.defineClass(file.getValue());
				}
			}
			JvmLinkage.Table own = located(compiler.linkTable(), fragment);
			this.table = this.table.merge(own);
			List<Range> grown = new ArrayList<>(this.ranges);
			grown.add(new Range(fragment, base, Math.max(base, own.funcIdEnd())));
			this.ranges = List.copyOf(grown);
			return new Defined(fragment);
		}

		/**
		 * A name a class's registry does not answer: the row of the function of that name
		 * the host or a fragment defines -- {@code {Integer id, Integer arity}}, a
		 * variadic one's arity its negated parameter count, the registry's shape -- or
		 * null. Every function a table lists is a row of its own class, so the class that
		 * made the id takes it.
		 */
		Object @Nullable [] foreignLookup(Object name) {
			if (!(name instanceof String symbol)) {
				return null;
			}
			JvmLinkage.Function fn = this.table.functions().get(symbol);
			if (fn == null) {
				return null;
			}
			return new Object[] { fn.id(), fn.variadic() ? -fn.paramCount() : fn.paramCount() };
		}

		/**
		 * The function value of an id a class's own table does not hold -- a name the
		 * class resolved when the code ran, through {@link #foreignLookup}: what a
		 * redefinition stored in the cell of a {@code notinline} function, else the box
		 * of the class that made it, else one this session keeps for it.
		 */
		Object foreignFuncref(int id) throws Throwable {
			Object redefined = (Object) cell(id).invokeExact();
			if (redefined != null) {
				return redefined;
			}
			Range owner = owner(id);
			Object box = owner.box(this.lookup, id);
			return box != null ? box : this.boxes.computeIfAbsent(id, key -> new Object[] { key });
		}

		/** The reader of the cell of an id's function ({@link #cells}). */
		private MethodHandle cell(int id) throws ReflectiveOperationException {
			MethodHandle found = this.cells.get(id);
			if (found != null) {
				return found;
			}
			found = MethodHandles.constant(Object.class, null);
			for (Map.Entry<String, JvmLinkage.Function> fn : this.table.functions().entrySet()) {
				if (fn.getValue().id() == id && fn.getValue().celled()) {
					String field = JvmLinkage.cellField(fn.getKey());
					String declaring = this.table.fields().get(field + ":" + JvmLinkage.CELL_DESC);
					if (declaring != null) {
						found = this.lookup.findStaticGetter(this.lookup.findClass(declaring.replace('/', '.')), field,
								Object.class);
					}
					break;
				}
			}
			this.cells.put(id, found);
			return found;
		}

		private Range owner(int id) {
			for (Range range : this.ranges) {
				if (id >= range.start && id < range.end) {
					return range;
				}
			}
			throw new IllegalStateException("no class of " + this.host.getName() + " made function " + id);
		}

		/**
		 * A function value of another class applied: routed by its id to the spread
		 * dispatcher of the class that made it.
		 */
		@Nullable Object foreignApply(Object fn, Object args, int depth) throws Throwable {
			int id = fn instanceof Object[] cell && cell.length > 0 && cell[0] instanceof Integer i ? i : -1;
			return owner(id).dispatcher(this.lookup).invoke(fn, args, depth);
		}

	}

	/**
	 * A class's table with each function located in the class declaring its method: the
	 * class itself, or one of the {@code $PartN} classes a class too large for one class
	 * file splits into.
	 */
	private static JvmLinkage.Table located(JvmLinkage.Table table, Class<?> owner) {
		Map<String, String> declaring = new java.util.HashMap<>();
		Class<?> part = owner;
		for (int n = 1; part != null; n++) {
			String name = part.getName().replace('.', '/');
			for (java.lang.reflect.Method method : part.getDeclaredMethods()) {
				declaring
					.put(method.getName() + MethodType.methodType(method.getReturnType(), method.getParameterTypes())
						.toMethodDescriptorString(), name);
			}
			try {
				part = Class.forName(owner.getName() + "$Part" + n, false, owner.getClassLoader());
			}
			catch (ClassNotFoundException ex) {
				part = null;
			}
		}
		return table.located(declaring);
	}

	/** A fragment just defined. */
	private record Defined(Class<?> fragment) {
	}

	/** A class's function ids, {@code [start, end)}, and its spread dispatcher. */
	private static final class Range {

		private final Class<?> owner;

		private final int start;

		private final int end;

		private volatile @Nullable MethodHandle dispatcher;

		private volatile @Nullable MethodHandle funcref;

		Range(Class<?> owner, int start, int end) {
			this.owner = owner;
			this.start = start;
			this.end = end;
		}

		/**
		 * The class's own box of one of its ids ({@code _funcref}), or null where it has
		 * none: no table at all, or none covering the id.
		 */
		@Nullable Object box(MethodHandles.Lookup lookup, int id) throws Throwable {
			MethodHandle found = this.funcref;
			if (found == null) {
				try {
					found = lookup.findStatic(this.owner, "_funcref", MethodType.methodType(Object.class, int.class));
				}
				catch (NoSuchMethodException ex) {
					found = MethodHandles.constant(Object.class, null);
					found = MethodHandles.dropArguments(found, 0, int.class);
				}
				this.funcref = found;
			}
			try {
				return (Object) found.invokeExact(id);
			}
			catch (ArrayIndexOutOfBoundsException ex) {
				return null;
			}
		}

		MethodHandle dispatcher(MethodHandles.Lookup lookup) throws ReflectiveOperationException {
			MethodHandle found = this.dispatcher;
			if (found == null) {
				found = lookup.findStatic(this.owner, "_invoke_v",
						MethodType.methodType(Object.class, Object.class, Object.class, int.class));
				this.dispatcher = found;
			}
			return found;
		}

	}

	/**
	 * The program without the library definitions a class before it already holds: the
	 * splice hands a fragment every helper its forms reach, and the ones the host or an
	 * earlier fragment defines it calls there instead -- a global defined twice would
	 * start its state over. The forms' own definitions stay, a redefinition a store into
	 * the class that holds the global.
	 */
	static List<LispVal> unlinked(List<LispVal> program, java.util.Set<String> own, JvmLinkage.Table table) {
		List<LispVal> out = new ArrayList<>(program.size());
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name
					&& !own.contains(name.name())) {
				boolean linked = switch (head.name()) {
					case LispNames.DEFUN -> table.functions().containsKey(name.name());
					case "DEFVAR", "DEFPARAMETER", "DEFCONSTANT" -> table.globals().containsKey(name.name());
					default -> false;
				};
				if (linked) {
					continue;
				}
			}
			out.add(form);
		}
		return out;
	}

	/** The names the forms define at their top level, a {@code progn}'s included. */
	static java.util.Set<String> definedNames(List<LispVal> forms) {
		java.util.Set<String> names = new java.util.HashSet<>();
		java.util.Deque<LispVal> pending = new java.util.ArrayDeque<>(forms);
		while (!pending.isEmpty()) {
			if (pending.pop() instanceof LispCons cons && cons.car() instanceof LispSymbol head) {
				if (head.name().equals(LispNames.PROGN)) {
					for (LispVal item = cons.cdr(); item instanceof LispCons cell; item = cell.cdr()) {
						pending.push(cell.car());
					}
				}
				else if (DEFINERS.contains(head.name()) && cons.cdr() instanceof LispCons rest
						&& rest.car() instanceof LispSymbol name) {
					names.add(name.name());
				}
			}
		}
		return names;
	}

	/**
	 * The program a lowered call compiles to: its entries' forms in order, the last form
	 * of the entry that echoes storing the call's value ({@code %run-time-answer}).
	 */
	static List<LispVal> program(List<ClojureProgramLowering.Entry> entries) {
		List<LispVal> forms = new ArrayList<>();
		boolean answered = false;
		for (ClojureProgramLowering.Entry entry : entries) {
			List<LispVal> own = entry.forms();
			if (entry.echoes() && !own.isEmpty() && !answered) {
				forms.addAll(own.subList(0, own.size() - 1));
				forms.add(answering(own.getLast()));
				answered = true;
			}
			else {
				forms.addAll(own);
			}
		}
		if (!answered) {
			forms.add(list(new LispSymbol(LispNames.RUN_TIME_ANSWER_QUALIFIED), LispNil.INSTANCE));
		}
		return forms;
	}

	/**
	 * A top-level form storing its value as the call's: a definition stays at the top
	 * level and the name it answers is stored after it, a {@code progn}'s last form
	 * answers.
	 */
	private static LispVal answering(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head) {
			String name = head.name();
			if (name.equals(LispNames.PROGN)) {
				List<LispVal> items = cons.toList();
				if (items.size() > 1) {
					List<LispVal> out = new ArrayList<>(items.subList(0, items.size() - 1));
					out.add(answering(items.getLast()));
					return list(out.toArray(LispVal[]::new));
				}
			}
			if (DEFINERS.contains(name) && cons.cdr() instanceof LispCons rest) {
				return list(new LispSymbol(LispNames.PROGN), form,
						list(new LispSymbol(LispNames.RUN_TIME_ANSWER_QUALIFIED),
								list(new LispSymbol(LispNames.QUOTE), rest.car())));
			}
		}
		return list(new LispSymbol(LispNames.RUN_TIME_ANSWER_QUALIFIED), form);
	}

	private static final java.util.Set<String> DEFINERS = java.util.Set.of(LispNames.DEFUN, "DEFMACRO", "DEFVAR",
			"DEFPARAMETER", "DEFCONSTANT", "DEFSTRUCT", "DEFCLASS", "DEFINE-CONDITION", "DEFGENERIC", "DEFMETHOD");

	private static LispVal list(LispVal... items) {
		LispVal out = LispNil.INSTANCE;
		for (int i = items.length - 1; i >= 0; i--) {
			out = new LispCons(items[i], out);
		}
		return out;
	}

	/** A compiled value as the interpreter's: the plain data a transport carries. */
	static LispVal received(@Nullable Object value) {
		if (value == null) {
			return LispNil.INSTANCE;
		}
		if (value instanceof String s) {
			if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
				return new LispString(s.substring(1, s.length() - 1));
			}
			return s.equals("T") ? LispTrue.INSTANCE : new LispSymbol(s);
		}
		if (value instanceof Long l) {
			return new LispInteger(l);
		}
		if (value instanceof Integer i) {
			return new LispInteger(i);
		}
		if (value instanceof BigInteger b) {
			return b.bitLength() < 64 ? new LispInteger(b.longValue()) : new LispBigInteger(b);
		}
		if (value instanceof Double d) {
			return new LispDouble(d);
		}
		if (value instanceof BigInteger[] ratio && ratio.length == 2) {
			return new LispRatio(ratio[0], ratio[1]);
		}
		if (value instanceof int[] character && character.length == 1) {
			return new LispChar(character[0]);
		}
		if (value instanceof Object[] cell && cell.length == 2 && !(cell[0] instanceof Integer)
				&& !(cell[0] instanceof String[]) && !(cell[0] instanceof Boolean)) {
			List<LispVal> items = new ArrayList<>();
			Object run = value;
			while (run instanceof Object[] c && c.length == 2 && !(c[0] instanceof Integer)
					&& !(c[0] instanceof String[]) && !(c[0] instanceof Boolean)) {
				items.add(received(c[0]));
				run = c[1];
			}
			LispVal out = received(run);
			for (int i = items.size() - 1; i >= 0; i--) {
				out = new LispCons(items.get(i), out);
			}
			return out;
		}
		// the transport hands every other value over as a constant
		throw new IllegalStateException("a value the run-time lowering cannot read: " + value.getClass().getName());
	}

	/** The values a transport embeds: its {@code (count . reversed)} cell, in order. */
	private static Object[] constantsOf(Object cell) {
		if (!(cell instanceof Object[] box) || box.length != 2 || !(box[0] instanceof Long count)) {
			return new Object[0];
		}
		Object[] out = new Object[Math.toIntExact(count)];
		int i = out.length - 1;
		for (Object run = box[1]; run instanceof Object[] c && c.length == 2 && i >= 0; run = c[1]) {
			out[i--] = c[0];
		}
		return out;
	}

	/** A Lisp string's content. */
	private static String stringValue(Object value) {
		if (value instanceof String s && s.length() >= 2 && s.charAt(0) == '"') {
			return s.substring(1, s.length() - 1);
		}
		throw new IllegalArgumentException("not a Lisp string: " + value);
	}

	/** A Java string as a Lisp string. */
	private static String compiledString(String text) {
		return "\"" + text + "\"";
	}

	/**
	 * The name of the namespace object {@code (:C%NS-OBJECT "name")}, {@code user} else.
	 */
	private static String namespaceName(@Nullable Object namespace) {
		if (namespace instanceof Object[] cell && cell.length == 2 && ":C%NS-OBJECT".equals(cell[0])
				&& cell[1] instanceof Object[] rest && rest.length == 2 && rest[0] instanceof String name) {
			return stringValue(name);
		}
		return "user";
	}

	private static String packageOf(String internalName) {
		int slash = internalName.lastIndexOf('/');
		return slash < 0 ? "" : internalName.substring(0, slash);
	}

}
