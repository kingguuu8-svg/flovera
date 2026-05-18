import builtins
import contextlib
import io
import json
import os
import shutil
import socket
import subprocess
import sys
import threading
import time
import traceback


_sessions = {}
_run_lock = threading.RLock()


class FloveraPythonBoundaryError(PermissionError):
    pass


class FloveraPythonTimeout(TimeoutError):
    pass


def run_code(code, workspace_root, cwd, timeout_ms, max_output_chars, session_id, reset_session, scope, network_enabled):
    with _run_lock:
        started_at = time.monotonic()
        root = os.path.realpath(workspace_root)
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

        old_cwd = os.getcwd()
        deadline = time.monotonic() + timeout_s
        patches = _install_boundaries(root, start_cwd, scope, bool(network_enabled), deadline)
        old_trace = sys.gettrace()
        try:
            os.chdir(start_cwd)
            sys.settrace(_timeout_trace(deadline))
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                exec(compile(code, "<flovera-python-run>", "exec"), globals_dict)
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
            sys.settrace(old_trace)
            _restore_patches(patches)
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


def _timeout_trace(deadline):
    def trace(frame, event, arg):
        if time.monotonic() > deadline:
            raise FloveraPythonTimeout()
        return trace

    return trace


def _normalize_path(root, current_cwd, path, scope, write):
    if isinstance(path, int):
        return path
    raw = os.fspath(path)
    base = current_cwd if not os.path.isabs(raw) else root
    resolved = os.path.realpath(raw if os.path.isabs(raw) else os.path.join(base, raw))
    if resolved != root and not resolved.startswith(root + os.sep):
        raise FloveraPythonBoundaryError("Path escapes workspace: " + raw)
    _check_flovera_scope(root, resolved, scope, write)
    return resolved


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
        }
        if rel in metadata_read:
            if write:
                raise FloveraPythonBoundaryError("python_run cannot write app metadata: " + rel)
            return
        if rel == ".flovera/proposals" or rel.startswith(".flovera/proposals/"):
            return
        raise FloveraPythonBoundaryError("Path is outside python_run metadata scope: " + rel)


def _install_boundaries(root, start_cwd, scope, network_enabled, deadline):
    current_cwd = [start_cwd]
    patches = []
    original_open = builtins.open
    original_chdir = os.chdir
    original_sleep = time.sleep

    def patch(obj, name, value):
        patches.append((obj, name, getattr(obj, name)))
        setattr(obj, name, value)

    def guarded_path(path, write=False):
        return _normalize_path(root, current_cwd[0], path, scope, write)

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
            if now >= deadline:
                raise FloveraPythonTimeout()
            if now >= target:
                return
            original_sleep(min(target - now, deadline - now, 0.05))

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
        patch(socket, "socket", deny("socket.socket"))
        patch(socket, "create_connection", deny("socket.create_connection"))
    return patches


def _restore_patches(patches):
    for obj, name, value in reversed(patches):
        setattr(obj, name, value)


def _truncate(text, max_chars):
    if len(text) <= max_chars:
        return text, False
    return text[:max_chars] + "\n[truncated]", True
