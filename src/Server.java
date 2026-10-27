import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/** Small HTTP backend (JDK only). The browser is just a view; all edits go to the PieceTree. */
public final class Server {
    private static final EditingController doc = new EditingController();
    private static final Path WEB = Paths.get("web");

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path sample = Paths.get("sample_document.txt");
        if (Files.exists(sample)) openFile(sample.toString());

        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);   // local only
        s.createContext("/api/", Server::api);
        s.createContext("/", Server::staticFile);
        s.start();
        System.out.println("Open http://localhost:" + port);
    }

    private static synchronized void api(HttpExchange ex) throws IOException {
        try {
            Map<String, String> p = form(ex);
            switch (ex.getRequestURI().getPath()) {
                case "/api/state": break;
                case "/api/openfile": openFile(p.get("path")); break;
                case "/api/save": doc.save(); break;
                case "/api/saveas": doc.saveAs(Paths.get(need(p, "path"))); break;
                case "/api/replace": doc.replace(num(p, "pos"), num(p, "len"), p.getOrDefault("text", "")); break;
                case "/api/insert": doc.insert(num(p, "pos"), p.getOrDefault("text", "")); break;
                case "/api/delete": doc.delete(num(p, "pos"), num(p, "len")); break;
                case "/api/undo": doc.undo(); break;
                case "/api/redo": doc.redo(); break;
                default: send(ex, 404, "{\"error\":\"unknown endpoint\"}"); return;
            }
            send(ex, 200, state());
        } catch (IOException e) {
            send(ex, 400, "{\"error\":" + q("File error (" + e.getClass().getSimpleName() + "): " + e.getMessage()) + "}");
        } catch (IndexOutOfBoundsException | IllegalArgumentException | IllegalStateException e) {
            send(ex, 400, "{\"error\":" + q(String.valueOf(e.getMessage())) + "}");
        }
    }

    private static String need(Map<String, String> p, String k) {
        String v = p.get(k);
        if (v == null || v.trim().isEmpty()) throw new IllegalArgumentException("'" + k + "' is required");
        return v.trim();
    }

    /** Opens a real file (strict UTF-8). Line endings are normalised to \n because browsers do the same in a textarea. */
    static void openFile(String path) throws IOException {
        Path f = Paths.get(need(Map.of("path", path == null ? "" : path), "path"));
        byte[] bytes = Files.readAllBytes(f);
        String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        doc.newDocument(text.replace("\r\n", "\n").replace('\r', '\n'), f.toAbsolutePath());
    }

    private static int num(Map<String, String> p, String k) {
        try { return Integer.parseInt(p.get(k).trim()); }
        catch (RuntimeException e) { throw new IllegalArgumentException("'" + k + "' must be a whole number"); }
    }

    private static Map<String, String> form(HttpExchange ex) throws IOException {
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> m = new HashMap<>();
        if (raw.isEmpty()) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
        }
        return m;
    }

    private static String state() {
        PieceTree t = doc.tree();
        return "{\"text\":" + q(doc.getText()) + ",\"length\":" + t.length() + ",\"pieces\":" + t.pieceCount()
            + ",\"height\":" + t.height() + ",\"file\":" + q(doc.getFile() == null ? "" : doc.getFile().toString()) + ",\"dirty\":" + doc.isDirty()
            + ",\"caret\":" + doc.caretHint() + ",\"canUndo\":" + doc.canUndo() + ",\"canRedo\":" + doc.canRedo()
            + ",\"tree\":" + tree(t.root(), 0) + "}";
    }

    private static String tree(Node n, int depth) {
        if (n == null) return "null";
        if (depth >= 5) return "{\"hidden\":" + n.subtreePieces() + "}";
        return "{\"src\":\"" + n.piece().source() + "\",\"start\":" + n.piece().start() + ",\"len\":" + n.piece().length()
            + ",\"h\":" + n.height() + ",\"size\":" + n.subtreeChars()
            + ",\"left\":" + tree(n.left(), depth + 1) + ",\"right\":" + tree(n.right(), depth + 1) + "}";
    }

    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default: if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private static void staticFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        Path f = WEB.resolve(path.equals("/") ? "index.html" : path.substring(1)).normalize();
        if (!f.startsWith(WEB) || !Files.isRegularFile(f)) { send(ex, 404, "not found"); return; }
        String name = f.toString();
        String type = name.endsWith(".css") ? "text/css" : name.endsWith(".js") ? "text/javascript" : "text/html";
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        byte[] b = Files.readAllBytes(f);
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        if (!ex.getResponseHeaders().containsKey("Content-Type"))
            ex.getResponseHeaders().set("Content-Type", body.startsWith("{") ? "application/json; charset=utf-8" : "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }
}
