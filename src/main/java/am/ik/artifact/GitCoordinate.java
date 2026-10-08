package am.ik.artifact;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * A commit of a git repository, as a {@code deps.edn} {@code :git/url} coordinate names
 * it: the repository URL, the FULL commit sha (a short one is refused, as tools.deps
 * refuses it), an optional tag that must name that commit, and an optional sub-directory
 * of the checkout ({@code :deps/root}).
 *
 * <p>
 * Malformed input is refused here, with an {@link IllegalArgumentException}, before
 * anything runs: a URL or tag starting with {@code -} would otherwise reach the git
 * command line as an option, and a root naming {@code ..} would leave the checkout.
 *
 * @param url the repository URL (any form git accepts: {@code https://}, {@code ssh://},
 * {@code git@host:path}, {@code file://})
 * @param sha the full commit sha, lower case (40 hex digits, or 64 in a SHA-256
 * repository)
 * @param tag the tag that must name {@code sha}, or {@code null}
 * @param root the sub-directory of the checkout to answer, relative, or {@code null}
 */
public record GitCoordinate(String url, String sha, @Nullable String tag, @Nullable String root) {

	private static final Pattern FULL_SHA = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

	/**
	 * Validates and normalizes the coordinate.
	 */
	public GitCoordinate {
		Objects.requireNonNull(url, "url is required");
		Objects.requireNonNull(sha, "sha is required");
		if (url.isBlank() || url.startsWith("-") || url.chars().anyMatch(Character::isWhitespace)) {
			throw new IllegalArgumentException("not a git repository URL: '" + url + "'");
		}
		sha = sha.toLowerCase(Locale.ROOT);
		if (!FULL_SHA.matcher(sha).matches()) {
			throw new IllegalArgumentException(
					"not a full commit sha (40 hex digits; a short sha is refused): '" + sha + "' for " + url);
		}
		if (tag != null && (tag.isBlank() || tag.startsWith("-") || tag.contains("..")
				|| tag.chars().anyMatch(c -> c <= ' ' || c == '~' || c == '^' || c == ':' || c == '\\'))) {
			throw new IllegalArgumentException("not a git tag name: '" + tag + "' for " + url);
		}
		if (root != null) {
			root = normalizeRoot(root, url);
		}
	}

	/**
	 * Answers a builder.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Normalizes a relative sub-directory: {@code /} separators, no empty, {@code .} or
	 * {@code ..} segment; answers {@code null} for the checkout's own root.
	 */
	private static @Nullable String normalizeRoot(String root, String url) {
		String path = root.replace('\\', '/');
		if (path.startsWith("/") || (path.length() >= 2 && path.charAt(1) == ':')) {
			throw new IllegalArgumentException("not a relative root directory: '" + root + "' for " + url);
		}
		StringBuilder normalized = new StringBuilder();
		for (String segment : path.split("/")) {
			if (segment.isEmpty() || segment.equals(".")) {
				continue;
			}
			if (segment.equals("..")) {
				throw new IllegalArgumentException("root directory leaves the checkout: '" + root + "' for " + url);
			}
			if (!normalized.isEmpty()) {
				normalized.append('/');
			}
			normalized.append(segment);
		}
		return normalized.isEmpty() ? null : normalized.toString();
	}

	/**
	 * Builds a {@link GitCoordinate}.
	 */
	public static final class Builder {

		@Nullable private String url;

		@Nullable private String sha;

		@Nullable private String tag;

		@Nullable private String root;

		private Builder() {
		}

		/**
		 * Sets the repository URL.
		 * @param url the URL
		 * @return this builder
		 */
		public Builder url(String url) {
			this.url = url;
			return this;
		}

		/**
		 * Sets the full commit sha.
		 * @param sha the sha
		 * @return this builder
		 */
		public Builder sha(String sha) {
			this.sha = sha;
			return this;
		}

		/**
		 * Sets the tag that must name the commit.
		 * @param tag the tag, or {@code null} for none
		 * @return this builder
		 */
		public Builder tag(@Nullable String tag) {
			this.tag = tag;
			return this;
		}

		/**
		 * Sets the sub-directory of the checkout to answer.
		 * @param root the relative directory, or {@code null} for the checkout itself
		 * @return this builder
		 */
		public Builder root(@Nullable String root) {
			this.root = root;
			return this;
		}

		/**
		 * Builds the coordinate.
		 * @return the coordinate
		 * @throws IllegalArgumentException if a part is malformed
		 */
		public GitCoordinate build() {
			return new GitCoordinate(Objects.requireNonNull(this.url, "url is required"),
					Objects.requireNonNull(this.sha, "sha is required"), this.tag, this.root);
		}

	}

}
