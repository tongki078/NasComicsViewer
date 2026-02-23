from flask import Flask, jsonify, send_from_directory, request, send_file, Response, render_template_string, \
    stream_with_context, redirect
import os, urllib.parse, unicodedata, logging, time, zipfile, io, sys, sqlite3, json, threading, hashlib, yaml, queue
from concurrent.futures import ThreadPoolExecutor

# [로그 설정]
logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s [%(name)s] %(message)s', stream=sys.stdout)
logger = logging.getLogger("NasServer")

app = Flask(__name__)

# --- 설정 ---
BASE_PATH = "/volume2/video/GDS3/GDRIVE/READING/만화"
METADATA_DB_PATH = '/volume2/video/NasComicsViewer_v7.db'

ALLOWED_CATEGORIES = ["완결A", "완결B", "마블", "번역", "연재", "작가"]
FLATTEN_CATEGORIES = ["완결A", "완결B", "번역", "연재"]

db_queue = queue.Queue()
scanning_pool = ThreadPoolExecutor(max_workers=10)


def normalize_nfc(s):
    if s is None: return ""
    return unicodedata.normalize('NFC', str(s))


def get_path_hash(path):
    p = os.path.abspath(path).replace(os.sep, '/')
    return hashlib.md5(normalize_nfc(p).encode('utf-8')).hexdigest()


def get_depth(rel_path):
    if not rel_path or rel_path == ".": return 0
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
        conn.execute('CREATE INDEX IF NOT EXISTS idx_rel ON entries(rel_path)')
    conn.close()


# --- 정보 추출 엔진 ---
def is_comic_file(name):
    return name.lower().endswith(('.zip', '.cbz', '.rar', '.cbr', '.pdf'))


def is_image_file(name):
    return name.lower().endswith(('.jpg', '.jpeg', '.png', '.webp', '.gif'))


def get_comic_info(abs_path, rel_path):
    title = normalize_nfc(os.path.basename(abs_path))
    poster = None
    meta_dict = {"summary": "줄거리 정보가 없습니다.", "writers": [], "genres": [], "status": "Unknown", "publisher": ""}

    if os.path.isdir(abs_path):
        yaml_path = os.path.join(abs_path, "kavita.yaml")
        if os.path.exists(yaml_path):
            try:
                with open(yaml_path, 'r', encoding='utf-8') as f:
                    data = yaml.safe_load(f)
                    if data:
                        if 'poster' in data: poster = (rel_path + "/" + data['poster']).replace("//", "/")
                        meta_dict['summary'] = data.get('summary', meta_dict['summary'])
                        meta_dict['writers'] = data.get('writers', []) if isinstance(data.get('writers'), list) else [
                            data.get('writers')] if data.get('writers') else []
                        meta_dict['genres'] = data.get('genres', []) if isinstance(data.get('genres'), list) else [
                            data.get('genres')] if data.get('genres') else []
                        meta_dict['status'] = data.get('status', 'Unknown')
                        meta_dict['publisher'] = data.get('publisher', '')
            except:
                pass
        if not poster:
            try:
                with os.scandir(abs_path) as it:
                    ents = sorted(list(it), key=lambda x: x.name)
                    for e in ents:
                        if is_image_file(e.name): poster = (rel_path + "/" + e.name).replace("//", "/"); break
                    if not poster:
                        for e in ents:
                            if is_comic_file(e.name): poster = "zip_thumb://" + (rel_path + "/" + e.name).replace("//",
                                                                                                                  "/"); break
            except:
                pass
    else:
        poster = "zip_thumb://" + rel_path
        title = os.path.splitext(title)[0]

    if poster:
        if poster.startswith("zip_thumb://"):
            poster = "zip_thumb://" + urllib.parse.quote(poster[12:])
        else:
            poster = urllib.parse.quote(poster)
    return title, poster, json.dumps(meta_dict, ensure_ascii=False)


def scan_folder_sync(abs_path, recursive_depth=0):
    abs_path = os.path.abspath(abs_path).replace(os.sep, '/')
    root = os.path.abspath(BASE_PATH).replace(os.sep, '/')
    rel_from_root = os.path.relpath(abs_path, BASE_PATH).replace(os.sep, '/')

    if abs_path != root:
        p_abs = os.path.dirname(abs_path)
        p_hash = get_path_hash(p_abs)
        title, poster, meta_json = get_comic_info(abs_path, rel_from_root)
        folder_item = (get_path_hash(abs_path), p_hash, abs_path, rel_from_root, os.path.basename(abs_path),
                       1 if os.path.isdir(abs_path) else 0, poster, title, get_depth(rel_from_root), time.time(),
                       meta_json)

        conn = sqlite3.connect(METADATA_DB_PATH)
        conn.execute('INSERT OR REPLACE INTO entries VALUES (?,?,?,?,?,?,?,?,?,?,?)', folder_item)
        conn.commit()
        conn.close()

    items = []
    try:
        phash = get_path_hash(abs_path)
        with os.scandir(abs_path) as it:
            for e in it:
                if e.is_dir() or is_comic_file(e.name):
                    e_abs = os.path.abspath(e.path).replace(os.sep, '/')
                    rel = os.path.relpath(e_abs, root).replace(os.sep, '/')
                    title, poster, meta_json = get_comic_info(e_abs, rel)
                    items.append((
                                 get_path_hash(e_abs), phash, e_abs, rel, normalize_nfc(e.name), 1 if e.is_dir() else 0,
                                 poster, title, get_depth(rel), time.time(), meta_json))
        if items:
            conn = sqlite3.connect(METADATA_DB_PATH)
            conn.executemany('INSERT OR REPLACE INTO entries VALUES (?,?,?,?,?,?,?,?,?,?,?)', items)
            conn.commit()
            conn.close()
            if recursive_depth > 0:
                for item in items:
                    if item[5] == 1: scan_folder_sync(item[2], recursive_depth - 1)
    except:
        pass
    return items


# --- API ---
@app.route('/metadata/admin')
def metadata_admin():
    target_path = request.args.get('path', '완결A')
    html = """
    <!DOCTYPE html><html><head><meta charset="utf-8"><title>🛠️ 메타데이터 실시간 업데이트</title><style>
        body { font-family: 'Malgun Gothic', 'Apple SD Gothic Neo', sans-serif; background: #f0f2f5; color: #333; padding: 40px; }
        .container { max-width: 1000px; margin: auto; background: white; padding: 40px; border-radius: 15px; box-shadow: 0 10px 30px rgba(0,0,0,0.1); }
        h2 { border-bottom: 3px solid #eee; padding-bottom: 20px; margin-top: 0; color: #2c3e50; }
        .desc { color: #7f8c8d; font-size: 15px; margin-bottom: 25px; line-height: 1.6; }
        .form-group { display: flex; gap: 15px; margin-bottom: 35px; }
        input { flex: 1; padding: 15px; border: 2px solid #ddd; border-radius: 8px; font-size: 16px; outline: none; transition: border-color 0.3s; }
        input:focus { border-color: #3498db; }
        button { padding: 15px 30px; background: #2c3e50; color: white; border: none; border-radius: 8px; font-weight: bold; cursor: pointer; font-size: 16px; transition: background 0.3s; }
        button:hover { background: #1a252f; }
        .progress-container { display: none; background: #fff; border: 1px solid #eee; padding: 25px; border-radius: 10px; margin-bottom: 30px; }
        .progress-status { font-weight: bold; margin-bottom: 15px; font-size: 20px; color: #2c3e50; }
        .progress-bar-bg { width: 100%; height: 30px; background: #ecf0f1; border-radius: 15px; overflow: hidden; margin-bottom: 15px; }
        .progress-bar-fill { width: 0%; height: 100%; background: #3498db; transition: width 0.4s ease; box-shadow: inset 0 -2px 5px rgba(0,0,0,0.1); }
        .stats { display: flex; justify-content: space-between; font-size: 16px; margin-bottom: 12px; font-weight: 500; }
        .console { background: #282c34; color: #abb2bf; padding: 25px; border-radius: 10px; height: 450px; overflow-y: auto; font-family: 'Consolas', 'Monaco', monospace; font-size: 14px; line-height: 1.8; box-shadow: inset 0 2px 10px rgba(0,0,0,0.2); }
        .log-line { margin-bottom: 6px; border-bottom: 1px solid #3e4451; padding-bottom: 4px; }
        .success-text { color: #98c379; font-weight: bold; }
        .fail-text { color: #e06c75; font-weight: bold; }
        .init-text { color: #61afef; font-weight: bold; }
    </style></head><body>
    <div class="container">
        <h2>🛠️ 메타데이터 실시간 업데이트</h2>
        <p class="desc">업데이트할 폴더의 경로를 입력하세요. (예: 완결A, 작가/ㄱ)<br>하위 모든 폴더를 탐색하여 kavita.yaml 및 첫 페이지 썸네일을 업데이트합니다.</p>
        <div class="form-group">
            <input type="text" id="pathInput" placeholder="경로 입력 (예: 완결A)" value=\"""" + target_path + """\">
            <button id="startBtn" onclick="startUpdate()">업데이트 시작</button>
        </div>
        <div id="progressSection" class="progress-container">
            <div class="progress-status" id="currentTask">준비 중...</div>
            <div class="progress-bar-bg"><div class="progress-bar-fill" id="progressBar"></div></div>
            <div class="stats">
                <span id="counter">0 / 0</span>
                <span id="percent">0%</span>
            </div>
            <div class="stats">
                <span class="success-text">성공: <span id="successCount">0</span></span>
                <span class="fail-text">실패: <span id="failCount">0</span></span>
            </div>
        </div>
        <div class="console" id="logConsole"></div>
    </div>
    <script>
        function startUpdate() {
            const path = document.getElementById('pathInput').value;
            const cb = document.getElementById('logConsole');
            const ps = document.getElementById('progressSection');
            const pb = document.getElementById('progressBar');
            const btn = document.getElementById('startBtn');
            btn.disabled = true;
            cb.innerHTML = '';
            ps.style.display = 'block';
            const es = new EventSource('/metadata/scan_stream?path=' + encodeURIComponent(path));
            let s = 0; let f = 0; let t = 0;
            es.onmessage = function(e) {
                const d = JSON.parse(e.data);
                if (d.type === 'init') {
                    t = d.total;
                    document.getElementById('counter').innerText = '0 / ' + t;
                } else if (d.type === 'log') {
                    const l = document.createElement('div');
                    l.className = 'log-line ' + (d.icon==='🚀'?'init-text':'');
                    l.innerText = d.icon + ' ' + d.msg;
                    cb.appendChild(l);
                    cb.scrollTop = cb.scrollHeight;
                } else if (d.type === 'progress') {
                    const p = t > 0 ? Math.round((d.current / t) * 100) : 0;
                    pb.style.width = p + '%';
                    document.getElementById('percent').innerText = p + '%';
                    document.getElementById('counter').innerText = d.current + ' / ' + t;
                    document.getElementById('currentTask').innerText = '처리 중: ' + d.name;
                    if (d.status === 'success') s++; else f++;
                    document.getElementById('successCount').innerText = s;
                    document.getElementById('failCount').innerText = f;
                    const l = document.createElement('div');
                    l.className = 'log-line';
                    l.innerHTML = (d.status==='success'?'<span class="success-text">✅</span>':'<span class="fail-text">❌</span>') + ' [UPDATE] \\'' + d.name + '\\' updated via SCAN';
                    cb.appendChild(l);
                    cb.scrollTop = cb.scrollHeight;
                }
                if (d.msg && d.msg.indexOf('FINISH') !== -1) {
                    es.close();
                    btn.disabled = false;
                }
            };
            es.onerror = function() { es.close(); btn.disabled = false; };
        }
    </script></body></html>
    """
    return render_template_string(html)


@app.route('/metadata/scan_stream')
def scan_stream():
    target_rel = request.args.get('path', '').strip('/')
    abs_root = os.path.abspath(os.path.join(BASE_PATH, target_rel)).replace(os.sep, '/')

    def generate():
        msg_search = "🔎 Searching folders in '" + (target_rel or "Root") + "'..."
        yield "data: " + json.dumps({'type': 'log', 'msg': msg_search, 'icon': '🔍'}) + "\n\n"
        targets = []
        for root, dirs, files in os.walk(abs_root):
            if any(is_comic_file(f) for f in files):
                targets.append(root.replace(os.sep, '/'))
        targets = sorted(list(set(targets)))
        total = len(targets)
        msg_init = "🚀 [INIT] Found " + str(total) + " manga items to update."
        yield "data: " + json.dumps({'type': 'log', 'msg': msg_init, 'icon': '🚀'}) + "\n\n"
        yield "data: " + json.dumps({'type': 'init', 'total': total}) + "\n\n"
        for i, t_path in enumerate(targets):
            try:
                name = os.path.basename(t_path)
                scan_folder_sync(t_path, 0)
                yield "data: " + json.dumps(
                    {'type': 'progress', 'current': i + 1, 'name': name, 'status': 'success'}) + "\n\n"
            except:
                yield "data: " + json.dumps(
                    {'type': 'progress', 'current': i + 1, 'name': name, 'status': 'fail'}) + "\n\n"
        yield "data: " + json.dumps({'type': 'log', 'msg': '🏁 [FINISH] Update completed.', 'icon': '🏁'}) + "\n\n"

    return Response(stream_with_context(generate()), mimetype='text/event-stream')


@app.route('/files')
def list_files():
    path = request.args.get('path', '')
    abs_p = os.path.abspath(os.path.join(BASE_PATH, path)).replace(os.sep, '/')
    parent_hash = get_path_hash(abs_p)
    conn = sqlite3.connect(METADATA_DB_PATH)
    conn.row_factory = sqlite3.Row
    rows = conn.execute("SELECT * FROM entries WHERE parent_hash = ? ORDER BY name", (parent_hash,)).fetchall()
    conn.close()
    if not rows: scan_folder_sync(abs_p, 0); return list_files()
    return jsonify([{'name': r['name'], 'isDirectory': bool(r['is_dir']), 'path': r['rel_path']} for r in rows])


@app.route('/scan')
def scan_comics():
    path = request.args.get('path', '')
    page = request.args.get('page', 1, type=int);
    psize = request.args.get('page_size', 50, type=int)
    abs_p = os.path.abspath(os.path.join(BASE_PATH, path)).replace(os.sep, '/')
    rel_path = os.path.relpath(abs_p, BASE_PATH).replace(os.sep, '/')
    cat_name = rel_path.split('/')[0]
    is_flatten_cat = any(normalize_nfc(f).lower() == normalize_nfc(cat_name).lower() for f in FLATTEN_CATEGORIES)
    conn = sqlite3.connect(METADATA_DB_PATH);
    conn.row_factory = sqlite3.Row
    if is_flatten_cat and get_depth(rel_path) == 1:
        # depth = 3인 항목(작품)들만 필터링하여 평탄화된 목록 제공
        rows = conn.execute("SELECT * FROM entries WHERE rel_path LIKE ? AND depth = 3 ORDER BY title LIMIT ? OFFSET ?",
                            (path + '/%' if path else '%', psize, (page - 1) * psize)).fetchall()
        if not rows and page == 1: conn.close(); scan_folder_sync(abs_p, 1); return scan_comics()
    else:
        # 그 외 카테고리는 계층 구조(폴더 구조) 그대로 탐색
        parent_hash = get_path_hash(abs_p)
        rows = conn.execute("SELECT * FROM entries WHERE parent_hash = ? ORDER BY name LIMIT ? OFFSET ?",
                            (parent_hash, psize, (page - 1) * psize)).fetchall()
        if not rows and page == 1: conn.close(); scan_folder_sync(abs_p, 0); return scan_comics()
    items = []
    for r in rows:
        meta = json.loads(r['metadata'] or '{}')
        meta['poster_url'] = r['poster_url']
        meta['title'] = r['title']
        items.append({'name': r['title'] or r['name'], 'isDirectory': bool(r['is_dir']), 'path': r['rel_path'],
                      'metadata': meta})
    conn.close()
    return jsonify({'total_items': 10000, 'page': page, 'page_size': psize, 'items': items})


@app.route('/search')
def search_comics():
    query = request.args.get('query', '').strip()
    if not query:
        return jsonify({'total_items': 0, 'page': 1, 'page_size': 50, 'items': []})

    page = request.args.get('page', 1, type=int)
    psize = request.args.get('page_size', 50, type=int)

    logger.info(f"🔎 SEARCH START: '{query}'")

    conn = sqlite3.connect(METADATA_DB_PATH)
    conn.row_factory = sqlite3.Row

    q_nfc = unicodedata.normalize('NFC', query)
    q_nfd = unicodedata.normalize('NFD', query)

    patterns = set([f"%{q_nfc}%", f"%{q_nfd}%", f"%{query}%"])

    where_clauses = []
    params = []
    for p in patterns:
        where_clauses.append("title LIKE ?")
        params.append(p)
        where_clauses.append("name LIKE ?")
        params.append(p)

    where_stmt = " OR ".join(where_clauses)

    # [수정] 만화책 권수 하나하나 검색되는 문제를 해결하기 위해 그룹화 로직 적용
    # 1. 파일이 깊은 곳에 있다면 부모 폴더(시리즈)로 그룹핑
    # 2. 결과는 고유한 path_hash를 가진 항목들만 반환
    sql = f"""
    SELECT * FROM entries
    WHERE path_hash IN (
        SELECT DISTINCT
            CASE
                WHEN is_dir = 0 AND depth >= 3 THEN parent_hash
                ELSE path_hash
            END
        FROM entries
        WHERE ({where_stmt}) AND depth >= 2
    )
    ORDER BY last_scanned DESC LIMIT ? OFFSET ?
    """

    count_sql = f"""
    SELECT COUNT(DISTINCT
        CASE
            WHEN is_dir = 0 AND depth >= 3 THEN parent_hash
            ELSE path_hash
        END)
    FROM entries
    WHERE ({where_stmt}) AND depth >= 2
    """

    try:
        rows = conn.execute(sql, params + [psize, (page - 1) * psize]).fetchall()
        total_row = conn.execute(count_sql, params).fetchone()
        total = total_row[0] if total_row else 0

        items = []
        for r in rows:
            rel_path = r['rel_path']
            category = rel_path.split('/')[0] if rel_path else "Unknown"

            meta = json.loads(r['metadata'] or '{}')
            meta['poster_url'] = r['poster_url']
            meta['title'] = r['title']
            meta['category'] = category

            items.append({
                'name': r['title'] or r['name'],
                'isDirectory': bool(r['is_dir']),
                'path': r['rel_path'],
                'metadata': meta
            })

        logger.info(f"✅ SEARCH FINISH: Found {total} groups")
        return jsonify({'total_items': total, 'page': page, 'page_size': psize, 'items': items})
    except Exception as e:
        logger.error(f"❌ SEARCH ERROR: {str(e)}")
        return jsonify({'error': str(e)}), 500
    finally:
        conn.close()


@app.route('/metadata')
def get_metadata():
    path = request.args.get('path') or request.args.get('url')
    if not path:
        conn = sqlite3.connect(METADATA_DB_PATH);
        conn.row_factory = sqlite3.Row
        rows = conn.execute(
            "SELECT title, rel_path, poster_url FROM entries WHERE is_dir = 1 AND depth = 3 ORDER BY last_scanned DESC LIMIT 100").fetchall()
        conn.close()
        html_dashboard = """
        <html><head><title>Nas Comics Dashboard</title><style>
            body { font-family: sans-serif; background: #111; color: #eee; padding: 20px; }
            .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 20px; }
            .card { background: #222; padding: 15px; border-radius: 8px; text-align: center; }
            img { width: 100%; height: 240px; object-fit: cover; border-radius: 4px; margin-bottom: 8px; }
            a, button { color: #fff; text-decoration: none; font-size: 13px; display: block; margin-top: 5px; padding: 5px; border-radius: 4px; }
            .btn-view { background: #3498db; }
            .btn-update { background: #e67e22; border: none; width: 100%; cursor: pointer; }
        </style></head><body>
            <h1>최근 스캔된 만화 (100개) | <a href="/metadata/admin" style="display:inline; color:lime;">⚙️ 관리자 모드</a></h1>
            <div class="grid">
                {% for r in rows %}
                <div class="card">
                    <img src="/download?path={{ r.poster_url }}" onerror="this.src='https://via.placeholder.com/180x240?text=No+Image'">
                    <div><b>{{ r.title }}</b></div>
                    <a class="btn-view" href="/metadata?path={{ r.rel_path }}">JSON 정보</a>
                    <button class="btn-update" onclick="location.href='/metadata/admin?path={{ r.rel_path }}'">🔄 재스캔</button>
                </div>
                {% endfor %}
            </div>
        </body></html>
        """
        return render_template_string(html_dashboard, rows=rows)
    path = normalize_nfc(path);
    abs_p = os.path.abspath(os.path.join(BASE_PATH, path)).replace(os.sep, '/')
    phash = get_path_hash(abs_p);
    conn = sqlite3.connect(METADATA_DB_PATH);
    conn.row_factory = sqlite3.Row
    row = conn.execute("SELECT * FROM entries WHERE path_hash = ?", (phash,)).fetchone()
    if not row: scan_folder_sync(os.path.dirname(abs_p), 0); row = conn.execute(
        "SELECT * FROM entries WHERE path_hash = ?", (phash,)).fetchone()
    if not row: conn.close(); return jsonify({"error": "Not found", "path": path}), 404
    children = conn.execute("SELECT * FROM entries WHERE parent_hash = ? ORDER BY name", (phash,)).fetchall()
    conn.close()
    meta = json.loads(row['metadata'] or '{}');
    meta['title'] = row['title'];
    meta['poster_url'] = row['poster_url'];
    meta['rel_path'] = row['rel_path']
    meta['chapters'] = [{'name': c['name'], 'path': c['rel_path'], 'isDirectory': bool(c['is_dir']),
                         'metadata': {'poster_url': c['poster_url'], 'title': c['name']}} for c in children]
    return jsonify(meta)


@app.route('/zip_entries')
def zip_entries():
    path = urllib.parse.unquote(request.args.get('path', ''))
    abs_p = os.path.join(BASE_PATH, path)
    if not os.path.isfile(abs_p): return jsonify([])
    try:
        with zipfile.ZipFile(abs_p, 'r') as z:
            imgs = sorted([n for n in z.namelist() if is_image_file(n)])
            return jsonify(imgs)
    except:
        return jsonify([])


@app.route('/download_zip_entry')
def download_zip_entry():
    path = urllib.parse.unquote(request.args.get('path', ''));
    entry = urllib.parse.unquote(request.args.get('entry', ''))
    abs_p = os.path.join(BASE_PATH, path)
    if not os.path.isfile(abs_p): return "No Zip", 404
    try:
        with zipfile.ZipFile(abs_p, 'r') as z:
            with z.open(entry) as f: return send_file(io.BytesIO(f.read()), mimetype='image/jpeg')
    except:
        return "Error", 500


@app.route('/download')
def download():
    p = urllib.parse.unquote(request.args.get('path', ''))
    if p.startswith("zip_thumb://"):
        azp = os.path.join(BASE_PATH, p[12:])
        if os.path.isdir(azp):
            try:
                with os.scandir(azp) as it:
                    for e in it:
                        if is_comic_file(e.name): azp = e.path; break
            except:
                pass
        try:
            with zipfile.ZipFile(azp, 'r') as z:
                imgs = sorted([n for n in z.namelist() if is_image_file(n)])
                if imgs:
                    with z.open(imgs[0]) as f: return send_file(io.BytesIO(f.read()), mimetype='image/jpeg')
        except:
            pass
        return "No Image", 404
    target_path = os.path.join(BASE_PATH, p)
    return send_from_directory(os.path.dirname(target_path), os.path.basename(target_path))


@app.route('/monitor')
def monitor_metadata():
    cat = request.args.get('category', '완결A')
    conn = sqlite3.connect(METADATA_DB_PATH)
    conn.row_factory = sqlite3.Row
    # depth >= 2로 설정하여 수동 입력한 작품(2)과 스캔된 작품(3) 모두 나오게 합니다.
    rows = conn.execute("SELECT * FROM entries WHERE rel_path LIKE ? AND depth >= 2 ORDER BY title", (cat + '/%',)).fetchall()
    conn.close()

    processed = []
    for r in rows:
        try:
            m = json.loads(r['metadata'] or '{}')
        except:
            m = {}

        processed.append({
            'title': r['title'] or r['name'],
            'path': r['rel_path'],
            'poster': r['poster_url'],
            'writers': ", ".join(m.get('writers', [])) if isinstance(m.get('writers'), list) else m.get('writers', ''),
            'publisher': m.get('publisher', ''),
            'status': m.get('status', '')
        })

    html = """
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="utf-8">
        <title>Manga Metadata Monitor</title>
        <style>
            body { font-family: 'Segoe UI', sans-serif; background: #121212; color: #ccc; padding: 30px; margin: 0; }
            .header { background: #1e1e1e; padding: 20px; border-radius: 12px; margin-bottom: 20px; border: 1px solid #333; }
            .nav { margin-bottom: 25px; padding: 10px; background: #1e1e1e; border-radius: 8px; }
            .nav a { color: #3498db; text-decoration: none; margin-right: 20px; font-weight: bold; font-size: 15px; }
            .nav a.active { color: #f1c40f; text-decoration: underline; }

            .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 25px; }
            .card { background: #1e1e1e; border-radius: 10px; overflow: hidden; border: 1px solid #333; transition: 0.3s; }
            .card:hover { transform: translateY(-5px); border-color: #555; }

            .poster-box { width: 100%; aspect-ratio: 0.72; background: #000; position: relative; }
            .poster-img { width: 100%; height: 100%; object-fit: cover; }

            .info { padding: 15px; }
            .title { font-weight: bold; color: #fff; margin-bottom: 10px; font-size: 14px;
                     display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; height: 38px; }

            .meta-item { font-size: 12px; margin-bottom: 5px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
            .label { color: #f1c40f; font-weight: bold; margin-right: 5px; }
            .path-text { color: #555; font-size: 10px; margin-top: 10px; word-break: break-all; }

            h1 { margin: 0; color: #fff; font-size: 24px; }
            .count-badge { background: #e74c3c; color: white; padding: 2px 8px; border-radius: 10px; font-size: 14px; vertical-align: middle; }
        </style>
    </head>
    <body>
        <div class="header">
            <h1>📊 만화 메타데이터 모니터링 <span class="count-badge">{{ total }}</span></h1>
        </div>

        <div class="nav">
            {% for c in ["완결A", "완결B", "마블", "번역", "연재", "작가"] %}
            <a href="/monitor?category={{ c }}" class="{{ 'active' if c == category else '' }}">{{ c }}</a>
            {% endfor %}
        </div>

        <div class="grid">
            {% for item in items %}
            <div class="card">
                <div class="poster-box">
                    <img class="poster-img" src="/download?path={{ item.poster }}"
                         onerror="this.src='https://via.placeholder.com/250x350?text=No+Image'">
                </div>
                <div class="info">
                    <div class="title" title="{{ item.title }}">{{ item.title }}</div>
                    <div class="meta-item"><span class="label">작가:</span> {{ item.writers or '-' }}</div>
                    <div class="meta-item"><span class="label">출판:</span> {{ item.publisher or '-' }}</div>
                    <div class="meta-item"><span class="label">상태:</span> {{ item.status or '-' }}</div>
                    <div class="path-text">{{ item.path }}</div>
                </div>
            </div>
            {% endfor %}
        </div>
    </body>
    </html>
    """
    return render_template_string(html, items=processed, category=cat, total=len(processed))


@app.route('/metadata/inject', methods=['GET', 'POST'])
def manual_inject():
    if request.method == 'POST':
        cat = request.form.get('category', '완결A')
        title = request.form.get('title', '테스트 작품')
        writers = request.form.get('writers', '').split(',')
        publisher = request.form.get('publisher', '테스트 출판사')

        rel_path = f"{cat}/{title}"
        abs_path = os.path.join(BASE_PATH, rel_path).replace(os.sep, '/')
        p_hash = get_path_hash(abs_path)
        parent_hash = get_path_hash(os.path.join(BASE_PATH, cat))

        meta = {
            "summary": "수동으로 입력한 테스트 데이터입니다.",
            "writers": [w.strip() for w in writers if w.strip()],
            "publisher": publisher,
            "status": "완결"
        }

        # DB에 강제로 삽입 (테스트를 위해 depth를 2와 3 모두에서 보이게 조절 가능)
        # /monitor가 현재 depth=3만 보므로, 테스트를 위해 3으로 입력해봅니다.
        item = (p_hash, parent_hash, abs_path, rel_path, title, 1, "", title, 3, time.time(),
                json.dumps(meta, ensure_ascii=False))

        conn = sqlite3.connect(METADATA_DB_PATH)
        conn.execute('INSERT OR REPLACE INTO entries VALUES (?,?,?,?,?,?,?,?,?,?,?)', item)
        conn.commit()
        conn.close()
        return f"입력 완료: {title} (카테고리: {cat}). <a href='/monitor?category={cat}'>모니터 확인</a>"

    return """
    <form method="post" style="padding:20px; line-height:2;">
        <h3>🛠️ 메타데이터 수동 주입 (테스트용)</h3>
        카테고리: <input type="text" name="category" value="완결A"><br>
        작품제목: <input type="text" name="title" placeholder="예: 나루토"><br>
        작가(쉼표구분): <input type="text" name="writers" placeholder="예: 작가A, 작가B"><br>
        출판사: <input type="text" name="publisher" value="테스트출판사"><br>
        <button type="submit">DB에 강제 입력</button>
    </form>
    """
# --- 기존 /monitor 라우터의 쿼리 수정 (depth 2~3 모두 보이게) ---
# NasComicsViewerServer.py 내의 monitor_metadata 함수에서 아래 줄을 찾아 수정하세요.
# rows = conn.execute("SELECT * FROM entries WHERE rel_path LIKE ? AND (depth = 2 OR depth = 3) ORDER BY title", (cat + '/%',)).fetchall()
@app.route('/metadata/debug_all')
def debug_db_all():
    conn = sqlite3.connect(METADATA_DB_PATH)
    rows = conn.execute("SELECT rel_path, depth, is_dir, title FROM entries LIMIT 500").fetchall()
    conn.close()
    return jsonify([{"path": r[0], "depth": r[1], "is_dir": r[2], "title": r[3]} for r in rows])

@app.route('/check_zombie')
def check_zombie():
    conn = sqlite3.connect(METADATA_DB_PATH)
    conn.row_factory = sqlite3.Row
    # '좀비'가 포함된 모든 데이터를 찾습니다.
    rows = conn.execute("SELECT title, rel_path, depth, metadata FROM entries WHERE title LIKE '%좀비%' OR name LIKE '%좀비%'").fetchall()
    conn.close()
    return jsonify([dict(r) for r in rows])

if __name__ == '__main__':
    init_db()
    for cat in ALLOWED_CATEGORIES: scanning_pool.submit(scan_folder_sync, os.path.join(BASE_PATH, cat), 1)
    app.run(host='0.0.0.0', port=5555, threaded=True)
