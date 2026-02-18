from flask import Flask, jsonify, send_from_directory, request, send_file
import os
import yaml
import zipfile
import io

app = Flask(__name__)

# 의존성: pip install PyYAML

ROOT_DIRECTORY = "/volume2/video/GDS3/GDRIVE/READING/만화"

# --- Helper Functions ---
def get_full_path(path):
    return os.path.join(ROOT_DIRECTORY, path)

def find_first_image_in_zip(zip_path):
    """ZIP 파일에서 첫 번째 이미지 파일의 이름을 찾습니다."""
    try:
        with zipfile.ZipFile(zip_path, 'r') as zf:
            image_files = sorted([f for f in zf.namelist() if f.lower().endswith(('.jpg', '.jpeg', '.png', '.gif', '.webp')) and not f.startswith('__MACOSX')])
            if image_files:
                return image_files[0]
    except Exception:
        pass
    return None

# --- API Endpoints ---

@app.route('/files/', defaults={'path': ''})
@app.route('/files/<path:path>')
def list_files_route(path):
    abs_path = get_full_path(path)
    if not os.path.isdir(abs_path):
        return jsonify({"error": "Directory not found"}), 404
    items = []
    for item_name in sorted(os.listdir(abs_path)):
        item_path = os.path.join(abs_path, item_name)
        relative_item_path = os.path.join(path, item_name).replace('\\', '/')
        items.append({
            'name': item_name,
            'isDirectory': os.path.isdir(item_path),
            'path': relative_item_path
        })
    return jsonify(items)

@app.route('/download/<path:path>')
def download_file_route(path):
    try:
        directory, filename = os.path.split(path)
        return send_from_directory(get_full_path(directory), filename)
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route('/zip_entries/<path:zip_path>')
def get_zip_entries_route(zip_path):
    """ZIP 파일 내의 이미지 파일 목록을 반환합니다."""
    full_zip_path = get_full_path(zip_path)
    try:
        with zipfile.ZipFile(full_zip_path, 'r') as zf:
            image_files = sorted([f for f in zf.namelist() if f.lower().endswith(('.jpg', '.jpeg', '.png', '.gif', '.webp')) and not f.startswith('__MACOSX')])
            return jsonify(image_files)
    except Exception as e:
        return jsonify({"error": f"Could not list entries for zip: {e}"}), 500

@app.route('/download_zip_entry/<path:zip_path>')
def download_zip_entry_route(zip_path):
    entry_name = request.args.get('entry')
    if not entry_name:
        return "Entry name required", 400

    full_zip_path = get_full_path(zip_path)
    try:
        with zipfile.ZipFile(full_zip_path, 'r') as zf:
            with zf.open(entry_name) as entry_file:
                return send_file(io.BytesIO(entry_file.read()), mimetype='image/jpeg')
    except Exception as e:
        return jsonify({"error": f"Could not read entry '{entry_name}' from zip: {e}"}), 500

@app.route('/metadata/<path:path>')
def get_metadata_route(path):
    abs_path = get_full_path(path)
    if not os.path.isdir(abs_path):
        return jsonify({"error": "Directory not found"}), 404
    try:
        files_in_dir = os.listdir(abs_path)
        metadata = {"title": None, "author": None, "summary": None, "posterUrl": None}
        if 'kavita.yaml' in [f.lower() for f in files_in_dir]:
            yaml_path = os.path.join(abs_path, 'kavita.yaml')
            with open(yaml_path, 'r', encoding='utf-8') as f:
                content = f.read()
                try:
                    kavita_data = yaml.safe_load(content)
                    metadata['title'] = kavita_data.get('localizedName') or kavita_data.get('name')
                    metadata['author'] = kavita_data.get('writer') or kavita_data.get('author')
                    metadata['summary'] = kavita_data.get('summary')
                    poster_name = kavita_data.get('poster_url') or kavita_data.get('cover')
                    if poster_name and poster_name in files_in_dir:
                         metadata['posterUrl'] = os.path.join(path, poster_name).replace('\\', '/')
                except yaml.YAMLError: pass
        if not metadata['posterUrl']:
            common_names = ["poster", "cover", "folder", "thumbnail"]
            for f in files_in_dir:
                name_low = f.lower()
                if name_low.endswith(('.jpg', '.png')) and name_low.rsplit('.', 1)[0] in common_names:
                    metadata['posterUrl'] = os.path.join(path, f).replace('\\', '/')
                    break
        if not metadata['posterUrl']:
            zip_files = sorted([f for f in files_in_dir if f.lower().endswith(('.zip', '.cbz'))])
            if zip_files:
                first_zip_path = os.path.join(abs_path, zip_files[0])
                first_image_entry = find_first_image_in_zip(first_zip_path)
                if first_image_entry:
                    relative_zip_path = os.path.join(path, zip_files[0]).replace('\\', '/')
                    metadata['posterUrl'] = f"api_zip_thumb://{relative_zip_path}?entry={first_image_entry}"
        return jsonify(metadata)
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route('/scan/', defaults={'path': ''})
@app.route('/scan/<path:path>')
def scan_comics_route(path):
    try:
        max_depth = int(request.args.get('maxDepth', 5))
        comic_folders = []
        start_abs_path = os.path.join(ROOT_DIRECTORY, path)
        if not os.path.isdir(start_abs_path):
            return jsonify({"error": "Start directory not found"}), 404
        base_depth = start_abs_path.rstrip(os.sep).count(os.sep)
        for root, dirs, files in os.walk(start_abs_path, topdown=True):
            current_depth = root.count(os.sep) - base_depth
            if current_depth >= max_depth:
                dirs[:] = []
                continue
            if any(f.lower().endswith(('.zip', '.cbz')) for f in files):
                relative_path = os.path.relpath(root, ROOT_DIRECTORY)
                comic_folders.append({
                    'name': os.path.basename(root),
                    'isDirectory': True,
                    'path': relative_path.replace('\\', '/')
                })
        return jsonify(comic_folders)
    except Exception as e:
        return jsonify({"error": str(e)}), 500

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5555, debug=True)
