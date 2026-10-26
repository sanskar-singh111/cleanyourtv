"""tools/theme_sync.py: the same commands as tools/theme.sh, and only what changed is sent or deleted."""
import hashlib
import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("theme_sync", ROOT / "tools" / "theme_sync.py")
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


def theme_sh(expr):
    """Evaluates `expr` in bash after theme.sh's own definitions (PKG, DIR, URI, TMP, stage, put, del, RESTART)."""
    keep = [line for line in (ROOT / "tools" / "theme.sh").read_text().splitlines()
            if line.startswith(("PKG=", "DIR=", "URI=", "TMP=", "stage()", "put()", "del()", "RESTART="))]
    assert len(keep) == 8, keep
    return subprocess.run(["bash", "-c", "\n".join(keep) + "\n" + expr], capture_output=True, text=True,
                          check=True).stdout.rstrip("\n")


@unittest.skipUnless(shutil.which("bash"), "needs bash")
class SameCommandsTest(unittest.TestCase):
    def test_paths(self):
        self.assertEqual(theme_sh('echo "$PKG $DIR $URI $TMP"'), f"{sync.PKG} {sync.DIR} {sync.URI} {sync.TMP}")

    def test_restart(self):
        self.assertEqual(theme_sh('printf "%s" "$RESTART"'), sync.RESTART)

    def test_write_and_delete(self):
        for name in ("config.json", "wallpapers/a.jpg", "logos/netflix.png", "theme_revision"):
            with self.subTest(name=name):
                self.assertEqual(theme_sh(f'stage {name}'), sync.stage(name))
                self.assertEqual(theme_sh(f'put {name}'), sync.put(name))
                self.assertEqual(theme_sh(f'del {name}'), sync.delete(name))


class PlanTest(unittest.TestCase):
    def setUp(self):
        self.theme = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.theme, ignore_errors=True)
        (self.theme / "config.json").write_text("{}")
        (self.theme / "wallpaper.png").write_bytes(b"wall")
        for folder, files in {"logos": ["a.png", "b.png", "notes.txt"], "wallpapers": ["w.jpg", "captions.txt"],
                              "Bad Folder": ["x.png"], "moods": ["bad name.jpg", "p.jpg"]}.items():
            (self.theme / folder).mkdir()
            for f in files:
                (self.theme / folder / f).write_bytes(f.encode())

    def sums(self, names):
        local = sync.theme_files(str(self.theme))
        return {n: hashlib.md5(Path(local[n]).read_bytes()).hexdigest() for n in names}

    def test_files_are_the_ones_theme_sh_sends(self):
        self.assertEqual(list(sync.theme_files(str(self.theme))),
                         ["config.json", "wallpaper.png", "wallpapers/w.jpg", "logos/a.png", "logos/b.png",
                          "moods/p.jpg", "wallpapers/captions.txt"])

    def test_only_changed_files_are_sent(self):
        local = sync.theme_files(str(self.theme))
        remote = self.sums(local)
        remote["logos/a.png"] = "0" * 32
        changed, stale = sync.plan(local, remote)
        self.assertEqual(changed, ["logos/a.png"])
        self.assertEqual(stale, [])

    def test_only_removed_pictures_are_deleted(self):
        local = sync.theme_files(str(self.theme))
        remote = self.sums(local)
        remote.update({"logos/old.png": "1" * 32, "wallpaper.jpg": "2" * 32, "theme_revision": "3" * 32,
                       "cache/data.bin": "4" * 32, "logos/readme.txt": "5" * 32})
        changed, stale = sync.plan(local, remote)
        self.assertEqual(changed, [])
        self.assertEqual(stale, ["logos/old.png", "wallpaper.jpg"])

    def test_an_unknown_tv_gets_everything(self):
        local = sync.theme_files(str(self.theme))
        changed, stale = sync.plan(local, None)
        self.assertEqual(changed, list(local))
        self.assertEqual(stale, [])


if __name__ == "__main__":
    unittest.main()
