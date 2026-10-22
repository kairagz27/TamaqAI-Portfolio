"""Container launcher: initialize mount ownership, drop privileges, then exec Uvicorn."""
import os
from pathlib import Path


def main():
    if hasattr(os, "geteuid") and os.geteuid() == 0:
        mount = Path("/data")
        mount.mkdir(exist_ok=True)
        os.chown(mount, 10001, 10001)
        files_root=os.environ.get('TAMAQ_FILES_ROOT','')
        if files_root:
            root=Path(files_root)
            if root.is_symlink(): raise RuntimeError('File storage symlinks are forbidden')
            root.mkdir(parents=True,exist_ok=True)
            os.chown(root,10001,10001)
        database = Path(os.environ.get("TAMAQ_DATABASE", "/data/tamaq.sqlite3"))
        if database.parent.resolve() != mount.resolve():
            raise RuntimeError("Container database must be directly inside /data")
        for suffix in (() if os.environ.get('TAMAQ_DATABASE_URL') else ("", "-wal", "-shm")):
            target = Path(str(database) + suffix)
            if target.exists():
                if target.is_symlink():
                    raise RuntimeError("Database symlinks are forbidden")
                os.chown(target, 10001, 10001)
        os.setgroups([])
        os.setgid(10001)
        os.setuid(10001)
        if files_root:
            # CHOWN does not grant chmod permission after ownership changes.
            # Tighten the mount as its new owner, without adding FOWNER.
            os.chmod(root,0o700)
    os.umask(0o077)
    os.execvp("python", ["python", "-m", "uvicorn", "tamaq.app:create_app", "--factory",
        "--host", "0.0.0.0", "--port", os.environ.get("PORT", "8000"), "--workers", "1",
        "--no-access-log", "--limit-concurrency", "32", "--timeout-keep-alive", "5",
        "--proxy-headers", "--forwarded-allow-ips", os.environ.get("TAMAQ_PROXY_IPS", "127.0.0.1")])


if __name__ == "__main__":
    main()
