package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The bases of the classes a class keyword may name -- the oracle's {@code bases}: the
 * superclass first, then the direct interfaces -- so {@code isa?}, {@code parents} and
 * {@code ancestors} follow Java inheritance the way the oracle does. The throwable half
 * extends {@link ClojureThrowables#PARENTS} with the interfaces, the stream half is
 * {@link #STREAM_SUPERS}, the half of the instants and the UUID
 * {@link #TIME_VALUE_SUPERS}; all are tables read off clj 1.12.6 on JDK 25 (2026-10-04,
 * the time half 2026-10-08), not reflection, so a host that reflects only what its image
 * holds resolves them alike. Any other throwable reflects, like its chain.
 */
final class ClojureClassBases {

	/** The root of every class's supers but an interface's. */
	static final String OBJECT = "java.lang.Object";

	/**
	 * The superclass of each stream class {@code class} answers ({@code clojure.lisp},
	 * {@code %clojure-stream-class}) and of theirs below {@code Writer} and
	 * {@code Reader}: the stream half of the class chains, as
	 * {@link ClojureThrowables#PARENTS} is the throwable half.
	 */
	static final Map<String, String> STREAM_SUPERS = Map.of("java.io.StringWriter", "java.io.Writer",
			"java.io.PrintWriter", "java.io.Writer", "java.io.OutputStreamWriter", "java.io.Writer",
			"java.io.BufferedWriter", "java.io.Writer", "java.io.BufferedReader", "java.io.Reader",
			"clojure.lang.LineNumberingPushbackReader", "java.io.PushbackReader", "java.io.PushbackReader",
			"java.io.FilterReader", "java.io.FilterReader", "java.io.Reader");

	/** The stream classes below {@code Object} that no other stream class extends. */
	private static final Set<String> STREAM_ROOTS = Set.of("java.io.Writer", "java.io.Reader");

	/**
	 * The superclass of each class a {@code clojure.java.io} value's {@code class}
	 * answers that is no character stream ({@code ClojureIoLowering.CLASSES}), and of
	 * theirs below {@code Object}: a dispatch value or a hierarchy argument spelling one
	 * lowers to the keyword {@code class} answers.
	 */
	static final Map<String, String> IO_SUPERS = Map.ofEntries(Map.entry("java.io.File", OBJECT),
			Map.entry("java.net.URL", OBJECT), Map.entry("java.net.URI", OBJECT),
			Map.entry("java.io.BufferedInputStream", "java.io.FilterInputStream"),
			Map.entry("java.io.FilterInputStream", "java.io.InputStream"), Map.entry("java.io.InputStream", OBJECT),
			Map.entry("java.io.BufferedOutputStream", "java.io.FilterOutputStream"),
			Map.entry("java.io.FilterOutputStream", "java.io.OutputStream"), Map.entry("java.io.OutputStream", OBJECT),
			Map.entry("java.io.ByteArrayInputStream", "java.io.InputStream"),
			Map.entry("java.io.ByteArrayOutputStream", "java.io.OutputStream"));

	/**
	 * The superclass of each class an instant {@code class} answers that has one below
	 * {@code Object}: the time half of the class chains.
	 */
	static final Map<String, String> TIME_VALUE_SUPERS = Map.of("java.sql.Timestamp", "java.util.Date",
			"java.util.GregorianCalendar", "java.util.Calendar");

	/** The classes of the instants and the UUID whose superclass is {@code Object}. */
	private static final Set<String> TIME_VALUE_ROOTS = Set.of("java.util.Date", "java.util.Calendar",
			"java.util.UUID");

	/**
	 * The class keyword an {@code extend} of a class of the instants and the UUID
	 * dispatches on: the class's own, but for {@code Calendar}, whose one class here is
	 * {@code GregorianCalendar} (a protocol's rows are looked up by the exact class).
	 */
	static final Map<String, String> TIME_VALUE_DISPATCH = Map.of("java.util.Date", "java.util.Date",
			"java.sql.Timestamp", "java.sql.Timestamp", "java.util.Calendar", "java.util.GregorianCalendar",
			"java.util.GregorianCalendar", "java.util.GregorianCalendar", "java.util.UUID", "java.util.UUID");

	/**
	 * The direct interfaces of the tabled classes that have any, and the bases of the
	 * interfaces among their supers (an interface's bases are its superinterfaces).
	 */
	static final Map<String, List<String>> INTERFACES = Map.ofEntries(
			Map.entry("java.lang.Throwable", List.of("java.io.Serializable")),
			Map.entry("clojure.lang.ExceptionInfo", List.of("clojure.lang.IExceptionInfo")),
			Map.entry("clojure.lang.Compiler$CompilerException", List.of("clojure.lang.IExceptionInfo")),
			Map.entry("clojure.lang.LispReader$ReaderException", List.of("clojure.lang.IExceptionInfo")),
			Map.entry("java.io.Writer", List.of("java.lang.Appendable", "java.io.Closeable", "java.io.Flushable")),
			Map.entry("java.io.Reader", List.of("java.lang.Readable", "java.io.Closeable")),
			Map.entry("java.io.Closeable", List.of("java.lang.AutoCloseable")),
			Map.entry("java.io.File", List.of("java.io.Serializable", "java.lang.Comparable")),
			Map.entry("java.net.URL", List.of("java.io.Serializable")),
			Map.entry("java.net.URI", List.of("java.lang.Comparable", "java.io.Serializable")),
			Map.entry("java.io.InputStream", List.of("java.io.Closeable")),
			Map.entry("java.io.OutputStream", List.of("java.io.Closeable", "java.io.Flushable")),
			Map.entry("java.util.Date", List.of("java.io.Serializable", "java.lang.Cloneable", "java.lang.Comparable")),
			Map.entry("java.util.Calendar",
					List.of("java.io.Serializable", "java.lang.Cloneable", "java.lang.Comparable")),
			Map.entry("java.util.UUID", List.of("java.io.Serializable", "java.lang.Comparable")));

	/** The interfaces among the tabled classes' supers. */
	static final Set<String> TABLED_INTERFACES = Set.of("java.io.Serializable", "clojure.lang.IExceptionInfo",
			"java.lang.Appendable", "java.io.Closeable", "java.io.Flushable", "java.lang.Readable",
			"java.lang.AutoCloseable", "java.lang.Cloneable", "java.lang.Comparable");

	/**
	 * The classes a runtime error's or an {@code ex-info}'s class may be
	 * ({@code clojure.lisp}, {@code %clojure-error-chain} and
	 * {@code %clojure-condition-chain}), which a value has without the program naming
	 * them.
	 */
	static final List<String> RUNTIME_THROWABLES = List.of("java.lang.ArithmeticException",
			"java.lang.ClassCastException", "java.lang.NullPointerException", "java.lang.IndexOutOfBoundsException",
			"java.lang.UnsupportedOperationException", "clojure.lang.ArityException", "java.io.FileNotFoundException",
			"clojure.lang.ExceptionInfo");

	/**
	 * The keywords {@code class} answers for a core kind (the
	 * {@code ClojureDispatchLowering
	 * classForm} arms but nil's, exceptions' and streams'): each stands for host classes
	 * that are no one value here -- {@code :number} is {@code Long}, {@code Double},
	 * {@code Ratio}... -- so its row names no bases and its supers are {@code Object}
	 * alone. A record's or a deftype's tag is such a class too.
	 */
	static final List<String> KINDS = List.of("boolean", "keyword", "symbol", "char", "string", "number", "set", "map",
			"vector", "pattern", "matcher", "list", "function", "atom", "reify", "clojure.lang.Namespace");

	private ClojureClassBases() {
	}

	/**
	 * The bases of the class with this name, or null when it is none a class keyword
	 * names here: a tabled class, an interface among their supers, {@code Object}, or a
	 * throwable host reflection resolves.
	 * @param name the class's binary name
	 * @return its superclass then its interfaces, empty for {@code Object} and a root
	 * interface
	 */
	static @Nullable List<String> basesOf(String name) {
		if (name.equals(OBJECT)) {
			return List.of();
		}
		if (TABLED_INTERFACES.contains(name)) {
			return INTERFACES.getOrDefault(name, List.of());
		}
		String superclass = ClojureThrowables.PARENTS.get(name);
		if (superclass == null) {
			superclass = STREAM_SUPERS.get(name);
		}
		if (superclass == null) {
			superclass = IO_SUPERS.get(name);
		}
		if (superclass == null) {
			superclass = TIME_VALUE_SUPERS.get(name);
		}
		if (superclass == null && (name.equals(ClojureThrowables.THROWABLE) || STREAM_ROOTS.contains(name)
				|| TIME_VALUE_ROOTS.contains(name))) {
			superclass = OBJECT;
		}
		if (superclass != null) {
			List<String> bases = new ArrayList<>();
			bases.add(superclass);
			bases.addAll(INTERFACES.getOrDefault(name, List.of()));
			return bases;
		}
		return ClojureThrowables.chainOf(name) != null ? reflectedBases(name) : null;
	}

	/** The bases host reflection answers, or null when the class does not resolve. */
	private static @Nullable List<String> reflectedBases(String name) {
		Class<?> type;
		try {
			type = ClojureHostClasses.load(name);
		}
		catch (ClassNotFoundException | LinkageError _) {
			return null;
		}
		List<String> bases = new ArrayList<>();
		if (type.getSuperclass() != null) {
			bases.add(type.getSuperclass().getName());
		}
		for (Class<?> each : type.getInterfaces()) {
			bases.add(each.getName());
		}
		return bases;
	}

	/**
	 * The bases of a class among the supers of one {@link #basesOf} answers: the tables
	 * first, then reflection for an interface a reflected throwable implements.
	 */
	static @Nullable List<String> superBasesOf(String name) {
		List<String> bases = basesOf(name);
		return bases != null ? bases : reflectedBases(name);
	}

	/**
	 * Whether a spelling of this class lowers to its class keyword: a throwable, a stream
	 * class, a class of the instants or the UUID, an interface among their supers, or
	 * {@code Object}.
	 */
	static boolean isChained(String name) {
		return basesOf(name) != null;
	}

	/**
	 * The classes of the instants below the class: what a spelling of
	 * {@code java.util.Date} records beside its own row, so the class keyword of a
	 * Timestamp walks to it, while a spelling of {@code Object} or an interface, which
	 * every class reaches, records none of them.
	 * @param name the spelled class
	 * @return the subclasses, sorted
	 */
	static List<String> timeValueSubclassesOf(String name) {
		List<String> subclasses = new ArrayList<>();
		for (Map.Entry<String, String> entry : TIME_VALUE_SUPERS.entrySet()) {
			if (entry.getValue().equals(name)) {
				subclasses.add(entry.getKey());
			}
		}
		subclasses.sort(null);
		return subclasses;
	}

	/**
	 * Whether the class is a stream class {@code class} may answer or a superclass of
	 * one.
	 */
	static boolean isStreamClass(String name) {
		return STREAM_SUPERS.containsKey(name) || STREAM_SUPERS.containsValue(name);
	}

	/**
	 * The class's supers (the oracle's {@code supers}): every base, and every base's
	 * supers.
	 * @param name a class {@link #basesOf} resolves
	 * @return its supers, nearest first
	 */
	static Set<String> supersOf(String name) {
		Set<String> supers = new LinkedHashSet<>();
		List<String> bases = superBasesOf(name);
		if (bases != null) {
			for (String base : bases) {
				if (supers.add(base)) {
					supers.addAll(supersOf(base));
				}
			}
		}
		return supers;
	}

	/**
	 * The classes a value's {@code class} may answer without the program naming them: the
	 * runtime errors', the streams' and the other {@code clojure.java.io} values'.
	 */
	static List<String> implicitClasses() {
		List<String> classes = new ArrayList<>(RUNTIME_THROWABLES);
		classes.addAll(STREAM_SUPERS.keySet().stream().sorted().toList());
		classes.addAll(List.of("java.io.BufferedInputStream", "java.io.BufferedOutputStream", "java.io.File",
				"java.net.URI", "java.net.URL"));
		return classes;
	}

}
