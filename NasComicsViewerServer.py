from flask import Flask, jsonify, send_from_directory, request, send_file
import os
import urllib.parse
import unicodedata
import logging
import yaml
import time
import zipfile
import io
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

# 서버 시작 시 루트 디렉토리 확정
ROOT_DIRECTORY = find_folder_in_path(BASE_PATH, TARGET_ROOT_NAME) or os.path.join(BASE_PATH, TARGET_ROOT_NAME)
ROOT_NORM = os.path.normpath(os.path.abspath(ROOT_DIRECTORY))
logger.info(f"!!! 서버 루트 설정 완료: {ROOT_NORM} !!!")

def get_safe_rel_path(abs_path):
    """절대 경로에서 ROOT_NORM을 제외한 상대 경로 추출 (시스템 차이 극복)"""
    abs_path = os.path.normpath(os.path.abspath(abs_path))
    if abs_path.startswith(ROOT_NORM):
        rel = abs_path[len(ROOT_NORM):].lstrip(os.sep).replace(os.sep, '/')
        return rel
    try:
        return os.path.relpath(abs_path, ROOT_NORM).replace(os.sep, '/')
    except:
        return abs_path

def get_actual_abs_path(rel_path):
    if not rel_path or rel_path.strip() in [".", "", "/"]:
        return ROOT_NORM
    decoded_path = urllib.parse.unquote(rel_path).replace('\\', '/')
    parts = decoded_path.strip('/').split('/')
    curr = ROOT_NORM
    for part in parts:
        if not part: continue
        next_path = find_folder_in_path(curr, part)
        curr = next_path if next_path else os.path.join(curr, part)
    return os.path.normpath(os.path.abspath(curr))

def get_metadata_internal(abs_path, rel_path):
    """제공된 kavita.yaml 구조(meta, search)를 완벽하게 파싱"""
    folder_name = normalize_nfc(os.path.basename(abs_path))
    meta = {"title": folder_name, "author": "", "summary": "", "poster_url": None}
    try:
        if os.path.exists(abs_path):
            with os.scandir(abs_path) as it:
                entries = sorted(list(it), key=lambda x: x.name)

                kavita_entry = next((e for e in entries if normalize_nfc(e.name).lower() == "kavita.yaml"), None)
                if kavita_entry:
                    try:
                        with open(kavita_entry.path, 'r', encoding='utf-8', errors='ignore') as f:
                            data = yaml.safe_load(f)
                            if data:
                                m_section = data.get('meta', {})
                                meta['title'] = m_section.get('Name') or m_section.get('localizedName') or meta['title']
                                meta['author'] = m_section.get('Person Writers') or m_section.get('Writer') or ""
                                meta['summary'] = m_section.get('Summary') or ""

                                s_list = data.get('search', [])
                                if isinstance(s_list, list) and len(s_list) > 0:
                                    s = s_list[0]
                                    p_url = s.get('poster_url') or s.get('cover')
                                    if p_url:
                                        if p_url.startswith('http'):
                                            meta['poster_url'] = p_url
                                        else:
                                            meta['poster_url'] = normalize_nfc(os.path.join(rel_path, p_url)).replace('\\', '/')

                                    if meta['title'] == folder_name: meta['title'] = s.get('title') or meta['title']
                                    if not meta['author']: meta['author'] = s.get('author') or ""
                                    if not meta['summary']: meta['summary'] = s.get('description') or ""
                    except: pass

                if not meta['poster_url']:
                    for entry in entries:
                        if entry.is_file():
                            name_low = normalize_nfc(entry.name).lower()
                            if name_low.endswith(('.jpg', '.png', '.jpeg')) and \
                               any(x in name_low for x in ["poster", "cover", "folder", "thumb"]):
                                meta['poster_url'] = normalize_nfc(os.path.join(rel_path, entry.name)).replace('\\', '/')
                                break

                if not meta['poster_url']:
                    comic_file = next((e for e in entries if e.is_file() and e.name.lower().endswith(('.zip', '.cbz'))), None)
                    if comic_file:
                        thumb_path = normalize_nfc(os.path.join(rel_path, comic_file.name)).replace('\\', '/')
                        meta['poster_url'] = f"zip_thumb://{thumb_path}"
    except: pass
    return meta

def fast_scan_worker(folder_path, root_norm):
    results = []
    try:
        found_sub_dir = False
        with os.scandir(folder_path) as it:
            for entry in it:
                if entry.is_dir():
                    found_sub_dir = True
                    rel = get_safe_rel_path(entry.path)
                    results.append({
                        'name': normalize_nfc(entry.name),
                        'isDirectory': True,
                        'path': normalize_nfc(rel),
                        'metadata': None
                    })
        if not found_sub_dir:
            rel = get_safe_rel_path(folder_path)
            results.append({
                'name': normalize_nfc(os.path.basename(folder_path)),
                'isDirectory': True,
                'path': normalize_nfc(rel),
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

    abs_start_path = get_actual_abs_path(path)
    logger.info(f"[SCAN] 요청: {path} -> {abs_start_path}")

    if not os.path.exists(abs_start_path):
        return jsonify([])

    all_results = []
    groups = []
    try:
        with os.scandir(abs_start_path) as it:
            for entry in it:
                if entry.is_dir():
                    name = normalize_nfc(entry.name)
                    # 2글자 이하(ㄱ, ㄴ, # 등)는 그룹 폴더로 분류하여 병렬 스캔
                    if len(name) <= 2:
                        groups.append(entry.path)
                    else:
                        rel = get_safe_rel_path(entry.path)
                        all_results.append({
                            'name': name,
                            'isDirectory': True,
                            'path': normalize_nfc(rel),
                            'metadata': None
                        })
                # 카테고리 바로 밑에 압축파일이 있는 경우 대응 (단층 구조)
                elif entry.is_file() and entry.name.lower().endswith(('.zip', '.cbz')):
                    rel = get_safe_rel_path(abs_start_path)
                    all_results.append({
                        'name': normalize_nfc(os.path.basename(abs_start_path)),
                        'isDirectory': True,
                        'path': normalize_nfc(rel),
                        'metadata': None
                    })

        if groups:
            with ThreadPoolExecutor(max_workers=15) as executor:
                futures = [executor.submit(fast_scan_worker, f, ROOT_NORM) for f in groups]
                for future in as_completed(futures):
                    all_results.extend(future.result())

        seen_paths = set()
        unique_results = []
        for r in all_results:
            if r['path'] not in seen_paths:
                seen_paths.add(r['path'])
                unique_results.append(r)

        final_data = sorted(unique_results, key=lambda x: x['name'])
        SCAN_CACHE[path] = (time.time(), final_data)
        logger.info(f"[SCAN DONE] {len(final_data)}개 발견")
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
    if path.startswith('http'): return "External URL", 400

    if path.startswith("zip_thumb://"):
        rel_zip_path = path[len("zip_thumb://"):]
        abs_zip_path = get_actual_abs_path(rel_zip_path)
        if abs_zip_path and os.path.isfile(abs_zip_path):
            try:
                with zipfile.ZipFile(abs_zip_path, 'r') as z:
                    img_list = sorted([n for n in z.namelist() if n.lower().endswith(('.jpg', '.jpeg', '.png', '.webp', '.gif'))])
                    if img_list:
                        with z.open(img_list[0]) as f:
                            return send_file(io.BytesIO(f.read()), mimetype='image/jpeg')
            except: pass
        return "Not found", 404

    abs_path = get_actual_abs_path(path)
    if not abs_path or not os.path.isfile(abs_path): return "Not found", 404
    return send_from_directory(os.path.dirname(abs_path), os.path.basename(abs_path))

@app.route('/debug')
def debug_info():
    return jsonify({'root': ROOT_NORM, 'exists': os.path.exists(ROOT_NORM)})

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5555, debug=False, threaded=True, use_reloader=False)
