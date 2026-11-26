package com.example.logging;

import java.util.logging.Level;
import java.util.logging.Logger;

// The orders, logged through java.util.logging.
class Orders {

	static final Logger LOG = Logger.getLogger(Orders.class.getName());

	private final Stock stock;

	private int next = 1;

	Orders(Stock stock) {
		this.stock = stock;
	}

	void place(String item, int quantity) {
		int id = this.next++;
		// FINE: printed, as App lowered this logger's level; the root's handler passes
		// INFO and above, so it is printed nowhere else.
		LOG.fine(() -> "order " + id + ": " + quantity + " x " + item);
		try {
			this.stock.reserve(item, quantity);
			LOG.log(Level.INFO, "order {0} placed", id);
		}
		catch (IllegalStateException ex) {
			LOG.log(Level.WARNING, "order " + id + " refused", ex);
		}
	}

	void hold(int id, String reason) {
		LOG.log(App.ALERT, "order {0} held: {1}", new Object[] { id, reason });
	}

}
