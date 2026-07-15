import builtins
import contextlib
import io
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import traceback


_sessions = {}
_run_lock = threading.RLock()
_cancelled_runs = set()
_cancelled_runs_lock = threading.RLock()


class FloveraPythonBoundaryError(PermissionError):
    pass


class FloveraPythonTimeout(TimeoutError):
    pass


class FloveraPythonCancelled(KeyboardInterrupt):
    pass


def cancel_run(run_id):
    key = str(run_id or "")
    if not key:
        return False
    with _cancelled_runs_lock:
        _cancelled_runs.add(key)
    return True


def _clear_cancelled_run(run_id):
    key = str(run_id or "")
    if not key:
        return
    with _cancelled_runs_lock:
        _cancelled_runs.discard(key)


def _is_run_cancelled(run_id):
    key = str(run_id or "")
    if not key:
        return False
    with _cancelled_runs_lock:
        return key in _cancelled_runs


def run_code(code, workspace_root, cwd, timeout_ms, max_output_chars, session_id, reset_session, scope, network_enabled, environment_json="{}", run_id=""):
    with _run_lock:
        started_at = time.monotonic()
        if _is_run_cancelled(run_id):
            _clear_cancelled_run(run_id)
            return json.dumps(
                {
                    "status": "cancelled",
                    "exitCode": 130,
                    "stdout": "",
                    "stderr": "Python execution cancelled.\n",
                    "stdoutTruncated": False,
                    "stderrTruncated": False,
                    "elapsedMs": 0,
                    "sessionId": session_id or "",
                },
                ensure_ascii=False,
            )
        root = os.path.realpath(workspace_root)
        _install_workspace_site_packages(root)
        start_cwd = _normalize_path(root, root, cwd, scope, False)
        timeout_s = max(1, int(timeout_ms)) / 1000.0
        max_chars = max(1000, int(max_output_chars))
        stdout = io.StringIO()
        stderr = io.StringIO()
        status = "ok"
        exit_code = 0
        globals_dict = _globals_for_session(session_id, reset_session)
        globals_dict["WORKSPACE_ROOT"] = root
        globals_dict["WORKSPACE_CWD"] = start_cwd
        _preload_runtime_packages()

        old_cwd = os.getcwd()
        old_tempdir = tempfile.tempdir
        old_dont_write_bytecode = sys.dont_write_bytecode
        old_environ = os.environ.copy()
        deadline = time.monotonic() + timeout_s
        patches = _install_boundaries(root, start_cwd, scope, bool(network_enabled), deadline, run_id)
        old_trace = sys.gettrace()
        try:
            if _is_run_cancelled(run_id):
                raise FloveraPythonCancelled()
            os.chdir(start_cwd)
            tempfile.tempdir = start_cwd
            sys.dont_write_bytecode = True
            os.environ.update(_safe_environment(environment_json))
            sys.settrace(_timeout_trace(deadline, run_id))
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                exec(compile(code, "<flovera-python-run>", "exec"), globals_dict)
        except FloveraPythonCancelled:
            status = "cancelled"
            exit_code = 130
            stderr.write("Python execution cancelled.\n")
        except FloveraPythonTimeout:
            status = "timeout"
            exit_code = 124
            stderr.write("Python execution timed out.\n")
        except SystemExit as exc:
            status = "system_exit"
            exit_code = exc.code if isinstance(exc.code, int) else 1
        except Exception:
            status = "error"
            exit_code = 1
            traceback.print_exc(file=stderr)
        finally:
            _clear_cancelled_run(run_id)
            sys.settrace(old_trace)
            _restore_patches(patches)
            os.environ.clear()
            os.environ.update(old_environ)
            sys.dont_write_bytecode = old_dont_write_bytecode
            tempfile.tempdir = old_tempdir
            os.chdir(old_cwd)

        elapsed_ms = int((time.monotonic() - started_at) * 1000)
        out_text, out_truncated = _truncate(stdout.getvalue(), max_chars)
        err_text, err_truncated = _truncate(stderr.getvalue(), max_chars)
        return json.dumps(
            {
                "status": status,
                "exitCode": exit_code,
                "stdout": out_text,
                "stderr": err_text,
                "stdoutTruncated": out_truncated,
                "stderrTruncated": err_truncated,
                "elapsedMs": elapsed_ms,
                "sessionId": session_id or "",
            },
            ensure_ascii=False,
        )


def _globals_for_session(session_id, reset_session):
    if not session_id:
        return {"__name__": "__main__", "__builtins__": builtins}
    key = str(session_id)
    if reset_session or key not in _sessions:
        _sessions[key] = {"__name__": "__main__", "__builtins__": builtins}
    return _sessions[key]


def _safe_environment(environment_json):
    try:
        parsed = json.loads(environment_json or "{}")
    except Exception:
        return {}
    if not isinstance(parsed, dict):
        return {}
    result = {}
    for key, value in parsed.items():
        key_text = str(key)
        if not key_text or "=" in key_text or "\x00" in key_text:
            continue
        result[key_text] = str(value)
    return result


def _preload_runtime_packages():
    import docx
    import jinja2
    import lxml.etree
    import markdown
    import openpyxl
    import pypdf
    import xlsxwriter


def _install_workspace_site_packages(root):
    site_packages = os.path.join(root, ".flovera", "python", "site-packages")
    if site_packages not in sys.path:
        sys.path.insert(0, site_packages)


def _timeout_trace(deadline, run_id):
    def trace(frame, event, arg):
        if _is_run_cancelled(run_id):
            raise FloveraPythonCancelled()
        if time.monotonic() > deadline:
            raise FloveraPythonTimeout()
        return trace

    return trace


def _normalize_path(root, current_cwd, path, scope, write, read_roots=None):
    if isinstance(path, int):
        return path
    raw = os.fspath(path)
    base = current_cwd if not os.path.isabs(raw) else root
    resolved = os.path.realpath(raw if os.path.isabs(raw) else os.path.join(base, raw))
    if resolved != root and not resolved.startswith(root + os.sep):
        if _is_chaquopy_asset_path(raw) or _is_chaquopy_asset_path(resolved):
            if not write or _is_chaquopy_import_stack() or _is_chaquopy_runtime_write_path(resolved):
                return resolved
        if not write and _is_under_any(resolved, read_roots or ()):
            return resolved
        raise FloveraPythonBoundaryError("Path escapes workspace: " + raw)
    _check_flovera_scope(root, resolved, scope, write)
    return resolved


def _is_under_any(path, roots):
    for candidate in roots:
        if path == candidate or path.startswith(candidate + os.sep):
            return True
    return False


def _is_chaquopy_asset_path(path):
    normalized = str(path).replace("\\", "/")
    return (
        "/chaquopy/AssetFinder" in normalized
        or normalized.startswith("chaquopy/AssetFinder")
    )


def _chaquopy_asset_root(path):
    try:
        parts = os.path.realpath(path).replace("\\", "/").split("/")
    except (OSError, TypeError, ValueError):
        return None
    for index in range(len(parts) - 1):
        if parts[index] == "chaquopy" and parts[index + 1] == "AssetFinder":
            return "/".join(parts[: index + 2])
    return None


def _is_chaquopy_runtime_write_path(path):
    normalized = str(path).replace("\\", "/")
    marker = "/chaquopy/AssetFinder/stdlib-"
    return marker in normalized


def _is_chaquopy_import_stack():
    frame = sys._getframe()
    while frame:
        filename = str(frame.f_code.co_filename).replace("\\", "/")
        if filename.endswith("import.pxi") or "/java/chaquopy/" in filename:
            return True
        frame = frame.f_back
    return False


def _python_read_roots():
    roots = set()

    def add_root(value, require_exists=True):
        if not value:
            return
        try:
            resolved = os.path.realpath(value)
        except (OSError, TypeError, ValueError):
            return
        if not require_exists or os.path.exists(resolved):
            roots.add(resolved)

    module_dir = os.path.dirname(__file__)
    asset_root = os.path.dirname(module_dir)
    for value in (module_dir, asset_root, os.path.join(asset_root, "requirements")):
        add_root(value, require_exists=False)
    chaquopy_asset_root = _chaquopy_asset_root(module_dir)
    if chaquopy_asset_root:
        add_root(chaquopy_asset_root, require_exists=False)
    for value in list(sys.path) + [sys.prefix, sys.exec_prefix]:
        add_root(value)
        chaquopy_asset_root = _chaquopy_asset_root(value)
        if chaquopy_asset_root:
            add_root(chaquopy_asset_root, require_exists=False)
    return tuple(sorted(roots))


def _check_flovera_scope(root, path, scope, write):
    rel = os.path.relpath(path, root).replace(os.sep, "/")
    if rel == ".":
        return
    if not (rel == ".flovera" or rel.startswith(".flovera/")):
        return
    if rel.startswith(".flovera/retrieval/") or rel.startswith(".flovera/cache/"):
        raise FloveraPythonBoundaryError("Path is outside python_run .flovera scope: " + rel)
    normalized_scope = str(scope or "workspace_public").lower()
    if normalized_scope in ("workspace_public", "public", "workspace"):
        raise FloveraPythonBoundaryError("python_run scope cannot access .flovera: " + rel)
    if normalized_scope in ("workspace_app_metadata", "app_metadata", "metadata", "flovera_metadata"):
        metadata_read = {
            ".flovera/manifest.json",
            ".flovera/settings-view.json",
            ".flovera/capabilities.json",
            ".flovera/python/wheel-catalog.json",
            ".flovera/tools/manifest.json",
        }
        if rel in metadata_read:
            if write:
                raise FloveraPythonBoundaryError("python_run cannot write app metadata: " + rel)
            return
        if rel == ".flovera/proposals" or rel.startswith(".flovera/proposals/"):
            return
        raise FloveraPythonBoundaryError("Path is outside python_run metadata scope: " + rel)


def _install_boundaries(root, start_cwd, scope, network_enabled, deadline, run_id):
    current_cwd = [start_cwd]
    patches = []
    original_open = builtins.open
    original_chdir = os.chdir
    original_sleep = time.sleep
    original_socket = socket.socket
    read_roots = _python_read_roots()

    def patch(obj, name, value):
        patches.append((obj, name, getattr(obj, name)))
        setattr(obj, name, value)

    def guarded_path(path, write=False):
        return _normalize_path(root, current_cwd[0], path, scope, write, read_roots)

    def guarded_open(file, mode="r", *args, **kwargs):
        write = any(flag in str(mode) for flag in ("w", "a", "x", "+"))
        return original_open(guarded_path(file, write), mode, *args, **kwargs)

    def guarded_chdir(path):
        resolved = guarded_path(path, False)
        if not os.path.isdir(resolved):
            raise NotADirectoryError(resolved)
        original_chdir(resolved)
        current_cwd[0] = resolved

    def guarded_unary(func, write=False):
        def wrapper(path, *args, **kwargs):
            return func(guarded_path(path, write), *args, **kwargs)

        return wrapper

    def guarded_binary(func, write=False):
        def wrapper(src, dst, *args, **kwargs):
            return func(guarded_path(src, write), guarded_path(dst, True), *args, **kwargs)

        return wrapper

    def deny(name):
        def wrapper(*args, **kwargs):
            raise RuntimeError(name + " is disabled in blocking python_run")

        return wrapper

    def guarded_sleep(seconds):
        target = time.monotonic() + max(0.0, float(seconds))
        while True:
            now = time.monotonic()
            if _is_run_cancelled(run_id):
                raise FloveraPythonCancelled()
            if now >= deadline:
                raise FloveraPythonTimeout()
            if now >= target:
                return
            original_sleep(min(target - now, deadline - now, 0.05))

    class GuardedSocket(original_socket):
        def connect(self, *args, **kwargs):
            raise RuntimeError("socket.socket.connect is disabled in blocking python_run")

        def connect_ex(self, *args, **kwargs):
            raise RuntimeError("socket.socket.connect_ex is disabled in blocking python_run")

    patch(builtins, "open", guarded_open)
    patch(io, "open", guarded_open)
    patch(os, "chdir", guarded_chdir)
    patch(os, "listdir", guarded_unary(os.listdir))
    patch(os, "scandir", guarded_unary(os.scandir))
    patch(os, "remove", guarded_unary(os.remove, True))
    patch(os, "unlink", guarded_unary(os.unlink, True))
    patch(os, "mkdir", guarded_unary(os.mkdir, True))
    patch(os, "makedirs", guarded_unary(os.makedirs, True))
    patch(os, "rmdir", guarded_unary(os.rmdir, True))
    patch(os, "rename", guarded_binary(os.rename, True))
    patch(os, "replace", guarded_binary(os.replace, True))
    patch(shutil, "copyfile", guarded_binary(shutil.copyfile))
    patch(shutil, "copy", guarded_binary(shutil.copy))
    patch(shutil, "copy2", guarded_binary(shutil.copy2))
    patch(shutil, "move", guarded_binary(shutil.move, True))
    patch(shutil, "rmtree", guarded_unary(shutil.rmtree, True))
    patch(subprocess, "Popen", deny("subprocess.Popen"))
    patch(subprocess, "run", deny("subprocess.run"))
    patch(subprocess, "call", deny("subprocess.call"))
    patch(subprocess, "check_call", deny("subprocess.check_call"))
    patch(subprocess, "check_output", deny("subprocess.check_output"))
    patch(os, "system", deny("os.system"))
    patch(os, "popen", deny("os.popen"))
    patch(threading.Thread, "start", deny("threading.Thread.start"))
    patch(time, "sleep", guarded_sleep)
    if not network_enabled:
        patch(socket, "socket", GuardedSocket)
        patch(socket, "create_connection", deny("socket.create_connection"))
    return patches


def _restore_patches(patches):
    for obj, name, value in reversed(patches):
        setattr(obj, name, value)


def _truncate(text, max_chars):
    if len(text) <= max_chars:
        return text, False
    return text[:max_chars] + "\n[truncated]", True
