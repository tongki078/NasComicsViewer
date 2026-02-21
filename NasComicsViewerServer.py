from flask import Flask, jsonify, send_from_directory, request, send_file, Response, render_template_string
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
FLATTEN_CATEGORIES = ["완결A", "완결B", "번역", "연재", "작가"]

db_queue = queue.Queue()
scanning_pool = ThreadPoolExecutor(max_workers=10)

def normalize_nfc(s):
    return unicodedata.normalize('NFC', str(s)) if s else ""

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
        except Exception as e: logger.error(f"DB Write Error: {e}")
        finally: db_queue.task_done()

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
        try: conn.execute("ALTER TABLE entries ADD COLUMN metadata TEXT")
        except: pass
    conn.close()

# --- 정보 추출 엔진 ---
def is_comic_file(name):
    return name.lower().endswith(('.zip', '.cbz', '.rar', '.cbr', '.pdf'))

def is_image_file(name):
    return name.lower().endswith(('.jpg', '.jpeg', '.png', '.webp'))

def get_comic_info(abs_path, rel_path):
    title = normalize_nfc(os.path.basename(abs_path))
    poster = None
    meta_dict = {
        "summary": "줄거리 정보가 없습니다.",
        "writers": [],
        "genres": [],
        "status": "Unknown",
        "publisher": ""
    }

    if os.path.isdir(abs_path):
        yaml_path = os.path.join(abs_path, "kavita.yaml")
        if os.path.exists(yaml_path):
            try:
                with open(yaml_path, 'r', encoding='utf-8') as f:
                    data = yaml.safe_load(f)
                    if data:
                        if 'poster' in data: poster = (rel_path + "/" + data['poster']).replace("//", "/")
                        meta_dict['summary'] = data.get('summary', meta_dict['summary'])
                        meta_dict['writers'] = data.get('writers', []) if isinstance(data.get('writers'), list) else [data.get('writers')] if data.get('writers') else []
                        meta_dict['genres'] = data.get('genres', []) if isinstance(data.get('genres'), list) else [data.get('genres')] if data.get('genres') else []
                        meta_dict['status'] = data.get('status', 'Unknown')
                        meta_dict['publisher'] = data.get('publisher', '')
            except: pass

        if not poster:
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
        title = os.path.splitext(title)[0]

    if poster:
        if poster.startswith("zip_thumb://"):
            poster = "zip_thumb://" + urllib.parse.quote(poster[12:])
        else:
            poster = urllib.parse.quote(poster)

    return title, poster, json.dumps(meta_dict, ensure_ascii=False)

def scan_folder_sync(abs_path, recursive_depth=0):
    abs_path = os.path.abspath(abs_path).replace(os.sep, '/')
    rel_from_root = os.path.relpath(abs_path, BASE_PATH).replace(os.sep, '/')
    if rel_from_root != "." and "/" not in rel_from_root:
        if normalize_nfc(rel_from_root) not in [normalize_nfc(c) for c in ALLOWED_CATEGORIES]: return []

    phash = get_path_hash(abs_path)
    root = os.path.abspath(BASE_PATH).replace(os.sep, '/')
    items = []
    try:
        with os.scandir(abs_path) as it:
            for e in it:
                if e.is_dir() or is_comic_file(e.name):
                    e_abs = os.path.abspath(e.path).replace(os.sep, '/')
                    rel = os.path.relpath(e_abs, root).replace(os.sep, '/')
                    title, poster, meta_json = get_comic_info(e_abs, rel)
                    items.append((get_path_hash(e_abs), phash, e_abs, rel, normalize_nfc(e.name),
                                  1 if e.is_dir() else 0, poster, title, get_depth(rel), time.time(), meta_json))
        if items:
            conn = sqlite3.connect(METADATA_DB_PATH)
            conn.executemany('INSERT OR REPLACE INTO entries VALUES (?,?,?,?,?,?,?,?,?,?,?)', items)
            conn.commit()
            conn.close()
            if recursive_depth > 0:
                for item in items:
                    if item[5] == 1: scan_folder_sync(item[2], recursive_depth - 1)
    except: pass
    return items

# --- API ---
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
    page = request.args.get('page', 1, type=int)
    psize = request.args.get('page_size', 50, type=int)
    abs_p = os.path.abspath(os.path.join(BASE_PATH, path)).replace(os.sep, '/')
    rel_path = os.path.relpath(abs_p, BASE_PATH).replace(os.sep, '/')
    cat_name = rel_path.split('/')[0]
    is_flatten_cat = any(normalize_nfc(f).lower() == normalize_nfc(cat_name).lower() for f in FLATTEN_CATEGORIES)

    conn = sqlite3.connect(METADATA_DB_PATH)
    conn.row_factory = sqlite3.Row

    # 1. 목록 표시 로직
    if is_flatten_cat and get_depth(rel_path) == 1:
        # 카테고리 뷰 (만화 폴더들)
        rows = conn.execute("SELECT * FROM entries WHERE rel_path LIKE ? AND depth = 3 ORDER BY title LIMIT ? OFFSET ?", (path + '/%' if path else '%', psize, (page-1)*psize)).fetchall()
        if not rows and page == 1:
            conn.close(); scan_folder_sync(abs_p, 1); return scan_comics()
    else:
        # 일반 폴더 뷰 (상세 페이지 내부 권수 리스트 등)
        parent_hash = get_path_hash(abs_p)
        rows = conn.execute("SELECT * FROM entries WHERE parent_hash = ? ORDER BY name LIMIT ? OFFSET ?", (parent_hash, psize, (page-1)*psize)).fetchall()
        if not rows and page == 1:
            conn.close(); scan_folder_sync(abs_p, 0); return scan_comics()

    items = []
    for r in rows:
        meta = json.loads(r['metadata'] or '{}')
        meta['poster_url'] = r['poster_url']
        meta['title'] = r['title']
        items.append({
            'name': r['title'],
            'isDirectory': bool(r['is_dir']),
            'path': r['rel_path'],
            'metadata': meta
        })
    conn.close()
    return jsonify({'total_items': 10000, 'page': page, 'page_size': psize, 'items': items})

@app.route('/metadata')
def get_metadata():
    path = request.args.get('path', '')
    abs_p = os.path.abspath(os.path.join(BASE_PATH, path)).replace(os.sep, '/')
    phash = get_path_hash(abs_p)
    conn = sqlite3.connect(METADATA_DB_PATH)
    conn.row_factory = sqlite3.Row
    row = conn.execute("SELECT * FROM entries WHERE path_hash = ?", (phash,)).fetchone()

    # 상세 페이지용 하위 아이템(권수) 리스트도 미리 긁어옴
    children = conn.execute("SELECT * FROM entries WHERE parent_hash = ? ORDER BY name", (phash,)).fetchall()
    if not children:
        scan_folder_sync(abs_p, 0)
        children = conn.execute("SELECT * FROM entries WHERE parent_hash = ? ORDER BY name", (phash,)).fetchall()

    conn.close()
    if not row: return jsonify({"error": "Not found"}), 404

    meta = json.loads(row['metadata'] or '{}')
    meta['title'] = row['title']
    meta['poster_url'] = row['poster_url']
    meta['rel_path'] = row['rel_path']
    # 하위 권수 리스트 추가 (상세화면 하단 UI용)
    meta['chapters'] = [{
        'name': c['name'],
        'path': c['rel_path'],
        'isDirectory': bool(c['is_dir']),
        'metadata': {'poster_url': c['poster_url'], 'title': c['name']}
    } for c in children]

    return jsonify(meta)

@app.route('/zip_entries')
def zip_entries():
    path = urllib.parse.unquote(request.args.get('path', ''))
    abs_p = os.path.abspath(os.path.join(BASE_PATH, path)).replace(os.sep, '/')
    if not os.path.isfile(abs_p): return jsonify([])
    try:
        with zipfile.ZipFile(abs_p, 'r') as z:
            imgs = sorted([n for n in z.namelist() if is_image_file(n)])
            return jsonify(imgs)
    except: return jsonify([])

@app.route('/download_zip_entry')
def download_zip_entry():
    path = urllib.parse.unquote(request.args.get('path', ''))
    entry = urllib.parse.unquote(request.args.get('entry', ''))
    abs_p = os.path.abspath(os.path.join(BASE_PATH, path)).replace(os.sep, '/')
    if not os.path.isfile(abs_p): return "No Zip", 404
    try:
        with zipfile.ZipFile(abs_p, 'r') as z:
            # Entry name might need normalization
            target = entry
            if target not in z.namelist():
                # Try normalization match
                norm_target = normalize_nfc(target)
                for name in z.namelist():
                    if normalize_nfc(name) == norm_target:
                        target = name
                        break
            with z.open(target) as f:
                return send_file(io.BytesIO(f.read()), mimetype='image/jpeg')
    except: return "Error", 500

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
            except: pass
        try:
            with zipfile.ZipFile(azp, 'r') as z:
                imgs = sorted([n for n in z.namelist() if is_image_file(n)])
                if imgs:
                    with z.open(imgs[0]) as f: return send_file(io.BytesIO(f.read()), mimetype='image/jpeg')
        except: pass
        return "No Image", 404

    full_path = os.path.abspath(os.path.join(BASE_PATH, p)).replace(os.sep, '/')
    if not full_path.startswith(os.path.abspath(BASE_PATH).replace(os.sep, '/')):
        return "Access Denied", 403
    return send_from_directory(os.path.dirname(full_path), os.path.basename(full_path))

if __name__ == '__main__':
    init_db()
    for cat in ALLOWED_CATEGORIES:
        scanning_pool.submit(scan_folder_sync, os.path.join(BASE_PATH, cat), 1)
    app.run(host='0.0.0.0', port=5555, threaded=True)
