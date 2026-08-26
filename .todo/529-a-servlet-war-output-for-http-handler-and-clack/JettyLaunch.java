import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;

public class JettyLaunch {
	public static void main(String[] args) throws Exception {
		Server server = new Server(18081);
		WebAppContext context = new WebAppContext();
		context.setContextPath(args.length > 1 ? args[1] : "/");
		context.setWar(args[0]);
		// Jetty needs the annotation configuration enabled to run SCIs / @HandlesTypes.
		context.addConfiguration(new org.eclipse.jetty.ee10.annotations.AnnotationConfiguration());
		server.setHandler(context);
		server.start();
		System.out.println("JETTY READY on 18081");
		server.join();
	}
}
