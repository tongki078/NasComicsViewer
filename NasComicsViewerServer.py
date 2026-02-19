from flask import Flask, jsonify, send_from_directory, request
import os
import urllib.parse
import unicodedata
import logging
import yaml
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

# 로그 설정
logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s: %(message)s')
logger = logging.getLogger(__name__)

app = Flask(__name__)

# --- 핵심 설정 ---
BASE_PATH = "/volume2/video/GDS3/GDRIVE/READING"
TARGET_ROOT_NAME = "만화"
SCAN_CACHE = {}
CACHE_EXPIRY = 600 # 10분 캐시

def normalize_nfc(s):
    if not isinstance(s, str): return s
    return unicodedata.normalize('NFC', s)

def find_folder_in_path(parent, target_name):
    try:
        if not os.path.exists(parent): return None
        target_norm = normalize_nfc(target_name).lower()
        with os.scandir(parent) as it:
            for entry in it:
                if entry.is_dir() and normalize_nfc(entry.name).lower() == target_norm:
                    return entry.path
    except: pass
    return None

ROOT_DIRECTORY = find_folder_in_path(BASE_PATH, TARGET_ROOT_NAME) or os.path.join(BASE_PATH, TARGET_ROOT_NAME)
logger.info(f"!!! 서버 루트 설정 완료: {ROOT_DIRECTORY} !!!")

def get_actual_abs_path(rel_path):
    if not rel_path or rel_path.strip() in [".", "", "/"]:
        return ROOT_DIRECTORY
    decoded_path = urllib.parse.unquote(rel_path).replace('\\', '/')
    parts = decoded_path.strip('/').split('/')
    curr = ROOT_DIRECTORY
    for part in parts:
        if not part: continue
        next_path = find_folder_in_path(curr, part)
        curr = next_path if next_path else os.path.join(curr, part)
    return curr

def get_metadata_internal(abs_path, rel_path):
    """메타데이터 추출 (개별 요청 시에만 실행)"""
    folder_name = normalize_nfc(os.path.basename(abs_path))
    # 기본 제목을 폴더명으로 설정 (매우 중요)
    meta = {"title": folder_name, "author": "", "summary": "", "posterUrl": None}

    try:
        if os.path.exists(abs_path):
            with os.scandir(abs_path) as it:
                entries = list(it)

                # 1. kavita.yaml 탐색 및 파싱
                kavita_entry = next((e for e in entries if normalize_nfc(e.name).lower() == "kavita.yaml"), None)
                if kavita_entry:
                    try:
                        with open(kavita_entry.path, 'r', encoding='utf-8', errors='ignore') as f:
                            data = yaml.safe_load(f)
                            if data:
                                meta['title'] = data.get('localizedName') or data.get('name') or meta['title']
                                meta['author'] = data.get('writer') or data.get('author') or ""
                                meta['summary'] = data.get('summary') or ""
                                p_name = data.get('poster_url') or data.get('cover')
                                if p_name:
                                    meta['posterUrl'] = normalize_nfc(os.path.join(rel_path, p_name)).replace('\\', '/')
                    except: pass

                # 2. 특정 키워드(poster, cover 등)가 포함된 이미지 우선 검색
                if not meta['posterUrl']:
                    for entry in entries:
                        if entry.is_file():
                            name_low = normalize_nfc(entry.name).lower()
                            if name_low.endswith(('.jpg', '.png', '.jpeg')) and \
                               any(x in name_low for x in ["poster", "cover", "folder", "thumb"]):
                                meta['posterUrl'] = normalize_nfc(os.path.join(rel_path, entry.name)).replace('\\', '/')
                                break

                # 3. 그래도 없으면 폴더 내 첫 번째 이미지를 포스터로 사용
                if not meta['posterUrl']:
                    for entry in entries:
                        if entry.is_file():
                            name_low = normalize_nfc(entry.name).lower()
                            if name_low.endswith(('.jpg', '.png', '.jpeg')):
                                meta['posterUrl'] = normalize_nfc(os.path.join(rel_path, entry.name)).replace('\\', '/')
                                break
    except Exception as e:
        logger.error(f"Metadata error for {abs_path}: {e}")

    return meta

def fast_scan_worker(folder_path, root_norm):
    results = []
    try:
        with os.scandir(folder_path) as it:
            for entry in it:
                if entry.is_dir(follow_symlinks=False):
                    rel = os.path.relpath(entry.path, root_norm)
                    results.append({
                        'name': normalize_nfc(entry.name),
                        'isDirectory': True,
                        'path': normalize_nfc(rel.replace('\\', '/')),
                        'metadata': None
                    })
    except: pass
    return results

@app.route('/scan')
def scan_comics():
    path = request.args.get('path', '')
    if path in SCAN_CACHE:
        ts, data = SCAN_CACHE[path]
        if time.time() - ts < CACHE_EXPIRY:
            return jsonify(data)

    logger.info(f"[HIGH-SPEED SCAN] {path} 시작")
    abs_start_path = get_actual_abs_path(path)
    root_norm = os.path.abspath(ROOT_DIRECTORY)

    if not os.path.exists(abs_start_path):
        return jsonify([])

    all_results = []
    try:
        initial_folders = []
        with os.scandir(abs_start_path) as it:
            for entry in it:
                if entry.is_dir(follow_symlinks=False):
                    initial_folders.append(entry.path)

        with ThreadPoolExecutor(max_workers=12) as executor:
            futures = [executor.submit(fast_scan_worker, f, root_norm) for f in initial_folders]
            for future in as_completed(futures):
                all_results.extend(future.result())

        final_data = sorted(all_results, key=lambda x: x['name'])
        SCAN_CACHE[path] = (time.time(), final_data)
        logger.info(f"[SCAN DONE] {len(final_data)}개 수집 완료")
        return jsonify(final_data)
    except Exception as e:
        logger.error(f"Scan error: {e}")
        return jsonify([])

@app.route('/metadata')
def get_metadata():
    path = request.args.get('path', '')
    abs_path = get_actual_abs_path(path)
    if not abs_path: return jsonify({}), 404
    return jsonify(get_metadata_internal(abs_path, path))

@app.route('/files')
def list_files():
    path = request.args.get('path', '')
    abs_path = get_actual_abs_path(path)
    if not abs_path or not os.path.isdir(abs_path): return jsonify([]), 404
    items = []
    try:
        with os.scandir(abs_path) as it:
            for entry in it:
                items.append({
                    'name': normalize_nfc(entry.name),
                    'isDirectory': entry.is_dir(),
                    'path': normalize_nfc(os.path.join(path, entry.name).replace('\\', '/'))
                })
        return jsonify(sorted(items, key=lambda x: x['name']))
    except: return jsonify([]), 500

@app.route('/download')
def download_file():
    path = request.args.get('path', '')
    abs_path = get_actual_abs_path(path)
    if not abs_path or not os.path.isfile(abs_path): return "Not found", 404
    return send_from_directory(os.path.dirname(abs_path), os.path.basename(abs_path))

@app.route('/debug')
def debug_info():
    return jsonify({
        'root': str(ROOT_DIRECTORY),
        'exists': os.path.exists(ROOT_DIRECTORY) if ROOT_DIRECTORY else False
    })

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5555, debug=False, threaded=True, use_reloader=False)
