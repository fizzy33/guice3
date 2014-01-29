package com.google.inject.servlet;


import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;



public class RequestResponseStack {

	private List<GuiceFilter.Context> _stack = new ArrayList<GuiceFilter.Context>();


	public void push(ServletRequest req, ServletResponse resp) {
		GuiceFilter.Context ctx = new GuiceFilter.Context((HttpServletRequest)req, (HttpServletResponse)resp);
		push(ctx);
	}
	public void push(GuiceFilter.Context context) {
		_stack.add(context);
	}

	public void pop() {
		_stack.remove(_stack.size()-1);
	}

	public GuiceFilter.Context currentContext() {
		return _stack.get(_stack.size()-1);
	}

}
