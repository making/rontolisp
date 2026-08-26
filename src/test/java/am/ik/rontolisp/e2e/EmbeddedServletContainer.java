package am.ik.rontolisp.e2e;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.catalina.startup.Tomcat;

/**
 * Deploys a rontolisp-compiled war into an embedded Tomcat. Three suites need the same
 * five lines -- {@link WarE2eTest} for the transport itself, {@link ClackE2eTest} and
 * {@link NingleE2eTest} for the Clack applications that ride it -- and the war is
 * deployed UNMODIFIED in all three, which is the claim: the container finds the program
 * through the {@code ServletContainerInitializer} the war carries, with no
 * {@code web.xml} and no configuration.
 */
final class EmbeddedServletContainer {

	private EmbeddedServletContainer() {
	}

	/**
	 * Starts a Tomcat on an ephemeral port with the war deployed at {@code contextPath}.
	 * @param baseDir a directory to use as the container's CATALINA_BASE
	 * @param war the war to deploy
	 * @param contextPath the context path ({@code ""} for the root context)
	 * @param maxThreads the connector pool size, or 0 for Tomcat's default -- pinning it
	 * far below the request burst is what makes the async transport's
	 * one-virtual-thread-per-request rule observable
	 * @return the started container, which the caller stops and destroys
	 */
	static Tomcat tomcat(Path baseDir, Path war, String contextPath, int maxThreads) throws Exception {
		Path base = Files.createDirectories(baseDir);
		Files.createDirectories(base.resolve("webapps"));
		Tomcat tomcat = new Tomcat();
		tomcat.setBaseDir(base.toAbsolutePath().toString());
		tomcat.setPort(0);
		tomcat.getConnector();
		if (maxThreads > 0) {
			tomcat.getConnector().setProperty("maxThreads", String.valueOf(maxThreads));
			tomcat.getConnector().setProperty("minSpareThreads", String.valueOf(maxThreads));
		}
		tomcat.addWebapp(contextPath, new File(war.toString()).getAbsolutePath());
		tomcat.start();
		return tomcat;
	}

}
