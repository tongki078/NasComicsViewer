from flask import Flask, jsonify, send_from_directory, request, send_file, Response, render_template_string, stream_with_context, redirect
import os, urllib.parse, unicodedata, logging, time, zipfile, io, sys, sqlite3, json, threading, hashlib, queue, urllib.request, yaml
from concurrent.futures import ThreadPoolExecutor

# [로그 설정]
logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s [%(name)s] %(message)s', stream=sys.stdout)
logger = logging.getLogger("NasWebtoonServer")

app = Flask(__name__)

# --- 설정 ---
BASE_PATH = "/volume2/video/GDS3/GDRIVE/READING"
METADATA_DB_PATH = '/volume2/video/NasWebtoonViewer.db'
WEBTOON_CATEGORIES = ["가", "나", "다", "라", "마", "바", "사", "아", "자", "차", "카", "타", "파", "하", "기타", "0Z", "A-Z"]

# 필터링할 폴더 목록
EXCLUDED_FOLDERS = ["INCOMING", "Incoming", "incoming"]

THUMB_CACHE_DIR = os.path.join(os.path.dirname(METADATA_DB_PATH), "webtoon_cache")
if not os.path.exists(THUMB_CACHE_DIR): os.makedirs(THUMB_CACHE_DIR)

# --- 전역 상태 관리 ---
scan_status = {
    "is_running": False,
    "current_type": "None",
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
    formatted_msg = f"{prefix} {msg}"
    scan_status["logs"].append(formatted_msg)
    if len(scan_status["logs"]) > 200: scan_status["logs"].pop(0)
    log_queue.put(formatted_msg)

def normalize_nfc(s):
    if s is None: return ""
    return unicodedata.normalize('NFC', str(s))

def is_excluded(name):
    return normalize_nfc(name) in [normalize_nfc(f) for f in EXCLUDED_FOLDERS]

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
                if e.is_dir() and not is_excluded(e.name):
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
        kavita_path = os.path.join(abs_path, "kavita.yaml")
        json_path = os.path.join(abs_path, "series.json")

        if os.path.exists(kavita_path):
            try:
                with open(kavita_path, 'r', encoding='utf-8') as f:
                    data = yaml.safe_load(f)
                    m = data.get('meta', {}) or data.get('search', {})
                    if 'title' in m: title = normalize_nfc(m['title'])
                    meta_dict['summary'] = m.get('summary', meta_dict['summary'])
                    meta_dict['writers'] = m.get('writers', [])
                    meta_dict['genres'] = m.get('genres', [])
                    for key in ['backgroundImage', 'image', 'thumbnail', 'cover', 'poster']:
                        if key in m and m[key]:
                            val = str(m[key]).strip()
                            if val.startswith(('http://', 'https://')): poster = val; break
                            check_path = os.path.join(abs_path, val)
                            if os.path.exists(check_path):
                                poster = os.path.join(rel_path, val).replace(os.sep, '/'); break
            except Exception as e: logger.error(f"Error reading kavita.yaml: {e}")

        if not poster and os.path.exists(json_path):
            try:
                with open(json_path, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                    for key in ['backgroundImage', 'image', 'thumbnail', 'cover', 'poster']:
                        if key in data and data[key]:
                            val = data[key].strip()
                            if val.startswith(('http://', 'https://')): poster = val; break
                            check_path = os.path.join(abs_path, val)
                            if os.path.exists(check_path):
                                poster = os.path.join(rel_path, val).replace(os.sep, '/'); break
                    if 'title' in data: title = normalize_nfc(data['title'])
                    meta_dict['summary'] = data.get('description', data.get('summary', meta_dict['summary']))
            except: pass

        if not poster:
            cover_path = os.path.join(abs_path, "cover.png")
            if os.path.exists(cover_path): poster = os.path.join(rel_path, "cover.png").replace(os.sep, '/')

        if not poster: poster = find_first_image_recursive(abs_path)
    else:
        poster = "zip_thumb://" + rel_path
        title = os.path.splitext(title)[0]

    if poster and not poster.startswith("http"):
        if poster.startswith("zip_thumb://"):
            poster = "zip_thumb://" + urllib.parse.quote(poster[12:])
        else:
            poster = urllib.parse.quote(poster)

    meta_dict['poster_url'] = poster
    return title, poster, json.dumps(meta_dict, ensure_ascii=False)

def scan_task(abs_path, series_depth):
    try:
        if is_excluded(os.path.basename(abs_path)): return

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

        if depth == series_depth:
            add_web_log(f"'{title}' updated", "UPDATE")

        if os.path.isdir(abs_path) and depth < series_depth:
            with os.scandir(abs_path) as it:
                for e in it:
                    if e.is_dir() and not is_excluded(e.name):
                        scan_status["total"] += 1
                        scanning_pool.submit(scan_task, e.path, series_depth)
        elif os.path.isdir(abs_path) and depth == series_depth:
            ep_items = []
            with os.scandir(abs_path) as it:
                for e in it:
                    if (is_comic_file(e.name) or e.is_dir()) and not is_excluded(e.name):
                        e_rel = os.path.relpath(e.path, BASE_PATH).replace(os.sep, '/')
                        e_title = os.path.splitext(e.name)[0]
                        if is_comic_file(e.name): e_poster = "zip_thumb://" + urllib.parse.quote(e_rel)
                        elif e.is_dir():
                            found_img = find_first_image_recursive(e.path)
                            if found_img:
                                e_poster = urllib.parse.quote(found_img)
                            else:
                                e_poster = poster
                        else: e_poster = poster
                        ep_items.append((get_path_hash(e.path), get_path_hash(abs_path), e.path, e_rel, e.name, 1 if e.is_dir() else 0, e_poster, normalize_nfc(e_title), depth + 1, time.time(), "{}"))
            if ep_items: db_queue.put(ep_items)
    except Exception as e:
        scan_status["failed"] += 1
        add_web_log(f"Error scanning {abs_path}: {e}", "ERROR")

    if scan_status["processed"] >= scan_status["total"] and scan_status["total"] > 0:
        scan_status["is_running"] = False
        add_web_log(f"{scan_status['current_type']} Update Completed!", "SUCCESS")

def start_full_scan(target_type):
    scan_status["is_running"] = True
    scan_status["current_type"] = target_type
    scan_status["processed"] = 0
    scan_status["success"] = 0
    scan_status["failed"] = 0
    scan_status["total"] = 0
    scan_status["logs"] = []

    if target_type == "WEBTOON":
        webtoon_root = os.path.join(BASE_PATH, "웹툰")
        if os.path.exists(webtoon_root):
            add_web_log("Initializing Webtoon Scan...", "INIT")
            with os.scandir(webtoon_root) as it:
                for entry in it:
                    if entry.is_dir() and normalize_nfc(entry.name) in WEBTOON_CATEGORIES and not is_excluded(entry.name):
                        scan_status["total"] += 1
                        scanning_pool.submit(scan_task, entry.path, 3)
    elif target_type == "MAGAZINE":
        magazine_root = os.path.join(BASE_PATH, "잡지")
        if os.path.exists(magazine_root):
            add_web_log("Initializing Magazine Scan...", "INIT")
            scan_status["total"] += 1
            scanning_pool.submit(scan_task, magazine_root, 2)
    elif target_type == "PHOTO_BOOK":
        photobook_root = os.path.join(BASE_PATH, "화보")
        if os.path.exists(photobook_root):
            add_web_log("Initializing Photo Book Scan...", "INIT")
            with os.scandir(photobook_root) as it:
                for entry in it:
                    if entry.is_dir() and not is_excluded(entry.name):
                        scan_status["total"] += 1
                        scanning_pool.submit(scan_task, entry.path, 3)

@app.route('/')
def index():
    html = """
    <!DOCTYPE html><html><head><title>NasWebtoon Admin</title>
    <style>
        body { font-family: 'Segoe UI', sans-serif; background: #121212; color: #eee; padding: 40px; }
        .container { max-width: 1000px; margin: 0 auto; background: #1e1e1e; padding: 30px; border-radius: 12px; }
        .grid-buttons { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 20px; margin-bottom: 30px; }
        .btn { padding: 20px; border-radius: 8px; border: none; font-size: 16px; font-weight: bold; cursor: pointer; transition: 0.3s; color: white; }
        .btn-webtoon { background: #E91E63; } .btn-magazine { background: #2196F3; } .btn-photobook { background: #9C27B0; }
        .btn:hover { opacity: 0.8; transform: translateY(-2px); }
        .status-box { background: #252525; padding: 20px; border-radius: 8px; margin-bottom: 20px; border: 1px solid #333; }
        .log-window { background: #000; color: #0f0; padding: 15px; border-radius: 4px; height: 400px; overflow-y: auto; font-family: 'Consolas', monospace; font-size: 13px; }
        .progress-container { background: #333; height: 10px; border-radius: 5px; margin-top: 10px; overflow: hidden; }
        .progress-bar { background: #4caf50; height: 100%; width: 0%; transition: 0.3s; }
    </style></head><body>
        <div class="container">
            <h1>🛠️ Nas 통합 콘텐츠 관리</h1>
            <div class="grid-buttons">
                <button class="btn btn-webtoon" onclick="startScan('WEBTOON')">웹툰 메타데이터 갱신</button>
                <button class="btn btn-magazine" onclick="startScan('MAGAZINE')">잡지 메타데이터 갱신</button>
                <button class="btn btn-photobook" onclick="startScan('PHOTO_BOOK')">화보 메타데이터 갱신</button>
            </div>
            <div class="status-box">
                <div id="statusLabel">상태: 대기 중</div>
                <div class="progress-container"><div id="progressBar" class="progress-bar"></div></div>
                <div style="margin-top: 10px; display: flex; justify-content: space-between; font-size: 14px;">
                    <span id="progressText">0 / 0</span>
                    <span id="percentText">0%</span>
                </div>
            </div>
            <div class="log-window" id="logWindow"></div>
        </div>
        <script>
            function startScan(type) {
                fetch('/start_scan?type=' + type).then(r => r.json());
                document.getElementById('logWindow').innerHTML = '<div>[' + type + '] 스캔 시작 중...</div>';
            }
            const evtSource = new EventSource("/stream_logs");
            evtSource.onmessage = function(event) {
                const data = JSON.parse(event.data);
                document.getElementById('statusLabel').innerText = "상태: " + (data.is_running ? data.current_type + " 처리 중..." : "대기 중") + " (" + data.current_item + ")";
                document.getElementById('progressText').innerText = data.processed + " / " + data.total;
                const percent = data.total > 0 ? Math.round((data.processed / data.total) * 100) : 0;
                document.getElementById('percentText').innerText = percent + "%";
                document.getElementById('progressBar').style.width = percent + "%";
                if (data.new_log) {
                    const div = document.createElement('div'); div.innerText = data.new_log;
                    const logWin = document.getElementById('logWindow'); logWin.appendChild(div);
                    logWin.scrollTop = logWin.scrollHeight;
                }
            };
        </script>
    </body></html>
    """
    return render_template_string(html)

@app.route('/start_scan')
def start_scan_api():
    t = request.args.get('type', 'WEBTOON')
    threading.Thread(target=start_full_scan, args=(t,)).start()
    return jsonify({"status": "started", "type": t})

@app.route('/stream_logs')
def stream_logs():
    def generate():
        while True:
            new_log = None
            try: new_log = log_queue.get(timeout=0.5)
            except: pass
            data = {"is_running": scan_status["is_running"], "current_type": scan_status["current_type"], "current_item": scan_status["current_item"], "total": scan_status["total"], "processed": scan_status["processed"], "new_log": new_log}
            yield f"data: {json.dumps(data)}\n\n"
    return Response(stream_with_context(generate()), mimetype='text/event-stream')

@app.route('/files')
def list_files_api():
    path = request.args.get('path', '')
    if not path:
        target_root = os.path.join(BASE_PATH, "웹툰")
    else:
        target_root = os.path.join(BASE_PATH, path)

    phash = get_path_hash(target_root)
    conn = sqlite3.connect(METADATA_DB_PATH)
    conn.row_factory = sqlite3.Row

    placeholders = ','.join(['?'] * len(EXCLUDED_FOLDERS))
    query = f"SELECT * FROM entries WHERE parent_hash = ? AND name NOT IN ({placeholders}) ORDER BY name"
    rows = conn.execute(query, [phash] + EXCLUDED_FOLDERS).fetchall()

    items = []
    for r in rows:
        items.append({
            'name': r['name'],
            'isDirectory': bool(r['is_dir']),
            'path': r['rel_path']
        })
    conn.close()
    return jsonify(items)

@app.route('/scan')
def scan_comics():
    path = request.args.get('path', '')
    page = request.args.get('page', 1, type=int); psize = request.args.get('page_size', 50, type=int)
    conn = sqlite3.connect(METADATA_DB_PATH); conn.row_factory = sqlite3.Row

    placeholders = ','.join(['?'] * len(EXCLUDED_FOLDERS))

    # 웹툰 모드 등에서 빈 경로로 진입 시 리스트에 초성(가,나,다...)이 나오는 것을 방지
    if not path or path == "웹툰":
        query = f"SELECT * FROM entries WHERE depth = 3 AND abs_path LIKE '%/웹툰/%' AND name NOT IN ({placeholders}) ORDER BY title LIMIT ? OFFSET ?"
        params = EXCLUDED_FOLDERS + [psize, (page-1)*psize]
        rows = conn.execute(query, params).fetchall()

        count_query = f"SELECT COUNT(*) FROM entries WHERE depth = 3 AND abs_path LIKE '%/웹툰/%' AND name NOT IN ({placeholders})"
        total = conn.execute(count_query, EXCLUDED_FOLDERS).fetchone()[0]
    else:
        target_root = os.path.join(BASE_PATH, path)
        phash = get_path_hash(target_root)

        query = f"SELECT * FROM entries WHERE parent_hash = ? AND name NOT IN ({placeholders}) ORDER BY name LIMIT ? OFFSET ?"
        params = [phash] + EXCLUDED_FOLDERS + [psize, (page-1)*psize]

        rows = conn.execute(query, params).fetchall()
        count_query = f"SELECT COUNT(*) FROM entries WHERE parent_hash = ? AND name NOT IN ({placeholders})"
        total = conn.execute(count_query, [phash] + EXCLUDED_FOLDERS).fetchone()[0]

    items = []
    for r in rows:
        meta = json.loads(r['metadata'] or '{}'); meta['poster_url'] = r['poster_url']
        items.append({'name': r['title'] or r['name'], 'isDirectory': bool(r['is_dir']), 'path': r['rel_path'], 'metadata': meta})
    conn.close()
    return jsonify({'total_items': total, 'page': page, 'page_size': psize, 'items': items})

@app.route('/download')
def download():
    p = request.args.get('path', '')
    # [수정됨] urllib.parse.unquote 를 적용하여 앞서 전달된 URL을 확실하게 디코딩
    p = urllib.parse.unquote(p)

    if not p: return "Path required", 400

    # 1. 외부 URL (카카오/네이버 등) 프록시 처리
    if p.startswith("http"):
        try:
            # 카카오/네이버 등 외부 접속 차단을 막기 위해 Referer와 User-Agent 우회
            headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'}
            if 'kakao' in p: headers['Referer'] = 'https://webtoon.kakao.com/'
            elif 'naver' in p: headers['Referer'] = 'https://comic.naver.com/'

            req = urllib.request.Request(p, headers=headers)
            with urllib.request.urlopen(req, timeout=10) as response:
                return send_file(io.BytesIO(response.read()), mimetype=response.headers.get_content_type() or 'image/jpeg')
        except Exception as e:
            logger.error(f"Proxy Download Failed: {p}, Error: {e}")
            return "Image Load Failed", 500

    # 2. 압축 파일 썸네일 캐싱 처리
    if p.startswith("zip_thumb://"):
        rel_path = p[12:]; cache_key = hashlib.md5(normalize_nfc(rel_path).encode('utf-8')).hexdigest() + ".jpg"
        cache_path = os.path.join(THUMB_CACHE_DIR, cache_key)
        if os.path.exists(cache_path): return send_from_directory(THUMB_CACHE_DIR, cache_key)
        azp = os.path.join(BASE_PATH, rel_path)
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
                    with z.open(imgs[min(2, len(imgs)-1)]) as f:
                        img_data = f.read()
                        with open(cache_path, 'wb') as cf: cf.write(img_data)
                        return send_file(io.BytesIO(img_data), mimetype='image/jpeg')
        except: pass
        return "No Image", 404

    # 3. 로컬 파일 처리
    target_path = os.path.join(BASE_PATH, p)
    return send_from_directory(os.path.dirname(target_path), os.path.basename(target_path))

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

    # [수정됨] 만약 zip_entry 자체가 외부 HTTP URL이라면 프록시 처리
    if entry.startswith("http"):
        try:
            headers = {'User-Agent': 'Mozilla/5.0'}
            if 'kakao' in entry: headers['Referer'] = 'https://webtoon.kakao.com/'
            elif 'naver' in entry: headers['Referer'] = 'https://comic.naver.com/'
            req = urllib.request.Request(entry, headers=headers)
            with urllib.request.urlopen(req, timeout=10) as response:
                return send_file(io.BytesIO(response.read()), mimetype=response.headers.get_content_type() or 'image/jpeg')
        except: return "Image Proxy Failed", 500

    abs_p = os.path.join(BASE_PATH, path)
    if os.path.isdir(abs_p): return send_from_directory(abs_p, entry)
    try:
        with zipfile.ZipFile(abs_p, 'r') as z:
            with z.open(entry) as f: return send_file(io.BytesIO(f.read()), mimetype='image/jpeg')
    except: return "Error", 500

@app.route('/metadata')
def get_metadata():
    path = request.args.get('path', '')
    if not path: return jsonify({})
    abs_p = os.path.join(BASE_PATH, path); phash = get_path_hash(abs_p)
    conn = sqlite3.connect(METADATA_DB_PATH); conn.row_factory = sqlite3.Row
    row = conn.execute("SELECT * FROM entries WHERE path_hash = ?", (phash,)).fetchone()
    if not row: conn.close(); return jsonify({})
    meta = json.loads(row['metadata'] or '{}'); meta['poster_url'] = row['poster_url']; meta['title'] = row['title'] or row['name']

    placeholders = ','.join(['?'] * len(EXCLUDED_FOLDERS))
    query = f"SELECT * FROM entries WHERE parent_hash = ? AND name NOT IN ({placeholders}) ORDER BY name"
    ep_rows = conn.execute(query, [phash] + EXCLUDED_FOLDERS).fetchall()

    meta['chapters'] = [{'name': ep['title'] or ep['name'], 'isDirectory': bool(ep['is_dir']), 'path': ep['rel_path'], 'metadata': {'poster_url': ep['poster_url'] or row['poster_url']}} for ep in ep_rows]
    conn.close()
    return jsonify(meta)

@app.route('/search')
def search_comics():
    query = request.args.get('query', '')
    page = request.args.get('page', 1, type=int); psize = request.args.get('page_size', 50, type=int)
    if not query: return jsonify({'total_items': 0, 'page': page, 'page_size': psize, 'items': []})
    conn = sqlite3.connect(METADATA_DB_PATH); conn.row_factory = sqlite3.Row
    q = f"%{query}%"

    placeholders = ','.join(['?'] * len(EXCLUDED_FOLDERS))
    query_str = f"SELECT * FROM entries WHERE (title LIKE ? OR name LIKE ?) AND depth >= 2 AND name NOT IN ({placeholders}) ORDER BY title LIMIT ? OFFSET ?"
    params = [q, q] + EXCLUDED_FOLDERS + [psize, (page-1)*psize]

    rows = conn.execute(query_str, params).fetchall()
    total = conn.execute(f"SELECT COUNT(*) FROM entries WHERE (title LIKE ? OR name LIKE ?) AND depth >= 2 AND name NOT IN ({placeholders})", [q, q] + EXCLUDED_FOLDERS).fetchone()[0]

    items = []
    for r in rows:
        meta = json.loads(r['metadata'] or '{}'); meta['poster_url'] = r['poster_url']
        items.append({'name': r['title'] or r['name'], 'isDirectory': bool(r['is_dir']), 'path': r['rel_path'], 'metadata': meta})
    conn.close()
    return jsonify({'total_items': total, 'page': page, 'page_size': psize, 'items': items})

if __name__ == '__main__':
    init_db()
    app.run(host='0.0.0.0', port=5556, threaded=True)
