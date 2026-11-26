package annotated;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import am.ik.servlet.httpserver.ServletHttpServer;

/** The whole of an application that registers nothing: it starts the server. */
public class Main {

	public static void main(String[] args) throws Exception {
		ServletHttpServer server = ServletHttpServer.builder()
			.address(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
			.contextPath(args[0])
			.discover()
			.start();
		System.out.println(server.port());
		// until whoever started it closes its input
		while (System.in.read() >= 0) {
		}
		server.stop();
	}

}
