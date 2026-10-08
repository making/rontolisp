package am.ik.maven;

import java.io.IOException;

/**
 * A resolution that cannot answer: a transfer failure, a parent or imported POM no
 * repository has, a version no metadata resolves or a version conflict no version
 * settles, or a request this resolver refuses by name (a version range where one artifact
 * is meant, a {@code settings.xml} mirror URL it cannot read, offline mode). A POM that
 * is missing or invalid is not one of these: its descriptor answers no dependencies and a
 * warning, as Maven's does -- except a POM read from bytes
 * ({@link MavenResolver#projectDependencies}), whose invalidity is one.
 */
public class MavenResolutionException extends IOException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates the exception.
	 * @param message what failed
	 */
	public MavenResolutionException(String message) {
		super(message);
	}

	/**
	 * Creates the exception.
	 * @param message what failed
	 * @param cause the underlying failure
	 */
	public MavenResolutionException(String message, Throwable cause) {
		super(message, cause);
	}

}
