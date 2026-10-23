package am.ik.rontolisp.docgen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Generates the skill bundle from the real {@code doc/} tree and checks it holds
 * together. The link assertion is the point: the skill is a view of the documentation, so
 * a page that is renamed or removed must break this build rather than ship a bundle that
 * points at nothing.
 */
class SkillGenTest {

	private static final Path DOC = Path.of("..", "doc");

	private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]*)]\\(([^)\\s]+)\\)");

	@TempDir
	static Path out;

	static Path skillDir;

	static String skill;

	private static final Path EXAMPLES = Path.of("..", "examples");

	/** A hand-written skill published beside the generated one. */
	private static final Path LISPRESEN_SKILL = Path.of("..", "lispresen", "skills", "lispresen");

	private static SkillGen generator() {
		return new SkillGen(DOC, out, "en", "1.2.3", "cafe123", "https://example.test/rontolisp", EXAMPLES,
				"https://repo.test/blob/develop");
	}

	@BeforeAll
	static void generate() throws IOException {
		assumeTrue(Files.isDirectory(DOC), "run from the docs-tool module, next to doc/");
		generator().plugin(LISPRESEN_SKILL, "4.5.6").generate();
		skillDir = out.resolve(SkillGen.SKILL_NAME);
		skill = Files.readString(skillDir.resolve("SKILL.md"), StandardCharsets.UTF_8);
	}

	@Test
	void frontmatterCarriesTheTriggeringMetadata() {
		assertThat(skill).startsWith("---\n");
		String frontmatter = skill.substring(4, skill.indexOf("\n---", 4));
		assertThat(frontmatter).contains("name: " + SkillGen.SKILL_NAME).contains("version: 1.2.3");
		// The description is what decides whether the skill is consulted at all.
		int description = frontmatter.indexOf("description:");
		assertThat(description).isNotNegative();
		assertThat(frontmatter.substring(description).length()).isGreaterThan(200);
	}

	@Test
	void bodyStaysWithinTheProgressiveDisclosureBudget() {
		// Everything past the routing prose belongs in references/, which costs
		// nothing until it is read.
		assertThat(skill.lines().count()).isLessThan(500);
	}

	@Test
	void everyPlaceholderIsSubstituted() {
		assertThat(skill).doesNotContain("{{").contains("Skill version 1.2.3");
	}

	@Test
	void theDeltaPageIsInlinedAndItsLinksRetargeted() {
		assertThat(skill).contains("## Unsupported Common Lisp Features")
			.contains("(references/reference/special-forms.md)")
			.doesNotContain("(../reference/");
	}

	@Test
	void everyRelativeLinkResolvesInsideTheBundle() throws IOException {
		List<String> broken = new ArrayList<>();
		collectBrokenLinks(skillDir.resolve("SKILL.md"), broken);
		try (Stream<Path> files = Files.walk(skillDir.resolve("references"))) {
			for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".md")).toList()) {
				collectBrokenLinks(file, broken);
			}
		}
		assertThat(broken).isEmpty();
	}

	@Test
	void theOperatorIndexListsEveryCatalogEntry() throws IOException {
		List<Catalog> catalogs = Catalog.discover(DOC.resolve("en"));
		String operators = Files.readString(skillDir.resolve("references/operators.md"), StandardCharsets.UTF_8);
		int expected = 0;
		for (Catalog catalog : catalogs) {
			for (Catalog.Entry entry : catalog.flatEntries()) {
				expected++;
				assertThat(operators).as("%s is indexed", entry.name())
					.contains("(" + catalog.baseDir() + "/" + entry.slug() + ".md)");
			}
		}
		assertThat(expected).isGreaterThan(600);
	}

	@Test
	void versionIsPublishedWhereAClientCanPollIt() throws IOException {
		assertThat(Files.readString(out.resolve("VERSION"), StandardCharsets.UTF_8)).isEqualTo("1.2.3\n");
		assertThat(Files.readString(out.resolve("version.json"), StandardCharsets.UTF_8))
			.contains("\"version\": \"1.2.3\"")
			.contains("\"commit\": \"cafe123\"");
		assertThat(Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8)).contains("1.2.3")
			.doesNotContain("{{");
	}

	@Test
	void theInstallPageIsTheManualsOwnGuideRendered() throws IOException {
		// One set of install instructions, written as a documentation page.
		String guide = Files.readString(DOC.resolve("en").resolve(SkillGen.INSTALL_GUIDE), StandardCharsets.UTF_8);
		String installCommand = guide.lines()
			.filter(line -> line.contains("tar xz -C ~/.claude/skills"))
			.findFirst()
			.orElseThrow(() -> new AssertionError("the guide no longer shows a Claude Code install command"));
		String page = Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8);
		assertThat(page).contains(installCommand.strip()).contains("<h1");
		// It is served from /skill/, so its links into the manual must be URLs.
		assertThat(page).contains("https://example.test/rontolisp/docs/en/guides/missing-features.html")
			.doesNotContain("href=\"missing-features.md\"");
	}

	@Test
	void anUnchangedBundleKeepsItsVersion(@TempDir Path second) throws IOException {
		String published = Files.readString(out.resolve("version.json"), StandardCharsets.UTF_8);
		String hash = published.replaceAll("(?s).*\"contentHash\": \"([^\"]+)\".*", "$1");
		assertThat(hash).hasSize(64);
		// A later commit offers a higher version; identical content declines it, so
		// every installed copy is spared a re-download that changes nothing.
		new SkillGen(DOC, second, "en", "9.9.9", "beef456", "https://example.test/rontolisp", EXAMPLES,
				"https://repo.test/blob/develop", "1.2.3", hash)
			.generate();
		assertThat(Files.readString(second.resolve("VERSION"), StandardCharsets.UTF_8)).isEqualTo("1.2.3\n");
		assertThat(Files.readString(second.resolve("version.json"), StandardCharsets.UTF_8)).contains(hash);
	}

	@Test
	void aChangedBundleTakesTheNewVersion(@TempDir Path second) throws IOException {
		new SkillGen(DOC, second, "en", "9.9.9", "beef456", "https://example.test/rontolisp", EXAMPLES,
				"https://repo.test/blob/develop", "1.2.3", "0".repeat(64))
			.generate();
		assertThat(Files.readString(second.resolve("VERSION"), StandardCharsets.UTF_8)).isEqualTo("9.9.9\n");
	}

	@Test
	void bothArchivesCarryTheWholeBundle() throws IOException {
		try (ZipFile zip = new ZipFile(out.resolve(SkillGen.SKILL_NAME + ".skill").toFile())) {
			assertThat(zip.getEntry(SkillGen.SKILL_NAME + "/SKILL.md")).isNotNull();
			assertThat(zip.getEntry(SkillGen.SKILL_NAME + "/references/operators.md")).isNotNull();
		}
		List<String> names = tarEntryNames(out.resolve(SkillGen.SKILL_NAME + "-skill.tar.gz"));
		assertThat(names).contains(SkillGen.SKILL_NAME + "/SKILL.md",
				SkillGen.SKILL_NAME + "/references/guides/missing-features.md");
		try (Stream<Path> files = Files.walk(skillDir)) {
			assertThat(names).hasSize((int) files.filter(Files::isRegularFile).count());
		}
	}

	@Test
	void generatedPagesDoNotCollideWithDocumentationPages() throws IOException {
		// A case-insensitive filesystem makes a generated INDEX.md eat the
		// documentation's own index.md, so the two name spaces must stay apart.
		List<String> lowercased = new ArrayList<>();
		try (Stream<Path> files = Files.walk(skillDir.resolve("references"))) {
			files.filter(Files::isRegularFile)
				.forEach(p -> lowercased.add(skillDir.relativize(p).toString().toLowerCase()));
		}
		assertThat(lowercased).doesNotHaveDuplicates()
			.contains("references/" + SkillGen.CONTENTS_PAGE, "references/" + SkillGen.OPERATORS_PAGE,
					"references/index.md");
	}

	@Test
	void aLinkInsideACodeSpanIsNotALink() {
		String js = "reach it with `ex['in-range'](...)` instead";
		assertThat(generator().retargetLinks(js, "guides/wasm-browser.md")).isEqualTo(js);
		// ...but a code span inside a link LABEL must still be retargeted
		assertThat(generator().retargetLinks("[`car`](../reference/functions/car.md)", "guides/x.md"))
			.isEqualTo("[`car`](references/reference/functions/car.md)");
	}

	@Test
	@SuppressWarnings("unchecked")
	void thePluginArchiveHasTheLayoutClaudeCodeExpects() throws IOException {
		try (ZipFile zip = new ZipFile(out.resolve(SkillGen.PLUGIN_ZIP).toFile())) {
			assertThat(zip.getEntry("skills/" + SkillGen.SKILL_NAME + "/SKILL.md")).isNotNull();
			assertThat(zip.getEntry("skills/" + SkillGen.SKILL_NAME + "/references/operators.md")).isNotNull();
			var manifest = zip.getEntry(".claude-plugin/plugin.json");
			assertThat(manifest).isNotNull();
			try (InputStream in = zip.getInputStream(manifest)) {
				Map<String, Object> plugin = new org.yaml.snakeyaml.Yaml().load(in);
				assertThat(plugin).containsEntry("name", SkillGen.SKILL_NAME).containsEntry("version", "1.2.3");
				assertThat((String) plugin.get("description")).isNotBlank();
			}
		}
		// A --plugin skill directory goes in whole, as it is.
		try (ZipFile zip = new ZipFile(out.resolve("lispresen-plugin.zip").toFile());
				Stream<Path> files = Files.walk(LISPRESEN_SKILL)) {
			List<Path> sources = files.filter(Files::isRegularFile).toList();
			assertThat(sources).hasSizeGreaterThan(1);
			for (Path file : sources) {
				String entry = "skills/lispresen/" + LISPRESEN_SKILL.relativize(file).toString().replace('\\', '/');
				try (InputStream in = zip.getInputStream(zip.getEntry(entry))) {
					assertThat(in.readAllBytes()).as(entry).isEqualTo(Files.readAllBytes(file));
				}
			}
			assertThat(zip.size()).isEqualTo(sources.size() + 1);
			try (InputStream in = zip.getInputStream(zip.getEntry(".claude-plugin/plugin.json"))) {
				Map<String, Object> plugin = new org.yaml.snakeyaml.Yaml().load(in);
				assertThat(plugin).containsEntry("name", "lispresen")
					.containsEntry("version", "4.5.6")
					.containsEntry("homepage", "https://repo.test/blob/develop/lispresen/skills/lispresen");
			}
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void theMarketplaceIsSelfContainedBecauseItIsAddedByUrl() throws IOException {
		String json = Files.readString(out.resolve(SkillGen.MARKETPLACE_JSON), StandardCharsets.UTF_8);
		Map<String, Object> marketplace = new org.yaml.snakeyaml.Yaml().load(json);
		assertThat(marketplace).containsEntry("name", SkillGen.SKILL_NAME);
		List<Map<String, Object>> plugins = (List<Map<String, Object>>) marketplace.get("plugins");
		assertThat(plugins).extracting(entry -> entry.get("name"), entry -> entry.get("version"))
			.containsExactly(org.assertj.core.groups.Tuple.tuple(SkillGen.SKILL_NAME, "1.2.3"),
					org.assertj.core.groups.Tuple.tuple("lispresen", "4.5.6"));
		// Claude Code downloads nothing but this file when the marketplace is added
		// by URL, so a relative source would have no checkout to resolve against.
		for (Map<String, Object> entry : plugins) {
			assertThat(entry.get("source")).isInstanceOf(Map.class);
			Map<String, Object> archive = (Map<String, Object>) entry.get("source");
			assertThat(archive).containsEntry("source", "archive")
				.containsEntry("url", "https://example.test/rontolisp/skill/" + entry.get("name") + "-plugin.zip");
			assertThat(out.resolve(entry.get("name") + "-plugin.zip")).exists();
			assertThat((String) entry.get("description")).isNotBlank().endsWith(".");
		}
	}

	@Test
	void thePluginDescriptionIsTheSkillsOwn() throws IOException {
		String summary = SkillGen.frontmatterDescription("""
				---
				name: x
				description: >-
				  First sentence about it. Second sentence,
				  wrapped over two lines.
				---

				body
				""");
		assertThat(summary).isEqualTo("First sentence about it.");
		assertThat(SkillGen.frontmatterDescription("---\nname: x\ndescription: Make (a ...) b. Then c.\n---\n"))
			.isEqualTo("Make (a ...) b.");
	}

	@Test
	void theExamplesAreMirroredWithoutTheirBuildOutput() throws IOException {
		Path examples = skillDir.resolve("references/" + SkillGen.EXAMPLES_DIR);
		try (Stream<Path> files = Files.walk(examples)) {
			List<String> names = files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).toList();
			assertThat(names).isNotEmpty()
				.as("a compiled artifact is unreadable text, so it stays out")
				.noneMatch(name -> name.endsWith(".wasm") || name.endsWith(".bin"));
		}
		// Spot-check that whole programs really are there to read.
		assertThat(examples.resolve("asdf/README.md")).exists();
		String index = Files.readString(skillDir.resolve("references/" + SkillGen.EXAMPLES_PAGE),
				StandardCharsets.UTF_8);
		assertThat(index).contains("`" + SkillGen.EXAMPLES_DIR + "/asdf/`");
	}

	@Test
	void aFileTheBundleLeavesOutIsLinkedInTheRepository() {
		SkillGen generator = generator();
		// `.wasm` is not mirrored, so its link has to say where the file lives.
		assertThat(generator.repoLinksForMissingFiles("run [it](hello.wasm) and [read](hello.lisp)", "hello/README.md",
				java.util.Set.of("hello/README.md", "hello/hello.lisp")))
			.isEqualTo("run [it](https://repo.test/blob/develop/examples/hello/hello.wasm) and [read](hello.lisp)");
	}

	@Test
	void buildOutputAndBinariesAreNotReadableExampleFiles() {
		assertThat(SkillGen.isReadableExampleFile("hello/hello.lisp")).isTrue();
		assertThat(SkillGen.isReadableExampleFile("hello/README.md")).isTrue();
		// the other source languages' programs and a Clojure project's files
		assertThat(SkillGen.isReadableExampleFile("scheme/queens.scm")).isTrue();
		assertThat(SkillGen.isReadableExampleFile("clojure/demo.clj")).isTrue();
		assertThat(SkillGen.isReadableExampleFile("clojure/portable.cljc")).isTrue();
		assertThat(SkillGen.isReadableExampleFile("clojure/deps-project/deps.edn")).isTrue();
		assertThat(SkillGen.isReadableExampleFile("hello/hello.wasm")).isFalse();
		assertThat(SkillGen.isReadableExampleFile("ch07/params.bin")).isFalse();
		assertThat(SkillGen.isReadableExampleFile("web/node_modules/x/index.js")).isFalse();
		assertThat(SkillGen.isReadableExampleFile("app/target/classes/A.lisp")).isFalse();
		assertThat(SkillGen.isReadableExampleFile("hello/.gitignore")).isFalse();
	}

	@Test
	void theSingleFileDigestHoldsEveryReference() throws IOException {
		String full = Files.readString(out.resolve(SkillGen.SKILL_NAME + "-full.md"), StandardCharsets.UTF_8);
		assertThat(full).contains("# FILE: references/operators.md")
			.contains("# FILE: references/reference/functions/car.md");
	}

	@Test
	void linksAreRetargetedRelativeToTheBundleRoot() {
		String retargeted = generator().retargetLinks(
				"see [values](../reference/functions/values.md#x) and [http](https://x/y.md)",
				"guides/missing-features.md");
		assertThat(retargeted).contains("(references/reference/functions/values.md#x)").contains("(https://x/y.md)");
	}

	@Test
	void aLinkOutOfTheDocumentationTreeBecomesTheUrlItMeans() {
		// The bundle has no playground.html; on the site it lives above docs/en/.
		assertThat(generator().siteLinksForMirroredPage("[try it](../../playground.html)", "index.md"))
			.isEqualTo("[try it](https://example.test/rontolisp/playground.html)");
		assertThat(generator().siteLinksForMirroredPage("[repl](getting-started/repl.md)", "index.md"))
			.isEqualTo("[repl](getting-started/repl.md)");
	}

	@Test
	void headingsAreDemotedSoTheIncludedPageNestsUnderTheSkill() {
		assertThat(SkillGen.demoteHeadings("# Title\n\ntext\n\n## Section\n"))
			.isEqualTo("## Title\n\ntext\n\n### Section\n");
	}

	private static void collectBrokenLinks(Path file, List<String> broken) throws IOException {
		Path dir = file.getParent();
		String markdown = Files.readString(file, StandardCharsets.UTF_8);
		boolean[] code = SkillGen.codeMask(markdown);
		Matcher matcher = MD_LINK.matcher(markdown);
		while (matcher.find()) {
			String target = matcher.group(2);
			if (code[matcher.start(2)] || target.startsWith("#") || target.contains("://")
					|| target.startsWith("mailto:")) {
				continue;
			}
			int hash = target.indexOf('#');
			String relative = hash < 0 ? target : target.substring(0, hash);
			if (relative.isEmpty()) {
				continue;
			}
			if (!Files.exists(dir.resolve(relative).normalize())) {
				broken.add(file.getFileName() + " -> " + target);
			}
		}
	}

	private static List<String> tarEntryNames(Path tarGz) throws IOException {
		List<String> names = new ArrayList<>();
		try (InputStream in = new GZIPInputStream(Files.newInputStream(tarGz))) {
			byte[] header = new byte[512];
			while (in.readNBytes(header, 0, 512) == 512) {
				if (header[0] == 0) {
					break;
				}
				int end = 0;
				while (end < 100 && header[end] != 0) {
					end++;
				}
				names.add(new String(header, 0, end, StandardCharsets.UTF_8));
				long size = Long.parseLong(new String(header, 124, 11, StandardCharsets.US_ASCII).trim(), 8);
				in.skipNBytes(size + (512 - (size % 512)) % 512);
			}
		}
		return names;
	}

}
