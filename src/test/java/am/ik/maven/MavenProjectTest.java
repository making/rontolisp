package am.ik.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A {@code pom.xml} project's model ({@link MavenResolver#project}) as Maven 3.9.16
 * builds it from a file for tools.deps: every expectation is what
 * {@code clj -Srepro -Spath} (1.12.6, 2026-10-08) put on the classpath for the same tree
 * -- tools.deps reads the source directory, the resources' directories and the first
 * plugin's build-helper configuration off this model, each made canonical against the
 * project -- and every refusal one the oracle refused.
 */
class MavenProjectTest {

	private static final String HELPER = "<groupId>org.codehaus.mojo</groupId>"
			+ "<artifactId>build-helper-maven-plugin</artifactId>";

	/** What tools.deps adds to the system properties. */
	private static final Map<String, String> TOOLS_DEPS = Map.of("project.basedir", ".");

	@TempDir
	Path dir;

	@TempDir
	Path local;

	@Test
	void withoutABuildTheSuperPomsDirectoriesReadTheBasedirProperty() throws IOException {
		Path pom = pom("nobuild", project("nobuild", ""));

		MavenProject project = resolver().project(pom, TOOLS_DEPS);

		assertThat(project.sourceDirectory()).isEqualTo("./src/main/java");
		assertThat(project.resourceDirectories()).containsExactly("./src/main/resources");
		assertThat(project.plugins()).isEmpty();
		// without the property there is no project directory to read it from
		assertThat(resolver().project(pom, Map.of()).sourceDirectory()).isEqualTo("${project.basedir}/src/main/java");
	}

	@Test
	void theBuildsOwnDirectoriesStayAsInterpolated() throws IOException {
		Path pom = pom("custom", project("custom", """
				<build><sourceDirectory>src/java</sourceDirectory>
				<resources><resource><directory>res1</directory></resource>
				<resource><directory>${project.basedir}/res2</directory></resource>
				<resource><directory>${basedir}/res3</directory></resource>
				<resource><directory>/abs/res4</directory></resource></resources></build>
				"""));

		MavenProject project = resolver().project(pom, TOOLS_DEPS);

		// ${basedir} has no value without a project directory: the oracle's classpath
		// holds custom/${basedir}/res3
		assertThat(project.sourceDirectory()).isEqualTo("src/java");
		assertThat(project.resourceDirectories()).containsExactly("res1", "./res2", "${basedir}/res3", "/abs/res4");
	}

	@Test
	void pluginsKeepTheirExecutionsAndConfigurationsInterpolated() throws IOException {
		Path pom = pom("helper", project("helper", """
				<build><plugins><plugin>%s<version>3.6.0</version><executions>
				<execution><id>a</id><goals><goal>add-source</goal></goals><configuration><sources>
				<source>extra</source><source>${project.build.directory}/gen</source><source>${basedir}/b</source>
				<source>${project.build.outputDirectory}/x</source><source>${project.build.finalName}</source>
				<source/><source></source></sources></configuration></execution>
				<execution><id>r</id><goals><goal>add-resource</goal></goals><configuration><resources>
				<resource><directory>rdir</directory></resource><resource>flat</resource></resources></configuration>
				</execution></executions></plugin>
				<plugin><artifactId>maven-compiler-plugin</artifactId></plugin></plugins></build>
				""".formatted(HELPER)));

		MavenProject project = resolver().project(pom, TOOLS_DEPS);

		assertThat(project.plugins()).extracting(plugin -> plugin.groupId() + ":" + plugin.artifactId())
			.containsExactly("org.codehaus.mojo:build-helper-maven-plugin",
					"org.apache.maven.plugins:maven-compiler-plugin");
		assertThat(executions(project.plugins().get(0))).containsExactly(
				"a [add-source] sources=[extra, ./target/gen, ${basedir}/b, ./target/classes/x, helper-1, null, ]",
				"r [add-resource] resources=[null, flat]");
	}

	@Test
	void aChildInheritsItsParentsBuildMergedAsMavenMergesIt() throws IOException {
		pom("pa", """
				<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>pa</artifactId>
				<version>1</version><packaging>pom</packaging>
				<properties><srcd>psrc</srcd></properties>
				<build><sourceDirectory>${srcd}</sourceDirectory><plugins><plugin>%s<executions>
				<execution><id>p</id><goals><goal>add-source</goal></goals>
				<configuration><sources><source>p1</source><source>p2</source></sources></configuration></execution>
				<execution><id>q</id><goals><goal>add-source</goal></goals>
				<configuration><sources><source>q1</source></sources></configuration></execution>
				<execution><id>nope</id><inherited>false</inherited><goals><goal>add-source</goal></goals>
				<configuration><sources><source>notinherited</source></sources></configuration></execution>
				</executions></plugin></plugins></build></project>
				""".formatted(HELPER));
		Path inherits = pom("pa/inherits", child("pa", "inherits", ""));
		Path merges = pom("pa/merges", child("pa", "merges", """
				<properties><srcd>msrc</srcd></properties>
				<build><plugins><plugin>%s<executions>
				<execution><id>q</id><configuration><sources combine.children="append"><source>cq</source></sources>
				</configuration></execution>
				<execution><id>p</id><configuration><sources><source>c1</source></sources></configuration></execution>
				<execution><id>c</id><goals><goal>add-source</goal></goals>
				<configuration><sources><source>cnew</source></sources></configuration></execution>
				</executions></plugin></plugins></build>
				""".formatted(HELPER)));
		Path first = pom("pa/first", child("pa", "first", """
				<build><plugins><plugin><groupId>x</groupId><artifactId>childfirst</artifactId></plugin>
				<plugin>%s</plugin></plugins></build>
				""".formatted(HELPER)));

		// the parent's source directory, its property the child's; the inherited
		// executions only
		MavenProject inherited = resolver().project(inherits, TOOLS_DEPS);
		assertThat(inherited.sourceDirectory()).isEqualTo("psrc");
		assertThat(inherited.resourceDirectories()).containsExactly("./src/main/resources");
		assertThat(executions(inherited.plugins().get(0))).containsExactly("p [add-source] sources=[p1, p2]",
				"q [add-source] sources=[q1]");
		// an execution merges into the parent's of its id at its place: the child's
		// configuration dominant (a recessive child beyond the dominant's count dropped),
		// appended after the parent's under combine.children="append", the goals joined
		MavenProject merged = resolver().project(merges, TOOLS_DEPS);
		assertThat(merged.sourceDirectory()).isEqualTo("msrc");
		assertThat(executions(merged.plugins().get(0))).containsExactly("p [add-source] sources=[c1]",
				"q [add-source] sources=[q1, cq]", "c [add-source] sources=[cnew]");
		// a child's plugin before one it shares with its parent stays first
		assertThat(resolver().project(first, TOOLS_DEPS).plugins())
			.extracting(plugin -> plugin.groupId() + ":" + plugin.artifactId())
			.containsExactly("x:childfirst", "org.codehaus.mojo:build-helper-maven-plugin");
	}

	@Test
	void managementProfilesAndDuplicatesMergeIntoThePlugins() throws IOException {
		pom("pb", """
				<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>pb</artifactId>
				<version>1</version><packaging>pom</packaging>
				<build><pluginManagement><plugins><plugin>%s<executions><execution><id>m</id>
				<goals><goal>add-source</goal></goals><configuration><sources><source>m1</source></sources>
				</configuration></execution></executions></plugin></plugins></pluginManagement></build></project>
				""".formatted(HELPER));
		Path managed = pom("pb/managed",
				child("pb", "managed", "<build><plugins><plugin>%s</plugin></plugins></build>".formatted(HELPER)));
		Path profiled = pom("profiled", project("profiled", """
				<build><sourceDirectory>main-src</sourceDirectory>
				<resources><resource><directory>r-main</directory></resource></resources></build>
				<profiles><profile><id>x</id><activation><activeByDefault>true</activeByDefault></activation>
				<build><resources><resource><directory>r-profile</directory></resource></resources>
				<plugins><plugin>%s<executions><execution><goals><goal>add-resource</goal></goals>
				<configuration><resources><resource>prof-res</resource></resources></configuration></execution>
				</executions></plugin></plugins></build></profile></profiles>
				""".formatted(HELPER)));
		Path dups = pom("dups",
				project("dups",
						"""
								<build><plugins>
								<plugin>%1$s<executions><execution><id>a</id><goals><goal>add-source</goal></goals>
								<configuration><sources><source>first</source></sources></configuration></execution></executions></plugin>
								<plugin><artifactId>maven-compiler-plugin</artifactId></plugin>
								<plugin>%1$s<executions><execution><id>b</id><goals><goal>add-source</goal></goals>
								<configuration><sources><source>second</source></sources></configuration></execution></executions></plugin>
								</plugins></build>
								"""
							.formatted(HELPER)));

		assertThat(executions(resolver().project(managed, TOOLS_DEPS).plugins().get(0)))
			.containsExactly("m [add-source] sources=[m1]");
		MavenProject profile = resolver().project(profiled, TOOLS_DEPS);
		assertThat(profile.sourceDirectory()).isEqualTo("main-src");
		assertThat(profile.resourceDirectories()).containsExactly("r-main", "r-profile");
		assertThat(executions(profile.plugins().get(0))).containsExactly("default [add-resource] resources=[prof-res]");
		MavenProject duplicates = resolver().project(dups, TOOLS_DEPS);
		assertThat(duplicates.plugins()).hasSize(2);
		assertThat(executions(duplicates.plugins().get(0))).containsExactly("a [add-source] sources=[first]",
				"b [add-source] sources=[second]");
	}

	@Test
	void aRepositoryParentAndTheDependenciesAsTheModelHoldsThem(@TempDir Path remote) throws IOException {
		publish(remote, "rparent",
				"""
						<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>rparent</artifactId>
						<version>1.0</version><packaging>pom</packaging>
						<dependencyManagement><dependencies><dependency><groupId>fixture</groupId><artifactId>managed</artifactId>
						<version>2.0</version></dependency></dependencies></dependencyManagement>
						<build><resources><resource><directory>from-rparent</directory></resource></resources></build></project>
						""");
		Path pom = pom("withdeps",
				"""
						<project><modelVersion>4.0.0</modelVersion>
						<parent><groupId>fixture</groupId><artifactId>rparent</artifactId><version>1.0</version><relativePath/></parent>
						<groupId>g</groupId><artifactId>withdeps</artifactId><version>1</version>
						<properties><v>1.0</v></properties>
						<dependencies>
						<dependency><groupId>fixture</groupId><artifactId>lib</artifactId><version>${v}</version></dependency>
						<dependency><groupId>fixture</groupId><artifactId>opt</artifactId><version>1.0</version><optional>true</optional></dependency>
						<dependency><groupId>fixture</groupId><artifactId>tst</artifactId><version>1.0</version><scope>test</scope></dependency>
						<dependency><groupId>fixture</groupId><artifactId>managed</artifactId></dependency>
						</dependencies></project>
						""");

		MavenProject project = resolver(remote).project(pom, TOOLS_DEPS);

		assertThat(project.resourceDirectories()).containsExactly("from-rparent");
		assertThat(project.dependencies()).extracting(MavenTestRepository::format)
			.containsExactly("fixture:lib:jar::1.0 scope=compile optional=null",
					"fixture:opt:jar::1.0 scope=compile optional=true", "fixture:tst:jar::1.0 scope=test optional=null",
					"fixture:managed:jar::2.0 scope=compile optional=null");
	}

	@Test
	void aParentIsReadBesideTheFileWhenItIsTheOneNamed() throws IOException {
		String parent = """
				<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>p</artifactId>
				<version>1</version><packaging>pom</packaging><build><sourceDirectory>psrc</sourceDirectory></build>
				</project>
				""";
		// an explicit relativePath naming a directory
		pom("c4/parent", parent);
		Path explicit = pom("c4/a/b/m", """
				<project><modelVersion>4.0.0</modelVersion><parent><groupId>g</groupId><artifactId>p</artifactId>
				<version>1</version><relativePath>../../../parent</relativePath></parent><artifactId>m</artifactId>
				</project>
				""");
		assertThat(resolver().project(explicit, TOOLS_DEPS).sourceDirectory()).isEqualTo("psrc");
		// a range admitting the local version, the child's version its own
		pom("c7", parent);
		Path ranged = pom("c7/m", """
				<project><modelVersion>4.0.0</modelVersion><parent><groupId>g</groupId><artifactId>p</artifactId>
				<version>[1,2)</version></parent><artifactId>m</artifactId><version>3</version></project>
				""");
		assertThat(resolver().project(ranged, TOOLS_DEPS).sourceDirectory()).isEqualTo("psrc");
	}

	@Test
	void whatTheOracleRefusesIsRefused() throws IOException {
		String parent = """
				<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>p</artifactId>
				<version>1</version><packaging>pom</packaging></project>
				""";
		// the model version is never inherited: missing in the raw model and in the
		// effective one
		pom("c1", parent);
		Path noModelVersion = pom("c1/m", """
				<project><parent><groupId>g</groupId><artifactId>p</artifactId><version>1</version></parent>
				<artifactId>m</artifactId></project>
				""");
		assertRefused(noModelVersion, "the POM is invalid: 'modelVersion' is missing.; 'modelVersion' is missing.");
		// a relativePath at another artifact, or at another version, sends the lookup to
		// the repositories
		pom("c2", parent.replace("<artifactId>p</artifactId>", "<artifactId>other</artifactId>"));
		assertRefused(pom("c2/m", child("p", "m", "")),
				"Non-resolvable parent POM g:p:1 for g:m:1: no repository has it");
		pom("c5", parent);
		assertRefused(pom("c5/m", child("p", "m", "").replace("<version>1</version>", "<version>2</version>")),
				"Non-resolvable parent POM g:p:2 for g:m:2: no repository has it");
		// a parent must be a pom
		pom("c3", parent.replace("<packaging>pom</packaging>", ""));
		assertRefused(pom("c3/m", child("p", "m", "")),
				"the POM is invalid: Invalid packaging for parent POM g:p:1, must be \"pom\" but is \"jar\"");
		// a range admitting the local version cannot give the child its version
		pom("c6", parent);
		assertRefused(pom("c6/m", child("p", "m", "").replace("<version>1</version>", "<version>[1,2)</version>")),
				"the POM is invalid: Version must be a constant");
	}

	/**
	 * A POM checked at the strict level (tools.deps' request): the refusal lists Maven's
	 * problems in Maven's order, a warning marked; {@code null} where the oracle built
	 * the model.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("strictCases")
	void theStrictLevelRefusesWhatTheOracleRefuses(String name, String pom, @Nullable String refusal)
			throws IOException {
		Path file = pom(name, pom);
		if (refusal == null) {
			assertThat(resolver().project(file, TOOLS_DEPS).sourceDirectory()).isEqualTo("./src/main/java");
		}
		else {
			assertRefused(file, "the POM is invalid: " + refusal);
		}
	}

	static Stream<Arguments> strictCases() {
		String plugin = "<artifactId>maven-x-plugin</artifactId>";
		String key = "org.apache.maven.plugins:maven-x-plugin";
		return Stream.of(
				Arguments.of("resource",
						project("proj",
								"<build><resources><resource/></resources><plugins><plugin>"
										+ "<artifactId>maven-compiler-plugin</artifactId></plugin></plugins></build>"),
						"[WARNING] 'build.plugins.plugin.version' for org.apache.maven.plugins:maven-compiler-plugin"
								+ " is missing.; 'build.resources.resource.directory' is missing."),
				Arguments.of("status",
						project("proj", "<distributionManagement><status>deployed</status></distributionManagement>"),
						"'distributionManagement.status' must not be specified."),
				Arguments.of("report",
						project("proj", "<reporting><plugins><plugin><artifactId/></plugin></plugins></reporting>"),
						"'reporting.plugins.plugin.artifactId' is missing."),
				Arguments.of("repos",
						project("proj",
								"<repositories><repository><id>local</id></repository><repository>"
										+ "<id>local</id><url>file:///nowhere</url></repository></repositories>"),
						"'repositories.repository.[local].url' is missing.; 'repositories.repository.id' must be"
								+ " unique: local -> null vs file:///nowhere; [WARNING] 'repositories.repository.id'"
								+ " must not be 'local', this identifier is reserved for the local repository, using it"
								+ " for other repositories will corrupt your repository metadata."),
				Arguments.of("profiles",
						project("proj",
								"<profiles><profile><id>p</id><activation><property><name>${project.x}</name>"
										+ "</property></activation></profile><profile><id>p</id></profile></profiles>"),
						"[WARNING] 'profiles.profile[p].activation.property.name' Failed to interpolate profile"
								+ " activation property ${project.x}: ${project.x} expressions are not supported during"
								+ " profile activation.; 'profiles.profile.id' must be unique but found duplicate"
								+ " profile with id p"),
				Arguments.of("executions",
						project("proj", "<build><plugins><plugin>" + plugin + "<version>1</version><executions>"
								+ "<execution><goals><goal>a</goal></goals></execution><execution/></executions>"
								+ "</plugin><plugin>" + plugin + "</plugin></plugins></build>"),
						"'build.plugins.plugin.[" + key + "].executions.execution.id' must be unique but found"
								+ " duplicate execution with id default; [WARNING] 'build.plugins.plugin."
								+ "(groupId:artifactId)' must be unique but found duplicate declaration of plugin "
								+ key),
				Arguments.of("plugin-version",
						project("proj",
								"<build><plugins><plugin>" + plugin
										+ "<version>LATEST</version></plugin></plugins></build>"),
						"'build.plugins.plugin.version' for " + key + " must be a valid version but is 'LATEST'."),
				Arguments.of("booleans",
						project("proj", "<build><plugins><plugin>" + plugin + "<version>1</version>"
								+ "<extensions>no</extensions></plugin></plugins><resources><resource>"
								+ "<directory>r</directory><filtering>yes</filtering></resource></resources></build>"),
						"'build.plugins.plugin.extensions' for " + key + " must be 'true' or 'false' but is 'no'.;"
								+ " 'build.resources.resource.filtering' for r must be 'true' or 'false' but is"
								+ " 'yes'."),
				Arguments.of("dependency",
						project("proj",
								"<dependencies><dependency><groupId>x</groupId><artifactId>y</artifactId>"
										+ "<version>${undefined}</version><optional>yes</optional></dependency>"
										+ "</dependencies>"),
						"'dependencies.dependency.optional' for x:y:jar must be 'true' or 'false' but is 'yes'.;"
								+ " 'dependencies.dependency.version' for x:y:jar must be a valid version but is"
								+ " '${undefined}'."),
				Arguments.of("plugin-dependency",
						project("proj", "<build><plugins><plugin>" + plugin + "<version>1</version><dependencies>"
								+ "<dependency><groupId>x</groupId><artifactId>y</artifactId><version>1</version>"
								+ "<scope>test</scope></dependency></dependencies></plugin></plugins></build>"),
						"'build.plugins.plugin[" + key + "].dependencies.dependency.scope' for x:y:jar must be one"
								+ " of [compile, runtime, system] but is 'test'."),
				Arguments.of("self",
						project("proj",
								"<dependencies><dependency><groupId>g</groupId><artifactId>proj</artifactId>"
										+ "<version>1</version></dependency></dependencies>"),
						"'dependencies.dependency[g:proj:1]' for g:proj:1 is referencing itself."),
				Arguments.of("version-expression",
						"<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>proj</artifactId>"
								+ "<version>${revision}</version></project>",
						"'version' must be a constant version but is '${revision}'."),
				Arguments.of("model-version", project("proj", "").replace("4.0.0", "4.0"),
						"'modelVersion' must be one of [4.0.0] but is '4.0'."),
				Arguments.of("model-version-newer", project("proj", "").replace("4.0.0", "5.0.0"),
						"'modelVersion' of '5.0.0' is newer than the versions supported by this version of Maven:"
								+ " [4.0.0]. Building this project requires a newer version of Maven."),
				// the oracle's validator throws out of the model builder
				Arguments.of("model-version-text", project("proj", "").replace("4.0.0", "4.x"),
						"java.lang.NumberFormatException: For input string: \"x\""),
				Arguments.of("module", project("proj", "<packaging>pom</packaging><modules><module/></modules>"),
						"'modules.module[0]' has been specified without a path to the project directory."),
				// what only the lenient reader reads: a warning for the file built
				Arguments.of("unknown", project("proj", "<foo><bar/></foo>"), null),
				Arguments.of("text", project("proj", " junk <name>n</name>"), null),
				Arguments.of("root",
						project("proj", "").replace("<project>", "<proj>").replace("</project>", "</proj>"), null),
				Arguments.of("attribute", project("proj", "").replace("<project>", "<project bar=\"1\">"), null),
				// a duplicate plugin is a warning below level 3.1
				Arguments.of(
						"duplicates", project("proj", "<build><plugins><plugin>" + plugin
								+ "<version>1</version></plugin><plugin>" + plugin + "</plugin></plugins></build>"),
						null));
	}

	@Test
	void aParentBesideTheFileIsReadStrictlyOneFromARepositoryAtLevelTwo(@TempDir Path remote) throws IOException {
		String duplicateProfiles = """
				<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>pp</artifactId>
				<version>1</version><packaging>pom</packaging>
				<profiles><profile><id>p</id></profile><profile><id>p</id></profile></profiles></project>
				""";
		String child = """
				<project><modelVersion>4.0.0</modelVersion><parent><groupId>g</groupId><artifactId>pp</artifactId>
				<version>1</version>%s</parent><artifactId>proj</artifactId></project>
				""";
		pom("local", duplicateProfiles);
		assertRefused(pom("local/proj", child.formatted("")),
				"the POM is invalid: 'profiles.profile.id' must be unique but found duplicate profile with id p");
		// what only the lenient reader reads of a parent beside the file is an error
		pom("malformed", duplicateProfiles.replaceAll("<profiles>.*</profiles>", "<foo/>"));
		Path malformed = pom("malformed/proj", child.formatted(""));
		assertThatThrownBy(() -> resolver().project(malformed, TOOLS_DEPS))
			.hasMessageStartingWith("the POM is invalid: Malformed POM " + this.dir.resolve("malformed/pom.xml")
					+ ": Unrecognised tag: 'foo'");
		Path pp = remote.resolve("g/pp/1/pp-1.pom");
		Files.createDirectories(pp.getParent());
		byte[] bytes = duplicateProfiles.getBytes(StandardCharsets.UTF_8);
		Files.write(pp, bytes);
		Files.writeString(pp.resolveSibling("pp-1.pom.sha1"), MavenTestRepository.sha1(bytes));
		Path fromRepository = pom("repository/proj", child.formatted("<relativePath/>"));
		assertThat(resolver(remote).project(fromRepository, TOOLS_DEPS).sourceDirectory()).isEqualTo("./src/main/java");
	}

	@Test
	void aJarsOwnPomIsBuiltStrictlyWhatOnlyTheLenientReaderReadsAWarning() throws MavenResolutionException {
		assertThat(resolver().projectDependencies(project("proj", "<foo/>").getBytes(StandardCharsets.UTF_8)))
			.isEmpty();
		byte[] resource = project("proj", "<build><resources><resource/></resources></build>")
			.getBytes(StandardCharsets.UTF_8);
		assertThatThrownBy(() -> resolver().projectDependencies(resource))
			.hasMessage("the POM is invalid: 'build.resources.resource.directory' is missing.");
	}

	private void assertRefused(Path pom, String message) {
		assertThatThrownBy(() -> resolver().project(pom, TOOLS_DEPS)).isInstanceOf(MavenResolutionException.class)
			.hasMessage(message);
	}

	/** Each execution: its id, goals and the values of its configuration's list. */
	private static List<String> executions(MavenProject.Plugin plugin) {
		List<String> out = new ArrayList<>();
		for (MavenProject.Execution execution : plugin.executions()) {
			StringBuilder line = new StringBuilder(execution.id() + " " + execution.goals());
			ConfigurationNode configuration = execution.configuration();
			for (ConfigurationNode list : configuration == null ? List.<ConfigurationNode>of()
					: configuration.children()) {
				List<@Nullable String> values = new ArrayList<>();
				for (ConfigurationNode item : list.children()) {
					values.add(item.value());
				}
				line.append(' ').append(list.name()).append('=').append(values);
			}
			out.add(line.toString());
		}
		return out;
	}

	private MavenResolver resolver() {
		return MavenResolver.builder()
			.localRepository(this.local)
			.repositories(List.of())
			.systemProperties(MavenTestRepository.SYSTEM)
			.build();
	}

	private MavenResolver resolver(Path remote) {
		return MavenResolver.builder()
			.localRepository(this.local)
			.repositories(List.of(new RemoteRepository("fixture", remote.toUri().toString())))
			.systemProperties(MavenTestRepository.SYSTEM)
			.build();
	}

	private Path pom(String directory, String text) throws IOException {
		Path pom = this.dir.resolve(directory).resolve("pom.xml");
		Files.createDirectories(pom.getParent());
		Files.writeString(pom, text);
		return pom;
	}

	private static String project(String artifactId, String content) {
		return "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>" + artifactId
				+ "</artifactId><version>1</version>" + content + "</project>";
	}

	private static String child(String parent, String artifactId, String content) {
		return "<project><modelVersion>4.0.0</modelVersion><parent><groupId>g</groupId><artifactId>" + parent
				+ "</artifactId><version>1</version></parent><artifactId>" + artifactId + "</artifactId>" + content
				+ "</project>";
	}

	private static void publish(Path remote, String artifactId, String text) throws IOException {
		Path pom = remote.resolve("fixture/" + artifactId + "/1.0/" + artifactId + "-1.0.pom");
		Files.createDirectories(pom.getParent());
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		Files.write(pom, bytes);
		Files.writeString(pom.resolveSibling(artifactId + "-1.0.pom.sha1"), MavenTestRepository.sha1(bytes));
	}

}
