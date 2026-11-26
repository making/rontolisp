package am.ik.servlet.httpserver;

import java.io.File;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.ErrorPage;
import org.apache.tomcat.util.scan.StandardJarScanner;
import org.eclipse.jetty.ee11.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.ee11.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

/**
 * The containers one application is asked in: this module's, and the two it is measured
 * against where the specification leaves room.
 */
enum Container {

	HTTPSERVER {
		@Override
		Running start(String contextPath, Path documentRoot, Consumer<ServletContext> application) throws Exception {
			ServletHttpServer.Builder builder = ServletHttpServer.builder()
				.address(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
				.contextPath(contextPath)
				.errorPage(ERROR_STATUS, ERROR_BY_STATUS)
				.errorPage(Application.Refused.class, ERROR_BY_TYPE)
				.onStartup(application);
			if (documentRoot != null) {
				builder.documentRoot(documentRoot);
			}
			ServletHttpServer server = builder.start();
			return new Running(server.port(), server::stop);
		}

		@Override
		Running deploy(String contextPath, Path war) throws Exception {
			// A JVM of its own, whose class path is the application's: its classes, its
			// archives -- named by the manifest of an archive beside the application, as
			// an executable archive names its libraries -- this container and the API.
			// Its main starts the server and registers nothing.
			Path launcher = Files.createDirectories(war.resolveSibling(war.getFileName() + "-launcher"))
				.resolve("launcher.jar");
			StringBuilder named = new StringBuilder();
			try (DirectoryStream<Path> archives = Files.newDirectoryStream(war.resolve("WEB-INF/lib"))) {
				archives.forEach(archive -> named.append(' ')
					.append(launcher.getParent().relativize(archive).toString().replace(File.separatorChar, '/')));
			}
			Manifest manifest = new Manifest();
			manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
			manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, named.toString().strip());
			try (OutputStream out = Files.newOutputStream(launcher)) {
				new JarOutputStream(out, manifest).close();
			}
			List<String> classPath = new ArrayList<>(
					List.of(war.resolve("WEB-INF/classes").toString(), launcher.toString()));
			classPath.add(location(ServletHttpServer.class));
			classPath.add(location(Servlet.class));
			Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
					"-cp", String.join(File.pathSeparator, classPath), "annotated.Main", contextPath)
				.redirectError(ProcessBuilder.Redirect.INHERIT)
				.start();
			String port = process.inputReader().readLine();
			if (port == null) {
				throw new IllegalStateException("the application ended with " + process.waitFor());
			}
			return new Running(Integer.parseInt(port), () -> {
				process.getOutputStream().close();
				if (!process.waitFor(30, TimeUnit.SECONDS)) {
					process.destroyForcibly();
				}
			});
		}
	},

	TOMCAT {
		@Override
		Running start(String contextPath, Path documentRoot, Consumer<ServletContext> application) throws Exception {
			File base = Files.createTempDirectory("servlet-httpserver-tomcat").toFile();
			Tomcat tomcat = new Tomcat();
			tomcat.setBaseDir(base.getAbsolutePath());
			tomcat.setHostname("127.0.0.1");
			tomcat.setPort(0);
			tomcat.getConnector().setProperty("address", "127.0.0.1");
			Context context = tomcat.addContext(contextPath,
					documentRoot != null ? documentRoot.toString() : base.getAbsolutePath());
			if (documentRoot != null) {
				serveResources(context);
			}
			ErrorPage byStatus = new ErrorPage();
			byStatus.setErrorCode(ERROR_STATUS);
			byStatus.setLocation(ERROR_BY_STATUS);
			context.addErrorPage(byStatus);
			ErrorPage byType = new ErrorPage();
			byType.setExceptionType(Application.Refused.class.getName());
			byType.setLocation(ERROR_BY_TYPE);
			context.addErrorPage(byType);
			context.addServletContainerInitializer((classes, servletContext) -> application.accept(servletContext),
					null);
			tomcat.start();
			return new Running(tomcat.getConnector().getLocalPort(), () -> {
				tomcat.stop();
				tomcat.destroy();
			});
		}

		@Override
		Running deploy(String contextPath, Path war) throws Exception {
			File base = Files.createTempDirectory("servlet-httpserver-tomcat").toFile();
			Tomcat tomcat = new Tomcat();
			tomcat.setBaseDir(base.getAbsolutePath());
			tomcat.setHostname("127.0.0.1");
			tomcat.setPort(0);
			tomcat.getConnector().setProperty("address", "127.0.0.1");
			// No JSP servlet: the application is what it declares, and its resources.
			tomcat.setAddDefaultWebXmlToWebapp(false);
			Context context = tomcat.addWebapp(contextPath, war.toString());
			serveResources(context);
			// The application is its own classes and archives, not the test's class path.
			((StandardJarScanner) context.getJarScanner()).setScanClassPath(false);
			boolean started;
			try {
				tomcat.start();
				started = context.getState().isAvailable();
			}
			catch (LifecycleException ex) {
				// The application failed the start of the server.
				started = false;
			}
			return new Running(tomcat.getConnector().getLocalPort(), started, () -> {
				tomcat.stop();
				tomcat.destroy();
			});
		}
	},

	JETTY {
		@Override
		Running start(String contextPath, Path documentRoot, Consumer<ServletContext> application) throws Exception {
			Server server = new Server();
			ServerConnector connector = new ServerConnector(server);
			connector.setHost("127.0.0.1");
			connector.setPort(0);
			server.addConnector(connector);
			ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
			context.setContextPath(contextPath.isEmpty() ? "/" : contextPath);
			if (documentRoot != null) {
				context.setBaseResourceAsPath(documentRoot);
				context.setWelcomeFiles(new String[] { "index.html", "index.htm" });
				ServletHolder resources = new ServletHolder("default",
						org.eclipse.jetty.ee11.servlet.DefaultServlet.class);
				resources.setInitParameter("dirAllowed", "false");
				context.addServlet(resources, "/");
			}
			ErrorPageErrorHandler errorPages = new ErrorPageErrorHandler();
			errorPages.addErrorPage(ERROR_STATUS, ERROR_BY_STATUS);
			errorPages.addErrorPage(Application.Refused.class, ERROR_BY_TYPE);
			context.setErrorHandler(errorPages);
			context.addServletContainerInitializer((classes, servletContext) -> application.accept(servletContext));
			server.setHandler(context);
			server.start();
			return new Running(connector.getLocalPort(), server::stop);
		}

		@Override
		Running deploy(String contextPath, Path war) throws Exception {
			Server server = new Server();
			ServerConnector connector = new ServerConnector(server);
			connector.setHost("127.0.0.1");
			connector.setPort(0);
			server.addConnector(connector);
			WebAppContext context = new WebAppContext();
			context.setContextPath(contextPath);
			context.setWar(war.toString());
			server.setHandler(context);
			boolean started;
			try {
				server.start();
				started = context.isAvailable();
			}
			catch (Exception ex) {
				// The application failed the start of the server.
				started = false;
			}
			boolean failed = !started;
			return new Running(connector.getLocalPort(), started, () -> {
				try {
					server.stop();
				}
				catch (Exception ex) {
					// What failed the start is thrown again by the stop.
					if (!failed) {
						throw ex;
					}
				}
			});
		}
	};

	/** The status an error page of every application started in code answers. */
	static final int ERROR_STATUS = 410;

	/** The error page of that status. */
	static final String ERROR_BY_STATUS = "/failed/status";

	/** The error page of an {@link Application.Refused} and its subclasses. */
	static final String ERROR_BY_TYPE = "/failed/type";

	/**
	 * Tomcat's default servlet, its welcome files and its media types: what its default
	 * descriptor declares but for JSP.
	 */
	private static void serveResources(Context context) {
		Wrapper resources = Tomcat.addServlet(context, "default", new org.apache.catalina.servlets.DefaultServlet());
		resources.addInitParameter("listings", "false");
		context.addServletMappingDecoded("/", "default");
		context.addWelcomeFile("index.html");
		context.addWelcomeFile("index.htm");
		Tomcat.addDefaultMimeTypeMappings(context);
	}

	/** Where a class's files are: a directory or an archive of the class path. */
	static String location(Class<?> type) throws URISyntaxException {
		return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
	}

	/** Starts an application that registers itself in code. */
	Running start(String contextPath, Consumer<ServletContext> application) throws Exception {
		return start(contextPath, null, application);
	}

	/**
	 * Starts an application that registers itself in code, whose resources are the files
	 * of a directory, served by the container's default servlet.
	 */
	abstract Running start(String contextPath, Path documentRoot, Consumer<ServletContext> application)
			throws Exception;

	/**
	 * Starts an application the container discovers: an exploded web application, its
	 * classes under {@code WEB-INF/classes} and its archives under {@code WEB-INF/lib}.
	 */
	abstract Running deploy(String contextPath, Path war) throws Exception;

	interface Stop {

		void stop() throws Exception;

	}

	/** A started container: its port, and how it is stopped. */
	static final class Running {

		final int port;

		/** Whether the application started: a container may serve one that did not. */
		final boolean started;

		private final Stop stop;

		Running(int port, Stop stop) {
			this(port, true, stop);
		}

		Running(int port, boolean started, Stop stop) {
			this.port = port;
			this.started = started;
			this.stop = stop;
		}

		void stop() throws Exception {
			this.stop.stop();
		}

	}

}
