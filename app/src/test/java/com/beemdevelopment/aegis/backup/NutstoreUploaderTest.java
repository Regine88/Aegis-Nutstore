package com.beemdevelopment.aegis.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.beemdevelopment.aegis.vault.VaultRepository;

@RunWith(RobolectricTestRunner.class)
public class NutstoreUploaderTest {
    private Context _context;
    private MockWebServer _server;
    private HttpUrl _baseUrl;
    private NutstoreCredentialStore _credentials;
    private NutstoreBackupStore _store;
    private NutstoreCredentialStore.Config _config;

    @Before
    public void init() throws Exception {
        _context = ApplicationProvider.getApplicationContext();
        _server = new MockWebServer();
        _server.start();
        _baseUrl = _server.url("/dav/");
        _credentials = new NutstoreCredentialStore(_context,
                new NutstoreTestHelpers.MemoryKeySource());
        _store = new NutstoreBackupStore(_context);
        clearAll();
    }

    @After
    public void tearDown() throws Exception {
        try {
            _server.shutdown();
        } catch (Exception ignored) {
            // already shut down by a test
        }
        clearAll();
    }

    @Test
    public void testSuccessfulUploadAndPrune() throws Exception {
        createVault();
        configure(true, 2);
        _store.requestBackup(_config.getConfigVersion());

        final AtomicReference<String> uploadedName = new AtomicReference<>();
        final AtomicReference<byte[]> uploadedBody = new AtomicReference<>();
        final List<String> deleted = Collections.synchronizedList(new ArrayList<>());

        _server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                switch (request.getMethod()) {
                    case "MKCOL":
                        return new MockResponse().setResponseCode(405);
                    case "PUT":
                        uploadedName.set(lastSegment(request.getPath()));
                        uploadedBody.set(request.getBody().readByteArray());
                        return new MockResponse().setResponseCode(201);
                    case "GET":
                        return new MockResponse().setResponseCode(200)
                                .setBody(new String(uploadedBody.get(), StandardCharsets.UTF_8));
                    case "PROPFIND":
                        return new MockResponse().setResponseCode(207)
                                .setBody(listing(uploadedName.get()));
                    case "DELETE":
                        deleted.add(lastSegment(request.getPath()));
                        return new MockResponse().setResponseCode(204);
                    default:
                        return new MockResponse().setResponseCode(500);
                }
            }
        });

        NutstoreUploader.Result result = createUploader().uploadOnce();
        assertEquals(NutstoreUploader.Outcome.SUCCESS, result.getOutcome());

        NutstoreBackupStore.State state = _store.loadState();
        assertEquals(state.getRequestVersion(), state.getConfirmedVersion());
        assertEquals(NutstoreBackupStore.Status.SUCCESS, state.getStatus());
        assertNull(state.getSnapshot());
        assertTrue(state.getLastSuccessAt() > 0);
        assertEquals(0, state.getCleanupFailedAt());
        assertNotNull(uploadedName.get());
        assertTrue(NutstoreBackupStore.MANAGED_FILE_PATTERN.matcher(uploadedName.get()).matches());

        // Four managed files are listed, the oldest two are removed.
        assertEquals(2, deleted.size());
        assertTrue(deleted.contains("aegis-20200101-000000-aaaaaaaa.json"));
        assertTrue(deleted.contains("aegis-20200102-000000-bbbbbbbb.json"));
        assertFalse(deleted.contains(uploadedName.get()));
    }

    @Test
    public void testRetryReusesTheSameRemoteName() throws Exception {
        createVault();
        configure(true, 5);
        _store.requestBackup(_config.getConfigVersion());

        final List<String> putPaths = Collections.synchronizedList(new ArrayList<>());
        _server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getMethod().equals("MKCOL")) {
                    return new MockResponse().setResponseCode(405);
                }
                if (request.getMethod().equals("PUT")) {
                    putPaths.add(request.getPath());
                    return new MockResponse().setResponseCode(500);
                }
                return new MockResponse().setResponseCode(500);
            }
        });

        NutstoreUploader.Result first = createUploader().uploadOnce();
        assertEquals(NutstoreUploader.Outcome.RETRY, first.getOutcome());
        NutstoreBackupStore.State failed = _store.loadState();
        assertEquals(NutstoreBackupStore.Status.FAILED, failed.getStatus());
        assertEquals(NutstoreBackupStore.FailureKind.SERVER, failed.getLastFailure());
        assertNotNull(failed.getSnapshot());
        assertTrue(failed.isUploadPending());

        final AtomicReference<byte[]> uploadedBody = new AtomicReference<>();
        _server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                switch (request.getMethod()) {
                    case "MKCOL":
                        return new MockResponse().setResponseCode(405);
                    case "PUT":
                        putPaths.add(request.getPath());
                        uploadedBody.set(request.getBody().readByteArray());
                        return new MockResponse().setResponseCode(201);
                    case "GET":
                        return new MockResponse().setResponseCode(200)
                                .setBody(new String(uploadedBody.get(), StandardCharsets.UTF_8));
                    default:
                        return new MockResponse().setResponseCode(500);
                }
            }
        });

        NutstoreUploader.Result second = createUploader().uploadOnce();
        assertEquals(NutstoreUploader.Outcome.SUCCESS, second.getOutcome());
        assertEquals(2, putPaths.size());
        assertEquals(putPaths.get(0), putPaths.get(1));
        assertEquals(NutstoreBackupStore.Status.SUCCESS, _store.loadState().getStatus());
    }

    @Test
    public void testVerificationFailureKeepsSnapshot() throws Exception {
        createVault();
        configure(true, 5);
        _store.requestBackup(_config.getConfigVersion());

        _server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                switch (request.getMethod()) {
                    case "MKCOL":
                        return new MockResponse().setResponseCode(405);
                    case "PUT":
                        return new MockResponse().setResponseCode(201);
                    case "GET":
                        return new MockResponse().setResponseCode(200).setBody("{\"tampered\":true}");
                    default:
                        return new MockResponse().setResponseCode(500);
                }
            }
        });

        NutstoreUploader.Result result = createUploader().uploadOnce();
        assertEquals(NutstoreUploader.Outcome.RETRY, result.getOutcome());
        NutstoreBackupStore.State state = _store.loadState();
        assertEquals(NutstoreBackupStore.Status.FAILED, state.getStatus());
        assertEquals(NutstoreBackupStore.FailureKind.SERVER, state.getLastFailure());
        assertNotNull(state.getSnapshot());
        assertEquals(0, state.getConfirmedVersion());
    }

    @Test
    public void testCleanupFailureDoesNotFailTheBackup() throws Exception {
        createVault();
        configure(true, 1);
        _store.requestBackup(_config.getConfigVersion());

        final AtomicReference<byte[]> uploadedBody = new AtomicReference<>();
        _server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                switch (request.getMethod()) {
                    case "MKCOL":
                        return new MockResponse().setResponseCode(405);
                    case "PUT":
                        uploadedBody.set(request.getBody().readByteArray());
                        return new MockResponse().setResponseCode(201);
                    case "GET":
                        return new MockResponse().setResponseCode(200)
                                .setBody(new String(uploadedBody.get(), StandardCharsets.UTF_8));
                    case "PROPFIND":
                        return new MockResponse().setResponseCode(207)
                                .setBody(listing("aegis-20210101-000000-dddddddd.json"));
                    case "DELETE":
                        return new MockResponse().setResponseCode(403);
                    default:
                        return new MockResponse().setResponseCode(500);
                }
            }
        });

        NutstoreUploader.Result result = createUploader().uploadOnce();
        assertEquals(NutstoreUploader.Outcome.SUCCESS, result.getOutcome());
        NutstoreBackupStore.State state = _store.loadState();
        assertEquals(NutstoreBackupStore.Status.SUCCESS, state.getStatus());
        assertTrue(state.getCleanupFailedAt() > 0);
    }

    @Test
    public void testIncompleteListingSkipsCleanup() throws Exception {
        createVault();
        configure(true, 1);
        _store.requestBackup(_config.getConfigVersion());

        final AtomicReference<byte[]> uploadedBody = new AtomicReference<>();
        final AtomicInteger deleteCount = new AtomicInteger();
        _server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                switch (request.getMethod()) {
                    case "MKCOL":
                        return new MockResponse().setResponseCode(405);
                    case "PUT":
                        uploadedBody.set(request.getBody().readByteArray());
                        return new MockResponse().setResponseCode(201);
                    case "GET":
                        return new MockResponse().setResponseCode(200)
                                .setBody(new String(uploadedBody.get(), StandardCharsets.UTF_8));
                    case "PROPFIND":
                        return new MockResponse().setResponseCode(207).setBody(incompleteListing());
                    case "DELETE":
                        deleteCount.incrementAndGet();
                        return new MockResponse().setResponseCode(204);
                    default:
                        return new MockResponse().setResponseCode(500);
                }
            }
        });

        NutstoreUploader.Result result = createUploader().uploadOnce();
        assertEquals(NutstoreUploader.Outcome.SUCCESS, result.getOutcome());
        assertEquals(0, deleteCount.get());
        assertTrue(_store.loadState().getCleanupFailedAt() > 0);
    }

    @Test
    public void testConfigChangeAbortsUpload() throws Exception {
        createVault();
        configure(true, 5);
        _store.requestBackup(_config.getConfigVersion());

        // The user switches to a different directory before the upload starts.
        _credentials.save("user@example.com", null, "Other", true, 5);

        NutstoreUploader.Result result = createUploader().uploadOnce();
        assertEquals(NutstoreUploader.Outcome.SUCCESS, result.getOutcome());
        assertEquals(0, _server.getRequestCount());

        NutstoreBackupStore.State state = _store.loadState();
        assertEquals(2, state.getConfigVersion());
        assertEquals(0, state.getRequestVersion());
        assertNull(state.getSnapshot());
    }

    @Test
    public void testNetworkFailureIsRetryable() throws Exception {
        createVault();
        configure(true, 5);
        _store.requestBackup(_config.getConfigVersion());
        _server.shutdown();

        NutstoreUploader.Result result = createUploader().uploadOnce();
        assertEquals(NutstoreUploader.Outcome.RETRY, result.getOutcome());
        NutstoreBackupStore.State state = _store.loadState();
        assertEquals(NutstoreBackupStore.FailureKind.NETWORK, state.getLastFailure());
        assertNotNull(state.getSnapshot());
    }

    @Test
    public void testRateLimitWindowBlocksFurtherRequests() throws Exception {
        createVault();
        configure(true, 5);
        _store.requestBackup(_config.getConfigVersion());

        _server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getMethod().equals("MKCOL")) {
                    return new MockResponse().setResponseCode(405);
                }
                return new MockResponse().setResponseCode(429).setHeader("Retry-After", "600");
            }
        });

        NutstoreUploader.Result first = createUploader().uploadOnce();
        assertEquals(NutstoreUploader.Outcome.RETRY, first.getOutcome());
        NutstoreBackupStore.State state = _store.loadState();
        assertEquals(NutstoreBackupStore.FailureKind.RATE_LIMITED, state.getLastFailure());
        assertEquals(600, state.getLastRetryAfterSeconds());

        int requests = _server.getRequestCount();
        NutstoreUploader.Result second = createUploader().uploadOnce();
        assertEquals(NutstoreUploader.Outcome.RETRY, second.getOutcome());
        assertEquals(requests, _server.getRequestCount());
    }

    @Test
    public void testNothingPendingDoesNothing() throws Exception {
        createVault();
        configure(true, 5);

        NutstoreUploader.Result result = createUploader().uploadOnce();
        assertEquals(NutstoreUploader.Outcome.SUCCESS, result.getOutcome());
        assertEquals(0, _server.getRequestCount());
    }

    @Test
    public void testPlaintextVaultIsRejected() throws Exception {
        NutstoreTestHelpers.createVault(_context, NutstoreTestHelpers.VAULT_PASSWORD, false, false);
        configure(true, 5);
        _store.requestBackup(_config.getConfigVersion());

        NutstoreUploader.Result result = createUploader().uploadOnce();
        assertEquals(NutstoreUploader.Outcome.FATAL, result.getOutcome());
        assertEquals(NutstoreBackupStore.FailureKind.SNAPSHOT,
                _store.loadState().getLastFailure());
        assertEquals(0, _server.getRequestCount());
    }

    private void createVault() throws Exception {
        NutstoreTestHelpers.createVault(_context, NutstoreTestHelpers.VAULT_PASSWORD, true, true);
    }

    private void clearAll() throws Exception {
        _store.clearAll();
        _credentials.clearAll();
        VaultRepository.deleteFile(_context);
    }

    private void configure(boolean autoBackup, int retention) throws Exception {
        _config = _credentials.save("user@example.com", NutstoreTestHelpers.APP_PASSWORD,
                "Aegis", autoBackup, retention);
    }

    private NutstoreUploader createUploader() {
        return new NutstoreUploader(_credentials, _store, (account, password, rootPath) ->
                new WebDavClient(httpClient(), _baseUrl, account, password, rootPath));
    }

    private static OkHttpClient httpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(1, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    private String listing(String currentName) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">");
        xml.append(entry(currentName));
        xml.append(entry("aegis-20200101-000000-aaaaaaaa.json"));
        xml.append(entry("aegis-20200102-000000-bbbbbbbb.json"));
        xml.append(entry("aegis-20200103-000000-cccccccc.json"));
        xml.append("</d:multistatus>");
        return xml.toString();
    }

    private String incompleteListing() {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">");
        for (int i = 0; i < WebDavClient.MAX_LIST_ENTRIES; i++) {
            xml.append(entry(String.format("aegis-20200101-000000-%08x.json", i)));
        }
        xml.append("</d:multistatus>");
        return xml.toString();
    }

    private String entry(String name) {
        return "<d:response><d:href>/dav/Aegis/" + _config.getDeviceId() + "/" + name + "</d:href>"
                + "<d:propstat><d:prop><d:resourcetype/>"
                + "<d:getcontentlength>10</d:getcontentlength></d:prop>"
                + "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>";
    }

    private static String lastSegment(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 ? path : path.substring(index + 1);
    }
}
