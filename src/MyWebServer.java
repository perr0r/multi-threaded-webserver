import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class MyWebServer {
    private static int port = 8080;
    private static int slowDown = 0;

    private static final Path PUBLIC_DIR = Paths.get("./docs/public").toAbsolutePath().normalize();
    private static final Path UPLOAD_DIR = Paths.get("./docs/upload").toAbsolutePath().normalize();

    private static final Object fileWriteLock = new Object();

    private static final ExecutorService threadPool = Executors.newCachedThreadPool();

    public static void main(String[] args) {
        // --port --slowDown
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i+1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--slowDown".equals(args[i]) && i+1 < args.length) {
                slowDown = Integer.parseInt(args[++i]);
            }
        }

        // main
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                threadPool.execute(() -> handleClient(clientSocket));
            }
        } catch (IOException e) {
            System.out.println("error: " + e.getMessage());
        } finally {
            threadPool.shutdown();
        }
    }

    private static void handleClient(Socket socket) {
        try (InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {

            byte[] headerBytes = readHeaderBytes(in);
            if (headerBytes == null || headerBytes.length == 0) return;

                String headerText = new String(headerBytes, StandardCharsets.ISO_8859_1);
            String[] lines = headerText.split("\r\n");
            if (lines.length == 0) {
                sendResponse(out, 400, "Bad Request", "text/plain", "400 Bad Request".getBytes(StandardCharsets.UTF_8));
                return;
            }

            String[] requestLine = lines[0].split(" ");
            if (requestLine.length != 3) {
                sendResponse(out, 400, "Bad Request", "text/plain", "400 Bad Request".getBytes(StandardCharsets.UTF_8));
                return;
            }

            if (!requestLine[2].startsWith("HTTP/")) {
                sendResponse(out, 400, "Bad Request", "text/plain", "400 Bad Request".getBytes(StandardCharsets.UTF_8));
                return;
            }

            String method = requestLine[0].toUpperCase();
            String uri = requestLine[1];
            switch (method) {
                case "GET":
                    handleGet(out, uri);
                    break;
                case "PUT":
                    handlePut(in, out, uri, lines);
                    break;
                case "POST":
                    handlePost(in, out, uri, lines);
                    break;
                default:
                    sendResponse(out, 405, "Method Not Allowed", "text/plain", "405 Method Not Allowed".getBytes(StandardCharsets.UTF_8));
                    break;
            }

        } catch (IOException ignored) {
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private static String getHeaderValue(String[] lines, String headerName) {
        for (int i = 1; i < lines.length; i++) {
            int colonIdx = lines[i].indexOf(":");

            if (colonIdx > 0) {
                String name = lines[i].substring(0, colonIdx).trim();
                if (name.equalsIgnoreCase(headerName))
                    return lines[i].substring(colonIdx+1).trim();
            }
        }
        return null;
    }

    private static byte[] readHeaderBytes(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int state = 0;
        int b;
        while ((b = in.read()) != -1) {
            baos.write(b);
            if ((state == 0 || state == 2) && b == '\r') {
                state++;
            } else if ((state == 1 || state == 3) && b == '\n') {
                state++;
                if (state == 4) break;
            } else {
                state = (b == '\r') ? 1 : 0;
            }
        }
        return baos.toByteArray();
    }

    private static void handleGet(OutputStream out, String uri) throws IOException {
        String relativePath = uri.startsWith("/") ? uri.substring(1) : uri;
        if (relativePath.isEmpty()) relativePath = "index.html";

        int qIdx = relativePath.indexOf('?');
        if (qIdx != -1) relativePath = relativePath.substring(0, qIdx);

        Path targetPath = PUBLIC_DIR.resolve(relativePath).normalize();
        if (!targetPath.startsWith(PUBLIC_DIR) || !Files.exists(targetPath) || Files.isDirectory(targetPath)) {
            sendResponse(out, 404, "Not Found", "text/plain", "404 Not Found".getBytes(StandardCharsets.UTF_8));
            return;
        }

        byte[] fileBytes = Files.readAllBytes(targetPath);
        sendResponse(out, 200, "OK", getMimeType(targetPath.getFileName().toString()), fileBytes);
    }

    private static void handlePut(InputStream in, OutputStream out, String uri, String[] lines) throws IOException {
        String lenStr = getHeaderValue(lines, "content-length");
        if (lenStr == null) {
            sendResponse(out, 400, "Bad Request", "text/plain", "400 Bad Request".getBytes(StandardCharsets.UTF_8));
            return;
        }
        int contentLength;
        try {
            contentLength = Integer.parseInt(lenStr);
        } catch (NumberFormatException e) {
            sendResponse(out, 400, "Bad Request", "text/plain", "400 Bad Request".getBytes(StandardCharsets.UTF_8));
            return;
        }

        if (!uri.startsWith("/upload/")) {
            sendResponse(out, 400, "Bad Request", "text/plain", "400 Bad Request".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String relativePath = uri.substring("/upload/".length());
        if (relativePath.isEmpty()) {
            sendResponse(out, 400, "Bad Request", "text/plain", "400 Bad Request".getBytes(StandardCharsets.UTF_8));
            return;
        }

        Path targetPath = UPLOAD_DIR.resolve(relativePath).normalize();
        if (!targetPath.startsWith(UPLOAD_DIR) || Files.isDirectory(targetPath)) {
            sendResponse(out, 400, "Bad Request", "text/plain", "400 Bad Request".getBytes(StandardCharsets.UTF_8));
            return;
        }

        byte[] fileBytes = in.readNBytes(contentLength);
        boolean isCreated;

        synchronized (fileWriteLock) {
            if (slowDown > 0) {
                System.out.println("[" + Thread.currentThread().getName() + "] Entered synchronized write for: " + relativePath);
                try {
                    Thread.sleep(slowDown);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            isCreated = !Files.exists(targetPath);
            Files.write(
                    targetPath,
                    fileBytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );

            if (slowDown > 0) {
                System.out.println("[" + Thread.currentThread().getName() + "] Exited synchronized write for: " + relativePath);
            }
        }

        if (isCreated) {
            sendResponse(out, 201, "Created", "text/plain", "201 Created".getBytes(StandardCharsets.UTF_8));
        } else {
            sendResponse(out, 200, "OK", "text/plain", "200 OK".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void handlePost(InputStream in, OutputStream out, String uri, String[] lines) throws IOException {
        String contentTypeHeader = getHeaderValue(lines, "content-type");
        if (contentTypeHeader == null || !contentTypeHeader.toLowerCase().contains("multipart/form-data")) {
            sendResponse(out, 400, "Bad Request", "text/plain", "400 Bad Request".getBytes(StandardCharsets.UTF_8));
            return;
        }

        String lenStr = getHeaderValue(lines, "content-length");
        if (lenStr == null) {
            sendResponse(out, 400, "Bad Request", "text/plain", "400 Bad Request".getBytes(StandardCharsets.UTF_8));
            return;
        }
        int contentLength;
        try {
            contentLength = Integer.parseInt(lenStr);
        } catch (NumberFormatException e) {
            sendResponse(out, 400, "Bad Request", "text/plain", "400 Bad Request".getBytes(StandardCharsets.UTF_8));
            return;
        }

        String boundary = null;
        String[] parts = contentTypeHeader.split("boundary=");
        if (parts.length != 2) {
            sendResponse(out, 400, "Bad Request", "text/plain", "400 Bad Request".getBytes(StandardCharsets.UTF_8));
            return;
        }
        boundary = parts[1].split(";")[0].trim().replace("\"", "");
        if (boundary == null) {
            sendResponse(out, 400, "Bad Request", "text/plain", "400 Bad Request".getBytes(StandardCharsets.UTF_8));
            return;
        }

        byte[] body = in.readNBytes(contentLength);
        PartFile[] multiPartFile = parseMultipartFile(body, boundary);
        if (multiPartFile == null) {
            sendResponse(out, 400, "Bad Request", "text/plain", "400 Bad Request".getBytes(StandardCharsets.UTF_8));
            return;
        }
        PartFile targetFile = null;
        for (PartFile part : multiPartFile) {
            if (part.name.equals("file")) {
                targetFile = part;
                break;
            }
        }
        if (targetFile == null || targetFile.filename == null || targetFile.filename.isEmpty()) {
            sendResponse(out, 400, "Bad Request", "text/plain", "400 Bad Request".getBytes(StandardCharsets.UTF_8));
            return;
        }

        String relativePath = Paths.get(targetFile.filename).getFileName().toString();
        Path targetPath = UPLOAD_DIR.resolve(relativePath).normalize();
        if (!targetPath.startsWith(UPLOAD_DIR) || Files.isDirectory(targetPath)) {
            sendResponse(out, 400, "Bad Request", "text/plain", "400 Bad Request".getBytes(StandardCharsets.UTF_8));
            return;
        }

        synchronized (fileWriteLock) {
            if (slowDown > 0) {
                System.out.println("[" + Thread.currentThread().getName() + "] Entered synchronized write for: " + relativePath);
                try {
                    Thread.sleep(slowDown);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            Files.write(targetPath, targetFile.data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            if (slowDown > 0) {
                System.out.println("[" + Thread.currentThread().getName() + "] Exited synchronized write for: " + relativePath);
            }
        }

        sendResponse(out, 201, "Created", "text/plain", "201 Created".getBytes(StandardCharsets.UTF_8));
    }

    private static PartFile[] parseMultipartFile(byte[] body, String boundary) {
        String bodyText = new String(body, StandardCharsets.ISO_8859_1);
        String realBoundary = "--" + boundary;

        String[] parts = bodyText.split(java.util.regex.Pattern.quote(realBoundary));

        if (parts.length < 3) return null;
        PartFile[] multiPartFile = new PartFile[parts.length-2]; // excluding ""(idx 0), "--"(idx n-1)

        for (int i = 0; i < parts.length-2; i++) {
            String part = parts[i+1];

            int headerEnd = part.indexOf("\r\n\r\n");
            if (headerEnd == -1) return null;

            String partHeader = part.substring(0, headerEnd);
            String[] partLines = partHeader.split("\r\n");
            String disposition = getHeaderValue(partLines, "content-disposition");
            if (disposition == null) return null;

            String[] filenameParts = disposition.split("filename=\"");
            if (filenameParts.length != 2) return null;
            String filename = filenameParts[1].split("\"")[0];

            String[] nameParts = disposition.split("name=\"");
            if (nameParts.length != 3) return null;
            String name = nameParts[1].split("\"")[0];

            String fileContent = part.substring(headerEnd+"\r\n\r\n".length());
            if (fileContent.endsWith("\r\n")) fileContent = fileContent.substring(0, fileContent.length()-2);
            byte[] fileData = fileContent.getBytes(StandardCharsets.ISO_8859_1);

            multiPartFile[i] = new PartFile(name, filename, fileData);
        }

        return multiPartFile;
    }

    private static class PartFile {
        final String name;
        final String filename;
        final byte[] data;

        PartFile(String name, String filename, byte[] data) {
            this.name = name;
            this.filename = filename;
            this.data = data;
        }
    }

    private static String getMimeType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=UTF-8";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js")) return "text/javascript";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".bin")) return "application/octet-stream";
        if (lower.endsWith(".txt")) return "text/plain; charset=UTF-8";
        return "application/octet-stream";
    }

    private static void sendResponse(OutputStream out, int statusCode, String statusText,
                                     String contentType, byte[] body) throws IOException {
        int length = (body != null) ? body.length : 0;
        String responseHeader = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + "Connection: close\r\n\r\n";

        out.write(responseHeader.getBytes(StandardCharsets.UTF_8));
        if (body != null) out.write(body);
        out.flush();
    }
}
