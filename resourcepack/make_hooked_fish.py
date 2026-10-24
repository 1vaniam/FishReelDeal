#!/usr/bin/env python3
"""Generate the Reel Deal hooked-fish pack files for one or more fish.

Usage:  python make_hooked_fish.py <pack_root> <fish_name> [<fish_name> ...]
        python make_hooked_fish.py <pack_root> --all                 regenerate every fish already in the pack
        python make_hooked_fish.py <pack_root> --all --hands-only    only rewrite the hand/ground values,
                                                                     keeping your inventory/item-frame edits
Example: python make_hooked_fish.py ./MyPack squid amberjack

To change how fish are held, edit the HAND_* lines in display() below, then run with --all --hands-only.

For each fish it writes (under <pack_root>/assets/reel/):
  items/hooked/<name>.json          item definition: picks a model by size step (custom_model_data float #2)
  models/hooked/<name>.json         base/fallback model
  models/hooked/<name>_s0..s9.json  10 size steps, each a slightly different display scale

The 3D model itself must already exist at
  assets/fathoms/models/block/chopping_block/fish/<name>.json   (change MODEL_PREFIX below if yours differs)

Step lengths (cm) must match FishSize.STEP_MAX in the plugin.
"""
import json
import os
import sys

MODEL_PREFIX = "fathoms:block/chopping_block/fish"
STEP_LOW = [3, 9, 16, 26, 39, 53, 69, 86, 105, 126]
STEP_HIGH = [8, 15, 25, 38, 52, 68, 85, 104, 125, 150]
MIN_CM, MAX_CM, MIN_SCALE, MAX_SCALE = 3, 150, 0.25, 5.0


def scale_at(cm):
    return MIN_SCALE + (cm - MIN_CM) / (MAX_CM - MIN_CM) * (MAX_SCALE - MIN_SCALE)


def display(step):
    s = scale_at((STEP_LOW[step] + STEP_HIGH[step]) / 2)
    hand = s ** 0.6                      # hands + ground: grows, but softened
    fixed = min(s ** 0.5, 1.7)           # item frame: capped so it stays near the frame
    gui = max(0.6, min(s ** 0.3, 1.2))   # inventory slot

    def v(rot, tr, sc):
        return {"rotation": rot, "translation": tr, "scale": [round(sc, 3)] * 3}

    # ---- HAND settings: [rotation x,y,z], [translation x,y,z in 1/16 block], base scale ----
    HAND_FIRST_RIGHT = ([0, 0, 0], [-9, 6.5, 0], 0.9)
    HAND_FIRST_LEFT = ([0, 0, 0], [9, 6.5, 0], 0.9)
    HAND_THIRD_RIGHT = ([0, 0, 0], [-6, 4.25, 0.25], 0.9)
    HAND_THIRD_LEFT = ([0, 0, 0], [6, 4.25, 0.25], 0.9)
    GROUND = ([0, 0, 0], [0, 2, 0], 0.4)
    # ------------------------------------------------------------------------------------
    def h(cfg):
        return v(cfg[0], cfg[1], cfg[2] * hand)

    return {
        "gui": v([30, 225, 0], [0, 0, 0], 0.55 * gui),
        "ground": h(GROUND),
        "fixed": v([0, 0, 0], [0, 0, 0], 0.6 * fixed),
        "thirdperson_righthand": h(HAND_THIRD_RIGHT),
        "thirdperson_lefthand": h(HAND_THIRD_LEFT),
        "firstperson_righthand": h(HAND_FIRST_RIGHT),
        "firstperson_lefthand": h(HAND_FIRST_LEFT),
    }


def write(path, data, indent=2):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(data, f, indent=indent)


HAND_KEYS = ("ground", "thirdperson_righthand", "thirdperson_lefthand",
             "firstperson_righthand", "firstperson_lefthand")


def update_hands(root, name):
    """Rewrite only the hand/ground entries of an existing fish's models, keeping everything else."""
    models = os.path.join(root, "assets/reel/models/hooked")
    for suffix, step in [("", 3)] + [(f"_s{i}", i) for i in range(10)]:
        path = f"{models}/{name}{suffix}.json"
        if not os.path.exists(path):
            continue
        with open(path) as f:
            data = json.load(f)
        fresh = display(step)
        for k in HAND_KEYS:
            data.setdefault("display", {})[k] = fresh[k]
        write(path, data)


def build(root, name):
    parent = f"{MODEL_PREFIX}/{name}"
    models = os.path.join(root, "assets/reel/models/hooked")
    write(f"{models}/{name}.json", {"parent": parent, "display": display(3)})          # base ~ 26-38 cm
    for i in range(10):
        write(f"{models}/{name}_s{i}.json", {"parent": parent, "display": display(i)})
    write(os.path.join(root, f"assets/reel/items/hooked/{name}.json"), {"model": {
        "type": "minecraft:range_dispatch", "property": "minecraft:custom_model_data", "index": 1,
        "fallback": {"type": "minecraft:model", "model": f"reel:hooked/{name}"},
        "entries": [{"threshold": i, "model": {"type": "minecraft:model", "model": f"reel:hooked/{name}_s{i}"}}
                    for i in range(10)]}})


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = {a for a in sys.argv[1:] if a.startswith("--")}
    if not args or (len(args) < 2 and "--all" not in flags):
        sys.exit(__doc__)
    root = args[0]
    if "--all" in flags:
        items = os.path.join(root, "assets/reel/items/hooked")
        names = sorted(f[:-5] for f in os.listdir(items) if f.endswith(".json"))
    else:
        names = [n.strip().lower() for n in args[1:]]
    for n in names:
        if "--hands-only" in flags:
            update_hands(root, n)
        else:
            build(root, n)
        print("updated" if "--hands-only" in flags else "generated", n)
