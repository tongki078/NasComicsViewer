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

# 루트 설정 복구 로직
_ROOT_DIR = find_folder_in_path(BASE_PATH, TARGET_ROOT_NAME) or os.path.join(BASE_PATH, TARGET_ROOT_NAME)
ROOT_NORM = os.path.normpath(os.path.abspath(_ROOT_DIR))

def get_root():
    global ROOT_NORM
    if not os.path.isdir(ROOT_NORM):
        new_root = find_folder_in_path(BASE_PATH, TARGET_ROOT_NAME) or os.path.join(BASE_PATH, TARGET_ROOT_NAME)
        ROOT_NORM = os.path.normpath(os.path.abspath(new_root))
    return ROOT_NORM

def get_safe_rel_path(abs_path):
    root = get_root()
    abs_path = os.path.normpath(os.path.abspath(abs_path))
    if abs_path.startswith(root):
        rel = abs_path[len(root):].lstrip(os.sep).replace(os.sep, '/')
        return rel
    try:
        return os.path.relpath(abs_path, root).replace(os.sep, '/')
    except:
        return abs_path

def get_actual_abs_path(rel_path):
    root = get_root()
    if not rel_path or rel_path.strip() in [".", "", "/"]:
        return root
    decoded_path = urllib.parse.unquote(rel_path).replace('\\', '/').rstrip('/')
    parts = decoded_path.strip('/').split('/')
    curr = root
    for part in parts:
        if not part: continue
        next_path = find_folder_in_path(curr, part)
        curr = next_path if next_path else os.path.join(curr, part)
    return os.path.normpath(os.path.abspath(curr))

def clean_name(name):
    if not name: return ""
    res = name
    for ext in ['.zip', '.cbz', '.rar', '.pdf', '.7z']:
        if res.lower().endswith(ext):
            res = res[:-len(ext)]
            break
    return res.strip()

def get_metadata_internal(abs_path, rel_path_for_images):
    """제목을 폴더명으로 강제 고정하여 누락 방지"""
    clean_abs_path = abs_path.rstrip('/\\')
    folder_name = clean_name(normalize_nfc(os.path.basename(clean_abs_path)))

    # [무조건 제목 보장]
    meta = {"title": folder_name or "No Title", "author": "", "summary": "", "poster_url": None}

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
                                if m.get('Name'): meta['title'] = clean_name(str(m.get('Name')))
                                meta['author'] = str(m.get('Person Writers') or m.get('Writer') or "")
                                meta['summary'] = str(m.get('Summary') or "")

                                s_list = data.get('search', [])
                                if isinstance(s_list, list) and len(s_list) > 0:
                                    s = s_list[0]
                                    p_url = s.get('poster_url') or s.get('cover')
                                    if p_url:
                                        if p_url.startswith('http'): meta['poster_url'] = p_url
                                        else: meta['poster_url'] = normalize_nfc(os.path.join(rel_path_for_images, p_url)).replace('\\', '/')
                    except: pass

                if not meta['poster_url']:
                    for entry in entries:
                        if entry.is_file() and entry.name.lower().endswith(('.jpg', '.jpeg', '.png', '.webp')):
                            if any(x in entry.name.lower() for x in ["poster", "cover", "folder", "thumb"]):
                                meta['poster_url'] = normalize_nfc(os.path.join(rel_path_for_images, entry.name)).replace('\\', '/')
                                break

                if not meta['poster_url']:
                    comic_file = next((e for e in entries if e.is_file() and e.name.lower().endswith(('.zip', '.cbz'))), None)
                    if comic_file:
                        tp = normalize_nfc(os.path.join(rel_path_for_images, comic_file.name)).replace('\\', '/')
                        meta['poster_url'] = f"zip_thumb://{tp}"
    except: pass

    logger.info(f" -> [META] {folder_name} | Title: {meta['title']} | Poster: {meta['poster_url'] is not None}")
    return meta

@app.route('/files', methods=['GET'])
def list_files():
    p = request.args.get('path', '')
    ap = get_actual_abs_path(p)
    items = []
    try:
        if os.path.exists(ap):
            with os.scandir(ap) as it:
                for e in it:
                    items.append({
                        'name': normalize_nfc(e.name),
                        'isDirectory': os.path.isdir(e.path),
                        'path': normalize_nfc(os.path.join(p, e.name).replace('\\', '/'))
                    })
        return jsonify(sorted(items, key=lambda x: x['name']))
    except:
        return jsonify([])

@app.route('/scan', methods=['GET'])
def scan_comics():
    path = request.args.get('path', '')
    if path in SCAN_CACHE:
        ts, data = SCAN_CACHE[path]
        if time.time() - ts < CACHE_EXPIRY: return jsonify(data)

    abs_path = get_actual_abs_path(path)
    logger.info(f"[SCAN] '{path}' 시작")
    if not os.path.exists(abs_path): return jsonify([])

    all_res, grps = [], []
    try:
        with os.scandir(abs_path) as it:
            for entry in it:
                if os.path.isdir(entry.path):
                    name = normalize_nfc(entry.name)
                    if len(name) <= 2 or path.lower() in ["완결a", "완결b"]:
                        grps.append(entry.path)
                    else:
                        rel = get_safe_rel_path(entry.path)
                        all_res.append({'name': clean_name(name), 'isDirectory': True, 'path': normalize_nfc(rel), 'metadata': None})
                elif entry.is_file() and entry.name.lower().endswith(('.zip', '.cbz')):
                    rel = get_safe_rel_path(abs_path)
                    all_res.append({'name': clean_name(os.path.basename(abs_path.rstrip('/\\'))), 'isDirectory': True, 'path': normalize_nfc(rel), 'metadata': None})

        if grps:
            with ThreadPoolExecutor(max_workers=15) as ex:
                futs = [ex.submit(scan_worker, f) for f in grps]
                for future in as_completed(futs): all_res.extend(future.result())

        seen_paths = set()
        uniq = []
        for r in all_res:
            if r['path'] not in seen_paths:
                seen_paths.add(r['path']); uniq.append(r)

        fin = sorted(uniq, key=lambda x: x['name'])
        if fin: SCAN_CACHE[path] = (time.time(), fin)
        return jsonify(fin)
    except:
        return jsonify([])

def scan_worker(fp):
    res = []
    try:
        sub = False
        with os.scandir(fp) as it:
            for e in it:
                if os.path.isdir(e.path):
                    sub = True
                    rel = get_safe_rel_path(e.path)
                    res.append({'name': clean_name(normalize_nfc(e.name)), 'isDirectory': True, 'path': normalize_nfc(rel), 'metadata': None})
        if not sub:
            rel = get_safe_rel_path(fp)
            res.append({'name': clean_name(normalize_nfc(os.path.basename(fp.rstrip('/\\')))), 'isDirectory': True, 'path': normalize_nfc(rel), 'metadata': None})
    except: pass
    return res

@app.route('/metadata')
def get_metadata():
    p = request.args.get('path', '')
    ap = get_actual_abs_path(p)
    return jsonify(get_metadata_internal(ap, p))

@app.route('/download')
def download_file():
    p = request.args.get('path', '')
    if not p: return "No path", 200

    if p.startswith("zip_thumb://"):
        rzp = p[len("zip_thumb://"):]
        azp = get_actual_abs_path(rzp)
        if azp and os.path.isfile(azp):
            try:
                with zipfile.ZipFile(azp, 'r') as z:
                    il = sorted([n for n in z.namelist() if n.lower().endswith(('.jpg', '.jpeg', '.png', '.webp', '.gif'))])
                    if il:
                        logger.info(f" -> [THUMB] {os.path.basename(azp)}")
                        with z.open(il[0]) as f: return send_file(io.BytesIO(f.read()), mimetype='image/jpeg')
            except: pass
        return "Not found", 200

    ap = get_actual_abs_path(p)
    if not os.path.isfile(ap): return "Not found", 200
    return send_from_directory(os.path.dirname(ap), os.path.basename(ap))

@app.route('/debug')
def debug_info():
    root = get_root()
    return jsonify({'root': root, 'exists': os.path.exists(root)})

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5555, debug=False, threaded=True, use_reloader=False)
