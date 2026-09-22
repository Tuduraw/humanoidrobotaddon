package com.example.humanoidrobotaddon.client.mixin;

import com.example.humanoidrobotaddon.client.HeadLookOffsetState;
import com.example.humanoidrobotaddon.entity.HumanoidRobotEntity;
import com.example.tudursvehiclemod.client.AircraftCameraZoomState;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Camera for a HumanoidRobotEntity in FLIGHT (flight_style "robot"): free mouse-look AND smooth
 * body-following at once, which neither branch of the base mod's own CameraMixin gives (locked =
 * follows but no mouse; free-look = mouse but no yaw/pitch following). Recomputed every render
 * frame as body orientation x head-look offset, both as quaternions - no per-tick carry-over
 * (visible stepping at flight rotation rates) and no Euler round-trip.
 *
 * <p>Priority above the default 1000 so it runs after the base mod's CameraMixin at the same TAIL
 * and its write is the one that sticks. Stands aside for the "aircraft" scheme, ground modes and
 * every other vehicle. See DEVELOPMENT_NOTES.md. */
@Mixin(value = Camera.class, priority = 2000)
public abstract class RobotCameraMixin {

	@Invoker("setPos")
	public abstract void tudursvehiclemod$setPos(Vec3d pos);

	@Inject(method = "update(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;ZZF)V", at = @At("TAIL"))
	private void tudursvehiclemod$applyRobotFlightCamera(World area, Entity focusedEntity, boolean thirdPerson,
														  boolean inverseView, float tickProgress, CallbackInfo ci) {
		if (!(focusedEntity instanceof PlayerEntity player)) {
			return;
		}
		if (!(player.getVehicle() instanceof HumanoidRobotEntity robot)) {
			return;
		}
		if (!robot.tudursvehiclemod$getMovementMode().flies()) {
			return;
		}
		if (robot.tudursvehiclemod$usesAircraftFlightControls()) {
			return; // aircraft-style: the base mod's own CameraMixin (locked / free-look) drives it
		}

		Camera self = (Camera) (Object) this;
		Vec3d eyePos = robot.getRotatedEyePos(player, tickProgress);

		// The base mod's own camera-space construction for a locked FreeCameraVehicle, with the
		// head-look composed on top as a local rotation.
		float yaw = robot.getYaw(tickProgress);
		float pitch = robot.getPitch(tickProgress);
		float roll = robot.getRoll(tickProgress);

		Quaternionf fresh = new Quaternionf();
		fresh.rotateY((float) Math.toRadians(180.0 - yaw));
		fresh.rotateX((float) Math.toRadians(-pitch));
		fresh.rotateZ((float) Math.toRadians(-roll));
		if (inverseView) {
			fresh.rotateY((float) Math.PI);
		}
		// Built fresh from two independent scalars (Ry then Rx) - accumulating a quaternion drifts
		// into roll.
		Quaternionf headOffset = new Quaternionf()
				.rotationY((float) Math.toRadians(HeadLookOffsetState.yaw))
				.rotateX((float) Math.toRadians(HeadLookOffsetState.pitch));
		fresh.mul(headOffset);
		self.getRotation().set(fresh);

		if (thirdPerson) {
			// Pull direction from the same `fresh` quaternion: local +Z is the vehicle's forward, so
			// this is correct for both the behind and the front (inverseView) stage. Deriving it from a
			// separately-built quaternion diverges once head-look is composed in (non-commutative).
			Vector3f pullDirection = new Vector3f(0, 0, 1);
			fresh.transform(pullDirection);
			this.tudursvehiclemod$setPos(tudursvehiclemod$pullBack(area, player, eyePos, pullDirection));
		} else {
			this.tudursvehiclemod$setPos(eyePos);
		}
	}

	/** Ported from the base mod's own CameraMixin (a private method there, not reachable from this
	 * separate mixin class) - pulls the camera away from eyePos along pullDirection by
	 * AircraftCameraZoomState's own current scroll-wheel zoom distance, clamped by a raycast so it
	 * doesn't clip through terrain. */
	private static Vec3d tudursvehiclemod$pullBack(World area, PlayerEntity player, Vec3d eyePos, Vector3f pullDirection) {
		double desiredDistance = AircraftCameraZoomState.currentDistance;
		Vec3d desiredPos = new Vec3d(
				eyePos.x + pullDirection.x * desiredDistance,
				eyePos.y + pullDirection.y * desiredDistance,
				eyePos.z + pullDirection.z * desiredDistance);

		double actualDistance = desiredDistance;
		RaycastContext raycastContext = new RaycastContext(eyePos, desiredPos,
				RaycastContext.ShapeType.VISUAL, RaycastContext.FluidHandling.NONE, player);
		BlockHitResult hit = area.raycast(raycastContext);
		if (hit.getType() == HitResult.Type.BLOCK) {
			double hitDistance = eyePos.distanceTo(hit.getPos());
			actualDistance = Math.max(0.0, Math.min(desiredDistance, hitDistance - 0.25));
		}

		return new Vec3d(
				eyePos.x + pullDirection.x * actualDistance,
				eyePos.y + pullDirection.y * actualDistance,
				eyePos.z + pullDirection.z * actualDistance);
	}
}
