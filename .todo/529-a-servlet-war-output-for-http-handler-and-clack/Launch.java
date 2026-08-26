import java.io.File;
import org.apache.catalina.startup.Tomcat;

public class Launch {
	public static void main(String[] args) throws Exception {
		String contextPath = args.length > 1 ? args[1] : "";
		Tomcat tomcat = new Tomcat();
		tomcat.setBaseDir(new File("tomcat-base").getAbsolutePath());
		tomcat.setPort(18080);
		tomcat.getConnector();
		if (args.length > 2) {
			tomcat.getConnector().setProperty("maxThreads", args[2]);
			tomcat.getConnector().setProperty("minSpareThreads", args[2]);
		}
		tomcat.addWebapp(contextPath, new File(args[0]).getAbsolutePath());
		tomcat.start();
		System.out.println("READY on 18080 context='" + contextPath + "' maxThreads="
				+ (args.length > 2 ? args[2] : "default"));
		tomcat.getServer().await();
	}
}
