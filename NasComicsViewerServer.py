from flask import Flask, jsonify, send_from_directory, request, send_file, Response, render_template_string, \
    stream_with_context, redirect
import os, urllib.parse, unicodedata, logging, time, zipfile, io, sys, sqlite3, json, threading, hashlib, yaml, queue
import urllib.request
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
    meta_dict = {
        "summary": "줄거리 정보가 없습니다.",
        "writers": [],
        "genres": [],
        "tags": [],
        "status": "Unknown",
        "publisher": ""
    }

    yaml_dir = abs_path if os.path.isdir(abs_path) else os.path.dirname(abs_path)
    yaml_path = os.path.join(yaml_dir, "kavita.yaml")

    if os.path.exists(yaml_path):
        try:
            with open(yaml_path, 'r', encoding='utf-8') as f:
                data = yaml.safe_load(f)
                if data:
                    m = data.get('meta')
                    if not isinstance(m, dict): m = data

                    if isinstance(m, dict):
                        if os.path.isdir(abs_path) and m.get('Name'):
                            title = normalize_nfc(m['Name'])

                        if m.get('Summary'): meta_dict['summary'] = m['Summary']
                        pub = m.get('Person Publisher') or m.get('Publisher')
                        if pub: meta_dict['publisher'] = pub

                        writers = m.get('Person Writers') or m.get('Writers') or m.get('Author')
                        if writers:
                            if isinstance(writers, str): meta_dict['writers'] = [x.strip() for x in writers.split(',')]
                            else: meta_dict['writers'] = writers

                        genres = m.get('Genres') or m.get('Genre')
                        if genres:
                            if isinstance(genres, str): meta_dict['genres'] = [x.strip() for x in genres.split(',')]
                            else: meta_dict['genres'] = genres

                        tags = m.get('Tags')
                        if tags:
                            if isinstance(tags, str): meta_dict['tags'] = [x.strip() for x in tags.split(',')]
                            else: meta_dict['tags'] = tags

                        status_val = str(m.get('Publication Status', ''))
                        if status_val == '2': meta_dict['status'] = '완결'
                        elif status_val == '1': meta_dict['status'] = '연재'
                        elif m.get('Status'): meta_dict['status'] = m['Status']

                    s_list = data.get('search', [])
                    if isinstance(s_list, list) and len(s_list) > 0:
                        s = s_list[0]
                        if s.get('poster_url'): poster = s['poster_url']
                        if not meta_dict['writers'] and s.get('author'):
                            meta_dict['writers'] = [s['author']]
        except Exception as e:
            logger.error(f"Kavita YAML Parsing Error in {rel_path}: {e}")

    if not os.path.isdir(abs_path):
        title = os.path.splitext(normalize_nfc(os.path.basename(abs_path)))[0]

    if not poster:
        if os.path.isdir(abs_path):
            try:
                with os.scandir(abs_path) as it:
                    ents = sorted(list(it), key=lambda x: x.name)
                    for e in ents:
                        if is_image_file(e.name):
                            poster = (rel_path + "/" + e.name).replace("//", "/")
                            break
                    if not poster:
                        for e in ents:
                            if is_comic_file(e.name):
                                poster = "zip_thumb://" + (rel_path + "/" + e.name).replace("//", "/")
                                break
            except: pass
        else:
            poster = "zip_thumb://" + rel_path

    if poster and not poster.startswith("http"):
        if poster.startswith("zip_thumb://"):
            poster = "zip_thumb://" + urllib.parse.quote(poster[12:])
        else:
            poster = urllib.parse.quote(poster)

    meta_dict['poster_url'] = poster
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
    <!DOCTYPE html><html><head><meta charset="utf-8"><title>🛠️ 메타데이터 관리도구</title><style>
        body { font-family: 'Malgun Gothic', 'Apple SD Gothic Neo', sans-serif; background: #f0f2f5; color: #333; padding: 40px; }
        .container { max-width: 1000px; margin: auto; background: white; padding: 40px; border-radius: 15px; box-shadow: 0 10px 30px rgba(0,0,0,0.1); }
        h2 { border-bottom: 3px solid #eee; padding-bottom: 20px; margin-top: 0; color: #2c3e50; }
        .desc { color: #7f8c8d; font-size: 15px; margin-bottom: 25px; line-height: 1.6; }
        .form-group { display: flex; gap: 10px; margin-bottom: 35px; }
        input { flex: 1; padding: 15px; border: 2px solid #ddd; border-radius: 8px; font-size: 16px; outline: none; transition: border-color 0.3s; }
        input:focus { border-color: #3498db; }
        button { padding: 15px 25px; border: none; border-radius: 8px; font-weight: bold; cursor: pointer; font-size: 15px; transition: background 0.3s; }
        .btn-main { background: #2c3e50; color: white; }
        .btn-test { background: #27ae60; color: white; }
        .btn-del { background: #e74c3c; color: white; }
        button:hover { opacity: 0.8; }
        .progress-container { display: none; background: #fff; border: 1px solid #eee; padding: 25px; border-radius: 10px; margin-bottom: 30px; }
        .console { background: #282c34; color: #abb2bf; padding: 25px; border-radius: 10px; height: 450px; overflow-y: auto; font-family: 'Consolas', monospace; font-size: 14px; line-height: 1.8; }
        .log-line { margin-bottom: 6px; border-bottom: 1px solid #3e4451; padding-bottom: 4px; }
        pre { background: #1e2127; padding: 10px; border-radius: 5px; color: #d19a66; white-space: pre-wrap; word-break: break-all; }
    </style></head><body>
    <div class="container">
        <h2>🛠️ 메타데이터 관리 및 테스트</h2>
        <p class="desc">업데이트할 폴더의 경로를 입력하세요. (예: 완결A/0Z/#좀비를 찾습니다)<br>중복된 데이터가 보인다면 제목으로 검색 후 삭제 기능을 사용하세요.</p>
        <div class="form-group">
            <input type="text" id="pathInput" placeholder="경로 또는 검색어 입력" value=\"""" + target_path + """\">
            <button class="btn-test" onclick="testSync()">단일 폴더 즉시 동기화 (YAML 테스트)</button>
            <button class="btn-main" onclick="startUpdate()">하위 전체 스캔</button>
            <button class="btn-del" onclick="deleteTitle()">제목으로 DB 삭제</button>
        </div>
        <div class="console" id="logConsole"></div>
    </div>
    <script>
        const cb = document.getElementById('logConsole');
        function addLog(msg) {
            const l = document.createElement('div');
            l.className = 'log-line';
            l.innerHTML = msg;
            cb.appendChild(l);
            cb.scrollTop = cb.scrollHeight;
        }

        async function testSync() {
            const path = document.getElementById('pathInput').value;
            cb.innerHTML = '🚀 <b>테스트 시작:</b> ' + path + '<br>';
            try {
                const res = await fetch('/metadata/sync_single?path=' + encodeURIComponent(path));
                const data = await res.json();
                if (data.status === 'success') {
                    addLog('✅ <b>성공!</b> ' + data.scanned_count + '개 항목 갱신됨.');
                    addLog('<b>DB 저장 결과 샘플:</b><pre>' + JSON.stringify(data.db_result, null, 4) + '</pre>');
                } else { addLog('❌ <b>에러:</b> ' + data.error); }
            } catch (e) { addLog('❌ <b>실패:</b> ' + e); }
        }

        async function deleteTitle() {
            const title = document.getElementById('pathInput').value;
            if(!confirm(title + ' 이 포함된 모든 DB 기록을 삭제할까요?')) return;
            try {
                const res = await fetch('/metadata/delete_by_title?title=' + encodeURIComponent(title));
                const data = await res.json();
                addLog('🗑️ ' + data.count + '개의 항목이 삭제되었습니다.');
            } catch (e) { addLog('❌ 실패: ' + e); }
        }

        function startUpdate() {
            const path = document.getElementById('pathInput').value;
            cb.innerHTML = '';
            const es = new EventSource('/metadata/scan_stream?path=' + encodeURIComponent(path));
            es.onmessage = function(e) {
                const d = JSON.parse(e.data);
                if (d.type === 'log') addLog(d.msg);
                else if (d.type === 'progress') addLog('🔄 [' + d.current + '/' + d.total + '] ' + d.name + ' 업데이트 완료');
                if (d.msg && d.msg.includes('FINISH')) es.close();
            };
        }
    </script></body></html>
    """
    return render_template_string(html)


@app.route('/metadata/sync_single')
def sync_single():
    path = request.args.get('path', '').strip('/')
    abs_p = os.path.abspath(os.path.join(BASE_PATH, path)).replace(os.sep, '/')
    if not os.path.exists(abs_p):
        return jsonify({"status": "error", "error": f"Path not found: {abs_p}"})
    items = scan_folder_sync(abs_p, 0)
    conn = sqlite3.connect(METADATA_DB_PATH); conn.row_factory = sqlite3.Row
    row = conn.execute("SELECT * FROM entries WHERE abs_path = ?", (abs_p,)).fetchone()
    if not row and items:
        row = conn.execute("SELECT * FROM entries WHERE path_hash = ?", (items[0][0],)).fetchone()
    conn.close()
    if row:
        res = dict(row); res['metadata'] = json.loads(res['metadata'])
        return jsonify({"status": "success", "scanned_count": len(items), "db_result": res})
    return jsonify({"status": "error", "error": "No DB record found."})


@app.route('/metadata/delete_by_title')
def delete_by_title():
    title = request.args.get('title', '')
    if not title: return jsonify({"count": 0})
    conn = sqlite3.connect(METADATA_DB_PATH)
    cursor = conn.execute("DELETE FROM entries WHERE title LIKE ? OR name LIKE ?", (f'%{title}%', f'%{title}%'))
    count = cursor.rowcount
    conn.commit(); conn.close()
    return jsonify({"count": count})


@app.route('/download')
def download():
    p = urllib.parse.unquote(request.args.get('path', ''))

    # [수정] 외부 URL인 경우 리다이렉트가 아닌 프록시(Proxy) 처리
    # 앱의 SSL/TLS 인증서 검증 오류를 원천 차단하기 위해 서버가 이미지를 직접 받아 전달합니다.
    if p.startswith("http"):
        try:
            req = urllib.request.Request(p, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=10) as response:
                return send_file(io.BytesIO(response.read()), mimetype=response.headers.get_content_type() or 'image/jpeg')
        except Exception as e:
            logger.error(f"Image Proxy Error for {p}: {e}")
            return "Image Proxy Error", 500

    if p.startswith("zip_thumb://"):
        azp = os.path.join(BASE_PATH, p[12:])
        if os.path.isdir(azp):
            try:
                with os.scandir(azp) as it:
                    for e in it:
                        if is_comic_file(e.name): azp = e.path; break
            except: pass
        try:
            with zipfile.ZipFile(azp, 'r') as z:
                imgs = sorted([n for n in z.namelist() if is_image_file(n)])
                if imgs:
                    with z.open(imgs[0]) as f: return send_file(io.BytesIO(f.read()), mimetype='image/jpeg')
        except: pass
        return "No Image", 404
    target_path = os.path.join(BASE_PATH, p)
    return send_from_directory(os.path.dirname(target_path), os.path.basename(target_path))


@app.route('/metadata/scan_stream')
def scan_stream():
    target_rel = request.args.get('path', '').strip('/')
    abs_root = os.path.abspath(os.path.join(BASE_PATH, target_rel)).replace(os.sep, '/')

    def generate():
        # [수정] 즉각적인 피드백을 위해 검색 시작 로그를 먼저 보냅니다.
        yield "data: " + json.dumps({'type': 'log', 'msg': f"🔎 '{target_rel or 'Root'}' 폴더 구조를 분석 중입니다. 잠시만 기다려주세요..."}) + "\n\n"

        targets = []
        for root, dirs, files in os.walk(abs_root):
            if any(is_comic_file(f) for f in files):
                targets.append(root.replace(os.sep, '/'))
        targets = sorted(list(set(targets)))
        total = len(targets)

        yield "data: " + json.dumps({'type': 'log', 'msg': f"🚀 {total}개 폴더를 찾았습니다. 메타데이터 업데이트를 시작합니다."}) + "\n\n"

        for i, t_path in enumerate(targets):
            try:
                name = os.path.basename(t_path)
                scan_folder_sync(t_path, 0)
                yield "data: " + json.dumps({'type': 'progress', 'current': i + 1, 'total': total, 'name': name}) + "\n\n"
            except: pass
        yield "data: " + json.dumps({'type': 'log', 'msg': '🏁 작업이 완료되었습니다! [FINISH]'}) + "\n\n"

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
        rows = conn.execute("SELECT * FROM entries WHERE rel_path LIKE ? AND depth = 3 ORDER BY title LIMIT ? OFFSET ?",
                            (path + '/%' if path else '%', psize, (page - 1) * psize)).fetchall()
        if not rows and page == 1: conn.close(); scan_folder_sync(abs_p, 1); return scan_comics()
    else:
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
        return "Dashboard disabled. Use /metadata/admin"
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


@app.route('/monitor')
def monitor_metadata():
    cat = request.args.get('category', '완결A')
    conn = sqlite3.connect(METADATA_DB_PATH); conn.row_factory = sqlite3.Row
    rows = conn.execute("SELECT * FROM entries WHERE rel_path LIKE ? AND depth >= 2 ORDER BY title", (cat + '/%',)).fetchall()
    conn.close()
    processed = []
    for r in rows:
        try: m = json.loads(r['metadata'] or '{}')
        except: m = {}
        processed.append({
            'title': r['title'] or r['name'], 'path': r['rel_path'], 'poster': r['poster_url'],
            'writers': ", ".join(m.get('writers', [])) if isinstance(m.get('writers'), list) else m.get('writers', ''),
            'publisher': m.get('publisher', ''), 'status': m.get('status', '')
        })
    html = """
    <!DOCTYPE html><html><head><meta charset="utf-8"><title>Manga Metadata Monitor</title><style>
        body { font-family: 'Segoe UI', sans-serif; background: #121212; color: #ccc; padding: 30px; margin: 0; }
        .header { background: #1e1e1e; padding: 20px; border-radius: 12px; margin-bottom: 20px; border: 1px solid #333; }
        .nav { margin-bottom: 25px; padding: 10px; background: #1e1e1e; border-radius: 8px; }
        .nav a { color: #3498db; text-decoration: none; margin-right: 20px; font-weight: bold; font-size: 15px; }
        .nav a.active { color: #f1c40f; text-decoration: underline; }
        .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 25px; }
        .card { background: #1e1e1e; border-radius: 10px; overflow: hidden; border: 1px solid #333; transition: 0.3s; }
        .poster-img { width: 100%; aspect-ratio: 0.72; object-fit: cover; background: #000; }
        .info { padding: 15px; }
        .title { font-weight: bold; color: #fff; margin-bottom: 10px; font-size: 14px; height: 38px; overflow: hidden; }
        .meta-item { font-size: 12px; margin-bottom: 5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
        .label { color: #f1c40f; font-weight: bold; margin-right: 5px; }
    </style></head><body>
        <div class="header"><h1>📊 메타데이터 모니터링 <span style="background:#e74c3c; padding:2px 8px; border-radius:10px;">{{ total }}</span></h1></div>
        <div class="nav">
            {% for c in ["완결A", "완결B", "마블", "번역", "연재", "작가"] %}
            <a href="/monitor?category={{ c }}" class="{{ 'active' if c == category else '' }}">{{ c }}</a>
            {% endfor %}
        </div>
        <div class="grid">
            {% for item in items %}
            <div class="card">
                <img class="poster-img" src="/download?path={{ item.poster }}" onerror="this.src='https://via.placeholder.com/250x350?text=No+Image'">
                <div class="info">
                    <div class="title">{{ item.title }}</div>
                    <div class="meta-item"><span class="label">작가:</span> {{ item.writers or '-' }}</div>
                    <div class="meta-item"><span class="label">출판:</span> {{ item.publisher or '-' }}</div>
                    <div class="meta-item"><span class="label">상태:</span> {{ item.status or '-' }}</div>
                    <div style="color:#555; font-size:10px; margin-top:10px;">{{ item.path }}</div>
                </div>
            </div>
            {% endfor %}
        </div>
    </body></html>
    """
    return render_template_string(html, items=processed, category=cat, total=len(processed))


@app.route('/metadata/inject', methods=['GET', 'POST'])
def manual_inject():
    if request.method == 'POST':
        cat = request.form.get('category', '완결A'); title = request.form.get('title', '테스트'); writers = request.form.get('writers', '').split(','); publisher = request.form.get('publisher', '테스트')
        rel_path = f"{cat}/{title}"; abs_path = os.path.join(BASE_PATH, rel_path).replace(os.sep, '/'); p_hash = get_path_hash(abs_path); parent_hash = get_path_hash(os.path.join(BASE_PATH, cat))
        meta = {"summary": "수동 데이터", "writers": [w.strip() for w in writers if w.strip()], "publisher": publisher, "status": "완결"}
        item = (p_hash, parent_hash, abs_path, rel_path, title, 1, "", title, 3, time.time(), json.dumps(meta, ensure_ascii=False))
        conn = sqlite3.connect(METADATA_DB_PATH); conn.execute('INSERT OR REPLACE INTO entries VALUES (?,?,?,?,?,?,?,?,?,?,?)', item); conn.commit(); conn.close()
        return f"완료. <a href='/monitor?category={cat}'>확인</a>"
    return '<form method="post">카테고리: <input name="category"><br>제목: <input name="title"><br><button type="submit">주입</button></form>'


@app.route('/metadata/debug_all')
def debug_db_all():
    conn = sqlite3.connect(METADATA_DB_PATH)
    rows = conn.execute("SELECT rel_path, depth, is_dir, title FROM entries LIMIT 500").fetchall()
    conn.close()
    return jsonify([{"path": r[0], "depth": r[1], "is_dir": r[2], "title": r[3]} for r in rows])


@app.route('/check_zombie')
def check_zombie():
    conn = sqlite3.connect(METADATA_DB_PATH); conn.row_factory = sqlite3.Row
    rows = conn.execute("SELECT title, rel_path, depth, metadata FROM entries WHERE title LIKE '%좀비%' OR name LIKE '%좀비%'").fetchall()
    conn.close()
    return jsonify([dict(r) for r in rows])


if __name__ == '__main__':
    init_db()
    for cat in ALLOWED_CATEGORIES: scanning_pool.submit(scan_folder_sync, os.path.join(BASE_PATH, cat), 1)
    app.run(host='0.0.0.0', port=5555, threaded=True)
