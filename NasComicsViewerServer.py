from flask import Flask, jsonify, send_from_directory, request, send_file
import os
import zipfile
import io
import urllib.parse
import unicodedata

app = Flask(__name__)

# --- 핵심 설정: 실제 만화 카테고리들이 들어있는 경로로 수정 ---
ROOT_DIRECTORY = "/volume2/video/GDS3/GDRIVE/READING/만화/만화"

def normalize_nfc(s):
    if not isinstance(s, str): return s
    return unicodedata.normalize('NFC', s)

def get_abs_path(rel_path):
    return os.path.normpath(os.path.join(ROOT_DIRECTORY, normalize_nfc(rel_path)))

def find_file_case_insensitive(directory, filename):
    try:
        target = normalize_nfc(filename).lower()
        for f in os.listdir(directory):
            if normalize_nfc(f).lower() == target:
                return f
    except: pass
    return None

def find_first_image_in_zip(zip_path):
    try:
        with zipfile.ZipFile(zip_path, 'r') as zf:
            image_files = sorted([f for f in zf.namelist() if f.lower().endswith(('.jpg', '.jpeg', '.png', '.gif', '.webp')) and not f.startswith('__MACOSX')])
            if image_files: return image_files[0]
    except: pass
    return None

@app.route('/debug')
def debug_info():
    info = {'root': ROOT_DIRECTORY, 'exists': os.path.exists(ROOT_DIRECTORY)}
    if info['exists']:
        try:
            content = os.listdir(ROOT_DIRECTORY)
            info['count'] = len(content)
            info['sample'] = [normalize_nfc(c) for c in content[:20]]
        except Exception as e: info['error'] = str(e)
    return jsonify(info)

@app.route('/files')
def list_files_route():
    path = request.args.get('path', '')
    abs_path = get_abs_path(path)
    if not os.path.isdir(abs_path): return jsonify([]), 404
    items = []
    for item_name in sorted(os.listdir(abs_path)):
        item_abs_path = os.path.join(abs_path, item_name)
        items.append({
            'name': normalize_nfc(item_name),
            'isDirectory': os.path.isdir(item_abs_path),
            'path': normalize_nfc(os.path.join(path, item_name).replace('\\', '/'))
        })
    return jsonify(items)

@app.route('/download')
def download_file_route():
    path = request.args.get('path', '')
    abs_path = get_abs_path(path)
    try:
        directory, filename = os.path.split(abs_path)
        return send_from_directory(directory, filename)
    except Exception as e: return jsonify({"error": str(e)}), 500

@app.route('/metadata')
def get_metadata_route():
    path = request.args.get('path', '')
    abs_path = get_abs_path(path)
    if not os.path.isdir(abs_path): return jsonify({"error": "Not found"}), 404

    metadata = {"title": normalize_nfc(os.path.basename(abs_path)), "author": None, "summary": None, "posterUrl": None}
    try:
        files = os.listdir(abs_path)
        # 1. kavita.yaml 읽기
        kavita_file = find_file_case_insensitive(abs_path, "kavita.yaml")
        if kavita_file:
            import yaml
            with open(os.path.join(abs_path, kavita_file), 'r', encoding='utf-8', errors='ignore') as f:
                data = yaml.safe_load(f)
                if data:
                    metadata['title'] = data.get('localizedName') or data.get('name') or metadata['title']
                    metadata['author'] = data.get('writer') or data.get('author')
                    metadata['summary'] = data.get('summary')
                    p_name = data.get('poster_url') or data.get('cover')
                    if p_name:
                        real_p = find_file_case_insensitive(abs_path, p_name)
                        if real_p: metadata['posterUrl'] = normalize_nfc(os.path.join(path, real_p))

        # 2. 직접 이미지 파일 찾기 (cover.jpg 등)
        if not metadata['posterUrl']:
            for f in files:
                f_low = normalize_nfc(f).lower()
                if f_low.endswith(('.jpg', '.png', '.jpeg')) and any(x in f_low for x in ["poster", "cover", "folder", "thumb"]):
                    metadata['posterUrl'] = normalize_nfc(os.path.join(path, f))
                    break

        # 3. Zip 내부 첫 이미지
        if not metadata['posterUrl']:
            zips = sorted([f for f in files if f.lower().endswith(('.zip', '.cbz'))])
            if zips:
                first_zip = os.path.join(abs_path, zips[0])
                entry = find_first_image_in_zip(first_zip)
                if entry:
                    # 특수문자 대응을 위해 구분자 사용
                    metadata['posterUrl'] = f"zip_thumb://{normalize_nfc(os.path.join(path, zips[0]))}|||{entry}"

        return jsonify(metadata)
    except Exception as e: return jsonify({"error": str(e)}), 500

@app.route('/scan')
def scan_comics_route():
    path = request.args.get('path', '')
    abs_start_path = get_abs_path(path)
    if not os.path.isdir(abs_start_path): return jsonify([])

    results = []
    # 충분히 깊게 스캔 (이미지 기준 3단계 이상 필요)
    for root, dirs, files in os.walk(abs_start_path):
        if any(f.lower().endswith(('.zip', '.cbz')) for f in files):
            rel_path = os.path.relpath(root, ROOT_DIRECTORY)
            results.append({
                'name': normalize_nfc(os.path.basename(root)),
                'isDirectory': True,
                'path': normalize_nfc(rel_path.replace('\\', '/'))
            })
            del dirs[:] # 만화 폴더 하위는 스캔 중단
    return jsonify(sorted(results, key=lambda x: x['name']))

@app.route('/download_zip_entry')
def download_zip_entry_route():
    zip_path = request.args.get('path', '')
    entry = request.args.get('entry', '')
    abs_zip_path = get_abs_path(zip_path)
    try:
        with zipfile.ZipFile(abs_zip_path, 'r') as zf:
            with zf.open(entry) as f:
                return send_file(io.BytesIO(f.read()), mimetype='image/jpeg')
    except Exception as e: return str(e), 500

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5555, debug=True)
