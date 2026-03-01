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

# PDF/EPUB 처리를 위한 라이브러리 체크
try:
    import fitz  # PyMuPDF
    HAS_FITZ = True
except ImportError:
    HAS_FITZ = False
    logger.warning("PyMuPDF (fitz) not found. PDF/EPUB thumbnails will not be generated. Install with: pip install pymupdf")

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
scanning_pool = ThreadPoolExecutor(max_workers=10)

# 페이지 수 캐시 (PDF/EPUB 로딩 속도 향상용)
doc_page_cache = {}

def add_web_log(msg, type="INFO"):
    prefix = "✅ [SUCCESS]" if type=="SUCCESS" else "❌ [FAILED]" if type=="ERROR" else "🚢 [INIT]" if type=="INIT" else "📝 [UPDATE]"
    formatted_msg = f"{prefix} {msg}"
    scan_status["logs"].append(formatted_msg)
    if len(scan_status["logs"]) > 200: scan_status["logs"].pop(0)
    log_queue.put(formatted_msg)

def normalize_nfc(s):
    if s is None: return ""
    return unicodedata.normalize('NFC', str(s))

def get_abs_path(rel_path):
    """NFC와 NFD 경로를 모두 시도하여 실제 존재하는 절대 경로를 반환합니다."""
    if not rel_path: return BASE_PATH
    rel_nfc = normalize_nfc(rel_path)
    abs_path = os.path.abspath(os.path.join(BASE_PATH, rel_nfc)).replace(os.sep, '/')
    if os.path.exists(abs_path): return abs_path
    rel_nfd = unicodedata.normalize('NFD', rel_nfc)
    abs_path_nfd = os.path.abspath(os.path.join(BASE_PATH, rel_nfd)).replace(os.sep, '/')
    if os.path.exists(abs_path_nfd): return abs_path_nfd
    return abs_path

def is_excluded(name):
    n = normalize_nfc(name)
    return n in [normalize_nfc(f) for f in EXCLUDED_FOLDERS] or n.lower() == "kavita.yaml"

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
            conn.executemany('''
                INSERT OR REPLACE INTO entries
                (path_hash, parent_hash, abs_path, rel_path, name, is_dir, poster_url, title, depth, last_scanned, metadata)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
            ''', items)
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
    conn.close()

# --- 정보 추출 엔진 ---
def is_comic_file(name):
    return name.lower().endswith(('.zip', '.cbz', '.rar', '.cbr', '.pdf', '.epub'))

def is_image_file(name):
    return name.lower().endswith(('.jpg', '.jpeg', '.png', '.webp', '.gif'))

def generate_file_thumbnail(file_path, cache_path):
    if not HAS_FITZ: return False
    if os.path.exists(cache_path): return True
    try:
        doc = fitz.open(file_path)
        if doc.page_count > 0:
            page = doc.load_page(0)
            pix = page.get_pixmap(matrix=fitz.Matrix(1.2, 1.2))
            pix.save(cache_path)
            doc.close()
            return True
        doc.close()
    except: pass
    return False

def generate_zip_thumbnail(zip_path, cache_path):
    if os.path.exists(cache_path): return True
    try:
        with zipfile.ZipFile(zip_path, 'r') as z:
            imgs = sorted([n for n in z.namelist() if is_image_file(n)])
            if imgs:
                target = imgs[min(2, len(imgs)-1)]
                with z.open(target) as f:
                    img_data = f.read()
                    with open(cache_path, 'wb') as cf: cf.write(img_data)
                    return True
    except: pass
    return False

def find_first_image_recursive(path, depth_limit=4):
    if depth_limit <= 0: return None
    try:
        with os.scandir(path) as it:
            entries = sorted(list(it), key=lambda x: x.name)
            subdirs = []
            comic_files = []
            for e in entries:
                if e.name.startswith('.') or is_excluded(e.name): continue
                if e.is_file() and is_image_file(e.name):
                    return os.path.relpath(e.path, BASE_PATH).replace(os.sep, '/')
                if e.is_dir(): subdirs.append(e.path)
                elif e.is_file() and is_comic_file(e.name): comic_files.append(e.path)
            for sd in subdirs[:5]:
                res = find_first_image_recursive(sd, depth_limit - 1)
                if res: return res
            if comic_files: return "zip_thumb://" + os.path.relpath(comic_files[0], BASE_PATH).replace(os.sep, '/')
    except: pass
    return None

def get_comic_info(abs_path, rel_path):
    title = normalize_nfc(os.path.basename(abs_path))
    poster = None
    meta_dict = {"summary": "줄거리 정보가 없습니다.", "writers": [], "genres": [], "status": "Unknown", "publisher": ""}
    if os.path.isdir(abs_path):
        kavita_path = os.path.join(abs_path, "kavita.yaml")
        if not os.path.exists(kavita_path): kavita_path = os.path.join(abs_path, "Kavita.yaml")
        if os.path.exists(kavita_path):
            try:
                with open(kavita_path, 'r', encoding='utf-8') as f:
                    data = yaml.safe_load(f)
                    m = data.get('meta', {}) or data.get('search', {})
                    if 'title' in m: title = normalize_nfc(m['title'])
                    meta_dict['summary'] = m.get('summary', meta_dict['summary'])
                    meta_dict['writers'] = m.get('writers', [])
                    meta_dict['genres'] = m.get('genres', [])
                    for key in ['image', 'thumbnail', 'cover', 'poster']:
                        if key in m and m[key]:
                            val = str(m[key]).strip()
                            if val.startswith(('http://', 'https://')): poster = val; break
                            poster = os.path.join(rel_path, val).replace(os.sep, '/'); break
            except: pass
        if not poster: poster = find_first_image_recursive(abs_path, depth_limit=4)
    else:
        poster = "zip_thumb://" + rel_path
        title = os.path.splitext(title)[0]
    if poster and not poster.startswith("http"):
        if poster.startswith("zip_thumb://"): poster = "zip_thumb://" + urllib.parse.quote(poster[12:], safe='/')
        else: poster = urllib.parse.quote(poster, safe='/')
    meta_dict['poster_url'] = poster
    return title, poster, json.dumps(meta_dict, ensure_ascii=False)

def scan_folder_sync(abs_path, series_depth):
    try:
        abs_path = normalize_nfc(abs_path)
        if not os.path.exists(abs_path): return []
        rel = normalize_nfc(os.path.relpath(abs_path, BASE_PATH).replace(os.sep, '/'))
        if rel == ".": rel = ""
        depth = get_depth(rel)
        scan_status["current_item"] = os.path.basename(abs_path)
        has_comic_files = False
        if os.path.isdir(abs_path):
            with os.scandir(abs_path) as it:
                for e in it:
                    if is_comic_file(e.name): has_comic_files = True; break
        is_series_level = (depth >= series_depth) or has_comic_files
        title, poster, meta = get_comic_info(abs_path, rel)
        item = (get_path_hash(abs_path), get_path_hash(os.path.dirname(abs_path)), abs_path, rel, os.path.basename(abs_path), 1 if os.path.isdir(abs_path) else 0, poster, title, depth, time.time(), meta)

        # 1. 현재 폴더 정보 저장
        conn = sqlite3.connect(METADATA_DB_PATH, timeout=20)
        conn.execute('INSERT OR REPLACE INTO entries VALUES (?,?,?,?,?,?,?,?,?,?,?)', item)

        # 2. 직계 하위 항목들 즉시 스캔 (브라우징을 위해)
        child_items = []
        if os.path.isdir(abs_path):
            with os.scandir(abs_path) as it:
                for e in it:
                    if not is_excluded(e.name) and (e.is_dir() or is_comic_file(e.name)):
                        e_rel = normalize_nfc(os.path.relpath(e.path, BASE_PATH).replace(os.sep, '/'))
                        # 하위 항목의 포스터는 일단 부모 포스터나 기본값으로 빠르게 설정 (상세 스캔은 나중에)
                        e_poster = "zip_thumb://" + urllib.parse.quote(e_rel, safe='/') if is_comic_file(e.name) else poster
                        child_items.append((get_path_hash(e.path), get_path_hash(abs_path), normalize_nfc(e.path), e_rel, normalize_nfc(e.name), 1 if e.is_dir() else 0, e_poster, normalize_nfc(os.path.splitext(e.name)[0]), depth + 1, time.time(), "{}"))
            if child_items:
                conn.executemany('INSERT OR REPLACE INTO entries VALUES (?,?,?,?,?,?,?,?,?,?,?)', child_items)

        conn.commit()
        conn.close()

        # 3. 비동기 전체 스캔 (하위 깊이까지)
        if not is_series_level and os.path.isdir(abs_path):
            for child in child_items:
                if child[5] == 1: # Directory
                    scan_status["total"] += 1
                    scanning_pool.submit(scan_task, child[2], series_depth)

        scan_status["processed"] += 1
        scan_status["success"] += 1
        return child_items
    except Exception as e:
        scan_status["failed"] += 1
        logger.error(f"Scan error in {abs_path}: {e}")
        return []

def scan_task(abs_path, series_depth):
    scan_folder_sync(abs_path, series_depth)
    if scan_status["processed"] >= scan_status["total"] > 0:
        scan_status["is_running"] = False
        add_web_log(f"{scan_status['current_type']} Completed!", "SUCCESS")

@app.route('/files')
def list_files():
    path = normalize_nfc(urllib.parse.unquote(request.args.get('path', '')))
    abs_p = get_abs_path(path)
    items = []
    if os.path.exists(abs_p) and os.path.isdir(abs_p):
        for e in os.scandir(abs_p):
            if not is_excluded(e.name) and (e.is_dir() or is_comic_file(e.name)):
                e_rel = normalize_nfc(os.path.relpath(e.path, BASE_PATH).replace(os.sep, '/'))
                items.append({'name': e.name, 'isDirectory': e.is_dir(), 'path': e_rel, 'metadata': {}})
    return jsonify(items)

@app.route('/scan')
def scan_comics():
    path = normalize_nfc(urllib.parse.unquote(request.args.get('path', '')))
    page = request.args.get('page', 1, type=int)
    psize = request.args.get('page_size', 50, type=int)
    conn = sqlite3.connect(METADATA_DB_PATH); conn.row_factory = sqlite3.Row
    placeholders = ','.join(['?'] * len(EXCLUDED_FOLDERS))
    abs_p = os.path.abspath(os.path.join(BASE_PATH, path)).replace(os.sep, '/')
    phash = get_path_hash(abs_p)
    if not path or path == "웹툰":
        query = f"SELECT * FROM entries WHERE depth = 3 AND rel_path LIKE '웹툰/%' AND name NOT IN ({placeholders}) AND name NOT LIKE 'kavita.yaml' ORDER BY title LIMIT ? OFFSET ?"
        params = EXCLUDED_FOLDERS + [psize, (page - 1) * psize]
        rows = conn.execute(query, params).fetchall()
        count_query = f"SELECT COUNT(*) FROM entries WHERE depth = 3 AND rel_path LIKE '웹툰/%' AND name NOT IN ({placeholders}) AND name NOT LIKE 'kavita.yaml'"
        total = conn.execute(count_query, EXCLUDED_FOLDERS).fetchone()[0]
    else:
        query = f"SELECT * FROM entries WHERE parent_hash = ? AND name NOT IN ({placeholders}) AND name NOT LIKE 'kavita.yaml' ORDER BY is_dir DESC, title LIMIT ? OFFSET ?"
        params = [phash] + EXCLUDED_FOLDERS + [psize, (page-1)*psize]
        rows = conn.execute(query, params).fetchall()
        count_query = f"SELECT COUNT(*) FROM entries WHERE parent_hash = ? AND name NOT IN ({placeholders}) AND name NOT LIKE 'kavita.yaml'"
        total = conn.execute(count_query, [phash] + EXCLUDED_FOLDERS).fetchone()[0]
        if not rows and page == 1:
            conn.close()
            real_abs_path = get_abs_path(path)
            if os.path.exists(real_abs_path):
                depth = 3
                if path.startswith("잡지"): depth = 2
                elif path.startswith("화보"): depth = 4
                elif path.startswith("책"): depth = 5
                scan_folder_sync(real_abs_path, depth)
                return scan_comics()
    items = []
    for r in rows:
        meta = json.loads(r['metadata'] or '{}'); meta['poster_url'] = r['poster_url']
        items.append({'name': r['title'] or r['name'], 'isDirectory': bool(r['is_dir']), 'path': r['rel_path'], 'metadata': meta})
    conn.close()
    return jsonify({'total_items': total, 'page': page, 'page_size': psize, 'items': items})

@app.route('/download')
def download():
    raw_path = request.args.get('path', '')
    p = normalize_nfc(urllib.parse.unquote(raw_path)).replace('+', ' ')
    if not p: return "Path required", 400
    if p.startswith("http"):
        try:
            req = urllib.request.Request(p, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=10) as response:
                return send_file(io.BytesIO(response.read()), mimetype=response.headers.get_content_type() or 'image/jpeg')
        except: return "Image Load Failed", 500
    if p.startswith("zip_thumb://"):
        rel_path = p[12:]
        cache_key = hashlib.md5(normalize_nfc(rel_path).encode('utf-8')).hexdigest() + ".jpg"
        cache_path = os.path.join(THUMB_CACHE_DIR, cache_key)
        if os.path.exists(cache_path): return send_from_directory(THUMB_CACHE_DIR, cache_key)
        azp = get_abs_path(rel_path)
        if azp.lower().endswith(('.pdf', '.epub')):
            if generate_file_thumbnail(azp, cache_path): return send_from_directory(THUMB_CACHE_DIR, cache_key)
        else:
            if generate_zip_thumbnail(azp, cache_path): return send_from_directory(THUMB_CACHE_DIR, cache_key)
        return "No Image", 404
    target_path = get_abs_path(p)
    return send_from_directory(os.path.dirname(target_path), os.path.basename(target_path))

@app.route('/zip_entries')
def zip_entries():
    path = urllib.parse.unquote_plus(request.args.get('path', ''))
    abs_p = get_abs_path(path)
    if not os.path.exists(abs_p): return "File Not Found", 404
    if abs_p.lower().endswith(('.pdf', '.epub')):
        if abs_p in doc_page_cache: return jsonify(doc_page_cache[abs_p])
        if HAS_FITZ:
            try:
                doc = fitz.open(abs_p)
                pages = [f"page_{i:04d}.jpg" for i in range(doc.page_count)]
                doc.close()
                doc_page_cache[abs_p] = pages
                return jsonify(pages)
            except: return jsonify([])
        return jsonify([])
    if os.path.isdir(abs_p): return jsonify(sorted([e.name for e in os.scandir(abs_p) if is_image_file(e.name)]))
    try:
        with zipfile.ZipFile(abs_p, 'r') as z: return jsonify(sorted([n for n in z.namelist() if is_image_file(n)]))
    except: return jsonify([])

@app.route('/download_zip_entry')
def download_zip_entry():
    path = urllib.parse.unquote_plus(request.args.get('path', ''))
    entry = urllib.parse.unquote_plus(request.args.get('entry', ''))
    abs_p = get_abs_path(path)
    if abs_p.lower().endswith(('.pdf', '.epub')) and entry.startswith("page_") and HAS_FITZ:
        try:
            page_idx = int(entry[5:9])
            doc = fitz.open(abs_p)
            page = doc.load_page(page_idx)
            pix = page.get_pixmap(matrix=fitz.Matrix(2.0, 2.0))
            img_data = pix.tobytes("jpg")
            doc.close()
            return send_file(io.BytesIO(img_data), mimetype='image/jpeg')
        except: return "Error", 500
    if os.path.isdir(abs_p): return send_from_directory(abs_p, entry)
    try:
        with zipfile.ZipFile(abs_p, 'r') as z:
            with z.open(entry) as f: return send_file(io.BytesIO(f.read()), mimetype='image/jpeg')
    except: return "Error", 500

@app.route('/metadata')
def get_metadata():
    path = normalize_nfc(urllib.parse.unquote(request.args.get('path', '')))
    if not path: return jsonify({})
    real_abs_path = get_abs_path(path)
    phash = get_path_hash(real_abs_path)
    conn = sqlite3.connect(METADATA_DB_PATH); conn.row_factory = sqlite3.Row
    row = conn.execute("SELECT * FROM entries WHERE path_hash = ?", (phash,)).fetchone()
    if not row:
        depth = 3
        if path.startswith("잡지"): depth = 2
        elif path.startswith("화보"): depth = 4
        elif path.startswith("책"): depth = 5
        scan_folder_sync(real_abs_path, depth)
        row = conn.execute("SELECT * FROM entries WHERE path_hash = ?", (phash,)).fetchone()
    if not row: conn.close(); return jsonify({})
    meta = json.loads(row['metadata'] or '{}')
    meta['poster_url'] = row['poster_url']; meta['title'] = normalize_nfc(row['title'] or row['name'])
    placeholders = ','.join(['?'] * len(EXCLUDED_FOLDERS))
    query = f"SELECT * FROM entries WHERE parent_hash = ? AND name NOT IN ({placeholders}) AND name NOT LIKE 'kavita.yaml' ORDER BY name"
    ep_rows = conn.execute(query, [phash] + EXCLUDED_FOLDERS).fetchall()
    chapters = []
    if ep_rows:
        for ep in ep_rows:
            if is_comic_file(ep['name']) or ep['is_dir'] == 1:
                chapters.append({'name': ep['title'] or ep['name'], 'isDirectory': bool(ep['is_dir'] == 1), 'path': ep['rel_path'], 'metadata': {'poster_url': ep['poster_url'] or row['poster_url']}})
    if not chapters and is_comic_file(row['name']):
        chapters.append({'name': meta['title'], 'isDirectory': False, 'path': row['rel_path'], 'metadata': {'poster_url': row['poster_url']}})
    meta['chapters'] = chapters
    conn.close()
    return jsonify(meta)

if __name__ == '__main__':
    init_db()
    app.run(host='0.0.0.0', port=5556, threaded=True)
