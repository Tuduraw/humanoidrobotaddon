#!/usr/bin/env python3
"""Builds the stand-in models and palette textures for every robot except sample_walker (which has
its own tools/make_sample_walker.py), plus the projectile models the weapons use.

  Tier 1  spider_light_tank   four legs, turret + cannon
  Tier 2  spider_heavy_tank   larger four legs, twin heavy cannon, tracking-missile rack
  Tier 4  tread_walker        humanoid upper body on a tracked lower unit that follows terrain
  Tier 5  quad_flyer          four legs that spread flat to hover in GLIDE / FLIGHT
  Tier 5  transformer_jet     humanoid with backpack wings (flight_style aircraft)
  Tier 5  variable_fighter    humanoid whose FLIGHT form is a fighter jet, with landing gear
  bullets bullet_shell, bullet_heavy_shell, bullet_missile  (ModelBullet = shell / heavy_shell / missile)

Pivots in each vehicle JSON were chosen against THIS geometry - move a box here, move its pivot there.
Conventions (see tools/robotgen.py): +Z forward, +Y up, y=0 ground, one unit = one block. Facing
+Z, the robot's own LEFT is +X and its RIGHT is -X (Minecraft: facing south, east is on the left).
Note sample_walker's historical "$arm_r" name sits at +X - the robot's own left arm.
"""
from robotgen import ROOT, Obj, make_texture, wheel_x

SCRIPT = "tools/make_vehicles.py"


def out(name):
    return ROOT / f"models/obj/{name}.obj", ROOT / f"textures/vehicle/{name}.png"


# ------------------------------------------------------------------------------------------------
# Four-legged spider tank legs (shared by Tier 1 and Tier 2). Each leg is two JOINTS:
#   $leg_<id>_upper  hip, axis Y (swings the leg fore/aft)        pivot (hip_x, hip_y, z)
#   $leg_<id>_lower  knee, child of upper, axis Z (lifts the foot) pivot (knee_x, knee_y, z)
# ids: fr, fl, br, bl (front/back, right/left).
# ------------------------------------------------------------------------------------------------
def spider_legs(m, hip_x, hip_y, knee_x, knee_y, foot_x, z, thick):
    for fb, zz in (("f", z), ("b", -z)):
        for side, s in (("r", 1), ("l", -1)):
            up = f"$leg_{fb}{side}_upper"
            lo = f"$leg_{fb}{side}_lower"
            m.box(up, "joint", s * hip_x, hip_y, zz, thick * 1.5, thick * 1.5, thick * 1.5)
            m.box(up, "hull", s * (hip_x + knee_x) / 2, (hip_y + knee_y) / 2, zz,
                  abs(knee_x - hip_x) + thick, thick, thick)
            m.box(lo, "joint", s * knee_x, knee_y, zz, thick * 1.3, thick * 1.3, thick * 1.3)
            m.box(lo, "panel", s * foot_x, knee_y / 2, zz, thick * 0.85, knee_y, thick * 0.85)
            m.box(lo, "foot", s * foot_x, 0.08, zz, thick * 1.6, 0.16, thick * 1.6)


# ------------------------------------------------------------------------------------------------
# Tier 1: spider_light_tank
# ------------------------------------------------------------------------------------------------
m = Obj("Spider Light Tank", SCRIPT)
m.box("body", "hull", 0.0, 1.55, 0.0, 2.0, 0.60, 2.60)          # hull
m.box("body", "panel", 0.0, 1.90, -0.60, 1.40, 0.12, 1.00)      # engine deck
m.box("body", "visor", 0.0, 1.62, 1.31, 1.20, 0.18, 0.04)       # front sensor strip
spider_legs(m, hip_x=1.0, hip_y=1.55, knee_x=1.75, knee_y=1.70, foot_x=1.85, z=1.05, thick=0.28)
# Turret: yaw about (0, 1.85, 0). Cannon: pitch about (0, 2.15, 0.55), riding the turret.
m.box("$turret", "hull", 0.0, 2.10, -0.10, 1.20, 0.50, 1.30)
m.box("$turret", "panel", 0.0, 2.38, -0.30, 0.50, 0.10, 0.50)   # hatch
m.box("$cannon", "joint", 0.0, 2.15, 0.62, 0.40, 0.34, 0.24)    # mantlet
m.box("$cannon", "gun", 0.0, 2.15, 1.40, 0.18, 0.18, 1.60)      # barrel -> muzzle z ~2.20
m.write(out("spider_light_tank")[0])
make_texture(out("spider_light_tank")[1], {"hull": (110, 118, 80), "panel": (150, 150, 110)})

# ------------------------------------------------------------------------------------------------
# Tier 2: spider_heavy_tank
# ------------------------------------------------------------------------------------------------
m = Obj("Spider Heavy Tank", SCRIPT)
m.box("body", "hull", 0.0, 2.05, 0.0, 2.80, 0.90, 3.40)
m.box("body", "panel", 0.0, 2.55, -0.20, 2.20, 0.14, 2.60)      # armour deck
m.box("body", "visor", 0.0, 2.15, 1.71, 1.80, 0.20, 0.04)
spider_legs(m, hip_x=1.40, hip_y=2.00, knee_x=2.40, knee_y=2.25, foot_x=2.55, z=1.45, thick=0.38)
# Turret: yaw about (0, 2.60, -0.10). Twin cannon: pitch about (0, 2.95, 0.75).
m.box("$turret", "hull", 0.0, 2.90, -0.20, 1.80, 0.60, 1.80)
m.box("$turret", "panel", 0.0, 3.24, -0.40, 0.70, 0.10, 0.70)
m.box("$cannon", "joint", 0.0, 2.95, 0.82, 1.10, 0.50, 0.34)    # mantlet
for x in (0.35, -0.35):
    m.box("$cannon", "gun", x, 2.95, 1.95, 0.24, 0.24, 2.20)    # barrels -> muzzles z ~3.05
    m.box("$cannon", "gun", x, 2.95, 3.00, 0.32, 0.32, 0.18)    # muzzle brakes
# Tracking-missile rack at the hull's rear: yaw+pitch about (0, 2.70, -1.30).
m.box("$missile_rack", "pod", 0.0, 2.95, -1.30, 1.30, 0.50, 0.80)
for x in (-0.45, -0.15, 0.15, 0.45):
    m.box("$missile_rack", "accent", x, 2.95, -0.88, 0.20, 0.20, 0.06)  # tube caps
m.write(out("spider_heavy_tank")[0])
make_texture(out("spider_heavy_tank")[1], {"hull": (70, 78, 64), "panel": (110, 118, 96), "accent": (220, 160, 40)})

# ------------------------------------------------------------------------------------------------
# Humanoid upper body shared by Tier 4 and Tier 5 (sample_walker's layout, offset by dy/dz):
#   torso + $head; rifle arm at +X ($arm_r weapon part + $forearm_r elbow joint, as sample_walker);
#   plain arm at -X ($upperarm_l / $forearm_l joints); LEFT (+X) shoulder missile pod $shoulder_pod_l.
# ------------------------------------------------------------------------------------------------
def humanoid_upper(m, dy=0.0, dz=0.0):
    m.box("body", "hull", 0.0, 2.55 + dy, 0.00 + dz, 1.30, 1.30, 0.80)      # torso
    m.box("body", "panel", 0.0, 2.65 + dy, 0.41 + dz, 0.90, 0.70, 0.04)     # chest plate
    m.box("$head", "hull", 0.0, 3.45 + dy, 0.00 + dz, 0.60, 0.50, 0.60)
    m.box("$head", "visor", 0.0, 3.47 + dy, 0.31 + dz, 0.45, 0.14, 0.04)
    m.box("$arm_r", "joint", 1.15, 2.85 + dy, 0.00 + dz, 0.40, 0.40, 0.40)
    m.box("$arm_r", "hull", 1.15, 2.50 + dy, 0.00 + dz, 0.36, 0.60, 0.36)
    m.box("$forearm_r", "joint", 1.15, 2.30 + dy, 0.00 + dz, 0.38, 0.30, 0.38)
    m.box("$forearm_r", "hull", 1.15, 2.30 + dy, 0.35 + dz, 0.34, 0.34, 0.90)
    m.box("$forearm_r", "gun", 1.15, 2.30 + dy, 0.80 + dz, 0.18, 0.22, 1.10)
    m.box("$forearm_r", "gun", 1.15, 2.30 + dy, 1.30 + dz, 0.10, 0.10, 0.30)   # muzzle ~z 1.45
    m.box("$upperarm_l", "joint", -1.15, 2.85 + dy, 0.00 + dz, 0.40, 0.40, 0.40)
    m.box("$upperarm_l", "hull", -1.15, 2.52 + dy, 0.00 + dz, 0.36, 0.64, 0.36)
    m.box("$forearm_l", "joint", -1.15, 2.20 + dy, 0.00 + dz, 0.34, 0.22, 0.34)
    m.box("$forearm_l", "hull", -1.15, 1.90 + dy, 0.00 + dz, 0.34, 0.50, 0.34)
    m.box("$forearm_l", "joint", -1.15, 1.58 + dy, 0.00 + dz, 0.30, 0.20, 0.30)
    # LEFT shoulder (+X) missile pod: yaw+pitch about (0.95, 3.05 + dy, dz).
    m.box("$shoulder_pod_l", "pod", 0.95, 3.32 + dy, 0.05 + dz, 0.55, 0.40, 0.90)
    m.box("$shoulder_pod_l", "accent", 0.95, 3.32 + dy, 0.51 + dz, 0.45, 0.30, 0.04)
    for x in (0.80, 0.95, 1.10):
        m.box("$shoulder_pod_l", "joint", x, 3.42 + dy, 0.52 + dz, 0.08, 0.08, 0.02)  # cell caps


# ------------------------------------------------------------------------------------------------
# Tier 4: tread_walker - humanoid upper body on a tracked lower unit.
# Lower unit joints: $tread_pitch (virtual, no geometry, terrain pitch about (0, 0.45, 0))
#                    -> $treads (terrain roll about the same point; belts, chassis, waist)
#                    -> $roadwheel_<side><n> (spin about X at each wheel centre)
# RIGHT shoulder (-X) backpack cannon, modelled DEPLOYED (barrel forward over the shoulder); pivot
# (-0.62, 3.30, -0.35). Stowed = pitch -90 (barrel straight up behind the shoulder).
# ------------------------------------------------------------------------------------------------
m = Obj("Tread Walker", SCRIPT)
humanoid_upper(m)
m.box("body", "joint", 0.0, 1.80, 0.00, 0.70, 0.40, 0.50)      # hips
m.box("$backpack_cannon", "joint", -0.62, 3.30, -0.35, 0.50, 0.50, 0.50)
m.box("$backpack_cannon", "hull", -0.62, 3.45, -0.05, 0.46, 0.46, 0.80)
m.box("$backpack_cannon", "gun", -0.62, 3.45, 1.10, 0.26, 0.26, 1.60)       # barrel -> z ~1.90
m.box("$backpack_cannon", "gun", -0.62, 3.45, 1.95, 0.34, 0.34, 0.16)
# Tracked lower unit ($treads): belts, chassis, waist column up to the hips.
for x in (0.75, -0.75):
    m.box("$treads", "joint", x, 0.45, 0.0, 0.50, 0.90, 2.60)     # track belt
    m.box("$treads", "foot", x, 0.45, 1.30, 0.46, 0.70, 0.12)     # belt front curve
    m.box("$treads", "foot", x, 0.45, -1.30, 0.46, 0.70, 0.12)    # belt rear curve
m.box("$treads", "hull", 0.0, 0.95, 0.0, 1.10, 0.45, 1.90)        # chassis
m.box("$treads", "panel", 0.0, 1.35, 0.0, 0.70, 0.40, 0.70)       # waist column
for side, x in (("r", 1.03), ("l", -1.03)):
    for n, z in enumerate((-0.90, -0.30, 0.30, 0.90)):
        wheel_x(m, f"$roadwheel_{side}{n}", "gun", x, 0.30, z, 0.08, 0.26)
m.write(out("tread_walker")[0])
make_texture(out("tread_walker")[1], {"hull": (120, 96, 70), "panel": (170, 150, 120), "pod": (90, 90, 70)})

# ------------------------------------------------------------------------------------------------
# Tier 5: quad_flyer - humanoid upper body (raised 0.45, forward 0.35) on a four-legged base.
# Each leg: $spread_<id> (virtual, axis Z: spreads the leg flat in the air)
#   -> $thigh_<id> (axis X: walking swing) -> $shin_<id> (axis X: knee). ids fr/fl/br/bl.
# Hip pivots (+-0.85, 1.70, +-0.95); knees at y 0.75.
# ------------------------------------------------------------------------------------------------
m = Obj("Quad Flyer", SCRIPT)
m.box("body", "hull", 0.0, 1.95, 0.0, 1.60, 0.70, 2.20)
m.box("body", "panel", 0.0, 2.34, -0.55, 1.10, 0.10, 1.00)
m.box("body", "accent", 0.0, 2.45, -1.00, 0.20, 0.35, 0.40)      # tail fin
humanoid_upper(m, dy=0.45, dz=0.35)
for fb, z in (("f", 0.95), ("b", -0.95)):
    for side, x in (("r", 0.85), ("l", -0.85)):
        t = f"$thigh_{fb}{side}"
        sh = f"$shin_{fb}{side}"
        m.box(t, "joint", x, 1.70, z, 0.36, 0.36, 0.36)
        m.box(t, "hull", x, 1.22, z, 0.28, 0.90, 0.28)
        m.box(sh, "joint", x, 0.75, z, 0.30, 0.24, 0.30)
        m.box(sh, "panel", x, 0.42, z, 0.24, 0.62, 0.24)
        m.box(sh, "foot", x, 0.08, z + 0.05, 0.34, 0.16, 0.44)
# Belly thrusters: MODE-DRIVEN slide part, drop 0.25 out of the hull in GLIDE / FLIGHT.
for x in (0.45, -0.45):
    m.box("$thrusters", "accent", x, 1.62, -0.40, 0.30, 0.20, 0.30)
m.write(out("quad_flyer")[0])
make_texture(out("quad_flyer")[1], {"hull": (60, 70, 96), "panel": (130, 150, 190), "accent": (240, 180, 60)})

# ------------------------------------------------------------------------------------------------
# Tier 5 (transforming_robot): transformer_jet - a humanoid that becomes a fighter in FLIGHT.
# Humanoid upper body + sample_walker-style jointed legs ($thigh_/$shin_/$foot_, hip y 1.90,
# knee 0.68, ankle 0.25). FLIGHT leans the whole body 90 degrees (head = nose, feet = tail) and:
#   $wing_l / $wing_r  MODE-DRIVEN toggle parts, flat plates folded down the back, pivots
#                      (+-0.30, 3.00, -0.44); rotating +-90 about Z swings them out sideways, so after
#                      the lean they lie flat as wings.
#   $vulcans           twin barrels beside the head pointing along +Y (the nose once flying) - fixed,
#                      flight-only guns (muzzles ~ (+-0.2, 4.10, 0.10)).
# ------------------------------------------------------------------------------------------------
m = Obj("Transformer Jet", SCRIPT)
humanoid_upper(m)
m.box("body", "joint", 0.0, 1.80, 0.00, 0.70, 0.40, 0.50)      # hips
for side, x in (("r", 0.5), ("l", -0.5)):
    m.box(f"$thigh_{side}", "joint", x, 1.90, 0.00, 0.50, 0.30, 0.50)
    m.box(f"$thigh_{side}", "hull", x, 1.25, 0.00, 0.44, 1.10, 0.56)
    m.box(f"$shin_{side}", "joint", x, 0.68, 0.02, 0.40, 0.24, 0.40)
    m.box(f"$shin_{side}", "panel", x, 0.42, 0.00, 0.42, 0.50, 0.50)
    m.box(f"$foot_{side}", "foot", x, 0.10, 0.15, 0.52, 0.20, 0.90)
    m.box(f"$foot_{side}", "accent", x, 0.10, -0.32, 0.30, 0.14, 0.06)   # heel thruster
for name, x in (("$wing_l", 0.30), ("$wing_r", -0.30)):
    m.box(name, "panel", x, 2.30, -0.44, 0.50, 1.40, 0.06)       # folded wing plate
    m.box(name, "accent", x, 1.62, -0.44, 0.50, 0.08, 0.07)      # wing tip stripe
    m.box(name, "joint", x, 3.00, -0.44, 0.14, 0.14, 0.12)       # hinge
for x in (0.20, -0.20):
    m.box("$vulcans", "gun", x, 3.88, 0.10, 0.07, 0.44, 0.07)
m.write(out("transformer_jet")[0])
make_texture(out("transformer_jet")[1], {"hull": (200, 204, 212), "panel": (60, 90, 170), "accent": (220, 60, 50)})

# ------------------------------------------------------------------------------------------------
# Tier 5: variable_fighter - a humanoid whose FLIGHT form reads as a fighter jet. FLIGHT leans the
# body 90 degrees about y 1.5 (head -> nose, feet -> tail, chest -> belly, back -> top) and:
#   $nose          MODE slide part: a nose/cockpit section stowed as a backpack spire, sliding +1.3 Y
#                  in FLIGHT so it juts out ahead of the head (canopy on its -Z face = top).
#   $wing_l/_r     MODE rotate parts: broad plates folded down the back, pivots (+-0.35, 2.90, -0.50),
#                  swinging +-90 about Z to become the wings (X size = chord once swung out).
#   legs           engines: tail fins on the calves (-Z = up when flying); the feet pitch 90 in
#                  FLIGHT (joint flight_pose) so the soles face aft as nozzles.
#   $gear_nose, $gear_l, $gear_r  landing_gear toggle parts, modelled DEPLOYED (struts along +Z =
#                  down when flying); stowed = 90 about X (folded flat along the chest / hips).
# ------------------------------------------------------------------------------------------------
m = Obj("Variable Fighter", SCRIPT)
humanoid_upper(m)
m.box("body", "joint", 0.0, 1.80, 0.00, 0.70, 0.40, 0.50)      # hips
for side, x in (("r", 0.5), ("l", -0.5)):
    m.box(f"$thigh_{side}", "joint", x, 1.90, 0.00, 0.50, 0.30, 0.50)
    m.box(f"$thigh_{side}", "hull", x, 1.25, 0.00, 0.48, 1.10, 0.60)     # engine nacelle
    m.box(f"$shin_{side}", "joint", x, 0.68, 0.02, 0.44, 0.24, 0.44)
    m.box(f"$shin_{side}", "panel", x, 0.42, 0.00, 0.46, 0.50, 0.54)
    m.box(f"$shin_{side}", "accent", x, 0.50, -0.50, 0.05, 0.70, 0.55)  # tail fin
    m.box(f"$foot_{side}", "foot", x, 0.10, 0.15, 0.52, 0.20, 0.90)
    m.box(f"$foot_{side}", "visor", x, 0.02, 0.15, 0.36, 0.05, 0.60)    # nozzle glow (sole)
m.box("$nose", "hull", 0.0, 3.15, -0.62, 0.50, 1.50, 0.45)       # nose section
m.box("$nose", "hull", 0.0, 4.00, -0.62, 0.30, 0.30, 0.30)       # nose cone
m.box("$nose", "visor", 0.0, 3.40, -0.86, 0.30, 0.70, 0.05)      # canopy
for name, x in (("$wing_l", 0.35), ("$wing_r", -0.35)):
    m.box(name, "panel", x, 2.05, -0.50, 0.90, 1.70, 0.05)       # wing (folded)
    m.box(name, "accent", x, 1.18, -0.50, 0.60, 0.10, 0.06)      # wing tip stripe
    m.box(name, "joint", x, 2.90, -0.50, 0.16, 0.16, 0.12)       # hinge
m.box("$gear_nose", "joint", 0.0, 3.00, 0.92, 0.10, 0.10, 1.00)  # nose strut
m.box("$gear_nose", "foot", 0.0, 3.00, 1.42, 0.12, 0.25, 0.25)   # nose wheel
for name, x in (("$gear_l", 0.45), ("$gear_r", -0.45)):
    m.box(name, "joint", x, 1.95, 0.85, 0.12, 0.12, 1.00)        # main strut
    m.box(name, "foot", x, 1.95, 1.35, 0.14, 0.30, 0.30)         # main wheel
m.write(out("variable_fighter")[0])
make_texture(out("variable_fighter")[1], {"hull": (225, 225, 220), "panel": (70, 80, 90), "accent": (230, 170, 40), "visor": (120, 200, 255)})

# ------------------------------------------------------------------------------------------------
# Projectiles (ModelBullet = <name> -> models/obj/bullet_<name>.obj + textures/vehicle/bullet_<name>.png).
# Drawn nose-forward along +Z, centred on the projectile's own position.
# ------------------------------------------------------------------------------------------------
m = Obj("Bullet: shell", SCRIPT)
m.box("body", "gun", 0.0, 0.0, 0.0, 0.10, 0.10, 0.30)
m.box("body", "accent", 0.0, 0.0, 0.18, 0.07, 0.07, 0.06)       # tip
m.write(out("bullet_shell")[0])
make_texture(out("bullet_shell")[1], {"gun": (190, 150, 60), "accent": (230, 210, 120)})

m = Obj("Bullet: heavy shell", SCRIPT)
m.box("body", "gun", 0.0, 0.0, 0.0, 0.20, 0.20, 0.55)
m.box("body", "accent", 0.0, 0.0, 0.32, 0.14, 0.14, 0.10)
m.box("body", "joint", 0.0, 0.0, -0.26, 0.22, 0.22, 0.05)       # driving band
m.write(out("bullet_heavy_shell")[0])
make_texture(out("bullet_heavy_shell")[1], {"gun": (150, 120, 60), "accent": (200, 70, 40)})

m = Obj("Bullet: missile", SCRIPT)
m.box("body", "panel", 0.0, 0.0, 0.0, 0.16, 0.16, 0.80)         # airframe
m.box("body", "accent", 0.0, 0.0, 0.45, 0.11, 0.11, 0.10)       # seeker head
m.box("body", "joint", 0.0, 0.0, -0.32, 0.46, 0.04, 0.14)       # tail fins
m.box("body", "joint", 0.0, 0.0, -0.32, 0.04, 0.46, 0.14)
m.box("body", "visor", 0.0, 0.0, -0.43, 0.10, 0.10, 0.04)       # exhaust glow
m.write(out("bullet_missile")[0])
make_texture(out("bullet_missile")[1], {"panel": (215, 215, 205), "accent": (200, 60, 40), "visor": (255, 190, 80)})
