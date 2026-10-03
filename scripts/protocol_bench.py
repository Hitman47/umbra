#!/usr/bin/env python3
"""
Compare SMB, WebDAV and NFS on the same NAS files, the way a video player reads them.

For each video drawn at random, in each protocol:
  - ouverture : open the file and read its first 64 KiB (what the player waits for first);
  - démarrage : the reads of a player opening a file (start, end for the index, a bit further);
  - sauts     : 2 MiB read at 6 places of the file (the same places in every protocol);
  - débit     : sequential reading for 12 s (or 400 MiB).
The order of the protocols turns from one file to the next, so that none always
finds the file in the NAS's cache. Nothing is ever written on the NAS.

Install:   pip install smbprotocol requests pyNfsClient
Run:       python protocol_bench.py --smb "Vidéos/Films" --user mathieu
           python protocol_bench.py --explore      (only lists what each protocol sees)

Defaults are those of the Zima NAS: 192.168.1.131, WebDAV on port 5005, NFS export
/media/sdb1/Vidéos/Films. --smb is the SMB share and folder holding the same files
("Partage/Dossier"); without it, SMB is skipped and the files are found over WebDAV.
"""

import argparse
import getpass
import io
import json
import random
import socket
import statistics
import struct
import sys
import time
import unicodedata
import urllib.parse
import xml.etree.ElementTree as ET

VIDEO = (".mkv", ".mp4", ".m4v", ".avi", ".mov", ".ts", ".m2ts", ".webm", ".wmv")
KIB, MIB = 1024, 1024 * 1024
SEEKS = 6
THROUGHPUT_SECONDS = 12
THROUGHPUT_MAX = 400 * MIB


# --- Helpers -----------------------------------------------------------------

def forms(name):
    """The name as typed, and its composed/decomposed forms ("é" or "e" + accent): NAS differ."""
    out = []
    for form in (name, unicodedata.normalize("NFC", name), unicodedata.normalize("NFD", name)):
        if form not in out:
            out.append(form)
    return out


def ms(seconds):
    return round(seconds * 1000)


def mbps(byte_count, seconds):
    return round(byte_count * 8 / 1e6 / seconds, 1) if seconds > 0 else None


class Failure(Exception):
    """A protocol that can't reach the file, with what to do about it."""


def install_xdrlib():
    """pyNfsClient needs xdrlib, removed from Python 3.13: an equivalent, if missing."""
    try:
        import xdrlib  # noqa: F401  (Python <= 3.12)
        return
    except ImportError:
        pass
    import types
    xdr = types.ModuleType("xdrlib")

    class Error(Exception):
        def __init__(self, msg):
            super().__init__(msg)
            self.msg = msg

    class ConversionError(Error):
        pass

    def pad(n):
        return (4 - n % 4) % 4

    class Packer:
        def __init__(self):
            self.reset()

        # A BytesIO, as in the standard xdrlib: pyNfsClient writes to it directly.
        def reset(self):
            self.__buf = io.BytesIO()

        def get_buffer(self):
            return self.__buf.getvalue()

        get_buf = get_buffer

        def _put(self, fmt, x):
            try:
                self.__buf.write(struct.pack(fmt, x))
            except struct.error as e:
                raise ConversionError(str(e))

        def pack_uint(self, x): self._put(">L", x)
        def pack_int(self, x): self._put(">l", x)
        pack_enum = pack_int
        def pack_bool(self, x): self.__buf.write(b"\0\0\0\1" if x else b"\0\0\0\0")
        def pack_uhyper(self, x): self._put(">Q", x)
        def pack_hyper(self, x): self._put(">q", x)
        def pack_float(self, x): self._put(">f", x)
        def pack_double(self, x): self._put(">d", x)

        def pack_fstring(self, n, s):
            if n < 0:
                raise ValueError("fstring size must be nonnegative")
            data = bytes(s[:n])
            self.__buf.write(data + b"\0" * (n - len(data) + pad(n)))

        pack_fopaque = pack_fstring

        def pack_string(self, s):
            self.pack_uint(len(s))
            self.pack_fstring(len(s), s)

        pack_opaque = pack_string
        pack_bytes = pack_string

        def pack_list(self, items, pack_item):
            for item in items:
                self.pack_uint(1)
                pack_item(item)
            self.pack_uint(0)

        def pack_farray(self, n, items, pack_item):
            if len(items) != n:
                raise ValueError("wrong array size")
            for item in items:
                pack_item(item)

        def pack_array(self, items, pack_item):
            self.pack_uint(len(items))
            self.pack_farray(len(items), items, pack_item)

    class Unpacker:
        def __init__(self, data):
            self.reset(data)

        def reset(self, data):
            self.__buf = bytes(data)
            self.__pos = 0

        def get_position(self): return self.__pos
        def set_position(self, position): self.__pos = position
        def get_buffer(self): return self.__buf

        def done(self):
            if self.__pos < len(self.__buf):
                raise Error("unextracted data remains")

        def _get(self, fmt, size):
            i = self.__pos
            self.__pos = j = i + size
            data = self.__buf[i:j]
            if len(data) < size:
                raise EOFError
            return struct.unpack(fmt, data)[0]

        def unpack_uint(self): return self._get(">L", 4)
        def unpack_int(self): return self._get(">l", 4)
        unpack_enum = unpack_int
        def unpack_bool(self): return bool(self.unpack_int())
        def unpack_uhyper(self): return self._get(">Q", 8)
        def unpack_hyper(self): return self._get(">q", 8)
        def unpack_float(self): return self._get(">f", 4)
        def unpack_double(self): return self._get(">d", 8)

        def unpack_fstring(self, n):
            if n < 0:
                raise ValueError("fstring size must be nonnegative")
            i = self.__pos
            j = i + n + pad(n)
            if j > len(self.__buf):
                raise EOFError
            self.__pos = j
            return self.__buf[i:i + n]

        unpack_fopaque = unpack_fstring

        def unpack_string(self):
            return self.unpack_fstring(self.unpack_uint())

        unpack_opaque = unpack_string
        unpack_bytes = unpack_string

        def unpack_list(self, unpack_item):
            items = []
            while True:
                x = self.unpack_uint()
                if x == 0:
                    return items
                if x != 1:
                    raise ConversionError(f"0 or 1 expected, got {x!r}")
                items.append(unpack_item())

        def unpack_farray(self, n, unpack_item):
            return [unpack_item() for _ in range(n)]

        def unpack_array(self, unpack_item):
            return self.unpack_farray(self.unpack_uint(), unpack_item)

    xdr.Error, xdr.ConversionError, xdr.Packer, xdr.Unpacker = Error, ConversionError, Packer, Unpacker
    sys.modules["xdrlib"] = xdr


# --- SMB ---------------------------------------------------------------------

class Smb:
    name = "SMB"

    def __init__(self, host, folder, user, password):
        try:
            import smbclient
        except ImportError:
            raise Failure("module manquant : pip install smbprotocol")
        self.smbclient = smbclient
        share, _, sub = folder.replace("\\", "/").strip("/").partition("/")
        self.base = "\\\\" + host + "\\" + share + ("\\" + sub.replace("/", "\\") if sub else "")
        try:
            smbclient.register_session(host, username=user or None, password=password or None, connection_timeout=10)
        except Exception as e:
            raise Failure(f"connexion refusée : {e}")

    def _path(self, rel):
        return self.base + ("\\" + rel.replace("/", "\\") if rel else "")

    def list(self, rel=""):
        return [(e.name, e.is_dir(), 0 if e.is_dir() else e.stat().st_size) for e in self.smbclient.scandir(self._path(rel))]

    def open(self, rel):
        f = self.smbclient.open_file(self._path(rel), mode="rb", buffering=0)
        f.seek(0, 2)
        size = f.tell()
        return f, size

    def read(self, handle, offset, count):
        handle.seek(offset)
        data = b""
        while len(data) < count:
            chunk = handle.read(count - len(data))
            if not chunk:
                break
            data += chunk
        return len(data)

    def close(self, handle):
        handle.close()


# --- WebDAV ------------------------------------------------------------------

class WebDav:
    name = "WebDAV"

    def __init__(self, url, user, password):
        try:
            import requests
            from requests.auth import HTTPBasicAuth, HTTPDigestAuth
        except ImportError:
            raise Failure("module manquant : pip install requests")
        self.requests = requests
        self.base = url.rstrip("/")
        self.session = requests.Session()
        if user:
            self.session.auth = HTTPBasicAuth(user, password)
            # Digest if the server asks for it.
            probe = self.session.request("PROPFIND", self.base + "/", headers={"Depth": "0"}, timeout=10)
            if probe.status_code == 401 and "digest" in probe.headers.get("WWW-Authenticate", "").lower():
                self.session.auth = HTTPDigestAuth(user, password)

    def url(self, rel, form=None):
        parts = urllib.parse.urlsplit(self.base)
        # The folder as typed ("Vidéos") or already encoded ("Vid%C3%A9os"): encoded once.
        path = urllib.parse.unquote(parts.path).rstrip("/") + ("/" + rel if rel else "")
        if form:
            path = unicodedata.normalize(form, path)
        return urllib.parse.urlunsplit((parts.scheme, parts.netloc, urllib.parse.quote(path, safe="/()!$&'*+,;=:@~"), "", ""))

    def list(self, rel=""):
        codes = []
        for form in (None, "NFD"):
            url = self.url(rel, form).rstrip("/") + "/"
            r = self.session.request("PROPFIND", url, headers={"Depth": "1"}, timeout=20)
            if r.status_code == 207:
                break
            codes.append(f"{url} → HTTP {r.status_code}")
        else:
            if any("HTTP 401" in c for c in codes):
                raise Failure("compte refusé (401) : --webdav-user / --webdav-password")
            raise Failure("PROPFIND " + " ; ".join(dict.fromkeys(codes)))
        ns = {"d": "DAV:"}
        out = []
        for i, resp in enumerate(ET.fromstring(r.content).findall("d:response", ns)):
            href = resp.findtext("d:href", default="", namespaces=ns)
            if i == 0:
                continue  # the folder itself
            name = urllib.parse.unquote(href.rstrip("/").rsplit("/", 1)[-1])
            is_dir = resp.find(".//d:resourcetype/d:collection", ns) is not None
            size = int(resp.findtext(".//d:getcontentlength", default="0", namespaces=ns) or 0)
            out.append((name, is_dir, size))
        return out

    def open(self, rel):
        for candidate in forms(rel):
            url = self.url(candidate)
            r = self.session.get(url, headers={"Range": "bytes=0-0"}, timeout=20)
            if r.status_code in (200, 206):
                total = r.headers.get("Content-Range", "/").rsplit("/", 1)[-1]
                size = int(total) if total.isdigit() else int(r.headers.get("Content-Length", 0))
                return url, size
            if r.status_code == 401:
                raise Failure("compte refusé (401) : --webdav-user / --webdav-password")
        raise Failure(f"introuvable (404) : {self.url(rel)}")

    def read(self, url, offset, count):
        r = self.session.get(url, headers={"Range": f"bytes={offset}-{offset + count - 1}"}, timeout=60)
        if r.status_code not in (200, 206):
            raise Failure(f"HTTP {r.status_code} en lecture")
        return len(r.content)

    def close(self, handle):
        pass


# --- NFS ---------------------------------------------------------------------

class Nfs:
    name = "NFS"

    def __init__(self, host, export, uid, gid):
        install_xdrlib()
        try:
            import pyNfsClient as nfs
            from pyNfsClient.rpc import RPC
        except ImportError as e:
            raise Failure(f"module manquant ou incompatible ({e}) : pip install pyNfsClient")
        self.nfs = nfs
        _patch_pynfsclient(nfs, RPC)
        self.auth = {"flavor": 1, "machine_name": "nyxara-bench", "uid": uid, "gid": gid, "aux_gid": []}
        try:
            portmap = nfs.Portmap(host, timeout=10)
            portmap.connect()
            mount_port = portmap.getport(nfs.Mount.program, nfs.Mount.program_version)
            nfs_port = portmap.getport(nfs.NFS_PROGRAM, nfs.NFS_V3)
            mount = nfs.Mount(host=host, port=mount_port, timeout=10, auth=self.auth)
            mount.connect()
            res = mount.mnt(export, self.auth)
        except Failure:
            raise
        except Exception as e:
            raise Failure(f"NAS injoignable en NFS : {e}")
        status = res.get("status")
        if status != nfs.MNT3_OK:
            hints = {
                13: "accès refusé : ajoute « insecure » à l'export (/etc/exports) puis exportfs -ra",
                2: f"{export} n'est pas exporté par le NAS (cat /etc/exports)",
            }
            raise Failure(f"montage refusé (code {status}) : " + hints.get(status, "voir /etc/exports"))
        self.root = res["mountinfo"]["fhandle"]
        self.client = nfs.NFSv3(host, nfs_port, 10, self.auth)
        self.client.connect()

    def _lookup(self, rel):
        handle, attributes = self.root, None
        for part in [p for p in rel.split("/") if p]:
            for form in forms(part):
                res = self.client.lookup(handle, form, self.auth)
                if res.get("status") == self.nfs.NFS3_OK:
                    handle = res["resok"]["object"]["data"]
                    attributes = (res["resok"].get("obj_attributes") or {}).get("attributes")
                    break
            else:
                raise Failure(f"« {part} » introuvable dans l'export (chemin {rel})")
        return handle, attributes

    def list(self, rel=""):
        handle = self._lookup(rel)[0] if rel else self.root
        res = self.client.readdir(handle, count=65536, auth=self.auth)
        if res.get("status") != self.nfs.NFS3_OK:
            raise Failure(f"lecture du dossier refusée (code {res.get('status')})")
        names = []
        entries = res["resok"]["reply"]["entries"]
        entry = entries[0] if entries else None
        while entry:
            name = entry["name"].decode("utf-8", "replace") if isinstance(entry["name"], bytes) else str(entry["name"])
            if name not in (".", ".."):
                names.append(name)
            entry = entry["nextentry"][0] if entry["nextentry"] else None
        out = []
        for name in names[:400]:
            try:
                _, attributes = self._lookup((rel + "/" if rel else "") + name)
                is_dir = attributes is not None and attributes.get("type") == 2
                out.append((name, is_dir, 0 if is_dir or attributes is None else attributes.get("size", 0)))
            except Failure:
                out.append((name, False, 0))
        return out

    def open(self, rel):
        handle, attributes = self._lookup(rel)
        size = attributes.get("size", 0) if attributes else 0
        return handle, size

    def read(self, handle, offset, count):
        done = 0
        while done < count:
            res = self.client.read(handle, offset + done, min(count - done, MIB), self.auth)
            if res.get("status") != self.nfs.NFS3_OK:
                raise Failure(f"lecture refusée (code {res.get('status')}) : droits des fichiers ?")
            got = len(res["resok"]["data"])
            done += got
            if got == 0 or res["resok"]["eof"]:
                break
        return done

    def close(self, handle):
        pass


def _patch_pynfsclient(nfs, RPC):
    """Two fixes to pyNfsClient: the mount path length counted in bytes (accents),
    and an unprivileged port when a privileged one can't be had (Linux, Android)."""
    if getattr(nfs, "_nyxara_patched", False):
        return

    def mnt(self, path, auth=None):
        raw = path.encode("utf-8")
        data = struct.pack("!L", len(raw)) + raw + b"\x00" * ((4 - len(raw) % 4) % 4)
        data = RPC.request(self, self.program, self.program_version, 1, data=data, auth=auth if auth else self.auth)
        res = nfs.pack.nfs_pro_v3Unpacker(data).unpack_mountres3()
        if res["status"] == nfs.MNT3_OK:
            self.path = path
        return res

    def connect(self):
        self.client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.client.settimeout(self.timeout)
        for _ in range(20):
            try:
                port = random.randint(600, 1023)
                self.client.bind(("", port))
                self.client_port = port
                break
            except OSError:
                continue
        self.client.connect((self.host, self.port))
        RPC.connections.append(self)

    nfs.Mount.mnt = mnt
    RPC.connect = connect
    nfs._nyxara_patched = True


# --- The test ----------------------------------------------------------------

def find_videos(proto, limit_dirs=300, depth=4):
    """Videos of at least 100 MB below the folder, as paths relative to it."""
    found, queue, seen = [], [("", 0)], 0
    while queue and seen < limit_dirs:
        rel, level = queue.pop(0)
        seen += 1
        try:
            entries = proto.list(rel)
        except Failure:
            continue
        for name, is_dir, size in entries:
            path = (rel + "/" if rel else "") + name
            if is_dir and level < depth and not name.startswith((".", "@", "#")):
                queue.append((path, level + 1))
            elif name.lower().endswith(VIDEO) and size >= 100 * MIB:
                found.append((path, size))
    return found


def measure(proto, rel, size_hint, spots):
    result = {"protocole": proto.name}
    t = time.perf_counter()
    handle, size = proto.open(rel)
    size = size or size_hint
    proto.read(handle, 0, 64 * KIB)
    result["ouverture_ms"] = ms(time.perf_counter() - t)

    t = time.perf_counter()
    proto.read(handle, 0, 4 * MIB)
    proto.read(handle, max(0, size - 2 * MIB), 2 * MIB)
    proto.read(handle, size // 100, 4 * MIB)
    result["demarrage_ms"] = ms(time.perf_counter() - t)

    seeks = []
    for fraction in spots:
        t = time.perf_counter()
        proto.read(handle, int(size * fraction), 2 * MIB)
        seeks.append(ms(time.perf_counter() - t))
    result["saut_median_ms"] = statistics.median(seeks)
    result["saut_max_ms"] = max(seeks)

    t, done, offset = time.perf_counter(), 0, size // 5
    while time.perf_counter() - t < THROUGHPUT_SECONDS and done < THROUGHPUT_MAX and offset + done < size:
        got = proto.read(handle, offset + done, 4 * MIB)
        if not got:
            break
        done += got
    result["debit_mbps"] = mbps(done, time.perf_counter() - t)
    proto.close(handle)
    return result


def explore(protocols):
    for proto in protocols:
        print(f"\n== {proto.name} ==")
        try:
            entries = proto.list("")
            for name, is_dir, size in entries[:25]:
                print(f"  {'[dossier] ' if is_dir else ''}{name}{'' if is_dir else f'  ({size / MIB:.0f} Mo)'}")
            if len(entries) > 25:
                print(f"  … {len(entries) - 25} autres")
        except Exception as e:
            print(f"  ✗ {e}")
            if isinstance(proto, WebDav):
                nearest_parent(proto)
            elif isinstance(proto, Smb):
                nearest_smb(proto)


def nearest_parent(dav):
    """Where the WebDAV server answers: each parent of the URL, then the usual roots, with their HTTP status."""
    parts = urllib.parse.urlsplit(dav.base)
    segments = [s for s in urllib.parse.unquote(parts.path).split("/") if s]
    paths = ["/" + "/".join(segments[:depth]) for depth in range(len(segments) - 1, -1, -1)]
    paths += [p for p in ("/dav", "/webdav", "/remote.php/webdav", "/DATA", "/media") if p not in paths]
    print("  Ce que répond le serveur :")
    for path in paths:
        url = urllib.parse.urlunsplit((parts.scheme, parts.netloc, urllib.parse.quote(path.rstrip("/") + "/", safe="/"), "", ""))
        try:
            r = dav.session.request("PROPFIND", url, headers={"Depth": "1"}, timeout=10)
        except Exception as e:
            print(f"    {path:24} ✗ {e}")
            continue
        print(f"    {path:24} HTTP {r.status_code}" + ("  ← liste ci-dessous" if r.status_code == 207 else ""))
        if r.status_code == 207:
            parent = WebDav.__new__(WebDav)
            parent.__dict__.update(dav.__dict__)
            parent.base = url.rstrip("/")
            for name, is_dir, _ in parent.list("")[:25]:
                print(f"      {'[dossier] ' if is_dir else ''}{name}")
            return
    try:
        r = dav.session.get(urllib.parse.urlunsplit((parts.scheme, parts.netloc, "/", "", "")), timeout=10)
        print(f"  GET / : HTTP {r.status_code}, serveur « {r.headers.get('Server', '?')} »")
    except Exception as e:
        print(f"  GET / : ✗ {e}")


def nearest_smb(smb):
    """The deepest part of the SMB path that exists, and what it holds."""
    base = smb.base
    while base.count("\\") > 3:
        base = base.rsplit("\\", 1)[0]
        try:
            entries = list(smb.smbclient.scandir(base))
        except Exception:
            continue
        print(f"  Existe : {base}")
        for e in entries[:25]:
            print(f"    {'[dossier] ' if e.is_dir() else ''}{e.name}")
        return
    share = smb.base.split("\\")[3] if smb.base.count("\\") >= 3 else "?"
    print(f"  Le partage « {share} » n'existe pas : --smb commence par le nom du partage SMB "
          "(celui affiché en haut de l'onglet Dossiers de Nyxara), pas par le chemin Linux /media/….")


def main():
    p = argparse.ArgumentParser(description="SMB, WebDAV et NFS comparés sur les mêmes vidéos du NAS.")
    p.add_argument("--host", default="192.168.1.131")
    p.add_argument("--smb", help='partage et dossier SMB des mêmes fichiers, ex. "Vidéos/Films"')
    p.add_argument("--webdav", default="http://192.168.1.131:5005/media/sdb1/Vidéos/Films", help="URL WebDAV du dossier")
    p.add_argument("--nfs-export", default="/media/sdb1/Vidéos/Films")
    p.add_argument("--user", help="compte du NAS (SMB, et WebDAV par défaut)")
    p.add_argument("--password", help="demandé si absent")
    p.add_argument("--webdav-user")
    p.add_argument("--webdav-password")
    p.add_argument("--uid", type=int, default=0, help="uid NFS (0 par défaut)")
    p.add_argument("--gid", type=int, default=0)
    p.add_argument("--files", type=int, default=3)
    p.add_argument("--seed", type=int, default=None)
    p.add_argument("--only", default="smb,webdav,nfs", help="protocoles à tester, ex. webdav,nfs")
    p.add_argument("--explore", action="store_true", help="liste seulement ce que chaque protocole voit")
    args = p.parse_args()

    wanted = {w.strip().lower() for w in args.only.split(",")}
    if args.user and args.password is None:
        args.password = getpass.getpass(f"Mot de passe de {args.user} : ")
    webdav_user = args.webdav_user if args.webdav_user is not None else args.user
    webdav_password = args.webdav_password if args.webdav_password is not None else args.password

    protocols = []
    for key, build in (
        ("smb", lambda: Smb(args.host, args.smb, args.user, args.password) if args.smb else None),
        ("webdav", lambda: WebDav(args.webdav, webdav_user, webdav_password)),
        ("nfs", lambda: Nfs(args.host, args.nfs_export, args.uid, args.gid)),
    ):
        if key not in wanted:
            continue
        try:
            proto = build()
            if proto is None:
                print("SMB : ignoré (--smb \"Partage/Dossier\" pour le tester)")
                continue
            protocols.append(proto)
            print(f"{proto.name} : connecté")
        except Exception as e:
            print(f"{key.upper()} : ✗ {e}")

    if not protocols:
        sys.exit("Aucun protocole utilisable.")
    if args.explore:
        explore(protocols)
        return

    videos = []
    for proto in protocols:
        videos = find_videos(proto)
        if videos:
            print(f"{len(videos)} vidéos trouvées via {proto.name}")
            break
    if not videos:
        print("Aucune vidéo de plus de 100 Mo trouvée. Ce que voit chaque protocole :")
        explore(protocols)
        sys.exit(1)

    rng = random.Random(args.seed)
    chosen = rng.sample(videos, min(args.files, len(videos)))
    results = []
    for i, (rel, size) in enumerate(chosen):
        spots = [rng.uniform(0.05, 0.95) for _ in range(SEEKS)]
        order = protocols[i % len(protocols):] + protocols[:i % len(protocols)]
        print(f"\n▶ {rel}  ({size / MIB / 1024:.1f} Go)")
        for proto in order:
            try:
                r = measure(proto, rel, size, spots)
                r["fichier"] = rel
                results.append(r)
                print(f"  {proto.name:7} ouverture {r['ouverture_ms']:5} ms · démarrage {r['demarrage_ms']:5} ms · "
                      f"saut {r['saut_median_ms']:5} ms (max {r['saut_max_ms']}) · débit {r['debit_mbps']} Mb/s")
            except Exception as e:
                results.append({"protocole": proto.name, "fichier": rel, "erreur": str(e)})
                print(f"  {proto.name:7} ✗ {e}")

    print("\n=== Médianes ===")
    for proto in protocols:
        ok = [r for r in results if r["protocole"] == proto.name and "erreur" not in r]
        failed = len([r for r in results if r["protocole"] == proto.name and "erreur" in r])
        if not ok:
            print(f"{proto.name:7} aucun résultat ({failed} échec{'s' if failed > 1 else ''})")
            continue
        med = lambda key: statistics.median(r[key] for r in ok if r[key] is not None)
        print(f"{proto.name:7} ouverture {med('ouverture_ms'):.0f} ms · démarrage {med('demarrage_ms'):.0f} ms · "
              f"saut {med('saut_median_ms'):.0f} ms · débit {med('debit_mbps'):.0f} Mb/s"
              + (f" · {failed} échec{'s' if failed > 1 else ''}" if failed else ""))

    with open("protocol_bench_results.json", "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print("\nDétails : protocol_bench_results.json (à coller pour Claude).")


if __name__ == "__main__":
    main()
