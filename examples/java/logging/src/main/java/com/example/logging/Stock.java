package com.example.logging;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// The stock, logged through SLF4J. Each record is a java.util.logging record of the
// logger com.example.logging.Stock, naming the method that called SLF4J.
class Stock {

	private static final Logger log = LoggerFactory.getLogger(Stock.class);

	private final Map<String, Integer> counts = new LinkedHashMap<>();

	void add(String item, int count) {
		this.counts.merge(item, count, Integer::sum);
		// DEBUG is FINE, below the level this logger inherits: printed nowhere.
		log.debug("{} + {}", item, count);
	}

	void reserve(String item, int quantity) {
		int left = this.counts.getOrDefault(item, 0);
		if (left < quantity) {
			log.atWarn().setMessage("{}: {} asked, {} left").addArgument(item).addArgument(quantity)
				.addArgument(left).log();
			throw new IllegalStateException(item + " short");
		}
		this.counts.put(item, left - quantity);
		log.info("{} left of {}", left - quantity, item);
	}

	void count(String item, String text) {
		try {
			this.counts.put(item, Integer.parseInt(text));
		}
		catch (NumberFormatException ex) {
			log.error("count of {} not read", item, ex);
		}
	}

}
