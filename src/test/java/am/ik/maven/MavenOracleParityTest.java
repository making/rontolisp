package am.ik.maven;

import java.io.IOException;
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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The resolver against Maven itself. Each file in
 * {@code src/test/resources/am/ik/maven/oracle} is Maven 3.9.16's answer (resolver
 * 1.9.27) for one request over the fixture repository, written by
 * {@code src/test/resources/am/ik/maven/oracle/MavenOracle.java}: its first line is the
 * request, the rest what Maven printed. The resolver must print the same.
 *
 * <p>
 * What may differ, and only that:
 * <ul>
 * <li>an {@code error} line: the request fails at the same place, worded the resolver's
 * way (its own tests pin the wording);</li>
 * <li>an invalid POM's warning: the same artifact, and every problem the resolver names
 * is one Maven names.</li>
 * </ul>
 */
class MavenOracleParityTest {

	private static final Path CASES = Path.of("src/test/resources/am/ik/maven/oracle");

	private static final Pattern MAVEN_WARNING = Pattern.compile("warning (missing|invalid) (\\S+?)(?::? (.*))?");

	private static final Pattern MISSING = Pattern
		.compile("The POM for (\\S+) is missing, no dependency information available");

	private static final Pattern INVALID = Pattern
		.compile("The POM for (\\S+) is invalid, transitive dependencies \\(if any\\) will not be available: (.*)");

	@TempDir
	static Path remoteRoot;

	private static Path remote;

	@BeforeAll
	static void copyFixture() throws IOException {
		remote = MavenTestRepository.remote(remoteRoot);
	}

	static Stream<String> cases() throws IOException {
		try (Stream<Path> files = Files.list(CASES)) {
			return files.map(file -> file.getFileName().toString())
				.filter(name -> name.endsWith(".txt"))
				.sorted()
				.toList()
				.stream();
		}
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("cases")
	void answersWhatMavenAnswers(String name, @TempDir Path local) throws IOException {
		List<String> lines = Files.readAllLines(CASES.resolve(name), StandardCharsets.UTF_8);
		Request request = Request.parse(lines.get(0).substring(2));
		MavenResolver resolver = MavenTestRepository.resolver(remote, local, request.system(), request.releases(),
				request.snapshots());
		List<String> maven = lines.subList(1, lines.size());
		List<String> ours = new ArrayList<>();
		if (!request.mode().equals("descriptor")) {
			try {
				DependencyGraph graph = request.mode().equals("collect")
						? resolver.collect(request.dependencies(), request.managed())
						: resolver.resolve(request.dependencies(), request.managed());
				if (request.mode().equals("classpath")) {
					for (Artifact entry : graph.runtimeClassPath()) {
						ours.add("classpath " + entry.groupId() + ":" + entry.artifactId() + ":" + entry.extension()
								+ ":" + entry.classifier() + ":" + entry.version());
					}
				}
				else {
					ours.addAll(MavenTestRepository.render(graph));
				}
				graph.warnings().forEach(warning -> ours.add("warning " + warning));
			}
			catch (MavenResolutionException ex) {
				ours.add("error " + ex.getMessage());
				if (!request.mode().equals("collect")) {
					// a version conflict no candidate settles fails after the collection,
					// whose warnings Maven reports with the failure
					try {
						resolver.collect(request.dependencies(), request.managed())
							.warnings()
							.forEach(warning -> ours.add("warning " + warning));
					}
					catch (MavenResolutionException collecting) {
						// failed while collecting: no warnings, as in Maven's answers
					}
				}
			}
		}
		else {
			for (Dependency dependency : request.dependencies()) {
				try {
					ArtifactDescriptor descriptor = resolver.descriptor(dependency.artifact());
					ours.addAll(MavenTestRepository.render(descriptor));
					descriptor.warnings().forEach(warning -> ours.add("warning " + warning));
				}
				catch (MavenResolutionException ex) {
					ours.add("error " + ex.getMessage());
				}
			}
		}
		assertThat(ours).as("the resolver's answer to %s", lines.get(0)).hasSameSizeAs(maven);
		for (int i = 0; i < maven.size(); i++) {
			assertSameLine(maven.get(i), ours.get(i), lines.get(0));
		}
	}

	private static void assertSameLine(String maven, String ours, String request) {
		if (maven.startsWith("error ")) {
			assertThat(ours).as("%s: Maven failed here (%s)", request, maven).startsWith("error ");
			return;
		}
		if (!maven.startsWith("warning ")) {
			assertThat(ours).as(request).isEqualTo(maven);
			return;
		}
		Matcher expected = MAVEN_WARNING.matcher(maven);
		assertThat(expected.matches()).as("an oracle warning line: %s", maven).isTrue();
		String warning = ours.startsWith("warning ") ? ours.substring("warning ".length()) : ours;
		if (expected.group(1).equals("missing")) {
			Matcher missing = MISSING.matcher(warning);
			assertThat(missing.matches()).as("%s: Maven warns %s, the resolver says %s", request, maven, ours).isTrue();
			assertThat(missing.group(1)).isEqualTo(expected.group(2));
			return;
		}
		Matcher invalid = INVALID.matcher(warning);
		assertThat(invalid.matches()).as("%s: Maven warns %s, the resolver says %s", request, maven, ours).isTrue();
		assertThat(invalid.group(1)).isEqualTo(expected.group(2));
		for (String problem : invalid.group(2).split("; ")) {
			assertThat(maven).as("%s: a problem the resolver names must be one Maven names", request)
				.contains(core(problem));
		}
	}

	/**
	 * A problem without the resolver's own framing: the parse prefix, a position suffix.
	 */
	private static String core(String problem) {
		String text = problem.startsWith("Non-parseable POM: ") ? problem.substring("Non-parseable POM: ".length())
				: problem;
		int suffix = text.lastIndexOf(" (");
		return suffix > 0 && text.endsWith(")") ? text.substring(0, suffix) : text;
	}

	/** A request in {@code MavenOracle}'s argument syntax. */
	private record Request(String mode, Map<String, String> system, List<Dependency> dependencies,
			List<Dependency> managed, RepositoryPolicy releases, RepositoryPolicy snapshots) {

		static Request parse(String header) {
			String[] words = header.split(" ");
			Map<String, String> system = new LinkedHashMap<>();
			List<Dependency> dependencies = new ArrayList<>();
			List<Dependency> managed = new ArrayList<>();
			boolean inManaged = false;
			RepositoryPolicy releases = RepositoryPolicy.DEFAULT;
			RepositoryPolicy snapshots = RepositoryPolicy.DEFAULT;
			for (int i = 1; i < words.length; i++) {
				String word = words[i];
				if (word.equals("--no-releases")) {
					releases = RepositoryPolicy.DISABLED;
					continue;
				}
				if (word.equals("--no-snapshots")) {
					snapshots = RepositoryPolicy.DISABLED;
					continue;
				}
				if (word.startsWith("-D")) {
					String[] pair = word.substring(2).split("=", 2);
					system.put(pair[0], pair.length > 1 ? pair[1] : "");
					continue;
				}
				if (word.equals("--managed")) {
					inManaged = true;
					continue;
				}
				String[] parts = word.split("#");
				List<Exclusion> exclusions = new ArrayList<>();
				for (int e = 1; e < parts.length; e++) {
					String[] ga = parts[e].split(":");
					exclusions.add(new Exclusion(ga[0], ga[1]));
				}
				String head = parts[0];
				Boolean optional = null;
				if (head.endsWith("?")) {
					optional = Boolean.TRUE;
					head = head.substring(0, head.length() - 1);
				}
				String[] scoped = head.split("@");
				Artifact artifact = Artifact.parse(scoped[0]);
				String scope = scoped.length > 1 ? scoped[1] : (inManaged ? "" : "compile");
				(inManaged ? managed : dependencies)
					.add(new Dependency(artifact, artifact.extension(), scope, optional, exclusions));
			}
			return new Request(words[0], system, dependencies, managed, releases, snapshots);
		}

	}

}
