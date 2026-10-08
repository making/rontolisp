package am.ik.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
		// the model version is never inherited
		pom("c1", parent);
		Path noModelVersion = pom("c1/m", """
				<project><parent><groupId>g</groupId><artifactId>p</artifactId><version>1</version></parent>
				<artifactId>m</artifactId></project>
				""");
		assertRefused(noModelVersion, "the POM is invalid: 'modelVersion' is missing.");
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
