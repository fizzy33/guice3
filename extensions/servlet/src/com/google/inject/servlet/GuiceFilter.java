/**
 * Copyright (C) 2006 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.inject.servlet;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.inject.Inject;
import com.google.inject.OutOfScopeException;
import com.google.inject.internal.util.Preconditions;

/**
 * <p>
 * Apply this filter in web.xml above all other filters (typically), to all requests where you plan
 *  to use servlet scopes. This is also needed in order to dispatch requests to injectable filters
 *  and servlets:
 *  <pre>
 *  &lt;filter&gt;
 *    &lt;filter-name&gt;guiceFilter&lt;/filter-name&gt;
 *    &lt;filter-class&gt;<b>com.google.inject.servlet.GuiceFilter</b>&lt;/filter-class&gt;
 *  &lt;/filter&gt;
 *
 *  &lt;filter-mapping&gt;
 *    &lt;filter-name&gt;guiceFilter&lt;/filter-name&gt;
 *    &lt;url-pattern&gt;/*&lt;/url-pattern&gt;
 *  &lt;/filter-mapping&gt;
 *  </pre>
 *
 * This filter must appear before every filter that makes use of Guice injection or servlet
 * scopes functionality. Typically, you will only register this filter in web.xml and register
 * any other filters (and servlets) using a {@link ServletModule}.
 *
 * @author crazybob@google.com (Bob Lee)
 * @author dhanji@gmail.com (Dhanji R. Prasanna)
 */
public class GuiceFilter implements Filter {
	
  private static final ThreadLocal<RequestResponseStack> threadStorage = new ThreadLocal<RequestResponseStack>();
  static volatile FilterPipeline pipeline = new DefaultFilterPipeline();

  /**
   * We allow both the static and dynamic versions of the pipeline to exist.
   */
  @Inject
  private final FilterPipeline injectedPipeline = null;

  /** Used to inject the servlets configured via {@link ServletModule} */
  static volatile WeakReference<ServletContext> servletContext =
      new WeakReference<ServletContext>(null);

  private static final String MULTIPLE_INJECTORS_WARNING =
      "Multiple Servlet injectors detected. This is a warning "
      + "indicating that you have more than one "
      + GuiceFilter.class.getSimpleName() + " running "
      + "in your web application. If this is deliberate, you may safely "
      + "ignore this message. If this is NOT deliberate however, "
      + "your application may not work as expected.";

  //VisibleForTesting
  @Inject
  static void setPipeline(FilterPipeline pipeline) {

    // This can happen if you create many injectors and they all have their own
    // servlet module. This is legal, caveat a small warning.
    if (GuiceFilter.pipeline instanceof ManagedFilterPipeline) {
        Logger.getLogger(GuiceFilter.class.getName()).warning(MULTIPLE_INJECTORS_WARNING);
    }

    // We overwrite the default pipeline
    GuiceFilter.pipeline = pipeline;
  }

  //VisibleForTesting
  static void reset() {
    pipeline = new DefaultFilterPipeline();
  }

  public void doFilter(ServletRequest servletRequest,
      ServletResponse servletResponse, FilterChain filterChain)
      throws IOException, ServletException {

	RequestResponseStack reqRespStack = new RequestResponseStack();
	threadStorage.set(reqRespStack);

    // Prefer the injected pipeline, but fall back on the static one for web.xml users.
    FilterPipeline filterPipeline = null != injectedPipeline ? injectedPipeline : pipeline;

    reqRespStack.push(servletRequest, servletResponse);
    try {
      //dispatch across the servlet pipeline, ensuring web.xml's filterchain is honored
      filterPipeline.dispatch(servletRequest, servletResponse, filterChain);

    } finally {
    	threadStorage.set(null);
    }
  }

  static HttpServletRequest getRequest() {
    return getContext().getRequest();
  }

  static HttpServletResponse getResponse() {
    return getContext().getResponse();
  }

  static ServletContext getServletContext() {
    return servletContext.get();
  }

  public static ThreadLocal<RequestResponseStack> getThreadStorage() {
	  return threadStorage;
  }
  
  public static RequestResponseStack getRequestResponseStack() {
	  return threadStorage.get();
  }
  
  public static Context getContext() {
	  RequestResponseStack stack = threadStorage.get();
	    if (stack == null) {
	        throw new OutOfScopeException("Cannot access scoped object. Either we"
	            + " are not currently inside an HTTP Servlet request, or you may"
	            + " have forgotten to apply " + GuiceFilter.class.getName()
	            + " as a servlet filter for this request.");
	      }
    return stack.currentContext();
  }

  public static class Context {

    final HttpServletRequest request;
    final HttpServletResponse response;

    public Context(HttpServletRequest request, HttpServletResponse response) {
      this.request = request;
      this.response = response;
    }

    public HttpServletRequest getRequest() {
      return request;
    }

	public HttpServletResponse getResponse() {
      return response;
    }
	
  }

  public void init(FilterConfig filterConfig) throws ServletException {
    final ServletContext servletContext = filterConfig.getServletContext();

    // Store servlet context in a weakreference, for injection
    GuiceFilter.servletContext = new WeakReference<ServletContext>(servletContext);

    // In the default pipeline, this is a noop. However, if replaced
    // by a managed pipeline, a lazy init will be triggered the first time
    // dispatch occurs.
    FilterPipeline filterPipeline = null != injectedPipeline ? injectedPipeline : pipeline;
    filterPipeline.initPipeline(servletContext);
  }
  
  public static <T> T withStack(ServletRequest req, ServletResponse resp, Callable<T> callable) {
      Preconditions.checkState(null == GuiceFilter.getRequestResponseStack(),
              "Cannot continue request in the same thread as another HTTP request!");
      RequestResponseStack reqRespStack = new RequestResponseStack();
	  threadStorage.set(reqRespStack);
	  reqRespStack.push(req, resp);
	  try {
		  return callable.call();
	  } catch ( Exception e ) {
		  throw throwUnchecked(e);
	  } finally {
	    threadStorage.remove();
	  }
  }

  public void destroy() {

    try {
      // Destroy all registered filters & servlets in that order
      FilterPipeline filterPipeline = null != injectedPipeline ? injectedPipeline : pipeline;
      filterPipeline.destroyPipeline();

    } finally {
      reset();
      servletContext.clear();
    }
  }
  

  public static RuntimeException throwUnchecked(Throwable th) {
      GuiceFilter.<RuntimeException>throwUncheckedImpl(th);
      return null;
  }


  /**
   * Remember, Generics are erased in Java. So this basically throws an Exception. The real
   * Type of T is lost during the compilation
   */
  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void throwUncheckedImpl(Throwable toThrow) throws T {
      // Since the type is erased, this cast actually does nothing!!!
      // we can throw any exception
      throw (T) toThrow;
  }
  
}
