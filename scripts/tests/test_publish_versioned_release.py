"""Run offline with python3 -m unittest discover -s scripts/tests."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

PUBLISH = Path(__file__).resolve().parents[1] / "publish-versioned-release.sh"
COMMIT = "a" * 40
TAG = "v0.33"
SUFFIXES = ("", "-arm64-v8a", "-armeabi-v7a", "-x86_64", "-x86")
APPS = {"scrib": "Scrib", "max": "Scrib-Max", "remote": "Scrib-Remote"}

MOCK = r'''#!/usr/bin/env python3
import hashlib, json, os, sys
from pathlib import Path

args = sys.argv[1:]
tool = Path(sys.argv[0]).name
p = Path(os.environ["MOCK_STATE"])
s = json.loads(p.read_text())
tag = s["tag"]

def save():
    p.write_text(json.dumps(s))

if tool == "apksigner":
    assert args[0] == "verify"
    sys.exit(Path(args[1]).name == s.get("bad_signature"))

if tool == "git":
    assert args[:1] == ["ls-remote"] and "origin" in args
    if "--refs" in args:
        for candidate in s["tags"]:
            print(s["tip"], "refs/tags/" + candidate, sep="\t")
        sys.exit()
    requested = args[3].removeprefix("refs/tags/")
    if requested in ("latest", "v0.32") and requested != tag:
        print(("b" if s.get("legacy_mismatch") and requested == "latest" else "c") * 40,
              "refs/tags/" + requested, sep="\t")
        sys.exit()
    if s.get("annotated"):
        print("f" * 40, "refs/tags/" + tag, sep="\t")
        print(s["tip"], "refs/tags/" + tag + "^{}", sep="\t")
    else:
        print(s["tip"], "refs/tags/" + tag, sep="\t")
    sys.exit()

assert tool == "gh"
if args[:3] == ["api", "--paginate", "--slurp"]:
    if "assets" in args[3]:
        assert "/233/assets?" in args[3] and s["release"]
        print(json.dumps([s["assets"]]))
    else:
        # Historical release lives on a separate page. All writes below are
        # checked against the versioned tag or that tag's separate numeric ID.
        print(json.dumps([[{"id": 1, "tag_name": "latest", "name": "Scrib 0.32",
                            "draft": False, "prerelease": False}],
                           [s["release"]] if s["release"] else [], s.get("published", [])]))
elif args[:2] == ["release", "create"]:
    assert args[2] == tag and "--draft" in args and "--verify-tag" in args and not s["release"]
    s["writes"].append("create draft")
    s["release"] = {"id": 233, "tag_name": tag, "draft": True, "prerelease": False}
    save()
elif args[:2] == ["release", "upload"]:
    assert args[2] == tag and s["release"]["draft"]
    for item in args[3:args.index("--clobber")]:
        path = Path(item)
        body = path.read_bytes()
        s["assets"] = [a for a in s["assets"] if a["name"] != path.name]
        s["assets"].append({"name": path.name, "size": len(body), "state": "uploaded",
            "digest": "sha256:" + ("0" * 64 if s.get("bad_upload") else hashlib.sha256(body).hexdigest())})
        s["writes"].append("upload " + path.name)
    save()
else:
    assert args[:4] == ["api", "--method", "PATCH", "repos/example/scrib/releases/233"]
    assert s["release"]["draft"] and len(s["assets"]) == 15 and "draft=false" in args
    s["writes"].append(args[-1])
    s["release"]["draft"] = False
    save()
    print(json.dumps(dict(s["release"], html_url="https://example.invalid/releases/" + tag)))
'''


class VersionedReleaseTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.artifacts = self.root / "apks"
        self.state = self.root / "state"
        self.notes = self.root / "notes"
        self.notes.touch()
        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        sdk = self.root / "sdk"
        tools = sdk / "build-tools/35.0.0"
        tools.mkdir(parents=True)
        for name in ("git", "gh", "apksigner"):
            executable = (tools if name == "apksigner" else bin_dir) / name
            executable.write_text(MOCK)
            executable.chmod(0o755)
        self.env = dict(os.environ, PATH=f'{bin_dir}:{os.environ["PATH"]}',
                        ANDROID_HOME=str(sdk), GITHUB_REPOSITORY="example/scrib", MOCK_STATE=str(self.state))
        self.files = {}
        for product, app in APPS.items():
            sub = self.artifacts / ("apk-" + product)
            sub.mkdir(parents=True)
            for suffix in SUFFIXES:
                path = sub / (app + suffix + ".apk")
                path.write_text(path.name + "\n")
                self.files[path.name] = path
        self.fixture = {"tag": TAG, "tip": COMMIT, "tags": ["v0.32", TAG], "release": None,
                        "assets": [], "writes": []}

    def apk(self, name):
        data = self.files[name].read_bytes()
        return {"name": name, "digest": "sha256:" + hashlib.sha256(data).hexdigest(),
                "state": "uploaded", "size": len(data)}

    def invoke(self, passes, tag=TAG):
        self.state.write_text(json.dumps(self.fixture))
        r = subprocess.run(["bash", PUBLISH, tag, COMMIT, self.artifacts, self.notes],
                           env=self.env, capture_output=True, text=True)
        self.fixture = json.loads(self.state.read_text())
        self.assertEqual(r.returncode == 0, passes, r.stdout + r.stderr)

    def draft(self, *, public=False):
        self.fixture["release"] = {"id": 233, "tag_name": TAG, "draft": not public, "prerelease": False}

    def test_creates_draft_and_publishes_after_fifteen_verified_uploads(self):
        self.fixture["annotated"] = True
        self.fixture["tags"].append("v0.34")  # Exists but is not yet released.
        self.invoke(True)
        self.assertEqual(self.fixture["writes"][0], "create draft")
        self.assertEqual(len(self.fixture["assets"]), 15)
        self.assertEqual(self.fixture["writes"][-1], "make_latest=true")
        self.assertFalse(self.fixture["release"]["draft"])
        self.fixture["writes"] = []
        self.invoke(True)
        self.assertEqual(self.fixture["writes"], [])

    def test_older_than_published_tag_and_partial_draft_resume(self):
        self.fixture["published"] = [{"tag_name": "v0.34", "draft": False, "prerelease": False}]
        self.draft()
        self.fixture["assets"] = [self.apk("Scrib.apk")]
        self.invoke(True)
        self.assertEqual(len(self.fixture["writes"]), 15)  # 14 uploads and publish
        self.assertNotIn("upload Scrib.apk", self.fixture["writes"])
        self.assertEqual(self.fixture["writes"][-1], "make_latest=false")

    def test_published_incomplete_release_is_not_touched(self):
        self.draft(public=True)
        self.fixture["assets"] = [self.apk("Scrib.apk")]
        self.invoke(False)
        self.assertEqual(self.fixture["writes"], [])

    def test_wrong_digest_leaves_draft_unpublished(self):
        self.fixture["bad_upload"] = True
        self.invoke(False)
        self.assertTrue(self.fixture["release"]["draft"])
        self.assertEqual(len(self.fixture["assets"]), 15)

    def test_missing_or_invalid_local_apk_stops_before_any_write(self):
        self.fixture["bad_signature"] = "Scrib.apk"
        self.invoke(False)
        self.assertEqual(self.fixture["writes"], [])
        self.fixture.pop("bad_signature")
        self.files["Scrib.apk"].unlink()
        self.invoke(False)
        self.assertEqual(self.fixture["writes"], [])

    def test_frozen_legacy_version_protects_default_from_older_publication(self):
        self.fixture["tag"] = "v0.31"
        self.invoke(True, tag="v0.31")
        self.assertEqual(self.fixture["writes"][-1], "make_latest=false")

    def test_disagreement_between_legacy_version_and_tag_leaves_default_alone(self):
        self.fixture["legacy_mismatch"] = True
        self.invoke(True)
        self.assertEqual(self.fixture["writes"][-1], "make_latest=false")

    def test_moved_tag_and_legacy_latest_are_refused(self):
        self.fixture["tip"] = "b" * 40
        self.invoke(False)
        self.assertEqual(self.fixture["writes"], [])
        self.invoke(False, tag="latest")
        self.assertEqual(self.fixture["writes"], [])


if __name__ == "__main__":
    unittest.main()
