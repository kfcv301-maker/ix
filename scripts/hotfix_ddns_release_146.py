"""Repair the two installer assets used by already-deployed 1.4.6 commands.

This operator-requested compatibility hotfix keeps the release tag and Agent
binaries unchanged. Original assets are renamed, never deleted. Any partial
name switch is rolled back. Credentials come only from the Actions runtime.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import time
import urllib.error
import urllib.parse
import urllib.request

REPO = "kfcv301-maker/ix"
TAG = "1.4.6"
EXPECTED_OLD = "46b8185915ccccbb0bd1fa3f376e378df7ae37a66706a3f1c2b8828f677dba16"
OLD = b'''  grep -oE '"id"[[:space:]]*:[[:space:]]*"[^"]+"' | wc -l | tr -d '[:space:]' '''.rstrip()
NEW = b'''  # A zero-record result is valid: grep returns 1 when it finds no IDs.
  { grep -oE '"id"[[:space:]]*:[[:space:]]*"[^"]+"' || [[ "$?" -eq 1 ]]; } | wc -l | tr -d '[:space:]' '''.rstrip()


def api(path, method="GET", data=None, content_type="application/json"):
    url = path if path.startswith("https://") else "https://api.github.com" + path
    if isinstance(data, dict):
        data = json.dumps(data).encode()
    request = urllib.request.Request(url, data=data, method=method, headers={
        "Authorization": "Bearer " + os.environ["GH_TOKEN"],
        "Accept": "application/vnd.github+json", "User-Agent": "Lunaris-DDNS-146-hotfix",
        "Content-Type": content_type,
    })
    with urllib.request.urlopen(request, timeout=45) as response:
        return json.load(response)


def download(url):
    request = urllib.request.Request(url, headers={"User-Agent": "Lunaris-DDNS-146-verification"})
    with urllib.request.urlopen(request, timeout=45) as response:
        return response.read()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def rename(asset, name):
    return api(f"/repos/{REPO}/releases/assets/{asset['id']}", "PATCH", {"name": name})


def main():
    release = api(f"/repos/{REPO}/releases/tags/{TAG}")
    if release.get("immutable"):
        raise RuntimeError("Release is immutable; no assets changed")
    assets = {asset["name"]: asset for asset in release["assets"]}
    installer_asset, checksum_asset = assets["install.sh"], assets["install.sh.sha256"]
    original = download(installer_asset["browser_download_url"])
    original_checksum = download(checksum_asset["browser_download_url"])
    if digest(original) != original_checksum.decode().split()[0]:
        raise RuntimeError("Existing public installer/checksum pair is inconsistent")
    if digest(original) != EXPECTED_OLD:
        if NEW in original and OLD not in original:
            print(json.dumps({"already_fixed": True, "sha256": digest(original)}))
            return
        raise RuntimeError("Release installer changed since review; no hotfix applied")
    if original.count(OLD) != 1:
        raise RuntimeError("Expected unique DDNS helper missing")
    fixed = original.replace(OLD, NEW)
    fixed_checksum = (digest(fixed) + "  install.sh\n").encode()
    # Validate the release-specific generated DDNS code before publishing.
    import importlib.util
    spec = importlib.util.spec_from_file_location("ddns_regression", "tests/ddns_install_recovery_test.py")
    regression = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(regression)
    match = re.search(rb"cat > \"\$INSTALL_DIR/cloudflare-ddns\.sh\" <<'EOF'\n(.*?)\nEOF", fixed, re.S)
    checks = regression.exercise(match.group(1).decode() + "\n")
    if not all(check["passed"] for check in checks):
        raise RuntimeError("Release-specific DDNS recovery regressions failed")
    archive = Path("release-hotfix-audit")
    archive.mkdir(exist_ok=True)
    (archive / "install.sh.original").write_bytes(original)
    (archive / "install.sh.sha256.original").write_bytes(original_checksum)
    (archive / "install.sh.fixed").write_bytes(fixed)
    (archive / "regressions.json").write_text(json.dumps(checks, indent=2) + "\n")
    unchanged = {asset["id"]: (asset["name"], asset.get("digest"), asset["size"])
                 for asset in release["assets"] if asset["name"] not in ("install.sh", "install.sh.sha256")}
    tag_before = api(f"/repos/{REPO}/git/ref/tags/{TAG}")["object"]
    suffix = ".before-ddns-empty-fix-20261015"
    if "install.sh" + suffix in assets or "install.sh.sha256" + suffix in assets:
        raise RuntimeError("Backup asset names already exist; inspect state before retry")
    upload_url = release["upload_url"].split("{")[0]
    staged = []
    old_renamed = []
    candidates = [("install.sh", fixed), ("install.sh.sha256", fixed_checksum)]
    try:
        # Upload and verify candidates while the original URLs remain intact.
        for name, body in candidates:
            candidate_name = name + ".ddns-candidate-" + digest(fixed)[:12]
            if candidate_name in assets:
                raise RuntimeError("Candidate asset already exists; inspect prior attempt")
            asset = api(upload_url + "?" + urllib.parse.urlencode({"name": candidate_name}),
                        "POST", body, "application/octet-stream")
            staged.append((name, asset))
            if download(asset["browser_download_url"]) != body:
                raise RuntimeError("Uploaded candidate bytes did not verify")
        for original_asset in (installer_asset, checksum_asset):
            rename(original_asset, original_asset["name"] + suffix)
            old_renamed.append(original_asset)
        for name, asset in staged:
            rename(asset, name)
    except Exception:
        # Keep candidate bytes under their staging names, restore old URL names.
        current = api(f"/repos/{REPO}/releases/{release['id']}")
        by_id = {asset["id"]: asset for asset in current["assets"]}
        for name, asset in reversed(staged):
            if by_id.get(asset["id"], {}).get("name") == name:
                rename(asset, name + ".ddns-candidate-" + digest(fixed)[:12])
        for asset in reversed(old_renamed):
            rename(asset, asset["name"])
        raise
    after = api(f"/repos/{REPO}/releases/{release['id']}")
    after_map = {asset["name"]: asset for asset in after["assets"]}
    verified = False
    # Public release redirects can be briefly cached during the name switch.
    for attempt in range(12):
        script_bytes = download(after_map["install.sh"]["browser_download_url"])
        sum_bytes = download(after_map["install.sh.sha256"]["browser_download_url"])
        if script_bytes == fixed and sum_bytes == fixed_checksum:
            verified = True
            break
        time.sleep(5)
    after_by_id = {asset["id"]: asset for asset in after["assets"]}
    others_preserved = all((after_by_id[key]["name"], after_by_id[key].get("digest"),
                            after_by_id[key]["size"]) == value for key, value in unchanged.items())
    tag_preserved = api(f"/repos/{REPO}/git/ref/tags/{TAG}")["object"] == tag_before
    result = {"fixed_command_urls_verified": verified, "tag_unchanged": tag_preserved,
              "other_assets_unchanged": others_preserved, "old_sha256": EXPECTED_OLD,
              "new_sha256": digest(fixed), "regressions_passed": len(checks),
              "backup_assets": [asset["name"] + suffix for asset in (installer_asset, checksum_asset)]}
    (archive / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))
    if not (verified and tag_preserved and others_preserved):
        raise RuntimeError("Post-publication checks did not all pass; inspect hotfix audit")


if __name__ == "__main__":
    main()
