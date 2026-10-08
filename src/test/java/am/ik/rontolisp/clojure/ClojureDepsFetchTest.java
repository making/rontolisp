package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Maven and git coordinates fetched through repositories, here held in memory: every
 * expected source path is the oracle's classpath ({@code clj -Srepro -Spath}, {@code clj}
 * 1.12.6, measured 2026-10-08 over a {@code file:} Maven repository and {@code file://}
 * git repositories of the same shape), its jars and directories spelled as this front
 * end's roots, and every refusal the oracle's words. The built-in
 * {@code org.clojure/clojure} contributes nothing, so the oracle's clojure and spec jars
 * are absent from each expected path.
 */
class ClojureDepsFetchTest {

	private static final String GITLIB = "file:///git/gitlib";

	private static final String C1 = "cb1b0fe5ee6c4a3dc19e70e0e2da7f939905c5e0";

	private static final String C2 = "f5d1cc2a972021c4e321ba72479824e95b285a08";

	private static final String OTHER = "0eab1e8837f687e162dcfb7087b653872be7cf7d";

	@Test
	void aMavenCoordinateBringsItsCompileAndRuntimeDependenciesAndTheirJars() {
		Fixture fixture = new Fixture();
		MemoryClojureFiles files = fixture.files("{:deps {fixture/clj-lib {:mvn/version \"1.0\"}}}");
		assertThat(roots(files)).isEqualTo(
				": ., src, " + jar("clj-lib", "1.0") + ", " + jar("java-lib", "1.0") + ", " + jar("rt-lib", "1.0"));
		// the optional, test and provided dependencies are never asked of the repository
		assertThat(fixture.repositories.asked).noneMatch(question -> question.contains("opt-lib")
				|| question.contains("test-lib") || question.contains("prov-lib"));
		// only the jar holding classes joins the Java class path, with its coordinates
		assertThat(files.javaClassPath).containsExactly(jar("java-lib", "1.0") + " fixture:java-lib:1.0");
	}

	@Test
	void exclusionsExtensionsAndClassifiersAreTheOracles() {
		Fixture fixture = new Fixture();
		assertThat(roots(fixture.files("{:deps {fixture/excl-parent {:mvn/version \"1.0\"}}}"))).isEqualTo(
				": ., src, " + jar("excl-parent", "1.0") + ", " + jar("clj-lib", "1.0") + ", " + jar("rt-lib", "1.0"));
		// an exclusion of every library is no library's name
		assertThat(roots(fixture.files("{:deps {fixture/wild {:mvn/version \"1.0\"}}}")))
			.isEqualTo(": ., src, " + jar("wild", "1.0") + ", " + jar("clj-lib", "1.0") + ", " + jar("java-lib", "1.0")
					+ ", " + jar("rt-lib", "1.0"));
		// a pom-typed dependency brings no jar, only its own dependencies
		assertThat(roots(fixture.files("{:deps {fixture/uses-agg {:mvn/version \"1.0\"}}}")))
			.isEqualTo(": ., src, " + jar("uses-agg", "1.0") + ", " + jar("agg-member", "1.0"));
		assertThat(roots(fixture.files("{:deps {fixture/pom-agg {:mvn/version \"1.0\" :extension \"pom\"}}}")))
			.isEqualTo(": ., src, " + jar("agg-member", "1.0"));
		// a classifier, written or implied by a type, names the classified jar
		assertThat(roots(fixture.files("{:deps {fixture/uses-cls {:mvn/version \"1.0\"}}}")))
			.isEqualTo(": ., src, " + jar("uses-cls", "1.0") + ", m2/fixture/cls/1.0/cls-1.0-natives.jar");
		assertThat(roots(fixture.files("{:deps {fixture/cls$natives {:mvn/version \"1.0\"}}}")))
			.isEqualTo(": ., src, m2/fixture/cls/1.0/cls-1.0-natives.jar");
		assertThat(roots(fixture.files("{:deps {fixture/testjar-user {:mvn/version \"1.0\"}}}")))
			.isEqualTo(": ., src, " + jar("testjar-user", "1.0") + ", m2/fixture/rt-lib/1.0/rt-lib-1.0-tests.jar");
		// [1.0] names one version
		assertThat(roots(fixture.files("{:deps {fixture/rt-lib {:mvn/version \"[1.0]\"}}}")))
			.isEqualTo(": ., src, " + jar("rt-lib", "1.0"));
	}

	@Test
	void aMavenCoordinateTheOracleRefusesIsRefusedInItsWords() {
		Fixture fixture = new Fixture();
		assertThatThrownBy(
				() -> roots(fixture.files("{:deps {fixture/cls {:mvn/version \"1.0\" :classifier \"natives\"}}}")))
			.isInstanceOf(LispReadException.class)
			.hasMessage("Invalid library spec:\n  fixture/cls {:mvn/version \"1.0\", :classifier \"natives\"}\n"
					+ ":classifier in Maven coordinates is no longer supported.\n"
					+ "Use groupId/artifactId$classifier in lib names instead.");
		assertThatThrownBy(() -> roots(fixture.files("{:deps {fixture/nope {:mvn/version \"1.0\"}}}")))
			.isInstanceOf(LispReadException.class)
			.hasMessage("fixture:nope:jar:1.0 is in neither the local repository nor any of: fixture (file:/repo/)");
		// a version range needs the repositories' metadata, which this host reads none of
		assertThatThrownBy(() -> roots(fixture.files("{:deps {fixture/rt-lib {:mvn/version \"[1.0,2.0)\"}}}")))
			.isInstanceOf(LispReadException.class)
			.hasMessage("fixture:rt-lib:jar:[1.0,2.0): no metadata");
	}

	@Test
	void aLocalJarsOwnPomDeclaresItsDependenciesAsAProjectModel() {
		Fixture fixture = new Fixture();
		// compile and runtime ones, optional ones kept; a type implies no classifier
		assertThat(roots(fixture.files("{:deps {fixture/localjar {:local/root \"localjar.jar\"}}}")))
			.isEqualTo(": ., src, localjar.jar, " + jar("java-lib", "1.0") + ", " + jar("opt-lib", "1.0") + ", "
					+ jar("rt-lib", "1.0"));
	}

	@Test
	void aGitCoordinateIsCheckedOutAndReadLikeALocalRoot() {
		Fixture fixture = new Fixture();
		assertThat(roots(fixture.files("{:deps {my/gitlib {:git/url \"" + GITLIB + "\" :git/sha \"" + C2 + "\"}}}")))
			.isEqualTo(": ., src, gitlibs/gitlib/" + C2 + "/src, " + jar("rt-lib", "1.0"));
		assertThat(roots(fixture
			.files("{:deps {my/gitlib {:git/url \"" + GITLIB + "\" :git/tag \"v1\" :git/sha \"" + C1 + "\"}}}")))
			.isEqualTo(": ., src, gitlibs/gitlib/" + C1 + "/src, " + jar("rt-lib", "1.0"));
		// an abbreviated sha resolves through its tag
		ClojureSourcePath prefixed = new ClojureSourcePath(fixture.files("{:deps {my/gitlib {:git/url \"" + GITLIB
				+ "\" :git/tag \"v2\" :git/sha \"" + C2.substring(0, 7) + "\"}}}"), null);
		assertThat(prefixed.describeRoots())
			.isEqualTo(": ., src, gitlibs/gitlib/" + C2 + "/src, " + jar("rt-lib", "1.0"));
		// a :deps/root below the checkout
		assertThat(roots(fixture.files("{:deps {my/mono {:git/url \"file:///git/gitmono\" :git/sha \"" + fixture.mono
				+ "\" :deps/root \"mod\"}}}")))
			.isEqualTo(": ., src, gitlibs/gitmono/" + fixture.mono + "/mod/src");
	}

	@Test
	void aPomXmlProjectBringsItsSourceDirectoriesAndItsModelsDependencies() {
		Fixture fixture = new Fixture();
		String deps = "{:deps {my/gitpom {:git/url \"file:///git/gitpom\" :git/sha \"" + fixture.pom + "\"}}}";
		String checkout = "gitlibs/gitpom/" + fixture.pom;
		// the source directory, src/main/clojure, the resources, then what the first
		// plugin's add-source and add-resource executions configure (a value-less element
		// left out, an empty one the root itself), each once; the compile and runtime
		// dependencies, optional ones too
		assertThat(roots(fixture.files(deps))).isEqualTo(": ., src, " + checkout + "/src/main/java, " + checkout
				+ "/src/main/clojure, " + checkout + "/src/main/resources, " + checkout + "/extra, " + checkout + ", "
				+ checkout + "/res, " + jar("opt-lib", "1.0") + ", " + jar("rt-lib", "1.0"));
		assertThat(fixture.repositories.asked).containsOnlyOnce("project " + checkout + "/pom.xml");
		// the plugin's directories come off the first plugin only, here no build helper
		fixture.repositories.pomProjects.put(checkout + "/pom.xml",
				new ClojureRepositories.PomProject(List.of(), "src/main/java", List.of(),
						List.of(new ClojureRepositories.PomPlugin("x", "other", List.of()), BUILD_HELPER)));
		assertThat(roots(fixture.files(deps)))
			.isEqualTo(": ., src, " + checkout + "/src/main/java, " + checkout + "/src/main/clojure");
		// a project the resolver cannot build is refused in its words
		fixture.repositories.pomProjects.remove(checkout + "/pom.xml");
		assertRefused(fixture, deps,
				checkout + "/pom.xml: Non-resolvable parent POM g:p:1 for g:m:1: no repository has it");
		// where nothing is fetched it is not read, and says so when a lookup misses
		Map<String, String> files = new LinkedHashMap<>(fixture.files);
		files.put("deps.edn",
				deps.replace("my/gitpom {:git/url \"file:///git/gitpom\" :git/sha \"" + fixture.pom + "\"}",
						"my/pom {:local/root \"" + checkout + "\"}"));
		assertThat(roots(new MemoryClojureFiles(files, fixture.archives, null)))
			.isEqualTo(": ., src; not searched: my/pom " + checkout + " (a pom.xml project, not read)");
	}

	/**
	 * A {@code build-helper-maven-plugin} adding sources and resources, and test sources
	 * no classpath reads.
	 */
	private static final ClojureRepositories.PomPlugin BUILD_HELPER = new ClojureRepositories.PomPlugin(
			"org.codehaus.mojo", "build-helper-maven-plugin", List.of(
					new ClojureRepositories.PomExecution(List.of("add-source"),
							configuration("sources", value("source", "extra"), value("source", null),
									value("source", ""), value("source", "src/main/java"))),
					new ClojureRepositories.PomExecution(List.of("add-resource"),
							configuration("resources",
									new ClojureRepositories.PomConfiguration("resource", null,
											List.of(value("directory", "nested"))),
									value("resource", "res"))),
					new ClojureRepositories.PomExecution(List.of("add-test-source"),
							configuration("sources", value("source", "test-extra")))));

	private static ClojureRepositories.PomConfiguration configuration(String list,
			ClojureRepositories.PomConfiguration... items) {
		return new ClojureRepositories.PomConfiguration("configuration", null,
				List.of(new ClojureRepositories.PomConfiguration(list, null, List.of(items))));
	}

	private static ClojureRepositories.PomConfiguration value(String name, @Nullable String value) {
		return new ClojureRepositories.PomConfiguration(name, value, List.of());
	}

	@Test
	void aGitCoordinateTheOracleRefusesIsRefusedInItsWords() {
		Fixture fixture = new Fixture();
		assertRefused(fixture,
				"{:deps {my/gitlib {:git/url \"" + GITLIB + "\" :git/tag \"v9\" :git/sha \"" + C1 + "\"}}}",
				"Library my/gitlib has invalid tag: v9");
		assertRefused(fixture,
				"{:deps {my/gitlib {:git/url \"" + GITLIB + "\" :git/tag \"v1\" :git/sha \"" + C2 + "\"}}}",
				"Library my/gitlib has sha and tag that point to different commits");
		assertRefused(fixture, "{:deps {my/gitlib {:git/url \"" + GITLIB + "\" :git/tag \"v1\"}}}",
				"Library my/gitlib has coord with missing sha");
		String absent = "0123456789012345678901234567890123456789";
		assertRefused(fixture, "{:deps {my/gitlib {:git/url \"" + GITLIB + "\" :git/sha \"" + absent + "\"}}}",
				"Commit not found for my/gitlib in repo " + GITLIB + " at " + absent);
		assertRefused(fixture,
				"{:deps {my/gitnone {:git/url \"file:///git/gitnone\" :git/sha \"" + fixture.none + "\"}}}",
				"Manifest file not found for my/gitnone in coordinate #:git{:url \"file:///git/gitnone\", :sha \""
						+ fixture.none + "\"}");
	}

	@Test
	void ofTwoCommitsTheDescendantWinsAndUnrelatedOnesAreRefused() {
		Fixture fixture = new Fixture();
		fixture.files.put("l1/deps.edn", "{:deps {my/gitlib {:git/url \"" + GITLIB + "\" :git/sha \"" + C1 + "\"}}}");
		fixture.files.put("l2/deps.edn", "{:deps {my/gitlib {:git/url \"" + GITLIB + "\" :git/sha \"" + C2 + "\"}}}");
		fixture.files.put("l3/deps.edn",
				"{:deps {my/gitlib {:git/url \"" + GITLIB + "\" :git/sha \"" + OTHER + "\"}}}");
		String newest = ": ., src, l1/src, l2/src, gitlibs/gitlib/" + C2 + "/src, " + jar("rt-lib", "1.0");
		assertThat(roots(fixture.files("{:deps {my/l1 {:local/root \"l1\"} my/l2 {:local/root \"l2\"}}}")))
			.isEqualTo(newest);
		assertThat(roots(fixture.files("{:deps {my/l2 {:local/root \"l2\"} my/l1 {:local/root \"l1\"}}}")))
			.isEqualTo(newest);
		// the oracle throws an exception without a message here; its message for an
		// unknown relationship names both commits
		assertRefused(fixture, "{:deps {my/l2 {:local/root \"l2\"} my/l3 {:local/root \"l3\"}}}",
				"No known ancestor relationship between git versions for my/gitlib\n  " + GITLIB + " at " + OTHER
						+ "\n  " + GITLIB + " at " + C2);
	}

	@Test
	void theProjectResolvesBeforeTheProgramLowersAndItsNamespacesLoad() {
		Fixture fixture = new Fixture();
		MemoryClojureFiles files = fixture.files("{:deps {fixture/clj-lib {:mvn/version \"1.0\"} my/gitlib "
				+ "{:git/url \"" + GITLIB + "\" :git/sha \"" + C2 + "\"}}}");
		// a program requiring nothing still puts the jars holding classes on the class
		// path
		Clojure.read("(println 1)", "main.clj", null, files);
		assertThat(files.javaClassPath).containsExactly(jar("java-lib", "1.0") + " fixture:java-lib:1.0");
		List<String> forms = Clojure
			.read("(require '[fixture.clj-lib :as c] '[gitlib.core :as g]) (println (c/hello) g/version)", "main.clj",
					null, fixture.files("{:deps {fixture/clj-lib {:mvn/version \"1.0\"} my/gitlib {:git/url \"" + GITLIB
							+ "\" :git/sha \"" + C2 + "\"}}}"))
			.stream()
			.map(LispVal::print)
			.toList();
		assertThat(forms).anyMatch(form -> form.contains("|c%fixture.clj-lib/hello|"))
			.anyMatch(form -> form.contains("|c%gitlib.core/version|"));
		// a refusal while resolving names the program's first form
		assertThatThrownBy(() -> Clojure.read("(ns app)\n(println 1)", "main.clj", null,
				fixture.files("{:deps {fixture/nope {:mvn/version \"1.0\"}}}")))
			.isInstanceOf(LispReadException.class)
			.hasMessageStartingWith("main.clj:1:1: fixture:nope:jar:1.0 is in neither");
	}

	private static void assertRefused(Fixture fixture, String deps, String refusal) {
		assertThatThrownBy(() -> roots(fixture.files(deps))).isInstanceOf(LispReadException.class).hasMessage(refusal);
	}

	private static String roots(MemoryClojureFiles files) {
		return new ClojureSourcePath(files, null).describeRoots();
	}

	private static String jar(String artifact, String version) {
		return "m2/fixture/" + artifact + "/" + version + "/" + artifact + "-" + version + ".jar";
	}

	private static ClojureRepositories.MavenDependency dep(String coordinates, String scope, boolean optional,
			String... exclusions) {
		String[] parts = coordinates.split(":");
		ClojureRepositories.MavenArtifact artifact = parts.length == 3
				? new ClojureRepositories.MavenArtifact(parts[0], parts[1], "", "jar", parts[2])
				: new ClojureRepositories.MavenArtifact(parts[0], parts[1], parts[3], parts[2], parts[4]);
		return new ClojureRepositories.MavenDependency(artifact, scope, optional, List.of(exclusions));
	}

	private static ClojureRepositories.MavenDependency dep(String coordinates) {
		return dep(coordinates, "compile", false);
	}

	/**
	 * The fixture repositories the oracle resolved, in memory: the POMs and jars of a
	 * {@code file:} Maven repository and the commits of three git repositories.
	 */
	private static final class Fixture {

		final Map<String, String> files = new LinkedHashMap<>();

		final Map<String, Map<String, String>> archives = new LinkedHashMap<>();

		final MemoryRepositories repositories = new MemoryRepositories();

		final String mono = "41cb4bb7c0879205127022111037ee7f561d7e6c";

		final String pom = "ea2788db262fc955f03442d11598359f836a18c7";

		final String none = "59e63ac0d7e55426692d2811629fb4f27f612ad2";

		Fixture() {
			publish("fixture:java-lib:1.0", List.of(), "fixture/javalib/Greeter.class");
			publish("fixture:clj-lib:1.0",
					List.of(dep("fixture:java-lib:1.0"), dep("fixture:opt-lib:1.0", "compile", true),
							dep("fixture:test-lib:1.0", "test", false), dep("fixture:rt-lib:1.0", "runtime", false),
							dep("fixture:prov-lib:1.0", "provided", false)),
					"fixture/clj_lib.clj", "(ns fixture.clj-lib) (defn hello [] \"clj-lib\")");
			for (String lib : List.of("opt-lib", "rt-lib", "agg-member", "uses-agg", "uses-cls", "testjar-user")) {
				publish("fixture:" + lib + ":1.0", List.of(), "fixture/" + lib.replace('-', '_') + ".clj");
			}
			publish("fixture:excl-parent:1.0",
					List.of(dep("fixture:clj-lib:1.0", "compile", false, "fixture/java-lib")),
					"fixture/excl_parent.clj");
			publish("fixture:wild:1.0", List.of(dep("fixture:clj-lib:1.0", "compile", false, "*/*")),
					"fixture/wild.clj");
			this.repositories.poms.put("fixture:pom-agg:pom:1.0", List.of(dep("fixture:agg-member:1.0")));
			this.repositories.poms.put("fixture:uses-agg:jar:1.0", List.of(dep("fixture:pom-agg:pom::1.0")));
			publishJar("fixture:cls:jar:natives:1.0", "m2/fixture/cls/1.0/cls-1.0-natives.jar", "fixture/cls.clj");
			this.repositories.poms.put("fixture:uses-cls:jar:1.0", List.of(dep("fixture:cls:jar:natives:1.0")));
			publishJar("fixture:rt-lib:jar:tests:1.0", "m2/fixture/rt-lib/1.0/rt-lib-1.0-tests.jar",
					"fixture/rt_lib_tests.clj");
			this.repositories.poms.put("fixture:testjar-user:jar:1.0", List.of(dep("fixture:rt-lib:jar:tests:1.0")));
			// a local jar shipping its own pom.xml
			Map<String, String> localJar = new LinkedHashMap<>();
			localJar.put("localjar/core.clj", "(ns localjar.core)");
			localJar.put("META-INF/maven/fixture/localjar/pom.xml", "<project/>");
			this.archives.put("localjar.jar", localJar);
			this.repositories.pomTexts.put("<project/>",
					List.of(dep("fixture:rt-lib:1.0"), dep("fixture:opt-lib:1.0", "compile", true),
							dep("fixture:test-lib:1.0", "test", false), dep("fixture:java-lib:1.0", "runtime", false),
							dep("fixture:rt-lib:1.0")));
			// git: gitlib's two commits and an unrelated one, a monorepo, a pom.xml
			// project, a commit without a manifest
			commit(GITLIB, C1, Map.of("deps.edn", "{:paths [\"src\"] :deps {fixture/rt-lib {:mvn/version \"1.0\"}}}",
					"src/gitlib/core.clj", "(ns gitlib.core) (def version 1)"));
			commit(GITLIB, C2, Map.of("deps.edn", "{:paths [\"src\"] :deps {fixture/rt-lib {:mvn/version \"1.0\"}}}",
					"src/gitlib/core.clj", "(ns gitlib.core) (def version 2)"));
			commit(GITLIB, OTHER, Map.of("deps.edn", "{:paths [\"src\"]}", "src/gitlib/core.clj",
					"(ns gitlib.core) (def version 0)"));
			this.repositories.lines.put(GITLIB, List.of(C1, C2));
			this.repositories.tags.put(GITLIB, Map.of("v1", C1, "v2", C2));
			commit("file:///git/gitmono", this.mono,
					Map.of("mod/deps.edn", "{:paths [\"src\"]}", "mod/src/mono/core.clj", "(ns mono.core)"));
			commit("file:///git/gitpom", this.pom,
					Map.of("pom.xml", "<project/>", "src/main/clojure/gitpom/core.clj", "(ns gitpom.core)"));
			// its model: the super POM's directories as tools.deps reads them
			// (${project.basedir} is "."), a resource repeating the source directory
			this.repositories.pomProjects
				.put("gitlibs/gitpom/" + this.pom + "/pom.xml", new ClojureRepositories.PomProject(
						List.of(dep("fixture:rt-lib:1.0"), dep("fixture:opt-lib:1.0", "compile", true),
								dep("fixture:test-lib:1.0", "test", false)),
						"./src/main/java", List.of("./src/main/resources", "src/main/java"),
						List.of(BUILD_HELPER,
								new ClojureRepositories.PomPlugin("org.apache.maven.plugins", "maven-compiler-plugin",
										List.of(new ClojureRepositories.PomExecution(List.of("add-source"),
												configuration("sources", value("source", "notread"))))))));
			commit("file:///git/gitnone", this.none, Map.of("src/x.clj", "(ns x)"));
		}

		/** A jar artifact holding one entry, and its POM's dependencies. */
		private void publish(String coordinates, List<ClojureRepositories.MavenDependency> deps, String entry) {
			publish(coordinates, deps, entry, "");
		}

		private void publish(String coordinates, List<ClojureRepositories.MavenDependency> deps, String entry,
				String text) {
			String[] parts = coordinates.split(":");
			this.repositories.poms.put(parts[0] + ":" + parts[1] + ":jar:" + parts[2], deps);
			Map<String, String> entries = new LinkedHashMap<>();
			entries.put(entry, text);
			this.archives.put(jar(parts[1], parts[2]), entries);
			this.repositories.jars.put(parts[0] + ":" + parts[1] + ":jar:" + parts[2], jar(parts[1], parts[2]));
		}

		private void publishJar(String artifact, String path, String entry) {
			Map<String, String> entries = new LinkedHashMap<>();
			entries.put(entry, "");
			this.archives.put(path, entries);
			this.repositories.jars.put(artifact, path);
		}

		private void commit(String url, String sha, Map<String, String> tree) {
			String dir = "gitlibs/" + url.substring(url.lastIndexOf('/') + 1) + "/" + sha;
			for (Map.Entry<String, String> file : tree.entrySet()) {
				this.files.put(dir + "/" + file.getKey(), file.getValue());
			}
			this.repositories.checkouts.computeIfAbsent(url, key -> new LinkedHashMap<>()).put(sha, dir);
		}

		/**
		 * The fixture's files with a project {@code deps.edn} naming the fixture
		 * repository.
		 */
		MemoryClojureFiles files(String deps) {
			Map<String, String> all = new LinkedHashMap<>(this.files);
			all.put("deps.edn", deps);
			return new MemoryClojureFiles(all, this.archives, null).withRepositories(this.repositories);
		}

	}

	/** Repositories held in memory, every question recorded. */
	private static final class MemoryRepositories implements ClojureRepositories {

		/**
		 * {@code group:artifact:extension[:classifier]:version} to the POM's
		 * dependencies.
		 */
		final Map<String, List<MavenDependency>> poms = new LinkedHashMap<>();

		/** The same coordinates to the jar's path. */
		final Map<String, String> jars = new LinkedHashMap<>();

		/** A POM's text to its model's dependencies. */
		final Map<String, List<MavenDependency>> pomTexts = new LinkedHashMap<>();

		/** A {@code pom.xml} project's file to its model. */
		final Map<String, PomProject> pomProjects = new LinkedHashMap<>();

		/** Each repository's commits and their checkouts. */
		final Map<String, Map<String, String>> checkouts = new LinkedHashMap<>();

		/** Each repository's tags. */
		final Map<String, Map<String, String>> tags = new LinkedHashMap<>();

		/**
		 * Each repository's line of history, each commit descending from those before.
		 */
		final Map<String, List<String>> lines = new LinkedHashMap<>();

		final List<String> asked = new ArrayList<>();

		@Override
		public String mavenVersion(MavenSource source, MavenArtifact artifact) {
			throw new FetchFailure(artifact + ": no metadata");
		}

		@Override
		public List<MavenDependency> mavenDependencies(MavenSource source, MavenArtifact artifact) {
			this.asked.add("pom " + artifact);
			return this.poms.getOrDefault(artifact.toString(), List.of());
		}

		@Override
		public List<MavenDependency> pomDependencies(MavenSource source, String pom) {
			this.asked.add("model " + pom);
			return this.pomTexts.getOrDefault(pom, List.of());
		}

		@Override
		public PomProject pomProject(MavenSource source, String pom) {
			this.asked.add("project " + pom);
			PomProject project = this.pomProjects.get(pom);
			if (project == null) {
				throw new FetchFailure("Non-resolvable parent POM g:p:1 for g:m:1: no repository has it");
			}
			return project;
		}

		@Override
		public String mavenArtifact(MavenSource source, MavenArtifact artifact) {
			this.asked.add("jar " + artifact);
			String jar = this.jars.get(artifact.toString());
			if (jar == null) {
				throw new FetchFailure(
						artifact + " is in neither the local repository nor any of: fixture (file:/repo/)");
			}
			return jar;
		}

		@Override
		public @Nullable String gitCommit(String url, String revision) {
			String tagged = this.tags.getOrDefault(url, Map.of()).get(revision);
			if (tagged != null) {
				return tagged;
			}
			List<String> matches = this.checkouts.getOrDefault(url, Map.of())
				.keySet()
				.stream()
				.filter(sha -> sha.startsWith(revision))
				.toList();
			return matches.size() == 1 ? matches.get(0) : null;
		}

		@Override
		public boolean gitTag(String url, String tag) {
			return this.tags.getOrDefault(url, Map.of()).containsKey(tag);
		}

		@Override
		public @Nullable String gitCheckout(String url, String sha) {
			return this.checkouts.getOrDefault(url, Map.of()).get(sha);
		}

		@Override
		public @Nullable String gitDescendant(String url, String x, String y) {
			List<String> line = this.lines.getOrDefault(url, List.of());
			int ix = line.indexOf(x);
			int iy = line.indexOf(y);
			if (ix < 0 || iy < 0) {
				return x.equals(y) ? x : null;
			}
			return ix > iy ? x : y;
		}

	}

}
