package am.ik.rontolisp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
 * item's file is DELETED when it closes, and the notes ({@code .kb/}, {@code .todo/}) go
 * on citing its number afterwards ({@code .todo/.history.md}, "Reading a deleted item").
 * Everything else under {@code .todo/} -- the artefact directories under
 * {@code .todo/artefacts/} above all -- is checked.</li>
 * </ul>
 *
 * <p>
 * <b>An item number belongs to the notes only.</b> Outside {@code .kb/} and
 * {@code .todo/} -- source, tests, test data, examples, user docs -- a comment states the
 * fact or measurement itself, or points at the {@code .kb/} file holding it; an item
 * number means nothing to a reader once the item closes.
 * {@link #noTodoItemIsCitedOutsideTheNotes()} fails on one.
 *
 * <p>
 * <b>A section title cited from source must be a heading of the file it is cited
 * from.</b> Outside {@code .kb/} and {@code .todo/}, a comment that names a note and
 * quotes a title after it points at a section by name, and a heading that is renamed or
 * split takes the pointer with it without breaking any path.
 * {@link #everyQuotedSectionTitleInSourceIsAHeadingOfThatNote()} resolves each one: the
 * quoted words, markup stripped and case ignored, must open a heading, so a title may be
 * shortened to its leading words but may not be reworded. A citation that is a bold
 * paragraph label or a table row rather than a heading names the heading it sits under.
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
			// On the gui-poc branch only, never on develop.
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
	 * {@code .todo/NNN} or {@code .todo/NNN-title.md}, NNN being three digits or, past
	 * 999, a letter and two digits -- an item, not a path.
	 */
	private static final Pattern TODO_ITEM_REFERENCE = Pattern.compile("\\.todo/[0-9a-z]\\d{2}(-[^/]*\\.md)?");

	/**
	 * An item number cited anywhere: {@code .todo/NNN} (also behind a closing
	 * {@code @code} brace), and the word todo directly before the number --
	 * {@code todo NNN}, {@code Todo-NNN}, or the number wrapped onto the next comment
	 * line. Three digits or a letter and two digits; two digits alone is how an item
	 * below 100 was written in prose.
	 */
	private static final Pattern TODO_ITEM_CITATION = Pattern.compile(
			"\\.todo[}/-]{1,2}[0-9a-z]\\d{2}(?![0-9A-Za-z])|(?i)\\btodos?(?:[ -]|\\s*\\R\\s*(?://+|\\*|;+|#+)?\\s*)"
					+ "#?(?:[a-z]\\d{2}|\\d{2,3})(?![0-9A-Za-z])");

	/**
	 * Top-level entries the item-number scan leaves out: the notes, where item numbers
	 * belong, and what is not this repository's text.
	 */
	private static final Set<String> NOT_SCANNED_FOR_ITEMS = Set.of(".git", ".kb", ".todo", ".claude", ".idea",
			"target");

	/**
	 * Directories that are build output wherever they sit
	 * ({@code examples/.../node_modules}); none is tracked.
	 */
	private static final Set<String> PRUNED_DIRECTORIES = Set.of("target", "node_modules", "dist", ".wrangler",
			".gradle", ".venv");

	/** The ANSI suite checkout: foreign code, git-ignored. */
	private static final Path ANSI_SUITE = Path.of(".", "ansi-test", "suite");

	/** Larger than any tracked text file by a wide margin; past it is model weights. */
	private static final long LARGEST_SCANNED_FILE = 8L * 1024 * 1024;

	/** A citation may carry a line or a range: {@code Foo.java:120-134}. */
	private static final Pattern TRAILING_LINES = Pattern.compile(":\\d+(-\\d+)?$");

	/**
	 * A {@code .kb/README.md} index line: {@code [name.md](name.md)}. Excludes the header
	 * paragraph's prose mentions and the "evidence" filenames -- neither is a link, so
	 * neither carries the {@code [x](y)} shape this pattern requires.
	 */
	private static final Pattern KB_INDEX_LINK = Pattern
		.compile("\\[([a-zA-Z0-9._-]+\\.md)]\\(([a-zA-Z0-9._-]+\\.md)\\)");

	/**
	 * A note's path followed by a quoted title: the path, an optional closing
	 * {@code @code} brace, backtick or parenthesis, then a comma or whitespace and the
	 * title in double quotes. The separator is required so a string literal that ends
	 * right after a note's name is not read as opening a title.
	 */
	private static final Pattern KB_SECTION_CITATION = Pattern
		.compile("\\.kb/([A-Za-z0-9_.-]+\\.md)[}`)]*(?:,\\s*|\\s+)\"([^\"\\\\\\n]{1,120})\"");

	/**
	 * A line break inside a comment, with the comment leader that starts the next line: a
	 * citation wraps across lines and its title is read as if it had not.
	 */
	private static final Pattern COMMENT_LINE_BREAK = Pattern.compile("[ \\t]*\\R[ \\t]*(?://+!?|\\*|;+|#+)?[ \\t]*");

	private static final Pattern HEADING_LINE = Pattern.compile("^#{1,6}\\s+(.*?)\\s*$");

	private static final Pattern CODE_TAG_WRAPPER = Pattern.compile("\\{@code\\s+([^}]*)}");

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
	 * {@code .todo/} carries two things with different lifetimes -- an item, deleted when
	 * it closes, and its measurement artefacts, kept forever because the numbers outlive
	 * the item. They shared one namespace until 2026-09-06, when {@code ls .todo/} showed
	 * sixteen numbered directories of which two belonged to an open item, and three
	 * readers had taken one of the other fourteen for an open item hours after it closed.
	 * The artefacts now live under {@code .todo/artefacts/}, which makes the listing
	 * readable without opening anything and costs nothing at close time -- the directory
	 * is already where it belongs, so closing an item moves nothing.
	 *
	 * <p>
	 * A third top-level directory, {@code .todo/pending/}, holds {@code NNN-title.md}
	 * files the same shape as an open item, for one specific state: the work itself is
	 * done and what remains is waiting on an upstream party (a bug report filed, a fix
	 * merged upstream) rather than on anything this repository can still act on. A file
	 * under it is still an open item -- {@code claim-number.sh} counts its numbers as
	 * used -- just not one a listing of {@code .todo/} itself needs to surface.
	 */
	@Test
	void theTodoDirectoryHoldsNoItemNumberedDirectories() throws IOException {
		try (Stream<Path> entries = Files.list(Path.of(".todo"))) {
			List<String> misplaced = entries.filter(Files::isDirectory)
				.map(path -> path.getFileName().toString())
				.filter(name -> !name.equals("artefacts") && !name.equals("history") && !name.equals("pending"))
				.sorted()
				.toList();
			assertThat(misplaced)
				.as("Measurement artefacts belong under .todo/artefacts/NNN-title/; only a .todo/NNN-title.md FILE "
						+ "or a .todo/pending/NNN-title.md FILE (an item waiting on an upstream party) says an item is open.")
				.isEmpty();
		}
	}

	/**
	 * Past 999 an item number's first character continues 0-9 with a-z
	 * ({@code .todo/claim-number.sh}), so {@code a00} is an item number, not a path.
	 */
	@Test
	void anItemNumberPast999IsAnItemReference() {
		assertThat(isCitation(item("999"))).isFalse();
		assertThat(isCitation(item("a00"))).isFalse();
		assertThat(isCitation(item("z99") + "-the-last-number.md")).isFalse();
		assertThat(isCitation(".todo/artefacts/a00-title")).isTrue();
	}

	@Test
	void noTodoItemIsCitedOutsideTheNotes() throws IOException {
		List<String> citing = new ArrayList<>();
		for (Path file : filesScannedForItems()) {
			String text;
			try {
				text = Files.readString(file);
			}
			catch (CharacterCodingException binary) {
				continue;
			}
			Matcher matcher = TODO_ITEM_CITATION.matcher(text);
			while (matcher.find()) {
				long line = 1 + text.substring(0, matcher.start()).chars().filter(c -> c == '\n').count();
				citing.add(file + ":" + line + ": " + matcher.group().replaceAll("\\s+", " "));
			}
		}
		assertThat(citing)
			.as("A todo item number is cited outside .kb/ and .todo/. State the fact or measurement itself, or point "
					+ "at the .kb/ file that holds it: the number means nothing once the item closes.")
			.isEmpty();
	}

	/**
	 * The forms the scan has to catch, and the paths and numbers it must leave alone.
	 */
	@Test
	void anItemCitationIsRecognizedInEveryForm() {
		for (String citing : List.of(item("671"), item("682") + "-what-a-rename-breaks.md", item("a00"),
				"{@code .todo}-" + "332's inventory", "(todo " + "626)", "Todo " + "445: a method", "todo-" + "473's",
				"(todo " + "a42)", "todo " + "92 Tier 2", "(todo\n\t\t// " + "194 stage 2)", "(todo\n * " + "a58)")) {
			assertThat(TODO_ITEM_CITATION.matcher(citing).find()).as(citing).isTrue();
		}
		for (String clean : List.of(".todo/artefacts/672-q8/bench.sh", ".todo/claim-number.sh", ".todo/NNN-title.md",
				"todo list", "(format t \"~a12\")", "a todo: 3 cases", "todo\n(print 100)")) {
			assertThat(TODO_ITEM_CITATION.matcher(clean).find()).as(clean).isFalse();
		}
	}

	@Test
	void everyQuotedSectionTitleInSourceIsAHeadingOfThatNote() throws IOException {
		Map<String, List<String>> headings = new HashMap<>();
		List<String> unresolved = new ArrayList<>();
		for (Path file : filesScannedForItems()) {
			if (file.getFileName().toString().endsWith(".json")) {
				continue;
			}
			String text;
			try {
				text = Files.readString(file);
			}
			catch (CharacterCodingException binary) {
				continue;
			}
			if (!text.contains(".kb/")) {
				continue;
			}
			StringBuilder joined = new StringBuilder();
			List<Integer> lineOf = new ArrayList<>();
			int line = 1;
			int from = 0;
			Matcher breaks = COMMENT_LINE_BREAK.matcher(text);
			while (breaks.find()) {
				for (int i = from; i < breaks.start(); i++) {
					joined.append(text.charAt(i));
					lineOf.add(line);
				}
				joined.append(' ');
				lineOf.add(line);
				line += (int) breaks.group().chars().filter(c -> c == '\n').count();
				from = breaks.end();
			}
			for (int i = from; i < text.length(); i++) {
				joined.append(text.charAt(i));
				lineOf.add(line);
			}
			Matcher citation = KB_SECTION_CITATION.matcher(joined);
			while (citation.find()) {
				String note = citation.group(1);
				List<String> noteHeadings = headings.computeIfAbsent(note, PathCitationTest::normalizedHeadings);
				if (!opensAHeading(citation.group(2), noteHeadings)) {
					unresolved.add(file + ":" + lineOf.get(citation.start()) + ": .kb/" + note + ", \""
							+ citation.group(2) + "\"");
				}
			}
		}
		assertThat(unresolved)
			.as("A quoted section title in source is not a heading of the note it names (or the note "
					+ "does not exist). Quote the words a heading opens with; for a paragraph label or a table row, "
					+ "name the heading it sits under.")
			.isEmpty();
	}

	@Test
	void aQuotedTitleMustOpenAHeadingAfterMarkupAndCaseAreIgnored() {
		List<String> modRem = List.of(normalizeTitle("`mod` / `rem` and the floor family"));
		List<String> buffers = List.of(normalizeTitle("Asynchronous command buffers"));
		assertThat(opensAHeading("mod / rem", modRem)).isTrue();
		assertThat(opensAHeading("{@code mod} / rem and the floor", modRem)).isTrue();
		assertThat(opensAHeading("Asynchronous  COMMAND buffers", buffers)).isTrue();
		assertThat(opensAHeading("Asynchronous command buffers on Metal", buffers)).isFalse();
		assertThat(opensAHeading("command buffers", buffers)).isFalse();
		assertThat(KB_SECTION_CITATION.matcher("(.kb/gpu.md, \"Tests\")").find()).isTrue();
		assertThat(KB_SECTION_CITATION.matcher("{@code .kb/gpu.md}, \"Tests\"").find()).isTrue();
		assertThat(KB_SECTION_CITATION.matcher("\"see .kb/gpu.md\").isEmpty()").find()).isFalse();
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

	/** An item path built at run time, so this file cites no item itself. */
	private static String item(String number) {
		return ".todo/" + number;
	}

	private static List<Path> filesScannedForItems() throws IOException {
		List<Path> files = new ArrayList<>();
		try (Stream<Path> entries = Files.list(Path.of("."))) {
			for (Path entry : entries.sorted().toList()) {
				if (NOT_SCANNED_FOR_ITEMS.contains(entry.getFileName().toString())) {
					continue;
				}
				if (Files.isRegularFile(entry)) {
					files.add(entry);
					continue;
				}
				Files.walkFileTree(entry, new SimpleFileVisitor<>() {
					@Override
					public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
						boolean pruned = PRUNED_DIRECTORIES.contains(dir.getFileName().toString())
								|| dir.equals(ANSI_SUITE);
						return pruned ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
						if (attrs.isRegularFile() && attrs.size() <= LARGEST_SCANNED_FILE) {
							files.add(file);
						}
						return FileVisitResult.CONTINUE;
					}
				});
			}
		}
		return files.stream().sorted().toList();
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

	/** A quoted title opens a heading when, normalized, it is a prefix of one. */
	private static boolean opensAHeading(String title, List<String> normalizedHeadings) {
		String wanted = normalizeTitle(title);
		return !wanted.isEmpty() && normalizedHeadings.stream().anyMatch(heading -> heading.startsWith(wanted));
	}

	/**
	 * Lower case, single spaces, no backticks and no {@code @code} wrapper: how a heading
	 * is written in markdown and how a comment quotes it differ in exactly these.
	 */
	private static String normalizeTitle(String title) {
		String unwrapped = CODE_TAG_WRAPPER.matcher(title).replaceAll("$1").replace("`", "");
		return unwrapped.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
	}

	/**
	 * The normalized headings of the note {@code .kb/<note>}, outside code fences; empty
	 * if there is no such note.
	 */
	private static List<String> normalizedHeadings(String note) {
		Path file = Path.of(".kb", note);
		if (!Files.isRegularFile(file)) {
			return List.of();
		}
		List<String> headings = new ArrayList<>();
		boolean fenced = false;
		try {
			for (String text : Files.readAllLines(file)) {
				if (text.startsWith("```")) {
					fenced = !fenced;
				}
				Matcher heading = HEADING_LINE.matcher(text);
				if (!fenced && heading.matches()) {
					headings.add(normalizeTitle(heading.group(1)));
				}
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return headings;
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
	 * Every {@code NNN-title.md} item file, open ones at the top level plus the ones
	 * under {@code .todo/pending/} waiting on an upstream party. {@code .todo/artefacts/}
	 * and {@code .todo/history/} are dated records and stay out.
	 */
	private static List<Path> todoItemFiles() throws IOException {
		List<Path> files = new ArrayList<>();
		try (Stream<Path> entries = Files.list(Path.of(".todo"))) {
			files.addAll(entries.filter(path -> path.getFileName().toString().endsWith(".md")).toList());
		}
		Path pending = Path.of(".todo", "pending");
		if (Files.isDirectory(pending)) {
			try (Stream<Path> entries = Files.list(pending)) {
				files.addAll(entries.filter(path -> path.getFileName().toString().endsWith(".md")).toList());
			}
		}
		return files.stream().sorted().toList();
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
