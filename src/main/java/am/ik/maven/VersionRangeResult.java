package am.ik.maven;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Maven's version range resolution of one artifact ({@code VersionRangeResult}).
 *
 * @param constraint the constraint the version wrote
 * @param versions the versions it admits that the metadata lists, ascending (a plain
 * version admits itself without any metadata)
 * @param problems what could not be read on the way: a repository's metadata that failed
 * to transfer, or was invalid
 */
record VersionRangeResult(VersionConstraint constraint, List<String> versions, List<String> problems) {

	/**
	 * Copies the lists.
	 * @param constraint the constraint
	 * @param versions the versions
	 * @param problems the problems
	 */
	VersionRangeResult {
		versions = List.copyOf(versions);
		problems = List.copyOf(problems);
	}

	/**
	 * The highest version.
	 * @return it, or {@code null} when there is none
	 */
	@Nullable String highest() {
		return this.versions.isEmpty() ? null : this.versions.get(this.versions.size() - 1);
	}

}
