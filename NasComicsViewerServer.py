from flask import Flask, jsonify, send_from_directory, request, send_file, render_template_string, Response
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
import threading
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

# [중요 변경] DB 파일 경로를 상대경로에서 절대경로로 변경
# 용량이 아주 많은(7.5TB 여유) /volume2/video 파티션에 명시적으로 저장
METADATA_DB_PATH = '/volume2/video/NasComicsViewer_metadata_cache.db'

MAX_WORKERS = 16

# SQLite 임시 폴더를 공간이 충분한 곳으로 강제 지정 (디스크 풀 에러 방지)
os.environ["SQLITE_TMPDIR"] = BASE_PATH

# 3단계 구조를 가진 카테고리 폴더 이름 (소문자로 비교)
THREE_LEVEL_STRUCTURE_FOLDERS = ["완결a", "완결b", "완결", "작가", "번역", "연재"]

# 업데이트 상태를 저장할 전역 딕셔너리
update_status = {
    'is_running': False,
    'total': 0,
    'processed': 0,
    'success': 0,
    'error': 0,
    'current_item': '',
    'logs': [],
    'path': ''
}
status_lock = threading.Lock()

# --- HTML 템플릿 (개선된 UI) ---
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
        button:disabled { background-color: #6c757d; cursor: not-allowed; }

        .progress-container { margin-top: 20px; display: none; }
        .progress-bar-bg { width: 100%; background-color: #e9ecef; border-radius: 5px; overflow: hidden; height: 20px; margin-bottom: 10px; }
        .progress-bar { height: 100%; background-color: #28a745; width: 0%; transition: width 0.3s ease; }
        .stats { display: flex; justify-content: space-between; font-size: 0.9em; color: #666; margin-bottom: 10px; }

        .log-container { background: #1e1e1e; color: #d4d4d4; padding: 15px; border-radius: 5px; height: 300px; overflow-y: auto; font-family: monospace; font-size: 0.85em; display: none; margin-top: 15px; }
        .log-item { margin-bottom: 4px; }
        .log-success { color: #4CAF50; }
        .log-warning { color: #FFC107; }
        .log-error { color: #F44336; }
        .log-info { color: #2196F3; }
    </style>
</head>
<body>
    <div class="container">
        <div class="card">
            <h1>🛠️ 메타데이터 실시간 업데이트</h1>
            <p style="margin-bottom: 20px; color: #666;">
                업데이트할 폴더의 경로를 입력하세요. (예: <code>완결</code>, <code>작가/ㄱ</code>)<br>
                빈 칸으로 두면 최상위 폴더를 기준으로 업데이트합니다.
            </p>
            <div class="form-group">
                <input type="text" id="pathInput" placeholder="폴더 경로 입력...">
                <button id="startBtn" onclick="startUpdate()">업데이트 시작</button>
            </div>

            <div id="progressSection" class="progress-container">
                <h3 id="statusText" style="margin-top: 0; margin-bottom: 15px; font-size: 1.1em;">준비 중...</h3>
                <div class="progress-bar-bg">
                    <div id="progressBar" class="progress-bar"></div>
                </div>
                <div class="stats">
                    <span id="progressCount">0 / 0</span>
                    <span id="percentText">0%</span>
                </div>
                <div class="stats">
                    <span style="color: #28a745;">성공: <span id="successCount">0</span></span>
                    <span style="color: #dc3545;">실패: <span id="errorCount">0</span></span>
                </div>
            </div>

            <div id="logSection" class="log-container"></div>
        </div>
    </div>

    <script>
        let eventSource = null;

        function startUpdate() {
            const path = document.getElementById('pathInput').value;
            const btn = document.getElementById('startBtn');
            const progressSection = document.getElementById('progressSection');
            const logSection = document.getElementById('logSection');

            btn.disabled = true;
            progressSection.style.display = 'block';
            logSection.style.display = 'block';
            logSection.innerHTML = '';

            // SSE 연결 시작 (스트리밍 요청)
            if (eventSource) {
                eventSource.close();
            }

            eventSource = new EventSource('/do_update_metadata?path=' + encodeURIComponent(path));

            eventSource.onmessage = function(event) {
                const data = JSON.parse(event.data);

                if (data.status === 'error') {
                    addLog(data.message, 'log-error');
                    finishUpdate();
                    return;
                }

                if (data.status === 'init') {
                    document.getElementById('statusText').innerText = `스캔 준비 중... (총 ${data.total}개 항목 찾음)`;
                    updateBars(0, data.total, 0, 0);
                    addLog(`📥 [INIT] Found ${data.total} items to update in '${path}'`, 'log-info');
                }
                else if (data.status === 'progress') {
                    document.getElementById('statusText').innerText = `처리 중: ${data.current_item}`;
                    updateBars(data.processed, data.total, data.success, data.error);

                    if (data.log) {
                        let logClass = 'log-item';
                        if (data.log.includes('✅')) logClass = 'log-success';
                        else if (data.log.includes('⚠️')) logClass = 'log-warning';
                        else if (data.log.includes('❌')) logClass = 'log-error';
                        addLog(data.log, logClass);
                    }
                }
                else if (data.status === 'done') {
                    document.getElementById('statusText').innerText = '✨ 업데이트 완료!';
                    updateBars(data.total, data.total, data.success, data.error);
                    addLog(`✨ [DONE] Finished. Updated ${data.success} items.`, 'log-info');
                    finishUpdate();
                }
            };

            eventSource.onerror = function(event) {
                console.error("EventSource failed:", event);
                addLog("❌ 서버와의 연결이 끊어졌습니다.", "log-error");
                finishUpdate();
            };
        }

        function updateBars(processed, total, success, error) {
            const percent = total === 0 ? 0 : Math.round((processed / total) * 100);
            document.getElementById('progressBar').style.width = percent + '%';
            document.getElementById('progressCount').innerText = `${processed} / ${total}`;
            document.getElementById('percentText').innerText = `${percent}%`;
            document.getElementById('successCount').innerText = success;
            document.getElementById('errorCount').innerText = error;
        }

        function addLog(message, className) {
            const logSection = document.getElementById('logSection');
            const div = document.createElement('div');
            div.className = className;
            div.innerText = message;
            logSection.appendChild(div);
            logSection.scrollTop = logSection.scrollHeight;
        }

        function finishUpdate() {
            document.getElementById('startBtn').disabled = false;
            if (eventSource) {
                eventSource.close();
                eventSource = null;
            }
        }
    </script>
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

def get_db_connection():
    """DB 연결 공통 함수. 임시 폴더 및 성능 옵션 적용"""
    conn = sqlite3.connect(METADATA_DB_PATH, timeout=60)
    c = conn.cursor()
    # 공간 부족 에러 해결을 위한 임시 저장소 지정 (최신 SQLite는 pragma temp_store_directory 지원 중단될 수 있으나 환경변수와 병행)
    try:
        c.execute(f"PRAGMA temp_store_directory = '{BASE_PATH}';")
    except Exception:
        pass

    c.execute('PRAGMA journal_mode=WAL;')
    c.execute('PRAGMA synchronous=NORMAL;')
    c.execute('PRAGMA wal_autocheckpoint=1000;')
    return conn

# --- DB 설정 ---
def init_db():
    try:
        logger.info(f"🔧 Using Database at: {METADATA_DB_PATH}")
        with get_db_connection() as conn:
            c = conn.cursor()

            c.execute('''
                CREATE TABLE IF NOT EXISTS metadata_cache (
                    path_hash TEXT PRIMARY KEY,
                    mtime REAL NOT NULL,
                    metadata_json TEXT NOT NULL,
                    cached_at REAL NOT NULL
                )
            ''')
            c.execute('''
                CREATE TABLE IF NOT EXISTS directory_cache (
                    path_hash TEXT PRIMARY KEY,
                    entries_json TEXT NOT NULL,
                    cached_at REAL NOT NULL
                )
            ''')
            conn.commit()

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
    retries = 3
    for attempt in range(retries):
        try:
            mtime = os.path.getmtime(path)
            metadata_json = json.dumps(metadata)
            conn.execute("INSERT OR REPLACE INTO metadata_cache (path_hash, mtime, metadata_json, cached_at) VALUES (?, ?, ?, ?)",
                          (path_hash, mtime, metadata_json, time.time()))
            return  # 성공하면 종료
        except sqlite3.OperationalError as e:
            if 'locked' in str(e).lower() and attempt < retries - 1:
                time.sleep(0.5) # 잠시 대기 후 재시도
            else:
                logger.error(f"Cache write failed for {path} after {attempt+1} attempts: {e}")
                break
        except Exception as e:
            logger.error(f"Cache write failed for {path}: {e}")
            break

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
    retries = 3
    for attempt in range(retries):
        try:
            entries_json = json.dumps(entries)
            conn.execute("INSERT OR REPLACE INTO directory_cache (path_hash, entries_json, cached_at) VALUES (?, ?, ?)",
                          (path_hash, entries_json, time.time()))
            return
        except sqlite3.OperationalError as e:
            if 'locked' in str(e).lower() and attempt < retries - 1:
                time.sleep(0.5)
            else:
                logger.error(f"Directory cache write failed for {path}: {e}")
                break
        except Exception as e:
            logger.error(f"Directory cache write failed for {path}: {e}")
            break

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
    cached = get_cached_metadata(abs_path, conn)
    if cached:
        return cached

    base_name = os.path.basename(abs_path.rstrip('/\\'))
    clean_title = clean_name(normalize_nfc(base_name))
    meta = {"title": clean_title, "poster_url": None}

    if os.path.isfile(abs_path):
        if abs_path.lower().endswith(('.zip', '.cbz')):
            meta['poster_url'] = "zip_thumb://" + rel_path
        elif is_image_file(abs_path):
            meta['poster_url'] = rel_path

    set_cached_metadata(abs_path, meta, conn)
    return meta

def force_update_metadata_task(task_path, is_dir, root_path, db_path):
    conn = None
    log_msg = ""
    result = {"success": False, "title": "", "source": "NONE", "poster": None, "log": ""}

    try:
        conn = get_db_connection()

        rel_path = os.path.relpath(task_path, root_path).replace(os.sep, '/')
        base_name = os.path.basename(task_path.rstrip('/\\'))
        clean_title = clean_name(normalize_nfc(base_name))
        result["title"] = clean_title

        # [이어하기 로직] 기존에 포스터 정보가 제대로 있으면 건너뛰기
        cached = get_cached_metadata(task_path, conn)
        if cached and cached.get('poster_url'):
            result["success"] = True
            result["source"] = "CACHE"
            result["poster"] = cached.get('poster_url')
            # 로그 생략 또는 간단히
            # result["log"] = f"⏭️ [SKIP] '{clean_title}' already has poster"
            return result

        meta = {"title": clean_title, "poster_url": None, "kavita_info": {}}
        source = "NONE"

        if os.path.isfile(task_path):
             source = "FILE"
             if task_path.lower().endswith(('.zip', '.cbz')):
                meta['poster_url'] = "zip_thumb://" + rel_path
             elif is_image_file(task_path):
                meta['poster_url'] = rel_path
        else:
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
                pass

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

        result["success"] = True
        result["source"] = source
        result["poster"] = meta.get('poster_url')

        if meta.get('poster_url'):
             result["log"] = f"✅ [UPDATE] '{clean_title}' updated via {source}"
        else:
             result["log"] = f"⚠️ [UPDATE] '{clean_title}' processed but NO POSTER found"

        return result
    except Exception as e:
        result["log"] = f"❌ [UPDATE] Failed for {task_path}: {e}"
        return result
    finally:
        if conn: conn.close()

# --- 라우트: 스캔 ---
def process_scan_task(task_path, is_dir, root_path, db_path):
    conn = None
    meta = None
    rel_path = os.path.relpath(task_path, root_path).replace(os.sep, '/')

    try:
        conn = get_db_connection()
        meta = get_metadata_internal(task_path, rel_path, conn)
        conn.commit()
    except Exception as e:
        if conn: conn.rollback()
        logger.error(f"Error processing task {task_path}: {e}")
        # DB 에러가 발생하더라도 메타데이터를 최대한 반환하기 위한 기본 처리
        base_name = os.path.basename(task_path.rstrip('/\\'))
        meta = {"title": clean_name(normalize_nfc(base_name)), "poster_url": None}
    finally:
        if conn: conn.close()

    return {
        'name': meta.get('title', 'Untitled'),
        'isDirectory': is_dir,
        'path': normalize_nfc(rel_path),
        'metadata': meta
    }

def scan_full_directory(abs_path, root, is_3_level_structure):
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

    cached_entries = None
    if not force_refresh:
        try:
            with get_db_connection() as conn:
                cached_entries = get_cached_directory_entries(abs_path, conn)
        except Exception as e:
            logger.error(f"Error reading DB Cache: {e}")

    if cached_entries is not None:
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

    logger.info(f"🐢 [CACHE_MISS] Scanning filesystem for '{path}'...")
    full_results = scan_full_directory(abs_path, root, is_3_level_structure)

    try:
        with get_db_connection() as conn:
            set_cached_directory_entries(abs_path, full_results, conn)
            conn.commit()
    except Exception as e:
        logger.error(f"Error writing Directory Cache for {abs_path}: {e}")

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

# --- 라우트: 메타데이터 업데이트 UI 제공 ---
@app.route('/update_metadata')
def update_metadata_ui():
    # 껍데기 HTML만 렌더링. 동작은 SSE(/do_update_metadata)를 통해 수행됨
    return render_template_string(ADMIN_TEMPLATE)

# --- 라우트: 실제 업데이트 프로세스 (SSE) ---
@app.route('/do_update_metadata')
def do_update_metadata():
    path = request.args.get('path', '')
    root = get_robust_root()
    abs_path = resolve_actual_path(path)

    def generate():
        if not os.path.isdir(abs_path):
            yield f"data: {json.dumps({'status': 'error', 'message': f'Invalid path: {abs_path}'})}\n\n"
            return

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

        total_tasks = len(tasks)
        yield f"data: {json.dumps({'status': 'init', 'total': total_tasks})}\n\n"

        success_count = 0
        error_count = 0
        processed_count = 0

        # 스레드 풀 크기를 적절히 조절 (너무 많으면 DB 락이 자주 걸릴 수 있음)
        with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
            future_map = {
                executor.submit(force_update_metadata_task, t_path, is_dir, root, METADATA_DB_PATH):
                (t_path, is_dir) for t_path, is_dir in tasks
            }
            for future in as_completed(future_map):
                res = future.result()
                processed_count += 1

                if res.get("success"):
                    success_count += 1
                else:
                    error_count += 1

                # 프론트엔드로 진행 상태와 로그 전송
                payload = {
                    'status': 'progress',
                    'processed': processed_count,
                    'total': total_tasks,
                    'success': success_count,
                    'error': error_count,
                    'current_item': res.get('title', ''),
                    'log': res.get('log', '')
                }
                yield f"data: {json.dumps(payload)}\n\n"

        # 모든 업데이트 완료 후 directory_cache 갱신
        try:
            yield f"data: {json.dumps({'status': 'progress', 'processed': processed_count, 'total': total_tasks, 'success': success_count, 'error': error_count, 'current_item': 'Refreshing Directory Cache...', 'log': '♻️ Refreshing directory cache...'})}\n\n"
            new_full_results = scan_full_directory(abs_path, root, is_3_level_structure)
            with get_db_connection() as conn:
                set_cached_directory_entries(abs_path, new_full_results, conn)
                conn.commit()
        except Exception as e:
            yield f"data: {json.dumps({'status': 'progress', 'processed': processed_count, 'total': total_tasks, 'success': success_count, 'error': error_count, 'log': f'❌ Cache Refresh Error: {e}'})}\n\n"

        yield f"data: {json.dumps({'status': 'done', 'total': total_tasks, 'success': success_count, 'error': error_count})}\n\n"

    return Response(generate(), mimetype='text/event-stream')


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