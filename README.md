guice
=====

A fork of google guice with a single tweak to ManagedFilterPipeline to handle wrapping servlet request and responses.  

This resolves the problem of having filters A->B->C wrap the req as follows req->A(req)->B(req)->C(req).  With vanilla guice 2.0 if you request injection of a request object you get back req.  This tweak means you will get back C(req) instead (assuming that you are at that depth in the stack.

Effectively this changes one unintuitive behaviour (not getting the wrapped request) with another (injections of a request at different call stack levels will result in a different request).  I/we felt the latter was less unintuitive and easier to program around.  With getting the original request things like compression, multi-part upload handling had ot be done outside of the guice filter chain which made us sad.

