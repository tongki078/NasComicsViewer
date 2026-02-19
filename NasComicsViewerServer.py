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
CACHE_EXPIRY = 600

def normalize_nfc(s):
    if not isinstance(s, str): return s
    return unicodedata.normalize('NFC', s)

def find_folder_in_path(parent, target_name):
    try:
        if not os.path.exists(parent): return None
        target_norm = normalize_nfc(target_name).lower()
        with os.scandir(parent) as it:
            for entry in it:
                if normalize_nfc(entry.name).lower() == target_norm:
                    return entry.path
    except: pass
    return None

# 초기 루트 설정
_ROOT_DIR = find_folder_in_path(BASE_PATH, TARGET_ROOT_NAME) or os.path.join(BASE_PATH, TARGET_ROOT_NAME)
ROOT_NORM = os.path.normpath(os.path.abspath(_ROOT_DIR))

def get_root():
    """마운트 해제 대응을 위해 루트 경로 재확인 로직 포함"""
    global ROOT_NORM
    if not os.path.isdir(ROOT_NORM):
        new_root = find_folder_in_path(BASE_PATH, TARGET_ROOT_NAME) or os.path.join(BASE_PATH, TARGET_ROOT_NAME)
        ROOT_NORM = os.path.normpath(os.path.abspath(new_root))
    return ROOT_NORM

logger.info(f"!!! 서버 루트 설정 완료: {ROOT_NORM} !!!")

def get_safe_rel_path(abs_path):
    root = get_root()
    abs_path = os.path.normpath(os.path.abspath(abs_path))
    if abs_path.startswith(root):
        return abs_path[len(root):].lstrip(os.sep).replace(os.sep, '/')
    try:
        return os.path.relpath(abs_path, root).replace(os.sep, '/')
    except:
        return abs_path

def get_actual_abs_path(rel_path):
    root = get_root()
    if not rel_path or rel_path.strip() in [".", "", "/"]:
        return root
    decoded_path = urllib.parse.unquote(rel_path).replace('\\', '/')
    parts = decoded_path.strip('/').split('/')
    curr = root
    for part in parts:
        if not part: continue
        next_path = find_folder_in_path(curr, part)
        curr = next_path if next_path else os.path.join(curr, part)
    return os.path.normpath(os.path.abspath(curr))

def get_metadata_internal(abs_path, rel_path):
    """kavita.yaml의 meta 및 search 섹션을 완벽하게 파싱"""
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
                                m = data.get('meta', {})
                                meta['title'] = m.get('Name') or m.get('localizedName') or meta['title']
                                meta['author'] = m.get('Person Writers') or m.get('Writer') or ""
                                meta['summary'] = m.get('Summary') or ""
                                s_list = data.get('search', [])
                                if isinstance(s_list, list) and len(s_list) > 0:
                                    s = s_list[0]
                                    p_url = s.get('poster_url') or s.get('cover')
                                    if p_url:
                                        if p_url.startswith('http'): meta['poster_url'] = p_url
                                        else: meta['poster_url'] = normalize_nfc(os.path.join(rel_path, p_url)).replace('\\', '/')
                                    if meta['title'] == folder_name: meta['title'] = s.get('title') or meta['title']
                                    if not meta['author']: meta['author'] = s.get('author') or ""
                                    if not meta['summary']: meta['summary'] = s.get('description') or ""
                    except: pass

                # 이미지 및 압축파일 첫 페이지 처리 (필드명 poster_url 사용)
                if not meta['poster_url']:
                    for entry in entries:
                        if entry.is_file():
                            nl = normalize_nfc(entry.name).lower()
                            if nl.endswith(('.jpg', '.png', '.jpeg')) and any(x in nl for x in ["poster", "cover", "folder", "thumb"]):
                                meta['poster_url'] = normalize_nfc(os.path.join(rel_path, entry.name)).replace('\\', '/')
                                break
                if not meta['poster_url']:
                    comic = next((e for e in entries if e.is_file() and e.name.lower().endswith(('.zip', '.cbz'))), None)
                    if comic:
                        tp = normalize_nfc(os.path.join(rel_path, comic.name)).replace('\\', '/')
                        meta['poster_url'] = f"zip_thumb://{tp}"
    except: pass
    return meta

def scan_worker(fp):
    res = []
    try:
        sub = False
        with os.scandir(fp) as it:
            for e in it:
                if os.path.isdir(e.path):
                    sub = True
                    rel = get_safe_rel_path(e.path)
                    res.append({'name': normalize_nfc(e.name), 'isDirectory': True, 'path': normalize_nfc(rel), 'metadata': None})
        if not sub:
            rel = get_safe_rel_path(fp)
            res.append({'name': normalize_nfc(os.path.basename(fp)), 'isDirectory': True, 'path': normalize_nfc(rel), 'metadata': None})
    except: pass
    return res

@app.route('/scan')
def scan_comics():
    path = request.args.get('path', '')
    if path in SCAN_CACHE:
        ts, data = SCAN_CACHE[path]
        if time.time() - ts < CACHE_EXPIRY: return jsonify(data)

    abs_path = get_actual_abs_path(path)
    logger.info(f"[SCAN] '{path}' -> {abs_path}")
    if not os.path.exists(abs_path): return jsonify([])

    all_res, grps = [], []
    try:
        with os.scandir(abs_path) as it:
            for entry in it:
                if os.path.isdir(entry.path):
                    name = normalize_nfc(entry.name)
                    # 특정 카테고리나 짧은 이름은 하위 스캔
                    if len(name) <= 2 or path.lower() in ["완결a", "완결b", "완결"]:
                        grps.append(entry.path)
                    else:
                        rel = get_safe_rel_path(entry.path)
                        all_res.append({'name': name, 'isDirectory': True, 'path': normalize_nfc(rel), 'metadata': None})
                elif entry.is_file() and entry.name.lower().endswith(('.zip', '.cbz')):
                    rel = get_safe_rel_path(abs_path)
                    all_res.append({'name': normalize_nfc(os.path.basename(abs_path)), 'isDirectory': True, 'path': normalize_nfc(rel), 'metadata': None})

        if grps:
            with ThreadPoolExecutor(max_workers=15) as ex:
                futs = [ex.submit(scan_worker, f) for f in grps]
                for f in as_completed(futs): all_res.extend(f.result())

        seen = set()
        uniq = []
        for r in all_res:
            if r['path'] not in seen:
                seen.add(r['path']); uniq.append(r)

        fin = sorted(uniq, key=lambda x: x['name'])
        if fin: SCAN_CACHE[path] = (time.time(), fin)
        logger.info(f"[SCAN DONE] {len(fin)}개 발견")
        return jsonify(fin)
    except Exception as e:
        logger.error(f"Scan error: {e}"); return jsonify([])

@app.route('/metadata')
def get_metadata():
    p = request.args.get('path', ''); ap = get_actual_abs_path(p)
    return jsonify(get_metadata_internal(ap, p))

@app.route('/files')
def list_files():
    p = request.args.get('path', ''); ap = get_actual_abs_path(p)
    if not os.path.isdir(ap):
        logger.error(f"Not a dir: {ap}")
        return jsonify([]), 404
    items = []
    try:
        with os.scandir(ap) as it:
            for e in it:
                items.append({'name': normalize_nfc(e.name), 'isDirectory': os.path.isdir(e.path), 'path': normalize_nfc(os.path.join(p, e.name).replace('\\', '/'))})
        return jsonify(sorted(items, key=lambda x: x['name']))
    except: return jsonify([]), 500

@app.route('/download')
def download_file():
    p = request.args.get('path', '')
    if p.startswith("zip_thumb://"):
        rzp = p[len("zip_thumb://"):]
        azp = get_actual_abs_path(rzp)
        if azp and os.path.isfile(azp):
            try:
                with zipfile.ZipFile(azp, 'r') as z:
                    il = sorted([n for n in z.namelist() if n.lower().endswith(('.jpg', '.jpeg', '.png', '.webp', '.gif'))])
                    if il:
                        with z.open(il[0]) as f: return send_file(io.BytesIO(f.read()), mimetype='image/jpeg')
            except: pass
        return "Not found", 404
    ap = get_actual_abs_path(p)
    if not os.path.isfile(ap): return "Not found", 404
    return send_from_directory(os.path.dirname(ap), os.path.basename(ap))

@app.route('/debug')
def debug_info():
    root = get_root()
    return jsonify({'root': root, 'exists': os.path.exists(root)})

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5555, debug=False, threaded=True, use_reloader=False)
