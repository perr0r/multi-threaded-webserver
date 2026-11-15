import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public class MyWebServer {
    private static int port = 8080;
    private static final Path PUBLIC_DIR = Paths.get("./docs/public").toAbsolutePath().normalize();

    public static void main(String[] args) {
        // port init
        for (int i = 0; i < args.length; i++)
            if ("--port".equals(args[i]) && i+1 < args.length)
                port = Integer.parseInt(args[++i]);

        // main
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("connected: " + clientSocket.getInetAddress());
                parseRequest(clientSocket);
            }
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }

    }

    private static void parseRequest(Socket socket) {
        try (InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {

            byte[] headerBytes = readHeaderBytes(in);
            if (headerBytes.length == 0) return;

            String headerText = new String(headerBytes, StandardCharsets.ISO_8859_1);
            String[] lines = headerText.split("\r\n");
            String[] requestLine = lines[0].split(" ");

            // 400: Bad Request
            if (requestLine.length != 3) {
                sendResponse(out, 400, "Bad Request", "text/plain", "400 Bad Request".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (!requestLine[0] in  ||  !requestLine[2].startsWith("HTTP/")) ) {
                sendResponse(out, 400, "Bad Request", "text/plain", "400 Bad Request".getBytes(StandardCharsets.UTF_8));
                return;
            }

            if (requestLine)

            String method = requestParts[0].toUpperCase();
            String uri = requestParts[1];

            if (!"GET".equals(method)) {
                sendResponse(out, 405, "Method Not Allowed", "text/plain", "405 Method Not Allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }

            handleGet(out, uri);
        } catch (IOException ignored) {
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

//    private static byte[] readHeaderBytes(InputStream in) throws IOException {
//        ByteArrayOutputStream baos = new ByteArrayOutputStream();
//        int b;
//        while ((b = in.read()) != -1) {
//            baos.write(b);
//            byte[] buf = baos.toByteArray();
//            int len = buf.length;
//            if (len >= 4 && buf[len - 4] == '\r' && buf[len - 3] == '\n'
//                    && buf[len - 2] == '\r' && buf[len - 1] == '\n') {
//                break;
//            }
//        }
//        return baos.toByteArray();
//    }
//
//    private static void handleGet(OutputStream out, String uri) throws IOException {
//        String relPath = uri.startsWith("/") ? uri.substring(1) : uri;
//        if (relPath.isEmpty()) relPath = "index.html";
//
//        Path targetPath = PUBLIC_DIR.resolve(relPath).normalize();
//        if (!targetPath.startsWith(PUBLIC_DIR) || !Files.exists(targetPath) || Files.isDirectory(targetPath)) {
//            sendResponse(out, 404, "Not Found", "text/plain", "404 Not Found".getBytes(StandardCharsets.UTF_8));
//            return;
//        }
//
//        byte[] fileBytes = Files.readAllBytes(targetPath);
//        sendResponse(out, 200, "OK", getMimeType(targetPath.getFileName().toString()), fileBytes);
//    }
//
//    private static String getMimeType(String filename) {
//        String lower = filename.toLowerCase();
//        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=UTF-8";
//        if (lower.endsWith(".css")) return "text/css";
//        if (lower.endsWith(".js")) return "text/javascript";
//        if (lower.endsWith(".png")) return "image/png";
//        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
//        if (lower.endsWith(".txt")) return "text/plain; charset=UTF-8";
//        return "application/octet-stream";
//    }
//
//    private static void sendResponse(OutputStream out, int code, String status, String contentType, byte[] body) throws IOException {
//        String headers = "HTTP/1.1 " + code + " " + status + "\r\n"
//                + "Content-Type: " + contentType + "\r\n"
//                + "Content-Length: " + body.length + "\r\n"
//                + "Connection: close\r\n\r\n";
//        out.write(headers.getBytes(StandardCharsets.UTF_8));
//        out.write(body);
//        out.flush();
//    }
}
