from flask import Flask, jsonify, send_from_directory, request, send_file, Response, render_template_string, stream_with_context, redirect
import os, urllib.parse, unicodedata, logging, time, zipfile, io, sys, sqlite3, json, threading, hashlib, yaml, queue
from concurrent.futures import ThreadPoolExecutor

# [로그 설정] - 실시간 모니터링을 위해 출력 강화
logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s [%(name)s] %(message)s', stream=sys.stdout)
logger = logging.getLogger("NasWebtoonServer")

app = Flask(__name__)

# --- 설정 ---
BASE_PATH = "/volume2/video/GDS3/GDRIVE/READING/웹툰"
METADATA_DB_PATH = '/volume2/video/NasWebtoonViewer.db'

# 스캔할 자음 폴더 목록
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
    if not rel_path or rel_path == "." or rel_path == "": return 0
    return len(rel_path.strip('/').split('/'))

# --- DB 엔진 ---
def db_writer_worker():
    logger.info(f"DB Writer Worker started. DB Path: {METADATA_DB_PATH}")
    conn = sqlite3.connect(METADATA_DB_PATH, timeout=60)
    conn.execute('PRAGMA journal_mode=WAL;')
    conn.execute('PRAGMA synchronous=NORMAL;')
    while True:
        items = db_queue.get()
        if items is None: break
        try:
            conn.executemany('INSERT OR REPLACE INTO entries VALUES (?,?,?,?,?,?,?,?,?,?,?)', items)
            conn.commit()
            # logger.info(f"DB Inserted {len(items)} items")
        except Exception as e:
            logger.error("DB Write Error: " + str(e))
        finally:
            db_queue.task_done()

threading.Thread(target=db_writer_worker, daemon=True).start()

def init_db():
    logger.info("Initializing Database...")
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
    logger.info("Database Initialized.")

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
                        # [DEBUG] series.json 발견 및 로드
                        logger.info(f"Found series.json in: {abs_path}")

                        # 젤 바깥 노드의 image 필드를 포스터로 사용
                        if 'image' in data:
                            poster = data['image']
                            logger.info(f"  > Poster URL found in series.json: {poster}")

                        if 'title' in data:
                            title = normalize_nfc(data['title'])

                        meta_dict['summary'] = data.get('description', data.get('summary', meta_dict['summary']))

                        # 작가 (author)
                        authors = data.get('author', [])
                        if isinstance(authors, str): meta_dict['writers'] = [authors]
                        elif isinstance(authors, list): meta_dict['writers'] = authors

                        # 장르 (genre)
                        genres = data.get('genre', [])
                        if isinstance(genres, str): meta_dict['genres'] = [genres]
                        elif isinstance(genres, list): meta_dict['genres'] = genres

                        meta_dict['status'] = data.get('status', 'Unknown')
            except Exception as e:
                logger.error(f"Json parsing error in {abs_path}: {e}")

        # 2. kavita.yaml 확인 (만약 series.json에서 포스터를 못 찾았을 때만)
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

                            if meta_dict['summary'] == "줄거리 정보가 없습니다.":
                                meta_dict['summary'] = data.get('summary', data.get('description', meta_dict['summary']))
                except: pass

        # 3. 직접 파일 스캔 (썸네일이 여전히 없는 경우)
        if not poster:
            try:
                with os.scandir(abs_path) as it:
                    ents = sorted(list(it), key=lambda x: x.name)
                    # 이미지 파일 우선
                    for e in ents:
                        if is_image_file(e.name):
                            poster = (rel_path + "/" + e.name).replace("//", "/")
                            break
                    # 압축 파일 내 이미지 확인
                    if not poster:
                        for e in ents:
                            if is_comic_file(e.name):
                                poster = "zip_thumb://" + (rel_path + "/" + e.name).replace("//", "/")
                                break
                    # 하위 폴더 내 이미지 확인 (에피소드 폴더가 있는 경우)
                    if not poster:
                        for e in ents:
                            if e.is_dir():
                                with os.scandir(e.path) as it2:
                                    ents2 = sorted(list(it2), key=lambda x: x.name)
                                    for e2 in ents2:
                                        if is_image_file(e2.name):
                                            poster = (rel_path + "/" + e.name + "/" + e2.name).replace("//", "/")
                                            break
                                if poster: break
            except: pass
    else:
        # 파일인 경우 (에피소드)
        poster = "zip_thumb://" + rel_path
        title = os.path.splitext(title)[0]

    # 포스터 경로 처리 (URL 인코딩)
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
    if rel_from_root == ".": rel_from_root = ""

    # 본인 정보 추출
    title, poster, meta_json = get_comic_info(abs_path, rel_from_root)
    p_hash = get_path_hash(os.path.dirname(abs_path))
    item = (get_path_hash(abs_path), p_hash, abs_path, rel_from_root, os.path.basename(abs_path), 1 if os.path.isdir(abs_path) else 0, poster, title, get_depth(rel_from_root), time.time(), meta_json)
    db_queue.put([item])

    items = []
    try:
        phash = get_path_hash(abs_path)
        with os.scandir(abs_path) as it:
            for e in it:
                if e.is_dir() or is_comic_file(e.name):
                    e_abs = os.path.abspath(e.path).replace(os.sep, '/')
                    rel = os.path.relpath(e_abs, root).replace(os.sep, '/')
                    # 하위 항목들도 정보 추출 (포스터 등)
                    title, poster, meta_json = get_comic_info(e_abs, rel)
                    items.append((get_path_hash(e_abs), phash, e_abs, rel, normalize_nfc(e.name), 1 if e.is_dir() else 0, poster, title, get_depth(rel), time.time(), meta_json))
        if items:
            db_queue.put(items)
            if recursive_depth > 0:
                for sub in items:
                    if sub[5] == 1: # Directory
                        # 백그라운드 스레드 풀을 사용하여 재귀적으로 스캔
                        scanning_pool.submit(scan_folder_sync, sub[2], recursive_depth - 1)
    except Exception as e:
        logger.error(f"Scan error in {abs_path}: {e}")
    return items

def trigger_initial_scan():
    logger.info("Triggering background initial scan for all categories...")
    for cat in ALLOWED_CATEGORIES:
        cat_path = os.path.join(BASE_PATH, cat)
        if os.path.exists(cat_path):
            scanning_pool.submit(scan_folder_sync, cat_path, 1)

@app.route('/categories')
def get_categories():
    logger.info("API CALL: /categories")
    return jsonify([])

@app.route('/scan')
def scan_comics():
    path = request.args.get('path', '')
    page = request.args.get('page', 1, type=int)
    psize = request.args.get('page_size', 50, type=int)

    logger.info(f"API CALL: /scan (path='{path}', page={page})")

    conn = sqlite3.connect(METADATA_DB_PATH)
    conn.row_factory = sqlite3.Row

    if not path:
        # 루트: Depth 2인 항목(작품 폴더)들만 반환
        rows = conn.execute("SELECT * FROM entries WHERE depth = 2 ORDER BY title LIMIT ? OFFSET ?", (psize, (page-1)*psize)).fetchall()
        if not rows and page == 1:
            logger.info("No data in DB. Starting background scan...")
            trigger_initial_scan()
            # 즉시 빈 결과를 반환하여 클라이언트가 대기하지 않게 함
            conn.close()
            return jsonify({'total_items': 0, 'page': page, 'page_size': psize, 'items': []})
    else:
        abs_p = os.path.abspath(os.path.join(BASE_PATH, path)).replace(os.sep, '/')
        phash = get_path_hash(abs_p)
        rows = conn.execute("SELECT * FROM entries WHERE parent_hash = ? ORDER BY name LIMIT ? OFFSET ?", (phash, psize, (page-1)*psize)).fetchall()
        if not rows and page == 1:
            logger.info(f"No children found for path '{path}'. Scanning folder in background...")
            scanning_pool.submit(scan_folder_sync, abs_p, 0)
            conn.close()
            return jsonify({'total_items': 0, 'page': page, 'page_size': psize, 'items': []})

    # 전체 개수 파악
    if not path:
        total = conn.execute("SELECT COUNT(*) FROM entries WHERE depth = 2").fetchone()[0]
    else:
        abs_p = os.path.abspath(os.path.join(BASE_PATH, path)).replace(os.sep, '/')
        phash = get_path_hash(abs_p)
        total = conn.execute("SELECT COUNT(*) FROM entries WHERE parent_hash = ?", (phash,)).fetchone()[0]

    items = []
    for r in rows:
        meta = json.loads(r['metadata'] or '{}')
        meta['poster_url'] = r['poster_url']
        meta['title'] = r['title']
        items.append({
            'name': r['title'] or r['name'],
            'isDirectory': bool(r['is_dir']),
            'path': r['rel_path'],
            'metadata': meta
        })
    conn.close()
    logger.info(f"Returning {len(items)} items for scan result (Total: {total}).")
    return jsonify({'total_items': total, 'page': page, 'page_size': psize, 'items': items})

@app.route('/files')
def list_files():
    path = request.args.get('path', '')
    logger.info(f"API CALL: /files (path='{path}')")
    if not path: return get_categories()
    abs_p = os.path.abspath(os.path.join(BASE_PATH, path)).replace(os.sep, '/')
    phash = get_path_hash(abs_p)
    conn = sqlite3.connect(METADATA_DB_PATH)
    conn.row_factory = sqlite3.Row
    rows = conn.execute("SELECT * FROM entries WHERE parent_hash = ? ORDER BY name", (phash,)).fetchall()
    conn.close()
    return jsonify([{'name': r['name'], 'isDirectory': bool(r['is_dir']), 'path': r['rel_path']} for r in rows])

@app.route('/zip_entries')
def zip_entries():
    path = urllib.parse.unquote(request.args.get('path', ''))
    logger.info(f"API CALL: /zip_entries (path='{path}')")
    abs_p = os.path.join(BASE_PATH, path)
    if os.path.isdir(abs_p):
        try:
            with os.scandir(abs_p) as it:
                return jsonify(sorted([e.name for e in it if is_image_file(e.name)]))
        except: return jsonify([])
    try:
        with zipfile.ZipFile(abs_p, 'r') as z:
            return jsonify(sorted([n for n in z.namelist() if is_image_file(n)]))
    except: return jsonify([])

@app.route('/download_zip_entry')
def download_zip_entry():
    path = urllib.parse.unquote(request.args.get('path', ''))
    entry = urllib.parse.unquote(request.args.get('entry', ''))
    # logger.info(f"API CALL: /download_zip_entry (path='{path}', entry='{entry}')")
    abs_p = os.path.join(BASE_PATH, path)
    if os.path.isdir(abs_p):
        return send_from_directory(abs_p, entry)
    try:
        with zipfile.ZipFile(abs_p, 'r') as z:
            with z.open(entry) as f: return send_file(io.BytesIO(f.read()), mimetype='image/jpeg')
    except: return "Error", 500

@app.route('/download')
def download():
    p = urllib.parse.unquote(request.args.get('path', ''))
    logger.info(f"API CALL: /download (path='{p}')")
    if p.startswith("http"):
        logger.info(f"Redirecting to external URL: {p}")
        return redirect(p)
    if p.startswith("zip_thumb://"):
        azp = os.path.join(BASE_PATH, p[12:])
        if os.path.isdir(azp):
            try:
                # 폴더인 경우 첫 번째 만화 파일을 찾아 썸네일 추출
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
        return "No Image", 404
    target_path = os.path.join(BASE_PATH, p)
    return send_from_directory(os.path.dirname(target_path), os.path.basename(target_path))

if __name__ == '__main__':
    init_db()
    # 서버 시작 시 백그라운드 스캔 트리거
    trigger_initial_scan()
    # 5556 포트로 실행
    app.run(host='0.0.0.0', port=5556, threaded=True)
