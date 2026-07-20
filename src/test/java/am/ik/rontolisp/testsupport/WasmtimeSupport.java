package am.ik.rontolisp.testsupport;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.PullPolicy;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared handle to the {@code wasmtime} container used by every WASM integration test.
 *
 * <p>
 * The container runs a prebuilt image (Debian + a pinned {@code wasmtime} on PATH)
 * published to GHCR by {@code .github/workflows/wasmtime-image.yaml}. Pulling a ready
 * image replaces the previous per-run install (a {@code debian:bookworm-slim} image that
 * downloaded and installed {@code wasmtime} on first use) -- that install was the slow
 * step that made the Unit Tests job look stuck.
 *
 * <p>
 * A single container is started lazily on first use and shared across every test class in
 * the JVM; Testcontainers' Ryuk reaps it at JVM shutdown. Callers that must still run
 * their JVM-only backends when Docker is absent guard the WASM tests with
 * {@link #DOCKER_AVAILABLE} (or a class-level
 * {@code @Testcontainers(disabledWithoutDocker
 * = true)}); {@link #container()} contacts Docker only when actually called.
 *
 * <p>
 * The image tracks the {@code :latest} tag and is always re-pulled: the wasmtime version
 * is managed at the source ({@code WASMTIME_VERSION} in the image workflow and the
 * {@code Dockerfile} ARG), so bumping it there and re-running the workflow rolls every
 * test onto the new wasmtime with no change here. Keep that version {@code >= 46} (the
 * {@code --component}/WASI 0.3 tests need wasmtime 46+).
 */
public final class WasmtimeSupport {

	/** The prebuilt image, tracking {@code :latest} (see the class Javadoc). */
	public static final DockerImageName IMAGE = DockerImageName.parse("ghcr.io/making/rontolisp-wasmtime:latest");

	/** Whether a Docker daemon is reachable; the WASM backends are skipped without it. */
	public static final boolean DOCKER_AVAILABLE = DockerClientFactory.instance().isDockerAvailable();

	// Always re-pull: :latest is mutable, so a stale local copy must not shadow a freshly
	// published image.
	private static final GenericContainer<?> CONTAINER = new GenericContainer<>(IMAGE)
		.withImagePullPolicy(PullPolicy.alwaysPull())
		.withCommand("sleep", "infinity");

	private static boolean started = false;

	private WasmtimeSupport() {
	}

	/**
	 * Returns the shared container, starting it on the first call. Only call this when
	 * {@link #DOCKER_AVAILABLE} is {@code true} (or the enclosing class is disabled
	 * without Docker); the first call contacts the Docker daemon.
	 * @return the running {@code wasmtime} container
	 */
	public static synchronized GenericContainer<?> container() {
		if (!started) {
			CONTAINER.start();
			started = true;
		}
		return CONTAINER;
	}

}
