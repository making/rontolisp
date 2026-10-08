package am.ik.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Stream;

/**
 * The fixture repository and the rendering the oracle files use. The fixture
 * ({@code src/test/resources/am/ik/maven/repo}) holds POMs only; {@link #remote} copies
 * it with a {@code .sha1} beside every file, which is what a Maven repository publishes
 * and what the resolver verifies. The rendering is {@code MavenOracle}'s, so a test can
 * compare the resolver's answer with Maven's line for line.
 */
final class MavenTestRepository {

	/** The fixture repository. */
	static final Path FIXTURE = Path.of("src/test/resources/am/ik/maven/repo");

	/**
	 * The oracle's system properties: Java 25.0.4 on Linux amd64, no JDK at java.home.
	 */
	static final Map<String, String> SYSTEM = Map.of("java.version", "25.0.4", "java.home", "/nonexistent/jdk",
			"os.name", "Linux", "os.arch", "amd64", "os.version", "6.8.0");

	private MavenTestRepository() {
	}

	/**
	 * Copies the fixture into {@code dir}, each file with its {@code .sha1}.
	 * @param dir an empty directory
	 * @return {@code dir}
	 * @throws IOException if the copy fails
	 */
	static Path remote(Path dir) throws IOException {
		try (Stream<Path> files = Files.walk(FIXTURE)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				Path target = dir.resolve(FIXTURE.relativize(file).toString());
				Files.createDirectories(target.getParent());
				byte[] bytes = Files.readAllBytes(file);
				Files.write(target, bytes);
				Files.writeString(target.resolveSibling(target.getFileName() + ".sha1"), sha1(bytes));
			}
		}
		return dir;
	}

	static String sha1(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * A resolver over a {@code file:} copy of the fixture, with the oracle's system
	 * properties plus {@code extra}.
	 * @param remote the copy, from {@link #remote}
	 * @param local an empty local repository
	 * @param extra system properties added or replaced
	 * @return the resolver
	 */
	static MavenResolver resolver(Path remote, Path local, Map<String, String> extra) {
		Map<String, String> system = new LinkedHashMap<>(SYSTEM);
		system.putAll(extra);
		return MavenResolver.builder()
			.localRepository(local)
			.repositories(List.of(new RemoteRepository("fixture", remote.toUri().toString())))
			.systemProperties(system)
			.build();
	}

	static String format(Dependency dependency) {
		Artifact a = dependency.artifact();
		StringBuilder text = new StringBuilder(
				a.groupId() + ":" + a.artifactId() + ":" + a.extension() + ":" + a.classifier() + ":" + a.version());
		text.append(" scope=").append(dependency.scope()).append(" optional=").append(dependency.optional());
		if (!dependency.exclusions().isEmpty()) {
			StringJoiner exclusions = new StringJoiner(",", " exclusions=", "");
			for (Exclusion exclusion : dependency.exclusions()) {
				exclusions.add(exclusion.groupId() + ":" + exclusion.artifactId());
			}
			text.append(exclusions);
		}
		return text.toString();
	}

	/**
	 * A descriptor as the oracle prints one, warnings excluded.
	 * @param descriptor the descriptor
	 * @return the lines
	 */
	static List<String> render(ArtifactDescriptor descriptor) {
		List<String> lines = new ArrayList<>();
		lines.add("artifact " + descriptor.artifact());
		for (Artifact relocation : descriptor.relocations()) {
			lines.add("relocation " + relocation);
		}
		for (Dependency dependency : descriptor.dependencies()) {
			lines.add("dep " + format(dependency));
		}
		for (Dependency dependency : descriptor.managedDependencies()) {
			lines.add("managed " + format(dependency));
		}
		return lines;
	}

	/**
	 * A graph as the oracle prints one, warnings excluded.
	 * @param graph the graph
	 * @return the lines
	 */
	static List<String> render(DependencyGraph graph) {
		List<String> lines = new ArrayList<>();
		for (DependencyNode root : graph.roots()) {
			render(root, "", lines);
		}
		return lines;
	}

	private static void render(DependencyNode node, String indent, List<String> lines) {
		StringBuilder line = new StringBuilder(indent).append(format(node.dependency()));
		if (node.premanagedVersion() != null) {
			line.append(" premanaged-version=").append(node.premanagedVersion());
		}
		if (node.premanagedScope() != null) {
			line.append(" premanaged-scope=").append(node.premanagedScope());
		}
		if (!node.relocations().isEmpty()) {
			line.append(" relocations=").append(node.relocations());
		}
		if (node.cycle()) {
			line.append(" cycle");
		}
		lines.add(line.toString());
		for (DependencyNode child : node.children()) {
			render(child, indent + "  ", lines);
		}
	}

	static String text(Path file) throws IOException {
		return Files.readString(file, StandardCharsets.UTF_8);
	}

}
