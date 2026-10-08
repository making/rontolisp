package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The throwable classes a program names -- in a {@code catch}, a {@code thrown?} or a
 * construction -- as their class chains: the class's own name, then each superclass's up
 * to {@code java.lang.Throwable}. A chain is resolved at lowering time, so every backend
 * tests the same list and none needs a class hierarchy at run time; an exception carries
 * its chain, and a catch tests whether its class's name is in it ({@code clojure.lisp},
 * "Catching"). The classes a program names without an import, and the
 * {@code clojure.lang} throwables, come from a fixed table ({@link #PARENTS}); any other
 * class from host reflection.
 */
final class ClojureThrowables {

	/** The root of every chain: a catch of it takes every condition, untested. */
	static final String THROWABLE = "java.lang.Throwable";

	/**
	 * The superclass of each throwable a program names without an import -- the oracle's
	 * {@code java.lang} default imports ({@link ClojureNamespaceLowering#JAVA_LANG}) and
	 * the classes between them and {@code Throwable} -- of the public
	 * {@code clojure.lang} throwables, read off clj 1.12.6 on JDK 25 (2026-10-03), and of
	 * the three the HTTP client throws ({@code rontolisp.http-client}: a transport's
	 * {@code IOException}, a failed future's {@code ExecutionException} under
	 * {@code deref}, the {@code CompletionException} {@code :async-catch} is handed) and
	 * of the three {@code clojure.java.io} refuses a file, a charset and a URL with. A
	 * table, not reflection, so a host that reflects only what its image holds (a native
	 * image, the browser) resolves these alike; {@code ClojureThrowablesTest} pins the
	 * {@code java} rows to reflection. The {@code clojure.lang} rows are what no host
	 * here can reflect: {@code ExceptionInfo} is what {@code ex-info} builds,
	 * {@code ArityException} what a wrong argument count throws.
	 */
	static final Map<String, String> PARENTS = Map.ofEntries(
			Map.entry("java.lang.AbstractMethodError", "java.lang.IncompatibleClassChangeError"),
			Map.entry("java.lang.ArithmeticException", "java.lang.RuntimeException"),
			Map.entry("java.lang.ArrayIndexOutOfBoundsException", "java.lang.IndexOutOfBoundsException"),
			Map.entry("java.lang.ArrayStoreException", "java.lang.RuntimeException"),
			Map.entry("java.lang.AssertionError", "java.lang.Error"),
			Map.entry("java.lang.ClassCastException", "java.lang.RuntimeException"),
			Map.entry("java.lang.ClassCircularityError", "java.lang.LinkageError"),
			Map.entry("java.lang.ClassFormatError", "java.lang.LinkageError"),
			Map.entry("java.lang.ClassNotFoundException", "java.lang.ReflectiveOperationException"),
			Map.entry("java.lang.CloneNotSupportedException", "java.lang.Exception"),
			Map.entry("java.lang.EnumConstantNotPresentException", "java.lang.RuntimeException"),
			Map.entry("java.lang.Error", THROWABLE), Map.entry("java.lang.Exception", THROWABLE),
			Map.entry("java.lang.ExceptionInInitializerError", "java.lang.LinkageError"),
			Map.entry("java.lang.IllegalAccessError", "java.lang.IncompatibleClassChangeError"),
			Map.entry("java.lang.IllegalAccessException", "java.lang.ReflectiveOperationException"),
			Map.entry("java.lang.IllegalArgumentException", "java.lang.RuntimeException"),
			Map.entry("java.lang.IllegalMonitorStateException", "java.lang.RuntimeException"),
			Map.entry("java.lang.IllegalStateException", "java.lang.RuntimeException"),
			Map.entry("java.lang.IllegalThreadStateException", "java.lang.IllegalArgumentException"),
			Map.entry("java.lang.IncompatibleClassChangeError", "java.lang.LinkageError"),
			Map.entry("java.lang.IndexOutOfBoundsException", "java.lang.RuntimeException"),
			Map.entry("java.lang.InstantiationError", "java.lang.IncompatibleClassChangeError"),
			Map.entry("java.lang.InstantiationException", "java.lang.ReflectiveOperationException"),
			Map.entry("java.lang.InternalError", "java.lang.VirtualMachineError"),
			Map.entry("java.lang.InterruptedException", "java.lang.Exception"),
			Map.entry("java.lang.LinkageError", "java.lang.Error"),
			Map.entry("java.lang.NegativeArraySizeException", "java.lang.RuntimeException"),
			Map.entry("java.lang.NoClassDefFoundError", "java.lang.LinkageError"),
			Map.entry("java.lang.NoSuchFieldError", "java.lang.IncompatibleClassChangeError"),
			Map.entry("java.lang.NoSuchFieldException", "java.lang.ReflectiveOperationException"),
			Map.entry("java.lang.NoSuchMethodError", "java.lang.IncompatibleClassChangeError"),
			Map.entry("java.lang.NoSuchMethodException", "java.lang.ReflectiveOperationException"),
			Map.entry("java.lang.NullPointerException", "java.lang.RuntimeException"),
			Map.entry("java.lang.NumberFormatException", "java.lang.IllegalArgumentException"),
			Map.entry("java.lang.OutOfMemoryError", "java.lang.VirtualMachineError"),
			Map.entry("java.lang.ReflectiveOperationException", "java.lang.Exception"),
			Map.entry("java.lang.RuntimeException", "java.lang.Exception"),
			Map.entry("java.lang.SecurityException", "java.lang.RuntimeException"),
			Map.entry("java.lang.StackOverflowError", "java.lang.VirtualMachineError"),
			Map.entry("java.lang.StringIndexOutOfBoundsException", "java.lang.IndexOutOfBoundsException"),
			Map.entry("java.lang.ThreadDeath", "java.lang.Error"),
			Map.entry("java.lang.TypeNotPresentException", "java.lang.RuntimeException"),
			Map.entry("java.lang.UnknownError", "java.lang.VirtualMachineError"),
			Map.entry("java.lang.UnsatisfiedLinkError", "java.lang.LinkageError"),
			Map.entry("java.lang.UnsupportedClassVersionError", "java.lang.ClassFormatError"),
			Map.entry("java.lang.UnsupportedOperationException", "java.lang.RuntimeException"),
			Map.entry("java.lang.VerifyError", "java.lang.LinkageError"),
			Map.entry("java.lang.VirtualMachineError", "java.lang.Error"),
			Map.entry("java.io.IOException", "java.lang.Exception"),
			Map.entry("java.io.FileNotFoundException", "java.io.IOException"),
			Map.entry("java.io.UnsupportedEncodingException", "java.io.IOException"),
			Map.entry("java.net.MalformedURLException", "java.io.IOException"),
			Map.entry("java.util.concurrent.ExecutionException", "java.lang.Exception"),
			Map.entry("java.util.concurrent.CompletionException", "java.lang.RuntimeException"),
			Map.entry("clojure.lang.ExceptionInfo", "java.lang.RuntimeException"),
			Map.entry("clojure.lang.ArityException", "java.lang.IllegalArgumentException"),
			Map.entry("clojure.lang.Compiler$CompilerException", "java.lang.RuntimeException"),
			Map.entry("clojure.lang.LispReader$ReaderException", "java.lang.RuntimeException"),
			Map.entry("clojure.lang.EdnReader$ReaderException", "java.lang.RuntimeException"));

	private ClojureThrowables() {
	}

	/**
	 * The chain of the throwable class with this fully qualified name, or null when the
	 * name resolves to no class, or to one that is no {@code Throwable}.
	 * @param className the class's binary name
	 * @return its chain, own name first and {@code java.lang.Throwable} last, or null
	 */
	static @Nullable List<String> chainOf(String className) {
		if (className.equals(THROWABLE) || PARENTS.containsKey(className)) {
			List<String> chain = new ArrayList<>();
			for (String c = className; c != null; c = PARENTS.get(c)) {
				chain.add(c);
			}
			return chain;
		}
		Class<?> type;
		try {
			type = ClojureHostClasses.load(className);
		}
		catch (ClassNotFoundException | LinkageError _) {
			return null;
		}
		return Throwable.class.isAssignableFrom(type) ? chainOf(type) : null;
	}

	/**
	 * The chain of a throwable host class.
	 * @param type a {@code Throwable} class
	 * @return its chain, own name first and {@code java.lang.Throwable} last
	 */
	static List<String> chainOf(Class<?> type) {
		List<String> chain = new ArrayList<>();
		for (Class<?> c = type; c != null && Throwable.class.isAssignableFrom(c); c = c.getSuperclass()) {
			chain.add(c.getName());
		}
		return chain;
	}

	/**
	 * The chain of the class a {@code catch}, {@code thrown?} or {@code thrown-with-msg?}
	 * names: dotted as written, imported or a {@code java.lang} default import. A name
	 * resolving to no class is the oracle's {@code Unable to resolve classname}, one
	 * resolving to a class that is no {@code Throwable} its verifier's refusal.
	 * @param ctx the lowering
	 * @param datum the class spelling
	 * @param what the form naming it, for the refusal
	 * @return the class's chain
	 */
	static List<String> caughtChain(ClojureLowering ctx, LispVal datum, String what) {
		if (!(datum instanceof LispSymbol named) || named.name().startsWith(":")) {
			throw new LispReadException(what + " takes a class name, not " + datum.print());
		}
		String className = ClojureNamespaceLowering.resolveClass(ctx, named.name());
		List<String> chain = chainOf(className);
		if (chain != null) {
			ctx.recordChain(chain);
			return chain;
		}
		if (isClass(className)) {
			throw new LispReadException("Catch type is not a subclass of Throwable: " + className);
		}
		throw new LispReadException("Unable to resolve classname: " + named.name());
	}

	private static boolean isClass(String className) {
		try {
			ClojureHostClasses.load(className);
			return true;
		}
		catch (ClassNotFoundException | LinkageError _) {
			return false;
		}
	}

	/**
	 * Whether a catch of the class with this chain takes every condition, so it needs no
	 * test.
	 */
	static boolean catchesEverything(List<String> chain) {
		return chain.size() == 1 && chain.get(0).equals(THROWABLE);
	}

	/** The chain as the quoted list of names the run-time tests read. */
	static LispVal quoted(List<String> chain) {
		List<LispVal> names = new ArrayList<>(chain.size());
		for (String name : chain) {
			names.add(LispString.literal(name));
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("quote"), ClojureLowerUtil.list(names));
	}

	/**
	 * The {@code handler-case} clause type a catch of the class with this chain is:
	 * {@code error} for {@code Throwable}, else an {@code error} its class's predicate
	 * ({@link #predicateName}) takes, recorded so the program defines that predicate
	 * ({@link #catchRuntime}). A handler-case clause whose type rejects a condition never
	 * catches it, so a condition no catch takes passes on with nothing caught and
	 * signalled again.
	 * @param ctx the lowering
	 * @param chain the caught class's chain
	 * @return the clause type
	 */
	static LispVal clauseType(ClojureLowering ctx, List<String> chain) {
		if (catchesEverything(chain)) {
			return ClojureLowerUtil.sym("ERROR");
		}
		ctx.caughtChains.putIfAbsent(chain.get(0), chain);
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("AND"), ClojureLowerUtil.sym("ERROR"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("SATISFIES"), predicateName(chain.get(0))));
	}

	/**
	 * The predicate a catch of the class tests a condition with: one per class the
	 * program catches, so a {@code satisfies} clause type (one symbol) can name it.
	 */
	static LispSymbol predicateName(String className) {
		return new LispSymbol("C%E-CATCHES-" + className);
	}

	/**
	 * The definitions a catch by class needs: one predicate per caught class, each asking
	 * the library's {@code %clojure-catches} (whether the class takes the condition,
	 * {@code clojure.lisp} "Catching") over its chain, plus, when the program carries no
	 * exception runtime, the exception reader that runtime would define, answering NIL:
	 * no condition such a program catches is an exception.
	 * @param chains the caught classes' chains
	 * @param reader whether to define the reader
	 * @return the top-level definitions
	 */
	static List<LispVal> catchRuntime(Collection<List<String>> chains, boolean reader) {
		List<LispVal> runtime = new ArrayList<>();
		LispSymbol condition = new LispSymbol("c");
		for (List<String> chain : chains) {
			runtime.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), predicateName(chain.get(0)),
					ClojureLowerUtil.list(List.of(condition)),
					ClojureLowerUtil.list(new LispSymbol(CATCHES), condition, quoted(chain))));
		}
		if (reader) {
			runtime.add(ClojureLowerUtil.list(ClojureLowerUtil.sym("defun"), new LispSymbol("C%E-PARTS"),
					ClojureLowerUtil.list(List.of(condition)),
					ClojureLowerUtil.list(ClojureLowerUtil.sym("declare"),
							ClojureLowerUtil.list(ClojureLowerUtil.sym("ignore"), condition)),
					ClojureLowering.NIL_CONST));
		}
		return runtime;
	}

	/** The library test of whether a catch's class takes a condition. */
	private static final String CATCHES = "RONTOLISP::%CLOJURE-CATCHES";

}
