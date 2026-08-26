package am.ik.rontolisp.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import am.ik.rontolisp.eval.AsdfSystems;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.reader.Features;
import org.jspecify.annotations.Nullable;

/**
 * The {@code rontolisp test} subcommand: runs a rove test target and turns its verdict
 * into the process EXIT CODE -- 0 when every test passed, 1 when one did not. A plain
 * {@code rontolisp FILE} cannot do that and must not: a top-level form is a statement, so
 * the value the file's last {@code rove:run-suite} answers is dropped, exactly as
 * {@code sbcl --script} drops it.
 * <p>
 * The command GENERATES a program -- the rove prologue, the target, the verdict epilogue
 * -- and hands it to the ordinary CLI pipeline, so every mode the pipeline has applies
 * unchanged: with no {@code -o} it is interpreted, with {@code -o out.class} /
 * {@code -o out.wasm} (+ {@code --component}) the same program is COMPILED and the
 * artifact carries the same exit contract, {@code uiop:quit} being real on all four
 * backends.
 * <p>
 * Upstream's {@code roswell/rove.ros} is the specification this mirrors: quickload rove
 * up front, derive a {@code .lisp} target's system name from its {@code defpackage},
 * {@code asdf:load-system} + {@code asdf:test-system} it, fall back to {@code rove:run}
 * when nothing recorded a report, and read the verdict from
 * {@code rove/core/suite:*last-suite-report*}. The deliberate divergences are recorded in
 * {@code .kb/asdf.md}: exit 1 rather than {@code -1} (which the 8-bit mask turns into
 * 255), no {@code COVERAGE} arm (sb-cover is a non-goal), the target's own output is NOT
 * swallowed into a broadcast stream, and -- the one that is not cosmetic -- an
 * {@code invoke-reporter :after} method makes {@code *last-suite-report*} carry the
 * verdict of EVERY entry point. rove sets it in {@code call-with-suite} only, which
 * {@code rove:run-suite} (the README FAQ shape a single test file ends with) never
 * reaches, so without the hook a file that runs its own suite leaves no trace to read and
 * the runner would have to run its tests a second time.
 */
final class TestCommand {

	/**
	 * The reporter styles the command offers -- rove's own three, the set
	 * {@code rove.ros} documents. {@code spec} and {@code dot} are registered before any
	 * program asks for one; {@code none} lives in a system of its own.
	 */
	private static final List<String> REPORTERS = List.of("spec", "dot", "none");

	/** The system name the runner loads rove under. */
	private static final String ROVE = "rove";

	private TestCommand() {
	}

	/**
	 * The generated program and the directory its relative paths resolve against (the
	 * target's own directory, so a sibling {@code .asd} and a relative {@code load}
	 * inside the target both resolve).
	 *
	 * @param source the program text
	 * @param baseDir the directory relative paths resolve against, or {@code null}
	 */
	record Program(String source, @Nullable String baseDir) {
	}

	/**
	 * Builds the program for the given command line.
	 * @param options the options after the {@code test} subcommand name
	 * @param systemPath the {@code .asd} search path (--system-path +
	 * RONTOLISP_SOURCE_REGISTRY)
	 * @return the program, or {@code null} when the command line was wrong -- the message
	 * is already on standard error and the caller exits 2
	 */
	@Nullable static Program build(CliOptions options, List<String> systemPath) {
		String target = options.getNokey();
		if (target == null) {
			return fail("no test target given (try: rontolisp test --help)");
		}
		if (options.contains("-e")) {
			return fail("-e/--eval names a program, not a test target, so it cannot be combined with 'test'");
		}
		String reporter = reporter(options);
		if (reporter == null) {
			return null;
		}
		boolean colors = colors(options);
		Path path = Path.of(target);
		boolean asd = target.endsWith(".asd");
		if (Files.isRegularFile(path)) {
			String baseDir = SourceLoader.parentDir(path.toAbsolutePath().toString());
			return asd ? system(stripExtension(path), baseDir, systemPath, reporter, colors)
					: file(path, baseDir, systemPath, reporter, colors);
		}
		if (asd || target.endsWith(".lisp")) {
			return fail(target + ": no such file");
		}
		if (Files.isDirectory(path)) {
			return fail(target + " is a directory: give a test file, a .asd file or a system name");
		}
		// Anything else is an ASDF system designator -- asdf:load-system reports an
		// unknown one, naming the search path it looked on.
		return system(target, null, systemPath, reporter, colors);
	}

	/**
	 * A {@code .lisp} target. Its {@code defpackage} names the system it belongs to when
	 * it belongs to one (real ASDF's package-inferred rule, and how {@code rove.ros}
	 * resolves a file); when no such system is on the search path the file is simply
	 * LOADED, which is what makes the single-file shape -- rove's own README FAQ example,
	 * declaring a package no {@code .asd} mentions -- work unchanged.
	 */
	@Nullable private static Program file(Path path, @Nullable String baseDir, List<String> systemPath, String reporter,
			boolean colors) {
		String source;
		try {
			source = Files.readString(path);
		}
		catch (IOException ex) {
			return fail(path + ": " + ex.getMessage());
		}
		String declared = AsdfSystems.fileDefpackageName(source, path.toString(), Features.INTERPRETER);
		if (declared != null && locatable(declared, baseDir, systemPath)) {
			return system(declared, baseDir, systemPath, reporter, colors);
		}
		StringBuilder out = prologue(reporter, colors, baseDir, systemPath);
		out.append("(load ").append(string(path.toAbsolutePath().toString())).append(")\n");
		out.append("(in-package #:cl-user)\n");
		// A file that only DEFINES tests leaves nothing to read: run the suite of the
		// package it declares (CL-USER when it declares none -- deftest registers under
		// *package*, which a load does not change). A file that ran its own suite has a
		// report already and this is skipped, so its tests run exactly once.
		String pkg = declared == null ? "cl-user" : declared;
		out.append(";; Nothing ran the tests the file defines: run the suite of its own package.\n");
		out.append("(let ((suite (let ((package (or (find-package ")
			.append(string(pkg.toUpperCase(Locale.ROOT)))
			.append(") (find-package ")
			.append(string(pkg))
			.append("))))\n");
		out.append("               (and package (rove:find-suite package)))))\n");
		out.append("  (when (and suite (null rove/core/suite:*last-suite-report*))\n");
		out.append("    (rove:run-suite suite)))\n");
		return new Program(epilogue(out, path.toString()), baseDir);
	}

	/**
	 * An ASDF system target ({@code my-app/tests}, or the system a {@code .asd} / a
	 * {@code .lisp} file names). {@code asdf:test-system} runs the system's
	 * {@code :perform (test-op ...)} -- the entry point a {@code .asd} declares -- and
	 * {@code rove:run} is the fallback for a system that declares none, exactly as in
	 * {@code rove.ros}.
	 */
	private static Program system(String name, @Nullable String baseDir, List<String> systemPath, String reporter,
			boolean colors) {
		StringBuilder out = prologue(reporter, colors, baseDir, systemPath);
		out.append("(asdf:load-system ").append(string(name)).append(")\n");
		out.append("(asdf:test-system ").append(string(name)).append(")\n");
		out.append("(in-package #:cl-user)\n");
		out.append(";; The system declares no :perform (test-op ...): run its suites directly.\n");
		out.append("(unless rove/core/suite:*last-suite-report*\n");
		out.append("  (rove:run ").append(string(name)).append("))\n");
		return new Program(epilogue(out, name), baseDir);
	}

	/**
	 * Loads rove, fixes the report's shape, and installs the verdict hook. rove is loaded
	 * BEFORE the target, the way {@code rove.ros}'s init forms do, because the hook has
	 * to be in place before the target runs a single test.
	 */
	private static StringBuilder prologue(String reporter, boolean colors, @Nullable String baseDir,
			List<String> systemPath) {
		StringBuilder out = new StringBuilder();
		out.append(";;; Generated by `rontolisp test`: the target, plus the rove runner around it.\n");
		out.append(";;; The exit code is the verdict -- 0 when every test passed, 1 when one did not.\n");
		// asdf:load-system when rove is on the search path (a vendored copy, an offline
		// CI); ql:quickload otherwise, which is what downloads it into the local cache.
		out.append(
				locatable(ROVE, baseDir, systemPath) ? "(asdf:load-system \"rove\")\n" : "(ql:quickload \"rove\")\n");
		if ("none".equals(reporter)) {
			// The silent reporter lives in a system of its own, which rove's
			// make-reporter
			// loads at RUN time -- something only the interpreter can do. Loading it here
			// is what gives a compiled artifact the same reporter.
			out.append("(asdf:load-system \"rove/reporter/none\")\n");
		}
		out.append("(setf rove:*enable-colors* ").append(colors ? "t" : "nil").append(")\n");
		if (!reporter.isEmpty()) {
			out.append("(setf rove:*default-reporter* :").append(reporter).append(")\n");
		}
		out.append(";; rove records *last-suite-report* in call-with-suite, which only rove:run and\n");
		out.append(";; rove:run-tests reach -- a file ending in (rove:run-suite *package*) leaves no\n");
		out.append(";; trace. Every entry point does go through invoke-reporter, whose argument IS the\n");
		out.append(";; stats object, so this is where the runner reads the verdict of any of them.\n");
		out.append("(defmethod rove:invoke-reporter :after (reporter function)\n");
		out.append("  (declare (ignore function))\n");
		out.append("  (setf rove/core/suite:*last-suite-report* (rove/core/stats:stats-results reporter)))\n");
		out.append(";; rove:run reads *default-reporter*, but run-suite's own method hard-codes :spec,\n");
		out.append(";; so -r would silently do nothing for the single-file shape. Same body, same\n");
		out.append(";; specializer -- CL redefinition semantics replace the method -- with the default\n");
		out.append(";; every other entry point already has.\n");
		out.append("(defmethod rove:run-suite (suite &key (style rove:*default-reporter*))\n");
		out.append("  (rove:with-reporter style (rove:run-suite-tests suite)))\n");
		return out;
	}

	/**
	 * The verdict: every result in the report passed, or exit 1. Running NO test is a
	 * failure of its own rather than a vacuous pass -- a target that stopped registering
	 * its tests (a renamed package, a system name that no longer matches) would otherwise
	 * report the green the whole command exists to stop reporting. It is counted rather
	 * than read off an empty report, because a run over a system with no suites at all
	 * still records its (empty) context.
	 */
	private static String epilogue(StringBuilder out, String target) {
		out.append("(let ((tests 0))\n");
		out.append("  (dolist (result rove/core/suite:*last-suite-report*)\n");
		out.append("    (setf tests (+ tests (length (rove:passed-tests result))\n");
		out.append("                   (length (rove:failed-tests result))\n");
		out.append("                   (length (rove:pending-tests result)))))\n");
		out.append("  (cond ((zerop tests)\n");
		// ~a with the name as an ARGUMENT, never spliced into the control string: a
		// target whose path holds a ~ would otherwise be read as a format directive.
		out.append("         (format *error-output* \"~&rontolisp test: no tests were run for ~a~%\" ")
			.append(string(target))
			.append(")\n");
		out.append("         (uiop:quit 1))\n");
		out.append("        ((every #'rove:passedp rove/core/suite:*last-suite-report*) (uiop:quit 0))\n");
		out.append("        (t (uiop:quit 1))))\n");
		return out.toString();
	}

	/**
	 * Whether {@code NAME.asd} is on the search path: the target's own directory first
	 * (what a {@code load} of the target would search), then {@code --system-path} /
	 * {@code RONTOLISP_SOURCE_REGISTRY} -- the same order {@code asdf:load-system} uses.
	 */
	private static boolean locatable(String name, @Nullable String baseDir, List<String> systemPath) {
		List<String> dirs = new ArrayList<>();
		dirs.add(baseDir == null ? "" : baseDir);
		dirs.addAll(systemPath);
		try {
			AsdfSystems.locate(name, dirs, SourceLoader.fileSystem());
			return true;
		}
		catch (RuntimeException _) {
			return false;
		}
	}

	/**
	 * The {@code -r}/{@code --reporter} style, downcased, or {@code ""} when the command
	 * line named none -- which is not the same as naming {@code spec}: rove reads its own
	 * default out of {@code cl-user::*rove-default-reporter*} when a program bound it,
	 * and an unasked-for style must not overwrite that. {@code null} means the value was
	 * unusable and the message is already on stderr.
	 */
	@Nullable private static String reporter(CliOptions options) {
		String value = options.get("-r");
		if (value == null) {
			return "";
		}
		String style = value.trim().toLowerCase(Locale.ROOT);
		if (!REPORTERS.contains(style)) {
			fail("--reporter takes one of " + String.join(", ", REPORTERS) + ", got '" + value + "'");
			return null;
		}
		return style;
	}

	/**
	 * Whether the report is colored. rove's own default is ON, which fills a CI log with
	 * escape codes; here it follows the destination instead -- a terminal the runner is
	 * attached to gets colors, a pipe does not, and a COMPILED artifact never does (the
	 * build cannot see the terminal the artifact will run on). {@code --color} and
	 * {@code --disable-colors} say it outright.
	 */
	private static boolean colors(CliOptions options) {
		if (options.contains("--color")) {
			return true;
		}
		if (options.contains("--disable-colors") || options.contains("--no-color")) {
			return false;
		}
		return !options.contains("-o") && System.console() != null && System.console().isTerminal();
	}

	private static String stripExtension(Path path) {
		String name = path.getFileName().toString();
		int dot = name.lastIndexOf('.');
		return dot <= 0 ? name : name.substring(0, dot);
	}

	/** A Lisp string literal for the given text. */
	private static String string(String text) {
		return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	@Nullable private static Program fail(String message) {
		System.err.println("rontolisp: test: " + message);
		return null;
	}

	static void printUsage(java.io.PrintStream out) {
		out.println("Usage: rontolisp test [options] <file|system>");
		out.println();
		out.println("Runs a rove test target and EXITS with its verdict: 0 when every");
		out.println("test passed, 1 when one did not (and 1 when no test ran at all).");
		out.println("A plain `rontolisp FILE` keeps Common Lisp semantics instead -- the");
		out.println("value of the last top-level form is dropped and the status stays 0.");
		out.println();
		out.println("Targets:");
		out.println("  FILE.lisp          Load the file. If its defpackage names an ASDF");
		out.println("                     system on the search path, that system is loaded");
		out.println("                     and tested instead (real ASDF's package-inferred");
		out.println("                     rule); otherwise the file's own package suite is");
		out.println("                     run -- unless the file already ran it, which is");
		out.println("                     detected, so its tests run exactly once");
		out.println("  FILE.asd           The system the .asd is named after");
		out.println("  SYSTEM             An ASDF system designator (my-app/tests):");
		out.println("                     asdf:test-system, then rove:run for a system");
		out.println("                     that declares no :perform (test-op ...)");
		out.println();
		out.println("Options:");
		out.println("  -r, --reporter S   rove reporter style: spec (default), dot or none");
		out.println("  --color            Force the ANSI colors on");
		out.println("  --disable-colors   Force them off. The default follows the output:");
		out.println("                     a terminal gets colors, a pipe (and every");
		out.println("                     compiled artifact) does not");
		out.println("  -o FILE            Compile the run instead of performing it: the");
		out.println("                     emitted .class / .wasm carries the same exit");
		out.println("                     contract. Every compiler flag applies (--component,");
		out.println("                     --optimize=off, ...); a .wasm compiles in EH mode,");
		out.println("                     since rove's handler-bind puts it there");
		out.println("  --system-path DIRS Directories searched for NAME.asd (like PATH)");
		out.println("  --dist DISTS       Dists ql:quickload may download from, beside");
		out.println("                     quicklisp: a name (ultralisp) or a distinfo URL");
		out.println("  -h, --help         Show this help message");
	}

}
