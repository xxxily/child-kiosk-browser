#!/usr/bin/env bash
set -euo pipefail

manifest="${1:-app/build/intermediates/merged_manifest/standardDebug/AndroidManifest.xml}"
package_name="site.anzz.childkiosk"

if [[ ! -f "$manifest" ]]; then
  echo "Merged manifest not found: $manifest" >&2
  exit 1
fi

python3 - "$manifest" "$package_name" <<'PY'
import sys
import xml.etree.ElementTree as ET

manifest_path, package_name = sys.argv[1:]
android = "{http://schemas.android.com/apk/res/android}"
root = ET.parse(manifest_path).getroot()
activities = {
    item.get(android + "name"): item
    for item in root.find("application").findall("activity")
}

def require(name, attribute, expected):
    activity = activities.get(name)
    if activity is None:
        raise SystemExit(f"Missing activity {name}")
    actual = activity.get(android + attribute)
    if actual != expected:
        raise SystemExit(
            f"{name} {attribute}: expected {expected!r}, got {actual!r}"
        )

main = f"{package_name}.MainActivity"
kiosk = f"{package_name}.WebViewActivity"
persistent = f"{package_name}.PersistentWebViewActivity"

require(main, "launchMode", "singleTask")
require(kiosk, "process", ":webview")
require(persistent, "process", ":webview")
require(persistent, "launchMode", "singleTask")
require(persistent, "taskAffinity", f"{package_name}.webview")

kiosk_affinity = activities[kiosk].get(android + "taskAffinity", package_name)
if kiosk_affinity != package_name:
    raise SystemExit(
        f"{kiosk} must remain in the main task; got affinity {kiosk_affinity!r}"
    )

kiosk_launch_mode = activities[kiosk].get(android + "launchMode", "standard")
if kiosk_launch_mode != "standard":
    raise SystemExit(
        f"{kiosk} launchMode must remain standard for Lock Task; got {kiosk_launch_mode!r}"
    )

print(f"Verified WebView task topology in {manifest_path}")
PY
