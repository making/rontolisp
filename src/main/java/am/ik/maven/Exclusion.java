package am.ik.maven;

import java.util.Objects;

/**
 * A dependency exclusion: the artifacts a dependency's subtree must not contain. Either
 * part may be {@code *}, matching anything; a part a POM leaves out is the empty string,
 * which matches nothing, as in Maven.
 *
 * @param groupId the excluded group id, or {@code *}
 * @param artifactId the excluded artifact id, or {@code *}
 */
public record Exclusion(String groupId, String artifactId) {

	/**
	 * Validates that no part is {@code null}.
	 * @param groupId the excluded group id, or {@code *}
	 * @param artifactId the excluded artifact id, or {@code *}
	 */
	public Exclusion {
		Objects.requireNonNull(groupId, "groupId");
		Objects.requireNonNull(artifactId, "artifactId");
	}

	/**
	 * Answers whether this exclusion removes {@code artifact}.
	 * @param artifact the candidate
	 * @return {@code true} when both parts match
	 */
	public boolean matches(Artifact artifact) {
		return matches(this.groupId, artifact.groupId()) && matches(this.artifactId, artifact.artifactId());
	}

	private static boolean matches(String pattern, String value) {
		return "*".equals(pattern) || pattern.equals(value);
	}

	/**
	 * {@code groupId:artifactId}.
	 * @return the exclusion
	 */
	@Override
	public String toString() {
		return this.groupId + ':' + this.artifactId;
	}

}
