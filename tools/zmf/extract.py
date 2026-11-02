#!/usr/bin/env python3
"""Copies the ZMF Cinema code out of EarthLife into the standalone mod (zmf-cinema-mod/), renaming packages.
Hand-written standalone files (marked // STANDALONE) are never overwritten. Run from the repo root."""
import os, re, shutil, json

SRC = "fabric-mod/src/main/java/earthlife/mod"
CORE = "core/src/main/java/earthlife/core"
OUT = "zmf-cinema-mod/src/main/java/zmf/cinema"
RES_IN = "fabric-mod/src/main/resources/assets/earthlife"
RES_OUT = "zmf-cinema-mod/src/main/resources/assets/zmfcinema"

FILES = {  # source -> destination (relative)
    **{f"{SRC}/zmf/{n}": f"{OUT}/{n}" for n in ["LocatorItem.java", "MultiplexBuilder.java", "MultiplexRecord.java", "MultiplexService.java",
        "ZmfCommands.java", "ZmfConfig.java", "ZmfEconomy.java", "ZmfLists.java", "ZmfPayloads.java"]},
    **{f"{SRC}/cinema/{n}": f"{OUT}/block/{n}" for n in ["CinemaContent.java", "CinemaScreenEntity.java", "CounterBlock.java", "DecorBlock.java",
        "SeatBlock.java", "SeatEntity.java", "CinemaCommands.java"]},
    **{f"{SRC}/client/cinema/{n}": f"{OUT}/client/media/{n}" for n in ["Atmosphere.java", "AudioOut.java", "BrowserControlScreen.java", "BrowserScreens.java",
        "CinemaClient.java", "CinemaConfig.java", "CinemaScreenRenderer.java", "Ffmpeg.java", "FourD.java", "Haptics.java", "MediaLibrary.java",
        "MoviePickerScreen.java", "VideoPlayer.java"]},
    **{f"{SRC}/client/zmf/{n}": f"{OUT}/client/{n}" for n in ["JellyfinScreen.java", "LibraryShare.java", "LocatorHud.java", "Posters.java", "ZmfScreen.java", "ZmfState.java"]},
    f"{SRC}/mixin/CameraMixin.java": f"{OUT}/mixin/CameraMixin.java",
    f"{SRC}/mixin/mcef/McefPaintMixin.java": f"{OUT}/mixin/mcef/McefPaintMixin.java",
    f"{SRC}/mixin/mcef/McefPlugin.java": f"{OUT}/mixin/mcef/McefPlugin.java",
    **{f"{CORE}/cinema/{n}": f"{OUT}/core/{n}" for n in ["FormatAllocator.java", "MovieScheduler.java", "MultiplexPlanner.java", "SiteGrid.java"]},
    **{f"{CORE}/cinema/media/{n}": f"{OUT}/core/media/{n}" for n in os.listdir(f"{CORE}/cinema/media")},
    f"{CORE}/build/Blueprint.java": f"{OUT}/core/Blueprint.java",
    f"{CORE}/util/Json.java": f"{OUT}/core/Json.java",
    f"{CORE}/world/AtomicFiles.java": f"{OUT}/core/AtomicFiles.java",
}

RENAMES = [  # order matters: longest first
    ("earthlife.core.cinema.media", "zmf.cinema.core.media"), ("earthlife.core.cinema", "zmf.cinema.core"),
    ("earthlife.core.build.Blueprint", "zmf.cinema.core.Blueprint"), ("earthlife.core.util.Json", "zmf.cinema.core.Json"),
    ("earthlife.core.world.AtomicFiles", "zmf.cinema.core.AtomicFiles"),
    ("package earthlife.core.build;", "package zmf.cinema.core;"), ("package earthlife.core.util;", "package zmf.cinema.core;"),
    ("package earthlife.core.world;", "package zmf.cinema.core;"),
    ("earthlife.mod.client.cinema", "zmf.cinema.client.media"), ("earthlife.mod.client.zmf", "zmf.cinema.client"),
    ("earthlife.mod.client.EarthLifeClient", "zmf.cinema.client.ZmfCinemaClient"), ("earthlife.mod.client.ClientState", "zmf.cinema.client.ClientState"),
    ("earthlife.mod.mixin.mcef", "zmf.cinema.mixin.mcef"), ("earthlife.mod.mixin", "zmf.cinema.mixin"),
    ("earthlife.mod.cinema", "zmf.cinema.block"), ("earthlife.mod.zmf", "zmf.cinema"),
    ("earthlife.mod.net", "zmf.cinema.net"), ("earthlife.mod.McCompat", "zmf.cinema.McCompat"),
    ("earthlife.mod.EarthLifeMod", "zmf.cinema.ZmfCinemaMod"),
    ("EarthLifeClient", "ZmfCinemaClient"), ("EarthLifeMod.LOG", "ZmfCinemaMod.LOG"), ("EarthLifeMod.id", "ZmfCinemaMod.id"),
    ("EarthLife-cinema", "ZMF-cinema"), ("\"EarthLife cinema", "\"ZMF cinema"), ("app_id=earthlife.minecraft&app_name=EarthLife", "app_id=zmfcinema.minecraft&app_name=ZMFCinema"),
    ("Client=\\\"EarthLife\\\"", "Client=\\\"ZMF Cinema\\\""), ("EarthLife/0.5.0 (Minecraft mod)", "ZMFCinema/0.1.0 (Minecraft mod)"),
    ("earthlife-cinema.json", "zmfcinema-client.json"), ("resolve(\"earthlife\").resolve(\"ffmpeg\")", "resolve(\"zmfcinema\").resolve(\"ffmpeg\")"),
    ("Identifier.fromNamespaceAndPath(\"earthlife\"", "Identifier.fromNamespaceAndPath(\"zmfcinema\""),
    ("\"earthlife:", "\"zmfcinema:"), ("\"earthlife cinema ", "\"zmfcinema film "), ("earthlife cinema pick", "zmfcinema film pick"),
    ("Commands.literal(\"earthlife\").then(Commands.literal(\"cinema\")", "Commands.literal(\"zmfcinema\").then(Commands.literal(\"film\")"),
    ("import earthlife.mod.EarthLifeServices;\n", ""), ("zmf.cinema.client.ClientState.earth()", "false"), ("earthlife$", "zmfcinema$"), ("itemGroup.earthlife.", "itemGroup.zmfcinema."),
]

def convert(text):
    for a, b in RENAMES:
        text = text.replace(a, b)
    return text

for src, dst in FILES.items():
    if os.path.exists(dst) and "// STANDALONE" in open(dst).read():
        continue
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    open(dst, "w").write(convert(open(src).read()))

# assets: everything cinema-related, ids rewritten to the zmfcinema namespace
for d in ["blockstates", "items", "models/block", "models/item", "textures/block", "textures/item", "textures/misc"]:
    src = os.path.join(RES_IN, d); dst = os.path.join(RES_OUT, d)
    os.makedirs(dst, exist_ok=True)
    for n in os.listdir(src):
        p = os.path.join(src, n)
        if n.endswith(".json"):
            open(os.path.join(dst, n), "w").write(open(p).read().replace("earthlife:", "zmfcinema:"))
        else:
            shutil.copyfile(p, os.path.join(dst, n))
lang = json.load(open(os.path.join(RES_IN, "lang/en_us.json")))
names = set(os.path.splitext(n)[0] for n in os.listdir(os.path.join(RES_IN, "items")))
out = {}
for k, v in lang.items():
    parts = k.split(".")
    if len(parts) == 3 and parts[0] in ("block", "item") and parts[2] in names:
        out[f"{parts[0]}.zmfcinema.{parts[2]}"] = v
out["itemGroup.zmfcinema.cinema"] = "ZMF Cinema"
out["key.zmfcinema.menu"] = "ZMF Cinema menu / web browser"
out["key.zmfcinema.fx_stop"] = "4DX + TactSuit emergency stop"
out["key.category.zmfcinema.zmfcinema"] = "ZMF Cinema"
out["item.zmfcinema.cinema_pass"] = "ZMF Cinema Pass"
os.makedirs(os.path.join(RES_OUT, "lang"), exist_ok=True)
json.dump(out, open(os.path.join(RES_OUT, "lang/en_us.json"), "w"), indent=2, ensure_ascii=False)
hd_in = "fabric-mod/src/main/resources/resourcepacks/zmf_hd"; hd_out = "zmf-cinema-mod/src/main/resources/resourcepacks/zmf_hd"
if os.path.exists(hd_out): shutil.rmtree(hd_out)
shutil.copytree(hd_in, hd_out)
os.rename(os.path.join(hd_out, "assets/earthlife"), os.path.join(hd_out, "assets/zmfcinema"))
print("extracted", len(FILES), "source files")
