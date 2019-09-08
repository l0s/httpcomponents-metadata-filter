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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;

import org.apache.http.HttpClientConnection;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.ProtocolException;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.ConnectionRequest;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.routing.HttpRoutePlanner;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultRedirectStrategy;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.DefaultSchemePortResolver;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.impl.conn.SystemDefaultDnsResolver;
import org.apache.http.impl.conn.SystemDefaultRoutePlanner;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.HttpRequestExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.commons.logging.Logger;
import org.junit.platform.commons.logging.LoggerFactory;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.Status;

/**
 * This test emulates typical creation and usage of HttpClient
 * instances. It was initially used to demonstrate the full lifecyle of
 * an HTTP request in order to determine the best place to add the
 * filtering logic.
 * <p>
 * Copyright &copy; 2019 Carlos Macasaet.
 * </p>
 *
 * @author Carlos Macasaet
 */
public class ConnectionBlockingTest {

    private static final String shortUrlHost = "short.url";
    private static final String shortUrlPath = "/Soh3hoot";
    private static final String shortUrl = "https://" + shortUrlHost + shortUrlPath;
    private static final int mockTargetPort = 8080;
    private static final InetAddress defaultLinkLocalAddress;

    final Logger logger = LoggerFactory.getLogger(getClass());

    static {
        InetAddress bannedLinkLocalAddress;
        try {
            bannedLinkLocalAddress = InetAddress
                    .getByAddress(new byte[] { (byte) 169, (byte) 254, (byte) 169, (byte) 254 });
        } catch (final UnknownHostException uhe) {
            bannedLinkLocalAddress = null;
            fail(uhe);
        }
        defaultLinkLocalAddress = bannedLinkLocalAddress;
    }

    private CloseableHttpClient client;
    private final HttpRequestInterceptor firstRequestInterceptor = new HttpRequestInterceptor() {
        public void process(final HttpRequest request, final HttpContext context) {
            logger.debug(() -> "-- first request interceptor: " + request + ", " + context);
        }
    };
    private final HttpRequestInterceptor lastRequestInterceptor = new HttpRequestInterceptor() {
        public void process(final HttpRequest request, final HttpContext context) {
            logger.debug(() -> "-- last request interceptor: " + request + ", " + context);
        }
    };
    private final HttpResponseInterceptor firstResponseInterceptor = new HttpResponseInterceptor() {
        public void process(final HttpResponse response, final HttpContext context) {
            logger.debug(() -> "-- first response interceptor: " + response + ", " + context);
        }
    };
    private final HttpResponseInterceptor lastResponseInterceptor = new HttpResponseInterceptor() {
        public void process(final HttpResponse response, final HttpContext context) {
            logger.debug(() -> "-- last response interceptor: " + response + ", " + context);
        }
    };
    private final DnsResolver dnsResolver = new DnsResolver() {
        private final DnsResolver delegate = new SystemDefaultDnsResolver();

        public InetAddress[] resolve(String host) throws UnknownHostException {
            logger.debug(() -> "-- DnsResolver::resolve enter: " + host);
            try {
                if ("169.254.169.254".equals(host) || "instance-data".contentEquals(host)
                        || "metadata.google.internal".contentEquals(host)) {
                    return new InetAddress[] { defaultLinkLocalAddress };
                } else if (shortUrlHost.contentEquals(host)) {
                    return new InetAddress[] { InetAddress.getByName("localhost") };
                }
                return delegate.resolve(host);
            } finally {
                logger.debug(() -> "-- DnsResolver::resolve exit: " + host);
            }
        }
    };
    private final HttpRequestExecutor requestExecutor = new HttpRequestExecutor() {
        public void preProcess(final HttpRequest request, final HttpProcessor processor, final HttpContext context)
                throws HttpException, IOException {
            logger.debug(() -> "-- HttpRequestExecutor::preProcess enter: " + request);
            try {
                super.preProcess(request, processor, context);
            } finally {
                logger.debug(() -> "-- HttpRequestExecutor::preProcess exit: " + request);
            }
        }

        public HttpResponse execute(final HttpRequest request, final HttpClientConnection conn,
                final HttpContext context) throws IOException, HttpException {
            logger.debug(() -> "-- HttpRequestExecutor::execute enter: " + request);
            try {
                return super.execute(request, conn, context);
            } finally {
                logger.debug(() -> "-- HttpRequestExecutor::execute exit: " + request);
            }
        }

        public void postProcess(final HttpResponse response, final HttpProcessor processor, final HttpContext context)
                throws HttpException, IOException {
            logger.debug(() -> "-- HttpRequestExecutor::postProcess enter: " + response);
            try {
                super.postProcess(response, processor, context);
            } finally {
                logger.debug(() -> "-- HttpRequestExecutor::postProcess enter: " + response);
            }
        }
    };
//	private final HttpProcessor processor = new HttpProcessor() {
//		public void process(HttpRequest request, HttpContext context) throws HttpException, IOException {
//			System.out.println("-- HttpProcessor::process request: " + request);
//		}
//
//		public void process(HttpResponse response, HttpContext context) throws HttpException, IOException {
//			System.out.println("-- HttpProcessor::process response: " + response);
//		}
//	};
    private final RedirectStrategy redirectStrategy = new RedirectStrategy() {
        private final RedirectStrategy delegate = new DefaultRedirectStrategy();

        public boolean isRedirected(final HttpRequest request, final HttpResponse response, final HttpContext context)
                throws ProtocolException {
            logger.debug(() -> "-- RedirectStrategy::isRedirected enter: " + request);
            try {
                if (shortUrl.contentEquals(request.getRequestLine().getUri())) {
                    return true;
                }
                return delegate.isRedirected(request, response, context);
            } finally {
                logger.debug(() -> "-- RedirectStrategy::isRedirected exit: " + request);
            }
        }

        public HttpUriRequest getRedirect(final HttpRequest request, final HttpResponse response,
                final HttpContext context) throws ProtocolException {
            logger.debug(() -> "-- RedirectStrategy::getRedirect enter: " + request);
            try {
                if (shortUrl.contentEquals(request.getRequestLine().getUri())) {
                    return new HttpGet("http://169.254.169.254/latest/meta-data/");
                }
                return delegate.getRedirect(request, response, context);
            } finally {
                logger.debug(() -> "-- RedirectStrategy::getRedirect exit: " + request);
            }
        }
    };
    // constructor emulates logic in HttpClientBuilder
    private final HttpRoutePlanner routePlanner = new HttpRoutePlanner() {
        private final HttpRoutePlanner delegate = new SystemDefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE,
                ProxySelector.getDefault());

        public HttpRoute determineRoute(final HttpHost target, final HttpRequest request, final HttpContext context)
                throws HttpException {
            logger.debug(() -> "-- HttpRoutePlanner::determineRoute enter: " + target + ", " + request);
            try {
                if (shortUrlHost.contentEquals(target.getHostName())) {
                    final HttpHost mockTarget = new HttpHost("localhost", mockTargetPort);
                    return delegate.determineRoute(mockTarget, request, context);
                }
                return delegate.determineRoute(target, request, context);
            } finally {
                logger.debug(() -> "-- HttpRoutePlanner::determineRoute exit: " + target + ", " + request);
            }
        }
    };
    // constructor emulates logic in HttpClientBuilder
    private final HttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(
            RegistryBuilder.<ConnectionSocketFactory>create()
                    .register("http", PlainConnectionSocketFactory.getSocketFactory())
                    .register("https", SSLConnectionSocketFactory.getSocketFactory()).build(),
            null, null, dnsResolver, 5, TimeUnit.MILLISECONDS) {

        public ConnectionRequest requestConnection(final HttpRoute route, final Object state) {
            logger.debug(() -> "-- HttpClientConnectionManager::requestConnection: " + route + " ( "
                    + route.getTargetHost() + " )");
            final HttpHost targetHost = route.getTargetHost();
            if (defaultLinkLocalAddress.equals(targetHost.getAddress())) {
                fail("Attempted to connected to link local address");
            } else if (targetHost.getHostName().endsWith(".internal")) {
                fail("Attempted to connected to internal host");
            } else if (shortUrlHost.contentEquals(targetHost.getHostName())) {
                final HttpHost target = new HttpHost("localhost", mockTargetPort);
                final HttpRoute mockRoute = new HttpRoute(target);
                return requestConnection(mockRoute, state);
            }
            return super.requestConnection(route, state);
        }

        public void connect(final HttpClientConnection managedConn, final HttpRoute route, final int connectTimeout,
                final HttpContext context) throws IOException {
            logger.debug(
                    () -> "-- HttpClientConnectionManager::connect: " + route + " ( " + route.getTargetHost() + " )");
            final HttpHost targetHost = route.getTargetHost();
            if (defaultLinkLocalAddress.equals(targetHost.getAddress())
                    || "169.254.169.254".contentEquals(targetHost.getHostName())) {
                fail("Attempted to connect to link local address");
            } else if (targetHost.getHostName().endsWith(".internal")) {
                fail("Attempted to connect to internal host");
            } else if (shortUrlHost.contentEquals(targetHost.getHostName())) {
                final HttpHost target = new HttpHost("localhost", mockTargetPort);
                final HttpRoute mockRoute = new HttpRoute(target);
                context.setAttribute("http.target_host", "http://localhost");
                context.setAttribute("http.route", mockRoute);
                connect(managedConn, mockRoute, connectTimeout, context);
            } else {
                super.connect(managedConn, route, connectTimeout, context);
            }
        }
    };

    private final NanoHTTPD shortUrlServer = new NanoHTTPD("localhost", mockTargetPort) {
        public Response serve(IHTTPSession session) {
            if (session.getMethod().equals(Method.GET) && session.getUri().contentEquals(shortUrlPath)) {
                final Response retval = newFixedLengthResponse(Status.REDIRECT, "text/plain", "");
                retval.addHeader("Location", "http://169.254.169.254/latest/meta-data/");
                return retval;
            } else if (session.getMethod().equals(Method.GET) && session.getUri().contentEquals("/valid")) {
                return newFixedLengthResponse("hello");
            }
            throw new IllegalArgumentException();
        }
    };

    @BeforeAll
    public static void setUpLogging() throws SecurityException, IOException {
        try (InputStream configurationStream = ConnectionBlockingTest.class
                .getResourceAsStream("/logging.properties")) {
            LogManager.getLogManager().readConfiguration(configurationStream);
        }
    }

    @BeforeEach
    public void setUp() throws IOException {
        shortUrlServer.start();
        assertTrue(shortUrlServer.wasStarted());
        assertTrue(shortUrlServer.isAlive());

        final HttpClientBuilder builder = HttpClientBuilder.create();
        builder.setRoutePlanner(routePlanner);
        builder.addInterceptorFirst(firstRequestInterceptor);
        builder.addInterceptorLast(lastRequestInterceptor);
        builder.setConnectionManager(connectionManager);
        builder.setDnsResolver(dnsResolver); // not used if an IP address is provided
        builder.setRequestExecutor(requestExecutor);
        builder.addInterceptorFirst(firstResponseInterceptor);
        builder.addInterceptorLast(lastResponseInterceptor);
        builder.setRedirectStrategy(redirectStrategy);

        // setting an http processor disables the interceptors
//		builder.setHttpProcessor(processor);

        builder.addInterceptorFirst(new InternalAddressFilteringRequestInterceptor());
        client = builder.build();
    }

    @AfterEach
    public void tearDown() throws IOException {
        client.close();
    }

    @AfterEach
    public void shutDownServer() {
        shortUrlServer.stop();
    }

    @Test
    public final void verifyRedirectToBannedHostIsBlocked() {
        final HttpUriRequest request = new HttpGet(shortUrl);
        assertThrows(Exception.class, () -> client.execute(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data/", // Amazon Web Services (AWS)
            "http://instance-data/latest/meta-data/", // Amazon Web Services (AWS): https://serverfault.com/q/436086
            "http://metadata.google.internal/computeMetadata/v1/instance/disks/0/", // Google Cloud Platform (GCP)
            "http://169.254.169.254/metadata/instance?api-version=2017-08-01&format=text", // Microsoft Azure
            "http://169.254.169.254/metadata/v1/", // DigitalOcean
            "http://169.254.169.254/opc/v1/instance/metadata/", // Oracle Cloud
            "http://169.254.169.254/openstack/2018-08-27/meta_data.json" // OpenStack
    })
    public final void verifyRequestToBannedHostIsBlocked(final String url) {
        final HttpUriRequest request = new HttpGet(url);
        assertThrows(Exception.class, () -> client.execute(request));
    }

    @Test
    public final void verifyClientCanConnectToValidHost() throws IOException {
        final HttpUriRequest request = new HttpGet("http://localhost:8080/valid");
        final HttpResponse response = client.execute(request);
        logger.debug(() -> "valid response: " + response);
    }

}