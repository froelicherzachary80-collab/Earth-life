"""Finds the Minecraft jar Loom used and writes javap signatures of the classes EarthLife touches (CI only)."""
import glob, os, subprocess, sys, zipfile

import re
classes = [l.strip() for l in open(sys.argv[1]) if l.strip()]
# plus every class the mod source imports
for root, _, files in os.walk("fabric-mod/src"):
    for fn in files:
        if fn.endswith(".java"):
            for m in re.finditer(r"^import (net\.(?:minecraft|fabricmc)[\w.$]+);", open(os.path.join(root, fn)).read(), re.M):
                if not m.group(1).endswith("*"): classes.append(m.group(1))
classes = sorted(set(classes))
out = sys.argv[2]
home = os.path.expanduser("~/.gradle/caches")
cands = [j for j in glob.glob(home + "/**/*.jar", recursive=True) if "minecraft" in os.path.basename(j).lower()]
cands += glob.glob("fabric-mod/.gradle/loom-cache/**/*.jar", recursive=True)
mc = None
for j in sorted(cands, key=os.path.getsize, reverse=True):
    try:
        with zipfile.ZipFile(j) as z:
            if "net/minecraft/world/entity/Entity.class" in z.namelist():
                mc = j; break
    except Exception:
        pass
open(os.path.join(out, "mc-jar.txt"), "w").write(str(mc) + "\n" + "\n".join(cands[:60]))
if not mc:
    sys.exit("no minecraft jar found")
with zipfile.ZipFile(mc) as z:
    names = sorted(n[:-6].replace("/", ".") for n in z.namelist() if n.endswith(".class") and n.startswith("net/minecraft"))
open(os.path.join(out, "mc-classes.txt"), "w").write("\n".join(names))
fabric = [j for j in glob.glob(home + "/**/fabric-*.jar", recursive=True)]
fab_names = set()
for j in fabric:
    try:
        with zipfile.ZipFile(j) as z:
            fab_names.update(n[:-6].replace("/", ".") for n in z.namelist() if n.endswith(".class") and n.startswith("net/fabricmc/fabric/api"))
    except Exception:
        pass
open(os.path.join(out, "fabric-classes.txt"), "w").write("\n".join(sorted(fab_names)))
alljars = glob.glob(home + "/**/*.jar", recursive=True)
open(os.path.join(out, "jars.txt"), "w").write("\n".join(sorted(alljars)))
cp = os.pathsep.join([mc] + fabric + [j for j in alljars if any(k in j for k in ("datafixerupper", "brigadier", "lwjgl", "joml", "fastutil", "gson", "guava", "authlib"))])
# Vanilla data files we mirror (dimension type, presets, tags) so EarthLife's JSON matches the 26.3 format exactly.
with zipfile.ZipFile(mc) as z:
    for n in z.namelist():
        if n.startswith(("data/minecraft/dimension_type/", "data/minecraft/worldgen/world_preset/", "data/minecraft/tags/worldgen/world_preset/",
                         "data/minecraft/world_clock/", "data/minecraft/timeline/")) and n.endswith(".json"):
            dst = os.path.join(out, "vanilla", n); os.makedirs(os.path.dirname(dst), exist_ok=True)
            open(dst, "wb").write(z.read(n))
with open(os.path.join(out, "api.txt"), "w") as f:
    for c in classes:
        r = subprocess.run(["javap", "-public", "-cp", cp, c.replace("$", "$")], capture_output=True, text=True)
        f.write(f"==== {c}\n{r.stdout or r.stderr}\n")
# Fabric API classes referenced by EarthLife
fab = ["net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper", "net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback",
       "net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry", "net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry",
       "net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry", "net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry",
       "net.fabricmc.fabric.api.event.player.UseBlockCallback", "net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents",
       "net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback", "net.fabricmc.fabric.api.networking.v1.PlayerLookup"]
with open(os.path.join(out, "fabric-api.txt"), "w") as f:
    for c in fab:
        r = subprocess.run(["javap", "-public", "-cp", cp, c], capture_output=True, text=True)
        f.write(f"==== {c}\n{r.stdout or r.stderr}\n")
print("dumped", len(classes), "classes from", mc)

# Full public-signature dump of every net.minecraft class (text only, gzipped) for offline porting.
import gzip
if os.environ.get("FULL_DUMP") != "1":
    sys.exit(0)
with gzip.open(os.path.join(out, "api-all.txt.gz"), "wt") as g:
    batch = 300
    for i in range(0, len(names), batch):
        chunk = [n for n in names[i:i + batch] if "$$" not in n]
        r = subprocess.run(["javap", "-protected", "-cp", cp] + chunk, capture_output=True, text=True)
        g.write(r.stdout)
    fab_list = sorted(fab_names)
    for i in range(0, len(fab_list), batch):
        r = subprocess.run(["javap", "-public", "-cp", cp] + [n for n in fab_list[i:i + batch] if not n.endswith("package-info")], capture_output=True, text=True)
        g.write(r.stdout)
print("full dump written")
