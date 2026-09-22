#!/usr/bin/env python3
"""Builds the blocky stand-in model and palette texture for sample_walker (Tier 3).

Pivot positions in sample_walker.json were chosen against THIS geometry - if a box moves here,
the corresponding pivot_x/y/z in the JSON has to follow. Shared machinery: tools/robotgen.py.
"""
from robotgen import ROOT, Obj, make_texture

OBJ_PATH = ROOT / "models/obj/sample_walker.obj"
PNG_PATH = ROOT / "textures/vehicle/sample_walker.png"


m = Obj("Sample Walker")

# --- Fixed body (no group name needed, but one keeps the file readable) ----------------------
m.box("body", "hull",  0.0, 2.55, 0.00, 1.30, 1.30, 0.80)   # torso
m.box("body", "panel", 0.0, 2.65, 0.42, 0.90, 0.80, 0.08)   # chest plate
m.box("body", "accent", 0.0, 2.95, 0.46, 0.30, 0.15, 0.06)  # chest marking
m.box("body", "joint", 0.0, 1.85, 0.00, 1.00, 0.40, 0.70)   # pelvis
m.box("body", "hull",  0.85, 2.95, 0.00, 0.50, 0.45, 0.60)  # right shoulder block
m.box("body", "hull", -0.85, 2.95, 0.00, 0.50, 0.45, 0.60)  # left shoulder block
m.box("body", "joint", 0.0, 3.13, 0.00, 0.30, 0.15, 0.30)   # neck

# --- Head: pivot (0, 3.2, 0), tracks the pilot's view -----------------------------------------
m.box("$head", "panel", 0.0, 3.55, 0.05, 0.70, 0.70, 0.70)
m.box("$head", "visor", 0.0, 3.60, 0.41, 0.50, 0.18, 0.04)
m.box("$head", "accent", 0.0, 3.95, 0.05, 0.10, 0.20, 0.10)  # antenna

# --- Right arm + rifle: shoulder pivot (1.15, 2.85, 0) is the AIMING weapon part ($arm_r: shoulder
# + upper arm). The forearm + rifle is a JOINT ($forearm_r) hung off that weapon part at the elbow
# (1.15, 2.30, 0): modelled in the aiming pose (forearm forward) = ready_angle 0; lowered it bends
# down. The weapon offset (1.15, 2.30, 0.90) still sits at the muzzle in the aiming pose.
m.box("$arm_r",     "joint", 1.15, 2.85, 0.00, 0.40, 0.40, 0.40)   # shoulder joint
m.box("$arm_r",     "hull",  1.15, 2.50, 0.00, 0.36, 0.60, 0.36)   # upper arm
m.box("$forearm_r", "joint", 1.15, 2.30, 0.00, 0.38, 0.30, 0.38)   # elbow
m.box("$forearm_r", "hull",  1.15, 2.30, 0.35, 0.34, 0.34, 0.90)   # forearm, forward
m.box("$forearm_r", "gun",   1.15, 2.30, 0.80, 0.18, 0.22, 1.10)   # rifle body
m.box("$forearm_r", "gun",   1.15, 2.30, 1.30, 0.10, 0.10, 0.30)   # barrel tip -> muzzle ~z 1.45

# --- Left arm: two JOINTS - upper arm (shoulder pivot -1.15, 2.85, 0) and forearm (elbow pivot
# -1.15, 2.20, 0, child of the upper arm). Hangs down and swings with the gait. ----------------
m.box("$upperarm_l", "joint", -1.15, 2.85, 0.00, 0.40, 0.40, 0.40)   # shoulder joint
m.box("$upperarm_l", "hull",  -1.15, 2.52, 0.00, 0.36, 0.64, 0.36)   # upper arm
m.box("$forearm_l",  "joint", -1.15, 2.20, 0.00, 0.34, 0.22, 0.34)   # elbow
m.box("$forearm_l",  "hull",  -1.15, 1.90, 0.00, 0.34, 0.50, 0.34)   # forearm
m.box("$forearm_l",  "joint", -1.15, 1.58, 0.00, 0.30, 0.20, 0.30)   # fist

# --- LEFT shoulder (+X) multi-lock missile pod: pivot (0.95, 3.05, 0). (Facing +Z, +X is the robot's
# own left; the older pod below, at -X, is on its RIGHT shoulder despite its comment's wording.) --
m.box("$shoulder_pod_l", "pod", 0.95, 3.32, 0.05, 0.55, 0.40, 0.90)
m.box("$shoulder_pod_l", "accent", 0.95, 3.32, 0.51, 0.45, 0.30, 0.04)
for x in (0.80, 0.95, 1.10):
    m.box("$shoulder_pod_l", "joint", x, 3.42, 0.52, 0.08, 0.08, 0.02)  # cell caps

# --- RIGHT shoulder (-X) missile pod: pivot (-0.95, 3.05, 0) ----------------------------------------
m.box("$shoulder_pod", "pod",   -0.95, 3.32, 0.10, 0.50, 0.32, 0.80)
m.box("$shoulder_pod", "joint", -0.95, 3.32, 0.52, 0.42, 0.24, 0.06)  # front cover
m.box("$shoulder_pod", "accent", -0.95, 3.50, 0.10, 0.12, 0.06, 0.60)

# --- Legs: three JOINTED segments each (humanoid_robot.joints) - thigh (hip pivot y=1.90),
# shin (knee pivot y=0.68, child of thigh), foot (ankle pivot y=0.25, child of shin). -----------
for side, x in (("r", 0.5), ("l", -0.5)):
    m.box(f"$thigh_{side}", "joint", x, 1.90, 0.00, 0.50, 0.30, 0.50)   # hip joint
    m.box(f"$thigh_{side}", "hull",  x, 1.25, 0.00, 0.44, 1.10, 0.56)   # thigh
    m.box(f"$shin_{side}",  "joint", x, 0.68, 0.02, 0.40, 0.24, 0.40)   # knee
    m.box(f"$shin_{side}",  "panel", x, 0.42, 0.00, 0.42, 0.50, 0.50)   # shin
    m.box(f"$foot_{side}",  "foot",  x, 0.10, 0.15, 0.52, 0.20, 0.90)   # foot

# --- Back boosters: MODE-DRIVEN toggle parts (humanoid_robot.mode_visuals.parts). Hinged at the top
# of the back, pivot (+-0.40, 3.05, -0.55); modelled in the CLOSED pose, hanging flat down the back.
# Rotating +90 about +X swings them up and out to point straight backward (nozzles to the rear). ----
for name, x in (("$booster_r", 0.40), ("$booster_l", -0.40)):
    m.box(name, "hull",   x, 2.55, -0.58, 0.34, 1.00, 0.30)   # booster body
    m.box(name, "joint",  x, 3.02, -0.58, 0.40, 0.12, 0.34)   # hinge collar
    m.box(name, "accent", x, 1.98, -0.58, 0.28, 0.14, 0.24)   # nozzle

# --- Dorsal fin: MODE-DRIVEN slide part. Retracted inside the torso when closed, slides up 0.45
# in flight. ------------------------------------------------------------------------------------
m.box("$fin", "accent", 0.0, 3.00, -0.30, 0.10, 0.50, 0.30)

m.write(OBJ_PATH)
make_texture(PNG_PATH)
