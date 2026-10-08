package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.clojure.ClojureMain;
import org.jspecify.annotations.Nullable;

/**
 * The command line's {@code deps.edn} entry points, the oracle's {@code clj -M},
 * {@code clj -X} and a project's test run, over the working directory's project and the
 * aliases the standards name ({@code clojure/ClojureMain} decides; this adapts the loader
 * and keeps {@code cli} from naming a {@code clojure} type, like
 * {@link SourceStandards}).
 */
public final class ClojureCommandLine {

	/** What the command line runs. */
	public sealed interface Entry permits Program, Script, Repl {

	}

	/**
	 * A generated Clojure program, read without a file and run or compiled like any other
	 * program.
	 *
	 * @param source its text
	 */
	public record Program(String source) implements Entry {
	}

	/**
	 * A source file run like any entry file.
	 *
	 * @param file its path
	 * @param arguments the program's arguments
	 */
	public record Script(String file, List<String> arguments) implements Entry {
	}

	/**
	 * The REPL.
	 *
	 * @param arguments the program's arguments
	 */
	public record Repl(List<String> arguments) implements Entry {
	}

	/**
	 * What runs, and the oracle's warnings about the alias selection.
	 *
	 * @param entry what runs
	 * @param warnings one line each, for standard error
	 */
	public record Resolved(Entry entry, List<String> warnings) {
	}

	private ClojureCommandLine() {
	}

	/**
	 * {@code clj -M:aliases args}: the aliases' {@code :main-opts}, then the arguments,
	 * as {@code clojure.main} reads them.
	 * @param args the arguments after {@code -M}
	 * @param replAliases whether {@code -A} selected aliases (the oracle warns of
	 * {@code :main-opts} then)
	 * @param standards the selected aliases and the user-level configuration
	 * @param loader where the project's files are read
	 * @return what runs
	 */
	public static Resolved main(List<String> args, boolean replAliases, SourceStandards standards,
			SourceLoader loader) {
		return adapt(ClojureMain.main(SourceLanguage.clojureFiles(loader, standards), args, replAliases));
	}

	/**
	 * {@code clj -X:aliases [fn] [k v]... [map]}: one function called with one map.
	 * @param args the arguments after {@code -X}
	 * @param replAliases whether {@code -A} selected aliases too
	 * @param standards the selected aliases and the user-level configuration
	 * @param loader where the project's files are read
	 * @return what runs
	 */
	public static Resolved exec(List<String> args, boolean replAliases, SourceStandards standards,
			SourceLoader loader) {
		return adapt(ClojureMain.exec(SourceLanguage.clojureFiles(loader, standards), args, replAliases));
	}

	/**
	 * A Clojure project's tests: the test namespaces run by {@code clojure.test}, the
	 * verdict the exit code.
	 * @param file the test file, or {@code null} for the project's test namespaces
	 * @param standards the selected aliases and the user-level configuration
	 * @param loader where the project's files are read
	 * @return what runs
	 */
	public static Resolved test(@Nullable String file, SourceStandards standards, SourceLoader loader) {
		return adapt(ClojureMain.test(SourceLanguage.clojureFiles(loader, standards), file));
	}

	private static Resolved adapt(ClojureMain.Resolved resolved) {
		Entry entry = switch (resolved.entry()) {
			case ClojureMain.Program program -> new Program(program.source());
			case ClojureMain.Script script -> new Script(script.file(), script.arguments());
			case ClojureMain.Repl repl -> new Repl(repl.arguments());
		};
		return new Resolved(entry, resolved.warnings());
	}

}
