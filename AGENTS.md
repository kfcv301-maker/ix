# Maintained integration contract

The backend one-click installer entry URL must never change:

`https://raw.githubusercontent.com/kfcv301-maker/ix/main/backend_install.sh`

Keep this same URL in the VPS backend deployment template and public installation instructions. Change reviewed backend source versions through `REPO_REF`, not by changing the entry URL. When changing `backend_install.sh`, regenerate its adjacent `backend_install.sh.sha256` using the exact LF script bytes and run `tests/backend_installer_contract_test.py`. Preserve existing default installation directories, Compose project/volume names and credentials during upgrades.
