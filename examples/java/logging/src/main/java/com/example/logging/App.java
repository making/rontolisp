package com.example.logging;

import java.util.logging.Level;
import java.util.logging.Logger;

// An order book that logs. Orders logs through java.util.logging, Stock through SLF4J,
// which slf4j-jdk14 binds to java.util.logging, Payments through Commons Logging, which
// logs through SLF4J. The program's own handler prints the records of
// com.example.logging on the standard output; each record also goes on to the root
// logger's handler: the standard error, or the host's log in a component built with
// --wasi-logging.
public class App {

	// A level above SEVERE, which the host's log calls critical.
	static final Level ALERT = new Level("ALERT", 1100) {
	};

	// Held, as a JDK may collect a logger nothing holds, and its handler with it.
	private static final Logger APP = Logger.getLogger("com.example.logging");

	public static void main(String[] args) {
		APP.addHandler(new StdoutHandler(new LineFormatter()));
		Orders.LOG.setLevel(Level.FINE);

		Stock stock = new Stock();
		stock.add("tea", 3);
		stock.add("coffee", 1);
		Orders orders = new Orders(stock);
		orders.place("tea", 2);
		orders.place("coffee", 2);
		stock.count("milk", "a dozen");
		orders.hold(3, "a new address");
		Payments payments = new Payments();
		payments.charge(1, 6);
		payments.charge(2, 0);

		// Outside com.example.logging: the root logger's handler alone.
		Logger.getGlobal().info("closed");
	}

}
