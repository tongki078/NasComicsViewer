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
BASE_PATH = "/volume2/video/GDS3/GDRIVE/READING/만화"

SCAN_CACHE = {}
CACHE_EXPIRY = 600
FS_CACHE = {}
FS_CACHE_TTL = 60

def normalize_nfc(s):
    if not isinstance(s, str): return s
    return unicodedata.normalize('NFC', s)

def get_fs_data(path):
    now = time.time()
    if path in FS_CACHE:
        ts, data = FS_CACHE[path]
        if now - ts < FS_CACHE_TTL:
            return data

    try:
        # 파일인 경우 처리 불가
        if os.path.isfile(path):
            return None

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
        print(f"❌ [ERROR] Directory read failed: {path} Error: {e}", flush=True)
        return None

def find_actual_name_in_dir(parent, target_name):
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
    if os.path.isdir(BASE_PATH):
        return os.path.normpath(os.path.abspath(BASE_PATH))
    return BASE_PATH

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
    print(f"📂 [FILES] Request: '{p}' -> Resolved: '{ap}'", flush=True)

    if not os.path.exists(ap):
        return jsonify({"error": "Path not found", "path": ap}), 404
    if not os.path.isdir(ap):
        return jsonify({"error": "Not a directory", "path": ap}), 400

    data = get_fs_data(ap)
    if data is None: return jsonify({"error": "Failed to read directory"}), 500

    items = []
    for item in data['items']:
        name = item['name']
        items.append({
            'name': normalize_nfc(name),
            'isDirectory': item['is_dir'],
            'path': normalize_nfc(os.path.join(p, name).replace('\\', '/'))
        })
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
                    all_files = z.namelist()
                    # 1. 이미지 파일 필터링
                    images = [n for n in all_files if is_image_file(n)]
                    images.sort()

                    if images:
                        target = images[0]
                        with z.open(target) as f:
                            # 성공 로그 추가 (디버깅용)
                            print(f"✅ [DOWNLOAD] Sending thumb from {os.path.basename(azp)} ({target})", flush=True)
                            return send_file(io.BytesIO(f.read()), mimetype='image/jpeg')
                    else:
                        print(f"⚠️ [DOWNLOAD] No images found in zip: {os.path.basename(azp)} (Files: {len(all_files)})", flush=True)
            except Exception as e:
                print(f"❌ [DOWNLOAD] Zip error: {azp} -> {e}", flush=True)
        else:
            print(f"⚠️ [DOWNLOAD] Zip file missing: {azp}", flush=True)
        return "Thumbnail not found", 404

    ap = resolve_actual_path(p)
    if not os.path.isfile(ap): return "File not found", 404
    print(f"✅ [DOWNLOAD] Sending file: {os.path.basename(ap)}", flush=True)
    return send_from_directory(os.path.dirname(ap), os.path.basename(ap))

@app.route('/scan')
def scan_comics():
    path = request.args.get('path', '')
    if path in SCAN_CACHE:
        ts, data = SCAN_CACHE[path]
        if time.time() - ts < CACHE_EXPIRY:
            return jsonify(data)

    root = get_robust_root()
    abs_path = resolve_actual_path(path)
    print(f"🔍 [SCAN] Request: {path} -> {abs_path}", flush=True)

    if not os.path.exists(abs_path) or not os.path.isdir(abs_path):
        return jsonify({"error": "Invalid scan path"}), 404

    results = []
    try:
        data = get_fs_data(abs_path)
        if not data: return jsonify([]), 200

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
                    results.append({'name': clean_name(name_nfc), 'isDirectory': True, 'path': normalize_nfc(rel), 'metadata': None})
            elif name.lower().endswith(('.zip', '.cbz')):
                rel = os.path.relpath(os.path.join(abs_path, name), root).replace(os.sep, '/')
                results.append({'name': clean_name(normalize_nfc(name)), 'isDirectory': True, 'path': normalize_nfc(rel), 'metadata': None})

        if scan_targets:
            with ThreadPoolExecutor(max_workers=8) as executor:
                futures = [executor.submit(scan_worker, t, root) for t in scan_targets]
                for f in as_completed(futures):
                    results.extend(f.result())

        final = []
        seen = set()
        for r in results:
            if r['path'] not in seen:
                seen.add(r['path'])
                final.append(r)

        final.sort(key=lambda x: x['name'])
        SCAN_CACHE[path] = (time.time(), final)
        print(f"✅ [SCAN] Found {len(final)} items", flush=True)
        return jsonify(final)
    except Exception as e:
        print(f"❌ [SCAN] Error: {e}", flush=True)
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
            res.append({'name': clean_name(normalize_nfc(item['name'])), 'isDirectory': True, 'path': normalize_nfc(rel), 'metadata': None})

    if not has_sub_dir:
        rel = os.path.relpath(fp, root).replace(os.sep, '/')
        res.append({'name': clean_name(normalize_nfc(os.path.basename(fp))), 'isDirectory': True, 'path': normalize_nfc(rel), 'metadata': None})
    return res

def clean_name(name):
    return os.path.splitext(name)[0].strip() if name else ""

def is_image_file(name):
    return name and name.lower().endswith(('.jpg', '.jpeg', '.png', '.webp', '.gif'))

@app.route('/metadata')
def get_metadata():
    p = request.args.get('path', '')
    ap = resolve_actual_path(p)
    meta = get_metadata_internal(ap, p)

    if meta['poster_url']:
        print(f"🖼️ [METADATA] Found for '{p}': {meta['poster_url']}", flush=True)
    else:
        try:
             data = get_fs_data(ap)
             if data:
                 files = [x['name'] for x in data['items']]
                 print(f"⚠️ [METADATA] NO POSTER for '{p}' (Resolved: {ap}). Files: {files[:5]}...", flush=True)
             else:
                 print(f"⚠️ [METADATA] NO POSTER for '{p}'. Dir empty or unreadable.", flush=True)
        except: pass
    return jsonify(meta)

def find_first_valid_thumb(abs_path, rel_path, depth=0):
    if depth > 3: return None
    try:
        data = get_fs_data(abs_path)
        if not data: return None

        items = data['items']

        # 1. Zip check
        for item in items:
            if not item['is_dir'] and item['name'].lower().endswith(('.zip', '.cbz')):
                return "zip_thumb://" + os.path.join(rel_path, item['name']).replace('\\', '/')

        # 2. Image check
        for item in items:
            if not item['is_dir'] and is_image_file(item['name']):
                 name = item['name']
                 if any(x in name.lower() for x in ["poster", "cover", "folder", "thumb"]):
                     return os.path.join(rel_path, name).replace('\\', '/')

        # 3. Any image fallback
        for item in items:
            if not item['is_dir'] and is_image_file(item['name']):
                return os.path.join(rel_path, item['name']).replace('\\', '/')

        # 4. Recurse
        sorted_items = sorted(items, key=lambda x: x['name'])
        for item in sorted_items:
            if item['is_dir']:
                found = find_first_valid_thumb(
                    os.path.join(abs_path, item['name']),
                    os.path.join(rel_path, item['name']),
                    depth + 1
                )
                if found: return found
    except Exception as e:
        print(f"❌ [ThumbSearch] Error: {e}", flush=True)
    return None

def get_metadata_internal(abs_path, rel_path):
    if os.path.isfile(abs_path):
        folder_name = clean_name(normalize_nfc(os.path.basename(abs_path)))
        meta = {"title": folder_name, "author": "", "summary": "", "poster_url": None}
        if abs_path.lower().endswith(('.zip', '.cbz')):
            meta['poster_url'] = "zip_thumb://" + rel_path.replace('\\', '/')
        elif is_image_file(abs_path):
            meta['poster_url'] = rel_path.replace('\\', '/')
        return meta

    folder_name = clean_name(normalize_nfc(os.path.basename(abs_path.rstrip('/\\'))))
    meta = {"title": folder_name or "Untitled", "author": "", "summary": "", "poster_url": None}

    data = get_fs_data(abs_path)
    if not data: return meta

    # 1. Kavita YAML
    kavita_actual = data['mapping'].get("kavita.yaml")
    if kavita_actual:
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
        except: pass

    # 2. Poster/Zip in Current Dir
    if not meta['poster_url']:
        for item in data['items']:
            name = item['name']
            if not item['is_dir']:
                if is_image_file(name) and any(x in name.lower() for x in ["poster", "cover", "folder", "thumb"]):
                    meta['poster_url'] = os.path.join(rel_path, name).replace('\\', '/')
                    break
                if name.lower().endswith(('.zip', '.cbz')):
                    meta['poster_url'] = "zip_thumb://" + os.path.join(rel_path, name).replace('\\', '/')
                    break

    # 3. Deep Search
    if not meta['poster_url']:
        found = find_first_valid_thumb(abs_path, rel_path, depth=0)
        if found: meta['poster_url'] = found

    return meta

@app.route('/metrics')
def metrics(): return "OK", 200

if __name__ == '__main__':
    print("🚀 ===== SERVER STARTING =====", flush=True)
    app.run(host='0.0.0.0', port=5555, debug=False, threaded=True, use_reloader=False)
