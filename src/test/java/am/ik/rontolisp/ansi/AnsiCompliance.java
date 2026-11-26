package am.ik.rontolisp.ansi;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the ANSI Common Lisp test suite against the rontolisp interpreter and writes the
 * conformance report.
 * <p>
 * One child JVM per chapter ({@link AnsiChapterRunner}), because a chapter is the unit
 * the suite itself shares state in and the unit a hang can cost us. The child survives
 * everything but a hang; a hang is timed out here, the offending form index is read off
 * the last progress marker, and the chapter is re-run with it skipped -- so the number of
 * re-runs is the number of non-terminating forms, not the number of failures.
 */
public final class AnsiCompliance {

	private static final Pattern MARKER = Pattern.compile("^%%%(AT|READ|EVAL|FILE|END) ?(.*)$");

	private AnsiCompliance() {
	}

	/**
	 * Runs every chapter and writes the report.
	 * @param args optional chapter names; all chapters when empty
	 * @throws Exception when the suite cannot be read or a child cannot be started
	 */
	public static void main(String[] args) throws Exception {
		Path root = Path.of(System.getProperty("rontolisp.ansi.root", "ansi-test")).toAbsolutePath();
		Path suite = Path.of(System.getProperty("rontolisp.ansi.suite", root.resolve("suite").toString()));
		Path shim = root.resolve("rt-shim.lisp");
		Path logs = root.resolve("results").resolve("logs");
		Files.createDirectories(logs);
		if (!Files.isDirectory(suite)) {
			throw new IOException("suite not found: " + suite + " (run ansi-test/fetch.sh first)");
		}
		List<String> chapters = args.length > 0 ? List.of(args) : chapters(suite);
		int workers = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
		ExecutorService pool = Executors.newFixedThreadPool(workers);
		List<Future<ChapterResult>> futures = new ArrayList<>();
		for (String chapter : chapters) {
			futures.add(pool.submit(runner(suite, shim, logs, root.resolve("results").resolve("work"), chapter)));
		}
		pool.shutdown();
		List<ChapterResult> results = new ArrayList<>();
		for (Future<ChapterResult> f : futures) {
			results.add(f.get());
		}
		results.sort(Comparator.comparing(ChapterResult::chapter));
		// A subset run writes its own file: the checked-in report is a whole-suite
		// baseline, and one chapter measured on its own must not silently replace it.
		Path report = root.resolve("results").resolve(args.length > 0 ? "partial.md" : "interpreter.md");
		Files.writeString(report, ReportWriter.render(results, suite), StandardCharsets.UTF_8);
		System.out.println("wrote " + report);
	}

	private static Callable<ChapterResult> runner(Path suite, Path shim, Path logs, Path work, String chapter) {
		return () -> {
			List<Integer> skips = new ArrayList<>();
			String output = "";
			for (int attempt = 0; attempt <= 40; attempt++) {
				Run run = execute(suite, shim, chapter, skips, work);
				output = run.output();
				if (!run.timedOut()) {
					break;
				}
				Integer last = lastMarker(output);
				System.err.printf("%s: form %s did not terminate, skipping%n", chapter, last);
				if (last == null || skips.contains(last)) {
					break;
				}
				skips.add(last);
			}
			Files.writeString(logs.resolve(chapter + ".log"), output, StandardCharsets.UTF_8);
			return ChapterResult.parse(chapter, output, skips);
		};
	}

	private record Run(String output, boolean timedOut) {
	}

	private static Run execute(Path suite, Path shim, String chapter, List<Integer> skips, Path workRoot)
			throws Exception {
		// The child runs in a scratch directory, so a relative classpath entry (this is
		// usually started as -cp target/test-classes:target/classes) has to be resolved
		// against OUR working directory before it is handed over.
		String classpath = java.util.Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
			.map(entry -> Path.of(entry).toAbsolutePath().toString())
			.collect(java.util.stream.Collectors.joining(File.pathSeparator));
		List<String> command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java"), "-cp",
				classpath, "-Xss64m", AnsiChapterRunner.class.getName(), suite.toString(), chapter, shim.toString()));
		skips.forEach(i -> command.add(String.valueOf(i)));
		// The chapter runs in a scratch directory of its own: the files/streams chapters
		// create, rename and delete files in the working directory, and a run must not
		// leave that litter in the repository root.
		Path work = Files.createDirectories(workRoot.resolve(chapter));
		Process process = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true).start();
		StringBuilder sb = new StringBuilder();
		java.util.concurrent.atomic.AtomicLong lastOutput = new java.util.concurrent.atomic.AtomicLong(
				System.nanoTime());
		Thread drain = Thread.ofVirtual().start(() -> {
			try (InputStream in = process.getInputStream()) {
				byte[] buffer = new byte[8192];
				int n;
				while ((n = in.read(buffer)) > 0) {
					synchronized (sb) {
						sb.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
					}
					lastOutput.set(System.nanoTime());
				}
			}
			catch (IOException ignored) {
				// the process died mid-read; whatever arrived is the record
			}
		});
		// A form that never returns is found by SILENCE, not by a wall-clock budget for
		// the whole chapter: the child prints a marker per form, so a chapter that is
		// still making progress is never killed however long it legitimately takes, and
		// a chapter that stops making it is killed in seconds instead of minutes.
		long stall = TimeUnit.SECONDS.toNanos(Integer.getInteger("rontolisp.ansi.stall", 60));
		boolean done;
		while (!(done = process.waitFor(1, TimeUnit.SECONDS))) {
			if (System.nanoTime() - lastOutput.get() > stall) {
				break;
			}
		}
		if (!done) {
			process.destroyForcibly();
			process.waitFor();
		}
		drain.join(5000);
		synchronized (sb) {
			return new Run(sb.toString(), !done);
		}
	}

	private static @org.jspecify.annotations.Nullable Integer lastMarker(String output) {
		Integer last = null;
		for (String line : output.split("\n")) {
			Matcher m = MARKER.matcher(line.strip());
			if (m.matches() && m.group(1).equals("AT")) {
				last = Integer.valueOf(m.group(2).strip());
			}
		}
		return last;
	}

	/** Chapter directories of the suite: every directory holding a {@code load.lsp}. */
	static List<String> chapters(Path suite) throws IOException {
		List<String> chapters = new ArrayList<>();
		try (var stream = Files.list(suite)) {
			for (Path p : stream.sorted().toList()) {
				if (Files.isDirectory(p) && Files.exists(p.resolve("load.lsp"))
						&& !p.getFileName().toString().equals("auxiliary")) {
					chapters.add(p.getFileName().toString());
				}
			}
		}
		return chapters;
	}

	/** The outcome of one chapter. */
	record ChapterResult(String chapter, int pass, int fail, int error, int readFailures, int evalFailures, int skipped,
			Map<String, Integer> reasons, Map<String, int[]> perFile) {

		static ChapterResult parse(String chapter, String output, List<Integer> skips) {
			int pass = 0;
			int fail = 0;
			int error = 0;
			int read = 0;
			int eval = 0;
			Map<String, Integer> reasons = new TreeMap<>();
			Map<String, int[]> perFile = new LinkedHashMap<>();
			String file = "";
			for (String raw : output.split("\n")) {
				String line = raw.strip();
				if (line.startsWith("%%%FILE ")) {
					file = line.substring(8).strip();
					perFile.computeIfAbsent(file, k -> new int[3]);
				}
				else if (line.startsWith("%%%READ ")) {
					read++;
					reasons.merge(Reasons.normalize(line.substring(8)), 1, Integer::sum);
				}
				else if (line.startsWith("%%%EVAL ")) {
					eval++;
					reasons.merge(Reasons.normalize(line.substring(8)), 1, Integer::sum);
				}
				else if (line.startsWith("PASS ")) {
					pass++;
					perFile.computeIfAbsent(file, k -> new int[3])[0]++;
				}
				else if (line.startsWith("FAIL ")) {
					fail++;
					perFile.computeIfAbsent(file, k -> new int[3])[1]++;
				}
				else if (line.startsWith("ERROR ")) {
					error++;
					perFile.computeIfAbsent(file, k -> new int[3])[2]++;
					reasons.merge(Reasons.normalize(line), 1, Integer::sum);
				}
			}
			return new ChapterResult(chapter, pass, fail, error, read, eval, skips.size(), reasons, perFile);
		}

		int total() {
			return this.pass + this.fail + this.error;
		}
	}

	/** Collapses a failure message to the shape that repeats across tests. */
	static final class Reasons {

		private static final Pattern QUOTED = Pattern.compile("[A-Z0-9%*+<>=/.-]{2,}");

		private static final Pattern NAMED = Pattern
			.compile("The (?:function|variable) [^ ]+ is (?:undefined|unbound)");

		private Reasons() {
		}

		static String normalize(String message) {
			String text = message.strip();
			int at = text.indexOf(' ');
			if (text.startsWith("ERROR ") && at >= 0) {
				text = text.substring(6);
				int second = text.indexOf(' ');
				text = second < 0 ? "" : text.substring(second + 1);
			}
			else if (at >= 0 && text.substring(0, at).matches("\\d+")) {
				text = text.substring(at + 1);
			}
			text = text.strip();
			// The name in "the function F is undefined" is the finding, not noise: it
			// says
			// which operator is missing. Everything else groups by shape, so that a
			// hundred tests failing the same way read as one line.
			Matcher named = NAMED.matcher(text);
			if (named.find()) {
				return named.group();
			}
			text = QUOTED.matcher(text).replaceAll("X");
			return text.length() > 110 ? text.substring(0, 110) : text;
		}

	}

}
