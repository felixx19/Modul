import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class server {
    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path MATERIALS = Path.of(
            System.getenv().getOrDefault("MATERIALS_DIR", ROOT.resolve("Source").resolve("Mentah").toString())
    ).toAbsolutePath().normalize();
    private static final String INITIAL_MATERIALS_DIR = System.getenv("INITIAL_MATERIALS_DIR");
    private static final long MAX_UPLOAD_SIZE = 100L * 1024 * 1024;
    private static final long SESSION_LIFETIME_MILLIS = Duration.ofHours(8).toMillis();
    private static final Map<String, Long> ADMIN_SESSIONS = new ConcurrentHashMap<>();
    private static final String ADMIN_PASSWORD = loadAdminPassword();

    public static void main(String[] args) throws IOException {
        Files.createDirectories(MATERIALS);
        seedInitialMaterials();
        String host = System.getenv().getOrDefault("HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8000"));
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
        httpServer.createContext("/", server::handle);
        httpServer.start();
        System.out.println("Modul Pemrograman Web server listening on " + host + ":" + port);
        System.out.println("Folder materi: " + MATERIALS);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/api/")) {
            if (!configureCors(exchange)) {
                sendJson(exchange, 403, Map.of("error", "Origin tidak diizinkan."));
                return;
            }
            if (exchange.getRequestMethod().equals("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
        }
        if (path.equals("/api/health")) {
            sendJson(exchange, 200, Map.of("status", "ok"));
        } else if (path.equals("/api/session")) {
            handleSession(exchange);
        } else if (path.equals("/api/materials")) {
            handleMaterialCollection(exchange);
        } else if (path.startsWith("/Source/Mentah/")) {
            String rawPath = exchange.getRequestURI().getRawPath();
            serveMaterialDownload(exchange, rawPath.substring("/Source/Mentah/".length()));
        } else if (path.startsWith("/api/materials/")) {
            String rawPath = exchange.getRequestURI().getRawPath();
            handleMaterialFile(exchange, rawPath.substring("/api/materials/".length()));
        } else {
            serveStaticFile(exchange, path);
        }
    }

    private static void handleSession(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equals("POST")) {
            byte[] body = exchange.getRequestBody().readNBytes(4097);
            if (body.length > 4096) {
                sendJson(exchange, 413, Map.of("error", "Data login terlalu besar."));
                return;
            }

            String form = new String(body, StandardCharsets.UTF_8);
            String username;
            String password;
            try {
                username = queryParameter(form, "username");
                password = queryParameter(form, "password");
            } catch (IllegalArgumentException exception) {
                sendJson(exchange, 400, Map.of("error", "Data login tidak valid."));
                return;
            }
            if (!username.equals("Admin_Root")
                    || !MessageDigest.isEqual(
                            password.getBytes(StandardCharsets.UTF_8),
                            ADMIN_PASSWORD.getBytes(StandardCharsets.UTF_8)
                    )) {
                sendJson(exchange, 401, Map.of("error", "Username atau password Administrator_Root salah."));
                return;
            }

            String token = UUID.randomUUID().toString();
            ADMIN_SESSIONS.put(token, System.currentTimeMillis() + SESSION_LIFETIME_MILLIS);
            sendJson(exchange, 200, Map.of("token", token, "expiresInSeconds", SESSION_LIFETIME_MILLIS / 1000));
            return;
        }
        if (exchange.getRequestMethod().equals("DELETE")) {
            String token = adminToken(exchange);
            if (token != null) ADMIN_SESSIONS.remove(token);
            sendJson(exchange, 200, Map.of("loggedOut", true));
            return;
        }
        sendJson(exchange, 405, Map.of("error", "Metode tidak diizinkan."));
    }

    private static void handleMaterialCollection(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (method.equals("GET")) {
            listMaterials(exchange);
            return;
        }
        if (method.equals("POST")) {
            uploadMaterial(exchange);
            return;
        }
        sendJson(exchange, 405, Map.of("error", "Metode tidak diizinkan."));
    }

    private static void listMaterials(HttpExchange exchange) throws IOException {
        List<Path> files;
        try (Stream<Path> entries = Files.list(MATERIALS)) {
            files = entries.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                    .toList();
        }

        StringBuilder response = new StringBuilder("{\"files\":[");
        for (int index = 0; index < files.size(); index++) {
            Path file = files.get(index);
            if (index > 0) response.append(',');
            response.append("{\"name\":\"")
                    .append(jsonEscape(file.getFileName().toString()))
                    .append("\",\"size\":")
                    .append(Files.size(file))
                    .append('}');
        }
        response.append("]}");
        sendJson(exchange, 200, response.toString());
    }

    private static void uploadMaterial(HttpExchange exchange) throws IOException {
        if (!requireAdmin(exchange)) return;

        String filename;
        try {
            filename = queryParameter(exchange.getRequestURI().getRawQuery(), "filename");
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 400, Map.of("error", "Nama file tidak valid."));
            return;
        }
        if (!isValidFilename(filename)) {
            sendJson(exchange, 400, Map.of("error", "Nama file tidak valid."));
            return;
        }

        long contentLength;
        try {
            contentLength = Long.parseLong(exchange.getRequestHeaders().getFirst("Content-Length"));
        } catch (RuntimeException exception) {
            sendJson(exchange, 411, Map.of("error", "Ukuran file tidak dapat ditentukan."));
            return;
        }
        if (contentLength <= 0 || contentLength > MAX_UPLOAD_SIZE) {
            sendJson(exchange, 413, Map.of("error", "Ukuran file harus lebih dari 0 dan tidak melebihi 100 MB."));
            return;
        }

        Path destination = MATERIALS.resolve(filename);
        Path temporaryFile = Files.createTempFile(MATERIALS, ".upload-", ".tmp");
        try {
            long copied = Files.copy(exchange.getRequestBody(), temporaryFile, StandardCopyOption.REPLACE_EXISTING);
            if (copied != contentLength) {
                sendJson(exchange, 400, Map.of("error", "Unggahan tidak lengkap."));
                return;
            }
            Files.move(temporaryFile, destination);
            sendJson(exchange, 201, Map.of("name", filename, "size", copied));
        } catch (FileAlreadyExistsException exception) {
            sendJson(exchange, 409, Map.of("error", "File dengan nama tersebut sudah ada."));
        } catch (IOException exception) {
            sendJson(exchange, 500, Map.of("error", "File gagal disimpan: " + exception.getMessage()));
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private static void handleMaterialFile(HttpExchange exchange, String encodedFilename) throws IOException {
        if (!exchange.getRequestMethod().equals("DELETE")) {
            sendJson(exchange, 405, Map.of("error", "Metode tidak diizinkan."));
            return;
        }
        if (!requireAdmin(exchange)) return;

        String filename;
        try {
            filename = URLDecoder.decode(encodedFilename, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 400, Map.of("error", "Nama file tidak valid."));
            return;
        }
        if (!isValidFilename(filename)) {
            sendJson(exchange, 400, Map.of("error", "Nama file tidak valid."));
            return;
        }

        Path target = MATERIALS.resolve(filename);
        if (!Files.isRegularFile(target)) {
            sendJson(exchange, 404, Map.of("error", "File tidak ditemukan di Source/Mentah/."));
            return;
        }
        try {
            Files.delete(target);
            sendJson(exchange, 200, Map.of("name", filename, "deleted", true));
        } catch (IOException exception) {
            sendJson(exchange, 500, Map.of("error", "File gagal dihapus: " + exception.getMessage()));
        }
    }

    private static boolean requireAdmin(HttpExchange exchange) throws IOException {
        String token = adminToken(exchange);
        if (token == null) {
            sendJson(exchange, 401, Map.of("error", "Sesi Administrator_Root tidak valid atau sudah kedaluwarsa. Silakan login kembali."));
            return false;
        }
        return true;
    }

    private static String adminToken(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) return null;
        String token = authorization.substring("Bearer ".length());
        Long expiresAt = ADMIN_SESSIONS.get(token);
        if (expiresAt == null) return null;
        if (expiresAt <= System.currentTimeMillis()) {
            ADMIN_SESSIONS.remove(token);
            return null;
        }
        return token;
    }

    private static void serveMaterialDownload(HttpExchange exchange, String encodedFilename) throws IOException {
        if (!exchange.getRequestMethod().equals("GET") && !exchange.getRequestMethod().equals("HEAD")) {
            sendJson(exchange, 405, Map.of("error", "Metode tidak diizinkan."));
            return;
        }
        String filename;
        try {
            filename = URLDecoder.decode(encodedFilename, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 400, Map.of("error", "Nama file tidak valid."));
            return;
        }
        if (!isValidFilename(filename)) {
            sendJson(exchange, 400, Map.of("error", "Nama file tidak valid."));
            return;
        }
        Path file = MATERIALS.resolve(filename);
        if (!Files.isRegularFile(file)) {
            sendJson(exchange, 404, Map.of("error", "File materi tidak ditemukan."));
            return;
        }

        String contentType = filename.toLowerCase().endsWith(".pdf")
                ? "application/pdf"
                : filename.toLowerCase().endsWith(".pptx")
                        ? "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                        : "application/octet-stream";
        String encodedDownloadName = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename*=UTF-8''" + encodedDownloadName);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        long size = Files.size(file);
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, size);
        try (var output = exchange.getResponseBody()) {
            Files.copy(file, output);
        }
    }

    private static boolean configureCors(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null) return true;

        String configuredOrigins = System.getenv("ALLOWED_ORIGIN");
        if (configuredOrigins == null || configuredOrigins.isBlank()) {
            if (!origin.matches("^http://(127\\.0\\.0\\.1|localhost)(:\\d+)?$")) return false;
        } else {
            boolean allowed = Stream.of(configuredOrigins.split(","))
                    .map(String::trim)
                    .anyMatch(origin::equals);
            if (!allowed) return false;
        }

        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "600");
        exchange.getResponseHeaders().set("Vary", "Origin");
        return true;
    }

    private static String queryParameter(String query, String name) {
        if (query == null) throw new IllegalArgumentException("Query tidak tersedia.");
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (URLDecoder.decode(parts[0], StandardCharsets.UTF_8).equals(name)) {
                return URLDecoder.decode(parts.length > 1 ? parts[1] : "", StandardCharsets.UTF_8);
            }
        }
        throw new IllegalArgumentException("Nama file tidak tersedia.");
    }

    private static String loadAdminPassword() {
        String password = System.getenv("ADMIN_PASSWORD");
        if ((password == null || password.isBlank())
                && "true".equalsIgnoreCase(System.getenv("REQUIRE_ADMIN_PASSWORD"))) {
            throw new IllegalStateException("Set the ADMIN_PASSWORD environment variable before starting the server.");
        }
        return password == null || password.isBlank() ? "password" : password;
    }

    private static void seedInitialMaterials() throws IOException {
        if (INITIAL_MATERIALS_DIR == null || INITIAL_MATERIALS_DIR.isBlank()) return;
        Path initialMaterials = Path.of(INITIAL_MATERIALS_DIR);
        Path marker = MATERIALS.getParent().resolve(".materials-initialized");
        if (Files.exists(marker) || !Files.isDirectory(initialMaterials)) return;

        try (Stream<Path> entries = Files.list(initialMaterials)) {
            for (Path source : entries.filter(Files::isRegularFile).toList()) {
                Path destination = MATERIALS.resolve(source.getFileName().toString());
                if (!Files.exists(destination)) Files.copy(source, destination);
            }
        }
        Files.createFile(marker);
    }

    private static boolean isValidFilename(String filename) {
        return filename != null
                && !filename.isBlank()
                && !filename.equals(".")
                && !filename.equals("..")
                && filename.chars().noneMatch(character -> character < 32)
                && Path.of(filename).getFileName().toString().equals(filename)
                && !filename.contains("/")
                && !filename.contains("\\");
    }

    private static void serveStaticFile(HttpExchange exchange, String urlPath) throws IOException {
        if (!exchange.getRequestMethod().equals("GET") && !exchange.getRequestMethod().equals("HEAD")) {
            sendJson(exchange, 405, Map.of("error", "Metode tidak diizinkan."));
            return;
        }
        String relativePath = urlPath.equals("/") ? "index.html" : urlPath.substring(1);
        Path file = ROOT.resolve(relativePath).normalize();
        if (!file.startsWith(ROOT) || !Files.isRegularFile(file)) {
            sendJson(exchange, 404, Map.of("error", "File tidak ditemukan."));
            return;
        }

        String contentType = Files.probeContentType(file);
        exchange.getResponseHeaders().set(
                "Content-Type",
                contentType == null ? "application/octet-stream" : contentType
        );
        long size = Files.size(file);
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, size);
        try (var output = exchange.getResponseBody()) {
            Files.copy(file, output);
        }
    }

    private static void sendJson(HttpExchange exchange, int status, Map<String, ?> payload) throws IOException {
        StringBuilder json = new StringBuilder("{");
        int index = 0;
        for (Map.Entry<String, ?> entry : payload.entrySet()) {
            if (index++ > 0) json.append(',');
            json.append('"').append(jsonEscape(entry.getKey())).append("\":");
            Object value = entry.getValue();
            if (value instanceof Number || value instanceof Boolean) {
                json.append(value);
            } else {
                json.append('"').append(jsonEscape(String.valueOf(value))).append('"');
            }
        }
        sendJson(exchange, status, json.append('}').toString());
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
