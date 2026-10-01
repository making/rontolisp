package am.ik.rontolisp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A catalog {@code index_page} with no {@code nav.yaml} entry breaks nobody's
 * root-suite run: {@code docs-tool/} stands outside the root reactor, so only
 * {@code DocGen.generateLanguage} (an {@code IOException} naming the catalog, the
 * page and the tree) catches it, when someone next builds that module (b18's
 * {@code clojure/reference/names.md}, b21's
 * {@code clojure/reference/regex.md}). This test is the machine half in the root
 * suite -- it fails when any {@code _catalog.yaml} under {@code doc/en} or
 * {@code doc/ja} names an {@code index_page} that is not a {@code file:} page in
 * the matching {@code doc/en/nav.yaml} or {@code doc/ja/nav.yaml}, on every push, without anyone having to run the
 * docs build. Pure file IO: the two line shapes are parsed with a regex, the way
 * the docs-tool's own test parses ID and HREF.
 */
class CatalogIndexPageTest {

	private static final List<String> LANGS = List.of("en", "ja");

	private static final Pattern INDEX_PAGE = Pattern.compile("^\\s*index_page:\\s*(\\S+)\\s*$");

	private static final Pattern NAV_FILE = Pattern.compile("^\\s*-\\s*file:\\s*(\\S+)\\s*$");

	@Test
	void everyCatalogIndexPageIsANavPage() throws IOException {
		List<String> broken = new ArrayList<>();
		for (String lang : LANGS) {
			Path root = Path.of("doc", lang);
			if (!Files.isDirectory(root)) {
				broken.add("doc tree missing: " + root);
				continue;
			}
			Set<String> navPages = navPages(root.resolve("nav.yaml"), lang, broken);
			for (Path catalog : catalogsUnder(root)) {
				for (String indexPage : indexPages(catalog)) {
					if (!navPages.contains(indexPage)) {
						broken.add(catalog + " names index_page '" + indexPage + "', which is not a file: page in "
								+ lang + "/nav.yaml");
					}
				}
			}
		}
		assertThat(broken)
			.as("A catalog index_page has no nav.yaml entry. Either the page joins the matching doc/<lang>/nav.yaml "
					+ "as a file: page (mirrored across both trees), or the catalog stops naming it.")
			.isEmpty();
	}

	private static Set<String> navPages(Path nav, String lang, List<String> broken) throws IOException {
		Set<String> pages = new HashSet<>();
		if (!Files.isRegularFile(nav)) {
			broken.add("nav missing: " + nav);
			return pages;
		}
		int line = 0;
		for (String text : Files.readString(nav).lines().toList()) {
			line++;
			Matcher matcher = NAV_FILE.matcher(text);
			if (matcher.matches()) {
				String page = stripQuotes(matcher.group(1));
				if (page.isEmpty()) {
					broken.add(nav + ":" + line + ": empty file: value");
				}
				else {
					pages.add(page);
				}
			}
		}
		if (pages.isEmpty()) {
			broken.add("no file: pages parsed from " + lang + "/nav.yaml");
		}
		return pages;
	}

	private static List<Path> catalogsUnder(Path root) throws IOException {
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().equals("_catalog.yaml"))
				.sorted()
				.toList();
		}
		catch (UncheckedIOException e) {
			throw e.getCause();
		}
	}

	private static List<String> indexPages(Path catalog) throws IOException {
		List<String> pages = new ArrayList<>();
		for (String text : Files.readString(catalog).lines().toList()) {
			Matcher matcher = INDEX_PAGE.matcher(text);
			if (matcher.matches()) {
				String page = stripQuotes(matcher.group(1));
				if (!page.isEmpty()) {
					pages.add(page);
				}
			}
		}
		return pages;
	}

	private static String stripQuotes(String value) {
		String result = value.strip();
		if (result.length() >= 2 && ((result.startsWith("\"") && result.endsWith("\""))
				|| (result.startsWith("'") && result.endsWith("'")))) {
			return result.substring(1, result.length() - 1).strip();
		}
		return result;
	}

}
