"""Finds the newest stable Fabric Loader, Fabric API and Loom for the target Minecraft version (CI only)."""
import json, re, sys, urllib.request

MC = "26.3"

def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "earthlife-ci"}), timeout=60) as r:
        return r.read().decode()

out = {"minecraft": MC}
manifest = json.loads(get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
ids = [v["id"] for v in manifest["versions"]]
print("latest release:", manifest["latest"]["release"], "| latest snapshot:", manifest["latest"]["snapshot"])
print("26.3 in manifest:", MC in ids, "| 26.x ids:", [i for i in ids if i.startswith("26.")][:15])
if MC not in ids:
    out["minecraft"] = manifest["latest"]["release"]
    print("!! 26.3 not found; falling back to", out["minecraft"])
loaders = json.loads(get(f"https://meta.fabricmc.net/v2/versions/loader/{out['minecraft']}"))
out["loader"] = next((l["loader"]["version"] for l in loaders if l["loader"].get("stable")), loaders[0]["loader"]["version"])
meta = get("https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml")
apis = [v for v in re.findall(r"<version>([^<]+)</version>", meta) if v.endswith("+" + out["minecraft"])]
out["fabric_api"] = apis[-1] if apis else "MISSING"
loom_meta = get("https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml")
looms = [v for v in re.findall(r"<version>([^<]+)</version>", loom_meta) if re.fullmatch(r"\d+\.\d+\.\d+", v)]
looms.sort(key=lambda v: tuple(map(int, v.split("."))))
out["loom"] = looms[-1]
for pid in ("net.fabricmc.fabric-loom", "fabric-loom", "net.fabricmc.fabric-loom-remap"):
    try:
        get(f"https://maven.fabricmc.net/{pid.replace('.', '/') if False else pid.replace('.', '/')}/{pid}.gradle.plugin/maven-metadata.xml")
        print("plugin marker present:", pid)
    except Exception as e:
        print("plugin marker missing:", pid, e)
print(json.dumps(out, indent=1))
json.dump(out, open(sys.argv[1], "w"))
