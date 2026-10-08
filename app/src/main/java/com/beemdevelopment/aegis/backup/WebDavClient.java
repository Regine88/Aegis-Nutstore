package com.beemdevelopment.aegis.backup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.beemdevelopment.aegis.encoding.Base64;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Minimal WebDAV client for Nutstore (Jianguoyun). Only the operations needed
 * for versioned backups are implemented: creating directories, listing them,
 * uploading, downloading and deleting files.
 *
 * All requests are limited to the configured HTTPS base URL and remote root
 * directory. Redirects are never followed and every {@code href} returned by
 * the server is re-validated against the base URL before it is used.
 */
public class WebDavClient implements Closeable {
    public static final long MAX_UPLOAD_BYTES = 25L * 1024 * 1024;
    public static final long MAX_DOWNLOAD_BYTES = 25L * 1024 * 1024;

    static final int MAX_LIST_ENTRIES = 750;

    private static final long MAX_LIST_BYTES = 4L * 1024 * 1024;
    private static final long MAX_PROBE_BYTES = 4096;
    private static final int CONNECT_TIMEOUT_SECONDS = 15;
    private static final int READ_TIMEOUT_SECONDS = 30;
    private static final int WRITE_TIMEOUT_SECONDS = 30;
    private static final int CALL_TIMEOUT_SECONDS = 120;
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

    private final OkHttpClient _client;
    private final HttpUrl _baseUrl;
    private final String _rootPath;
    private final String _authorization;

    private volatile boolean _canceled;

    public WebDavClient(String account, char[] password, String rootPath)
            throws WebDavException {
        this(createHttpClient(), HttpUrl.parse(NutstoreCredentialStore.SERVER_URL),
                account, password, rootPath);
    }

    /**
     * Test seam: allows unit tests to point the client at a simulated service
     * with a different base URL and shorter timeouts.
     */
    WebDavClient(OkHttpClient client, HttpUrl baseUrl, String account, char[] password, String rootPath)
            throws WebDavException {
        if (baseUrl == null) {
            throw new WebDavException(WebDavException.Reason.CONFIGURATION,
                    "The WebDAV base URL is invalid");
        }
        if (account == null || account.trim().isEmpty() || password == null || password.length == 0) {
            throw new WebDavException(WebDavException.Reason.CONFIGURATION,
                    "A Nutstore account and application password are required");
        }
        try {
            _rootPath = NutstoreCredentialStore.normalizeRootPath(rootPath);
        } catch (IllegalArgumentException e) {
            throw new WebDavException(WebDavException.Reason.CONFIGURATION, e.getMessage(), e);
        }

        _client = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        _baseUrl = baseUrl;
        _authorization = createAuthorization(account.trim(), password);
    }

    @NonNull
    public String getRootPath() {
        return _rootPath;
    }

    public void ensureRootDirectory() throws WebDavException {
        ensureDirectory(_rootPath);
    }

    /**
     * Creates the given directory and every missing parent directory.
     * An already existing collection is not an error.
     */
    public void ensureDirectory(@Nullable String relativeDir) throws WebDavException {
        List<String> segments = splitPath(relativeDir);
        StringBuilder path = new StringBuilder();
        for (String segment : segments) {
            if (path.length() > 0) {
                path.append('/');
            }
            path.append(segment);

            Request request = newRequest(path.toString())
                    .method("MKCOL", RequestBody.create(null, new byte[0]))
                    .build();
            try (Response response = execute(request, "MKCOL " + path)) {
                int code = response.code();
                // 201: created, 405: already exists. Some servers answer with
                // 200 or 204 for an idempotent MKCOL.
                if (code == 200 || code == 201 || code == 204 || code == 405) {
                    continue;
                }
                throw createHttpException("MKCOL " + path, code, response.header("Retry-After"));
            }
        }
    }

    /**
     * Lists the entries of a directory with Depth 1. The directory itself is
     * not part of the result.
     */
    @NonNull
    public ListResult list(@Nullable String relativeDir) throws WebDavException {
        HttpUrl url = buildUrl(relativeDir);
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", _authorization)
                .header("User-Agent", "Aegis")
                .header("Depth", "1")
                .method("PROPFIND", RequestBody.create(null, new byte[0]))
                .build();

        String requested = joinSegments(splitPath(relativeDir));
        try (Response response = execute(request, "PROPFIND " + requested)) {
            int code = response.code();
            if (code != 207) {
                throw createHttpException("PROPFIND " + requested, code, response.header("Retry-After"));
            }
            byte[] body = readLimited(responseBody(response, "PROPFIND " + requested), MAX_LIST_BYTES,
                    "PROPFIND " + requested);
            return parseMultiStatus(body, requested);
        }
    }

    public void upload(String relativePath, byte[] content) throws WebDavException {
        if (content.length > MAX_UPLOAD_BYTES) {
            throw new WebDavException(WebDavException.Reason.TOO_LARGE,
                    String.format("Refusing to upload %d bytes; the limit is %d",
                            content.length, MAX_UPLOAD_BYTES));
        }

        Request request = newRequest(relativePath)
                .method("PUT", RequestBody.create(JSON_MEDIA_TYPE, content))
                .build();
        try (Response response = execute(request, "PUT " + relativePath)) {
            int code = response.code();
            if (code != 200 && code != 201 && code != 204) {
                throw createHttpException("PUT " + relativePath, code, response.header("Retry-After"));
            }
        }
    }

    /**
     * Downloads a file and counts the actual number of bytes read, so a lying
     * or missing Content-Length header cannot bypass the limit.
     */
    @NonNull
    public byte[] download(String relativePath, long maxBytes) throws WebDavException {
        Request request = newRequest(relativePath).get().build();
        try (Response response = execute(request, "GET " + relativePath)) {
            int code = response.code();
            if (code != 200) {
                throw createHttpException("GET " + relativePath, code, response.header("Retry-After"));
            }
            return readLimited(responseBody(response, "GET " + relativePath), maxBytes, "GET " + relativePath);
        }
    }

    public void delete(String relativePath) throws WebDavException {
        Request request = newRequest(relativePath).delete().build();
        try (Response response = execute(request, "DELETE " + relativePath)) {
            int code = response.code();
            if (code != 200 && code != 202 && code != 204 && code != 404) {
                throw createHttpException("DELETE " + relativePath, code, response.header("Retry-After"));
            }
        }
    }

    /**
     * Verifies that the target directory is reachable and writable by uploading
     * a small probe file, reading it back and deleting it again.
     *
     * @return null when everything including the cleanup succeeded, or a
     *         human-readable warning when only the cleanup failed.
     */
    @Nullable
    public String testConnection() throws WebDavException {
        ensureRootDirectory();

        String probeName = "aegis-connection-test-" + UUID.randomUUID() + ".txt";
        String probePath = joinPath(_rootPath, probeName);
        byte[] data = ("aegis-probe:" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

        upload(probePath, data);
        byte[] downloaded = download(probePath, MAX_PROBE_BYTES);
        boolean matches = java.util.Arrays.equals(data, downloaded);

        try {
            delete(probePath);
        } catch (WebDavException e) {
            return "The connection test succeeded, but the probe file could not be removed: "
                    + e.getMessage();
        }

        if (!matches) {
            throw new WebDavException(WebDavException.Reason.PROTOCOL,
                    "The connection test read back different content than it uploaded");
        }
        return null;
    }

    /**
     * Cancels all requests of this client. The client must not be reused
     * afterwards.
     */
    public void cancel() {
        _canceled = true;
        _client.dispatcher().cancelAll();
    }

    @Override
    public void close() {
        _client.dispatcher().cancelAll();
        _client.connectionPool().evictAll();
    }

    private static OkHttpClient createHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    private static String createAuthorization(String account, char[] password) {
        byte[] bytes = null;
        try {
            String userInfo = account + ":" + new String(password);
            bytes = userInfo.getBytes(StandardCharsets.UTF_8);
            return "Basic " + Base64.encode(bytes);
        } finally {
            if (bytes != null) {
                java.util.Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    private Request.Builder newRequest(String relativePath) throws WebDavException {
        return new Request.Builder()
                .url(buildUrl(relativePath))
                .header("Authorization", _authorization)
                .header("User-Agent", "Aegis");
    }

    /**
     * Builds a request URL from the validated base URL and the relative path.
     * Path segments are added through HttpUrl, which percent-encodes them.
     */
    private HttpUrl buildUrl(@Nullable String relativePath) throws WebDavException {
        HttpUrl.Builder builder = new HttpUrl.Builder()
                .scheme(_baseUrl.scheme())
                .host(_baseUrl.host())
                .port(_baseUrl.port());
        for (String segment : _baseUrl.pathSegments()) {
            if (!segment.isEmpty()) {
                builder.addPathSegment(segment);
            }
        }
        for (String segment : splitPath(relativePath)) {
            builder.addPathSegment(segment);
        }
        return builder.build();
    }

    private Response execute(Request request, String action) throws WebDavException {
        if (_canceled) {
            throw new WebDavException(WebDavException.Reason.CANCELLED,
                    action + " was canceled");
        }

        try {
            return _client.newCall(request).execute();
        } catch (SocketTimeoutException e) {
            throw new WebDavException(WebDavException.Reason.TIMEOUT,
                    action + " timed out", e);
        } catch (IOException e) {
            if (_canceled) {
                throw new WebDavException(WebDavException.Reason.CANCELLED,
                        action + " was canceled", e);
            }
            throw new WebDavException(WebDavException.Reason.NETWORK,
                    action + " failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private static ResponseBody responseBody(Response response, String action) throws WebDavException {
        ResponseBody body = response.body();
        if (body == null) {
            throw new WebDavException(WebDavException.Reason.PROTOCOL,
                    action + " returned an empty response body");
        }
        return body;
    }

    private byte[] readLimited(ResponseBody body, long maxBytes, String action)
            throws WebDavException {
        if (body.contentLength() > maxBytes) {
            throw new WebDavException(WebDavException.Reason.TOO_LARGE,
                    String.format("%s exceeded the size limit of %d bytes", action, maxBytes));
        }

        try (InputStream inStream = body.byteStream();
             ByteArrayOutputStream outStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = inStream.read(buffer)) != -1) {
                // The socket close performed by cancel() is not guaranteed to
                // wake up a blocked read on every platform, so the flag is
                // checked again as soon as data arrives.
                if (_canceled) {
                    throw new WebDavException(WebDavException.Reason.CANCELLED,
                            action + " was canceled");
                }
                total += read;
                if (total > maxBytes) {
                    throw new WebDavException(WebDavException.Reason.TOO_LARGE,
                            String.format("%s exceeded the size limit of %d bytes", action, maxBytes));
                }
                outStream.write(buffer, 0, read);
            }
            if (_canceled) {
                throw new WebDavException(WebDavException.Reason.CANCELLED,
                        action + " was canceled");
            }
            return outStream.toByteArray();
        } catch (SocketTimeoutException e) {
            throw new WebDavException(WebDavException.Reason.TIMEOUT,
                    action + " timed out while reading the response", e);
        } catch (IOException e) {
            if (_canceled) {
                throw new WebDavException(WebDavException.Reason.CANCELLED,
                        action + " was canceled", e);
            }
            throw new WebDavException(WebDavException.Reason.NETWORK,
                    action + " failed while reading the response", e);
        }
    }

    private ListResult parseMultiStatus(byte[] body, String requestedPath)
            throws WebDavException {
        Document document = parseXml(body);
        NodeList responses = document.getElementsByTagNameNS("*", "response");
        boolean complete = responses.getLength() < MAX_LIST_ENTRIES;

        List<RemoteEntry> entries = new ArrayList<>();
        for (int i = 0; i < responses.getLength(); i++) {
            Node node = responses.item(i);
            if (!(node instanceof Element)) {
                continue;
            }
            Element response = (Element) node;
            String href = textOf(response, "href");
            if (href == null) {
                continue;
            }

            HttpUrl url = _baseUrl.resolve(href);
            if (url == null) {
                throw new WebDavException(WebDavException.Reason.PROTOCOL,
                        "The server returned a malformed href");
            }
            String relativePath = toRelativePath(url);

            // Skip the requested collection itself.
            if (relativePath.equals(requestedPath)) {
                continue;
            }
            // Everything else must live inside the requested directory.
            if (!requestedPath.isEmpty() && !relativePath.startsWith(requestedPath + "/")) {
                throw new WebDavException(WebDavException.Reason.PROTOCOL,
                        "The server returned a href outside of the requested directory");
            }

            EntryProps props = readProps(response);
            String name = lastSegment(relativePath);
            entries.add(new RemoteEntry(relativePath, name, props.isDirectory(),
                    props.getSize(), props.getLastModified()));
        }

        return new ListResult(entries, complete);
    }

    private static EntryProps readProps(Element response) {
        EntryProps props = new EntryProps();
        NodeList propstats = response.getElementsByTagNameNS("*", "propstat");
        for (int i = 0; i < propstats.getLength(); i++) {
            Node node = propstats.item(i);
            if (!(node instanceof Element)) {
                continue;
            }
            Element propstat = (Element) node;
            String status = textOf(propstat, "status");
            if (status == null || !isSuccessStatus(status)) {
                continue;
            }

            Element prop = firstElement(propstat, "prop");
            if (prop == null) {
                continue;
            }

            if (prop.getElementsByTagNameNS("*", "collection").getLength() > 0) {
                props.setDirectory(true);
            }
            String length = textOf(prop, "getcontentlength");
            if (length != null) {
                try {
                    props.setSize(Long.parseLong(length.trim()));
                } catch (NumberFormatException ignored) {
                    // A malformed length is treated as unknown.
                }
            }
            String modified = textOf(prop, "getlastmodified");
            if (modified != null) {
                props.setLastModified(parseHttpDate(modified));
            }
        }
        return props;
    }

    private static boolean isSuccessStatus(String status) {
        // Format: "HTTP/1.1 200 OK"
        int space = status.indexOf(' ');
        if (space < 0) {
            return false;
        }
        int codeStart = space + 1;
        if (codeStart + 3 > status.length()) {
            return false;
        }
        try {
            int code = Integer.parseInt(status.substring(codeStart, codeStart + 3));
            return code >= 200 && code < 300;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static long parseHttpDate(String value) {
        try {
            return ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().getEpochSecond();
        } catch (DateTimeParseException e) {
            return 0;
        }
    }

    /**
     * Converts an absolute or relative href to a path relative to the base URL
     * and rejects anything outside of the configured host and directory.
     */
    private String toRelativePath(HttpUrl url) throws WebDavException {
        if (!url.scheme().equals(_baseUrl.scheme())
                || !url.host().equals(_baseUrl.host())
                || url.port() != _baseUrl.port()) {
            throw new WebDavException(WebDavException.Reason.PROTOCOL,
                    "The server returned a href outside of the configured host");
        }

        List<String> baseSegments = nonEmpty(_baseUrl.pathSegments());
        List<String> urlSegments = nonEmpty(url.pathSegments());
        if (urlSegments.size() < baseSegments.size()) {
            throw new WebDavException(WebDavException.Reason.PROTOCOL,
                    "The server returned a href outside of the configured directory");
        }
        for (int i = 0; i < baseSegments.size(); i++) {
            if (!baseSegments.get(i).equals(urlSegments.get(i))) {
                throw new WebDavException(WebDavException.Reason.PROTOCOL,
                        "The server returned a href outside of the configured directory");
            }
        }

        StringBuilder path = new StringBuilder();
        for (int i = baseSegments.size(); i < urlSegments.size(); i++) {
            if (path.length() > 0) {
                path.append('/');
            }
            path.append(urlSegments.get(i));
        }
        return path.toString();
    }

    private static List<String> nonEmpty(List<String> segments) {
        List<String> result = new ArrayList<>(segments.size());
        for (String segment : segments) {
            if (!segment.isEmpty()) {
                result.add(segment);
            }
        }
        return result;
    }

    private static Document parseXml(byte[] body) throws WebDavException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // Android's parser implementation does not support XInclude and
            // throws UnsupportedOperationException for it. XInclude is not used
            // by WebDAV responses, so failing to configure it is harmless.
            setXIncludeAwareIfSupported(factory);
            setExpandEntityReferencesIfSupported(factory);
            setFeatureIfSupported(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // DTDs are never needed for WebDAV responses. If a platform does not
            // support these features, the EntityResolver below still neutralizes
            // external entities.
            setFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFeatureIfSupported(factory, "http://xml.org/sax/features/external-parameter-entities", false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            return builder.parse(new ByteArrayInputStream(body));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new WebDavException(WebDavException.Reason.PROTOCOL,
                    "The server returned an invalid WebDAV XML response", e);
        }
    }

    private static void setFeatureIfSupported(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (ParserConfigurationException | UnsupportedOperationException ignored) {
            // The platform does not know this feature; other protections still apply.
        }
    }

    private static void setXIncludeAwareIfSupported(DocumentBuilderFactory factory) {
        try {
            factory.setXIncludeAware(false);
        } catch (UnsupportedOperationException ignored) {
            // Not supported on Android; XInclude is disabled by default.
        }
    }

    private static void setExpandEntityReferencesIfSupported(DocumentBuilderFactory factory) {
        try {
            factory.setExpandEntityReferences(false);
        } catch (UnsupportedOperationException ignored) {
            // Not supported on some platforms; the EntityResolver below still
            // neutralizes external entities.
        }
    }

    private static WebDavException createHttpException(String action, int code, @Nullable String retryAfter) {
        long retryAfterSeconds = parseRetryAfter(retryAfter);
        String message = String.format("%s failed with HTTP %d", action, code);
        switch (code) {
            case 401:
                return new WebDavException(WebDavException.Reason.AUTHENTICATION, message, code, retryAfterSeconds);
            case 403:
                return new WebDavException(WebDavException.Reason.PERMISSION, message, code, retryAfterSeconds);
            case 404:
                return new WebDavException(WebDavException.Reason.NOT_FOUND, message, code, retryAfterSeconds);
            case 405:
            case 409:
            case 423:
                return new WebDavException(WebDavException.Reason.CONFLICT, message, code, retryAfterSeconds);
            case 429:
                return new WebDavException(WebDavException.Reason.RATE_LIMITED, message, code, retryAfterSeconds);
            case 507:
                return new WebDavException(WebDavException.Reason.QUOTA, message, code, retryAfterSeconds);
            default:
                if (code >= 500) {
                    return new WebDavException(WebDavException.Reason.SERVER, message, code, retryAfterSeconds);
                }
                if (code >= 300) {
                    return new WebDavException(WebDavException.Reason.PROTOCOL, message, code, retryAfterSeconds);
                }
                return new WebDavException(WebDavException.Reason.UNKNOWN, message, code, retryAfterSeconds);
        }
    }

    private static long parseRetryAfter(@Nullable String value) {
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Splits and validates a relative path. Empty segments, parent traversal
     * and characters that would change the meaning of the path are rejected.
     */
    private static List<String> splitPath(@Nullable String path) throws WebDavException {
        if (path == null) {
            return Collections.emptyList();
        }
        String value = path;
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        value = value.substring(start, end);
        if (value.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> segments = new ArrayList<>();
        for (String rawSegment : value.split("/", -1)) {
            String segment = rawSegment.trim();
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.indexOf('\\') >= 0) {
                throw new WebDavException(WebDavException.Reason.CONFIGURATION,
                        "The remote path contains an invalid segment");
            }
            for (char c : segment.toCharArray()) {
                if (c < 0x20 || c == 0x7f) {
                    throw new WebDavException(WebDavException.Reason.CONFIGURATION,
                            "The remote path contains an invalid character");
                }
            }
            segments.add(segment);
        }
        return segments;
    }

    private static String joinPath(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('/');
            }
            builder.append(part);
        }
        return builder.toString();
    }

    private static String joinSegments(List<String> segments) {
        return joinPath(segments.toArray(new String[0]));
    }

    private static String lastSegment(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 ? path : path.substring(index + 1);
    }

    @Nullable
    private static String textOf(Element parent, String localName) {
        Element element = firstElement(parent, localName);
        if (element == null) {
            return null;
        }
        String text = element.getTextContent();
        if (text == null) {
            return null;
        }
        text = text.trim();
        return text.isEmpty() ? null : text;
    }

    @Nullable
    private static Element firstElement(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element) {
                return (Element) node;
            }
        }
        return null;
    }

    /**
     * Result of a directory listing. {@code complete} is false when the number
     * of returned entries reached the server limit, in which case the list must
     * not be used to delete files.
     */
    public static class ListResult {
        private final List<RemoteEntry> _entries;
        private final boolean _complete;

        ListResult(List<RemoteEntry> entries, boolean complete) {
            _entries = entries;
            _complete = complete;
        }

        @NonNull
        public List<RemoteEntry> getEntries() {
            return _entries;
        }

        public boolean isComplete() {
            return _complete;
        }
    }

    public static class RemoteEntry {
        private final String _path;
        private final String _name;
        private final boolean _directory;
        private final long _size;
        private final long _lastModified;

        RemoteEntry(String path, String name, boolean directory, long size, long lastModified) {
            _path = path;
            _name = name;
            _directory = directory;
            _size = size;
            _lastModified = lastModified;
        }

        @NonNull
        public String getPath() {
            return _path;
        }

        @NonNull
        public String getName() {
            return _name;
        }

        public boolean isDirectory() {
            return _directory;
        }

        public long getSize() {
            return _size;
        }

        /**
         * Last modification time in epoch seconds, or 0 if the server did not
         * provide a parseable value.
         */
        public long getLastModified() {
            return _lastModified;
        }
    }

    private static class EntryProps {
        private boolean _directory;
        private long _size;
        private long _lastModified;

        public boolean isDirectory() {
            return _directory;
        }

        public void setDirectory(boolean directory) {
            _directory = directory;
        }

        public long getSize() {
            return _size;
        }

        public void setSize(long size) {
            _size = size;
        }

        public long getLastModified() {
            return _lastModified;
        }

        public void setLastModified(long lastModified) {
            _lastModified = lastModified;
        }
    }
}
