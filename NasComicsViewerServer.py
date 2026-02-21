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

# 허용된 상단 카테고리 목록
ALLOWED_CATEGORIES = ["완결A", "완결B", "마블", "번역", "연재", "작가"]
# 초성 건너뛰기가 필요한 카테고리
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
            conn.executemany('INSERT OR REPLACE INTO entries VALUES (?,?,?,?,?,?,?,?,?,?)', items)
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
            poster_url TEXT, title TEXT, depth INTEGER, last_scanned REAL
        )''')
        conn.execute('CREATE INDEX IF NOT EXISTS idx_parent ON entries(parent_hash)')
        conn.execute('CREATE INDEX IF NOT EXISTS idx_depth ON entries(depth)')
    conn.close()

# --- 정보 추출 및 스캔 ---
def is_comic_file(name):
    return name.lower().endswith(('.zip', '.cbz', '.rar', '.cbr', '.pdf'))

def get_comic_info(abs_path, rel_path):
    title = normalize_nfc(os.path.basename(abs_path))
    poster = None
    if os.path.isdir(abs_path):
        try:
            with os.scandir(abs_path) as it:
                ents = sorted(list(it), key=lambda x: x.name)
                for e in ents:
                    if e.name.lower().endswith(('.jpg', '.jpeg', '.png', '.webp')):
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

    # [중요] URL에 특수문자(# 등)가 포함된 경우를 대비해 인코딩 처리
    if poster:
        if poster.startswith("zip_thumb://"):
            poster = "zip_thumb://" + urllib.parse.quote(poster[12:])
        else:
            poster = urllib.parse.quote(poster)

    return title, poster

def scan_folder_sync(abs_path, recursive_depth=0):
    """즉시 스캔 후 결과를 반환하는 동기 함수 (첫 로딩용)"""
    abs_path = os.path.abspath(abs_path).replace(os.sep, '/')
    rel_from_root = os.path.relpath(abs_path, BASE_PATH).replace(os.sep, '/')

    # 보안 필터링
    if rel_from_root != "." and "/" not in rel_from_root:
        if normalize_nfc(rel_from_root) not in [normalize_nfc(c) for c in ALLOWED_CATEGORIES]:
            return []

    phash = get_path_hash(abs_path)
    root = os.path.abspath(BASE_PATH).replace(os.sep, '/')
    items = []
    try:
        with os.scandir(abs_path) as it:
            for e in it:
                if e.is_dir() or is_comic_file(e.name):
                    e_name = normalize_nfc(e.name)
                    if rel_from_root == "." and e_name not in [normalize_nfc(c) for c in ALLOWED_CATEGORIES]:
                        continue

                    e_abs = os.path.abspath(e.path).replace(os.sep, '/')
                    rel = os.path.relpath(e_abs, root).replace(os.sep, '/')
                    title, poster = get_comic_info(e_abs, rel)
                    items.append((get_path_hash(e_abs), phash, e_abs, rel, e_name,
                                  1 if e.is_dir() else 0, poster, title, get_depth(rel), time.time()))
        if items:
            # 즉시 DB 쓰기 (큐를 거치지 않음 - 첫 로딩 속도 보장)
            conn = sqlite3.connect(METADATA_DB_PATH)
            conn.executemany('INSERT OR REPLACE INTO entries VALUES (?,?,?,?,?,?,?,?,?,?)', items)
            conn.commit()
            conn.close()

            if recursive_depth > 0:
                for item in items:
                    if item[5] == 1: # is_dir
                        scan_folder_sync(item[2], recursive_depth - 1)
    except: pass
    return items

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

    # 평탄화: 카테고리 진입 시 깊이 3(만화)만 검색
    if is_flatten_cat and get_depth(rel_path) == 1:
        rows = conn.execute("""
            SELECT * FROM entries WHERE rel_path LIKE ? AND depth = 3
            ORDER BY title LIMIT ? OFFSET ?
        """, (path + '/%' if path else '%', psize, (page-1)*psize)).fetchall()

        if not rows and page == 1:
            conn.close()
            scan_folder_sync(abs_p, recursive_depth=1) # 즉시 2단계 아래까지 훑음
            return scan_comics()
    else:
        parent_hash = get_path_hash(abs_p)
        rows = conn.execute("SELECT * FROM entries WHERE parent_hash = ? ORDER BY name LIMIT ? OFFSET ?",
                            (parent_hash, psize, (page-1)*psize)).fetchall()
        if not rows and page == 1:
            conn.close()
            scan_folder_sync(abs_p, recursive_depth=0)
            return scan_comics()

    items = [{'name': r['title'], 'isDirectory': bool(r['is_dir']), 'path': r['rel_path'],
              'metadata': {'title': r['title'], 'poster_url': r['poster_url']}} for r in rows]
    conn.close()
    return jsonify({'total_items': 10000, 'page': page, 'page_size': psize, 'items': items})

@app.route('/files')
def list_files():
    path = request.args.get('path', '')
    abs_p = os.path.abspath(os.path.join(BASE_PATH, path)).replace(os.sep, '/')
    parent_hash = get_path_hash(abs_p)
    conn = sqlite3.connect(METADATA_DB_PATH)
    conn.row_factory = sqlite3.Row
    rows = conn.execute("SELECT * FROM entries WHERE parent_hash = ? ORDER BY name", (parent_hash,)).fetchall()
    conn.close()
    if not rows: scan_folder_sync(abs_p, 0)
    res = [{'name': r['name'], 'isDirectory': bool(r['is_dir']), 'path': r['rel_path']} for r in rows]
    return jsonify(res)

@app.route('/download')
def download():
    p = request.args.get('path', '')
    # URL 인코딩된 경로를 다시 되돌림
    p = urllib.parse.unquote(p)

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
                imgs = sorted([n for n in z.namelist() if n.lower().endswith(('.jpg', '.jpeg', '.png'))])
                if imgs:
                    with z.open(imgs[0]) as f: return send_file(io.BytesIO(f.read()), mimetype='image/jpeg')
        except: pass
        return "No Image", 404

    full_path = os.path.join(BASE_PATH, p)
    return send_from_directory(os.path.dirname(full_path), os.path.basename(full_path))

@app.route('/check_db')
def check_db():
    conn = sqlite3.connect(METADATA_DB_PATH)
    conn.row_factory = sqlite3.Row
    rows = conn.execute("SELECT * FROM entries ORDER BY last_scanned DESC LIMIT 100").fetchall()
    conn.close()
    return jsonify([dict(r) for r in rows])

@app.route('/debug_db')
def debug_db():
    conn = sqlite3.connect(METADATA_DB_PATH)
    count = conn.execute("SELECT COUNT(*) FROM entries").fetchone()[0]
    conn.close()
    return jsonify({'total_count': count, 'queue': db_queue.qsize()})

if __name__ == '__main__':
    init_db()
    # 시작 시 허용된 카테고리만 가볍게 백그라운드 스캔
    for cat in ALLOWED_CATEGORIES:
        scanning_pool.submit(scan_folder_sync, os.path.join(BASE_PATH, cat), 1)
    app.run(host='0.0.0.0', port=5555, threaded=True)