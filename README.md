# Apache HTTP Components Metadata Filter

[![Continuous Integration](https://github.com/l0s/httpcomponents-metadata-filter/actions/workflows/ci.yml/badge.svg)](https://github.com/l0s/httpcomponents-metadata-filter/actions/workflows/ci.yml)

This is a plugin to the Apache HTTP Client (4.x) that blocks access to
internal metadata APIs for most popular cloud environments. This is
meant to help prevent against a class of server-side request forgery
(SSRF) attacks similar to the one used in the [2019 Capital One
breach](https://blog.appsecco.com/an-ssrf-privileged-aws-keys-and-the-capital-one-breach-4c3c2cded3af).

This plugin works by blocking access to all link local addresses as well
as specific well-known metadata host names. It is assumed that clients
with this plugin installed would only need to access user-provided URLs
and would never need to access link local addresses or cloud metadata
APIs.

The implementation code is minimal. Because use cases may vary,
integrators should consider implementing the logic directly if this
plugin does not meet their needs exactly.

## Usage

Install this plugin using an `HttpClientBuilder`:

    import com.macasaet.apache.http.InternalAddressFilteringRequestInterceptor;

...

    final HttpClientBuilder builder = HttpClientBuilder.create();
    builder.addInterceptorFirst( new InternalAddressFilteringRequestInterceptor() ); // order does not matter
    final CloseableHttpClient client = builder.build();

Note that if you have a custom `HttpProcessor`, using it will disable
any request or response interceptors. So you will need to wrap it:

    final HttpClientBuilder builder = HttpClientBuilder.create();
    final HttpProcessor compositeProcessor = new InternalAddressFilteringRequestInterceptor().wrap( customProcessor );
    builder.setHttpProcessor( compositeProcessor );
    final CloseableHttpClient client = builder.build(); 

## Approach

The Apache HTTP Client offers several pluggable mechanisms to alter the
behaviour of HTTP requests. Ultimately, I chose to implement an
`HttpRequestInterceptor`. This is what a typical request/response looks
like:

    HttpRoutePlanner::determineRoute enter: http://host.example.com:8080, GET http://host.example.com:8080/valid HTTP/1.1 []
    HttpRoutePlanner::determineRoute exit: http://host.example.com:8080, GET http://host.example.com:8080/valid HTTP/1.1 []
    first request interceptor: GET /valid HTTP/1.1 [], org.apache.http.client.protocol.HttpClientContext@13acb0d1
    last request interceptor: GET /valid HTTP/1.1 [Host: host.example.com:8080, Connection: Keep-Alive, User-Agent: Apache-HttpClient/4.5.9 (Java/1.8.0_121), Accept-Encoding: gzip,deflate], org.apache.http.client.protocol.HttpClientContext@13acb0d1
    HttpClientConnectionManager::requestConnection: {}->http://host.example.com:8080 ( http://host.example.com:8080 )
    HttpClientConnectionManager::connect: {}->http://host.example.com:8080 ( http://host.example.com:8080 )
    DnsResolver::resolve enter: host.example.com
    DnsResolver::resolve exit: host.example.com
    HttpRequestExecutor::execute enter: GET /valid HTTP/1.1 [Host: host.example.com:8080, Connection: Keep-Alive, User-Agent: Apache-HttpClient/4.5.9 (Java/1.8.0_121), Accept-Encoding: gzip,deflate]
    HttpRequestExecutor::execute exit: GET /valid HTTP/1.1 [Host: host.example.com:8080, Connection: Keep-Alive, User-Agent: Apache-HttpClient/4.5.9 (Java/1.8.0_121), Accept-Encoding: gzip,deflate]
    first response interceptor: HttpResponseProxy{HTTP/1.1 200 OK [Content-Type: text/html, Date: Sun, 8 Sep 2019 00:23:38 GMT, Connection: keep-alive, Content-Encoding: gzip, Transfer-Encoding: chunked] ResponseEntityProxy{[Content-Type: text/html,Content-Encoding: gzip,Chunked: true]}}, org.apache.http.client.protocol.HttpClientContext@13acb0d1
    last response interceptor: HttpResponseProxy{HTTP/1.1 200 OK [Content-Type: text/html, Date: Sun, 8 Sep 2019 00:23:38 GMT, Connection: keep-alive, Transfer-Encoding: chunked] org.apache.http.client.entity.DecompressingEntity@7c0c77c7}, org.apache.http.client.protocol.HttpClientContext@13acb0d1
    RedirectStrategy::isRedirected enter: GET http://host.example.com:8080/valid HTTP/1.1
    RedirectStrategy::isRedirected exit: GET http://host.example.com:8080/valid HTTP/1.1

### Potential Injection Points

Here I discuss the pros and cons of various injection points. For
several of the "Cons", I mention code duplication. This is mainly a
problem because the code in `HttpClientBuilder` may change in the future
leading to inconsistencies with this plugin. I also call out whether or
not an `InetAddress` instance is available as that class provides
convenience methods for identifying various types of internal-use
hosts.

#### Route Planner (`HttpRoutePlanner`)

Pros:
* This is the first plugin to get executed so it allows us to fail fast.
* As it deals with routing, it is an obvious place to disable routes to
certain hosts.
* Strongly-typed - implementors have access to the host name on its own
  (no String manipulation required)

Cons:
* Constructing the delegate route planner is non-trivial and requires
  duplicating code in `HttpClientBuilder`.
* Although the API provides access to an `InetAddress` object, in
  practice, it is never populated.
* Does not provide an `InetAddress` instance. Implementations must
  construct one on their own, duplicating work further down the line.

#### Request Interceptor (`HttpRequestInterceptor`)

This is the approach I took.

Pros:
* Unintrusive - easy to add to existing code without requiring major
  refactoring
* You can add as many as you'd like.

Cons:
* Order matters - Some built-in request interceptors modify the request,
  so the first interceptor and last interceptor get different input. The
  implementation needs to be robust enough that it can be inserted in
  any position.
* Setting a custom `HttpProcessor` disables all request and response
  interceptors. This behaviour is *not obvious*.
* Does not provide an `InetAddress` instance. Implementations must
  construct one on their own, duplicating work further down the line.

#### Connection Manager (`HttpClientConnectionManager`)

Pros:
* Definitive arbiter of whether or not to connect to a host.
* Strongly-typed - implementors have access to the host name on its own (no
  String manipulation required)

Cons:
* Constructing the delegate connection manager is non-trivial and
  requires duplicating code in `HttpClientBuilder`.
* Setting a custom connection manager disables the `DnsResolver` which
  is not obvious.

#### DNS Resolver (`DnsResolver`)

Pros:
* Obvious place for hostname-handling logic
* Deals with `InetAddress` instances

Cons:
* Not used if the hostname is an IP address (e.g. `169.254.169.254`)
* Other components earlier in the chain attempt to resolve the host as well.

#### Request Executor (`HttpRequestExecutor`)

Pros:
* Has no dependency on any of the other pluggable mechanisms.
* Strongly-typed - implementors have access to the host name on its own (no
  String manipulation required)

Cons:
* You can only specify one. Developers who already have a custom
  implementation would need to create a composite object.
* This happens late in the request lifecycle after the connection has
  already been made.

#### Response Interceptor (`HttpResponseInterceptor`)

Pros:
* The response data allows for additional information that may help to
  decide whether or not to return the result to the client. For example,
  an `HttpClient` that is only intended to retrieve images can use the
  response `Content Type` to decide whether or not to suppress the
  results.

Cons:
* This happens late in the request lifecycle after the request has
  already been made. If the malicious behaviour depends solely on making
  the request without regard to the response, then this integration
  point is too late.
* Setting a custom `HttpProcessor` disables all request and response
  interceptors. This behaviour is *not obvious*.

#### HTTP Processor (`HttpProcessor`)

Pros:

Cons:
* Setting a custom `HttpProcessor` disables all request and response
  interceptors. This behaviour is *not obvious*.
* You can only specify one. Developers who already have a custom
  implementation would need to create a composite object.
* The default implementation has a lot of essential functionality (e.g.
  incorporating request and response interceptors). Replacing it with a
  custom implementation is risky.

## License

   Copyright 2019 Carlos Macasaet

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       https://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and 
   limitations under the License.
