"""Finds the Minecraft jar Loom used and writes javap signatures of the classes EarthLife touches (CI only)."""
import glob, os, subprocess, sys, zipfile

classes = [l.strip() for l in open(sys.argv[1]) if l.strip()]
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
cp = os.pathsep.join([mc] + fabric)
with open(os.path.join(out, "api.txt"), "w") as f:
    for c in classes:
        r = subprocess.run(["javap", "-public", "-cp", cp, c], capture_output=True, text=True)
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
