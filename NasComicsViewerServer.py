from flask import Flask, jsonify, send_from_directory, request, send_file, render_template_string
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
THREE_LEVEL_STRUCTURE_FOLDERS = ["완결a", "완결b", "완결", "작가", "번역", "연재"]

# --- HTML 템플릿 ---
ADMIN_TEMPLATE = """
<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>메타데이터 관리자</title>
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif; padding: 20px; background-color: #f8f9fa; color: #333; }
        .container { max-width: 800px; margin: 0 auto; }
        .card { background: white; padding: 30px; border-radius: 10px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); margin-bottom: 20px; }
        h1 { margin-top: 0; margin-bottom: 20px; border-bottom: 2px solid #eee; padding-bottom: 10px; font-size: 1.5em; }

        .form-group { display: flex; gap: 10px; }
        input[type="text"] { flex: 1; padding: 12px; border: 1px solid #ddd; border-radius: 6px; font-size: 16px; }
        button { padding: 12px 24px; background-color: #007bff; color: white; border: none; border-radius: 6px; cursor: pointer; font-size: 16px; font-weight: bold; transition: background 0.2s; }
        button:hover { background-color: #0056b3; }

        .summary { background-color: #e9ecef; padding: 15px; border-radius: 5px; margin-bottom: 20px; }
        .item { padding: 10px; border-bottom: 1px solid #eee; display: flex; align-items: center; }
        .item:last-child { border-bottom: none; }
        .badge { padding: 4px 8px; border-radius: 4px; font-size: 0.75em; font-weight: bold; margin-right: 10px; min-width: 70px; text-align: center; }
        .badge.scan { background-color: #17a2b8; color: white; }
        .badge.kavita { background-color: #28a745; color: white; }
        .badge.file { background-color: #6c757d; color: white; }
        .badge.none { background-color: #dc3545; color: white; }
        .title { font-weight: 500; font-size: 1em; }
        .error { color: #dc3545; font-size: 0.9em; margin-top: 5px; }
        .meta-info { font-size: 0.85em; color: #666; margin-left: auto; }
        .no-poster { color: #dc3545; font-weight: bold; margin-left: 10px; font-size: 0.8em; }
    </style>
</head>
<body>
    <div class="container">
        <div class="card">
            <h1>🛠️ 메타데이터 업데이트</h1>
            <p style="margin-bottom: 20px; color: #666;">
                업데이트할 폴더의 경로를 입력하세요. (예: <code>완결</code>, <code>작가/ㄱ</code>)<br>
                빈 칸으로 두면 최상위 폴더를 기준으로 업데이트합니다.
            </p>
            <form action="/update_metadata" method="get" class="form-group">
                <input type="text" name="path" value="{{ path }}" placeholder="폴더 경로 입력...">
                <button type="submit">업데이트 시작</button>
            </form>
        </div>

        {% if performed %}
        <div class="card">
            <h1>결과 리포트</h1>
            <div class="summary">
                <strong>대상 경로:</strong> /{{ path }}<br>
                <strong>총 항목:</strong> {{ total_count }}개<br>
                <strong>성공:</strong> <span style="color: #28a745">{{ success_count }}</span>개
                {% if error_count > 0 %}
                <br><strong style="color: #dc3545;">실패:</strong> {{ error_count }}개
                {% endif %}
            </div>

            <div class="list">
                {% if total_count == 0 %}
                    <div style="text-align: center; padding: 20px; color: #999;">
                        해당 경로에서 업데이트할 항목을 찾지 못했습니다.
                    </div>
                {% endif %}

                {% for item in items %}
                <div class="item">
                    {% if item.source == 'KAVITA_YAML' %}
                        <span class="badge kavita">KAVITA</span>
                    {% elif item.source == 'SCAN' %}
                        <span class="badge scan">SCAN</span>
                    {% elif item.source == 'FILE' %}
                        <span class="badge file">FILE</span>
                    {% else %}
                        <span class="badge none">UNKNOWN</span>
                    {% endif %}

                    <div>
                        <span class="title">{{ item.title }}</span>
                        {% if not item.poster %}
                            <span class="no-poster">⚠️ NO POSTER</span>
                        {% endif %}
                    </div>

                    <div class="meta-info">
                        {{ item.source }}
                    </div>
                </div>
                {% endfor %}

                {% for error in errors %}
                <div class="item">
                    <span class="badge none">ERROR</span>
                    <div class="error">{{ error }}</div>
                </div>
                {% endfor %}
            </div>
        </div>
        {% endif %}
    </div>
</body>
</html>
"""

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
    try:
        with sqlite3.connect(METADATA_DB_PATH) as conn:
            c = conn.cursor()
            c.execute('PRAGMA journal_mode=WAL;')
            # 개별 항목 메타데이터 캐시
            c.execute('''
                CREATE TABLE IF NOT EXISTS metadata_cache (
                    path_hash TEXT PRIMARY KEY,
                    mtime REAL NOT NULL,
                    metadata_json TEXT NOT NULL,
                    cached_at REAL NOT NULL
                )
            ''')
            # [신규] 디렉토리 목록 전체 캐시 (속도 향상 핵심)
            c.execute('''
                CREATE TABLE IF NOT EXISTS directory_cache (
                    path_hash TEXT PRIMARY KEY,
                    entries_json TEXT NOT NULL,
                    cached_at REAL NOT NULL
                )
            ''')
            conn.commit()

            # 현재 캐시된 항목 수 확인
            c.execute("SELECT COUNT(*) FROM directory_cache")
            dir_count = c.fetchone()[0]
            c.execute("SELECT COUNT(*) FROM metadata_cache")
            meta_count = c.fetchone()[0]
            logger.info(f"💾 [DB_INIT] Directory Cache: {dir_count} entries, Metadata Cache: {meta_count} entries")

    except Exception as e:
        logger.error(f"❌ [DB_INIT] Failed to initialize DB: {e}")

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

# --- 디렉토리 목록 캐시 (신규) ---
def get_cached_directory_entries(path, conn):
    path_hash = str(hash(path))
    try:
        c = conn.cursor()
        c.execute("SELECT entries_json FROM directory_cache WHERE path_hash = ?", (path_hash,))
        row = c.fetchone()
        if row:
            return json.loads(row[0])
    except Exception:
        pass
    return None

def set_cached_directory_entries(path, entries, conn):
    path_hash = str(hash(path))
    try:
        entries_json = json.dumps(entries)
        conn.execute("INSERT OR REPLACE INTO directory_cache (path_hash, entries_json, cached_at) VALUES (?, ?, ?)",
                      (path_hash, entries_json, time.time()))
    except Exception as e:
        logger.error(f"Directory cache write failed for {path}: {e}")

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

# --- 메타데이터 관련 로직 ---

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

def get_metadata_internal(abs_path, rel_path, conn):
    """
    일반 스캔 시 호출. DB 캐시가 있으면 반환하고,
    없으면 '파일'은 메타 생성, '디렉토리'는 poster=None으로 빠르게 반환.
    """
    cached = get_cached_metadata(abs_path, conn)
    if cached:
        return cached

    base_name = os.path.basename(abs_path.rstrip('/\\'))
    clean_title = clean_name(normalize_nfc(base_name))

    # 기본값 (디렉토리인 경우 포스터 없이 저장)
    meta = {"title": clean_title, "poster_url": None}

    if os.path.isfile(abs_path):
        if abs_path.lower().endswith(('.zip', '.cbz')):
            meta['poster_url'] = "zip_thumb://" + rel_path
        elif is_image_file(abs_path):
            meta['poster_url'] = rel_path

    set_cached_metadata(abs_path, meta, conn)
    return meta

def force_update_metadata_task(task_path, is_dir, root_path, db_path):
    """
    특정 경로에 대해 강제로 메타데이터(kavita.yaml, 썸네일 등)를 생성하고 DB에 업데이트
    """
    conn = None
    try:
        conn = sqlite3.connect(db_path, timeout=20)
        conn.execute('PRAGMA journal_mode=WAL;')

        rel_path = os.path.relpath(task_path, root_path).replace(os.sep, '/')
        base_name = os.path.basename(task_path.rstrip('/\\'))
        clean_title = clean_name(normalize_nfc(base_name))

        meta = {"title": clean_title, "poster_url": None, "kavita_info": {}}
        source = "NONE"

        if os.path.isfile(task_path):
             source = "FILE"
             if task_path.lower().endswith(('.zip', '.cbz')):
                meta['poster_url'] = "zip_thumb://" + rel_path
             elif is_image_file(task_path):
                meta['poster_url'] = rel_path
        else:
            # 디렉토리: 무거운 작업 수행
            # 1. kavita.yaml
            try:
                kavita_path = os.path.join(task_path, "kavita.yaml")
                if os.path.isfile(kavita_path):
                    with open(kavita_path, 'r', encoding='utf-8') as f:
                        kdata = yaml.safe_load(f)
                        if kdata:
                            meta['kavita_info'] = kdata

                            poster_candidates = []
                            for k in ['cover', 'poster', 'cover_image', 'coverImage']:
                                if k in kdata and kdata[k]:
                                    poster_candidates.append(kdata[k])

                            if 'search' in kdata and isinstance(kdata['search'], list) and len(kdata['search']) > 0:
                                search_item = kdata['search'][0]
                                if 'poster_url' in search_item and search_item['poster_url']:
                                    pass

                            for target in poster_candidates:
                                if os.path.exists(os.path.join(task_path, target)):
                                    meta['poster_url'] = os.path.join(rel_path, target).replace('\\', '/')
                                    source = "KAVITA_YAML"
                                    break
            except Exception as e:
                logger.warning(f"Failed to parse kavita.yaml for {task_path}: {e}")

            # 2. 직접 스캔 (kavita.yaml에서 포스터를 못 찾았을 경우)
            if not meta.get('poster_url'):
                try:
                    local_files = [e.name for e in os.scandir(task_path)]
                    local_files.sort(key=lambda x: normalize_nfc(x))
                    meta['poster_url'] = find_first_valid_thumb(task_path, rel_path, local_files)
                    if meta['poster_url']:
                        source = "SCAN"
                except Exception:
                    pass

        set_cached_metadata(task_path, meta, conn)
        conn.commit()

        if meta.get('poster_url'):
             logger.info(f"✅ [UPDATE] '{clean_title}' updated via {source}")
        else:
             logger.warning(f"⚠️ [UPDATE] '{clean_title}' processed but NO POSTER found")

        return {
            "success": True,
            "title": clean_title,
            "source": source,
            "poster": meta.get('poster_url')
        }
    except Exception as e:
        logger.error(f"❌ [UPDATE] Failed for {task_path}: {e}")
        return {"success": False, "error": str(e)}
    finally:
        if conn: conn.close()

# --- 라우트: 스캔 ---
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

def scan_full_directory(abs_path, root, is_3_level_structure):
    """
    파일 시스템을 전체 스캔하여 모든 항목의 리스트(메타데이터 포함)를 생성
    """
    logger.info(f"📂 [FS_SCAN] Scanning file system for: {abs_path}")

    all_entries = []
    scan_paths = [abs_path]
    if is_3_level_structure:
        try:
            scan_paths = [d.path for d in os.scandir(abs_path) if d.is_dir()]
        except Exception:
            pass

    tasks = []
    for current_path in scan_paths:
        try:
            with os.scandir(current_path) as it:
                for entry in it:
                    if entry.is_dir() or entry.name.lower().endswith(('.zip', '.cbz')):
                        tasks.append((entry.path, entry.is_dir()))
        except Exception:
            pass

    # 전체 항목에 대해 병렬로 메타데이터 확보
    results = []
    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        future_map = {
            executor.submit(process_scan_task, t_path, is_dir, root, METADATA_DB_PATH):
            t_path for t_path, is_dir in tasks
        }
        for future in as_completed(future_map):
            try:
                res = future.result()
                if res: results.append(res)
            except Exception:
                pass

    # 정렬 (상대 경로 기준 이름순)
    paged_entry_map = {r['path']: r['name'] for r in results}
    results.sort(key=lambda r: paged_entry_map.get(r['path'], ''))

    return results

@app.route('/scan')
@time_it
def scan_comics():
    path = request.args.get('path', '')
    page = request.args.get('page', 1, type=int)
    page_size = request.args.get('page_size', 100, type=int)
    force_refresh = request.args.get('force', 'false').lower() == 'true'

    root = get_robust_root()
    abs_path = resolve_actual_path(path)
    if not os.path.isdir(abs_path):
        return jsonify({"error": "Invalid scan path"}), 404

    requested_folder_name = os.path.basename(abs_path)
    normalized_name = normalize_nfc(requested_folder_name.lower())
    is_3_level_structure = normalized_name in THREE_LEVEL_STRUCTURE_FOLDERS

    # 1. DB 캐시 조회 (force_refresh가 아닐 때만)
    cached_entries = None
    if not force_refresh:
        with sqlite3.connect(METADATA_DB_PATH) as conn:
            conn.execute('PRAGMA journal_mode=WAL;')
            cached_entries = get_cached_directory_entries(abs_path, conn)

    if cached_entries is not None:
        logger.info(f"🚀 [CACHE_HIT] Serving '{path}' from DB cache! ({len(cached_entries)} items)")
        total_items = len(cached_entries)
        start_index = (page - 1) * page_size
        end_index = start_index + page_size
        paged_items = cached_entries[start_index:end_index]

        return jsonify({
            'total_items': total_items,
            'page': page,
            'page_size': page_size,
            'items': paged_items
        })

    # 2. 캐시 없으면 파일 시스템 스캔 수행
    logger.info(f"🐢 [CACHE_MISS] Scanning filesystem for '{path}'...")
    full_results = scan_full_directory(abs_path, root, is_3_level_structure)

    # 3. 결과 DB 저장
    with sqlite3.connect(METADATA_DB_PATH) as conn:
        conn.execute('PRAGMA journal_mode=WAL;')
        set_cached_directory_entries(abs_path, full_results, conn)

    # 4. 페이징 및 반환
    total_items = len(full_results)
    start_index = (page - 1) * page_size
    end_index = start_index + page_size
    paged_items = full_results[start_index:end_index]

    return jsonify({
        'total_items': total_items,
        'page': page,
        'page_size': page_size,
        'items': paged_items
    })

# --- 라우트: 메타데이터 업데이트 (수동) ---
@app.route('/update_metadata')
@time_it
def update_metadata():
    if 'path' not in request.args:
         return render_template_string(
            ADMIN_TEMPLATE,
            path="",
            performed=False,
            total_count=0,
            success_count=0,
            error_count=0,
            items=[],
            errors=[]
        )

    path = request.args.get('path', '')
    logger.info(f"🔄 [UPDATE_METADATA] Start request for path: '{path}'")

    root = get_robust_root()
    abs_path = resolve_actual_path(path)

    if not os.path.isdir(abs_path):
        return render_template_string(ADMIN_TEMPLATE, path=path, performed=True, total_count=0, success_count=0, error_count=1, items=[], errors=[f"Invalid path: {abs_path}"])

    requested_folder_name = os.path.basename(abs_path)
    normalized_name = normalize_nfc(requested_folder_name.lower())
    is_3_level_structure = normalized_name in THREE_LEVEL_STRUCTURE_FOLDERS

    scan_paths = [abs_path]
    if is_3_level_structure:
        scan_paths = [d.path for d in os.scandir(abs_path) if d.is_dir()]

    tasks = []
    for current_path in scan_paths:
        try:
            with os.scandir(current_path) as it:
                for entry in it:
                    if entry.is_dir() or entry.name.lower().endswith(('.zip', '.cbz')):
                        tasks.append((entry.path, entry.is_dir()))
        except Exception:
            pass

    logger.info(f"📥 [UPDATE_METADATA] Found {len(tasks)} items to update.")

    updated_items = []
    failed_items = []
    updated_results_for_cache = [] # 캐시 갱신용 데이터

    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        future_map = {
            executor.submit(force_update_metadata_task, t_path, is_dir, root, METADATA_DB_PATH):
            (t_path, is_dir) for t_path, is_dir in tasks
        }
        for future in as_completed(future_map):
            t_path, is_dir = future_map[future]
            res = future.result()

            # 캐시 갱신을 위해 데이터 구조 만들기
            if res:
                rel_path = os.path.relpath(t_path, root).replace(os.sep, '/')
                # force_update_metadata_task의 반환값과 process_scan_task 반환값 형식이 다름을 주의
                # 여기서 캐시용 구조로 변환
                cache_item = {
                    'name': res.get('title', 'Untitled'),
                    'isDirectory': is_dir,
                    'path': normalize_nfc(rel_path),
                    'metadata': {
                        'title': res.get('title'),
                        'poster_url': res.get('poster'),
                        'kavita_info': {} # force_update_metadata_task에서 kavita_info를 반환하지 않고 있었음. 필요시 수정
                    }
                }
                # 주의: force_update_metadata_task는 현재 UI용 요약 정보만 리턴함.
                # 제대로 캐시를 갱신하려면 메타데이터 전체가 필요함.
                # 따라서 가장 확실한 방법은 업데이트 완료 후 'scan_full_directory'를 한 번 돌리는 것임.

            if res.get("success"):
                updated_items.append(res)
            else:
                failed_items.append(str(res.get("error")))

    updated_items.sort(key=lambda x: x['title'])

    # [중요] 업데이트가 끝났으므로 해당 경로의 directory_cache를 갱신해야 함
    logger.info(f"♻️ [UPDATE_METADATA] Refreshing directory cache for '{path}'...")
    new_full_results = scan_full_directory(abs_path, root, is_3_level_structure)
    with sqlite3.connect(METADATA_DB_PATH) as conn:
        conn.execute('PRAGMA journal_mode=WAL;')
        set_cached_directory_entries(abs_path, new_full_results, conn)

    logger.info(f"✨ [UPDATE_METADATA] Finished. Updated {len(updated_items)} items.")

    return render_template_string(
        ADMIN_TEMPLATE,
        path=path,
        performed=True,
        total_count=len(tasks),
        success_count=len(updated_items),
        error_count=len(failed_items),
        items=updated_items,
        errors=failed_items
    )


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