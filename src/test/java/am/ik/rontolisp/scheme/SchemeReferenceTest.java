package am.ik.rontolisp.scheme;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Scheme reference ({@code doc/<lang>/scheme/reference/}) to the front end: one
 * page for every name {@link Scheme#providedNames()} answers, nothing missing and nothing
 * extra, so a builtin added later cannot go without a page. And every row of a table page
 * is backed by a CHECKED block on the name's own page -- {@code DocExamplesTest} verifies
 * the block, this test that the table shows what the block checks.
 */
class SchemeReferenceTest {

	private static final Path REFERENCE = Path.of("scheme", "reference");

	private static final Pattern ENTRY = Pattern.compile("\\{ slug: ([a-z0-9-]+), name: \"((?:[^\"\\\\]|\\\\.)*)\" }");

	private static final Pattern INDEX_PAGE = Pattern.compile("^\\s+index_page: (\\S+)$", Pattern.MULTILINE);

	// | `name` | `example` | result |
	private static final Pattern ROW = Pattern.compile("^\\| (`[^`]+`) \\| (`` .+? ``|`[^`]+`) \\| (.+) \\|$",
			Pattern.MULTILINE);

	private static final Pattern SCHEME_BLOCK = Pattern.compile("^```scheme\\n(.*?)^```$",
			Pattern.MULTILINE | Pattern.DOTALL);

	@ParameterizedTest
	@ValueSource(strings = { "en", "ja" })
	void theCatalogNamesEveryProvidedNameOnce(String lang) throws IOException {
		Map<String, String> slugs = catalog(lang);
		assertThat(slugs.keySet()).containsExactlyInAnyOrderElementsOf(Scheme.providedNames());
		for (String slug : slugs.values()) {
			assertThat(root(lang).resolve(REFERENCE).resolve(slug + ".md")).as(slug).isRegularFile();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "ja" })
	void everyLanguageHasTheSameEntriesInTheSameOrder(String lang) throws IOException {
		assertThat(List.copyOf(catalog(lang).entrySet())).isEqualTo(List.copyOf(catalog("en").entrySet()));
	}

	@ParameterizedTest
	@ValueSource(strings = { "en", "ja" })
	void everyTableRowIsBackedByACheckedBlockOnItsPage(String lang) throws IOException {
		Map<String, String> slugs = catalog(lang);
		Set<String> rows = new HashSet<>();
		for (String indexPage : indexPages(lang)) {
			String table = Files.readString(root(lang).resolve(indexPage), StandardCharsets.UTF_8);
			Matcher row = ROW.matcher(table);
			while (row.find()) {
				String name = codeSpan(row.group(1));
				if (!slugs.containsKey(name)) {
					continue;
				}
				assertThat(rows.add(name)).as("%s has one row", name).isTrue();
				String example = codeSpan(row.group(2));
				String result = row.group(3).strip();
				String page = Files.readString(root(lang).resolve(REFERENCE).resolve(slugs.get(name) + ".md"),
						StandardCharsets.UTF_8);
				List<String> lines = schemeLines(page);
				if (isCodeSpan(result)) {
					assertThat(lines).as("%s (%s): the row's example, checked", name, lang)
						.contains(example + " ; => " + codeSpan(result));
				}
				else {
					assertThat(lines.stream().anyMatch(line -> line.contains(example)))
						.as("%s (%s): the row's example %s is on its page", name, lang, example)
						.isTrue();
				}
			}
		}
		assertThat(rows).as("every name has a table row").containsExactlyInAnyOrderElementsOf(slugs.keySet());
	}

	private static Path root(String lang) {
		return Path.of("doc", lang);
	}

	/** The catalog's names, in order, to their slugs. */
	private static Map<String, String> catalog(String lang) throws IOException {
		String yaml = Files.readString(root(lang).resolve(REFERENCE).resolve("_catalog.yaml"), StandardCharsets.UTF_8);
		Map<String, String> slugs = new LinkedHashMap<>();
		Matcher entry = ENTRY.matcher(yaml);
		while (entry.find()) {
			String name = entry.group(2).replace("\\\"", "\"").replace("\\\\", "\\");
			assertThat(slugs.put(name, entry.group(1))).as("%s is catalogued once", name).isNull();
		}
		return slugs;
	}

	private static Set<String> indexPages(String lang) throws IOException {
		String yaml = Files.readString(root(lang).resolve(REFERENCE).resolve("_catalog.yaml"), StandardCharsets.UTF_8);
		Set<String> pages = new java.util.LinkedHashSet<>();
		Matcher page = INDEX_PAGE.matcher(yaml);
		while (page.find()) {
			pages.add(page.group(1));
		}
		return pages;
	}

	private static List<String> schemeLines(String page) {
		List<String> lines = new ArrayList<>();
		Matcher block = SCHEME_BLOCK.matcher(page);
		while (block.find()) {
			lines.addAll(List.of(block.group(1).split("\n")));
		}
		return lines;
	}

	private static boolean isCodeSpan(String cell) {
		return cell.startsWith("`") && cell.endsWith("`") && codeSpan(cell).indexOf('`') < 0
				|| cell.startsWith("`` ") && cell.endsWith(" ``");
	}

	private static String codeSpan(String cell) {
		if (cell.startsWith("`` ") && cell.endsWith(" ``")) {
			return cell.substring(3, cell.length() - 3);
		}
		return cell.substring(1, cell.length() - 1);
	}

}
