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
# 시놀로지 NAS의 실제 경로 (NFC/NFD 호환성을 위해 로직에서 재검증됨)
BASE_PATH = "/volume2/video/GDS3/GDRIVE/READING/만화"

SCAN_CACHE = {}
CACHE_EXPIRY = 600
FS_CACHE = {}
FS_CACHE_TTL = 60

def normalize_nfc(s):
    if not isinstance(s, str): return s
    return unicodedata.normalize('NFC', s)

def get_fs_data(path):
    """폴더 내 항목을 읽어 캐시함. 반복적인 디스크 I/O 방지."""
    now = time.time()
    if path in FS_CACHE:
        ts, data = FS_CACHE[path]
        if now - ts < FS_CACHE_TTL:
            return data

    try:
        # 존재 여부 확인 전, os.path.isdir가 실패할 수 있으므로 상위에서 검증된 경로만 들어온다고 가정
        mapping = {}
        items = []
        with os.scandir(path) as it:
            for entry in it:
                name = entry.name
                is_dir = entry.is_dir(follow_symlinks=False)
                norm_lower = normalize_nfc(name).lower()
                mapping[norm_lower] = name
                items.append({'name': name, 'is_dir': is_dir})

        data = {'mapping': mapping, 'items': items}
        FS_CACHE[path] = (now, data)
        return data
    except Exception as e:
        logger.error(f"Directory read failed: {path} Error: {e}")
        return None

def find_actual_name_in_dir(parent, target_name):
    """NFC/NFD 및 대소문자 무시하고 실제 이름을 찾음"""
    target_norm = normalize_nfc(target_name).lower()
    try:
        with os.scandir(parent) as it:
            for entry in it:
                if normalize_nfc(entry.name).lower() == target_norm:
                    return entry.name
    except:
        pass
    return None

def get_robust_root():
    """BASE_PATH가 인코딩 문제로 안 보일 경우를 대비해 단계별로 경로를 재구성함"""
    if os.path.isdir(BASE_PATH):
        return os.path.normpath(os.path.abspath(BASE_PATH))

    parts = BASE_PATH.strip('/').split('/')
    curr = "/"
    if os.name == 'nt': # Windows check
        curr = parts[0] + ":\\"
        parts = parts[1:]

    for part in parts:
        if not part: continue
        actual = find_actual_name_in_dir(curr, part)
        if actual:
            curr = os.path.join(curr, actual)
        else:
            curr = os.path.join(curr, part)

    return os.path.normpath(os.path.abspath(curr))

def resolve_actual_path(rel_path):
    root = get_robust_root()
    if not rel_path or rel_path.strip() in [".", "", "/"]:
        return root

    parts = urllib.parse.unquote(rel_path).replace('\\', '/').strip('/').split('/')
    curr = root
    for part in parts:
        if not part: continue
        actual = find_actual_name_in_dir(curr, part)
        if actual:
            curr = os.path.join(curr, actual)
        else:
            curr = os.path.join(curr, part)

    return os.path.normpath(os.path.abspath(curr))

@app.route('/files')
def list_files():
    p = request.args.get('path', '')
    ap = resolve_actual_path(p)
    logger.info(f"[FILES] Request: '{p}' -> Resolved: '{ap}'")

    if not os.path.exists(ap):
        logger.warning(f"[FILES] Path not found: {ap}")
        return jsonify({"error": "Path not found", "path": ap}), 404

    if not os.path.isdir(ap):
        logger.warning(f"[FILES] Not a directory: {ap}")
        return jsonify({"error": "Not a directory", "path": ap}), 400

    data = get_fs_data(ap)
    if data is None:
        logger.error(f"[FILES] Failed to read directory: {ap}")
        return jsonify({"error": "Failed to read directory"}), 500

    items = []
    for item in data['items']:
        name = item['name']
        items.append({
            'name': normalize_nfc(name),
            'isDirectory': item['is_dir'],
            'path': normalize_nfc(os.path.join(p, name).replace('\\', '/'))
        })

    logger.info(f"[FILES] Returning {len(items)} items for {ap}")
    return jsonify(sorted(items, key=lambda x: x['name']))

@app.route('/download')
def download_file():
    p = request.args.get('path', '')
    logger.info(f"[DOWNLOAD] Request: '{p}'")

    if not p:
        logger.warning("[DOWNLOAD] Path missing in request")
        return "Path missing", 400

    if p.startswith("zip_thumb://"):
        rzp = p[len("zip_thumb://"):]
        azp = resolve_actual_path(rzp)
        logger.info(f"[DOWNLOAD] Zip Thumb processing for: {azp}")

        if os.path.isfile(azp):
            try:
                with zipfile.ZipFile(azp, 'r') as z:
                    il = sorted([n for n in z.namelist() if is_image_file(n)])
                    if il:
                        logger.info(f"[DOWNLOAD] Extracting thumb from zip: {il[0]}")
                        with z.open(il[0]) as f:
                            return send_file(io.BytesIO(f.read()), mimetype='image/jpeg')
                    else:
                        logger.warning(f"[DOWNLOAD] No images found in zip for thumb: {azp}")
            except Exception as e:
                logger.error(f"[DOWNLOAD] Thumb extraction error: {e}")
        else:
            logger.warning(f"[DOWNLOAD] Zip file not found for thumb: {azp}")
        return "Thumbnail not found", 404

    ap = resolve_actual_path(p)
    if not os.path.isfile(ap):
        logger.warning(f"[DOWNLOAD] File not found: {ap}")
        return "File not found", 404

    logger.info(f"[DOWNLOAD] Sending file: {ap}")
    return send_from_directory(os.path.dirname(ap), os.path.basename(ap))

@app.route('/scan')
def scan_comics():
    path = request.args.get('path', '')
    logger.info(f"[SCAN] Request: '{path}'")

    if path in SCAN_CACHE:
        ts, data = SCAN_CACHE[path]
        if time.time() - ts < CACHE_EXPIRY:
            logger.info(f"[SCAN] Cache hit for '{path}', returning {len(data)} items")
            return jsonify(data)

    root = get_robust_root()
    abs_path = resolve_actual_path(path)
    logger.info(f"[SCAN] Resolved path: {abs_path}")

    if not os.path.exists(abs_path) or not os.path.isdir(abs_path):
        logger.warning(f"[SCAN] Invalid path: {abs_path}")
        return jsonify({"error": "Invalid scan path"}), 404

    results = []
    try:
        data = get_fs_data(abs_path)
        if not data:
            logger.warning(f"[SCAN] Failed to get fs data for {abs_path}")
            return jsonify([]), 200

        scan_targets = []
        for item in data['items']:
            name = item['name']
            is_dir = item['is_dir']
            name_nfc = normalize_nfc(name)

            if is_dir:
                if len(name_nfc) <= 2 or name_nfc.lower() in ["완결a", "완결b", "완결"]:
                    scan_targets.append(os.path.join(abs_path, name))
                else:
                    rel = os.path.relpath(os.path.join(abs_path, name), root).replace(os.sep, '/')
                    results.append({
                        'name': clean_name(name_nfc),
                        'isDirectory': True,
                        'path': normalize_nfc(rel),
                        'metadata': None
                    })
            elif name.lower().endswith(('.zip', '.cbz')):
                # Fix: Use the correct relative path for the zip file itself
                rel = os.path.relpath(os.path.join(abs_path, name), root).replace(os.sep, '/')
                results.append({
                    'name': clean_name(normalize_nfc(name)),
                    'isDirectory': True,
                    'path': normalize_nfc(rel),
                    'metadata': None
                })

        if scan_targets:
            logger.info(f"[SCAN] Scanning {len(scan_targets)} sub-directories recursively...")
            with ThreadPoolExecutor(max_workers=8) as executor:
                futures = [executor.submit(scan_worker, t, root) for t in scan_targets]
                for f in as_completed(futures):
                    res_chunk = f.result()
                    results.extend(res_chunk)

        final = []
        seen = set()
        for r in results:
            if r['path'] not in seen:
                seen.add(r['path'])
                final.append(r)

        final.sort(key=lambda x: x['name'])
        SCAN_CACHE[path] = (time.time(), final)
        logger.info(f"[SCAN] Completed. Found {len(final)} unique items for '{path}'")
        return jsonify(final)
    except Exception as e:
        logger.error(f"[SCAN] Critical Error: {e}", exc_info=True)
        return jsonify({"error": str(e)}), 500

def scan_worker(fp, root):
    res = []
    data = get_fs_data(fp)
    if not data: return res

    has_sub_dir = False
    for item in data['items']:
        if item['is_dir']:
            has_sub_dir = True
            rel = os.path.relpath(os.path.join(fp, item['name']), root).replace(os.sep, '/')
            res.append({
                'name': clean_name(normalize_nfc(item['name'])),
                'isDirectory': True,
                'path': normalize_nfc(rel),
                'metadata': None
            })

    if not has_sub_dir:
        rel = os.path.relpath(fp, root).replace(os.sep, '/')
        res.append({
            'name': clean_name(normalize_nfc(os.path.basename(fp))),
            'isDirectory': True,
            'path': normalize_nfc(rel),
            'metadata': None
        })
    return res

def clean_name(name):
    if not name: return ""
    return os.path.splitext(name)[0].strip()

def is_image_file(name):
    if not name: return False
    return name.lower().endswith(('.jpg', '.jpeg', '.png', '.webp', '.gif'))

@app.route('/metadata')
def get_metadata():
    p = request.args.get('path', '')
    logger.info(f"[METADATA] Request: '{p}'")
    ap = resolve_actual_path(p)
    return jsonify(get_metadata_internal(ap, p))

def get_metadata_internal(abs_path, rel_path):
    folder_name = clean_name(normalize_nfc(os.path.basename(abs_path.rstrip('/\\'))))
    meta = {"title": folder_name or "Untitled", "author": "", "summary": "", "poster_url": None}

    data = get_fs_data(abs_path)
    if not data: return meta

    try:
        # 1. Kavita YAML
        kavita_actual = data['mapping'].get("kavita.yaml")
        if kavita_actual:
            logger.info(f"[METADATA] Found kavita.yaml in {abs_path}")
            try:
                with open(os.path.join(abs_path, kavita_actual), 'r', encoding='utf-8', errors='ignore') as f:
                    y = yaml.safe_load(f)
                    if y:
                        m = y.get('meta', {})
                        t = m.get('Name') or m.get('localizedName') or meta['title']
                        meta.update({
                            'title': clean_name(str(t)),
                            'author': str(m.get('Person Writers') or m.get('Writer') or ""),
                            'summary': str(m.get('Summary') or "")
                        })
            except Exception as e:
                 logger.error(f"[METADATA] Error reading kavita.yaml: {e}")

        # 2. Poster 이미지
        if not meta['poster_url']:
            for item in data['items']:
                name = item['name']
                if not item['is_dir'] and is_image_file(name):
                    if any(x in name.lower() for x in ["poster", "cover", "folder", "thumb"]):
                        meta['poster_url'] = os.path.join(rel_path, name).replace('\\', '/')
                        logger.info(f"[METADATA] Found poster image: {meta['poster_url']}")
                        break

        # 3. Zip 썸네일 Fallback
        if not meta['poster_url']:
            for item in data['items']:
                name = item['name']
                if not item['is_dir'] and name.lower().endswith(('.zip', '.cbz')):
                    safe_path = os.path.join(rel_path, name).replace('\\', '/')
                    meta['poster_url'] = "zip_thumb://" + safe_path
                    logger.info(f"[METADATA] Using zip thumb fallback: {meta['poster_url']}")
                    break
    except Exception as e:
        logger.error(f"[METADATA] Error extracting metadata: {e}")

    return meta

@app.route('/metrics')
def metrics():
    return "OK", 200

if __name__ == '__main__':
    try:
        logger.info("===== SERVER STARTING =====")
        root = get_robust_root()
        logger.info(f"Root Resolved to: {root}")
        if not os.path.exists(root):
            logger.critical(f"ROOT PATH DOES NOT EXIST: {root}")
        app.run(host='0.0.0.0', port=5555, debug=False, threaded=True, use_reloader=False)
    except Exception as e:
        logger.critical("FATAL STARTUP ERROR: " + str(e), exc_info=True)
        sys.exit(1)
