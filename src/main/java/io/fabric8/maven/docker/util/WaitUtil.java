package io.fabric8.maven.docker.util;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClientBuilder;


/**
 * @author roland
 * @since 18.10.14
 */
public class WaitUtil {

    // how long to wait at max when doing a http ping
    private static final long DEFAULT_MAX_WAIT = 10 * 1000;

    // How long to wait between pings
    private static final long WAIT_RETRY_WAIT = 500;

    // Timeout for pings
    private static final int HTTP_PING_TIMEOUT = 500;
    private static final int TCP_PING_TIMEOUT = 500;

    // Default HTTP Method to use
    public static final String DEFAULT_HTTP_METHOD = "HEAD";

    // Default status codes
    public static final int DEFAULT_MIN_STATUS = 200;
    public static final int DEFAULT_MAX_STATUS = 399;

    // Disable HTTP client retries by default.
    public static final int HTTP_CLIENT_RETRIES = 0;


    private WaitUtil() {}

    public static long wait(int wait, Callable<Void> callable) throws ExecutionException, WaitTimeoutException {
        long now = System.currentTimeMillis();
        if (wait > 0) {
            try {
                FutureTask<Void> task = new FutureTask<>(callable);
                task.run();

                task.get(wait, TimeUnit.SECONDS);
            } catch (@SuppressWarnings("unused") InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (@SuppressWarnings("unused") TimeoutException e) {
                throw new WaitTimeoutException("timed out waiting for execution to complete", delta(now));
            }
        }
        return delta(now);
    }

    public static long wait(int maxWait, WaitChecker ... checkers) throws WaitTimeoutException {
        return wait(maxWait, Arrays.asList(checkers));
    }

    public static long wait(int maxWait, Iterable<WaitChecker> checkers) throws WaitTimeoutException {
        long max = maxWait > 0 ? maxWait : DEFAULT_MAX_WAIT;
        long now = System.currentTimeMillis();
        try {
            do {
                for (WaitChecker checker : checkers) {
                    if (checker.check()) {
                        return delta(now);
                    }
                }
                sleep(WAIT_RETRY_WAIT);
            } while (delta(now) < max);

            throw new WaitTimeoutException("No checker finished successfully", delta(now));

        } finally {
            cleanup(checkers);
        }
    }

    // Give checkers a possibility to clean up
    private static void cleanup(Iterable<WaitChecker> checkers) {
        for (WaitChecker checker : checkers) {
            checker.cleanUp();
        }
    }

    /**
     * Sleep a bit
     *
     * @param millis how long to sleep in milliseconds
     */
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // ...
            Thread.currentThread().interrupt();
        }
    }

    private static long delta(long now) {
        return System.currentTimeMillis() - now;
    }

    // ====================================================================================================

    /**
     * Check whether a given URL is available
     *
     */
    public static class HttpPingChecker implements WaitChecker {

        private int statusMin, statusMax;
        private String url;
        private String method;

        /**
         * Ping the given URL
         *
         * @param url URL to check
         * @param method HTTP method to use
         * @param status status code to check
         */
        public HttpPingChecker(String url, String method, String status) {
            this.url = url;
            this.method = method;

            if (method == null) {
                this.method = DEFAULT_HTTP_METHOD;
            }

            if (status == null) {
                statusMin = DEFAULT_MIN_STATUS;
                statusMax = DEFAULT_MAX_STATUS;
            } else {
                Matcher matcher = Pattern.compile("^(\\d+)\\s*\\.\\.+\\s*(\\d+)$").matcher(status);
                if (matcher.matches()) {
                    statusMin = Integer.parseInt(matcher.group(1));
                    statusMax = Integer.parseInt(matcher.group(2));
                } else {
                    statusMin = statusMax = Integer.parseInt(status);
                }
            }
        }

        public HttpPingChecker(String waitUrl) {
            this(waitUrl, null, null);
        }

        @Override
        public boolean check() {
            try {
                return ping();
            } catch (IOException exception) {
                return false;
            }
        }

        private boolean ping() throws IOException {
            RequestConfig requestConfig =
                    RequestConfig.custom()
                            .setSocketTimeout(HTTP_PING_TIMEOUT)
                            .setConnectTimeout(HTTP_PING_TIMEOUT)
                            .setConnectionRequestTimeout(HTTP_PING_TIMEOUT)
                            .setRedirectsEnabled(false)
                            .build();
            CloseableHttpClient httpClient = HttpClientBuilder.create()
                    .setDefaultRequestConfig(requestConfig)
                    .setRetryHandler(new DefaultHttpRequestRetryHandler(HTTP_CLIENT_RETRIES, false))
                    .build();
            try {
                CloseableHttpResponse response = httpClient.execute(RequestBuilder.create(method.toUpperCase()).setUri(url).build());
                try {
                    int responseCode = response.getStatusLine().getStatusCode();
                    if (responseCode == 501) {
                        throw new IllegalArgumentException("Invalid or not supported HTTP method '" + method.toUpperCase() + "' for checking " + url);
                    }
                    return (responseCode >= statusMin && responseCode <= statusMax);
                } finally {
                    response.close();
                }
            } finally {
                httpClient.close();
            }
        }

        @Override
        public void cleanUp() { }
    }

    // ====================================================================================================

    /**
     * Check whether a given TCP port is available
     */
    public static class TcpPortChecker implements WaitChecker {
        private final List<Integer> ports;

        private final List<InetSocketAddress> pending;

        public TcpPortChecker(String host, List<Integer> ports) {
            this.ports = ports;

            this.pending = new ArrayList<>();
            for (int port : ports) {
                this.pending.add(new InetSocketAddress(host, port));
            }

        }

        public List<Integer> getPorts() {
            return ports;
        }

        public List<InetSocketAddress> getPending() {
            return pending;
        }

        @Override
        public boolean check() {
            Iterator<InetSocketAddress> iter = pending.iterator();

            while (iter.hasNext()) {
                InetSocketAddress address = iter.next();

                try {
                    Socket s = new Socket();
                    s.connect(address, TCP_PING_TIMEOUT);
                    s.close();
                    iter.remove();
                } catch (IOException e) {
                    // Ports isn't opened, yet. So don't remove from queue.

                }

            }
            return pending.isEmpty();
        }

        @Override
        public void cleanUp() { }
    };

    // ====================================================================================================

    public interface WaitChecker {
        boolean check();

        void cleanUp();
    }

    public static class WaitTimeoutException extends TimeoutException {
        private final long waited;

        public WaitTimeoutException(String message, long waited) {
            super(message);
            this.waited = waited;
        }

        public long getWaited() {
            return waited;
        }
    }
}
