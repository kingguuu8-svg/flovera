import hashlib
import importlib
import json
import os
import sys
import urllib.request
import zipfile


def install_catalog_package(workspace_root, package_name, network_enabled):
    root = os.path.realpath(workspace_root)
    catalog_path = os.path.join(root, ".flovera", "python", "wheel-catalog.json")
    if not os.path.isfile(catalog_path):
        return _json("error", package_name, "Catalog not found: .flovera/python/wheel-catalog.json")
    with open(catalog_path, "r", encoding="utf-8") as handle:
        catalog = json.load(handle)
    packages = catalog.get("packages", [])
    entry = next((item for item in packages if item.get("name", "").lower() == package_name.lower()), None)
    if not entry:
        return _json("error", package_name, "Package is not in Flovera catalog.")

    site_packages = os.path.join(root, ".flovera", "python", "site-packages")
    os.makedirs(site_packages, exist_ok=True)
    if site_packages not in sys.path:
        sys.path.insert(0, site_packages)

    import_names = entry.get("topLevelImports") or [entry.get("name", package_name)]
    available = _importable(import_names)
    if available:
        return _json("ok", entry["name"], "Package already available.", source="bundled_or_installed", imports=import_names)
    if entry.get("purePython") is False:
        return _json("error", entry["name"], "Catalog entry is not pure Python and cannot be dynamically installed.")

    for dependency in entry.get("dependencies", []):
        dependency_entry = next((item for item in packages if item.get("name", "").lower() == dependency.lower()), None)
        if dependency_entry and not _importable(dependency_entry.get("topLevelImports") or [dependency_entry.get("name", dependency)]):
            dependency_result = json.loads(install_catalog_package(workspace_root, dependency, network_enabled))
            if dependency_result.get("status") != "ok":
                return _json("error", entry["name"], "Dependency install failed.", dependency=dependency, dependencyResult=dependency_result)

    url = entry.get("wheelUrl", "")
    sha256 = entry.get("sha256", "")
    if not url:
        return _json("error", entry["name"], "Catalog entry has no wheelUrl.")
    if not network_enabled and not url.startswith("file:"):
        return _json("error", entry["name"], "Network is disabled; cannot download wheel.")

    wheel_bytes = _read_url(url, root)
    digest = hashlib.sha256(wheel_bytes).hexdigest()
    if sha256 and digest.lower() != sha256.lower():
        return _json("error", entry["name"], "Wheel sha256 mismatch.", expected=sha256, actual=digest)

    wheel_cache = os.path.join(root, ".flovera", "python", "wheels")
    os.makedirs(wheel_cache, exist_ok=True)
    wheel_path = os.path.join(wheel_cache, os.path.basename(url.split("?", 1)[0]))
    with open(wheel_path, "wb") as handle:
        handle.write(wheel_bytes)
    with zipfile.ZipFile(wheel_path) as archive:
        for member in archive.infolist():
            normalized = member.filename.replace("\\", "/")
            if normalized.startswith("/") or ".." in normalized.split("/"):
                raise ValueError("Unsafe wheel member: " + member.filename)
            archive.extract(member, site_packages)

    importlib.invalidate_caches()
    available = _importable(import_names)
    if not available:
        return _json("error", entry["name"], "Wheel installed but import check failed.", imports=import_names)

    lock_path = os.path.join(root, ".flovera", "python", "installed-packages.json")
    lock = {"packages": []}
    if os.path.isfile(lock_path):
        with open(lock_path, "r", encoding="utf-8") as handle:
            lock = json.load(handle)
    lock["packages"] = [item for item in lock.get("packages", []) if item.get("name") != entry["name"]]
    lock["packages"].append({"name": entry["name"], "version": entry.get("version", ""), "sha256": digest})
    with open(lock_path, "w", encoding="utf-8") as handle:
        json.dump(lock, handle, ensure_ascii=False, indent=2)
    return _json("ok", entry["name"], "Package installed.", source="wheel_catalog", imports=import_names)


def _read_url(url, root):
    if url.startswith("file:"):
        path = urllib.request.url2pathname(url.removeprefix("file://"))
        resolved = os.path.realpath(path)
        if resolved != root and not resolved.startswith(root + os.sep):
            raise PermissionError("File URL escapes workspace.")
        with open(resolved, "rb") as handle:
            return handle.read()
    with urllib.request.urlopen(url, timeout=30) as response:
        return response.read()


def _importable(import_names):
    try:
        for name in import_names:
            importlib.import_module(name)
        return True
    except Exception:
        return False


def _json(status, package_name, message, **extra):
    payload = {"status": status, "package": package_name, "message": message}
    payload.update(extra)
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
