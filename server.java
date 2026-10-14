import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class server {
    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path MATERIALS = ROOT.resolve("Source").resolve("Mentah");
    private static final long MAX_UPLOAD_SIZE = 100L * 1024 * 1024;

    public static void main(String[] args) throws IOException {
        Files.createDirectories(MATERIALS);
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 8000), 0);
        httpServer.createContext("/", server::handle);
        httpServer.start();
        System.out.println("Modul Pemrograman Web: http://127.0.0.1:8000/modul.html");
        System.out.println("Folder materi: " + MATERIALS);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/api/materials")) {
            handleMaterialCollection(exchange);
        } else if (path.startsWith("/api/materials/")) {
            String rawPath = exchange.getRequestURI().getRawPath();
            handleMaterialFile(exchange, rawPath.substring("/api/materials/".length()));
        } else {
            serveStaticFile(exchange, path);
        }
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
        if (!"admin".equals(exchange.getRequestHeaders().getFirst("X-Module-Role"))) {
            sendJson(exchange, 403, Map.of("error", "Aksi ini hanya tersedia untuk Administrator_Root."));
            return false;
        }
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
