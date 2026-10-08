package com.beemdevelopment.aegis.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class WebDavClientTest {
    private static final String ACCOUNT = "user@example.com";
    private static final char[] PASSWORD = "app-password".toCharArray();

    private MockWebServer _server;

    @Before
    public void init() throws Exception {
        _server = new MockWebServer();
        _server.start();
    }

    @After
    public void tearDown() throws Exception {
        _server.shutdown();
    }

    @Test
    public void testEnsureDirectory() throws Exception {
        final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());
        _server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                requests.add(request);
                return new MockResponse().setResponseCode(201);
            }
        });

        try (WebDavClient client = createClient("Aegis")) {
            client.ensureDirectory("Aegis/Phone");
        }

        assertEquals(2, requests.size());
        assertEquals("MKCOL", requests.get(0).getMethod());
        assertEquals("/dav/Aegis", requests.get(0).getPath());
        assertEquals("MKCOL", requests.get(1).getMethod());
        assertEquals("/dav/Aegis/Phone", requests.get(1).getPath());
        assertNotNull(requests.get(0).getHeader("Authorization"));
        assertTrue(requests.get(0).getHeader("Authorization").startsWith("Basic "));
    }

    @Test
    public void testEnsureDirectoryAcceptsExisting() throws Exception {
        _server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(405);
            }
        });

        try (WebDavClient client = createClient("Aegis")) {
            client.ensureDirectory("Aegis");
        }
        assertEquals(1, _server.getRequestCount());
    }

    @Test
    public void testListParsesEntries() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<d:multistatus xmlns:d=\"DAV:\">"
                + response("/dav/Aegis/", "<d:resourcetype><d:collection/></d:resourcetype>")
                + response("/dav/Aegis/Phone/", "<d:resourcetype><d:collection/></d:resourcetype>")
                + response("/dav/Aegis/Phone/aegis-1.json",
                        "<d:resourcetype/><d:getcontentlength>1234</d:getcontentlength>"
                                + "<d:getlastmodified>Thu, 08 Oct 2026 06:00:00 GMT</d:getlastmodified>")
                + "</d:multistatus>";
        _server.enqueue(new MockResponse().setResponseCode(207).setBody(xml));

        try (WebDavClient client = createClient("Aegis")) {
            WebDavClient.ListResult result = client.list("Aegis");
            assertTrue(result.isComplete());
            assertEquals(2, result.getEntries().size());

            WebDavClient.RemoteEntry dir = result.getEntries().get(0);
            assertEquals("Phone", dir.getName());
            assertEquals("Aegis/Phone", dir.getPath());
            assertTrue(dir.isDirectory());

            WebDavClient.RemoteEntry file = result.getEntries().get(1);
            assertEquals("aegis-1.json", file.getName());
            assertEquals("Aegis/Phone/aegis-1.json", file.getPath());
            assertFalse(file.isDirectory());
            assertEquals(1234, file.getSize());
            assertEquals(Instant.parse("2026-10-08T06:00:00Z").getEpochSecond(), file.getLastModified());
        }

        RecordedRequest request = _server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("PROPFIND", request.getMethod());
        assertEquals("1", request.getHeader("Depth"));
    }

    @Test
    public void testListRejectsForeignHref() throws Exception {
        String xml = "<?xml version=\"1.0\"?>"
                + "<d:multistatus xmlns:d=\"DAV:\">"
                + response("http://evil.example.com/dav/Aegis/x.json", "<d:resourcetype/>")
                + "</d:multistatus>";
        _server.enqueue(new MockResponse().setResponseCode(207).setBody(xml));

        try (WebDavClient client = createClient("Aegis")) {
            WebDavException e = assertThrows(WebDavException.class, () -> client.list("Aegis"));
            assertEquals(WebDavException.Reason.PROTOCOL, e.getReason());
        }
    }

    @Test
    public void testListRejectsHrefOutsideRootScope() throws Exception {
        String xml = "<?xml version=\"1.0\"?>"
                + "<d:multistatus xmlns:d=\"DAV:\">"
                + response("/dav/Other/secret.json", "<d:resourcetype/>")
                + "</d:multistatus>";
        _server.enqueue(new MockResponse().setResponseCode(207).setBody(xml));

        try (WebDavClient client = createClient("Aegis")) {
            WebDavException e = assertThrows(WebDavException.class, () -> client.list("Aegis"));
            assertEquals(WebDavException.Reason.PROTOCOL, e.getReason());
        }
    }

    @Test
    public void testListMarksIncompleteAtServerLimit() throws Exception {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">");
        for (int i = 0; i < WebDavClient.MAX_LIST_ENTRIES; i++) {
            xml.append(response("/dav/Aegis/file-" + i + ".json", "<d:resourcetype/>"));
        }
        xml.append("</d:multistatus>");
        _server.enqueue(new MockResponse().setResponseCode(207).setBody(xml.toString()));

        try (WebDavClient client = createClient("Aegis")) {
            WebDavClient.ListResult result = client.list("Aegis");
            assertFalse(result.isComplete());
            assertEquals(WebDavClient.MAX_LIST_ENTRIES, result.getEntries().size());
        }
    }

    @Test
    public void testInvalidXmlIsRejected() throws Exception {
        _server.enqueue(new MockResponse().setResponseCode(207).setBody("<not-xml"));

        try (WebDavClient client = createClient("Aegis")) {
            WebDavException e = assertThrows(WebDavException.class, () -> client.list("Aegis"));
            assertEquals(WebDavException.Reason.PROTOCOL, e.getReason());
        }
    }

    @Test
    public void testAuthenticationFailure() throws Exception {
        _server.enqueue(new MockResponse().setResponseCode(401));

        try (WebDavClient client = createClient("Aegis")) {
            WebDavException e = assertThrows(WebDavException.class,
                    () -> client.download("Aegis/file.json", 1024));
            assertEquals(WebDavException.Reason.AUTHENTICATION, e.getReason());
            assertEquals(401, e.getHttpCode());
            assertFalse(e.isRetryable());
        }
    }

    @Test
    public void testRateLimited() throws Exception {
        _server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "120"));

        try (WebDavClient client = createClient("Aegis")) {
            WebDavException e = assertThrows(WebDavException.class,
                    () -> client.upload("Aegis/file.json", "{}".getBytes(StandardCharsets.UTF_8)));
            assertEquals(WebDavException.Reason.RATE_LIMITED, e.getReason());
            assertTrue(e.isRetryable());
            assertEquals(120, e.getRetryAfterSeconds());
        }
    }

    @Test
    public void testServerErrorIsRetryable() throws Exception {
        _server.enqueue(new MockResponse().setResponseCode(503));

        try (WebDavClient client = createClient("Aegis")) {
            WebDavException e = assertThrows(WebDavException.class,
                    () -> client.upload("Aegis/file.json", "{}".getBytes(StandardCharsets.UTF_8)));
            assertEquals(WebDavException.Reason.SERVER, e.getReason());
            assertTrue(e.isRetryable());
        }
    }

    @Test
    public void testRedirectIsNotFollowed() throws Exception {
        _server.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", _server.url("/dav/Aegis/other.json")));

        try (WebDavClient client = createClient("Aegis")) {
            WebDavException e = assertThrows(WebDavException.class,
                    () -> client.download("Aegis/file.json", 1024));
            assertEquals(WebDavException.Reason.PROTOCOL, e.getReason());
            assertEquals(302, e.getHttpCode());
        }
        assertEquals(1, _server.getRequestCount());
    }

    @Test
    public void testPathEncoding() throws Exception {
        _server.enqueue(new MockResponse().setResponseCode(201));

        try (WebDavClient client = createClient("Aegis")) {
            client.upload("Aegis/Phone Backup/\u5907\u4efd/aegis-1.json",
                    "{}".getBytes(StandardCharsets.UTF_8));
        }

        RecordedRequest request = _server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/dav/Aegis/Phone%20Backup/%E5%A4%87%E4%BB%BD/aegis-1.json", request.getPath());
    }

    @Test
    public void testReadTimeout() throws Exception {
        _server.enqueue(new MockResponse().setResponseCode(200)
                .setBodyDelay(2, TimeUnit.SECONDS)
                .setBody("hello"));

        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(500, TimeUnit.MILLISECONDS)
                .readTimeout(300, TimeUnit.MILLISECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .build();
        try (WebDavClient client = new WebDavClient(httpClient, _server.url("/dav/"),
                ACCOUNT, PASSWORD, "Aegis")) {
            WebDavException e = assertThrows(WebDavException.class,
                    () -> client.download("Aegis/file.json", 1024));
            assertEquals(WebDavException.Reason.TIMEOUT, e.getReason());
        }
    }

    @Test
    public void testDownloadTooLarge() throws Exception {
        byte[] content = new byte[4096];
        MockResponse response = new MockResponse().setResponseCode(200)
                .setChunkedBody(new String(content, StandardCharsets.UTF_8), 1024);
        _server.enqueue(response);

        try (WebDavClient client = createClient("Aegis")) {
            WebDavException e = assertThrows(WebDavException.class,
                    () -> client.download("Aegis/file.json", 100));
            assertEquals(WebDavException.Reason.TOO_LARGE, e.getReason());
        }
    }

    @Test
    public void testCancel() throws Exception {
        // The body arrives shortly after the cancellation. On platforms where
        // closing the socket wakes up a blocked read, the request fails
        // immediately; where it does not, the cancel flag terminates the
        // download as soon as the first bytes arrive. Both paths must report
        // CANCELLED and must not return the downloaded content.
        _server.enqueue(new MockResponse().setResponseCode(200)
                .setBodyDelay(300, TimeUnit.MILLISECONDS)
                .setBody("hello"));

        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .build();
        try (WebDavClient client = new WebDavClient(httpClient, _server.url("/dav/"),
                ACCOUNT, PASSWORD, "Aegis")) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try {
                    client.download("Aegis/file.json", 1024);
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            thread.start();

            // Wait until the request actually reached the server before
            // cancelling, so the cancellation hits an in-flight request.
            long deadline = System.currentTimeMillis() + 2000;
            while (_server.getRequestCount() < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            client.cancel();
            thread.join(10000);

            assertNotNull("the canceled download must fail", failure.get());
            assertTrue(failure.get() instanceof WebDavException);
            assertEquals(WebDavException.Reason.CANCELLED,
                    ((WebDavException) failure.get()).getReason());
            assertTrue(_server.getRequestCount() >= 1);
        }
    }

    @Test
    public void testConnection() throws Exception {
        final AtomicReference<byte[]> uploaded = new AtomicReference<>();
        _server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                switch (request.getMethod()) {
                    case "MKCOL":
                        return new MockResponse().setResponseCode(405);
                    case "PUT":
                        uploaded.set(request.getBody().readByteArray());
                        return new MockResponse().setResponseCode(201);
                    case "GET":
                        return new MockResponse().setResponseCode(200).setBody(
                                new okio.Buffer().write(uploaded.get()));
                    case "DELETE":
                        return new MockResponse().setResponseCode(204);
                    default:
                        return new MockResponse().setResponseCode(500);
                }
            }
        });

        try (WebDavClient client = createClient("Aegis")) {
            String warning = client.testConnection();
            assertNull(warning);
        }
        assertNotNull(uploaded.get());
        assertTrue(uploaded.get().length > 0);
        assertEquals(4, _server.getRequestCount());
    }

    @Test
    public void testConnectionReportsCleanupFailure() throws Exception {
        final AtomicReference<byte[]> uploaded = new AtomicReference<>();
        _server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                switch (request.getMethod()) {
                    case "MKCOL":
                        return new MockResponse().setResponseCode(405);
                    case "PUT":
                        uploaded.set(request.getBody().readByteArray());
                        return new MockResponse().setResponseCode(201);
                    case "GET":
                        return new MockResponse().setResponseCode(200).setBody(
                                new okio.Buffer().write(uploaded.get()));
                    case "DELETE":
                        return new MockResponse().setResponseCode(403);
                    default:
                        return new MockResponse().setResponseCode(500);
                }
            }
        });

        try (WebDavClient client = createClient("Aegis")) {
            String warning = client.testConnection();
            assertNotNull(warning);
            assertTrue(warning.contains("could not be removed"));
        }
    }

    @Test
    public void testUploadAndDownloadLimitsMatch() {
        assertEquals(WebDavClient.MAX_UPLOAD_BYTES, WebDavClient.MAX_DOWNLOAD_BYTES);
    }

    @Test
    public void testCanceledClientRejectsImmediately() throws Exception {
        try (WebDavClient client = createClient("Aegis")) {
            client.cancel();
            WebDavException e = assertThrows(WebDavException.class,
                    () -> client.download("Aegis/file.json", 1024));
            assertEquals(WebDavException.Reason.CANCELLED, e.getReason());
        }
        assertEquals(0, _server.getRequestCount());
    }

    private WebDavClient createClient(String rootPath) throws Exception {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(1, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build();
        return new WebDavClient(httpClient, _server.url("/dav/"), ACCOUNT, PASSWORD, rootPath);
    }

    private static String response(String href, String propContent) {
        return "<d:response><d:href>" + href + "</d:href>"
                + "<d:propstat><d:prop>" + propContent + "</d:prop>"
                + "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>";
    }
}
