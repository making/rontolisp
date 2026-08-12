package am.ik.rontolisp.docgen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;

/**
 * Agent-skill generator. Packages the documentation of one language into a Claude skill
 * -- a {@code SKILL.md} plus the whole doc tree as bundled references -- and publishes it
 * next to the HTML site.
 *
 * <p>
 * The skill is GENERATED rather than written, so that the documentation stays the single
 * source of truth: the only hand-written text is {@code skill/SKILL.template.md} on the
 * classpath, which holds the frontmatter and the routing prose and pulls everything else
 * in through {@code {{include:...}}} and the generated tables.
 *
 * <p>
 * Layout of the output directory:
 *
 * <pre>
 * &lt;out&gt;/
 *   index.html                install page
 *   VERSION, version.json     what a client polls to see whether it is stale
 *   rontolisp/SKILL.md        the skill
 *   rontolisp/references/**   the doc tree, plus the generated INDEX.md / operators.md
 *   rontolisp-skill.tar.gz    -&gt; ~/.claude/skills/
 *   rontolisp.skill           the same tree as a zip
 *   rontolisp-full.md         everything concatenated, for agents without a skill loader
 * </pre>
 *
 * Usage:
 *
 * <pre>
 * java -jar rontolisp-docgen.jar skill --source doc --out web/dist/skill \
 *      [--lang en] [--version 0.1.42] [--commit abc1234] [--site-base URL]
 * </pre>
 */
public final class SkillGen {

	/** Directory name of the skill inside the bundle; also the skill's name. */
	static final String SKILL_NAME = "rontolisp";

	static final String TEMPLATE_RESOURCE = "/skill/SKILL.template.md";

	static final String INDEX_HTML_RESOURCE = "/skill/index.html";

	/** The version used when nothing supplies one (local runs outside CI). */
	static final String DEV_VERSION = "0.0.0-dev";

	/** The generated page listing every documentation page, under {@code references/}. */
	static final String CONTENTS_PAGE = "contents.md";

	/** The generated page listing every operator, under {@code references/}. */
	static final String OPERATORS_PAGE = "operators.md";

	/**
	 * The documentation page that IS the install page, relative to the language
	 * directory.
	 */
	static final String INSTALL_GUIDE = "getting-started/agent-skill.md";

	/**
	 * The skill packaged as a Claude Code plugin, for an {@code archive} marketplace
	 * source.
	 */
	static final String PLUGIN_ZIP = SKILL_NAME + "-plugin.zip";

	/** The one-file marketplace that `claude plugin marketplace add <url>` reads. */
	static final String MARKETPLACE_JSON = "marketplace.json";

	/** Where the mirrored example programs land, under {@code references/}. */
	static final String EXAMPLES_DIR = "examples";

	/** The generated page listing every mirrored example. */
	static final String EXAMPLES_PAGE = "examples.md";

	/** Where a file that exists in the repository but not in the bundle can be read. */
	static final String DEFAULT_REPO_BASE = "https://github.com/making/rontolisp/blob/develop";

	/**
	 * What an example is made of, as far as a reader is concerned. The bundle is text an
	 * agent reads, so a compiled artifact (`.wasm`) or a weight file (`.bin`) is not
	 * merely large, it is unreadable -- the allowlist keeps binary out by construction
	 * rather than by naming every kind of it.
	 */
	static final List<String> EXAMPLE_TEXT_EXTENSIONS = List.of("lisp", "asd", "md", "txt", "sh", "js", "mjs", "css",
			"html", "json", "jsonc", "wit", "wat", "wac", "yaml", "yml", "toml", "rs", "py", "java", "xml", "sql",
			"csv");

	/** Build output that happens to sit inside an example directory. */
	static final List<String> EXAMPLE_SKIP_DIRS = List.of("target", "node_modules", "dist", "build", "out");

	/** How many files of one example the index names before summarizing the rest. */
	static final int LISTED_FILES_PER_EXAMPLE = 10;

	private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]*)]\\(([^)\\s]+)\\)");

	private static final Pattern INCLUDE = Pattern.compile("\\{\\{include:([^}]+)}}");

	private final Path source;

	private final Path out;

	private final String lang;

	private final String version;

	private final String commit;

	private final String siteBase;

	private final Path examples;

	private final String repoBase;

	private final String previousVersion;

	private final String previousHash;

	public SkillGen(Path source, Path out, String lang, String version, String commit, String siteBase) {
		this(source, out, lang, version, commit, siteBase, null, DEFAULT_REPO_BASE, null, null);
	}

	public SkillGen(Path source, Path out, String lang, String version, String commit, String siteBase, Path examples,
			String repoBase) {
		this(source, out, lang, version, commit, siteBase, examples, repoBase, null, null);
	}

	public SkillGen(Path source, Path out, String lang, String version, String commit, String siteBase, Path examples,
			String repoBase, String previousVersion, String previousHash) {
		this.previousVersion = previousVersion;
		this.previousHash = previousHash;
		this.source = source;
		this.out = out;
		this.lang = lang;
		this.version = version;
		this.commit = commit;
		this.siteBase = siteBase;
		this.examples = examples;
		this.repoBase = repoBase;
	}

	public static void main(String[] args) throws IOException {
		Path source = Path.of("doc");
		Path out = Path.of("web/dist/skill");
		String lang = "en";
		String version = DEV_VERSION;
		String commit = "";
		String siteBase = "https://making.github.io/rontolisp";
		Path examples = Path.of("examples");
		String repoBase = DEFAULT_REPO_BASE;
		String previousVersion = null;
		String previousHash = null;
		for (int i = 0; i < args.length - 1; i++) {
			switch (args[i]) {
				case "--source" -> source = Path.of(args[++i]);
				case "--out" -> out = Path.of(args[++i]);
				case "--lang" -> lang = args[++i];
				case "--version" -> version = args[++i];
				case "--commit" -> commit = args[++i];
				case "--site-base" -> siteBase = args[++i];
				case "--examples" -> examples = Path.of(args[++i]);
				case "--repo-base" -> repoBase = args[++i];
				case "--previous-version" -> previousVersion = args[++i];
				case "--previous-hash" -> previousHash = args[++i];
				default -> {
				}
			}
		}
		new SkillGen(source, out, lang, version, commit, siteBase, examples, repoBase, previousVersion, previousHash)
			.generate();
	}

	/** Writes the whole bundle. */
	public void generate() throws IOException {
		Path langDir = this.source.resolve(this.lang);
		if (!Files.exists(langDir.resolve("nav.yaml"))) {
			throw new IOException("No nav.yaml under " + langDir);
		}
		Nav nav = Nav.load(langDir.resolve("nav.yaml"));
		List<Catalog> catalogs = Catalog.discover(langDir);

		Path skillDir = this.out.resolve(SKILL_NAME);
		Path refDir = skillDir.resolve("references");
		deleteRecursively(skillDir);
		Files.createDirectories(refDir);

		Map<String, String> references = new LinkedHashMap<>();
		for (Map.Entry<String, String> page : readMarkdown(langDir).entrySet()) {
			references.put(page.getKey(), siteLinksForMirroredPage(page.getValue(), page.getKey()));
		}
		Map<String, String> examplePages = readExamples();
		if (!examplePages.isEmpty()) {
			for (Map.Entry<String, String> page : examplePages.entrySet()) {
				references.put(EXAMPLES_DIR + "/" + page.getKey(),
						page.getKey().endsWith(".md")
								? repoLinksForMissingFiles(page.getValue(), page.getKey(), examplePages.keySet())
								: page.getValue());
			}
			references.put(EXAMPLES_PAGE, buildExampleIndex(examplePages));
		}
		// Not "index.md": the documentation already has one, and a case-insensitive
		// filesystem would silently let the generated file eat it.
		references.put(CONTENTS_PAGE, buildContents(nav, catalogs));
		references.put(OPERATORS_PAGE, buildOperatorIndex(catalogs));
		for (Map.Entry<String, String> entry : references.entrySet()) {
			Path target = refDir.resolve(entry.getKey());
			Files.createDirectories(target.getParent());
			Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
		}

		// What the skill IS decides the version, not how many commits went by: a
		// change to doc/ja, to a test, or to a .wasm the bundle excludes produces
		// the same bytes, and an install that re-downloads for that is noise.
		String digest = contentDigest(references, renderSkill(langDir, nav, catalogs, ""));
		String resolved = digest.equals(this.previousHash) ? this.previousVersion : this.version;

		String skill = renderSkill(langDir, nav, catalogs, resolved);
		Files.writeString(skillDir.resolve("SKILL.md"), skill, StandardCharsets.UTF_8);

		Files.writeString(this.out.resolve("VERSION"), resolved + "\n", StandardCharsets.UTF_8);
		Files.writeString(this.out.resolve("version.json"), versionJson(resolved, digest), StandardCharsets.UTF_8);
		Files.writeString(this.out.resolve(SKILL_NAME + "-full.md"), buildSingleFile(skill, references, resolved),
				StandardCharsets.UTF_8);
		Files.writeString(this.out.resolve("index.html"), renderInstallPage(langDir, resolved), StandardCharsets.UTF_8);

		Map<String, byte[]> archive = new LinkedHashMap<>();
		archive.put(SKILL_NAME + "/SKILL.md", skill.getBytes(StandardCharsets.UTF_8));
		for (Map.Entry<String, String> entry : references.entrySet()) {
			archive.put(SKILL_NAME + "/references/" + entry.getKey(),
					entry.getValue().getBytes(StandardCharsets.UTF_8));
		}
		writeTarGz(this.out.resolve(SKILL_NAME + "-skill.tar.gz"), archive);
		writeZip(this.out.resolve(SKILL_NAME + ".skill"), archive);

		// The plugin form of the same skill, plus the one-file marketplace that
		// points at it -- `claude plugin marketplace add <that URL>` is the install
		// path that keeps itself up to date.
		String summary = frontmatterDescription(skill);
		Map<String, byte[]> plugin = new LinkedHashMap<>();
		plugin.put(".claude-plugin/plugin.json", pluginJson(summary, resolved).getBytes(StandardCharsets.UTF_8));
		for (Map.Entry<String, byte[]> entry : archive.entrySet()) {
			plugin.put("skills/" + entry.getKey(), entry.getValue());
		}
		writeZip(this.out.resolve(PLUGIN_ZIP), plugin);
		Files.writeString(this.out.resolve(MARKETPLACE_JSON), marketplaceJson(summary, resolved),
				StandardCharsets.UTF_8);

		System.out.println("Generated skill " + SKILL_NAME + " " + resolved
				+ (resolved.equals(this.version) ? "" : " (unchanged content; " + this.version + " not published)")
				+ " (" + references.size() + " reference files) into " + this.out);
	}

	// --- SKILL.md ---------------------------------------------------------

	private String renderSkill(Path langDir, Nav nav, List<Catalog> catalogs, String version) throws IOException {
		String template = readResource(TEMPLATE_RESOURCE);
		String body = substituteIncludes(template, langDir);
		body = body.replace("{{version}}", version)
			.replace("{{site-base}}", this.siteBase)
			.replace("{{operator-counts}}", operatorCounts(catalogs))
			.replace("{{guides-table}}", buildGuidesTable(nav))
			.replace("{{nav-table}}", buildNavTable(nav));
		if (body.contains("{{")) {
			throw new IOException("Unsubstituted placeholder in " + TEMPLATE_RESOURCE + ": "
					+ body.substring(body.indexOf("{{"), Math.min(body.length(), body.indexOf("{{") + 40)));
		}
		return body;
	}

	/**
	 * Inlines {@code {{include:PATH}}} with the documentation page at PATH. The page is
	 * demoted one heading level so it nests under the skill, and its relative links are
	 * retargeted at the bundled copy of whatever they pointed at -- which is what keeps
	 * the inlined text a view of the documentation rather than a fork of it.
	 */
	private String substituteIncludes(String template, Path langDir) throws IOException {
		Matcher matcher = INCLUDE.matcher(template);
		StringBuilder out = new StringBuilder();
		while (matcher.find()) {
			String relative = matcher.group(1).trim();
			Path page = langDir.resolve(relative);
			if (!Files.exists(page)) {
				throw new IOException("{{include:" + relative + "}} has no source page: " + page);
			}
			String markdown = Files.readString(page, StandardCharsets.UTF_8);
			markdown = retargetLinks(demoteHeadings(markdown), relative);
			matcher.appendReplacement(out, Matcher.quoteReplacement(markdown.strip()));
		}
		matcher.appendTail(out);
		return out.toString();
	}

	/**
	 * A digest of everything the bundle contains except the version itself, so that two
	 * runs over the same documentation agree no matter how many commits went by in
	 * between. Published in {@code version.json}, and fed back to the next run as
	 * {@code --previous-hash}: that is what lets an unchanged skill keep its version,
	 * which is what stops a client re-downloading it for nothing.
	 */
	static String contentDigest(Map<String, String> references, String skillWithoutVersion) throws IOException {
		try {
			java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
			for (Map.Entry<String, String> entry : new java.util.TreeMap<>(references).entrySet()) {
				sha.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
				sha.update((byte) 0);
				sha.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
				sha.update((byte) 0);
			}
			sha.update(skillWithoutVersion.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder();
			for (byte b : sha.digest()) {
				hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
			}
			return hex.toString();
		}
		catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException("SHA-256 is unavailable", e);
		}
	}

	/** Adds one {@code #} to every ATX heading. */
	static String demoteHeadings(String markdown) {
		return markdown.replaceAll("(?m)^(#{1,5} )", "#$1");
	}

	/**
	 * Rewrites the relative links of a page being inlined into {@code SKILL.md}, so that
	 * they resolve from the bundle root instead of from the page's own directory.
	 * Absolute and anchor-only links are left alone.
	 */
	String retargetLinks(String markdown, String pageRelativePath) {
		return mapLinks(markdown, pageRelativePath,
				(inTree, resolved) -> inTree ? "references/" + resolved : siteUrl(resolved));
	}

	/**
	 * Rewrites the links of a page being MIRRORED into the bundle. The tree keeps its
	 * shape, so a link that stays inside it still resolves as written; one that escapes
	 * it (the site's playground, say) only exists on the web, and is turned into the URL
	 * it means.
	 */
	String siteLinksForMirroredPage(String markdown, String pageRelativePath) {
		return mapLinks(markdown, pageRelativePath, (inTree, resolved) -> inTree ? null : siteUrl(resolved));
	}

	/**
	 * Applies {@code mapper} to every real relative link. The mapper receives whether the
	 * target resolves inside the language tree and its path relative to that tree's root,
	 * and returns the new target (or null to leave the link alone).
	 */
	private String mapLinks(String markdown, String pageRelativePath,
			java.util.function.BiFunction<Boolean, String, String> mapper) {
		String pageDir = pageRelativePath.contains("/")
				? pageRelativePath.substring(0, pageRelativePath.lastIndexOf('/')) + "/" : "";
		boolean[] code = codeMask(markdown);
		Matcher matcher = MD_LINK.matcher(markdown);
		StringBuilder out = new StringBuilder();
		while (matcher.find()) {
			String target = matcher.group(2);
			String replacement = matcher.group(0);
			if (!code[matcher.start(2)] && !target.startsWith("#") && !target.contains("://")
					&& !target.startsWith("mailto:")) {
				int hash = target.indexOf('#');
				String file = hash < 0 ? target : target.substring(0, hash);
				String anchor = hash < 0 ? "" : target.substring(hash);
				String resolved = normalize(pageDir + file);
				boolean inTree = !resolved.startsWith("..");
				String mapped = mapper.apply(inTree, resolved);
				if (mapped != null) {
					replacement = "[" + matcher.group(1) + "](" + mapped + anchor + ")";
				}
			}
			matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(out);
		return out.toString();
	}

	/** A Markdown source path as the site publishes it. */
	private static String asHtml(String path) {
		return path.endsWith(".md") ? path.substring(0, path.length() - 3) + ".html" : path;
	}

	/** The published URL of a path that climbed out of the language tree. */
	private String siteUrl(String resolvedFromLangRoot) {
		String path = normalize("docs/" + this.lang + "/" + resolvedFromLangRoot);
		return this.siteBase + "/" + path;
	}

	/**
	 * Marks every character that sits inside a fenced block or an inline code span, so
	 * that link handling can skip text that only LOOKS like a link -- these pages quote
	 * JavaScript and Lisp that contains {@code ](...)}. A code span inside a link label
	 * ({@code [`car`](car.md)}) is left alone: what disqualifies a match is its target
	 * being inside code, not its label.
	 */
	static boolean[] codeMask(String markdown) {
		boolean[] mask = new boolean[markdown.length()];
		boolean inFence = false;
		int lineStart = 0;
		while (lineStart <= markdown.length()) {
			int newline = markdown.indexOf('\n', lineStart);
			int lineEnd = newline < 0 ? markdown.length() : newline;
			String line = markdown.substring(lineStart, lineEnd);
			if (line.stripLeading().startsWith("```") || line.stripLeading().startsWith("~~~")) {
				inFence = !inFence;
				java.util.Arrays.fill(mask, lineStart, lineEnd, true);
			}
			else if (inFence) {
				java.util.Arrays.fill(mask, lineStart, lineEnd, true);
			}
			else {
				for (int i = lineStart; i < lineEnd; i++) {
					if (markdown.charAt(i) != '`') {
						continue;
					}
					int close = markdown.indexOf('`', i + 1);
					if (close < 0 || close >= lineEnd) {
						break;
					}
					java.util.Arrays.fill(mask, i, close + 1, true);
					i = close;
				}
			}
			if (newline < 0) {
				break;
			}
			lineStart = newline + 1;
		}
		return mask;
	}

	/** Collapses {@code a/b/../c} to {@code a/c} without touching the filesystem. */
	static String normalize(String path) {
		List<String> parts = new ArrayList<>();
		for (String segment : path.split("/")) {
			if (segment.isEmpty() || segment.equals(".")) {
				continue;
			}
			if (segment.equals("..") && !parts.isEmpty() && !parts.getLast().equals("..")) {
				parts.removeLast();
			}
			else {
				parts.add(segment);
			}
		}
		return String.join("/", parts);
	}

	// --- generated reference pages ---------------------------------------

	/**
	 * The existence check: every operator the implementation ships, by category, straight
	 * from the catalogs the reference pages are built from. An agent carrying Common Lisp
	 * priors needs one lookup, not 645 page reads.
	 */
	static String buildOperatorIndex(List<Catalog> catalogs) {
		StringBuilder md = new StringBuilder();
		md.append("""
				# Operator index

				Every operator rontolisp ships. **A name that is not listed here does not
				exist** -- rontolisp is a subset, so a Common Lisp operator missing from this
				page is missing from the language, not from the page.

				Each entry links to its detail page, which carries the signature, a runnable
				example and that operator's own deviations from Common Lisp. Paths are
				relative to this file.
				""");
		for (Catalog catalog : catalogs) {
			md.append("\n## ")
				.append(kindTitle(catalog))
				.append(" (")
				.append(catalog.flatEntries().size())
				.append(")\n\n`")
				.append(catalog.baseDir())
				.append("/<slug>.md`\n");
			for (Catalog.Category category : catalog.categories()) {
				if (category.functions().isEmpty()) {
					continue;
				}
				md.append("\n### ").append(category.title()).append("\n\n");
				List<String> cells = new ArrayList<>();
				for (Catalog.Entry entry : category.functions()) {
					cells.add("[`" + entry.name() + "`](" + catalog.baseDir() + "/" + entry.slug() + ".md)");
				}
				md.append(String.join(", ", cells)).append("\n");
			}
		}
		return md.toString();
	}

	// --- examples ---------------------------------------------------------

	/**
	 * Reads the example programs, keyed by path relative to the examples directory.
	 * Documentation says what the language does; an example says what a whole working
	 * program of some shape looks like, which is the other half of writing one.
	 */
	private Map<String, String> readExamples() throws IOException {
		Map<String, String> files = new LinkedHashMap<>();
		if (this.examples == null || !Files.isDirectory(this.examples)) {
			return files;
		}
		try (Stream<Path> paths = Files.walk(this.examples)) {
			for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
				String relative = this.examples.relativize(path).toString().replace('\\', '/');
				if (isReadableExampleFile(relative)) {
					files.put(relative, Files.readString(path, StandardCharsets.UTF_8));
				}
			}
		}
		return files;
	}

	/** True for a text file of an example that is not build output. */
	static boolean isReadableExampleFile(String relative) {
		for (String segment : relative.split("/")) {
			if (segment.startsWith(".") || EXAMPLE_SKIP_DIRS.contains(segment)) {
				return false;
			}
		}
		return EXAMPLE_TEXT_EXTENSIONS.contains(extensionOf(relative));
	}

	/** The lower-case extension of a path, or the empty string. */
	static String extensionOf(String path) {
		int dot = path.lastIndexOf('.');
		return dot < 0 ? "" : path.substring(dot + 1).toLowerCase();
	}

	/**
	 * Points an example's prose at the repository for the files the bundle leaves out --
	 * the compiled `.wasm` its README tells you to run, say. The link stays a link, and
	 * says where the thing really is.
	 */
	String repoLinksForMissingFiles(String markdown, String pageRelativePath, java.util.Set<String> bundled) {
		return mapLinks(markdown, pageRelativePath, (inTree, resolved) -> {
			if (!inTree) {
				return this.repoBase + "/" + normalize(EXAMPLES_DIR + "/" + pageRelativePath + "/../" + resolved);
			}
			return bundled.contains(resolved) ? null : this.repoBase + "/" + EXAMPLES_DIR + "/" + resolved;
		});
	}

	/**
	 * One line per example directory, so that "is there a program that already does
	 * this?" is a single lookup rather than a walk of the tree.
	 */
	static String buildExampleIndex(Map<String, String> examplePages) {
		Map<String, List<String>> byDirectory = new java.util.TreeMap<>();
		for (String relative : examplePages.keySet()) {
			int slash = relative.lastIndexOf('/');
			byDirectory.computeIfAbsent(slash < 0 ? "" : relative.substring(0, slash), key -> new ArrayList<>())
				.add(relative.substring(slash + 1));
		}
		StringBuilder md = new StringBuilder("""
				# Examples

				Whole working programs from the repository, mirrored here as they are --
				minus the build outputs, so a compiled `.wasm` or a `.bin` of weights is a
				link to the repository rather than a file. Read the one closest in shape to
				what you are about to write: it shows the imports, the entry point and the
				build command that a reference page cannot.

				Paths are relative to this file.
				""");
		String group = null;
		for (Map.Entry<String, List<String>> entry : byDirectory.entrySet()) {
			String directory = entry.getKey();
			if (directory.isEmpty()) {
				continue;
			}
			String top = directory.contains("/") ? directory.substring(0, directory.indexOf('/')) : directory;
			if (!top.equals(group)) {
				group = top;
				md.append("\n## ").append(top).append("\n\n");
			}
			md.append("- `").append(EXAMPLES_DIR).append('/').append(directory).append("/` -- ");
			String readme = directory + "/README.md";
			String title = examplePages.containsKey(readme) ? firstHeading(examplePages.get(readme)) : null;
			md.append(title == null ? "" : title + " ");
			// Name the files worth opening; a directory of fixtures (46 sample
			// glyphs, say) is a count, not 46 links nobody follows.
			List<String> names = new ArrayList<>(entry.getValue());
			names.sort(java.util.Comparator.comparing((String name) -> switch (extensionOf(name)) {
				case "md" -> 0;
				case "lisp", "asd" -> 1;
				case "sh" -> 2;
				default -> 3;
			}).thenComparing(java.util.Comparator.naturalOrder()));
			List<String> links = new ArrayList<>();
			for (String name : names.subList(0, Math.min(names.size(), LISTED_FILES_PER_EXAMPLE))) {
				links.add("[" + name + "](" + EXAMPLES_DIR + "/" + directory + "/" + name + ")");
			}
			md.append(String.join(", ", links));
			if (names.size() > LISTED_FILES_PER_EXAMPLE) {
				md.append(" (+").append(names.size() - LISTED_FILES_PER_EXAMPLE).append(" more in the directory)");
			}
			md.append("\n");
		}
		return md.toString();
	}

	/** The text of a Markdown file's first ATX heading, or null. */
	static String firstHeading(String markdown) {
		Matcher matcher = Pattern.compile("(?m)^#{1,3} (.+)$").matcher(markdown);
		return matcher.find() ? matcher.group(1).trim() + " --" : null;
	}

	/** Every documentation page by title, in navigation order. */
	static String buildContents(Nav nav, List<Catalog> catalogs) {
		StringBuilder md = new StringBuilder("""
				# Documentation index

				Every page bundled with this skill, in the order the documentation site
				presents it. Paths are relative to this file.
				""");
		md.append(buildNavTable(nav));
		md.append("\n## Per-operator pages\n\n");
		for (Catalog catalog : catalogs) {
			md.append("- ")
				.append(kindTitle(catalog))
				.append(": ")
				.append(catalog.flatEntries().size())
				.append(" pages under `")
				.append(catalog.baseDir())
				.append("/` -- listed in [")
				.append(OPERATORS_PAGE)
				.append("](")
				.append(OPERATORS_PAGE)
				.append(")\n");
		}
		return md.toString();
	}

	private static String buildNavTable(Nav nav) {
		StringBuilder md = new StringBuilder();
		for (Nav.Section section : nav.sections()) {
			md.append("\n## ").append(section.title()).append("\n\n");
			for (Nav.Page page : section.pages()) {
				md.append("- [").append(page.title()).append("](").append(page.file()).append(")\n");
			}
		}
		return md.toString();
	}

	/**
	 * The Guides section as a table for SKILL.md. These are the pages an agent cannot
	 * guess from Common Lisp knowledge, so they are named in the skill body itself rather
	 * than left to the index.
	 */
	private static String buildGuidesTable(Nav nav) {
		StringBuilder md = new StringBuilder("| Topic | Guide |\n| --- | --- |\n");
		for (Nav.Section section : nav.sections()) {
			if (!section.title().equalsIgnoreCase("Guides")) {
				continue;
			}
			for (Nav.Page page : section.pages()) {
				md.append("| ").append(page.title()).append(" | `references/").append(page.file()).append("` |\n");
			}
		}
		return md.toString().stripTrailing();
	}

	private static String operatorCounts(List<Catalog> catalogs) {
		List<String> parts = new ArrayList<>();
		for (Catalog catalog : catalogs) {
			parts.add(catalog.flatEntries().size() + " " + kindTitle(catalog).toLowerCase());
		}
		return String.join(", ", parts);
	}

	/** {@code reference/special-forms} -> {@code Special forms}. */
	static String kindTitle(Catalog catalog) {
		String dir = catalog.baseDir();
		String leaf = dir.contains("/") ? dir.substring(dir.lastIndexOf('/') + 1) : dir;
		String words = leaf.replace('-', ' ');
		return Character.toUpperCase(words.charAt(0)) + words.substring(1);
	}

	// --- other outputs ----------------------------------------------------

	private String buildSingleFile(String skill, Map<String, String> references, String version) {
		StringBuilder md = new StringBuilder();
		md.append("<!-- rontolisp skill ").append(version).append(" -- ").append(this.siteBase).append(" -->\n\n");
		md.append(skill);
		for (Map.Entry<String, String> entry : references.entrySet()) {
			md.append("\n\n---\n\n# FILE: references/").append(entry.getKey()).append("\n\n").append(entry.getValue());
		}
		return md.toString();
	}

	/**
	 * The skill's own frontmatter description, collapsed to one line. Reused as the
	 * plugin's and the marketplace's description so that what the skill says it is for is
	 * stated once.
	 */
	static String frontmatterDescription(String skill) throws IOException {
		Matcher matcher = Pattern.compile("(?ms)^description:[ \t]*>?-?[ \t]*\r?\n(.*?)^\\w", Pattern.MULTILINE)
			.matcher(skill);
		if (!matcher.find()) {
			throw new IOException("The skill template no longer has a folded `description:` in its frontmatter");
		}
		String folded = matcher.group(1).replaceAll("\\s+", " ").trim();
		int stop = folded.indexOf(". ");
		return stop < 0 ? folded : folded.substring(0, stop + 1).trim();
	}

	private String pluginJson(String summary, String version) {
		return """
				{
				  "name": "%s",
				  "description": "%s",
				  "version": "%s",
				  "homepage": "%s/docs/en/getting-started/agent-skill.html"
				}
				""".formatted(SKILL_NAME, jsonEscape(summary), version, this.siteBase);
	}

	/**
	 * A marketplace served as one static file. Because it is added by URL, Claude Code
	 * downloads nothing but this JSON -- a relative plugin source would have no checkout
	 * to be relative to -- so the plugin is an absolute {@code archive} URL. That URL is
	 * deliberately unversioned: a client holding a stale copy of this file still
	 * resolves, and the version it then installs is the one inside the archive's
	 * {@code plugin.json}.
	 */
	private String marketplaceJson(String summary, String version) {
		return """
				{
				  "name": "%s",
				  "owner": {
				    "name": "%s"
				  },
				  "metadata": {
				    "description": "The rontolisp documentation, packaged for coding agents",
				    "version": "%s"
				  },
				  "plugins": [
				    {
				      "name": "%s",
				      "description": "%s",
				      "version": "%s",
				      "homepage": "%s/docs/en/getting-started/agent-skill.html",
				      "source": {
				        "source": "archive",
				        "url": "%s/skill/%s"
				      }
				    }
				  ]
				}
				""".formatted(SKILL_NAME, ownerName(), version, SKILL_NAME, jsonEscape(summary), version, this.siteBase,
				this.siteBase, PLUGIN_ZIP);
	}

	/** The site's GitHub user, read off the site URL rather than hard-coded twice. */
	private String ownerName() {
		Matcher matcher = Pattern.compile("https?://([^./]+)\\.github\\.io").matcher(this.siteBase);
		return matcher.find() ? matcher.group(1) : SKILL_NAME;
	}

	private static String jsonEscape(String text) {
		return text.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private String versionJson(String version, String digest) {
		return """
				{
				  "name": "%s",
				  "version": "%s",
				  "commit": "%s",
				  "contentHash": "%s",
				  "docs": "%s/docs/en/"
				}
				""".formatted(SKILL_NAME, version, this.commit, digest, this.siteBase);
	}

	/**
	 * The install page is the manual's own Agent Skill guide, rendered into the bundle's
	 * chrome: the instructions someone follows to install the skill exist once, as a
	 * documentation page, not once here and once there.
	 */
	private String renderInstallPage(Path langDir, String version) throws IOException {
		Path guide = langDir.resolve(INSTALL_GUIDE);
		if (!Files.exists(guide)) {
			throw new IOException("The install page needs " + guide);
		}
		// The page sits at /skill/, not among the docs, so what it links to has to
		// be named by URL rather than by neighbourhood.
		String markdown = mapLinks(Files.readString(guide, StandardCharsets.UTF_8), INSTALL_GUIDE,
				(inTree, resolved) -> inTree ? this.siteBase + "/docs/" + this.lang + "/" + asHtml(resolved)
						: siteUrl(resolved));
		MutableDataSet options = DocGen.markdownOptions();
		String body = HtmlRenderer.builder(options).build().render(Parser.builder(options).build().parse(markdown));
		return readResource(INDEX_HTML_RESOURCE).replace("{{version}}", version)
			.replace("{{site-base}}", this.siteBase)
			.replace("{{body}}", body);
	}

	// --- filesystem helpers ----------------------------------------------

	/** Reads every Markdown page under the language directory, keyed by relative path. */
	private static Map<String, String> readMarkdown(Path langDir) throws IOException {
		Map<String, String> pages = new LinkedHashMap<>();
		try (Stream<Path> files = Files.walk(langDir)) {
			files.filter(Files::isRegularFile)
				.filter(p -> p.getFileName().toString().endsWith(".md"))
				.sorted()
				.forEach(p -> {
					try {
						pages.put(langDir.relativize(p).toString().replace('\\', '/'),
								Files.readString(p, StandardCharsets.UTF_8));
					}
					catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				});
		}
		return pages;
	}

	private static String readResource(String name) throws IOException {
		try (InputStream in = SkillGen.class.getResourceAsStream(name)) {
			if (in == null) {
				throw new IOException("Missing classpath resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static void deleteRecursively(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(dir)) {
			paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.delete(p);
				}
				catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
		}
	}

	// --- archives ---------------------------------------------------------
	//
	// Both archives are written with a fixed timestamp so that regenerating an
	// unchanged bundle produces identical bytes: a client comparing checksums
	// should see movement only when the documentation actually moved.

	private static void writeZip(Path target, Map<String, byte[]> entries) throws IOException {
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				ZipEntry zipEntry = new ZipEntry(entry.getKey());
				zipEntry.setTime(0L);
				zip.putNextEntry(zipEntry);
				zip.write(entry.getValue());
				zip.closeEntry();
			}
		}
	}

	private static void writeTarGz(Path target, Map<String, byte[]> entries) throws IOException {
		ByteArrayOutputStream tar = new ByteArrayOutputStream();
		for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
			writeTarEntry(tar, entry.getKey(), entry.getValue());
		}
		tar.write(new byte[1024]); // two zero blocks terminate the archive
		try (OutputStream gzip = new GZIPOutputStream(Files.newOutputStream(target))) {
			gzip.write(tar.toByteArray());
		}
	}

	/** Writes one ustar header plus the padded file content. */
	private static void writeTarEntry(OutputStream out, String name, byte[] content) throws IOException {
		byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
		if (nameBytes.length > 100) {
			throw new IOException("Path too long for a ustar header (100 bytes max): " + name);
		}
		byte[] header = new byte[512];
		System.arraycopy(nameBytes, 0, header, 0, nameBytes.length);
		putField(header, 100, 8, "0000644");
		putField(header, 108, 8, "0000000");
		putField(header, 116, 8, "0000000");
		putField(header, 124, 12, String.format("%011o", content.length));
		putField(header, 136, 12, "00000000000");
		header[156] = '0'; // regular file
		System.arraycopy("ustar 00".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 8);
		// The checksum is computed with its own field read as spaces.
		java.util.Arrays.fill(header, 148, 156, (byte) ' ');
		int checksum = 0;
		for (byte b : header) {
			checksum += b & 0xff;
		}
		putField(header, 148, 8, String.format("%06o", checksum));
		header[154] = 0;
		header[155] = ' ';
		out.write(header);
		out.write(content);
		int padding = (512 - (content.length % 512)) % 512;
		out.write(new byte[padding]);
	}

	private static void putField(byte[] header, int offset, int length, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length - 1));
	}

}
