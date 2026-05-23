import json
import os
import runpy
import socketserver
import sys
import threading
import traceback


_servers = {}
_servers_lock = threading.RLock()
_serve_forever_patched = False


def run_script_server(script_path, workspace_root, cwd, host, port, argv_json="[]", environment_json="{}"):
    _install_server_registry()
    script = os.path.realpath(script_path)
    root = os.path.realpath(workspace_root)
    start_cwd = os.path.realpath(cwd)
    if script != root and not script.startswith(root + os.sep):
        raise PermissionError("python_http script escapes workspace: " + script_path)
    if start_cwd != root and not start_cwd.startswith(root + os.sep):
        raise PermissionError("python_http cwd escapes workspace: " + cwd)

    old_argv = list(sys.argv)
    old_environ = os.environ.copy()
    added_to_path = False
    try:
        argv = _string_list(argv_json)
        environment = _string_map(environment_json)
        if start_cwd not in sys.path:
            sys.path.insert(0, start_cwd)
            added_to_path = True
        os.environ.update(environment)
        os.environ.setdefault("HOST", str(host))
        os.environ.setdefault("PORT", str(port))
        os.environ.setdefault("FLOVERA_HTTP_HOST", str(host))
        os.environ.setdefault("FLOVERA_HTTP_PORT", str(port))
        os.environ.setdefault("FLOVERA_WORKSPACE_ROOT", root)
        sys.argv = [script] + argv
        runpy.run_path(script, run_name="__main__")
    except SystemExit:
        raise
    except Exception:
        traceback.print_exc()
        raise
    finally:
        sys.argv = old_argv
        os.environ.clear()
        os.environ.update(old_environ)
        if added_to_path:
            try:
                sys.path.remove(start_cwd)
            except ValueError:
                pass


def stop_server(port):
    server = None
    with _servers_lock:
        server = _servers.get(int(port))
    if server is None:
        return False
    try:
        server.shutdown()
        server.server_close()
        return True
    except Exception:
        traceback.print_exc()
        return False


def active_servers():
    with _servers_lock:
        return sorted(_servers.keys())


def _install_server_registry():
    global _serve_forever_patched
    with _servers_lock:
        if _serve_forever_patched:
            return
        original_serve_forever = socketserver.BaseServer.serve_forever

        def registered_serve_forever(self, *args, **kwargs):
            port = _server_port(self)
            if port is not None:
                with _servers_lock:
                    _servers[int(port)] = self
            try:
                return original_serve_forever(self, *args, **kwargs)
            finally:
                if port is not None:
                    with _servers_lock:
                        if _servers.get(int(port)) is self:
                            _servers.pop(int(port), None)

        socketserver.BaseServer.serve_forever = registered_serve_forever
        _serve_forever_patched = True


def _server_port(server):
    address = getattr(server, "server_address", None)
    if isinstance(address, tuple) and len(address) >= 2:
        try:
            return int(address[1])
        except Exception:
            return None
    return None


def _string_list(text):
    try:
        value = json.loads(text or "[]")
    except Exception:
        return []
    if not isinstance(value, list):
        return []
    return [str(item) for item in value]


def _string_map(text):
    try:
        value = json.loads(text or "{}")
    except Exception:
        return {}
    if not isinstance(value, dict):
        return {}
    result = {}
    for key, item in value.items():
        key_text = str(key)
        if key_text and "=" not in key_text and "\x00" not in key_text:
            result[key_text] = str(item)
    return result
