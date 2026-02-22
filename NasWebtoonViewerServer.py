from flask import Flask, jsonify, send_from_directory, request, send_file, Response, render_template_string, stream_with_context, redirect
import os, urllib.parse, unicodedata, logging, time, zipfile, io, sys, sqlite3, json, threading, hashlib, queue
from concurrent.futures import ThreadPoolExecutor

# [로그 설정]
logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s [%(name)s] %(message)s', stream=sys.stdout)
logger = logging.getLogger("NasWebtoonServer")

app = Flask(__name__)

# --- 설정 ---
BASE_PATH = "/volume2/video/GDS3/GDRIVE/READING/웹툰"
METADATA_DB_PATH = '/volume2/video/NasWebtoonViewer.db'
ALLOWED_CATEGORIES = ["가", "나", "다", "라", "마", "바", "사", "아", "자", "차", "카", "타", "파", "하", "기타", "0Z", "A-Z"]

# --- 전역 상태 관리 ---
scan_status = {
    "is_running": False,
    "current_item": "대기 중",
    "total": 0,
    "processed": 0,
    "success": 0,
    "failed": 0,
    "logs": []
}
log_queue = queue.Queue()
db_queue = queue.Queue()
scanning_pool = ThreadPoolExecutor(max_workers=15)

def add_web_log(msg, type="INFO"):
    prefix = "✅ [SUCCESS]" if type=="SUCCESS" else "❌ [FAILED]" if type=="ERROR" else "🚢 [INIT]" if type=="INIT" else "📝 [UPDATE]"
    if "FINISH" in msg: prefix = "✨ [FINISH]"
    formatted_msg = f"{prefix} {msg}"
    scan_status["logs"].append(formatted_msg)
    if len(scan_status["logs"]) > 200: scan_status["logs"].pop(0)
    log_queue.put(formatted_msg)

def normalize_nfc(s):
    if s is None: return ""
    return unicodedata.normalize('NFC', str(s))

def get_path_hash(path):
    p = os.path.abspath(path).replace(os.sep, '/')
    return hashlib.md5(normalize_nfc(p).encode('utf-8')).hexdigest()

def get_depth(rel_path):
    if not rel_path or rel_path == "." or rel_path == "": return 0
    return len(rel_path.strip('/').split('/'))

# --- DB 엔진 ---
def db_writer_worker():
    conn = sqlite3.connect(METADATA_DB_PATH, timeout=60)
    conn.execute('PRAGMA journal_mode=WAL;')
    conn.execute('PRAGMA synchronous=NORMAL;')
    while True:
        items = db_queue.get()
        if items is None: break
        try:
            conn.executemany('INSERT OR REPLACE INTO entries VALUES (?,?,?,?,?,?,?,?,?,?,?)', items)
            conn.commit()
        except Exception as e:
            logger.error("DB Write Error: " + str(e))
        finally:
            db_queue.task_done()

threading.Thread(target=db_writer_worker, daemon=True).start()

def init_db():
    conn = sqlite3.connect(METADATA_DB_PATH)
    with conn:
        conn.execute('''CREATE TABLE IF NOT EXISTS entries (
            path_hash TEXT PRIMARY KEY, parent_hash TEXT, abs_path TEXT,
            rel_path TEXT, name TEXT, is_dir INTEGER,
            poster_url TEXT, title TEXT, depth INTEGER, last_scanned REAL,
            metadata TEXT
        )''')
        conn.execute('CREATE INDEX IF NOT EXISTS idx_parent ON entries(parent_hash)')
        conn.execute('CREATE INDEX IF NOT EXISTS idx_title ON entries(title)')
    conn.close()

# --- 정보 추출 엔진 ---
def is_comic_file(name):
    return name.lower().endswith(('.zip', '.cbz', '.rar', '.cbr', '.pdf'))

def is_image_file(name):
    return name.lower().endswith(('.jpg', '.jpeg', '.png', '.webp', '.gif'))

def find_first_image_recursive(path, depth_limit=3):
    if depth_limit <= 0: return None
    try:
        with os.scandir(path) as it:
            ents = sorted(list(it), key=lambda x: x.name)
            for e in ents:
                if is_image_file(e.name):
                    return os.path.relpath(e.path, BASE_PATH).replace(os.sep, '/')
            for e in ents:
                if e.is_dir():
                    res = find_first_image_recursive(e.path, depth_limit - 1)
                    if res: return res
            for e in ents:
                if is_comic_file(e.name):
                    return "zip_thumb://" + os.path.relpath(e.path, BASE_PATH).replace(os.sep, '/')
    except: pass
    return None

def get_comic_info(abs_path, rel_path):
    title = normalize_nfc(os.path.basename(abs_path))
    poster = None
    meta_dict = {"summary": "줄거리 정보가 없습니다.", "writers": [], "genres": [], "status": "Unknown", "publisher": ""}

    if os.path.isdir(abs_path):
        json_path = os.path.join(abs_path, "series.json")
        poster_found_source = "None"
        json_read_successful = False

        if os.path.exists(json_path):
            try:
                with open(json_path, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                    json_read_successful = True
                    if data:
                        # ** 결정적 수정: 외부 URL과 로컬 파일을 구분하여 처리 **

                        # 1. content 객체 확인
                        content_data = data.get('content', {})

                        # 2. web 객체 확인
                        web_data = data.get('web', {})

                        # 3. 최상위 객체 확인
                        top_level_data = data

                        # 포스터 우선순위: web.main_img -> web.share_img -> content.backgroundImage -> 최상위 image/cover 등
                        search_order = [
                            (web_data, ['main_img', 'share_img']),
                            (content_data, ['backgroundImage', 'featuredCharacterImageA', 'featuredCharacterImageB', 'titleImageA', 'titleImageB']),
                            (top_level_data, ['image', 'thumbnail', 'cover', 'poster'])
                        ]

                        for source_obj, keys in search_order:
                            for key in keys:
                                if key in source_obj and source_obj[key]:
                                    poster_candidate = source_obj[key].strip()
                                    if poster_candidate.startswith('http'):
                                        poster = poster_candidate
                                        poster_found_source = f"series.json key: {key} (Absolute URL)"
                                    else:
                                        poster = os.path.join(rel_path, poster_candidate).replace(os.sep, '/')
                                        poster_found_source = f"series.json key: {key} (Local File)"
                                    break # 키를 찾으면 루프 종료
                            if poster: break # 포스터를 찾았으면 전체 루프 종료

                        # 메타데이터 채우기
                        if 'title' in content_data: title = normalize_nfc(content_data['title'])
                        meta_dict['summary'] = content_data.get('synopsis', meta_dict['summary'])
                        if 'authors' in content_data:
                            meta_dict['writers'] = [author['name'] for author in content_data['authors'] if author.get('type') == 'AUTHOR']
                            publishers = [author['name'] for author in content_data['authors'] if author.get('type') == 'PUBLISHER']
                            if publishers: meta_dict['publisher'] = publishers[0]
                        meta_dict['genres'] = content_data.get('seoKeywords', [])
                        meta_dict['status'] = content_data.get('status', 'Unknown')

            except Exception as e:
                logger.error(f"Error reading series.json in {rel_path}: {e}")
                add_web_log(f"[ERROR] JSON Read Failed for {rel_path}: {e}", "ERROR")

        if not poster:
            if json_read_successful:
                 poster_found_source = "JSON Read OK, but no valid poster file found/set"
            poster = find_first_image_recursive(abs_path)
            if poster:
                 poster_found_source = "find_first_image_recursive"
            else:
                 poster_found_source = "None (Fallback to Zip)"

        add_web_log(f"[{rel_path}] Poster Source: {poster_found_source}. Final Poster: {poster}", "DEBUG")

    else:
        poster = "zip_thumb://" + rel_path
        title = os.path.splitext(title)[0]

    return title, poster, json.dumps(meta_dict, ensure_ascii=False)

def scan_task(abs_path):
    try:
        rel = os.path.relpath(abs_path, BASE_PATH).replace(os.sep, '/')
        if rel == ".": rel = ""
        depth = get_depth(rel)
        scan_status["current_item"] = os.path.basename(abs_path)

        title, poster, meta = get_comic_info(abs_path, rel)
        p_hash = get_path_hash(os.path.dirname(abs_path))
        item = (get_path_hash(abs_path), p_hash, abs_path, rel, os.path.basename(abs_path), 1 if os.path.isdir(abs_path) else 0, poster, title, depth, time.time(), meta)
        db_queue.put([item])

        scan_status["processed"] += 1
        scan_status["success"] += 1

        if depth == 2:
            p_info = "📷" if poster else "🌑"
            add_web_log(f"[{p_info}] '{title}' ({rel}) updated", "UPDATE")

        if os.path.isdir(abs_path) and depth < 2:
            with os.scandir(abs_path) as it:
                for e in it:
                    if e.is_dir():
                        scan_status["total"] += 1
                        scanning_pool.submit(scan_task, e.path)
        elif os.path.isdir(abs_path) and depth == 2:
            ep_items = []
            with os.scandir(abs_path) as it:
                for e in it:
                    if is_comic_file(e.name):
                        e_rel = os.path.relpath(e.path, BASE_PATH).replace(os.sep, '/')
                        e_title = os.path.splitext(e.name)[0]
                        e_poster = "zip_thumb://" + e_rel
                        ep_items.append((get_path_hash(e.path), get_path_hash(abs_path), e.path, e_rel, e.name, 0, e_poster, normalize_nfc(e_title), depth + 1, time.time(), "{}"))
            if ep_items:
                db_queue.put(ep_items)

    except Exception as e:
        scan_status["failed"] += 1
        add_web_log(f"Error scanning {abs_path}: {e}", "ERROR")

    if scan_status["processed"] >= scan_status["total"] and scan_status["total"] > 0:
        scan_status["is_running"] = False
        add_web_log("All update tasks completed!", "SUCCESS")

def start_full_scan():
    scan_status["is_running"] = True
    scan_status["processed"] = 0
    scan_status["success"] = 0
    scan_status["failed"] = 0
    scan_status["total"] = 0
    scan_status["logs"] = []

    add_web_log("Checking consonant categories in BASE_PATH...", "INIT")

    targets = []
    try:
        with os.scandir(BASE_PATH) as it:
            for entry in it:
                if entry.is_dir():
                    normalized_name = normalize_nfc(entry.name)
                    if normalized_name in ALLOWED_CATEGORIES:
                        targets.append(entry.path)
                        add_web_log(f"Category found: {normalized_name}", "INIT")
    except Exception as e:
        add_web_log(f"Failed to access BASE_PATH: {e}", "ERROR")
        scan_status["is_running"] = False
        return

    scan_status["total"] = len(targets)
    if not targets:
        add_web_log("No valid categories found. Check BASE_PATH or folder names.", "ERROR")
        scan_status["is_running"] = False
        return

    for t in targets:
        scanning_pool.submit(scan_task, t)

# --- Web UI ---
@app.route('/')
def index():
    html = """
    <!DOCTYPE html>
    <html>
    <head>
        <title>NasWebtoon - 메타데이터 심층 업데이트</title>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
            body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background: #f4f7f6; margin: 0; padding: 40px; color: #333; }
            .container { max-width: 900px; margin: 0 auto; background: #fff; padding: 30px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
            h1 { font-size: 24px; margin-bottom: 20px; display: flex; align-items: center; }
            h1 span { margin-right: 10px; }
            p.desc { color: #666; font-size: 14px; margin-bottom: 30px; line-height: 1.6; }
            .button-group { margin-bottom: 30px; text-align: center; }
            button { background: #6c757d; color: #fff; border: none; padding: 12px 40px; border-radius: 4px; cursor: pointer; font-weight: bold; font-size: 16px; transition: 0.2s; }
            button:hover { background: #5a6268; }
            button:disabled { background: #ccc; cursor: not-allowed; }
            .status-info { margin-bottom: 10px; font-weight: bold; font-size: 18px; color: #333; }
            .progress-container { background: #eee; height: 25px; border-radius: 12px; overflow: hidden; margin-bottom: 10px; position: relative; }
            .progress-bar { background: #4dabf7; width: 0%; height: 100%; transition: width 0.3s ease-in-out; }
            .progress-stats { display: flex; justify-content: space-between; font-size: 13px; color: #666; margin-bottom: 20px; }
            .success { color: #28a745; font-weight: bold; } .failed { color: #dc3545; font-weight: bold; }
            .log-window { background: #1e1e1e; color: #d4d4d4; padding: 15px; border-radius: 4px; height: 450px; overflow-y: auto; font-family: 'Consolas', monospace; font-size: 13px; line-height: 1.5; border: 1px solid #333; }
        </style>
    </head>
    <body>
        <div class="container">
            <h1><span>🛠️</span> 웹툰 메타데이터 심층 업데이트</h1>
            <p class="desc">라이브러리 전체를 정밀 스캔하여 포스터를 추출합니다.<br>하위 폴더 깊숙이 숨겨진 이미지까지 자동으로 찾아내어 DB를 갱신합니다.</p>

            <div class="button-group">
                <button id="scanBtn" onclick="startUpdate()">전체 업데이트 시작</button>
            </div>

            <div class="status-info" id="currentItem">대기 중...</div>
            <div class="progress-container">
                <div class="progress-bar" id="progressBar"></div>
            </div>
            <div class="progress-stats">
                <span id="progressText">0 / 0</span>
                <span id="percentText">0%</span>
            </div>
            <div class="progress-stats">
                <span class="success">성공: <span id="successCount">0</span></span>
                <span class="failed">실패: <span id="failedCount">0</span></span>
            </div>

            <div class="log-window" id="logWindow"></div>
        </div>

        <script>
            let isRunning = false;
            function startUpdate() {
                if(isRunning) return;
                document.getElementById('scanBtn').disabled = true;
                fetch('/start_scan');
                document.getElementById('logWindow').innerHTML = '';
            }

            const evtSource = new EventSource("/stream_logs");
            evtSource.onmessage = function(event) {
                const data = JSON.parse(event.data);
                isRunning = (data.processed < data.total);
                document.getElementById('scanBtn').disabled = (data.total > 0 && isRunning);

                document.getElementById('currentItem').innerText = "처리 중: " + data.current_item;
                document.getElementById('progressText').innerText = data.processed + " / " + data.total;
                const percent = data.total > 0 ? Math.round((data.processed / data.total) * 100) : 0;
                document.getElementById('percentText').innerText = percent + "%";
                document.getElementById('progressBar').style.width = percent + "%";
                document.getElementById('successCount').innerText = data.success;
                document.getElementById('failedCount').innerText = data.failed;

                if (data.new_log) {
                    const logWin = document.getElementById('logWindow');
                    const div = document.createElement('div');
                    div.innerText = data.new_log;
                    if (data.new_log.includes('SUCCESS') || data.new_log.includes('FINISH')) div.style.color = '#75beff';
                    if (data.new_log.includes('FAILED')) div.style.color = '#f48771';
                    if (data.new_log.includes('🌑')) div.style.color = '#ffcc66';
                    logWin.appendChild(div);
                    logWin.scrollTop = logWin.scrollHeight;
                }
            };
        </script>
    </body>
    </html>
    """
    return render_template_string(html)

@app.route('/start_scan')
def start_scan_api():
    threading.Thread(target=start_full_scan).start()
    return jsonify({"status": "started"})

@app.route('/stream_logs')
def stream_logs():
    def generate():
        while True:
            new_log = None
            try: new_log = log_queue.get(timeout=0.5)
            except: pass

            data = {
                "current_item": scan_status["current_item"],
                "total": scan_status["total"],
                "processed": scan_status["processed"],
                "success": scan_status["success"],
                "failed": scan_status["failed"],
                "new_log": new_log
            }
            yield f"data: {json.dumps(data)}\n\n"
    return Response(stream_with_context(generate()), mimetype='text/event-stream')

# --- 앱 API ---
@app.route('/scan')
def scan_comics():
    path = request.args.get('path', '')
    page = request.args.get('page', 1, type=int)
    psize = request.args.get('page_size', 50, type=int)
    conn = sqlite3.connect(METADATA_DB_PATH); conn.row_factory = sqlite3.Row
    if not path:
        rows = conn.execute("SELECT * FROM entries WHERE depth = 2 ORDER BY title LIMIT ? OFFSET ?", (psize, (page-1)*psize)).fetchall()
        total = conn.execute("SELECT COUNT(*) FROM entries WHERE depth = 2").fetchone()[0]
    else:
        abs_p = os.path.abspath(os.path.join(BASE_PATH, path)).replace(os.sep, '/')
        phash = get_path_hash(abs_p)
        rows = conn.execute("SELECT * FROM entries WHERE parent_hash = ? ORDER BY name LIMIT ? OFFSET ?", (phash, psize, (page-1)*psize)).fetchall()
        total = conn.execute("SELECT COUNT(*) FROM entries WHERE parent_hash = ?", (phash,)).fetchone()[0]

    items = []
    for r in rows:
        meta = json.loads(r['metadata'] or '{}')
        meta['poster_url'] = r['poster_url']
        items.append({
            'name': r['title'] or r['name'],
            'isDirectory': bool(r['is_dir']),
            'path': r['rel_path'],
            'metadata': meta
        })
    conn.close()
    return jsonify({'total_items': total, 'page': page, 'page_size': psize, 'items': items})

@app.route('/download')
def download():
    p = request.args.get('path', '')
    if not p: return "Path required", 400
    if p.startswith("http"): return redirect(p)

    if p.startswith("zip_thumb://"):
        azp = os.path.join(BASE_PATH, p[12:])
        if os.path.isdir(azp):
            try:
                with os.scandir(azp) as it:
                    for e in it:
                        if is_comic_file(e.name): azp = e.path; break
                        if is_image_file(e.name): return send_from_directory(azp, e.name)
            except: pass
        try:
            with zipfile.ZipFile(azp, 'r') as z:
                imgs = sorted([n for n in z.namelist() if is_image_file(n)])
                if imgs:
                    with z.open(imgs[0]) as f: return send_file(io.BytesIO(f.read()), mimetype='image/jpeg')
        except: pass
        return "No Image in Zip", 404

    target_path = os.path.join(BASE_PATH, p)
    if os.path.exists(target_path):
        return send_from_directory(os.path.dirname(target_path), os.path.basename(target_path))

    logger.error(f"File Not Found for Path: {p}") # 파일이 없을 경우 서버 로그 남김
    return "File Not Found", 404

@app.route('/zip_entries')
def zip_entries():
    path = urllib.parse.unquote(request.args.get('path', ''))
    abs_p = os.path.join(BASE_PATH, path)
    if os.path.isdir(abs_p):
        try:
            with os.scandir(abs_p) as it: return jsonify(sorted([e.name for e in it if is_image_file(e.name)]))
        except: return jsonify([])
    try:
        with zipfile.ZipFile(abs_p, 'r') as z: return jsonify(sorted([n for n in z.namelist() if is_image_file(n)]))
    except: return jsonify([])

@app.route('/download_zip_entry')
def download_zip_entry():
    path = urllib.parse.unquote(request.args.get('path', ''))
    entry = urllib.parse.unquote(request.args.get('entry', ''))
    abs_p = os.path.join(BASE_PATH, path)
    if os.path.isdir(abs_p): return send_from_directory(abs_p, entry)
    try:
        with zipfile.ZipFile(abs_p, 'r') as z:
            with z.open(entry) as f: return send_file(io.BytesIO(f.read()), mimetype='image/jpeg')
    except: return "Error", 500

@app.route('/search')
def search_comics():
    query = request.args.get('query', '')
    page = request.args.get('page', 1, type=int)
    psize = request.args.get('page_size', 50, type=int)
    if not query: return jsonify({'total_items': 0, 'page': page, 'page_size': psize, 'items': []})
    conn = sqlite3.connect(METADATA_DB_PATH); conn.row_factory = sqlite3.Row
    q = f"%{query}%"
    rows = conn.execute("SELECT * FROM entries WHERE (title LIKE ? OR name LIKE ?) AND depth >= 2 ORDER BY title LIMIT ? OFFSET ?", (q, q, psize, (page-1)*psize)).fetchall()
    total = conn.execute("SELECT COUNT(*) FROM entries WHERE (title LIKE ? OR name LIKE ?) AND depth >= 2", (q, q)).fetchone()[0]
    items = []
    for r in rows:
        meta = json.loads(r['metadata'] or '{}')
        meta['poster_url'] = r['poster_url']
        items.append({
            'name': r['title'] or r['name'],
            'isDirectory': bool(r['is_dir']),
            'path': r['rel_path'],
            'metadata': meta
        })
    conn.close()
    return jsonify({'total_items': total, 'page': page, 'page_size': psize, 'items': items})

if __name__ == '__main__':
    init_db()
    app.run(host='0.0.0.0', port=5556, threaded=True)
