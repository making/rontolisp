package am.ik.maven;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The coordinates of one file in a Maven repository: Maven Resolver's artifact without
 * its properties. The classifier is the empty string when there is none.
 *
 * @param groupId the group id
 * @param artifactId the artifact id
 * @param version the version
 * @param classifier the classifier, or the empty string
 * @param extension the file extension ({@code jar}, {@code pom}, ...)
 */
public record Artifact(String groupId, String artifactId, String version, String classifier, String extension) {

	/** Maven Resolver's coordinate syntax ({@code DefaultArtifact}). */
	private static final Pattern COORDINATES = Pattern.compile("([^: ]+):([^: ]+)(:([^: ]*)(:([^: ]+))?)?:([^: ]+)");

	/** A snapshot deployed under a timestamp ({@code 1.0-20240101.123456-1}). */
	private static final Pattern TIMESTAMPED_SNAPSHOT = Pattern.compile("^(.*-)?([0-9]{8}\\.[0-9]{6}-[0-9]+)$");

	private static final String SNAPSHOT = "SNAPSHOT";

	/**
	 * Validates that no part is {@code null}.
	 * @param groupId the group id
	 * @param artifactId the artifact id
	 * @param version the version
	 * @param classifier the classifier, or the empty string
	 * @param extension the file extension
	 */
	public Artifact {
		Objects.requireNonNull(groupId, "groupId");
		Objects.requireNonNull(artifactId, "artifactId");
		Objects.requireNonNull(version, "version");
		Objects.requireNonNull(classifier, "classifier");
		Objects.requireNonNull(extension, "extension");
	}

	/**
	 * Parses Maven's coordinate form,
	 * {@code groupId:artifactId[:extension[:classifier]]:version}; the extension defaults
	 * to {@code jar}.
	 * @param coordinates the coordinates
	 * @return the artifact
	 * @throws IllegalArgumentException if the text is not in that form
	 */
	public static Artifact parse(String coordinates) {
		Matcher m = COORDINATES.matcher(coordinates);
		if (!m.matches()) {
			throw new IllegalArgumentException(
					"not Maven coordinates (groupId:artifactId[:extension[:classifier]]:version): '" + coordinates
							+ "'");
		}
		String extension = m.group(4) == null || m.group(4).isEmpty() ? "jar" : m.group(4);
		String classifier = m.group(6) == null ? "" : m.group(6);
		return new Artifact(m.group(1), m.group(2), m.group(7), classifier, extension);
	}

	/**
	 * Returns this artifact at another version.
	 * @param newVersion the version
	 * @return the artifact
	 */
	public Artifact withVersion(String newVersion) {
		return new Artifact(this.groupId, this.artifactId, newVersion, this.classifier, this.extension);
	}

	/**
	 * Returns the POM that describes this artifact: same group, artifact id and version,
	 * no classifier, extension {@code pom}.
	 * @return the POM artifact
	 */
	public Artifact pom() {
		return new Artifact(this.groupId, this.artifactId, this.version, "", "pom");
	}

	/**
	 * Returns the path of this artifact in the Maven 2 repository layout, relative to the
	 * repository root: {@code org/example/lib/1.0/lib-1.0-sources.jar}. A timestamped
	 * snapshot is in its base version's directory
	 * ({@code lib/1.0-SNAPSHOT/lib-1.0-20240101.123456-1.jar}).
	 * @return the relative path, {@code /}-separated
	 */
	public String path() {
		StringBuilder path = new StringBuilder(128);
		path.append(this.groupId.replace('.', '/')).append('/');
		path.append(this.artifactId).append('/').append(baseVersion()).append('/');
		path.append(this.artifactId).append('-').append(this.version);
		if (!this.classifier.isEmpty()) {
			path.append('-').append(this.classifier);
		}
		return path.append('.').append(this.extension).toString();
	}

	/**
	 * Returns what two versions of one artifact share, the identity a dependency cycle
	 * and a version conflict are decided by:
	 * {@code groupId:artifactId:extension:classifier}.
	 * @return the versionless key
	 */
	public String versionlessKey() {
		return this.groupId + ':' + this.artifactId + ':' + this.extension + ':' + this.classifier;
	}

	/**
	 * Whether the version is a snapshot: {@code -SNAPSHOT}, or one deployed under a
	 * timestamp ({@code 1.0-20240101.123456-1}).
	 * @return whether it is a snapshot
	 */
	public boolean isSnapshot() {
		return this.version.endsWith(SNAPSHOT) || TIMESTAMPED_SNAPSHOT.matcher(this.version).matches();
	}

	/**
	 * Returns the version a snapshot is deployed under -- {@code 1.0-SNAPSHOT} for
	 * {@code 1.0-20240101.123456-1} -- and any other version itself. It names the
	 * directory the artifact's files are in.
	 * @return the base version
	 */
	public String baseVersion() {
		if (VersionConstraint.isRange(this.version)) {
			return this.version;
		}
		Matcher m = TIMESTAMPED_SNAPSHOT.matcher(this.version);
		if (!m.matches()) {
			return this.version;
		}
		return m.group(1) == null ? SNAPSHOT : m.group(1) + SNAPSHOT;
	}

	/**
	 * Whether the version is a range ({@code [1.0,2.0)}, {@code (,1.0]}, ...), which
	 * names no single artifact.
	 * @return whether it is a range
	 */
	public boolean isVersionRange() {
		return VersionConstraint.isRange(this.version);
	}

	/**
	 * Maven's spelling: {@code groupId:artifactId:extension[:classifier]:version}.
	 * @return the coordinates
	 */
	@Override
	public String toString() {
		StringBuilder text = new StringBuilder(64);
		text.append(this.groupId).append(':').append(this.artifactId).append(':').append(this.extension);
		if (!this.classifier.isEmpty()) {
			text.append(':').append(this.classifier);
		}
		return text.append(':').append(this.version).toString();
	}

}
