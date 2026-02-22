from flask import Flask, jsonify, send_from_directory, request, send_file, Response, render_template_string, stream_with_context, redirect
import os, urllib.parse, unicodedata, logging, time, zipfile, io, sys, sqlite3, json, threading, hashlib, yaml, queue
from concurrent.futures import ThreadPoolExecutor

# [로그 설정]
logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s [%(name)s] %(message)s', stream=sys.stdout)
logger = logging.getLogger("NasWebtoonServer")

app = Flask(__name__)

# --- 설정 ---
BASE_PATH = "/volume2/video/GDS3/GDRIVE/READING/웹툰"
METADATA_DB_PATH = '/volume2/video/NasWebtoonViewer.db'

# 웹툰은 카테고리 구분 없이 전체를 스캔 대상으로 잡습니다.
ALLOWED_CATEGORIES = ["가", "나", "다", "라", "마", "바", "사", "아", "자", "차", "카", "타", "파", "하", "기타", "0Z", "A-Z"]

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
        except Exception as e: logger.error("DB Write Error: " + str(e))
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
        conn.execute('CREATE INDEX IF NOT EXISTS idx_parent ON entries(parent_hash)')
        conn.execute('CREATE INDEX IF NOT EXISTS idx_title ON entries(title)')
        conn.execute('CREATE INDEX IF NOT EXISTS idx_rel ON entries(rel_path)')
        conn.execute('CREATE INDEX IF NOT EXISTS idx_depth ON entries(depth)')
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
        # 1. series.json 확인 (웹툰 형식 최우선)
        json_path = os.path.join(abs_path, "series.json")
        if os.path.exists(json_path):
            try:
                with open(json_path, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                    if data:
                        if 'image' in data:
                            poster = data['image'] # HTTP URL
                        if 'title' in data:
                            title = normalize_nfc(data['title'])
                        meta_dict['summary'] = data.get('description', data.get('summary', meta_dict['summary']))
                        meta_dict['status'] = data.get('status', 'Unknown')

                        authors = data.get('author', [])
                        meta_dict['writers'] = [authors] if isinstance(authors, str) else authors

                        genres = data.get('genre', [])
                        meta_dict['genres'] = [genres] if isinstance(genres, str) else genres
            except Exception as e:
                logger.error(f"Json parsing error in {abs_path}: {e}")

        # 2. kavita.yaml 확인
        if not poster:
            yaml_path = os.path.join(abs_path, "kavita.yaml")
            if os.path.exists(yaml_path):
                try:
                    with open(yaml_path, 'r', encoding='utf-8') as f:
                        data = yaml.safe_load(f)
                        if data:
                            if 'files' in data and isinstance(data['files'], dict) and 'cover' in data['files']:
                                poster = (rel_path + "/" + data['files']['cover']).replace("//", "/")
                            elif 'poster' in data:
                                poster = (rel_path + "/" + data['poster']).replace("//", "/")
                            meta_dict['summary'] = data.get('summary', data.get('description', meta_dict['summary']))
                except: pass

        # 3. 직접 파일 스캔
        if not poster:
            try:
                with os.scandir(abs_path) as it:
                    ents = sorted(list(it), key=lambda x: x.name)
                    for e in ents:
                        if is_image_file(e.name): poster = (rel_path + "/" + e.name).replace("//", "/"); break
                    if not poster:
                        for e in ents:
                            if is_comic_file(e.name): poster = "zip_thumb://" + (rel_path + "/" + e.name).replace("//", "/"); break
            except: pass
    else:
        poster = "zip_thumb://" + rel_path
        title = os.path.splitext(title)[0]

    if poster and not poster.startswith("http"):
        if poster.startswith("zip_thumb://"):
            poster = "zip_thumb://" + urllib.parse.quote(poster[12:])
        else:
            poster = urllib.parse.quote(poster)

    return title, poster, json.dumps(meta_dict, ensure_ascii=False)

def scan_folder_sync(abs_path, recursive_depth=0):
    abs_path = os.path.abspath(abs_path).replace(os.sep, '/')
    root = os.path.abspath(BASE_PATH).replace(os.sep, '/')
    rel_from_root = os.path.relpath(abs_path, BASE_PATH).replace(os.sep, '/')

    # 본인 정보 저장
    title, poster, meta_json = get_comic_info(abs_path, rel_from_root)
    p_abs = os.path.dirname(abs_path)
    p_hash = get_path_hash(p_abs)
    item = (get_path_hash(abs_path), p_hash, abs_path, rel_from_root, os.path.basename(abs_path), 1 if os.path.isdir(abs_path) else 0, poster, title, get_depth(rel_from_root), time.time(), meta_json)
    db_queue.put([item])

    # 하위 스캔
    items = []
    try:
        phash = get_path_hash(abs_path)
        with os.scandir(abs_path) as it:
            for e in it:
                if e.is_dir() or is_comic_file(e.name):
                    e_abs = os.path.abspath(e.path).replace(os.sep, '/')
                    rel = os.path.relpath(e_abs, root).replace(os.sep, '/')
                    title, poster, meta_json = get_comic_info(e_abs, rel)
                    items.append((get_path_hash(e_abs), phash, e_abs, rel, normalize_nfc(e.name), 1 if e.is_dir() else 0, poster, title, get_depth(rel), time.time(), meta_json))
        if items:
            db_queue.put(items)
            if recursive_depth > 0:
                for sub in items:
                    if sub[5] == 1: scan_folder_sync(sub[2], recursive_depth - 1)
    except: pass
    return items

@app.route('/categories')
def get_categories():
    # 웹툰 모드에서는 상단 탭 필터링을 없애기 위해 빈 리스트를 반환하거나
    # 혹은 "전체" 하나만 반환하도록 하여 UI에서 탭이 안 나오게 유도합니다.
    return jsonify([])

@app.route('/scan')
def scan_comics():
    path = request.args.get('path', '')
    page = request.args.get('page', 1, type=int); psize = request.args.get('page_size', 50, type=int)

    conn = sqlite3.connect(METADATA_DB_PATH); conn.row_factory = sqlite3.Row

    if not path:
        # 웹툰 모드 메인: Depth 2인 항목(작품 폴더)들만 한 번에 리스트로 추출
        rows = conn.execute("SELECT * FROM entries WHERE depth = 2 ORDER BY title LIMIT ? OFFSET ?", (psize, (page-1)*psize)).fetchall()
        # 데이터가 아예 없으면 초기 스캔 시도 (상위 카테고리 기반)
        if not rows and page == 1:
            conn.close()
            for cat in ALLOWED_CATEGORIES:
                scan_folder_sync(os.path.join(BASE_PATH, cat), 1)
            return scan_comics()
    else:
        # 특정 폴더 진입시
        abs_p = os.path.abspath(os.path.join(BASE_PATH, path)).replace(os.sep, '/')
        phash = get_path_hash(abs_p)
        rows = conn.execute("SELECT * FROM entries WHERE parent_hash = ? ORDER BY name LIMIT ? OFFSET ?", (phash, psize, (page-1)*psize)).fetchall()
        if not rows and page == 1:
            conn.close()
            scan_folder_sync(abs_p, 0)
            return scan_comics()

    items = []
    for r in rows:
        meta = json.loads(r['metadata'] or '{}')
        meta['poster_url'] = r['poster_url']
        meta['title'] = r['title']
        items.append({'name': r['title'] or r['name'], 'isDirectory': bool(r['is_dir']), 'path': r['rel_path'], 'metadata': meta})
    conn.close()
    return jsonify({'total_items': 10000, 'page': page, 'page_size': psize, 'items': items})

@app.route('/files')
def list_files():
    path = request.args.get('path', '')
    if not path: return get_categories()

    abs_p = os.path.abspath(os.path.join(BASE_PATH, path)).replace(os.sep, '/')
    phash = get_path_hash(abs_p)
    conn = sqlite3.connect(METADATA_DB_PATH); conn.row_factory = sqlite3.Row
    rows = conn.execute("SELECT * FROM entries WHERE parent_hash = ? ORDER BY name", (phash,)).fetchall()
    conn.close()
    return jsonify([{'name': r['name'], 'isDirectory': bool(r['is_dir']), 'path': r['rel_path']} for r in rows])

@app.route('/search')
def search_comics():
    query = request.args.get('query', '').strip()
    if not query: return jsonify({'total_items': 0, 'page': 1, 'page_size': 50, 'items': []})
    page = request.args.get('page', 1, type=int); psize = request.args.get('page_size', 50, type=int)

    conn = sqlite3.connect(METADATA_DB_PATH); conn.row_factory = sqlite3.Row
    q = f"%{query}%"
    sql = "SELECT * FROM entries WHERE (title LIKE ? OR name LIKE ?) AND depth >= 2 ORDER BY last_scanned DESC LIMIT ? OFFSET ?"
    rows = conn.execute(sql, (q, q, psize, (page-1)*psize)).fetchall()

    items = []
    for r in rows:
        meta = json.loads(r['metadata'] or '{}')
        meta['poster_url'] = r['poster_url']; meta['title'] = r['title']
        items.append({'name': r['title'] or r['name'], 'isDirectory': bool(r['is_dir']), 'path': r['rel_path'], 'metadata': meta})
    conn.close()
    return jsonify({'total_items': 100, 'page': page, 'page_size': psize, 'items': items})

@app.route('/metadata')
def get_metadata():
    path = request.args.get('path') or request.args.get('url')
    if not path: return jsonify({"error": "No path"})
    path = normalize_nfc(path); abs_p = os.path.abspath(os.path.join(BASE_PATH, path)).replace(os.sep, '/')
    phash = get_path_hash(abs_p); conn = sqlite3.connect(METADATA_DB_PATH); conn.row_factory = sqlite3.Row
    row = conn.execute("SELECT * FROM entries WHERE path_hash = ?", (phash,)).fetchone()
    if not row:
        conn.close(); return jsonify({"error": "Not found"}), 404
    children = conn.execute("SELECT * FROM entries WHERE parent_hash = ? ORDER BY name", (phash,)).fetchall()
    conn.close()
    meta = json.loads(row['metadata'] or '{}'); meta['title'] = row['title']; meta['poster_url'] = row['poster_url']; meta['rel_path'] = row['rel_path']
    meta['chapters'] = [{'name': c['name'], 'path': c['rel_path'], 'isDirectory': bool(c['is_dir']), 'metadata': {'poster_url': c['poster_url'], 'title': c['name']}} for c in children]
    return jsonify(meta)

@app.route('/download')
def download():
    p = urllib.parse.unquote(request.args.get('path', ''))
    if p.startswith("http"): return redirect(p) # 외부 이미지 리다이렉트
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

if __name__ == '__main__':
    init_db()
    app.run(host='0.0.0.0', port=5556, threaded=True)
