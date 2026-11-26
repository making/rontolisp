package com.example.logging;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

// The payments, logged through Commons Logging, which finds SLF4J beside it and logs
// through it. Each record is a java.util.logging record of the logger
// com.example.logging.Payments, naming the method that called Commons Logging.
class Payments {

	private static final Log log = LogFactory.getLog(Payments.class);

	private int total;

	void charge(int order, int amount) {
		if (amount <= 0) {
			log.error("order " + order + " not charged", new IllegalArgumentException("amount " + amount));
			return;
		}
		this.total += amount;
		log.info("order " + order + " charged " + amount + ", " + this.total + " in all");
		// DEBUG is FINE, below the level this logger inherits: printed nowhere.
		log.debug("total " + this.total);
	}

}
