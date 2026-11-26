package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The run-time half of the EXPERIMENTAL front end for a subset of the Java language
 * ({@code java.lisp} on the classpath): the condition a thrown object travels in, the
 * text of a number and of a value, {@code Object}'s methods over the values no class of
 * the program answers for, and the collection helpers. It is Common Lisp source like
 * every other shipped library, so no backend learns a Java name
 * ({@code .kb/java-frontend.md}).
 *
 * <p>
 * Consumers, the {@link StarlarkLibrary} shape:
 * <ul>
 * <li>the interpreter evaluates {@link #forms()} into the global environment the first
 * time one of the {@code rontolisp::%java-} functions is resolved
 * ({@code LispEvaluator#resolveFunction});</li>
 * <li>the compile path calls {@link #process(List, Features)} inside
 * {@code CompileFrontend.expand}: a program that references one of the functions -- only
 * a lowered Java program does -- gets the definitions prepended, and
 * {@link LibraryDefunPruner} drops the ones it does not reach.</li>
 * </ul>
 */
public final class JavaLibrary {

	private static final String NAME_PREFIX = "%JAVA-";

	/**
	 * The targets the library has a variant for: the thread functions and a thread's wait
	 * for a socket differ, under the same names.
	 */
	private enum Variant {

		/** A target with threads: the interpreter and the JVM. */
		HOST(Features.INTERPRETER),

		/** One stack, nothing of the host's to wait for but the time: Preview 1. */
		ONE_STACK(Features.WASM),

		/**
		 * One stack under the {@code --native} runner, whose sockets a thread waits for
		 * among the others: Preview 1's otherwise.
		 */
		NATIVE(Features.WASM.with(List.of(Features.NATIVE))),

		/** One stack under a host with timers, replies and tasks of its own. */
		COMPONENT(Features.WASM.with(List.of(Features.COMPONENT))),

		/**
		 * One stack a host calls and nothing of the host's to wait for: a
		 * {@code --no-wasi} module, whose clock moves between the host's calls alone, so
		 * a wait for a time no thread can end is refused by name.
		 */
		REACTOR(Features.WASM_REACTOR),

		/**
		 * One stack a host calls and waits for ({@code --host-timer}): a wait for a time
		 * is the host's timer, and a request's call with nothing to do idles in the host,
		 * which calls the program for other requests meanwhile.
		 */
		HOST_TIMER(Features.WASM_REACTOR.with(List.of(Features.HOST_TIMER))),

		/**
		 * A reactor whose sockets are its host's ({@code --host-sockets}): a thread waits
		 * for a socket among the others, as under the {@code --native} runner, and where
		 * none can go on the wait is the host's.
		 */
		REACTOR_SOCKETS(Features.WASM_REACTOR.with(List.of(Features.HOST_SOCKETS))),

		/**
		 * A reactor of both: where no thread can go on and one waits for a socket, the
		 * call waits in the host's sockets, for the soonest time a thread waits for at
		 * most, and idles otherwise.
		 */
		HOST_TIMER_SOCKETS(Features.WASM_REACTOR.with(List.of(Features.HOST_TIMER, Features.HOST_SOCKETS)));

		private final Features features;

		@Nullable private volatile List<LispVal> forms;

		Variant(Features features) {
			this.features = features;
		}

		static Variant of(Features target) {
			if (!target.contains("rontolisp-wasm")) {
				return HOST;
			}
			if (target.contains(Features.HOST_TIMER)) {
				return target.contains(Features.HOST_SOCKETS) ? HOST_TIMER_SOCKETS : HOST_TIMER;
			}
			if (target.contains("rontolisp-reactor")) {
				return target.contains(Features.HOST_SOCKETS) ? REACTOR_SOCKETS : REACTOR;
			}
			if (target.contains(Features.COMPONENT)) {
				return COMPONENT;
			}
			return target.contains(Features.NATIVE) ? NATIVE : ONE_STACK;
		}

		List<LispVal> forms() {
			List<LispVal> cached = this.forms;
			if (cached == null) {
				synchronized (this) {
					cached = this.forms;
					if (cached == null) {
						cached = List.copyOf(LispReader.readAllFromString(readSource(), this.features));
						this.forms = cached;
					}
				}
			}
			return cached;
		}

	}

	@Nullable private static volatile Set<String> functionNames;

	private JavaLibrary() {
	}

	/**
	 * Returns the parsed library definitions for a target that has threads: the
	 * interpreter and the JVM. The source is written in canonical shape (internal
	 * double-colon {@code rontolisp::} helpers, bare {@code cl} names), so it needs no
	 * package resolution. Parsed once and cached.
	 * @return the library forms
	 * @see #forms(Features)
	 */
	public static List<LispVal> forms() {
		return Variant.HOST.forms();
	}

	/**
	 * Returns the parsed library definitions for a target. The variants differ in the
	 * thread functions alone, under the same names: a thread is the host's where the
	 * target has threads; where it has one stack a thread is a body suspended and
	 * resumed, and what gives the threads their turns is the program's own loop, or under
	 * a component the host's events ({@code .kb/java-frontend.md}, "Threads"); a
	 * {@code --native} module's loop also waits in its runner for the sockets its threads
	 * wait for ("java.net.Socket and ServerSocket").
	 * @param target the features of the target the program is compiled for
	 * @return the library forms
	 */
	public static List<LispVal> forms(Features target) {
		return Variant.of(target).forms();
	}

	/**
	 * The definitions of every variant: every name the library can define.
	 * @return the forms of the variants, one after the other
	 */
	public static List<LispVal> everyVariantForms() {
		List<LispVal> all = new ArrayList<>();
		for (Variant variant : Variant.values()) {
			all.addAll(variant.forms());
		}
		return all;
	}

	private static String readSource() {
		try (InputStream in = JavaLibrary.class.getResourceAsStream("java.lisp")) {
			if (in == null) {
				throw new IllegalStateException("java.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Returns whether the name is one of the functions {@code java.lisp} defines.
	 * @param symbolName the symbol name, in its canonical spelling
	 * @return {@code true} when the library defines the function
	 */
	public static boolean isJavaFunction(String symbolName) {
		return mayDefine(symbolName) && functionNames().contains(symbolName);
	}

	/**
	 * Whether the name can be one the library defines, answered from its spelling alone:
	 * every definition is a {@code rontolisp} {@code %java-} name. The interpreter asks
	 * {@link #isJavaFunction} for every function name it resolves for the first time and
	 * the compile path for every symbol of a program, so any other name must not read the
	 * library ({@code .kb/source-language.md}, "The run-time libraries' name gate").
	 * @param symbolName the symbol name, in its canonical spelling
	 * @return {@code false} when the library cannot define the name
	 */
	static boolean mayDefine(String symbolName) {
		return symbolName.contains(NAME_PREFIX) && LispSymbol.memberName(symbolName).startsWith(NAME_PREFIX);
	}

	private static Set<String> functionNames() {
		Set<String> names = functionNames;
		if (names == null) {
			names = new HashSet<>();
			for (LispVal form : everyVariantForms()) {
				if (form instanceof LispCons cons && cons.cdr() instanceof LispCons rest
						&& rest.car() instanceof LispSymbol name) {
					names.add(name.name());
				}
			}
			for (String name : names) {
				if (!mayDefine(name)) {
					throw new IllegalStateException("java.lisp defines " + name + ", which mayDefine refuses");
				}
			}
			functionNames = names;
		}
		return names;
	}

	/**
	 * The compile-path pre-pass: prepends the library definitions when the program
	 * references one of its functions. A program that does not is returned unchanged.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @param target the features of the target the program is compiled for
	 * @return the program with the library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program, Features target) {
		for (LispVal form : program) {
			if (references(form)) {
				List<LispVal> out = new ArrayList<>(forms(target));
				out.addAll(program);
				return out;
			}
		}
		return program;
	}

	/**
	 * Whether the program is one the library runs: it references one of the library's
	 * functions, as only a lowered Java program does. Such a program ends through the
	 * host's exit -- what its {@code main} left uncaught, {@code System.exit}, a wait
	 * refused by name -- so a reactor, which has no process, gives it the exit that ends
	 * the instance ({@link ExitLibrary}); and its code is written for Common Lisp's nil,
	 * which the scalar backend does not tell from 0 (the compile path's
	 * {@code CompileFrontend.Result#lowered}).
	 * @param program the top-level forms
	 * @return {@code true} when the program holds a call of the library
	 */
	public static boolean runs(List<LispVal> program) {
		for (LispVal form : program) {
			if (references(form)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The functions a lowered program calls whose wasm definitions reach {@code sleep}: a
	 * sleep and a wait, of a program that starts no thread and of one that does.
	 */
	private static final Set<String> SLEEPING = Set.of("RONTOLISP::%JAVA-THREAD-SLEEP", "RONTOLISP::%JAVA-MONITOR-WAIT",
			"RONTOLISP::%JAVA-SUSPENDING-SLEEP", "RONTOLISP::%JAVA-SUSPENDING-WAIT");

	/**
	 * Whether a lowered program can sleep through the library: a thread's sleep and a
	 * timed wait pass the time by {@code sleep} where the target has one stack. The
	 * library is spliced after the pass that binds {@code sleep} for a component
	 * ({@link WaitForLibrary}), so that pass asks here.
	 * @param program the top-level forms, before the library is spliced
	 * @return {@code true} when the program names one of those functions
	 */
	static boolean sleeps(List<LispVal> program) {
		for (LispVal form : program) {
			if (namesSleeping(form)) {
				return true;
			}
		}
		return false;
	}

	private static boolean namesSleeping(LispVal form) {
		return names(form, SLEEPING);
	}

	/** What {@code Thread.start()} is lowered over, on every target. */
	private static final Set<String> STARTING = Set.of("RONTOLISP::%JAVA-THREAD-START");

	/**
	 * Whether a lowered program can start a thread: its forms hold {@code Thread.start()}
	 * only when a call reaches it.
	 * @param program the top-level forms
	 * @return {@code true} when the program names the function a thread starts through
	 */
	static boolean startsThreads(List<LispVal> program) {
		for (LispVal form : program) {
			if (names(form, STARTING)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The prefix of what the library's file classes reach the host's file system through.
	 */
	private static final String FILES = "RONTOLISP::%JAVA-FILE-";

	/**
	 * Whether a lowered program can reach the host's file system: its forms call the
	 * run-time functions of {@code rontolisp.internal.Disk} only when a call of a file
	 * class reaches them, so a program computing paths alone does not.
	 * @param program the top-level forms
	 * @return {@code true} when the program names a function a file is reached through
	 */
	static boolean reachesFiles(List<LispVal> program) {
		for (LispVal form : program) {
			if (startsWith(form, FILES)) {
				return true;
			}
		}
		return false;
	}

	private static boolean startsWith(LispVal form, String prefix) {
		LispVal rest = form;
		while (rest instanceof LispCons cons) {
			if (startsWith(cons.car(), prefix)) {
				return true;
			}
			rest = cons.cdr();
		}
		return rest instanceof LispSymbol symbol && symbol.name().startsWith(prefix);
	}

	private static boolean names(LispVal form, Set<String> names) {
		LispVal rest = form;
		while (rest instanceof LispCons cons) {
			if (names(cons.car(), names)) {
				return true;
			}
			rest = cons.cdr();
		}
		return rest instanceof LispSymbol symbol && names.contains(symbol.name());
	}

	private static boolean references(LispVal form) {
		LispVal rest = form;
		while (rest instanceof LispCons cons) {
			if (references(cons.car())) {
				return true;
			}
			rest = cons.cdr();
		}
		return rest instanceof LispSymbol symbol && isJavaFunction(symbol.name());
	}

}
