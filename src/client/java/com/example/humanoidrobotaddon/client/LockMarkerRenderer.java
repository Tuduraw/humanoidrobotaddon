package com.example.humanoidrobotaddon.client;

import com.example.humanoidrobotaddon.entity.HumanoidRobotEntity;
import com.example.tudursvehiclemod.asset.ServerObjModelHitboxes;
import com.example.tudursvehiclemod.asset.VehicleDefinition;
import com.example.tudursvehiclemod.asset.WeaponDefinition;
import com.example.tudursvehiclemod.entity.AbstractVehicleEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.DrawStyle;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.debug.gizmo.GizmoDrawing;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** This addon's own in-world markers: the candidate preview (orange, pilot only) and every locked
 * target (red + the locking weapon names, "name i/n" for multi-lock). Drawn with GizmoDrawing rather
 * than the base mod's shared highlight state, which its own missile lock-on also switches and would
 * fight over the same on/off flag.
 *
 * <p>A vehicle target is outlined by its own mesh (decimated above MAX_TRIANGLES_PER_DRAW), same
 * technique and cap as the base mod's own TargetHighlightRenderer, since a vehicle's hitbox is
 * deliberately larger than its visible model. Anything else uses its bounding box. */
public final class LockMarkerRenderer {

	private LockMarkerRenderer() {
	}

	private static final int LOCK_COLOR = 0xFFFF3030;
	private static final int CANDIDATE_COLOR = 0xFFFF9010;
	private static final float LINE_WIDTH = 2.0f;
	private static final float LABEL_SCALE = 1.0f;
	private static final int MAX_TRIANGLES_PER_DRAW = 400;

	/** Once per client tick: draws the markers for every loaded HumanoidRobotEntity. */
	public static void tick(MinecraftClient client) {
		if (client.world == null) {
			return;
		}

		try (var scope = client.newGizmoScope()) {
			for (Entity entity : client.world.getEntities()) {
				if (!(entity instanceof HumanoidRobotEntity robot)) {
					continue;
				}
				// Candidate preview: this addon's own outline (not the base mod's shared highlight
				// state, which its own missile lock-on also switches). Shown only to the pilot.
				if (robot.getControllingPassenger() == client.player
						&& robot.tudursvehiclemod$settings().highlightTarget()) {
					Entity candidate = robot.tudursvehiclemod$getCandidate();
					if (candidate != null) {
						tudursvehiclemod$outline(candidate, CANDIDATE_COLOR);
					}
				}
				Map<Entity, List<String>> weaponNamesByTarget = tudursvehiclemod$collectLocks(robot);
				for (Map.Entry<Entity, List<String>> entry : weaponNamesByTarget.entrySet()) {
					Entity target = entry.getKey();
					tudursvehiclemod$outline(target, LOCK_COLOR);
					GizmoDrawing.entityLabel(target, 0, String.join(", ", entry.getValue()), LOCK_COLOR, LABEL_SCALE);
				}
			}
		}
	}

	/** One robot's locked entities keyed by target (a target may be locked by several weapons), each
	 * with its weapon labels. weaponName() (the .txt file name), not displayName(), which falls back
	 * to "Weapon" when the file is missing. */
	private static Map<Entity, List<String>> tudursvehiclemod$collectLocks(HumanoidRobotEntity robot) {
		Map<Entity, List<String>> byTarget = new LinkedHashMap<>();
		for (Map.Entry<Integer, List<Integer>> lock : robot.tudursvehiclemod$getAllLockLists().entrySet()) {
			int seatIndex = lock.getKey();
			WeaponDefinition weapon = robot.tudursvehiclemod$weaponForSeat(seatIndex);
			String name = weapon != null ? weapon.weaponName() : ("#" + seatIndex);
			List<Integer> ids = lock.getValue();
			boolean multi = robot.tudursvehiclemod$isMultiLockActive(seatIndex);
			for (int i = 0; i < ids.size(); i++) {
				Entity target = robot.getEntityWorld().getEntityById(ids.get(i));
				if (target == null) {
					continue;
				}
				String label = multi ? name + " " + (i + 1) + "/" + ids.size() : name;
				byTarget.computeIfAbsent(target, t -> new ArrayList<>()).add(label);
			}
		}
		return byTarget;
	}

	/** Mesh outline for a vehicle (its hitbox is oversized for collision, not its visible shape -
	 * matching TargetHighlightRenderer's own reasoning); bounding box for anything else. */
	private static void tudursvehiclemod$outline(Entity target, int color) {
		if (target instanceof AbstractVehicleEntity vehicle) {
			VehicleDefinition def = vehicle.getDefinition();
			Optional<ServerObjModelHitboxes.Mesh> mesh = ServerObjModelHitboxes.getMesh(def.model());
			if (mesh.isPresent()) {
				tudursvehiclemod$drawMesh(vehicle, def, mesh.get(), color);
				return;
			}
		}
		GizmoDrawing.box(target.getBoundingBox(), DrawStyle.stroked(color, LINE_WIDTH));
	}

	private static void tudursvehiclemod$drawMesh(AbstractVehicleEntity vehicle, VehicleDefinition def,
			ServerObjModelHitboxes.Mesh mesh, int color) {
		Quaternionf rotation = vehicle.tudursvehiclemod$getCurrentRotationForRendering();
		float scale = def.scale();
		double x = vehicle.getX();
		double y = vehicle.getY();
		double z = vehicle.getZ();
		for (float[] tri : tudursvehiclemod$sampleTriangles(mesh.triangles())) {
			Vec3d a = tudursvehiclemod$toWorld(tri[0], tri[1], tri[2], rotation, scale, x, y, z);
			Vec3d b = tudursvehiclemod$toWorld(tri[3], tri[4], tri[5], rotation, scale, x, y, z);
			Vec3d c = tudursvehiclemod$toWorld(tri[6], tri[7], tri[8], rotation, scale, x, y, z);
			GizmoDrawing.line(a, b, color, LINE_WIDTH);
			GizmoDrawing.line(b, c, color, LINE_WIDTH);
			GizmoDrawing.line(c, a, color, LINE_WIDTH);
		}
	}

	/** Evenly-strided subsampling above the cap, matching TargetHighlightRenderer's own
	 * MAX_TRIANGLES_PER_DRAW - a coarse outline covering the whole model, not just one end of the
	 * triangle list. */
	private static List<float[]> tudursvehiclemod$sampleTriangles(List<float[]> triangles) {
		if (triangles.size() <= MAX_TRIANGLES_PER_DRAW) {
			return triangles;
		}
		List<float[]> sampled = new ArrayList<>(MAX_TRIANGLES_PER_DRAW);
		float stride = (float) triangles.size() / MAX_TRIANGLES_PER_DRAW;
		for (int i = 0; i < MAX_TRIANGLES_PER_DRAW; i++) {
			sampled.add(triangles.get((int) (i * stride)));
		}
		return sampled;
	}

	private static Vec3d tudursvehiclemod$toWorld(float localX, float localY, float localZ,
			Quaternionf rotation, float scale, double vehicleX, double vehicleY, double vehicleZ) {
		Vector3f local = new Vector3f(localX * scale, localY * scale, localZ * scale);
		rotation.transform(local);
		return new Vec3d(vehicleX + local.x, vehicleY + local.y, vehicleZ + local.z);
	}
}
