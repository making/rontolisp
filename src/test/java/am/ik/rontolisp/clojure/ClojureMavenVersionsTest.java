package am.ik.rontolisp.clojure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Maven version order tools.deps selects the newest version by, against the oracle's
 * own: {@code maven-version-order.txt} holds 121 versions -- numbers, separators, leading
 * zeros, every qualifier and its one-letter spellings, words, {@code min}/{@code max},
 * big numbers, the empty version -- each with the sign of comparing it to every other,
 * computed 2026-10-08 by {@code clj} 1.12.6's {@code GenericVersionScheme}
 * ({@code clojure-tools-1.12.6.1673.jar}, maven-resolver-util 1.9.27).
 */
class ClojureMavenVersionsTest {

	@Test
	void everyPairComparesLikeTheOracle() throws IOException {
		List<String> versions = new ArrayList<>();
		List<int[]> signs = new ArrayList<>();
		try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("maven-version-order.txt"))) {
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				int close = closingQuote(line);
				versions.add(unescape(line.substring(1, close)));
				String[] row = line.substring(line.indexOf('[', close) + 1, line.lastIndexOf(']')).split(" ");
				int[] values = new int[row.length];
				for (int i = 0; i < row.length; i++) {
					values[i] = Integer.parseInt(row[i]);
				}
				signs.add(values);
			}
		}
		assertThat(versions).hasSize(121);
		List<String> mismatches = new ArrayList<>();
		for (int i = 0; i < versions.size(); i++) {
			for (int j = 0; j < versions.size(); j++) {
				int expected = signs.get(i)[j];
				int actual = Integer.signum(ClojureMavenVersions.compare(versions.get(i), versions.get(j)));
				if (actual != expected) {
					mismatches.add(versions.get(i) + " vs " + versions.get(j) + ": " + actual + " not " + expected);
				}
			}
		}
		assertThat(mismatches).isEmpty();
	}

	@Test
	void theRanksAProjectMeets() {
		assertThat(ClojureMavenVersions.compare("1.15.5", "1.15.10")).isNegative();
		assertThat(ClojureMavenVersions.compare("2.0-rc1", "2.0-beta1")).isPositive();
		assertThat(ClojureMavenVersions.compare("1.0", "1.0.0")).isZero();
		assertThat(ClojureMavenVersions.compare("1.12.0-alpha1", "1.12.0")).isNegative();
		assertThat(ClojureMavenVersions.compare("1.0-SNAPSHOT", "1.0")).isNegative();
	}

	private static int closingQuote(String line) {
		for (int i = 1; i < line.length(); i++) {
			if (line.charAt(i) == '\\') {
				i++;
			}
			else if (line.charAt(i) == '"') {
				return i;
			}
		}
		throw new IllegalArgumentException(line);
	}

	private static String unescape(String text) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\\' && i + 1 < text.length()) {
				i++;
				c = text.charAt(i);
			}
			out.append(c);
		}
		return out.toString();
	}

}
