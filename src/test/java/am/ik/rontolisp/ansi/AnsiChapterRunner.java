package am.ik.rontolisp.ansi;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;

/**
 * Runs ONE chapter of the ANSI test suite in this JVM and writes a result line per
 * top-level form.
 * <p>
 * Every form is read and evaluated on its own, guarded by {@code catch (Throwable)}: a
 * conformance run must survive the failure modes a Lisp-level {@code handler-case} cannot
 * see (a raw Java exception out of the evaluator, a {@code StackOverflowError} from a
 * deeply recursive test), because each of those is one data point, not a reason to lose
 * the chapter. What this process cannot survive is a form that never returns; the parent
 * ({@link AnsiCompliance}) times the process out, learns the form index from the last
 * {@code @index} marker, and re-runs the chapter with that index skipped.
 */
public final class AnsiChapterRunner {

	private AnsiChapterRunner() {
	}

	/**
	 * Entry point of the child process.
	 * @param args suite directory, chapter name, shim path, then the skip indices
	 * @throws IOException when a suite file cannot be read
	 */
	public static void main(String[] args) throws IOException {
		Path suite = Path.of(args[0]);
		String chapter = args[1];
		Path shim = Path.of(args[2]);
		Set<Integer> skips = new HashSet<>();
		for (int i = 3; i < args.length; i++) {
			skips.add(Integer.parseInt(args[i]));
		}
		// The driver's own markers and the shim's result lines share one stream, so the
		// parent reads them in order: a marker is what tells it which form a crash was
		// in. Markers carry a prefix no test output realistically produces.
		PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
		PrintStream report = out;
		LispEvaluator evaluator = new LispEvaluator(out);
		evaluator.setLoadBaseDir(suite.toAbsolutePath().toString());
		for (LispVal form : LispReader.readAllFromString(Files.readString(shim), Features.INTERPRETER,
				shim.toString())) {
			evaluator.eval(form);
		}
		int index = 0;
		for (String rel : SuiteLayout.chapterFiles(suite, chapter)) {
			String source = Files.readString(suite.resolve(rel), StandardCharsets.UTF_8);
			report.println("%%%FILE " + rel);
			for (String text : TopLevelSplitter.split(source)) {
				int idx = index++;
				if (skips.contains(idx) || text.regionMatches(true, 0, "(in-package", 0, 11)) {
					// in-package would move the shim's own names out of reach: the driver
					// keeps every form in one package instead.
					continue;
				}
				report.println("%%%AT " + idx);
				runForm(evaluator, report, rel, idx, text);
			}
		}
		report.println("%%%END " + index);
		out.flush();
	}

	private static void runForm(LispEvaluator evaluator, PrintStream report, String rel, int idx, String text) {
		LispVal form;
		try {
			form = LispReader.readFirstForm(text, Features.INTERPRETER, rel);
		}
		catch (Throwable t) {
			report.println("%%%READ " + idx + " " + oneLine(t));
			return;
		}
		try {
			evaluator.eval(form);
		}
		catch (Throwable t) {
			report.println("%%%EVAL " + idx + " " + oneLine(t));
		}
	}

	private static String oneLine(Throwable t) {
		String message = t.getMessage() == null ? t.getClass().getSimpleName()
				: t.getClass().getSimpleName() + ": " + t.getMessage();
		return message.replace('\n', ' ').replace('\r', ' ');
	}

	/**
	 * The files of a chapter, in the order the suite's own {@code load.lsp} loads them.
	 */
	static final class SuiteLayout {

		/** Loaded before every chapter, in the order {@code gclload1.lsp} loads them. */
		static final List<String> PREFIX = List.of("auxiliary/ansi-aux-macros.lsp", "universe.lsp",
				"auxiliary/random-aux.lsp", "auxiliary/ansi-aux.lsp", "cl-symbol-names.lsp", "notes.lsp");

		private SuiteLayout() {
		}

		static List<String> chapterFiles(Path suite, String chapter) throws IOException {
			List<String> files = new ArrayList<>(PREFIX);
			Path load = suite.resolve(chapter).resolve("load.lsp");
			if (Files.exists(load)) {
				String source = Files.readString(load, StandardCharsets.UTF_8);
				for (String name : matches(source, "(compile-and-load")) {
					files.add(name.contains("/") ? name : "auxiliary/" + name);
				}
				for (String name : matches(source, "(load")) {
					files.add(chapter + "/" + name);
				}
			}
			List<String> existing = new ArrayList<>();
			for (String rel : files) {
				if (Files.exists(suite.resolve(rel))) {
					existing.add(rel);
				}
			}
			return existing;
		}

		/** The string literal argument of each {@code (head "...")} call in a source. */
		private static List<String> matches(String source, String head) {
			List<String> names = new ArrayList<>();
			int i = 0;
			while ((i = source.indexOf(head, i)) >= 0) {
				int j = i + head.length();
				// compile-and-load and compile-and-load* both start with the same head
				while (j < source.length() && (source.charAt(j) == '*' || source.charAt(j) == ' ')) {
					j++;
				}
				if (j < source.length() && source.charAt(j) == '"') {
					int end = source.indexOf('"', j + 1);
					if (end > 0) {
						names.add(source.substring(j + 1, end));
					}
				}
				i = j;
			}
			return names;
		}

	}

}
