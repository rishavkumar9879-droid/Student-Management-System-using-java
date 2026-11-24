import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;


public class StudentApp {

    record Student(int id, String name, String email, String course, int year, double cgpa) {}

    static final int PORT = 8080;
    static final Path DB_FILE = Path.of("students.tsv");   // data is saved here
    static final Map<Integer, Student> students = new TreeMap<>();
    static int nextId = 1;

    public static void main(String[] args) throws IOException {
        load();
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", StudentApp::handle);
        server.start();
        System.out.println("Student Management System running at http://localhost:" + PORT);
        System.out.println("Press Ctrl+C to stop.");
    }

    
    static void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/")) {
                send(ex, 200, "text/html; charset=utf-8", PAGE);
            } else if (path.equals("/api/students")) {
                api(ex);
            } else {
                send(ex, 404, "text/plain; charset=utf-8", "Not found");
            }
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "application/json", error("Server error: " + e.getMessage()));
        } finally {
            ex.close();
        }
    }

    static synchronized void api(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        Map<String, String> p = parse(ex.getRequestURI().getRawQuery());
        p.putAll(parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));

        switch (method) {
            case "GET" -> send(ex, 200, "application/json", listJson());
            case "POST" -> {
                Student s = build(0, p);
                if (s == null) { send(ex, 400, "application/json", error(lastError)); return; }
                s = new Student(nextId++, s.name(), s.email(), s.course(), s.year(), s.cgpa());
                students.put(s.id(), s);
                save();
                send(ex, 201, "application/json", toJson(s));
            }
            case "PUT" -> {
                int id = toInt(p.get("id"), -1);
                if (!students.containsKey(id)) { send(ex, 404, "application/json", error("Student not found")); return; }
                Student s = build(id, p);
                if (s == null) { send(ex, 400, "application/json", error(lastError)); return; }
                students.put(id, s);
                save();
                send(ex, 200, "application/json", toJson(s));
            }
            case "DELETE" -> {
                int id = toInt(p.get("id"), -1);
                if (students.remove(id) == null) { send(ex, 404, "application/json", error("Student not found")); return; }
                save();
                send(ex, 200, "application/json", "{\"ok\":true}");
            }
            default -> send(ex, 405, "application/json", error("Method not allowed"));
        }
    }

    
    static String lastError = "";

    static Student build(int id, Map<String, String> p) {
        String name = clean(p.get("name"));
        String email = clean(p.get("email"));
        String course = clean(p.get("course"));
        int year = toInt(p.get("year"), -1);
        double cgpa;
        try { cgpa = Double.parseDouble(p.getOrDefault("cgpa", "")); } catch (NumberFormatException e) { cgpa = -1; }

        if (name.isEmpty()) { lastError = "Name is required"; return null; }
        if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) { lastError = "Enter a valid email"; return null; }
        if (course.isEmpty()) { lastError = "Course is required"; return null; }
        if (year < 1 || year > 6) { lastError = "Year must be between 1 and 6"; return null; }
        if (cgpa < 0 || cgpa > 10) { lastError = "CGPA must be between 0 and 10"; return null; }
        return new Student(id, name, email, course, year, cgpa);
    }

    static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ").trim();
    }

    static int toInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }

    
    static void load() {
        try {
            if (!Files.exists(DB_FILE)) return;
            for (String line : Files.readAllLines(DB_FILE, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                if (f.length < 6) continue;
                Student s = new Student(Integer.parseInt(f[0]), f[1], f[2], f[3],
                        Integer.parseInt(f[4]), Double.parseDouble(f[5]));
                students.put(s.id(), s);
                nextId = Math.max(nextId, s.id() + 1);
            }
            System.out.println("Loaded " + students.size() + " students from " + DB_FILE.toAbsolutePath());
        } catch (Exception e) {
            System.out.println("Could not read " + DB_FILE + ": " + e.getMessage());
        }
    }

    static void save() throws IOException {
        List<String> lines = students.values().stream()
                .map(s -> String.join("\t", String.valueOf(s.id()), s.name(), s.email(), s.course(),
                        String.valueOf(s.year()), String.valueOf(s.cgpa())))
                .collect(Collectors.toList());
        Files.write(DB_FILE, lines, StandardCharsets.UTF_8);
    }

    
    static Map<String, String> parse(String body) {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isBlank()) return map;
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String v = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            map.put(k, v);
        }
        return map;
    }

    static String listJson() {
        return students.values().stream().map(StudentApp::toJson).collect(Collectors.joining(",", "[", "]"));
    }

    static String toJson(Student s) {
        return "{\"id\":" + s.id()
                + ",\"name\":\"" + esc(s.name()) + "\""
                + ",\"email\":\"" + esc(s.email()) + "\""
                + ",\"course\":\"" + esc(s.course()) + "\""
                + ",\"year\":" + s.year()
                + ",\"cgpa\":" + s.cgpa() + "}";
    }

    static String error(String msg) { return "{\"error\":\"" + esc(msg) + "\"}"; }

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                default -> { if (c < 0x20) b.append(' '); else b.append(c); }
            }
        }
        return b.toString();
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    
    static final String PAGE = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Student Management System</title>
<style>
  :root { --bg:#f4f6fb; --card:#fff; --primary:#4f46e5; --primary-d:#4338ca; --text:#1f2937; --muted:#6b7280; --line:#e5e7eb; --danger:#dc2626; }
  * { box-sizing: border-box; }
  body { margin:0; font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; background:var(--bg); color:var(--text); }
  header { background:var(--primary); color:#fff; padding:18px 24px; font-size:20px; font-weight:600; }
  main { max-width:1100px; margin:24px auto; padding:0 16px; display:grid; gap:20px; }
  .card { background:var(--card); border-radius:12px; padding:20px; box-shadow:0 1px 3px rgba(0,0,0,.08); }
  .stats { display:grid; grid-template-columns:repeat(auto-fit,minmax(160px,1fr)); gap:12px; }
  .stat { background:var(--card); border-radius:12px; padding:16px; box-shadow:0 1px 3px rgba(0,0,0,.08); }
  .stat b { display:block; font-size:26px; color:var(--primary); }
  .stat span { color:var(--muted); font-size:13px; }
  h2 { margin:0 0 14px; font-size:17px; }
  form { display:grid; grid-template-columns:repeat(auto-fit,minmax(180px,1fr)); gap:12px; align-items:end; }
  label { font-size:12px; color:var(--muted); display:block; margin-bottom:4px; }
  input { width:100%; padding:9px 10px; border:1px solid var(--line); border-radius:8px; font-size:14px; }
  input:focus { outline:2px solid var(--primary); border-color:transparent; }
  button { padding:9px 16px; border:0; border-radius:8px; cursor:pointer; font-size:14px; background:var(--primary); color:#fff; }
  button:hover { background:var(--primary-d); }
  button.ghost { background:#e5e7eb; color:var(--text); }
  button.danger { background:var(--danger); }
  button.small { padding:5px 10px; font-size:12px; }
  .toolbar { display:flex; justify-content:space-between; gap:12px; margin-bottom:12px; flex-wrap:wrap; }
  .toolbar input { max-width:280px; }
  table { width:100%; border-collapse:collapse; font-size:14px; }
  th, td { text-align:left; padding:10px 8px; border-bottom:1px solid var(--line); }
  th { color:var(--muted); font-weight:600; font-size:12px; text-transform:uppercase; cursor:pointer; user-select:none; }
  td.actions { white-space:nowrap; display:flex; gap:6px; }
  .empty { text-align:center; color:var(--muted); padding:24px; }
  #msg { min-height:20px; font-size:13px; margin-top:10px; }
  #msg.err { color:var(--danger); } #msg.ok { color:#059669; }
  .table-wrap { overflow-x:auto; }
</style>
</head>
<body>
<header>🎓 Student Management System</header>
<main>
  <div class="stats">
    <div class="stat"><b id="sTotal">0</b><span>Total students</span></div>
    <div class="stat"><b id="sAvg">0.00</b><span>Average CGPA</span></div>
    <div class="stat"><b id="sTop">-</b><span>Top student</span></div>
    <div class="stat"><b id="sCourses">0</b><span>Courses</span></div>
  </div>

  <div class="card">
    <h2 id="formTitle">Add a student</h2>
    <form id="form">
      <input type="hidden" id="id">
      <div><label>Full name</label><input id="name" required></div>
      <div><label>Email</label><input id="email" type="email" required></div>
      <div><label>Course</label><input id="course" placeholder="e.g. B.Tech CSE" required></div>
      <div><label>Year (1-6)</label><input id="year" type="number" min="1" max="6" required></div>
      <div><label>CGPA (0-10)</label><input id="cgpa" type="number" min="0" max="10" step="0.01" required></div>
      <div style="display:flex; gap:8px;">
        <button type="submit" id="saveBtn">Add student</button>
        <button type="button" class="ghost" id="cancelBtn" style="display:none">Cancel</button>
      </div>
    </form>
    <div id="msg"></div>
  </div>

  <div class="card">
    <div class="toolbar">
      <h2 style="margin:0">All students</h2>
      <input id="search" placeholder="Search by name, email or course...">
    </div>
    <div class="table-wrap">
      <table>
        <thead><tr>
          <th data-k="id">ID</th><th data-k="name">Name</th><th data-k="email">Email</th>
          <th data-k="course">Course</th><th data-k="year">Year</th><th data-k="cgpa">CGPA</th><th></th>
        </tr></thead>
        <tbody id="rows"></tbody>
      </table>
    </div>
  </div>
</main>

<script>
  let all = [];
  let sortKey = 'id', sortDir = 1;
  const $ = id => document.getElementById(id);

  function esc(s) { return String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }

  function say(text, ok) { const m = $('msg'); m.textContent = text; m.className = ok ? 'ok' : 'err'; if (ok) setTimeout(() => m.textContent = '', 2500); }

  async function load() {
    const res = await fetch('/api/students');
    all = await res.json();
    render();
  }

  function render() {
    const q = $('search').value.toLowerCase();
    const list = all
      .filter(s => (s.name + ' ' + s.email + ' ' + s.course).toLowerCase().includes(q))
      .sort((a, b) => (a[sortKey] > b[sortKey] ? 1 : a[sortKey] < b[sortKey] ? -1 : 0) * sortDir);

    $('rows').innerHTML = list.length ? list.map(s => `
      <tr>
        <td>${s.id}</td><td>${esc(s.name)}</td><td>${esc(s.email)}</td>
        <td>${esc(s.course)}</td><td>${s.year}</td><td>${s.cgpa.toFixed(2)}</td>
        <td class="actions">
          <button class="small" onclick="edit(${s.id})">Edit</button>
          <button class="small danger" onclick="removeStudent(${s.id})">Delete</button>
        </td>
      </tr>`).join('') : '<tr><td colspan="7" class="empty">No students found</td></tr>';

    $('sTotal').textContent = all.length;
    $('sAvg').textContent = all.length ? (all.reduce((t, s) => t + s.cgpa, 0) / all.length).toFixed(2) : '0.00';
    const top = all.reduce((b, s) => (!b || s.cgpa > b.cgpa ? s : b), null);
    $('sTop').textContent = top ? top.name.split(' ')[0] : '-';
    $('sCourses').textContent = new Set(all.map(s => s.course.toLowerCase())).size;
  }

  function resetForm() {
    $('form').reset(); $('id').value = '';
    $('formTitle').textContent = 'Add a student';
    $('saveBtn').textContent = 'Add student';
    $('cancelBtn').style.display = 'none';
  }

  function edit(id) {
    const s = all.find(x => x.id === id);
    $('id').value = s.id; $('name').value = s.name; $('email').value = s.email;
    $('course').value = s.course; $('year').value = s.year; $('cgpa').value = s.cgpa;
    $('formTitle').textContent = 'Edit student #' + s.id;
    $('saveBtn').textContent = 'Save changes';
    $('cancelBtn').style.display = '';
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  async function removeStudent(id) {
    if (!confirm('Delete this student?')) return;
    await fetch('/api/students?id=' + id, { method: 'DELETE' });
    say('Student deleted', true);
    load();
  }

  $('form').addEventListener('submit', async e => {
    e.preventDefault();
    const editing = $('id').value !== '';
    const body = new URLSearchParams({
      id: $('id').value, name: $('name').value, email: $('email').value,
      course: $('course').value, year: $('year').value, cgpa: $('cgpa').value
    });
    const res = await fetch('/api/students', { method: editing ? 'PUT' : 'POST', body });
    const data = await res.json();
    if (!res.ok) return say(data.error, false);
    say(editing ? 'Student updated' : 'Student added', true);
    resetForm(); load();
  });

  $('cancelBtn').onclick = resetForm;
  $('search').oninput = render;
  document.querySelectorAll('th[data-k]').forEach(th => th.onclick = () => {
    const k = th.dataset.k;
    sortDir = (sortKey === k) ? -sortDir : 1; sortKey = k; render();
  });

  load();
</script>
</body>
</html>
""";
}