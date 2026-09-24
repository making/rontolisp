package am.ik.rontolisp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A path cited in prose is a link with nothing holding it: grep finds citations,
 * {@code git ls-files} finds members, and a rename needs both
 * ({@code .kb/directory-rename.md}). This test is the machine half of that -- it fails
 * when a repository-rooted path named in a note, a card or a javadoc comment no longer
 * resolves, on every push, without anyone having to suspect it.
 *
 * <p>
 * <b>The rule, because prose contains paths that are not meant to resolve.</b> A citation
 * is checked when ALL of these hold; anything else is prose and is skipped.
 *
 * <ul>
 * <li>It sits inside a backtick span (markdown) or a {@code @code} tag (javadoc). A bare
 * word in a sentence is not a citation.</li>
 * <li>It begins with one of {@link #CHECKED_PREFIXES} -- a directory this repository
 * actually has. {@code src/} alone is NOT one: notes about a consumed ASDF system or a
 * generated user project cite {@code src/strings.lisp} and {@code src/main/lisp}, which
 * are paths in someone else's tree.</li>
 * <li>It carries no glob or placeholder ({@code *}, {@code ?}, {@code <>}, {@code NNN},
 * {@code YYYY}) -- {@code .kb/*.md} names a set, not a file.</li>
 * <li>It is not a {@code .todo/NNN} or {@code .todo/NNN-title.md} ITEM reference. An
 * item's file is DELETED when it closes and its number goes on being cited afterwards, by
 * design ({@code .todo/.history.md}, "Reading a deleted item"). Everything else under
 * {@code .todo/} -- the artefact directories under {@code .todo/artefacts/} above all --
 * is checked.</li>
 * </ul>
 *
 * <p>
 * <b>What is not scanned, and why.</b> {@code .todo/artefacts/} and
 * {@code .todo/history/} are dated records: a measurement keeps the paths it was taken
 * against, and a history row names the path INSIDE the commit that removed it, so neither
 * may be rewritten to match today's tree. {@code doc/} markdown LINKS are relative to the
 * rendered site rather than to the source tree, so only their backticked citations are
 * checked here; {@code DocGenTest} walks the generated site.
 */
class PathCitationTest {

	/**
	 * Top-level paths whose citations must resolve. Deliberately explicit: the noise a
	 * naive check produces is entirely paths that LOOK repository-rooted and are not.
	 */
	private static final List<String> CHECKED_PREFIXES = List.of(".kb/", ".todo/", "doc/", "examples/", "docs-tool/",
			"bench-report/", "size-report/", "rontolisp-maven-plugin/", "rontolisp-native/", "src/main/java/",
			"src/main/resources/", "src/test/java/", "src/test/resources/", "src/web/java/", "src/native/java/",
			"src/wasm-component/");

	/**
	 * Paths this tree does not have and names on purpose: a record whose subject is a
	 * rename has to name what moved, and a note about a vendored project cites that
	 * project's own layout. Each must be ABSENT -- see
	 * {@link #everyExemptedPathIsActuallyAbsent()}, which is what stops an entry from
	 * silently exempting a live path once the name comes back.
	 */
	private static final List<String> ABSENT_ON_PURPOSE = List.of(
			// The four ignore-bearing directory renames .kb/directory-rename.md is about.
			"examples/llama2", "examples/wasm-size", "examples/wit-world", "examples/hiragana",
			// On the gui-poc branch only, never on develop (.todo/030).
			"examples/maze-rl-gui.lisp",
			// Promoted out of the examples into the shipped metal.lisp
			// (eval/MetalLibrary).
			"examples/macos/metal.lisp",
			// rove's OWN tree, not ours: the vendored copy under src/test/resources/rove
			// ships no examples directory (e2e/RoveE2eTest).
			"examples/passed.lisp");

	private static final List<Path> MARKDOWN_ROOTS = List.of(Path.of(".kb"), Path.of("doc"));

	private static final List<Path> JAVA_ROOTS = List.of(Path.of("src", "main", "java"), Path.of("src", "test", "java"),
			Path.of("src", "web", "java"), Path.of("docs-tool", "src"), Path.of("rontolisp-maven-plugin", "src"));

	private static final Pattern BACKTICK_SPAN = Pattern.compile("`([^`\\n]+)`");

	private static final Pattern CODE_TAG = Pattern.compile("\\{@code\\s+([^}]+)}");

	private static final Pattern MARKDOWN_LINK = Pattern.compile("]\\(([^)\\s]+)\\)");

	/**
	 * {@code .todo/671}, {@code .todo/682-what-a-rename-breaks.md}, {@code .todo/a00} --
	 * an item, not a path.
	 */
	private static final Pattern TODO_ITEM_REFERENCE = Pattern.compile("\\.todo/[0-9a-z]\\d{2}(-[^/]*\\.md)?");

	/** A citation may carry a line or a range: {@code Foo.java:120-134}. */
	private static final Pattern TRAILING_LINES = Pattern.compile(":\\d+(-\\d+)?$");

	/**
	 * A {@code .kb/README.md} index line: {@code [name.md](name.md)}. Excludes the header
	 * paragraph's prose mentions and the "evidence" filenames -- neither is a link, so
	 * neither carries the {@code [x](y)} shape this pattern requires.
	 */
	private static final Pattern KB_INDEX_LINK = Pattern
		.compile("\\[([a-zA-Z0-9._-]+\\.md)]\\(([a-zA-Z0-9._-]+\\.md)\\)");

	@Test
	void everyCitedRepositoryPathResolves() throws IOException {
		List<String> broken = new ArrayList<>();
		for (Path file : scannedMarkdown()) {
			collectBroken(file, BACKTICK_SPAN, broken);
		}
		for (Path root : JAVA_ROOTS) {
			for (Path file : filesUnder(root, ".java")) {
				collectBroken(file, CODE_TAG, broken);
			}
		}
		assertThat(broken)
			.as("A cited path no longer resolves. Either the citation follows what moved, or -- if the record is "
					+ "dated evidence that must keep the old path -- the path joins ABSENT_ON_PURPOSE with its reason.")
			.isEmpty();
	}

	/**
	 * The same check for markdown links in the notes, which are relative to the file that
	 * holds them. {@code doc/} is out: its links resolve in the generated site.
	 */
	@Test
	void everyRelativeLinkInTheNotesResolves() throws IOException {
		List<String> broken = new ArrayList<>();
		for (Path file : Stream.concat(filesUnder(Path.of(".kb"), ".md").stream(), todoItemFiles().stream()).toList()) {
			String masked = maskCodeSpans(Files.readString(file));
			int line = 0;
			for (String text : masked.lines().toList()) {
				line++;
				Matcher matcher = MARKDOWN_LINK.matcher(text);
				while (matcher.find()) {
					String target = matcher.group(1);
					if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("#")
							|| target.startsWith("mailto:")) {
						continue;
					}
					String bare = target.split("#")[0];
					if (bare.isEmpty() || TODO_ITEM_REFERENCE.matcher(resolveAgainst(file, bare)).matches()) {
						continue;
					}
					if (!Files.exists(Path.of(resolveAgainst(file, bare)))) {
						broken.add(file + ":" + line + ": " + target);
					}
				}
			}
		}
		assertThat(broken).as("A markdown link in .kb/ or .todo/ points at a file that is not there.").isEmpty();
	}

	/**
	 * Step 1 of {@code .todo/710}, pinned: {@code .todo/} carries two things with
	 * different lifetimes -- an item, deleted when it closes, and its measurement
	 * artefacts, kept forever because the numbers outlive the item. They shared one
	 * namespace until 2026-09-06, when {@code ls .todo/} showed sixteen numbered
	 * directories of which two belonged to an open item, and three readers had taken one
	 * of the other fourteen for an open item hours after it closed. The artefacts now
	 * live under {@code .todo/artefacts/}, which makes the listing readable without
	 * opening anything and costs nothing at close time -- the directory is already where
	 * it belongs, so closing an item moves nothing.
	 */
	@Test
	void theTodoDirectoryHoldsNoItemNumberedDirectories() throws IOException {
		try (Stream<Path> entries = Files.list(Path.of(".todo"))) {
			List<String> misplaced = entries.filter(Files::isDirectory)
				.map(path -> path.getFileName().toString())
				.filter(name -> !name.equals("artefacts") && !name.equals("history"))
				.sorted()
				.toList();
			assertThat(misplaced)
				.as("Measurement artefacts belong under .todo/artefacts/NNN-title/; only a .todo/NNN-title.md FILE "
						+ "says an item is open.")
				.isEmpty();
		}
	}

	/**
	 * Past 999 an item number's first character continues 0-9 with a-z
	 * ({@code .todo/claim-number.sh}), so {@code a00} is an item number, not a path.
	 */
	@Test
	void anItemNumberPast999IsAnItemReference() {
		assertThat(isCitation(".todo/999")).isFalse();
		assertThat(isCitation(".todo/a00")).isFalse();
		assertThat(isCitation(".todo/z99-the-last-number.md")).isFalse();
		assertThat(isCitation(".todo/artefacts/a00-title")).isTrue();
	}

	/**
	 * {@link #everyCitedRepositoryPathResolves()} and
	 * {@link #everyRelativeLinkInTheNotesResolves()} fail when a link points at nothing;
	 * neither fails when a topic file has no line pointing AT it, so a file added without
	 * an index line breaks no link and the index silently stops being an index. This pins
	 * the other direction: a bijection between the topic files {@code .kb} actually has
	 * and the files {@code .kb/README.md} links, in one assertion, both ways.
	 */
	@Test
	void kbReadmeIndexesEveryTopicFileExactlyOnce() throws IOException {
		List<String> topicFiles = filesUnder(Path.of(".kb"), ".md").stream()
			.map(path -> path.getFileName().toString())
			.filter(name -> !name.equals("README.md"))
			.sorted()
			.toList();

		List<String> linkedTopics = new ArrayList<>();
		Matcher matcher = KB_INDEX_LINK.matcher(Files.readString(Path.of(".kb", "README.md")));
		while (matcher.find()) {
			String linkText = matcher.group(1);
			String target = matcher.group(2);
			assertThat(target).as("Index link text must name the same file as its target: " + matcher.group())
				.isEqualTo(linkText);
			linkedTopics.add(target);
		}

		Set<String> distinctLinked = new HashSet<>(linkedTopics);
		List<String> unlisted = topicFiles.stream().filter(name -> !distinctLinked.contains(name)).toList();
		List<String> dangling = distinctLinked.stream().filter(name -> !topicFiles.contains(name)).sorted().toList();
		Map<String, Long> occurrences = linkedTopics.stream()
			.collect(Collectors.groupingBy(name -> name, Collectors.counting()));
		List<String> duplicated = occurrences.entrySet()
			.stream()
			.filter(entry -> entry.getValue() > 1)
			.map(Map.Entry::getKey)
			.sorted()
			.toList();

		assertThat(unlisted).as("Topic file(s) under .kb/ with no [name.md](name.md) line in .kb/README.md").isEmpty();
		assertThat(dangling).as("Index line(s) in .kb/README.md pointing at a file .kb/ does not have").isEmpty();
		assertThat(duplicated).as("Topic file(s) indexed by more than one line in .kb/README.md").isEmpty();
	}

	@Test
	void everyExemptedPathIsActuallyAbsent() {
		assertThat(ABSENT_ON_PURPOSE.stream().filter(path -> Files.exists(Path.of(path))).toList())
			.as("An exempted path came back, so its entry now hides a live path from the check. Drop the entry.")
			.isEmpty();
	}

	private void collectBroken(Path file, Pattern span, List<String> broken) throws IOException {
		int line = 0;
		for (String text : Files.readString(file).lines().toList()) {
			line++;
			Matcher matcher = span.matcher(text);
			while (matcher.find()) {
				String token = normalize(matcher.group(1).strip());
				if (isCitation(token) && !Files.exists(Path.of(token))) {
					broken.add(file + ":" + line + ": " + token);
				}
			}
		}
	}

	private static boolean isCitation(String token) {
		if (CHECKED_PREFIXES.stream().noneMatch(token::startsWith)) {
			return false;
		}
		if (token.contains("..") || token.contains("NNN") || token.contains("YYYY")) {
			return false;
		}
		for (char c : "*?<>{}|$ \t".toCharArray()) {
			if (token.indexOf(c) >= 0) {
				return false;
			}
		}
		if (TODO_ITEM_REFERENCE.matcher(token).matches()) {
			return false;
		}
		// A build's output (rontolisp-native/target/resources) exists only in a tree that
		// ran that build, so whether it resolves says nothing about the citation.
		if (Arrays.asList(token.split("/")).contains("target")) {
			return false;
		}
		return ABSENT_ON_PURPOSE.stream().noneMatch(absent -> token.equals(absent) || token.startsWith(absent + "/"));
	}

	private static String normalize(String token) {
		String result = token;
		while (!result.isEmpty() && ".,;:)]\\".indexOf(result.charAt(result.length() - 1)) >= 0) {
			result = result.substring(0, result.length() - 1);
		}
		return TRAILING_LINES.matcher(result).replaceAll("");
	}

	/**
	 * Every scanned file sits under {@code .kb/} or {@code .todo/}, so it has a parent.
	 */
	private static String resolveAgainst(Path file, String target) {
		Path directory = file.getParent();
		return (directory == null ? Path.of(target) : directory.resolve(target)).normalize().toString();
	}

	/**
	 * Backticked text is quoted, not written -- a page that SHOWS link syntax is not
	 * linking.
	 */
	private static String maskCodeSpans(String text) {
		return BACKTICK_SPAN.matcher(text).replaceAll(match -> " ".repeat(match.group().length()));
	}

	private static List<Path> scannedMarkdown() throws IOException {
		List<Path> files = new ArrayList<>(todoItemFiles());
		for (Path root : MARKDOWN_ROOTS) {
			files.addAll(filesUnder(root, ".md"));
		}
		try (Stream<Path> entries = Files.list(Path.of("."))) {
			files.addAll(entries.filter(path -> path.getFileName().toString().endsWith(".md")).sorted().toList());
		}
		return files;
	}

	/**
	 * Top level only: {@code .todo/artefacts/} and {@code .todo/history/} are dated
	 * records.
	 */
	private static List<Path> todoItemFiles() throws IOException {
		try (Stream<Path> entries = Files.list(Path.of(".todo"))) {
			return entries.filter(path -> path.getFileName().toString().endsWith(".md")).sorted().toList();
		}
	}

	private static List<Path> filesUnder(Path root, String suffix) throws IOException {
		if (!Files.isDirectory(root)) {
			return List.of();
		}
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().endsWith(suffix))
				.sorted()
				.toList();
		}
		catch (UncheckedIOException e) {
			throw e.getCause();
		}
	}

}
