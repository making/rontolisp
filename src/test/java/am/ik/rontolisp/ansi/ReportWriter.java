package am.ik.rontolisp.ansi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import am.ik.rontolisp.ansi.AnsiCompliance.ChapterResult;

/** Renders the chapter results as the markdown report checked in under results/. */
final class ReportWriter {

	private ReportWriter() {
	}

	static String render(List<ChapterResult> results, Path suite) {
		StringBuilder sb = new StringBuilder();
		int pass = results.stream().mapToInt(ChapterResult::pass).sum();
		int fail = results.stream().mapToInt(ChapterResult::fail).sum();
		int error = results.stream().mapToInt(ChapterResult::error).sum();
		int read = results.stream().mapToInt(ChapterResult::readFailures).sum();
		int eval = results.stream().mapToInt(ChapterResult::evalFailures).sum();
		int hung = results.stream().mapToInt(ChapterResult::skipped).sum();
		int total = pass + fail + error;
		sb.append("# ANSI test suite -- interpreter\n\n");
		sb.append("Suite: `").append(suiteRevision(suite)).append("`\n\n");
		sb.append(String.format("**%,d / %,d tests pass (%.1f%%)** -- %,d fail, %,d signal an error.%n%n", pass, total,
				total == 0 ? 0.0 : 100.0 * pass / total, fail, error));
		sb.append(String.format(
				"%,d top-level forms could not be read, %,d could not be evaluated, %,d did not terminate; "
						+ "every test those forms would have defined is missing from the counts above.%n%n",
				read, eval, hung));
		sb.append("| chapter | tests | pass | fail | error | pass rate | top-level forms lost |\n");
		sb.append("|---|---:|---:|---:|---:|---:|---:|\n");
		for (ChapterResult r : results) {
			sb.append(String.format("| %s | %,d | %,d | %,d | %,d | %.1f%% | %,d |%n", r.chapter(), r.total(), r.pass(),
					r.fail(), r.error(), r.total() == 0 ? 0.0 : 100.0 * r.pass() / r.total(),
					r.readFailures() + r.evalFailures() + r.skipped()));
		}
		sb.append(String.format("| **total** | **%,d** | **%,d** | **%,d** | **%,d** | **%.1f%%** | **%,d** |%n%n",
				total, pass, fail, error, total == 0 ? 0.0 : 100.0 * pass / total, read + eval + hung));
		sb.append("## Most frequent failure reasons\n\n");
		sb.append("| count | reason |\n|---:|---|\n");
		Map<String, Integer> reasons = new TreeMap<>();
		for (ChapterResult r : results) {
			r.reasons().forEach((k, v) -> reasons.merge(k, v, Integer::sum));
		}
		List<Map.Entry<String, Integer>> top = new ArrayList<>(reasons.entrySet());
		top.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed());
		for (Map.Entry<String, Integer> e : top.subList(0, Math.min(40, top.size()))) {
			sb.append(String.format("| %,d | `%s` |%n", e.getValue(), e.getKey().replace("|", "\\|")));
		}
		sb.append('\n');
		return sb.toString();
	}

	private static String suiteRevision(Path suite) {
		Path head = suite.resolve(".git").resolve("HEAD");
		try {
			if (Files.exists(head)) {
				String ref = Files.readString(head).strip();
				if (ref.startsWith("ref: ")) {
					Path target = suite.resolve(".git").resolve(ref.substring(5).strip());
					if (Files.exists(target)) {
						return Files.readString(target).strip();
					}
				}
				return ref;
			}
		}
		catch (IOException ignored) {
			// an unreadable checkout is not worth failing the report over
		}
		return "unknown";
	}

}
