package com.benchmarkmethodscript;

import java.util.ArrayList;
import java.util.List;

/**
 *
 */
public class Log {
	private StringBuilder log = new StringBuilder();
	private List<LogHandler> handlers = new ArrayList<>();

	public Log() {

	}

	public void addLog(String s) {
		log.append(s).append("\n");
		for(LogHandler h : handlers) {
			try {
				h.handle(s);
			} catch (Throwable t) {
				//
			}
		}
	}

	public String getLog() {
		return log.toString();
	}

	public void clear() {
		log = new StringBuilder();
	}

	public static interface LogHandler {
		void handle(String input);
	}

	public void addLogHandler(LogHandler handler) {
		if(handler == null) {
			throw new NullPointerException("Handler can't be null");
		}
		handlers.add(handler);
	}

}
