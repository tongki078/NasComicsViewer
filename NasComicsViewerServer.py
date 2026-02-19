from flask import Flask, jsonify, send_from_directory, request, send_file
import os
import urllib.parse
import unicodedata
import logging
import yaml
import time
import zipfile
import io
import sys
import sqlite3
import json
import functools
from concurrent.futures import ThreadPoolExecutor, as_completed

# [로그 설정]
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s %(levelname)s [%(name)s] %(message)s',
    stream=sys.stdout
)
logger = logging.getLogger("NasServer")

app = Flask(__name__)

# --- 설정 ---
BASE_PATH = "/volume2/video/GDS3/GDRIVE/READING/만화"
METADATA_DB_PATH = 'metadata_cache.db'
MAX_WORKERS = 16

# 3단계 구조를 가진 카테고리 폴더 이름 (소문자로 비교)
# 실제 폴더 이름인 '번역', '연재'로 수정합니다.
THREE_LEVEL_STRUCTURE_FOLDERS = ["완결a", "완결b", "완결", "작가", "번역", "연재"]

# --- 유틸리티 ---
def time_it(func):
    @functools.wraps(func)
    def wrapper(*args, **kwargs):
        start_time = time.time()
        result = func(*args, **kwargs)
        end_time = time.time()
        logger.info(f"TIMING: {func.__name__} took {end_time - start_time:.4f} seconds to execute.")
        return result
    return wrapper

# --- DB 설정 ---
def init_db():
    with sqlite3.connect(METADATA_DB_PATH) as conn:
        c = conn.cursor()
        c.execute('PRAGMA journal_mode=WAL;')
        c.execute('''
            CREATE TABLE IF NOT EXISTS metadata_cache (
                path_hash TEXT PRIMARY KEY,
                mtime REAL NOT NULL,
                metadata_json TEXT NOT NULL,
                cached_at REAL NOT NULL
            )
        ''')
        conn.commit()

def get_cached_metadata(path, conn):
    path_hash = str(hash(path))
    try:
        mtime = os.path.getmtime(path)
        c = conn.cursor()
        c.execute("SELECT mtime, metadata_json FROM metadata_cache WHERE path_hash = ?", (path_hash,))
        row = c.fetchone()
        if row and row[0] == mtime:
            return json.loads(row[1])
    except Exception:
        pass
    return None

def set_cached_metadata(path, metadata, conn):
    path_hash = str(hash(path))
    try:
        mtime = os.path.getmtime(path)
        metadata_json = json.dumps(metadata)
        conn.execute("INSERT OR REPLACE INTO metadata_cache (path_hash, mtime, metadata_json, cached_at) VALUES (?, ?, ?, ?)",
                      (path_hash, mtime, metadata_json, time.time()))
    except Exception as e:
        logger.error(f"Cache write failed for {path}: {e}")

# --- 파일 시스템 및 경로 처리 ---
def normalize_nfc(s):
    if not isinstance(s, str): return s
    return unicodedata.normalize('NFC', s)

def find_actual_name_in_dir(parent, target_name):
    target_norm = normalize_nfc(target_name).lower()
    try:
        with os.scandir(parent) as it:
            for entry in it:
                if normalize_nfc(entry.name).lower() == target_norm:
                    return entry.name
    except Exception:
        pass
    return None

def get_robust_root():
    return os.path.normpath(os.path.abspath(BASE_PATH))

def resolve_actual_path(rel_path):
    root = get_robust_root()
    if not rel_path or rel_path.strip() in [".", "", "/"]:
        return root
    curr = root
    parts = urllib.parse.unquote(rel_path).replace('\\', '/').strip('/').split('/')
    for part in parts:
        if not part: continue
        actual = find_actual_name_in_dir(curr, part)
        curr = os.path.join(curr, actual) if actual else os.path.join(curr, part)
    return os.path.normpath(os.path.abspath(curr))

def is_image_file(name):
    return name and name.lower().endswith(('.jpg', '.jpeg', '.png', '.webp', '.gif'))

def clean_name(name):
    return os.path.splitext(name)[0].strip() if name else ""

# --- 메타데이터 및 포스터 검색 ---
def get_metadata_internal(abs_path, rel_path, conn):
    cached = get_cached_metadata(abs_path, conn)
    if cached:
        return cached

    meta = {}
    try:
        is_file = os.path.isfile(abs_path)
        base_name = os.path.basename(abs_path.rstrip('/\\'))
        clean_title = clean_name(normalize_nfc(base_name))

        if is_file:
            meta = {"title": clean_title, "poster_url": None}
            if abs_path.lower().endswith(('.zip', '.cbz')):
                meta['poster_url'] = "zip_thumb://" + rel_path
            elif is_image_file(abs_path):
                meta['poster_url'] = rel_path
        else:  # is Directory
            meta = {"title": clean_title or "Untitled", "poster_url": None}
            local_files = []
            try:
                local_files = [e.name for e in os.scandir(abs_path)]
            except Exception as e:
                logger.warning(f"Could not list files for {abs_path} in metadata generation: {e}")

            if not meta.get('poster_url'):
                meta['poster_url'] = find_first_valid_thumb(abs_path, rel_path, local_files)

    except Exception as e:
        logger.error(f"CRITICAL error in get_metadata_internal for {abs_path}: {e}", exc_info=True)
        meta = {"title": os.path.basename(abs_path), "poster_url": None}

    set_cached_metadata(abs_path, meta, conn)
    return meta

def find_first_valid_thumb(abs_path, rel_path, files):
    first_image = None
    first_zip = None
    for f in files:
        lowered_f = f.lower()
        if is_image_file(f):
            if any(keyword in lowered_f for keyword in ["poster", "cover", "folder", "thumb"]):
                return os.path.join(rel_path, f).replace('\\', '/')
            if not first_image:
                first_image = f
        elif not first_zip and lowered_f.endswith(('.zip', '.cbz')):
            first_zip = f
    if first_image:
        return os.path.join(rel_path, first_image).replace('\\', '/')
    if first_zip:
        return "zip_thumb://" + os.path.join(rel_path, first_zip).replace('\\', '/')
    return None

# --- 새로운 스캔 작업자 ---
def process_scan_task(task_path, is_dir, root_path, db_path):
    conn = None
    try:
        conn = sqlite3.connect(db_path, timeout=20)
        conn.execute('PRAGMA journal_mode=WAL;')
        rel_path = os.path.relpath(task_path, root_path).replace(os.sep, '/')
        meta = get_metadata_internal(task_path, rel_path, conn)
        conn.commit()
        return {
            'name': meta.get('title', 'Untitled'),
            'isDirectory': is_dir,
            'path': normalize_nfc(rel_path),
            'metadata': meta
        }
    except Exception as e:
        if conn: conn.rollback()
        logger.error(f"Error processing task {task_path}: {e}", exc_info=True)
        return None
    finally:
        if conn: conn.close()

# --- 페이지네이션을 적용한 새로운 스캔 라우트 ---
@app.route('/scan')
@time_it
def scan_comics():
    path = request.args.get('path', '')
    page = request.args.get('page', 1, type=int)
    page_size = request.args.get('page_size', 100, type=int)

    logger.info(f"[SCAN] Request for path: '{path}', page: {page}, page_size: {page_size}")

    root = get_robust_root()
    abs_path = resolve_actual_path(path)
    logger.info(f"[SCAN] Scanning resolved path: '{abs_path}'")

    if not os.path.isdir(abs_path):
        return jsonify({"error": "Invalid scan path"}), 404

    try:
        all_entries = []
        requested_folder_name = os.path.basename(abs_path)
        normalized_name = normalize_nfc(requested_folder_name.lower())

        logger.info(f"[DEBUG] Checking folder: '{normalized_name}'")
        logger.info(f"[DEBUG] Is in THREE_LEVEL_STRUCTURE_FOLDERS? {normalized_name in THREE_LEVEL_STRUCTURE_FOLDERS}")

        is_3_level_structure = normalized_name in THREE_LEVEL_STRUCTURE_FOLDERS

        scan_paths = [abs_path]
        if is_3_level_structure:
            logger.info(f"[SCAN] 3-level structure detected for '{requested_folder_name}'. Scanning subdirectories.")
            scan_paths = [d.path for d in os.scandir(abs_path) if d.is_dir()]

        for current_path in scan_paths:
            with os.scandir(current_path) as it:
                for entry in it:
                    if entry.is_dir() or entry.name.lower().endswith(('.zip', '.cbz')):
                        all_entries.append(entry)

        all_entries.sort(key=lambda e: normalize_nfc(e.name))
        total_items = len(all_entries)

        start_index = (page - 1) * page_size
        end_index = start_index + page_size
        paged_entries = all_entries[start_index:end_index]

        tasks_to_process = [(entry.path, entry.is_dir()) for entry in paged_entries]

    except Exception as e:
        logger.error(f"Error during directory scan for {abs_path}: {e}", exc_info=True)
        return jsonify({"error": "Failed to scan directory"}), 500

    results = []
    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        future_map = {
            executor.submit(process_scan_task, task_path, is_dir, root, METADATA_DB_PATH):
            (task_path, is_dir) for task_path, is_dir in tasks_to_process
        }
        for future in as_completed(future_map):
            try:
                worker_result = future.result()
                if worker_result:
                    results.append(worker_result)
            except Exception as exc:
                logger.error(f"Task for {future_map[future]} generated an exception: {exc}")

    paged_entry_map = {normalize_nfc(os.path.relpath(entry.path, root).replace(os.sep, '/')) : normalize_nfc(entry.name) for entry in paged_entries}
    results.sort(key=lambda r: paged_entry_map.get(r['path'], ''))

    logger.info(f"[SCAN] Completed page {page}. Returning {len(results)} of {total_items} items for '{path}'")

    response_data = {
        'total_items': total_items,
        'page': page,
        'page_size': page_size,
        'items': results
    }
    return jsonify(response_data)

# --- 기타 라우트 ---
@app.route('/files')
def list_files():
    p = request.args.get('path', '')
    ap = resolve_actual_path(p)
    if not os.path.exists(ap): return jsonify({"error": "Path not found", "path": ap}), 404
    if not os.path.isdir(ap): return jsonify({"error": "Not a directory", "path": ap}), 400
    items = [{'name': normalize_nfc(e.name), 'isDirectory': e.is_dir(), 'path': normalize_nfc(os.path.join(p, e.name).replace('\\', '/'))} for e in os.scandir(ap)]
    return jsonify(sorted(items, key=lambda x: x['name']))

@app.route('/download')
def download_file():
    p = request.args.get('path', '')
    if not p: return "Path missing", 400
    if p.startswith("zip_thumb://"):
        rzp = p[len("zip_thumb://"):]
        azp = resolve_actual_path(rzp)
        if os.path.isfile(azp):
            try:
                with zipfile.ZipFile(azp, 'r') as z:
                    images = sorted([n for n in z.namelist() if is_image_file(n)])
                    if images:
                        with z.open(images[0]) as f:
                            return send_file(io.BytesIO(f.read()), mimetype='image/jpeg')
            except Exception as e:
                logger.error(f"[DOWNLOAD] Zip thumb error: {azp} -> {e}")
        return "Thumbnail not found", 404
    ap = resolve_actual_path(p)
    if not os.path.isfile(ap): return "File not found", 404
    return send_from_directory(os.path.dirname(ap), os.path.basename(ap))

@app.route('/zip_entries')
def zip_entries():
    p = request.args.get('path', '')
    if not p: return "Path missing", 400
    ap = resolve_actual_path(p)
    if not os.path.isfile(ap): return jsonify({"error": "File not found"}), 404
    try:
        with zipfile.ZipFile(ap, 'r') as z:
            return jsonify(sorted([n for n in z.namelist() if is_image_file(n)]))
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route('/download_zip_entry')
def download_zip_entry():
    p = request.args.get('path', '')
    entry_name = request.args.get('entry', '')
    if not p or not entry_name: return "Path or entry missing", 400
    ap = resolve_actual_path(p)
    entry_name = normalize_nfc(urllib.parse.unquote(entry_name))
    if not os.path.isfile(ap): return "Zip file not found", 404
    try:
        with zipfile.ZipFile(ap, 'r') as z:
            for info in z.infolist():
                if normalize_nfc(info.filename) == entry_name:
                    with z.open(info) as f:
                        return send_file(io.BytesIO(f.read()), mimetype='application/octet-stream')
            return "Entry not found", 404
    except Exception as e:
        return f"Error reading zip: {e}", 500

@app.route('/metrics')
def metrics(): return "OK", 200

if __name__ == '__main__':
    init_db()
    logger.info("🚀 ===== SERVER STARTING ===== 🚀")
    app.run(host='0.0.0.0', port=5555, debug=False, threaded=True, use_reloader=False)
