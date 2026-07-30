package net.bmqy.dbeaver.webdav.backup.webdav;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WebDavClient {

    private static final Pattern HREF_PATTERN = Pattern.compile("<[^:>]*:?href>(.*?)</[^:>]*:?href>", Pattern.CASE_INSENSITIVE);

    private final HttpClient client;
    private final URI root;
    private final String authorization;

    public WebDavClient(String endpoint, String username, String password) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("WebDAV endpoint is required");
        }
        URI uri = parseEndpoint(endpoint);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Only HTTPS WebDAV endpoints are supported");
        }
        this.root = ensureSlash(uri);
        this.authorization = basic(username, password);
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public void test() throws IOException, InterruptedException {
        HttpRequest request = builder(root).method("PROPFIND", HttpRequest.BodyPublishers.noBody()).header("Depth", "0").build();
        requireSuccess(client.send(request, HttpResponse.BodyHandlers.discarding()), "test WebDAV endpoint");
    }

    public void ensureDirectory(String remoteDirectory) throws IOException, InterruptedException {
        String[] parts = normalize(remoteDirectory).split("/");
        String current = "";
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            current = current.isEmpty() ? part : current + "/" + part;
            URI uri = resolve(current + "/");
            HttpResponse<Void> response = client.send(builder(uri).method("MKCOL", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 201 && response.statusCode() != 405 && response.statusCode() != 301
                    && response.statusCode() != 302) {
                requireSuccess(response, "create remote directory " + current);
            }
        }
    }

    public void upload(Path file, String remotePath) throws IOException, InterruptedException {
        HttpRequest request = builder(resolve(remotePath)).PUT(HttpRequest.BodyPublishers.ofFile(file)).build();
        requireSuccess(client.send(request, HttpResponse.BodyHandlers.discarding()), "upload " + remotePath);
    }

    public byte[] download(String remotePath) throws IOException, InterruptedException {
        HttpRequest request = builder(resolve(remotePath)).GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        requireSuccess(response, "download " + remotePath);
        return response.body();
    }

    public List<String> listBackupFiles(String remoteDirectory) throws IOException, InterruptedException {
        HttpRequest request = builder(resolve(normalize(remoteDirectory) + "/"))
                .method("PROPFIND", HttpRequest.BodyPublishers.noBody())
                .header("Depth", "1")
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        requireSuccess(response, "list " + remoteDirectory);
        List<String> names = new ArrayList<>();
        Matcher matcher = HREF_PATTERN.matcher(response.body());
        while (matcher.find()) {
            String href = matcher.group(1);
            int slash = href.endsWith("/") ? href.lastIndexOf('/', href.length() - 2) : href.lastIndexOf('/');
            String name = href.substring(slash + 1);
            if (name.endsWith(".zip") && name.startsWith("dbeaver-backup-")) {
                names.add(name);
            }
        }
        names.sort(Comparator.reverseOrder());
        return names;
    }

    public void delete(String remotePath) throws IOException, InterruptedException {
        HttpResponse<Void> response = client.send(builder(resolve(remotePath)).DELETE().build(), HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 404) {
            requireSuccess(response, "delete " + remotePath);
        }
    }

    private HttpRequest.Builder builder(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).header("Authorization", authorization);
    }

    private URI resolve(String remotePath) {
        return root.resolve(encodePath(remotePath));
    }

    private static URI ensureSlash(URI uri) {
        String text = uri.toString();
        return URI.create(text.endsWith("/") ? text : text + "/");
    }

    private static URI parseEndpoint(String endpoint) {
        try {
            return URI.create(encodeUri(endpoint.trim().replace('\\', '/')));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid WebDAV endpoint", e);
        }
    }

    private static String basic(String username, String password) {
        String value = (username == null ? "" : username) + ":" + (password == null ? "" : password);
        return "Basic " + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String normalize(String remotePath) {
        String text = remotePath == null ? "" : remotePath.trim().replace('\\', '/');
        while (text.startsWith("/")) {
            text = text.substring(1);
        }
        return text;
    }

    private static String encodePath(String remotePath) {
        String normalized = normalize(remotePath);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch == '/') {
                out.append('/');
            } else if (ch == '%' && i + 2 < normalized.length() && isHex(normalized.charAt(i + 1))
                    && isHex(normalized.charAt(i + 2))) {
                out.append(ch).append(normalized.charAt(i + 1)).append(normalized.charAt(i + 2));
                i += 2;
            } else if (isPathChar(ch)) {
                out.append(ch);
            } else {
                appendEncoded(out, ch);
            }
        }
        return out.toString();
    }

    private static String encodeUri(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '%' && i + 2 < value.length() && isHex(value.charAt(i + 1)) && isHex(value.charAt(i + 2))) {
                out.append(ch).append(value.charAt(i + 1)).append(value.charAt(i + 2));
                i += 2;
            } else if (isUriChar(ch)) {
                out.append(ch);
            } else {
                appendEncoded(out, ch);
            }
        }
        return out.toString();
    }

    private static boolean isUriChar(char ch) {
        return isPathChar(ch) || ch == '/' || ch == '?' || ch == '#' || ch == '[' || ch == ']';
    }

    private static boolean isPathChar(char ch) {
        return isUnreserved(ch) || ":@!$&'()*+,;=".indexOf(ch) >= 0;
    }

    private static boolean isUnreserved(char ch) {
        return ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9'
                || ch == '-' || ch == '.' || ch == '_' || ch == '~';
    }

    private static boolean isHex(char ch) {
        return ch >= '0' && ch <= '9' || ch >= 'a' && ch <= 'f' || ch >= 'A' && ch <= 'F';
    }

    private static void appendEncoded(StringBuilder out, char ch) {
        for (byte b : String.valueOf(ch).getBytes(StandardCharsets.UTF_8)) {
            out.append('%');
            String hex = Integer.toHexString(Byte.toUnsignedInt(b)).toUpperCase();
            if (hex.length() == 1) {
                out.append('0');
            }
            out.append(hex);
        }
    }

    private static void requireSuccess(HttpResponse<?> response, String action) throws IOException {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("Failed to " + action + ": HTTP " + status);
        }
    }
}
