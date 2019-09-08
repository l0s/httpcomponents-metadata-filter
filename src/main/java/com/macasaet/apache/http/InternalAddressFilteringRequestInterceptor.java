/*
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
*/
package com.macasaet.apache.http;

import static java.net.InetAddress.getAllByName;
import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableList;
import static org.apache.http.client.protocol.HttpClientContext.HTTP_ROUTE;
import static org.apache.http.protocol.HttpCoreContext.HTTP_TARGET_HOST;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.Header;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpProcessor;

/**
 * <p>An {@link HttpRequestInterceptor} that blocks access to internal
 * metadata API hosts as well as link local addresses.
 * {@link org.apache.http.client.HttpClient Clients} that are meant to
 * call user-provided URLs should consider using this to mitigate
 * against malicious actors who may provide cloud provider metadata API
 * URLs as part of a server-side request forgery (SSRF) attack. Clients
 * that are meant to access metadata APIs should <strong>not</strong>
 * use this.</p>
 *
 * <p>Copyright &copy; 2019 Carlos Macasaet.</p>
 *
 * @author Carlos Macasaet
 */
public class InternalAddressFilteringRequestInterceptor implements HttpRequestInterceptor {

    private static final List<String> blockedHosts = unmodifiableList(
            asList("instance-data", "metadata.google.internal"));
    // TODO add ability to blacklist and whitelist hosts with properties
    // file

    private final Log log = LogFactory.getLog(getClass());

    public void install(final HttpClientBuilder builder) {
        builder.addInterceptorFirst(this);
    }

    public HttpProcessor wrap(final HttpProcessor delegate) {
        return new HttpProcessor() {

            public void process(final HttpResponse response, final HttpContext context)
                    throws HttpException, IOException {
                delegate.process(response, context);
            }

            public void process(final HttpRequest request, final HttpContext context)
                    throws HttpException, IOException {
                InternalAddressFilteringRequestInterceptor.this.process(request, context);
                delegate.process(request, context);
            }
        };
    }

    public void process(final HttpRequest request, final HttpContext context) throws HttpException, IOException {
        final HttpHost host = getHost(request, context);
        final InetAddress explicitAddress = host.getAddress();
        final String hostName = host.getHostName();
        if (blockedHosts.stream().anyMatch(
                blockedHost -> blockedHost.equalsIgnoreCase(hostName)
                || hostName.toLowerCase().endsWith("." + blockedHost))) {
            log.warn("Blocking connection to: " + host);
            throw new HttpException("Blocked host.");
        }
        final InetAddress[] addresses = explicitAddress != null ? new InetAddress[] { explicitAddress }
                : getAllByName(hostName);
        for (final InetAddress address : addresses) {
            if (address.isLinkLocalAddress()) {
                log.warn("Blocking connection to: " + host);
                throw new HttpException("Blocked host.");
            }
        }
    }

    protected HttpHost getHost(final HttpRequest request, final HttpContext context) throws HttpException {
        final HttpHost host = (HttpHost) context.getAttribute(HTTP_TARGET_HOST);
        if (host != null) {
            return host;
        }
        final HttpRoute route = (HttpRoute) context.getAttribute(HTTP_ROUTE);
        if (route != null) {
            return route.getTargetHost();
        }
        final Header[] headers = request.getHeaders("Host");
        if (headers.length == 1) {
            final String hostValue = headers[0].getValue();
            final String[] hostValueComponents = hostValue.split(":");
            if (hostValueComponents.length > 0) {
                final String hostname = hostValueComponents[0];
                return new HttpHost(hostname);
            }
        }
        log.error("Unable to determine host from: " + request + ", " + context);
        throw new HttpException("No host specified");
    }

}