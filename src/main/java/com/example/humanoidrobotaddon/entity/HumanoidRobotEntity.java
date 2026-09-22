package com.example.humanoidrobotaddon.entity;

import com.example.humanoidrobotaddon.HumanoidRobotAddon;
import com.example.humanoidrobotaddon.asset.Joint;
import com.example.humanoidrobotaddon.asset.ModeVisualSettings;
import com.example.humanoidrobotaddon.asset.MovementMode;
import com.example.humanoidrobotaddon.asset.MovementSettings;
import com.example.humanoidrobotaddon.asset.RobotSettings;
import com.example.humanoidrobotaddon.asset.RobotSettingsRegistry;
import com.example.humanoidrobotaddon.asset.TerrainFollow;
import com.example.humanoidrobotaddon.asset.TrackingChannel;
import com.example.tudursvehiclemod.asset.TogglePart;
import com.example.tudursvehiclemod.asset.VehicleDefinition;
import com.example.tudursvehiclemod.asset.WeaponAimRange;
import com.example.tudursvehiclemod.asset.WeaponDefinition;
import com.example.tudursvehiclemod.asset.WeaponPart;
import com.example.tudursvehiclemod.entity.AbstractVehicleEntity;
import com.example.tudursvehiclemod.entity.FreeCameraVehicle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** A walking humanoid / multi-legged robot. Extends AbstractVehicleEntity directly (not CarEntity:
 * a car tilts its whole body on slopes and brakes on the jump key; a robot keeps its torso level and
 * climbs) and reimplements ground movement from the base mod's shared helpers.
 *
 * <p>Three movement modes (WALK / GLIDE / FLIGHT), a two-stage targeting system (candidate ->
 * per-weapon lock) that re-points weapon parts through getWeaponAimYaw()/getWeaponAimPitch(),
 * multi-segment joints, per-mode visuals, and - with flight_style "aircraft" - a port of the base
 * mod's aircraft flight model. The head needs no code: the base mod already tracks a seat occupant's
 * view for a weapon part with no weapon_name (MC Heli's AddPartCamera).
 *
 * <p>Design rationale, base-mod internals and pitfalls: DEVELOPMENT_NOTES.md. */
public class HumanoidRobotEntity extends AbstractVehicleEntity implements FreeCameraVehicle {

	/** Encodes every currently-held lock as "seatIndex:targetId,seatIndex:targetId,.." - empty
	 * string means no locks at all. A DataTracker field rather than a plain Java map because the aim
	 * overrides below run on the CLIENT every frame to place the arms - a plain field would leave
	 * every other player (and the pilot's own render) seeing arms that never move. */
	private static final TrackedData<String> LOCKS_SYNC =
			DataTracker.registerData(HumanoidRobotEntity.class, TrackedDataHandlerRegistry.STRING);

	/** Entity ID of the current CANDIDATE (nearest the crosshair, not yet confirmed), or -1 for
	 * none. Synced for the same reason LOCKS_SYNC is - ASSIST channels read it client-side every
	 * frame. */
	private static final TrackedData<Integer> CANDIDATE_ID =
			DataTracker.registerData(HumanoidRobotEntity.class, TrackedDataHandlerRegistry.INTEGER);

	/** Current locomotion mode, as MovementMode's own ordinal. Synced because it changes almost
	 * everything visible about the robot at once - the gait stops, the performance envelope
	 * changes, and both sides have to agree on which physics path runs, since the client predicts
	 * its own movement between server updates. */
	private static final TrackedData<Integer> MOVEMENT_MODE =
			DataTracker.registerData(HumanoidRobotEntity.class, TrackedDataHandlerRegistry.INTEGER);

	/** Candidates closer than this to the pilot's own eye are skipped - matches the base mod's own
	 * lock search, where a near-zero distance makes the cone test meaningless. */
	private static final double MIN_SEARCH_DISTANCE = 1.0;

	/** Iterations for the ballistic pitch binary search - see tudursvehiclemod$computeBallisticPitch
	 * doc. 24 halves a 90-degree search span down to roughly 0.000005 degrees, far finer than
	 * visibly matters, at negligible cost (24 short forward simulations, only run per LOCK/ASSIST
	 * channel per tick, not per weapon on the whole vehicle). */
	private static final int BALLISTIC_SEARCH_ITERATIONS = 24;

	/** The base mod's own per-tick projectile air drag, mirrored for the ballistic solve. */
	private static final double PROJECTILE_AIR_DRAG_PER_TICK = 0.99;


	/** Eased 0..1 stride strength - 0 standing still, 1 at full speed. Kept as a plain field on
	 * purpose, unlike the lock/candidate state: it is DERIVED each tick from velocity, which is
	 * already synced, so both sides arrive at the same number without another tracked field. The
	 * previous tick's value is kept alongside it so rendering can interpolate instead of stepping. */
	private float gaitFactor;
	private float prevGaitFactor;

	// ------------------------------------------------------------------
	// Mode visuals: eased per-mode weights, mode-driven toggle parts
	// ------------------------------------------------------------------

	/** 0..1 eased "how far into GLIDE / FLIGHT" weights, stepped toward 1 for the current mode and
	 * 0 for the other at ModeVisualSettings' own transition_speed. Plain fields, not synced: they are
	 * derived deterministically each tick from the already-synced MOVEMENT_MODE, so every side arrives
	 * at the same value - the same reasoning the base mod applies to its own toggle-part progress.
	 * Drive the body-pitch lean and channel mode poses. */
	private float glideWeight;
	private float prevGlideWeight;
	private float flightWeight;
	private float prevFlightWeight;
	/** False until the first tick after spawn/load, so a robot that loads while already in GLIDE
	 * or FLIGHT starts fully in that pose instead of visibly easing into it from WALK. */
	private boolean modeVisualsInitialized;

	/** Per-part progress for ModeVisualSettings' own mode-driven parts, reported through
	 * getTogglePartProgress() in place of the base mod's own trigger-driven value. */
	private final Map<String, Float> modePartProgress = new HashMap<>();
	private final Map<String, Float> prevModePartProgress = new HashMap<>();

	// ------------------------------------------------------------------
	// ------------------------------------------------------------------
	// FLIGHT attitude: a quaternion (Euler angles can't pass through pitch +-90 smoothly)
	// ------------------------------------------------------------------

	/** True attitude while flying. Composed from rotation deltas each tick (see
	 * tudursvehiclemod$updateFlightOrientation()) rather than ever being assigned wholesale from an
	 * absolute yaw/pitch, which is what lets it pass through the poles smoothly. */
	private final Quaternionf flightOrientation = new Quaternionf();
	/** Last tick's own value, kept purely so getYaw(tickDelta) etc. can slerp between the two for
	 * smooth rendering instead of jumping once per tick. */
	private final Quaternionf prevFlightOrientation = new Quaternionf();
	/** False until tudursvehiclemod$updateFlightOrientation() has seeded flightOrientation at least
	 * once since the most recent mode change - see tudursvehiclemod$setMovementMode()'s own doc for
	 * why it resets this rather than leaving a stale quaternion in place across separate flights. */
	private boolean flightOrientationInitialized;

	/** This tick's own accumulated rotation input, in degrees - yaw and pitch only (roll comes from
	 * the ordinary, already-synced sideways input instead, see updateFlightOrientation()'s own doc).
	 * Public so client.HumanoidRobotAddonClient's own key-input handling and the server's own
	 * network receiver can both add to it directly, exactly like AircraftEntity's own
	 * identically-named, identically-public fields - consumed and zeroed once per tick. */
	public float pendingYawInput;
	public float pendingPitchInput;

	/** The pilot's head-look offset (client HeadLookOffsetState, synced via RobotHeadLookPayload) -
	 * current state, not consumed input. Used by tudursvehiclemod$effectivePilotViewDirection() so
	 * weapon-aim follows what the camera actually shows. */
	public float headLookYaw;
	public float headLookPitch;

	/** The actually-applied rotation delta, eased toward whatever pendingYawInput/pendingPitchInput
	 * (and the sideways roll input) currently target, rather than snapping straight to it each
	 * tick - see tudursvehiclemod$updateFlightOrientation()'s own doc. */
	private float smoothedFlightYawDelta;
	private float smoothedFlightPitchDelta;
	private float smoothedFlightRollDelta;

	/** Consecutive ticks with no flight rotation input at all - once this reaches
	 * {@code flight_level_assist_grace_ticks}, the level-assist engages (see
	 * tudursvehiclemod$updateFlightOrientation()'s own doc). */
	private int noFlightInputTicks;
	private float flightRollLevelTarget;
	private boolean flightRollLevelTargetLocked;

	/** Cache for getDefinition()'s own mode-scaled copy, plus the two inputs it was built from.
	 * Rebuilt only when either changes - getDefinition() is called many times per tick by both the
	 * base mod and this class, and building a 65-component record copy each time would be pure
	 * waste. */
	private VehicleDefinition scaledDefinition;
	private VehicleDefinition scaledDefinitionBase;
	private MovementMode scaledDefinitionMode;

	public HumanoidRobotEntity(EntityType<?> type, World world) {
		super(type, world);
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		super.initDataTracker(builder);
		builder.add(LOCKS_SYNC, "");
		builder.add(FOCUS_SEATS, "");
		builder.add(CANDIDATE_ID, -1);
		builder.add(MOVEMENT_MODE, MovementMode.WALK.ordinal());
	}

	@Override
	protected Identifier defaultDefinitionId() {
		return Identifier.of(HumanoidRobotAddon.MOD_ID, "humanoid_robot");
	}

	/** Roughly a humanoid footprint. This is the block-collision hitbox only - the visible model
	 * comes from the OBJ, and per-vehicle sizes come from each robot's own JSON. */
	@Override
	public net.minecraft.entity.EntityDimensions getDimensions(net.minecraft.entity.EntityPose pose) {
		return net.minecraft.entity.EntityDimensions.changing(1.6f, 4.0f);
	}

	/** This vehicle's own robot settings, from its own JSON. Never null. */
	public RobotSettings tudursvehiclemod$settings() {
		return RobotSettingsRegistry.get(this.getVehicleDefinitionId());
	}

	// ------------------------------------------------------------------
	// Stage 1: the candidate (continuous, unconfirmed)
	// ------------------------------------------------------------------

	/** Whatever is currently nearest the pilot's own crosshair within the acquisition cone, or null.
	 * This is a PREVIEW, not a commitment - it changes every tick as the pilot looks around, exactly
	 * like the base mod's own missile lock-on / Carrier lock-candidate preview. */
	public Entity tudursvehiclemod$getCandidate() {
		int id = this.dataTracker.get(CANDIDATE_ID);
		if (id < 0) {
			return null;
		}
		return this.getEntityWorld().getEntityById(id);
	}

	/** Re-evaluates the candidate from the pilot's CURRENT view. Called once per server tick from
	 * tick() - never on a key press, since the whole point of the candidate is that it needs none.
	 * Suppressed (candidate forced to none) whenever there's no real player piloting, or manual mode
	 * is engaged, for the same reason the aim channels themselves are suppressed then: there is no
	 * crosshair to justify previewing anything against. */
	private void tudursvehiclemod$updateCandidate() {
		Entity candidate = null;
		if (!this.isManualMode() && this.tudursvehiclemod$getSeatOccupant(0) instanceof ServerPlayerEntity pilot) {
			candidate = this.tudursvehiclemod$findNearestUnderCrosshair(pilot);
		}
		this.dataTracker.set(CANDIDATE_ID, candidate == null ? -1 : candidate.getId());
	}

	/** Where the pilot is effectively looking, for weapon-aim. On the ground: the pilot's own view.
	 * In FLIGHT: the camera's own direction - body orientation composed with head-look (flight_style
	 * "robot"), or the nose / free-look view ("aircraft") - because the head-look never touches the
	 * pilot's stored yaw/pitch, so getRotationVec() would be unrelated to what the camera shows. */
	private Vec3d tudursvehiclemod$effectivePilotViewDirection(PlayerEntity pilot) {
		return this.tudursvehiclemod$effectivePilotViewDirection(pilot, 1.0f);
	}

	/** Render-interpolated form - weapon parts that follow the pilot's view read it every frame
	 * (see tudursvehiclemod$aimInWeaponFrame()), and the camera itself is interpolated. */
	private Vec3d tudursvehiclemod$effectivePilotViewDirection(PlayerEntity pilot, float tickProgress) {
		if (!this.tudursvehiclemod$getMovementMode().flies()) {
			return pilot.getRotationVec(tickProgress);
		}
		// Aircraft-style flight (movement.flight_style "aircraft"): the camera is either locked to the nose,
		// or - free-look key held - the pilot's own ordinary vanilla view; there is no head-look
		// offset in this scheme at all.
		if (this.tudursvehiclemod$usesAircraftFlightControls()) {
			if (this.tudursvehiclemod$isEffectiveFreeLook(pilot)) {
				return pilot.getRotationVec(tickProgress);
			}
			Quaternionf nose = new Quaternionf()
					.rotateY((float) Math.toRadians(-this.getYaw(tickProgress)))
					.rotateX((float) Math.toRadians(this.getPitch(tickProgress)))
					.rotateZ((float) Math.toRadians(this.getRoll(tickProgress)));
			Vector3f forward = nose.transform(new Vector3f(0, 0, 1));
			return new Vec3d(forward.x, forward.y, forward.z);
		}
		Quaternionf fresh = new Quaternionf()
				.rotateY((float) Math.toRadians(180.0 - this.getYaw(tickProgress)))
				.rotateX((float) Math.toRadians(-this.getPitch(tickProgress)))
				.rotateZ((float) Math.toRadians(-this.getRoll(tickProgress)));
		Quaternionf headOffset = new Quaternionf()
				.rotationY((float) Math.toRadians(this.headLookYaw))
				.rotateX((float) Math.toRadians(this.headLookPitch));
		fresh.mul(headOffset);
		Vector3f forward = new Vector3f(0, 0, -1);
		fresh.transform(forward);
		return new Vec3d(forward.x, forward.y, forward.z);
	}

	/** The shared crosshair-cone search both the candidate preview and lock confirmation are built
	 * from - nearest to the pilot's own EFFECTIVE view direction, within range, matching the base
	 * mod's own tudursvehiclemod$findLockOnTarget() shape exactly (see
	 * tudursvehiclemod$isSearchCandidate()'s own doc for the candidate rules themselves). */
	private Entity tudursvehiclemod$findNearestUnderCrosshair(ServerPlayerEntity pilot) {
		RobotSettings settings = this.tudursvehiclemod$settings();
		double range = Math.max(1.0, settings.lockRange());
		Vec3d eyePos = pilot.getEyePos();
		Vec3d viewDir = this.tudursvehiclemod$effectivePilotViewDirection(pilot);
		double bestDot = Math.cos(Math.toRadians(settings.lockConeDegrees()));
		Entity best = null;

		Box searchBox = pilot.getBoundingBox().expand(range);
		for (Entity candidate : this.getEntityWorld().getOtherEntities(pilot, searchBox,
				e -> tudursvehiclemod$isSearchCandidate(e, pilot))) {
			Vec3d toCandidate = candidate.getEntityPos().subtract(eyePos);
			double distance = toCandidate.length();
			if (distance < MIN_SEARCH_DISTANCE || distance > range) {
				continue;
			}
			double dot = toCandidate.normalize().dotProduct(viewDir);
			if (dot > bestDot) {
				bestDot = dot;
				best = candidate;
			}
		}
		return best;
	}

	/** Same candidate rules the base mod's own missile lock search uses: a live LivingEntity or a
	 * not-yet-destroyed vehicle, never this robot itself, never the pilot, and never a carrier
	 * runway platform tile (which is a PathAwareEntity implementation detail, not a target). */
	private boolean tudursvehiclemod$isSearchCandidate(Entity candidate, ServerPlayerEntity pilot) {
		if (candidate == this || candidate == pilot || candidate.hasPassenger(pilot)) {
			return false;
		}
		if (candidate instanceof com.example.tudursvehiclemod.entity.CarrierRunwayPlatformEntity) {
			return false;
		}
		if (candidate instanceof AbstractVehicleEntity vehicle) {
			return !vehicle.tudursvehiclemod$isDestroyed();
		}
		return candidate instanceof LivingEntity living && living.isAlive();
	}

	// ------------------------------------------------------------------
	// Stage 2: per-weapon confirmed locks
	// ------------------------------------------------------------------

	/** Decodes LOCKS_SYNC's own "seatIndex:targetId,.." encoding. Public so the client renderer can
	 * enumerate every currently-held lock without needing its own copy of the parsing. */
	public Map<Integer, Integer> tudursvehiclemod$getAllLocks() {
		Map<Integer, Integer> primary = new HashMap<>();
		for (Map.Entry<Integer, List<Integer>> entry : this.tudursvehiclemod$getAllLockLists().entrySet()) {
			List<Integer> ids = entry.getValue();
			if (!ids.isEmpty()) {
				primary.put(entry.getKey(), ids.get(ids.size() - 1)); // the most recently designated
			}
		}
		return primary;
	}

	/** Every seat's own full target list ("seat:id|id|id,seat:id" in LOCKS_SYNC), oldest first. A
	 * single-lock weapon's list simply has one entry; multi_lock weapons can hold several. Public so
	 * the client's LockMarkerRenderer can mark every locked target. */
	public Map<Integer, List<Integer>> tudursvehiclemod$getAllLockLists() {
		String encoded = this.dataTracker.get(LOCKS_SYNC);
		if (encoded.isEmpty()) {
			return Map.of();
		}
		Map<Integer, List<Integer>> locks = new HashMap<>();
		for (String pair : encoded.split(",")) {
			int colon = pair.indexOf(':');
			if (colon < 0) {
				continue;
			}
			try {
				int seat = Integer.parseInt(pair.substring(0, colon));
				List<Integer> ids = new ArrayList<>();
				for (String id : pair.substring(colon + 1).split("\\|")) {
					if (!id.isEmpty()) {
						ids.add(Integer.parseInt(id));
					}
				}
				if (!ids.isEmpty()) {
					locks.put(seat, ids);
				}
			} catch (NumberFormatException ignored) {
				// Defensive - shouldn't happen given this is entirely this addon's own encoding.
			}
		}
		return locks;
	}

	private void tudursvehiclemod$writeAllLockLists(Map<Integer, List<Integer>> locks) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, List<Integer>> entry : locks.entrySet()) {
			if (entry.getValue().isEmpty()) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append(',');
			}
			sb.append(entry.getKey()).append(':');
			for (int i = 0; i < entry.getValue().size(); i++) {
				if (i > 0) {
					sb.append('|');
				}
				sb.append(entry.getValue().get(i));
			}
		}
		this.dataTracker.set(LOCKS_SYNC, sb.toString());
	}

	/** Seats of multi_lock weapons currently switched to single-target (focus) mode, "4,6". Synced so
	 * the client can show it on the lock markers. */
	private static final TrackedData<String> FOCUS_SEATS =
			DataTracker.registerData(HumanoidRobotEntity.class, TrackedDataHandlerRegistry.STRING);

	public boolean tudursvehiclemod$isFocusMode(int seatIndex) {
		String encoded = this.dataTracker.get(FOCUS_SEATS);
		for (String seat : encoded.split(",")) {
			if (seat.equals(Integer.toString(seatIndex))) {
				return true;
			}
		}
		return false;
	}

	/** Whether this seat's weapon currently takes several targets (a multi_lock channel not in focus
	 * mode). */
	public boolean tudursvehiclemod$isMultiLockActive(int seatIndex) {
		TrackingChannel channel = this.tudursvehiclemod$settings().channelFor(seatIndex);
		return channel != null && channel.multiLock().isPresent() && !this.tudursvehiclemod$isFocusMode(seatIndex);
	}

	/** The focus toggle key: switches the SELECTED weapon between multi-target and single-target
	 * mode, if it is a multi_lock weapon. Entering single-target mode keeps only the most recently
	 * designated target. Reports the new mode to the pilot. */
	public void tudursvehiclemod$toggleLockFocus(ServerPlayerEntity pilot) {
		Integer seat = this.tudursvehiclemod$selectedWeaponSeatIndex();
		if (seat == null) {
			return;
		}
		TrackingChannel channel = this.tudursvehiclemod$settings().channelFor(seat);
		if (channel == null || channel.multiLock().isEmpty()) {
			pilot.sendMessage(Text.translatable("message.humanoidrobotaddon.lock_mode.unsupported"), true);
			return;
		}
		Set<String> seats = new LinkedHashSet<>();
		for (String entry : this.dataTracker.get(FOCUS_SEATS).split(",")) {
			if (!entry.isEmpty()) {
				seats.add(entry);
			}
		}
		boolean nowFocus = !seats.remove(Integer.toString(seat));
		if (nowFocus) {
			seats.add(Integer.toString(seat));
			Map<Integer, List<Integer>> locks = new HashMap<>(this.tudursvehiclemod$getAllLockLists());
			List<Integer> ids = locks.get(seat);
			if (ids != null && ids.size() > 1) {
				locks.put(seat, List.of(ids.get(ids.size() - 1)));
				this.tudursvehiclemod$writeAllLockLists(locks);
			}
		}
		this.dataTracker.set(FOCUS_SEATS, String.join(",", seats));
		pilot.sendMessage(Text.translatable(nowFocus
				? "message.humanoidrobotaddon.lock_mode.single"
				: "message.humanoidrobotaddon.lock_mode.multi", channel.multiLock().get().maxTargets()), true);
	}

	/** The entity locked for the weapon at this seat index, or null if that seat has no lock right
	 * now. Resolved fresh from the synced encoding each call rather than cached, since the target
	 * may have been removed/unloaded since. */
	public Entity tudursvehiclemod$getLockTarget(int seatIndex) {
		Integer id = this.tudursvehiclemod$getAllLocks().get(seatIndex);
		return id == null ? null : this.getEntityWorld().getEntityById(id);
	}

	/** Confirms the current candidate as the SELECTED weapon's lock (the base mod's Carrier designation
	 * key). A multi_lock channel not in focus mode appends to its list (oldest dropped when full);
	 * anything else replaces. Returns the locked entity, or null if there was no candidate. */
	public Entity tudursvehiclemod$tryAcquireLock(ServerPlayerEntity pilot) {
		Entity candidate = this.tudursvehiclemod$getCandidate();
		if (candidate == null) {
			return null;
		}
		Integer seatIndex = this.tudursvehiclemod$selectedWeaponSeatIndex();
		if (seatIndex == null) {
			return null;
		}
		Map<Integer, List<Integer>> locks = new HashMap<>(this.tudursvehiclemod$getAllLockLists());
		TrackingChannel channel = this.tudursvehiclemod$settings().channelFor(seatIndex);
		if (this.tudursvehiclemod$isMultiLockActive(seatIndex)) {
			// Multi-target: append (re-designating moves to newest); the oldest drops off when full.
			List<Integer> ids = new ArrayList<>(locks.getOrDefault(seatIndex, List.of()));
			ids.remove(Integer.valueOf(candidate.getId()));
			ids.add(candidate.getId());
			int max = Math.max(1, channel.multiLock().get().maxTargets());
			while (ids.size() > max) {
				ids.remove(0);
			}
			locks.put(seatIndex, ids);
		} else {
			locks.put(seatIndex, List.of(candidate.getId()));
		}
		this.tudursvehiclemod$writeAllLockLists(locks);
		return candidate;
	}

	/** The seat index of the currently selected weapon (weapon 0 when nothing has been selected yet -
	 * the synced index starts at -1 and the client only sends changes), or null with no weapons. */
	private Integer tudursvehiclemod$selectedWeaponSeatIndex() {
		int weaponIndex = this.tudursvehiclemod$getSelectedWeaponIndex();
		if (weaponIndex < 0) {
			weaponIndex = 0;
		}
		List<WeaponDefinition> weapons = this.getDefinition().weapons();
		if (weaponIndex >= weapons.size()) {
			return null;
		}
		return weapons.get(weaponIndex).seatIndex();
	}

	/** Releases every lock this robot currently holds, across every weapon, at once. */
	public void tudursvehiclemod$releaseAllLocks() {
		this.tudursvehiclemod$writeAllLockLists(Map.of());
	}

	// ------------------------------------------------------------------
	// Reusing the base mod's own Carrier/CAS wingman target-designation key
	// ------------------------------------------------------------------

	/** The base mod's Carrier target-designation key, repurposed as this robot's own lock key: with a
	 * candidate, locks it for the selected weapon; without one, releases that weapon's own lock. */
	@Override
	public void tudursvehiclemod$toggleCarrierLockMode() {
		if (this.tudursvehiclemod$getSeatOccupant(0) instanceof ServerPlayerEntity pilot) {
			this.tudursvehiclemod$tryAcquireLock(pilot);
		}
	}

	/** The base mod's own client sends this when the SAME key is pressed WITH Alt held (see
	 * VehicleModClient's own carrierLockToggleKey doc) - for a Carrier lead this drops every
	 * wingman's own lock at once; here it releases every one of THIS robot's own per-weapon locks
	 * at once, matching that "release ALL" semantics literally. */
	@Override
	public void tudursvehiclemod$releaseAllCarrierLocks() {
		this.tudursvehiclemod$releaseAllLocks();
	}

	// ------------------------------------------------------------------
	// Per-tick maintenance
	// ------------------------------------------------------------------

	/** Per-tick bookkeeping shared by both sides (gait, visuals, readiness, terrain), then server-only
	 * targeting state. */
	@Override
	public void tick() {
		super.tick();
		this.tudursvehiclemod$updateGaitFactor();
		this.tudursvehiclemod$updateModeVisuals();
		this.tudursvehiclemod$updateReadyWeights();
		this.tudursvehiclemod$updateTerrainFollow();

		if (this.getEntityWorld().isClient()) {
			return;
		}

		this.tudursvehiclemod$enforceModeAvailability();
		this.tudursvehiclemod$enforceLandingGearState();
		this.tudursvehiclemod$updateCandidate();
		this.tudursvehiclemod$pruneInvalidLocks();
	}

	/** Drops any lock whose target is no longer a valid lock target, or every lock at once if
	 * nobody is currently piloting. */
	private void tudursvehiclemod$pruneInvalidLocks() {
		Map<Integer, List<Integer>> locks = this.tudursvehiclemod$getAllLockLists();
		if (locks.isEmpty()) {
			return;
		}
		boolean hasPilot = this.getControllingPassenger() != null;
		boolean changed = false;
		Map<Integer, List<Integer>> pruned = new HashMap<>();
		for (Map.Entry<Integer, List<Integer>> entry : locks.entrySet()) {
			List<Integer> kept = new ArrayList<>();
			for (Integer id : entry.getValue()) {
				Entity target = hasPilot ? this.getEntityWorld().getEntityById(id) : null;
				boolean stillValid = target != null
						&& target.isAlive()
						&& !target.isRemoved()
						&& !(target instanceof AbstractVehicleEntity vehicle && vehicle.tudursvehiclemod$isDestroyed());
				if (stillValid) {
					kept.add(id);
				} else {
					changed = true;
				}
			}
			pruned.put(entry.getKey(), kept);
		}
		if (changed) {
			this.tudursvehiclemod$writeAllLockLists(pruned);
		}
	}




	// ------------------------------------------------------------------
	// Locomotion modes
	// ------------------------------------------------------------------

	public MovementMode tudursvehiclemod$getMovementMode() {
		MovementMode[] modes = MovementMode.values();
		int ordinal = this.dataTracker.get(MOVEMENT_MODE);
		return (ordinal >= 0 && ordinal < modes.length) ? modes[ordinal] : MovementMode.WALK;
	}

	public void tudursvehiclemod$setMovementMode(MovementMode mode) {
		this.dataTracker.set(MOVEMENT_MODE, mode.ordinal());
		this.ticksSinceModeChange = 0;
		// Re-seed flightOrientation on the next FLIGHT tick, rather than resuming a stale one.
		this.flightOrientationInitialized = false;
	}

	/** Resolves a mode-key press against the CURRENT mode, server-side (see
	 * network.SetMovementModePayload's own doc for why the client doesn't just name a mode):
	 * a long press engages FLIGHT from anywhere, and a short press follows
	 * MovementMode.nextOnShortPress() - WALK and GLIDE alternate, and FLIGHT drops to GLIDE
	 * without needing a hold, since only ENTERING flight is the deliberate act. */
	public void tudursvehiclemod$applyModeKey(boolean longPress) {
		int availability = this.tudursvehiclemod$settings().movement().modeAvailability();
		if (availability <= 0) {
			return; // WALK only - the key does nothing
		}
		MovementMode current = this.tudursvehiclemod$getMovementMode();
		MovementMode next = longPress ? MovementMode.FLIGHT : current.nextOnShortPress();
		if (next == MovementMode.FLIGHT && availability < 2) {
			return; // GLIDE only: a long press is simply ignored
		}
		this.tudursvehiclemod$setMovementMode(next);
	}

	/** The highest mode movement.mode_availability permits (0 = WALK, 1 = GLIDE, 2 = FLIGHT). Checked
	 * every server tick as well as on key presses, so a robot already in a mode it may no longer use
	 * (settings reloaded, or spawned from older data) is brought back down: FLIGHT drops to GLIDE if
	 * GLIDE is allowed, otherwise to WALK. */
	private void tudursvehiclemod$enforceModeAvailability() {
		int availability = this.tudursvehiclemod$settings().movement().modeAvailability();
		MovementMode current = this.tudursvehiclemod$getMovementMode();
		if (current == MovementMode.FLIGHT && availability < 2) {
			this.tudursvehiclemod$setMovementMode(availability >= 1 ? MovementMode.GLIDE : MovementMode.WALK);
		} else if (current == MovementMode.GLIDE && availability < 1) {
			this.tudursvehiclemod$setMovementMode(MovementMode.WALK);
		}
	}

	/** The definition with max speed, acceleration, fuel burn and turn speed scaled by the current
	 * mode's own tuning (cached per mode) - one override that HUD, fuel, turn limits and cruise blend
	 * all follow. */
	@Override
	public VehicleDefinition getDefinition() {
		VehicleDefinition base = super.getDefinition();
		MovementMode mode = this.tudursvehiclemod$getMovementMode();
		if (mode == MovementMode.WALK) {
			return base;
		}
		if (this.scaledDefinition == null || this.scaledDefinitionBase != base || this.scaledDefinitionMode != mode) {
			this.scaledDefinition = tudursvehiclemod$scaleDefinition(
					base, RobotSettingsRegistry.get(this.getVehicleDefinitionId()).movement().tuningFor(mode));
			this.scaledDefinitionBase = base;
			this.scaledDefinitionMode = mode;
		}
		return this.scaledDefinition;
	}

	/** A copy of one VehicleDefinition with only max speed, acceleration and fuel burn scaled.
	 *
	 * <p>Spelled out component by component because a Java record has no "copy with these fields
	 * changed" operation - every one of the other 62 has to be carried across by hand. That is
	 * verbose but safe in the way that matters: if the base mod ever adds or reorders a component,
	 * this stops COMPILING rather than silently dropping a field at runtime. */
	private static VehicleDefinition tudursvehiclemod$scaleDefinition(
			VehicleDefinition base, MovementSettings.ModeTuning tuning) {
		return new VehicleDefinition(
				base.entityType(),
				base.model(),
				base.texture(),
				base.scale(),
				base.width(),
				base.height(),
				base.maxSpeed() * tuning.speed(),
				base.acceleration() * tuning.acceleration(),
				base.turnSpeed() * tuning.turn(),
				base.stepHeight(),
				base.gravity(),
				base.onGroundPitch(),
				base.reverseThrottle(),
				base.diveMaxSpeed(),
				base.engineSoundVolume(),
				base.pivotTurnThrottle(),
				base.wheelRotationSpeed(),
				base.throttleUpDown(),
				base.weightType(),
				base.throttleSwitchHoldTicks(),
				base.seats(),
				base.weapons(),
				base.spinningParts(),
				base.toggleParts(),
				base.spawnItem(),
				base.hud(),
				base.engineSound(),
				base.weaponParts(),
				base.maxHealth(),
				base.armorDamageFactor(),
				base.armorMinDamage(),
				base.armorMaxDamage(),
				base.damageFactor(),
				base.maxFuel(),
				base.fuelConsumptionPerSecond() * tuning.fuel(),
				base.inventorySize(),
				base.ammoParts(),
				base.submergedDamageHeight(),
				base.forceBoundingBox(),
				base.stallSpeedFraction(),
				base.hideEntity(),
				base.entityWidth(),
				base.entityHeight(),
				base.isFloatCapable(),
				base.wheelParts(),
				base.steeringWheelParts(),
				base.crawlerTracks(),
				base.trackRollerParts(),
				base.vtolRotorParts(),
				base.isUav(),
				base.isTargetDrone(),
				base.vtolHoverSpeedFraction(),
				base.runways(),
				base.wakeTrailSpreadDistance(),
				base.wakeTrailDurationTicks(),
				base.flareType(),
				base.searchLightParts(),
				base.navLightParts(),
				base.fuelSupplyRange(),
				base.ammoSupplyRange(),
				base.healthSupplyRange(),
				base.regeneration(),
				base.defaultFreelook(),
				base.enableEjectionSeat(),
				base.mobDropOption());
	}

	// ------------------------------------------------------------------
	// Ground movement (WALK and GLIDE)
	// ------------------------------------------------------------------

	/** Eased turn rate (degrees/tick), so yaw doesn't jump straight to full turn speed the instant
	 * A/D is pressed or released. Ported from CarEntity, which needs it for exactly the same
	 * reason. */
	private float turnRate;

	/** Visual-only Y catch-up after a step-up. The step itself has to move the robot instantly (the
	 * collision check depends on it), so this holds the difference and eases it away over the next
	 * few ticks, turning the snap into a rise. Ported from CarEntity along with the step-up search
	 * it belongs to. */
	private float stepUpVisualOffset;
	private float prevStepUpVisualOffset;
	private static final float STEP_UP_VISUAL_CATCHUP_RATE = 0.3f;

	/** Accumulated ground distance, in degrees, driving the gait. Named for the wheel rotation it
	 * was in CarEntity because it is computed identically and scaled by the same
	 * {@code wheel_rotation_speed} - what it turns here is legs rather than wheels. */
	private float wheelRotation;
	private float prevWheelRotation;

	/** isOnGround() flickers false for single ticks on ordinary uneven terrain, so ground contact is
	 * judged over a short grace window instead. Ported from CarEntity, which documents the same
	 * flakiness. */
	private int ticksSinceGrounded;
	private static final int GROUNDED_GRACE_TICKS = 3;

	/** Horizontal speed retained per tick while genuinely airborne - ordinary air resistance, not a
	 * brake (this vehicle has none). */
	private static final float AIRBORNE_MOMENTUM_RETENTION_PER_TICK = 0.98f;

	/** How fast throttle spools back to 0 when unpiloted. */
	private static final float UNMANNED_THROTTLE_DECAY = 0.01f;

	/** Minimum look-ahead distance for step detection, for when the robot is barely moving (e.g.
	 * throttle still ramping up from a stop) and its own velocity would look ahead almost nowhere. */
	private static final double STEP_LOOK_AHEAD_MIN_DISTANCE = 0.1;

	/** Vanilla's own gravity acceleration, applied while airborne. */
	private static final double GRAVITY_PER_TICK = 0.08;

	/** WALK / GLIDE: Space sets Y velocity to climb_speed while held, within movement.climb_limit_ticks
	 * per airborne stretch (landing refills). Returns true while climbing, which also suppresses gravity
	 * for the tick. No hover on release - deliberate, so climbing stays a deliberate act. */
	private boolean tudursvehiclemod$applyGroundModeClimb() {
		if (!(this.getControllingPassenger() instanceof PlayerEntity pilot)) {
			return false;
		}
		if (!tudursvehiclemod$isPilotJumping(pilot) || this.tudursvehiclemod$isOutOfFuel()) {
			return false;
		}
		float climbSpeed = this.tudursvehiclemod$settings().movement().climbSpeed();
		if (climbSpeed <= 0f) {
			return false;
		}
		// movement.climb_limit_ticks: -1 unlimited; otherwise at most that many ticks of climbing per
		// airborne stretch - once spent, no more lift (whatever the input) until the robot lands,
		// which refills it (see tudursvehiclemod$updateGroundMovement()).
		int limit = this.tudursvehiclemod$settings().movement().climbLimitTicks();
		if (limit >= 0 && this.climbTicksUsed >= limit) {
			return false;
		}
		this.climbTicksUsed++;
		this.setVelocity(this.getVelocity().x, climbSpeed, this.getVelocity().z);
		return true;
	}

	/** Ticks of ground-mode climb spent since the robot last stood on the ground - see
	 * movement.climb_limit_ticks. */
	private int climbTicksUsed;

	/** WALK / GLIDE physics: level out any leftover flight attitude, steer (yaw before velocity - the
	 * heading is read from getYaw() at that moment), throttle-driven velocity while grounded OR
	 * climbing (a deliberate climb is not a fall), momentum decay while genuinely airborne, then
	 * climb / grounded-zero / gravity for Y, step-up, and move. cruiseSpeed is left to
	 * approachThrottledVelocity()'s own blend - overwriting it with measured speed fed noise back into
	 * the blend every tick (see DEVELOPMENT_NOTES.md). */
	private void tudursvehiclemod$updateGroundMovement(VehicleDefinition def) {
		this.aircraftFlightActive = false; // leaving FLIGHT: the aircraft model re-seeds on the next entry
		// Level out any pitch/roll FLIGHT left behind (nothing else resets them).
		this.setPitch(stepTowardAngle(this.getPitch(), 0f, 6f));
		this.prevRoll = this.roll;
		this.roll = stepTowardAngle(this.roll, 0f, 6f);

		if (this.isOnGround()) {
			this.ticksSinceGrounded = 0;
		} else {
			this.ticksSinceGrounded++;
		}
		boolean effectivelyGrounded = this.ticksSinceGrounded <= GROUNDED_GRACE_TICKS;
		if (this.isOnGround()) {
			this.climbTicksUsed = 0; // landing refills the climb budget
		}
		// Evaluated ONCE per tick: it spends the climb budget, so both the horizontal "in control"
		// test and the vertical block below read this same result.
		boolean climbing = this.tudursvehiclemod$applyGroundModeClimb();

		this.prevWheelRotation = this.wheelRotation;
		this.prevStepUpVisualOffset = this.stepUpVisualOffset;

		if (this.getControllingPassenger() instanceof PlayerEntity pilot) {
			double sidewaysInput = this.getSyncedSidewaysInput();
			float throttle = this.updateThrottle(pilot, 0.03f * def.throttleUpDown().orElse(1.0f),
					def.reverseThrottle(), 1.0f);

			// Steering. Reversed while genuinely travelling backwards (keyed off actual signed
			// speed, not throttle input) - a body pivoting about its own leading end swings the
			// opposite way in reverse, the same reasoning CarEntity applies to its front wheels.
			if (!this.tudursvehiclemod$isOutOfFuel()) {
				float targetTurnRate = (float) sidewaysInput * this.tudursvehiclemod$getEffectiveTurnSpeed();
				if (this.cruiseSpeed < 0f) {
					targetTurnRate = -targetTurnRate;
				}
				this.turnRate += (targetTurnRate - this.turnRate) * 0.4f;
				this.setYaw(this.getYaw() - this.turnRate);
				// Turn every passenger's own view by the same amount, so their view stays fixed
				// relative to the robot rather than silently rotating out of sync with its body.
				for (Entity passenger : this.tudursvehiclemod$getRealPassengerList()) {
					passenger.setYaw(passenger.getYaw() - this.turnRate);
					if (passenger instanceof ServerPlayerEntity serverPlayer) {
						serverPlayer.networkHandler.requestTeleport(
								serverPlayer.getX(), serverPlayer.getY(), serverPlayer.getZ(),
								serverPlayer.getYaw(), serverPlayer.getPitch());
					}
				}
			} else {
				this.turnRate = 0f;
			}

			// A deliberate climb keeps full throttle response - only a genuine fall decays momentum.
			if (effectivelyGrounded || climbing) {
				Vec3d throttled = this.approachThrottledVelocity(def, throttle, false);
				this.setVelocity(throttled.x, this.getVelocity().y, throttled.z);
			} else {
				// Genuinely, involuntarily airborne (fell off a ledge, stepped off a cliff) - no
				// traction to accelerate against, so horizontal momentum just decays.
				this.setVelocity(this.getVelocity().x * AIRBORNE_MOMENTUM_RETENTION_PER_TICK,
						this.getVelocity().y,
						this.getVelocity().z * AIRBORNE_MOMENTUM_RETENTION_PER_TICK);
			}
		} else {
			this.turnRate = 0f;
			float throttle = this.getThrottle();
			this.setThrottleDirect(throttle > 0f ? Math.max(0f, throttle - UNMANNED_THROTTLE_DECAY)
					: Math.min(0f, throttle + UNMANNED_THROTTLE_DECAY));
			Vec3d throttled = this.approachThrottledVelocity(def, this.getThrottle(), false);
			this.setVelocity(throttled.x, this.getVelocity().y, throttled.z);
		}

		// Vertical: climb wins while held; grounded means zero (not gravity accumulating), else gravity.
		if (!climbing) {
			if (effectivelyGrounded) {
				this.setVelocity(this.getVelocity().x, 0.0, this.getVelocity().z);
			} else {
				this.setVelocity(this.getVelocity().x, this.getVelocity().y - GRAVITY_PER_TICK,
						this.getVelocity().z);
			}
		}

		this.tudursvehiclemod$applyStepUp(def, effectivelyGrounded);
		this.stepUpVisualOffset += (0f - this.stepUpVisualOffset) * STEP_UP_VISUAL_CATCHUP_RATE;
		this.wheelRotation += (float) this.getVelocity().horizontalLength() * 20f;
		double yawRad = Math.toRadians(this.getYaw());
		this.prevTravelDistance = this.travelDistance;
		this.travelDistance += (float) (this.getVelocity().x * -Math.sin(yawRad) + this.getVelocity().z * Math.cos(yawRad));

		this.move(MovementType.SELF, this.getVelocity());
	}

	/** Lifts the robot over obstacles up to step_height (binary search on the clearance), tracked by
	 * stepUpVisualOffset so the render position eases instead of jumping. Grounded only. */
	private void tudursvehiclemod$applyStepUp(VehicleDefinition def, boolean effectivelyGrounded) {
		if (!effectivelyGrounded || def.stepHeight() < 0.05f) {
			return;
		}
		Vec3d velocity = this.getVelocity();
		double horizontalSpeed = velocity.horizontalLength();
		Box currentBox = this.getBoundingBox();
		Box movedBox;
		if (horizontalSpeed > STEP_LOOK_AHEAD_MIN_DISTANCE) {
			movedBox = currentBox.offset(velocity.x, 0, velocity.z);
		} else if (horizontalSpeed > 1.0E-4) {
			double scale = STEP_LOOK_AHEAD_MIN_DISTANCE / horizontalSpeed;
			movedBox = currentBox.offset(velocity.x * scale, 0, velocity.z * scale);
		} else {
			return;
		}
		if (this.getEntityWorld().isSpaceEmpty(this, movedBox)) {
			return;
		}
		float lo = 0f;
		float hi = def.stepHeight();
		if (!this.getEntityWorld().isSpaceEmpty(this, movedBox.offset(0, hi, 0))) {
			return;
		}
		for (int i = 0; i < 8; i++) {
			float mid = (lo + hi) / 2f;
			if (this.getEntityWorld().isSpaceEmpty(this, movedBox.offset(0, mid, 0))) {
				hi = mid;
			} else {
				lo = mid;
			}
		}
		this.setPosition(this.getX(), this.getY() + hi, this.getZ());
		this.stepUpVisualOffset -= hi;
	}

	/** Yaw/pitch/roll (degrees, Minecraft convention) from a body-orientation quaternion, in double
	 * precision - which delays, but cannot remove, the gimbal-lock singularity at pitch +-90. */
	private static float[] tudursvehiclemod$extractYawPitchRoll(Quaternionf q) {
		double x = q.x;
		double y = q.y;
		double z = q.z;
		double w = q.w;

		double pitchRad = Math.asin(MathHelper.clamp(2.0 * (x * w - y * z), -1.0, 1.0));
		double negYawRad = Math.atan2(2.0 * (x * z + y * w), 1.0 - 2.0 * (x * x + y * y));
		double rollRad = Math.atan2(2.0 * (x * y + z * w), 1.0 - 2.0 * (x * x + z * z));

		float pitch = (float) Math.toDegrees(pitchRad);
		float yaw = (float) -Math.toDegrees(negYawRad);
		float roll = (float) Math.toDegrees(rollRad);
		return new float[]{yaw, pitch, roll};
	}

	private void tudursvehiclemod$setFlightOrientationFromEuler(float yaw, float pitch, float roll) {
		this.flightOrientation.rotationY((float) Math.toRadians(-yaw))
				.rotateX((float) Math.toRadians(pitch))
				.rotateZ((float) Math.toRadians(roll));
	}

	/** FLIGHT attitude: seeds flightOrientation from the current yaw/pitch/roll on the first tick after
	 * a mode change (super.getYaw(), not this.getYaw() - the override reads the not-yet-seeded
	 * quaternion), then composes this tick's rate-clamped, smoothed yaw/pitch/roll input onto it, applies
	 * level assist after a period with no input, and extracts yaw/pitch/roll back into the vanilla
	 * fields for everything that reads those. */
	private void tudursvehiclemod$updateFlightOrientation() {
		this.tudursvehiclemod$updateFlightOrientation(true, null, 0f);
	}

	/** pilotControl false skips pilot input and level assist entirely (the aircraft flight model's
	 * own stall / level / ground branches, which don't take stick input - see
	 * tudursvehiclemod$updateAircraftFlight()); forcedTarget, if given, is then slerped toward by
	 * forcedT - both before the yaw/pitch/roll fields are extracted and passenger views carried, so
	 * every attitude change of the tick goes through the same bookkeeping exactly once. */
	private void tudursvehiclemod$updateFlightOrientation(boolean pilotControl, Quaternionf forcedTarget, float forcedT) {
		this.prevFlightOrientation.set(this.flightOrientation);
		if (!this.flightOrientationInitialized) {
			// First tick of this flight: seed from the current attitude. super.getYaw()/getPitch(),
			// not this.getYaw() - the override would read flightOrientation, the thing being seeded.
			this.tudursvehiclemod$setFlightOrientationFromEuler(super.getYaw(), super.getPitch(), this.roll);
			this.prevFlightOrientation.set(this.flightOrientation);
			this.flightOrientationInitialized = true;
			this.smoothedFlightYawDelta = 0f;
			this.smoothedFlightPitchDelta = 0f;
			this.smoothedFlightRollDelta = 0f;
			this.noFlightInputTicks = 0;
			this.flightRollLevelTargetLocked = false;
		}

		if (pilotControl) {
			MovementSettings movement = this.tudursvehiclemod$settings().movement();
			// Key input always applies (free-look only ever meant "the mouse is busy", and it doesn't
			// steer in the robot scheme). Aircraft scheme: AircraftEntity's turn_speed-derived rates,
			// 10% when destroyed, stick centred while free-looking.
			boolean aircraftStyle = this.tudursvehiclemod$usesAircraftFlightControls();
			float destroyedMultiplier = aircraftStyle && this.tudursvehiclemod$isDestroyed() ? 0.1f : 1f;
			if (aircraftStyle && this.isFreeLook()) {
				this.pendingYawInput = 0f;
				this.pendingPitchInput = 0f;
			}
			float yawRate = aircraftStyle
					? this.tudursvehiclemod$getEffectiveTurnSpeed() * AIRCRAFT_YAW_RATE_MULTIPLIER * destroyedMultiplier
					: movement.flightYawRateDegreesPerTick();
			float pitchRate = aircraftStyle
					? this.tudursvehiclemod$getEffectiveTurnSpeed() * AIRCRAFT_PITCH_RATE_MULTIPLIER * destroyedMultiplier
					: movement.flightPitchRateDegreesPerTick();
			float targetYawDelta = MathHelper.clamp(this.pendingYawInput, -yawRate, yawRate);
			float targetPitchDelta = MathHelper.clamp(this.pendingPitchInput, -pitchRate, pitchRate);
			// Discard anything beyond the rate limit rather than saving it for a future tick to keep draining.
			this.pendingYawInput = 0f;
			this.pendingPitchInput = 0f;

			float targetRollDelta = (float) this.getSyncedSidewaysInput()
					* (aircraftStyle ? AIRCRAFT_ROLL_RATE_DEGREES_PER_TICK * destroyedMultiplier : movement.flightRollDegreesPerTick());

			float smoothing = aircraftStyle ? AIRCRAFT_ROTATION_SMOOTHING : MathHelper.clamp(movement.flightRotationSmoothing(), 0.0f, 1.0f);
			this.smoothedFlightYawDelta += (targetYawDelta - this.smoothedFlightYawDelta) * smoothing;
			this.smoothedFlightPitchDelta += (targetPitchDelta - this.smoothedFlightPitchDelta) * smoothing;
			this.smoothedFlightRollDelta += (targetRollDelta - this.smoothedFlightRollDelta) * smoothing;

			Quaternionf inputDelta = new Quaternionf()
					.rotateY((float) Math.toRadians(-this.smoothedFlightYawDelta))
					.rotateX((float) Math.toRadians(this.smoothedFlightPitchDelta))
					.rotateZ((float) Math.toRadians(-this.smoothedFlightRollDelta));
			this.flightOrientation.mul(inputDelta).normalize();

			// Based on the RAW target (before smoothing), matching AircraftEntity's own reasoning: an
			// input that's still ramping in through the smoothing above shouldn't reset the "how long
			// has it been idle" clock, only an input that's genuinely present should.
			boolean hasInput = targetYawDelta != 0f || targetPitchDelta != 0f || targetRollDelta != 0f;
			if (hasInput) {
				this.noFlightInputTicks = 0;
				this.flightRollLevelTargetLocked = false;
			} else {
				this.noFlightInputTicks++;
				if (this.noFlightInputTicks >= (aircraftStyle ? AIRCRAFT_LEVEL_ASSIST_GRACE_TICKS : movement.flightLevelAssistGraceTicks())) {
					float[] current = tudursvehiclemod$extractYawPitchRoll(this.flightOrientation);
					if (!this.flightRollLevelTargetLocked) {
						this.flightRollLevelTarget = Math.round(current[2] / 90f) * 90f;
						this.flightRollLevelTargetLocked = true;
					}
					Quaternionf levelTarget = new Quaternionf()
							.rotationY((float) Math.toRadians(-current[0]))
							.rotateZ((float) Math.toRadians(this.flightRollLevelTarget));
					this.flightOrientation.slerp(levelTarget, aircraftStyle ? AIRCRAFT_LEVEL_ASSIST_SMOOTHING : movement.flightLevelAssistSmoothing());
					this.flightOrientation.normalize();
				}
			}

			// Keep the vanilla yaw/pitch/roll fields current for anything reading them directly.
		}
		if (forcedTarget != null) {
			this.flightOrientation.slerp(forcedTarget, forcedT).normalize();
		}

		float[] extracted = tudursvehiclemod$extractYawPitchRoll(this.flightOrientation);
		super.setYaw(extracted[0]);
		super.setPitch(extracted[1]);
		this.prevRoll = this.roll;
		this.roll = extracted[2];

		this.tudursvehiclemod$carryFlightRotationOntoPassengerViews();
	}

	/** Rotates every passenger's STORED yaw/pitch along with the body each FLIGHT tick (the Car/Ship
	 * technique). Not what drives the camera (RobotCameraMixin does) - kept so a passenger's own reported
	 * facing (compass, F3, non-camera raycasts) doesn't drift away from the robot. The delta is composed
	 * as a quaternion before any Euler extraction, so it stays well-conditioned near pitch +-90. */
	private void tudursvehiclemod$carryFlightRotationOntoPassengerViews() {
		Quaternionf worldDelta = new Quaternionf(this.flightOrientation)
				.mul(new Quaternionf(this.prevFlightOrientation).invert());
		// Near-identity check on the angle, not equals() - two Quaternionf that represent the same
		// negligible rotation are essentially never bit-identical.
		if (Math.abs(worldDelta.w) > 0.999999f) {
			return;
		}
		for (Entity passenger : this.tudursvehiclemod$getRealPassengerList()) {
			Quaternionf view = new Quaternionf()
					.rotationY((float) Math.toRadians(-passenger.getYaw()))
					.rotateX((float) Math.toRadians(passenger.getPitch()));
			Quaternionf newView = new Quaternionf(worldDelta).mul(view).normalize();
			float[] viewExtracted = tudursvehiclemod$extractYawPitchRoll(newView);
			passenger.setYaw(viewExtracted[0]);
			passenger.setPitch(MathHelper.clamp(viewExtracted[1], -90f, 90f));
			if (passenger instanceof ServerPlayerEntity serverPlayer) {
				serverPlayer.networkHandler.requestTeleport(
						serverPlayer.getX(), serverPlayer.getY(), serverPlayer.getZ(),
						serverPlayer.getYaw(), serverPlayer.getPitch());
			}
		}
	}

	/** These five overrides are what make every OTHER piece of code in this class (and the base
	 * mod's own renderer, hitbox alignment, and channel-yaw/pitch aiming math) see a smoothly
	 * looping attitude while flying, with zero changes anywhere else: while in FLIGHT, they read
	 * flightOrientation instead of the plain yaw/pitch/roll fields; every other mode defers to
	 * those plain fields exactly as before (which ground mode's own leveling keeps at 0, and which
	 * updateFlightOrientation() itself keeps mirrored to the quaternion as a fallback for anything
	 * that reads them directly instead of through these). */
	@Override
	public float getYaw(float tickDelta) {
		if (this.tudursvehiclemod$getMovementMode().flies()) {
			Quaternionf interpolated = new Quaternionf(this.prevFlightOrientation).slerp(this.flightOrientation, tickDelta);
			return tudursvehiclemod$extractYawPitchRoll(interpolated)[0];
		}
		return super.getYaw(tickDelta);
	}

	@Override
	public float getYaw() {
		if (this.tudursvehiclemod$getMovementMode().flies()) {
			return tudursvehiclemod$extractYawPitchRoll(this.flightOrientation)[0];
		}
		return super.getYaw();
	}

	@Override
	public float getPitch(float tickDelta) {
		if (this.tudursvehiclemod$getMovementMode().flies()) {
			Quaternionf interpolated = new Quaternionf(this.prevFlightOrientation).slerp(this.flightOrientation, tickDelta);
			return tudursvehiclemod$extractYawPitchRoll(interpolated)[1];
		}
		return super.getPitch(tickDelta);
	}

	@Override
	public float getPitch() {
		if (this.tudursvehiclemod$getMovementMode().flies()) {
			return tudursvehiclemod$extractYawPitchRoll(this.flightOrientation)[1];
		}
		return super.getPitch();
	}

	@Override
	public float getRoll(float tickDelta) {
		if (this.tudursvehiclemod$getMovementMode().flies()) {
			Quaternionf interpolated = new Quaternionf(this.prevFlightOrientation).slerp(this.flightOrientation, tickDelta);
			return tudursvehiclemod$extractYawPitchRoll(interpolated)[2];
		}
		return super.getRoll(tickDelta);
	}

	@Override
	public float getRoll() {
		if (this.tudursvehiclemod$getMovementMode().flies()) {
			return tudursvehiclemod$extractYawPitchRoll(this.flightOrientation)[2];
		}
		return super.getRoll();
	}

	@Override
	public float getCurrentRoll() {
		return this.getRoll();
	}

	/** flight_style "robot": always true - keeps the base mod's CameraMixin in its free-look branch,
	 * which RobotCameraMixin (higher priority) then overwrites with body orientation x head-look.
	 * "aircraft" in FLIGHT: the base mod's own rule (pilot free only via the free-look toggle), so the
	 * camera locks to the nose. On the ground: always free, like a CarEntity. */
	@Override
	public boolean tudursvehiclemod$isEffectiveFreeLook(Entity viewer) {
		// flight_style "aircraft" while flying: the base mod's own rule - non-pilots always free, the
		// pilot free only while their free-look toggle is on - so the base mod's CameraMixin locks the
		// camera to the nose like an aircraft's.
		if (this.tudursvehiclemod$usesAircraftFlightControls() && this.tudursvehiclemod$getMovementMode().flies()) {
			return viewer != this.getControllingPassenger() || this.isFreeLook();
		}
		return true;
	}

	/** Non-interpolated counterpart, used by the base mod's own one-shot direction calculations
	 * (flare deployment, carried-mount direction) rather than per-frame rendering - see
	 * AircraftEntity's own identical pairing of overrides for precedent. Same reasoning as the
	 * tick-interpolated version just below: return flightOrientation directly while flying, rather
	 * than letting the base mod's own default implementation round-trip it through this class's own
	 * (necessarily gimbal-lock-prone) getYaw()/getPitch()/getRoll() overrides first. */
	@Override
	public Quaternionf tudursvehiclemod$getBodyOrientation() {
		return this.tudursvehiclemod$getUnleanedBodyOrientation()
				.rotateX((float) Math.toRadians(this.tudursvehiclemod$getVisualBodyPitch(1.0f)));
	}

	/** The body's own attitude WITHOUT the FLIGHT body lean - what thrust, the pilot's own camera and
	 * view direction are built on. Everything the base mod hangs off the body (weapon mounts and fire
	 * direction, flares, seats, rendering) goes through tudursvehiclemod$getBodyOrientation() instead,
	 * which is this plus the lean - see tudursvehiclemod$getVisualBodyPitch()'s own doc. */
	private Quaternionf tudursvehiclemod$getUnleanedBodyOrientation() {
		if (this.tudursvehiclemod$getMovementMode().flies()) {
			return new Quaternionf(this.flightOrientation);
		}
		return super.tudursvehiclemod$getBodyOrientation();
	}

	private Quaternionf tudursvehiclemod$getUnleanedBodyOrientation(float tickDelta) {
		if (this.tudursvehiclemod$getMovementMode().flies()) {
			return new Quaternionf(this.prevFlightOrientation).slerp(this.flightOrientation, tickDelta);
		}
		return super.tudursvehiclemod$getBodyOrientation(tickDelta);
	}

	/** Render-interpolated body frame (slerped in FLIGHT, plus the visual lean) for the base mod's
	 * renderer, weapon mounts, seats and flares. */
	@Override
	public Quaternionf tudursvehiclemod$getBodyOrientation(float tickDelta) {
		return this.tudursvehiclemod$getUnleanedBodyOrientation(tickDelta)
				.rotateX((float) Math.toRadians(this.tudursvehiclemod$getVisualBodyPitch(tickDelta)));
	}

	/** FLIGHT physics. flight_style "aircraft" runs the ported aircraft model; "robot" is thrust along
	 * the nose plus Space/Shift vertical lift, no gravity. */
	private void tudursvehiclemod$updateFlightMovement(VehicleDefinition def) {
		if (this.tudursvehiclemod$usesAircraftFlightControls()) {
			this.tudursvehiclemod$updateAircraftFlight(def);
			return;
		}
		this.aircraftFlightActive = false;
		this.tudursvehiclemod$updateFlightOrientation();
		if (!(this.getControllingPassenger() instanceof PlayerEntity pilot)) {
			// Unpiloted in flight mode - keep coasting on whatever velocity is left rather than
			// freezing mid-air, the same way the base mod's own vehicles keep moving unmanned.
			this.move(MovementType.SELF, this.getVelocity());
			return;
		}
		float throttle = this.updateThrottle(pilot, 0.03f * def.throttleUpDown().orElse(1.0f), def.reverseThrottle(), 1.0f);
		Vec3d thrust = this.approachThrottledVelocity(def, throttle, true);
		this.setVelocity(thrust.x, thrust.y + this.tudursvehiclemod$flightVerticalInput(pilot), thrust.z);
		this.move(MovementType.SELF, this.getVelocity());
	}

	// ------------------------------------------------------------------
	// ------------------------------------------------------------------
	// flight_style "aircraft": a port of AircraftEntity's piloted flight model (same constants;
	// a base-mod tuning change must be repeated here). Omitted as not applicable: drone /
	// formation / CAS / carrier logic, spawn grace, water landing, on_ground_pitch (taxis level).
	// ------------------------------------------------------------------

	private static final float AIRCRAFT_YAW_RATE_MULTIPLIER = 3.0f;
	private static final float AIRCRAFT_GEAR_DEPLOYED_MAX_SPEED_MULTIPLIER = 0.7f;
	private static final float AIRCRAFT_LANDING_ATTITUDE_SAFE_DEGREES = 20f;
	private static final float AIRCRAFT_LANDING_ATTITUDE_DESTROY_DEGREES = 45f;
	private static final float AIRCRAFT_LANDING_ATTITUDE_DAMAGE_AMOUNT = 15f;
	private static final float AIRCRAFT_PITCH_RATE_MULTIPLIER = 9.0f;
	private static final float AIRCRAFT_ROLL_RATE_DEGREES_PER_TICK = 6f;
	private static final float AIRCRAFT_ROTATION_SMOOTHING = 0.2f;
	private static final int AIRCRAFT_LEVEL_ASSIST_GRACE_TICKS = 100;
	private static final float AIRCRAFT_LEVEL_ASSIST_SMOOTHING = 0.02f;
	private static final float AIRCRAFT_MIN_FLIGHT_SPEED_FRACTION = 0.5f;
	private static final float AIRCRAFT_SUSTAINED_FLIGHT_DISENGAGE_FRACTION = 0.4f;
	private static final int AIRCRAFT_SURFACED_DEBOUNCE_TICKS = 3;
	private static final double AIRCRAFT_LIFTOFF_VELOCITY = 0.35;
	private static final float AIRCRAFT_GROUND_SPEED_BLEND = 0.04f;
	private static final float AIRCRAFT_GROUND_DECEL_BLEND = 0.03f;
	private static final float AIRCRAFT_LOW_THROTTLE_GLIDE_BLEND = 0.003f;
	private static final float AIRCRAFT_DECEL_SPEEDUP_MULTIPLIER = 1.5f;
	private static final float AIRCRAFT_UNMANNED_THROTTLE_DECAY = 0.01f;
	private static final double AIRCRAFT_GROUNDED_STICK_VELOCITY = -0.05;
	private static final double AIRCRAFT_SINKING_VERTICAL_SPEED = -0.08;
	private static final float AIRCRAFT_NEAR_STALL_GRAVITY_MULTIPLIER = 6.0f;
	private static final double AIRCRAFT_NEAR_STALL_MAX_FALL_SPEED = -1.5;
	private static final float AIRCRAFT_STALL_PITCH_SMOOTHING = 0.15f;
	private static final float AIRCRAFT_AIR_DRAG_DECEL_BLEND = 0.002f;
	private static final float AIRCRAFT_DIVE_MAX_SPEED_MULTIPLIER = 1.5f;
	private static final float AIRCRAFT_DIVE_ACCEL_BLEND = 0.05f;
	private static final float AIRCRAFT_LEVEL_OUT_PITCH_BLEND = 0.03f;
	private static final float AIRCRAFT_CRASH_ANGLE_THRESHOLD_DEGREES = 20.0f;
	private static final double AIRCRAFT_CRASH_MIN_SPEED = 0.5;
	private static final float AIRCRAFT_CRASH_DAMAGE_PER_SPEED = 300.0f;
	private static final double AIRCRAFT_ENTITY_CRASH_MIN_SPEED = 0.6;
	private static final float AIRCRAFT_ENTITY_CRASH_DAMAGE_PER_SPEED = 120.0f;
	private static final float AIRCRAFT_GROUND_LEVEL_SMOOTHING = 0.3f;
	private static final float AIRCRAFT_CLIMB_DECEL_MAX_BLEND = 0.04f;
	private static final float AIRCRAFT_CLIMB_TARGET_MIN_FRACTION = 0.4f;
	private static final int AIRCRAFT_STEEP_CLIMB_GRACE_TICKS = 60;
	private static final int AIRCRAFT_STEEP_CLIMB_DECAY_TICKS = 100;
	private static final float AIRCRAFT_STEEP_CLIMB_THRESHOLD = 0.8f;
	private static final float AIRCRAFT_LANDING_IMPACT_SPEED_FRACTION = 0.5f;
	private static final float AIRCRAFT_LANDING_IMPACT_DAMAGE_PER_SPEED = 50.0f;

	/** Ticks since the last movement-mode change (any direction) - see
	 * AIRCRAFT_MODE_CHANGE_GRACE_TICKS's own doc for why the aircraft flight model's own crash/
	 * landing-impact damage checks wait for this. */
	private int ticksSinceModeChange = Integer.MAX_VALUE;

	/** Ticks after any mode switch during which the aircraft model withholds its crash / entity-crash /
	 * landing-impact damage - like the base mod's own spawn grace. The checks only see this tick's
	 * collision flags and velocity, and a mode switch is a discontinuity they can't tell from a crash. */
	private static final int AIRCRAFT_MODE_CHANGE_GRACE_TICKS = 20;

	/** False whenever the aircraft model isn't running; its first tick re-seeds the state below. */
	private boolean aircraftFlightActive;
	private boolean aircraftSustainedFlight;
	private boolean aircraftStalling;
	private boolean aircraftHasLifted;
	private boolean aircraftSurfacedState;
	private int aircraftSurfacedDebounceTicks;
	private float aircraftSmoothedSpeedTargetPitch;
	private int aircraftSteepClimbTicks;
	private boolean aircraftStallYawLocked;
	private float aircraftStallTargetYaw;
	private boolean aircraftLevelYawLocked;
	private float aircraftLevelTargetYaw;
	private boolean aircraftWasGroundedForImpact;

	private void tudursvehiclemod$updateAircraftFlight(VehicleDefinition def) {
		PlayerEntity player = this.getControllingPassenger() instanceof PlayerEntity p ? p : null;
		if (!this.aircraftFlightActive) {
			// Entering FLIGHT (the transformation): whatever this robot was doing becomes the
			// aircraft's starting state - airborne counts as already lifted, so transforming at low
			// speed in the air stalls exactly like an aircraft that slowed down too far would.
			this.aircraftFlightActive = true;
			this.aircraftSurfacedState = this.isOnGround();
			this.aircraftSurfacedDebounceTicks = 0;
			this.aircraftHasLifted = !this.aircraftSurfacedState;
			this.aircraftStalling = false;
			this.aircraftSustainedFlight = false;
			this.aircraftSmoothedSpeedTargetPitch = 0f;
			this.aircraftSteepClimbTicks = 0;
			this.aircraftStallYawLocked = false;
			this.aircraftLevelYawLocked = false;
			this.aircraftWasGroundedForImpact = this.aircraftSurfacedState;
			// Continuity: seed cruiseSpeed from the actual speed, not ground mode's leftover blend value.
			this.cruiseSpeed = (float) this.getVelocity().length();
		}

		float throttle;
		if (player != null) {
			throttle = this.updateThrottle(player, 0.015f * def.throttleUpDown().orElse(1.0f), def.reverseThrottle(), 1.0f);
			throttle = this.applyPivotTurnThrottleRestriction(def, throttle, this.getSyncedSidewaysInput(),
					0.015f * def.throttleUpDown().orElse(1.0f));
		} else {
			throttle = Math.max(0f, this.getThrottle() - AIRCRAFT_UNMANNED_THROTTLE_DECAY);
			this.setThrottleDirect(throttle);
		}

		boolean rawSurfaced = this.isOnGround();
		if (rawSurfaced == this.aircraftSurfacedState) {
			this.aircraftSurfacedDebounceTicks = 0;
		} else if (++this.aircraftSurfacedDebounceTicks >= AIRCRAFT_SURFACED_DEBOUNCE_TICKS) {
			this.aircraftSurfacedState = rawSurfaced;
			this.aircraftSurfacedDebounceTicks = 0;
		}
		boolean surfaced = this.aircraftSurfacedState;
		boolean sinking = this.isTouchingWater();
		// Landing gear, exactly as the aircraft: deployed (progress 0) costs 30% top speed and lowers
		// the stall-engage speed by 0.1 - what lets a slowed, gear-down approach stay out of the
		// near-stall sink and touch down shallowly instead of dropping in steeply.
		float gearProgress = this.getLandingGearProgress();
		float effectiveMaxSpeed = this.tudursvehiclemod$getEffectiveMaxSpeed()
				* MathHelper.lerp(gearProgress, AIRCRAFT_GEAR_DEPLOYED_MAX_SPEED_MULTIPLIER, 1f);
		float actualSpeed = (float) this.getVelocity().length();

		if (this.aircraftSustainedFlight) {
			if (this.cruiseSpeed < effectiveMaxSpeed * AIRCRAFT_SUSTAINED_FLIGHT_DISENGAGE_FRACTION) {
				this.aircraftSustainedFlight = false;
			}
		} else if (this.cruiseSpeed >= effectiveMaxSpeed * AIRCRAFT_MIN_FLIGHT_SPEED_FRACTION) {
			this.aircraftSustainedFlight = true;
		}
		boolean sustainedFlight = this.aircraftSustainedFlight;
		float stallDisengageFraction = def.stallSpeedFraction() + 0.1f;
		float stallEngageFraction = MathHelper.lerp(gearProgress, def.stallSpeedFraction() - 0.1f, def.stallSpeedFraction());
		if (surfaced) {
			this.aircraftStalling = false;
		} else if (!this.aircraftStalling && this.aircraftHasLifted && actualSpeed < effectiveMaxSpeed * stallEngageFraction) {
			this.aircraftStalling = true;
		} else if (this.aircraftStalling && actualSpeed >= effectiveMaxSpeed * stallDisengageFraction) {
			this.aircraftStalling = false;
		}

		// ---- attitude ----
		if (sinking) {
			this.pendingYawInput = 0f;
			this.pendingPitchInput = 0f;
			this.tudursvehiclemod$updateFlightOrientation(false, this.tudursvehiclemod$aircraftLevelTarget(), AIRCRAFT_LEVEL_ASSIST_SMOOTHING);
			this.aircraftHasLifted = false;
			this.aircraftStallYawLocked = false;
		} else if (surfaced) {
			// Taxiing level on the ground, steered with A/D (the robot has no on_ground_pitch attitude).
			this.pendingYawInput = 0f;
			this.pendingPitchInput = 0f;
			float steer = player != null ? (float) this.getSyncedSidewaysInput() * this.tudursvehiclemod$getEffectiveTurnSpeed() : 0f;
			Quaternionf groundTarget = new Quaternionf().rotationY((float) Math.toRadians(-(this.getYaw() - steer)));
			this.tudursvehiclemod$updateFlightOrientation(false, groundTarget, AIRCRAFT_GROUND_LEVEL_SMOOTHING);
			this.noFlightInputTicks = 0;
			this.flightRollLevelTargetLocked = false;
			this.aircraftStallYawLocked = false;
			this.aircraftLevelYawLocked = false;
			if (player != null) {
				this.aircraftHasLifted = false;
			}
			this.aircraftSteepClimbTicks = 0;
		} else if (this.aircraftStalling) {
			if (!this.aircraftStallYawLocked) {
				this.aircraftStallTargetYaw = this.getYaw();
				this.aircraftStallYawLocked = true;
			}
			Quaternionf noseDown = new Quaternionf()
					.rotationY((float) Math.toRadians(-this.aircraftStallTargetYaw))
					.rotateX((float) Math.toRadians(90f));
			this.pendingYawInput = 0f;
			this.pendingPitchInput = 0f;
			this.tudursvehiclemod$updateFlightOrientation(false, noseDown, AIRCRAFT_STALL_PITCH_SMOOTHING);
			this.aircraftHasLifted = true;
			this.aircraftLevelYawLocked = false;
		} else if (player != null) {
			this.tudursvehiclemod$updateFlightOrientation(true, null, 0f);
			this.aircraftHasLifted = true;
			this.aircraftLevelYawLocked = false;
			this.aircraftStallYawLocked = false;
		} else {
			this.tudursvehiclemod$updateFlightOrientation(false, this.tudursvehiclemod$aircraftLevelTarget(), AIRCRAFT_LEVEL_ASSIST_SMOOTHING);
			this.aircraftHasLifted = true;
			this.aircraftStallYawLocked = false;
		}

		// ---- velocity ----
		Vec3d target;
		if (sinking) {
			Vec3d current = this.getVelocity();
			target = new Vec3d(current.x * 0.9, AIRCRAFT_SINKING_VERTICAL_SPEED, current.z * 0.9);
			this.cruiseSpeed = 0f;
			this.setThrottleDirect(0f);
		} else if (surfaced) {
			float targetSpeed = throttle * effectiveMaxSpeed;
			float groundBlend = targetSpeed < this.cruiseSpeed ? AIRCRAFT_GROUND_DECEL_BLEND : AIRCRAFT_GROUND_SPEED_BLEND;
			this.cruiseSpeed += (targetSpeed - this.cruiseSpeed) * groundBlend;
			if (Math.abs(targetSpeed - this.cruiseSpeed) < 1.0e-4f) {
				this.cruiseSpeed = targetSpeed;
			}
			double yawRad = Math.toRadians(this.getYaw());
			target = new Vec3d(-Math.sin(yawRad) * this.cruiseSpeed, AIRCRAFT_GROUNDED_STICK_VELOCITY, Math.cos(yawRad) * this.cruiseSpeed);
		} else {
			Vec3d heading = this.getRotationVec(1.0f);
			float pitch = this.tudursvehiclemod$aircraftSmoothedSpeedTargetPitch();
			float targetSpeed;
			float blend;
			if (pitch >= 0f) {
				float climbFactor = MathHelper.clamp(pitch / 90f, 0f, 1f);
				targetSpeed = throttle * effectiveMaxSpeed
						* MathHelper.lerp(climbFactor, 1f, this.tudursvehiclemod$aircraftClimbTargetMinFraction(climbFactor));
				float base = sustainedFlight ? MathHelper.clamp(def.acceleration(), 0.01f, 1.0f) : AIRCRAFT_LOW_THROTTLE_GLIDE_BLEND;
				blend = Math.max(base, climbFactor * AIRCRAFT_CLIMB_DECEL_MAX_BLEND);
			} else {
				float diveFactor = MathHelper.clamp(-pitch / 90f, 0f, 1f);
				targetSpeed = MathHelper.lerp(diveFactor, throttle * effectiveMaxSpeed,
						effectiveMaxSpeed * AIRCRAFT_DIVE_MAX_SPEED_MULTIPLIER);
				float base = sustainedFlight ? MathHelper.clamp(def.acceleration(), 0.01f, 1.0f) : AIRCRAFT_LOW_THROTTLE_GLIDE_BLEND;
				blend = Math.max(base, diveFactor * AIRCRAFT_DIVE_ACCEL_BLEND);
				this.aircraftSteepClimbTicks = 0;
			}
			float effBlend = targetSpeed < this.cruiseSpeed ? blend * AIRCRAFT_DECEL_SPEEDUP_MULTIPLIER : blend;
			if (targetSpeed < this.cruiseSpeed) {
				effBlend = Math.max(effBlend, AIRCRAFT_AIR_DRAG_DECEL_BLEND);
			}
			this.cruiseSpeed += (targetSpeed - this.cruiseSpeed) * effBlend;
			if (Math.abs(targetSpeed - this.cruiseSpeed) < 1.0e-4f) {
				this.cruiseSpeed = targetSpeed;
			}
			target = heading.multiply(this.cruiseSpeed);
			if (!sustainedFlight && !this.aircraftStalling && this.cruiseSpeed < effectiveMaxSpeed * stallDisengageFraction) {
				// Near-stall sink: gravity phases in as speed falls toward the stall speed.
				float lowerBound = effectiveMaxSpeed * stallEngageFraction;
				float upperBound = effectiveMaxSpeed * stallDisengageFraction;
				float nearStallFactor = upperBound > lowerBound
						? MathHelper.clamp(1f - (this.cruiseSpeed - lowerBound) / (upperBound - lowerBound), 0f, 1f)
						: 1f;
				double gravityStrength = def.gravity() * AIRCRAFT_NEAR_STALL_GRAVITY_MULTIPLIER * nearStallFactor;
				double gravityDrivenY = Math.max(AIRCRAFT_NEAR_STALL_MAX_FALL_SPEED, this.getVelocity().y + gravityStrength);
				target = new Vec3d(target.x, MathHelper.lerp(nearStallFactor, target.y, gravityDrivenY), target.z);
			}
		}
		if (surfaced && sustainedFlight && !this.aircraftHasLifted) {
			target = new Vec3d(target.x, AIRCRAFT_LIFTOFF_VELOCITY, target.z); // rotation / take-off
		}

		double preMoveSpeed = target.length();
		double preMoveHorizontalSpeed = Math.sqrt(target.x * target.x + target.z * target.z);
		float preMoveFlightPathAngle = (preMoveHorizontalSpeed < 1.0e-4 && Math.abs(target.y) < 1.0e-4)
				? 0f : (float) Math.toDegrees(Math.atan2(target.y, preMoveHorizontalSpeed));
		this.setVelocity(target);
		this.move(MovementType.SELF, this.getVelocity());
		if (this.ticksSinceModeChange >= AIRCRAFT_MODE_CHANGE_GRACE_TICKS) {
			this.tudursvehiclemod$aircraftCheckBlockCrash(preMoveSpeed, preMoveFlightPathAngle);
			this.tudursvehiclemod$aircraftCheckEntityCrash(preMoveSpeed);
			this.tudursvehiclemod$aircraftCheckLandingImpact(preMoveSpeed, def);
		} else {
			// Still keeps the touchdown-tracking flag itself current, so the grace period doesn't
			// leave a stale "was grounded" reading that fires a landing-impact check retroactively
			// the instant the window ends.
			this.aircraftWasGroundedForImpact = this.isOnGround() || this.isTouchingWater();
		}
		this.wheelRotation += (float) this.getVelocity().horizontalLength() * 20f;
		this.ticksSinceModeChange++;
	}

	// ---- landing gear (flight_style "aircraft" only): the base mod's own gear state, but only
	// while flying, only by hand - always stowed otherwise, and the base mod's auto-deploy on
	// touchdown is undone each tick. ----

	/** The gear state the pilot last chose with the gear key while flying. */
	private boolean pilotGearDeployed;

	private boolean tudursvehiclemod$gearUsable() {
		return this.tudursvehiclemod$usesAircraftFlightControls() && this.tudursvehiclemod$getMovementMode().flies();
	}

	/** The gear key: ignored unless flying an aircraft-style robot. */
	@Override
	public void toggleLandingGear() {
		if (!this.tudursvehiclemod$gearUsable()) {
			return;
		}
		super.toggleLandingGear();
		this.pilotGearDeployed = this.isGearDeployed();
	}

	/** Server, every tick after the base mod's own gear update: holds GEAR_DEPLOYED at the pilot's
	 * own choice in FLIGHT (undoing the base mod's automatic deploy on touchdown), and stowed
	 * everywhere else. */
	private void tudursvehiclemod$enforceLandingGearState() {
		if (!this.tudursvehiclemod$gearUsable()) {
			this.pilotGearDeployed = false;
		}
		if (this.isGearDeployed() != this.pilotGearDeployed) {
			this.tudursvehiclemod$setGearDeployed(this.pilotGearDeployed);
		}
	}

	/** Stowed (1) whenever the gear isn't usable, whatever the underlying eased value - so physics,
	 * HUD and anything else reading it agree with the always-stowed rule. */
	@Override
	public float getLandingGearProgress() {
		return this.tudursvehiclemod$gearUsable() ? super.getLandingGearProgress() : 1f;
	}

	@Override
	public float getLandingGearProgress(float tickDelta) {
		return this.tudursvehiclemod$gearUsable() ? super.getLandingGearProgress(tickDelta) : 1f;
	}

	@Override
	public boolean tudursvehiclemod$supportsLandingGearDisplay() {
		return this.tudursvehiclemod$gearUsable();
	}

	/** The aircraft's own touchdown check (AircraftEntity.onJustLanded): landing with more than 20
	 * degrees of bank costs 15 health and snaps wings level; more than 45 destroys it. Only while the
	 * aircraft flight model is running. */
	@Override
	protected void tudursvehiclemod$onJustLanded() {
		super.tudursvehiclemod$onJustLanded();
		if (!this.aircraftFlightActive || !this.tudursvehiclemod$usesAircraftFlightControls()
				|| !(this.getEntityWorld() instanceof ServerWorld serverWorld)
				|| this.tudursvehiclemod$isDestroyed()) {
			return;
		}
		float absRoll = Math.abs(MathHelper.wrapDegrees(this.getRoll()));
		if (absRoll <= AIRCRAFT_LANDING_ATTITUDE_SAFE_DEGREES) {
			return;
		}
		if (absRoll <= AIRCRAFT_LANDING_ATTITUDE_DESTROY_DEGREES) {
			this.damage(serverWorld, this.getDamageSources().flyIntoWall(), AIRCRAFT_LANDING_ATTITUDE_DAMAGE_AMOUNT);
			this.tudursvehiclemod$setFlightOrientationFromEuler(this.getYaw(), this.getPitch(), 0f);
		} else {
			this.tudursvehiclemod$forceDestroy(serverWorld);
		}
	}

	/** easeTowardsLevelFlight()'s own target: wings level, nose on the horizon, heading held. */
	private Quaternionf tudursvehiclemod$aircraftLevelTarget() {
		if (!this.aircraftLevelYawLocked) {
			this.aircraftLevelTargetYaw = this.getYaw();
			this.aircraftLevelYawLocked = true;
		}
		return new Quaternionf().rotationY((float) Math.toRadians(-this.aircraftLevelTargetYaw));
	}

	private float tudursvehiclemod$aircraftSmoothedSpeedTargetPitch() {
		Vec3d velocity = this.getVelocity();
		double horizontalSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
		float raw = (horizontalSpeed < 1.0e-4 && Math.abs(velocity.y) < 1.0e-4)
				? 0f : (float) Math.toDegrees(Math.atan2(velocity.y, horizontalSpeed));
		if (Math.abs(raw) >= Math.abs(this.aircraftSmoothedSpeedTargetPitch)) {
			this.aircraftSmoothedSpeedTargetPitch = raw;
		} else {
			this.aircraftSmoothedSpeedTargetPitch += (raw - this.aircraftSmoothedSpeedTargetPitch) * AIRCRAFT_LEVEL_OUT_PITCH_BLEND;
		}
		return this.aircraftSmoothedSpeedTargetPitch;
	}

	private float tudursvehiclemod$aircraftClimbTargetMinFraction(float climbFactor) {
		if (climbFactor > AIRCRAFT_STEEP_CLIMB_THRESHOLD) {
			this.aircraftSteepClimbTicks++;
		} else {
			this.aircraftSteepClimbTicks = 0;
		}
		if (this.aircraftSteepClimbTicks <= AIRCRAFT_STEEP_CLIMB_GRACE_TICKS) {
			return AIRCRAFT_CLIMB_TARGET_MIN_FRACTION;
		}
		float decay = MathHelper.clamp((this.aircraftSteepClimbTicks - AIRCRAFT_STEEP_CLIMB_GRACE_TICKS)
				/ (float) AIRCRAFT_STEEP_CLIMB_DECAY_TICKS, 0f, 1f);
		return MathHelper.lerp(decay, AIRCRAFT_CLIMB_TARGET_MIN_FRACTION, 0f);
	}

	private void tudursvehiclemod$aircraftCheckBlockCrash(double preMoveSpeed, float preMoveFlightPathAngle) {
		if (!(this.getEntityWorld() instanceof ServerWorld serverWorld)) {
			return;
		}
		boolean steep = Math.abs(preMoveFlightPathAngle) >= AIRCRAFT_CRASH_ANGLE_THRESHOLD_DEGREES;
		boolean isCrash = this.horizontalCollision || (this.verticalCollision && steep);
		if (!isCrash || preMoveSpeed < AIRCRAFT_CRASH_MIN_SPEED) {
			return;
		}
		this.damage(serverWorld, this.getDamageSources().flyIntoWall(), (float) (preMoveSpeed * AIRCRAFT_CRASH_DAMAGE_PER_SPEED));
		if (this.horizontalCollision) {
			this.cruiseSpeed = 0f;
		}
	}

	private void tudursvehiclemod$aircraftCheckEntityCrash(double preMoveSpeed) {
		if (!(this.getEntityWorld() instanceof ServerWorld serverWorld)
				|| preMoveSpeed < AIRCRAFT_ENTITY_CRASH_MIN_SPEED) {
			return;
		}
		float damage = (float) (preMoveSpeed * AIRCRAFT_ENTITY_CRASH_DAMAGE_PER_SPEED);
		for (Entity other : this.getEntityWorld().getOtherEntities(this, this.getBoundingBox().expand(0.1),
				e -> (e instanceof LivingEntity || e instanceof AbstractVehicleEntity)
						&& e.isAlive() && !this.hasPassenger(e) && !this.tudursvehiclemod$isRecentlyDismounted(e))) {
			boolean touching = other instanceof AbstractVehicleEntity otherVehicle
					? tudursvehiclemod$anyCornerNearMesh(otherVehicle, this) || tudursvehiclemod$anyCornerNearMesh(this, otherVehicle)
					: this.tudursvehiclemod$isPointNearMeshSurface(other.getEntityPos());
			if (touching) {
				other.damage(serverWorld, this.getDamageSources().flyIntoWall(), damage);
				this.damage(serverWorld, this.getDamageSources().flyIntoWall(), damage);
			}
		}
	}

	private static boolean tudursvehiclemod$anyCornerNearMesh(AbstractVehicleEntity cornerSource, AbstractVehicleEntity meshOwner) {
		Box box = cornerSource.getBoundingBox();
		Vec3d[] samples = {
				new Vec3d(box.minX, box.minY, box.minZ), new Vec3d(box.minX, box.minY, box.maxZ),
				new Vec3d(box.minX, box.maxY, box.minZ), new Vec3d(box.minX, box.maxY, box.maxZ),
				new Vec3d(box.maxX, box.minY, box.minZ), new Vec3d(box.maxX, box.minY, box.maxZ),
				new Vec3d(box.maxX, box.maxY, box.minZ), new Vec3d(box.maxX, box.maxY, box.maxZ),
				box.getCenter(),
		};
		for (Vec3d sample : samples) {
			if (meshOwner.tudursvehiclemod$isPointNearMeshSurface(sample)) {
				return true;
			}
		}
		return false;
	}

	private void tudursvehiclemod$aircraftCheckLandingImpact(double preMoveSpeed, VehicleDefinition def) {
		if (!(this.getEntityWorld() instanceof ServerWorld serverWorld)) {
			return;
		}
		boolean groundedNow = this.isOnGround() || this.isTouchingWater();
		boolean justLanded = groundedNow && !this.aircraftWasGroundedForImpact;
		this.aircraftWasGroundedForImpact = groundedNow;
		if (!justLanded) {
			return;
		}
		double threshold = def.maxSpeed() * AIRCRAFT_LANDING_IMPACT_SPEED_FRACTION;
		if (preMoveSpeed < threshold) {
			return;
		}
		this.damage(serverWorld, this.getDamageSources().flyIntoWall(),
				(float) ((preMoveSpeed - threshold) * AIRCRAFT_LANDING_IMPACT_DAMAGE_PER_SPEED));
	}

	/** FLIGHT: world-vertical lift from Space (up) / Shift (down), added on top of thrust - a
	 * submarine-style ascend/descend, not a pitch input, so it moves the robot straight up or down
	 * regardless of where the nose points. Same input sources the base mod's own SubmarineEntity
	 * reads (tudursvehiclemod$isPilotJumping() for Space - player.isJumping() stays false for a
	 * mounted pilot on this version - and the synced isDescending() for Shift), and the same
	 * "no lift on an empty tank" rule this class's own ground climb follows. Both held cancel out.
	 * Nothing held contributes nothing: FLIGHT has no gravity, so the robot just holds altitude. */
	protected double tudursvehiclemod$flightVerticalInput(PlayerEntity pilot) {
		if (this.tudursvehiclemod$isOutOfFuel() || this.tudursvehiclemod$usesAircraftFlightControls()) {
			return 0.0;
		}
		float speed = this.tudursvehiclemod$settings().movement().flightVerticalSpeed();
		double vertical = 0.0;
		if (tudursvehiclemod$isPilotJumping(pilot)) {
			vertical += speed;
		}
		if (this.isDescending()) {
			vertical -= speed;
		}
		return vertical;
	}

	/** Routes to whichever mode's own physics is engaged. */
	@Override
	protected void updateVehicleMovement(VehicleDefinition def) {
		// A sinking wreck's motion is driven by the base mod's own tudursvehiclemod$applySinkingMotion(),
		// which has already run this tick - same stand-down every other vehicle type does.
		if (this.tudursvehiclemod$isSinking()) {
			this.move(MovementType.SELF, this.getVelocity());
			return;
		}
		if (this.tudursvehiclemod$getMovementMode().flies()) {
			this.tudursvehiclemod$updateFlightMovement(def);
			return;
		}
		this.tudursvehiclemod$updateGroundMovement(def);
	}

	/** The distance-driven phase real WHEEL or TRACK parts ride on (if this robot has any -
	 * `wheel_parts`/`crawler_tracks` in the vehicle JSON) - computed the same way, and scaled by the
	 * same {@code wheel_rotation_speed}, that CarEntity uses. Deliberately NOT what the leg gait
	 * itself reads - see tudursvehiclemod$getGaitPhase()'s own doc for why the two need to stay
	 * independent, and this class's own getAnimationPhase()/wheelRotation fields for the shared
	 * distance accumulator both are built from. */
	@Override
	public float getAnimationPhase(float tickDelta) {
		float rotation = this.prevWheelRotation + (this.wheelRotation - this.prevWheelRotation) * tickDelta;
		return rotation * this.getDefinition().wheelRotationSpeed();
	}

	/** Leg-swing phase: the same accumulated distance as getAnimationPhase(), scaled by
	 * gait_cycle_speed instead of wheel_rotation_speed - wheels need ~30, legs ~3. */
	private float tudursvehiclemod$getGaitPhase(float tickDelta) {
		float rotation = this.prevWheelRotation + (this.wheelRotation - this.prevWheelRotation) * tickDelta;
		return rotation * this.tudursvehiclemod$settings().gaitCycleSpeed();
	}

	/** Smooths the step-up's instant position jump into a visible rise - see stepUpVisualOffset. */
	@Override
	public float getRenderYOffset(float tickDelta) {
		return this.prevStepUpVisualOffset + (this.stepUpVisualOffset - this.prevStepUpVisualOffset) * tickDelta;
	}

	@Override
	public float getRenderYOffsetCurrent() {
		return this.stepUpVisualOffset;
	}


	// ------------------------------------------------------------------
	// Mode visuals
	// ------------------------------------------------------------------

	private ModeVisualSettings tudursvehiclemod$modeVisuals() {
		return this.tudursvehiclemod$settings().modeVisuals();
	}

	/** Steps the per-mode weights and every mode-driven part's own progress toward the current
	 * mode's targets. Runs on both sides every tick, from the synced MOVEMENT_MODE alone. */
	private void tudursvehiclemod$updateModeVisuals() {
		ModeVisualSettings visuals = this.tudursvehiclemod$modeVisuals();
		MovementMode mode = this.tudursvehiclemod$getMovementMode();
		float glideTarget = mode == MovementMode.GLIDE ? 1f : 0f;
		float flightTarget = mode == MovementMode.FLIGHT ? 1f : 0f;

		if (!this.modeVisualsInitialized) {
			this.glideWeight = glideTarget;
			this.flightWeight = flightTarget;
			for (ModeVisualSettings.ModePart part : visuals.parts()) {
				this.modePartProgress.put(part.part(), part.targetFor(mode));
			}
			this.modeVisualsInitialized = true;
		}

		float step = Math.max(0f, visuals.transitionSpeed()) / 20f;
		this.prevGlideWeight = this.glideWeight;
		this.prevFlightWeight = this.flightWeight;
		this.glideWeight = tudursvehiclemod$stepToward(this.glideWeight, glideTarget, step);
		this.flightWeight = tudursvehiclemod$stepToward(this.flightWeight, flightTarget, step);

		for (ModeVisualSettings.ModePart part : visuals.parts()) {
			float current = this.modePartProgress.getOrDefault(part.part(), part.targetFor(mode));
			this.prevModePartProgress.put(part.part(), current);
			float partStep = (part.speed() >= 0f ? part.speed() : visuals.transitionSpeed()) / 20f;
			this.modePartProgress.put(part.part(), tudursvehiclemod$stepToward(current, part.targetFor(mode), partStep));
		}
	}

	private static float tudursvehiclemod$stepToward(float current, float target, float maxStep) {
		if (maxStep <= 0f) {
			return target;
		}
		return current < target ? Math.min(target, current + maxStep) : Math.max(target, current - maxStep);
	}

	/** Mode-driven parts (ModeVisualSettings' own {@code parts}) report THIS addon's own progress
	 * here in place of the base mod's own trigger-driven one - so the base mod's renderer, which
	 * reads exactly this method for every toggle_parts entry, draws them with their ordinary
	 * pivot/axis/angle/slide geometry and no rendering code of this addon's own. Every other toggle
	 * part (hatches, canopies, gear...) is untouched. */
	@Override
	public float getTogglePartProgress(String partName) {
		if (!this.tudursvehiclemod$gearUsable() && this.tudursvehiclemod$isGearTogglePart(partName)) {
			return 1f; // stowed
		}
		ModeVisualSettings.ModePart part = this.tudursvehiclemod$modeVisuals().partFor(partName);
		if (part == null) {
			return super.getTogglePartProgress(partName);
		}
		return this.modePartProgress.getOrDefault(partName, part.targetFor(this.tudursvehiclemod$getMovementMode()));
	}

	@Override
	public float getTogglePartProgress(String partName, float tickDelta) {
		if (!this.tudursvehiclemod$gearUsable() && this.tudursvehiclemod$isGearTogglePart(partName)) {
			return 1f; // stowed
		}
		ModeVisualSettings.ModePart part = this.tudursvehiclemod$modeVisuals().partFor(partName);
		if (part == null) {
			return super.getTogglePartProgress(partName, tickDelta);
		}
		float fallback = part.targetFor(this.tudursvehiclemod$getMovementMode());
		float current = this.modePartProgress.getOrDefault(partName, fallback);
		float previous = this.prevModePartProgress.getOrDefault(partName, current);
		return previous + (current - previous) * tickDelta;
	}

	/** A toggle part driven by the base mod's own landing gear (trigger landing_gear /
	 * landing_gear_reversed). Those read the gear's eased value directly rather than its getter, so
	 * the always-stowed rule has to be applied to them here as well. */
	private boolean tudursvehiclemod$isGearTogglePart(String partName) {
		for (TogglePart part : this.getDefinition().toggleParts()) {
			if (part.part().equals(partName)) {
				return "landing_gear".equals(part.trigger()) || "landing_gear_reversed".equals(part.trigger());
			}
		}
		return false;
	}

	/** A channel's own held pose for the current mode blend, one axis (yaw if {@code yaw}, else
	 * pitch) - weighted by the eased GLIDE/FLIGHT weights, so a limb eases into and out of its pose. */
	private float tudursvehiclemod$modePoseAngle(TrackingChannel channel, boolean yaw, float tickProgress) {
		float glideW = MathHelper.lerp(tickProgress, this.prevGlideWeight, this.glideWeight);
		float flightW = MathHelper.lerp(tickProgress, this.prevFlightWeight, this.flightWeight);
		float angle = 0f;
		if (channel.glidePose().isPresent()) {
			TrackingChannel.Pose pose = channel.glidePose().get();
			angle += glideW * (yaw ? pose.yaw() : pose.pitch());
		}
		if (channel.flightPose().isPresent()) {
			TrackingChannel.Pose pose = channel.flightPose().get();
			angle += flightW * (yaw ? pose.yaw() : pose.pitch());
		}
		return angle;
	}

	/** Degrees the body leans in FLIGHT (mode_visuals.flight_body_pitch eased by the flight weight).
	 * Part of the body frame (getBodyOrientation()), not of the attitude (getYaw/getPitch/getRoll), so
	 * thrust, camera and candidate search are unaffected while rendering, seats and weapon mounts lean.
	 * Aiming parts stay on target through tudursvehiclemod$aimInWeaponFrame(). */
	public float tudursvehiclemod$getVisualBodyPitch(float tickDelta) {
		float pitch = this.tudursvehiclemod$modeVisuals().flightBodyPitch();
		if (pitch == 0f) {
			return 0f;
		}
		return MathHelper.lerp(tickDelta, this.prevFlightWeight, this.flightWeight) * pitch;
	}

	/** World-space translation that turns the lean from "about the model origin" (all a rotation
	 * alone can express) into "about body_pitch_pivot": body * (P - Rx(lean) * P). Reported through
	 * the base mod's own tudursvehiclemod$getBodyFrameOffset() hook. */
	public Vec3d tudursvehiclemod$getVisualBodyPitchOffset(float tickDelta) {
		float lean = this.tudursvehiclemod$getVisualBodyPitch(tickDelta);
		if (lean == 0f) {
			return Vec3d.ZERO;
		}
		ModeVisualSettings visuals = this.tudursvehiclemod$modeVisuals();
		float scale = this.getDefinition().scale();
		Vector3f pivot = new Vector3f(0f, visuals.bodyPitchPivotY() * scale, visuals.bodyPitchPivotZ() * scale);
		Vector3f rotated = new Quaternionf().rotationX((float) Math.toRadians(lean)).transform(new Vector3f(pivot));
		Vector3f delta = pivot.sub(rotated);
		this.tudursvehiclemod$getUnleanedBodyOrientation(tickDelta).transform(delta);
		return new Vec3d(delta.x, delta.y, delta.z);
	}

	/** Seats (and so the pilot's own eye/camera position) ride inside the LEANING body rather than
	 * where it would be upright. Built on tudursvehiclemod$getBodyOrientation(float) - flight-aware
	 * and gimbal-free - rather than the base mod's own yaw/pitch/roll composition, and identical to it
	 * on the ground. Only positions change; the pilot's own view DIRECTION is RobotCameraMixin's, from
	 * the un-leaned attitude, and stays as it was. */
	@Override
	protected Quaternionf getSeatRotationInterpolated(float tickDelta) {
		return this.tudursvehiclemod$getBodyOrientation(tickDelta);
	}

	@Override
	protected Quaternionf getSeatRotationCurrent() {
		return this.tudursvehiclemod$getBodyOrientation();
	}

	/** The FLIGHT body lean's pivot correction, handed to the base mod's own body-frame offset hook,
	 * which applies it to the rendered position, seat positions, the pilot's eye/camera position and
	 * weapon spawn positions alike - so the leaning model, its occupants and its muzzles all stay
	 * together. See tudursvehiclemod$getVisualBodyPitchOffset() for the maths. */
	@Override
	public Vec3d tudursvehiclemod$getBodyFrameOffset(float tickDelta) {
		return this.tudursvehiclemod$getVisualBodyPitchOffset(tickDelta);
	}




	// ------------------------------------------------------------------
	// Flight control scheme and per-mode weapon availability
	// ------------------------------------------------------------------

	/** Whether FLIGHT uses aircraft-style controls (movement.flight_style "aircraft") - mouse drives
	 * yaw/pitch, the camera locks to the body (the base mod's own free-look key to look around), no
	 * Space/Shift lift. Read by the client mixins (RobotHeadLookMixin, RobotCameraMixin), the key
	 * input tick, tudursvehiclemod$isEffectiveFreeLook() and tudursvehiclemod$flightVerticalInput(). */
	public boolean tudursvehiclemod$usesAircraftFlightControls() {
		return "aircraft".equalsIgnoreCase(this.tudursvehiclemod$settings().movement().flightStyle());
	}

	/** movement.flight_disabled_seats / flight_only_seats against the current mode. */
	public boolean tudursvehiclemod$isSeatUsableInCurrentMode(int seatIndex) {
		MovementSettings movement = this.tudursvehiclemod$settings().movement();
		boolean flying = this.tudursvehiclemod$getMovementMode().flies();
		if (flying && movement.flightDisabledSeats().contains(seatIndex)) {
			return false;
		}
		return flying || !movement.flightOnlySeats().contains(seatIndex);
	}

	/** Blocks firing weapons the current mode doesn't allow (see isSeatUsableInCurrentMode()), on
	 * top of every check the base mod already makes. */
	@Override
	public boolean tudursvehiclemod$canFireWeapons(java.util.Optional<WeaponDefinition> weapon) {
		if (weapon.isPresent() && !this.tudursvehiclemod$isSeatUsableInCurrentMode(weapon.get().seatIndex())) {
			return false;
		}
		return super.tudursvehiclemod$canFireWeapons(weapon);
	}

	// ------------------------------------------------------------------
	// Weapon readiness (stow_when_inactive)
	// ------------------------------------------------------------------

	/** Eased 0..1 "how raised" per stow_when_inactive channel seat, for joints that follow it
	 * (Joint's own ready_seat) - the channel's own weapon part already slews smoothly through the base
	 * mod's own turret_rotation_speed, so the joints hanging off it ease the same way rather than
	 * snapping. Derived each tick from synced state (locks, selection, pilot) on both sides. */
	private final Map<Integer, Float> readyWeight = new HashMap<>();
	private final Map<Integer, Float> prevReadyWeight = new HashMap<>();

	/** Whether a channel's weapon is currently raised. Always true for ordinary channels (they aim
	 * whenever they have something to aim at, as before). For stow_when_inactive channels: raised
	 * while this channel holds a lock, or while its own weapon is the selected one with a pilot
	 * aboard - so another weapon selected with no lock, or nobody piloting (dismounted; locks are
	 * cleared then too), keeps it lowered. */
	private boolean tudursvehiclemod$isChannelRaised(TrackingChannel channel, int seatIndex) {
		if (!this.tudursvehiclemod$isSeatUsableInCurrentMode(seatIndex)) {
			return false; // e.g. an arm weapon stowed by the FLIGHT transformation
		}
		if (!channel.stowWhenInactive()) {
			return true;
		}
		if (this.tudursvehiclemod$getLockTarget(seatIndex) != null) {
			return true;
		}
		if (this.getControllingPassenger() == null) {
			return false;
		}
		Integer selectedSeat = this.tudursvehiclemod$selectedWeaponSeatIndex();
		return selectedSeat != null && selectedSeat == seatIndex;
	}

	private void tudursvehiclemod$updateReadyWeights() {
		float step = Math.max(0f, this.tudursvehiclemod$modeVisuals().transitionSpeed()) / 20f;
		for (TrackingChannel channel : this.tudursvehiclemod$settings().channels()) {
			if (!channel.stowWhenInactive()) {
				continue;
			}
			int seat = channel.seatIndex();
			float target = this.tudursvehiclemod$isChannelRaised(channel, seat) ? 1f : 0f;
			float current = this.readyWeight.getOrDefault(seat, target);
			this.prevReadyWeight.put(seat, current);
			this.readyWeight.put(seat, tudursvehiclemod$stepToward(current, target, step));
		}
	}

	private float tudursvehiclemod$getReadyWeight(int seat, float tickDelta) {
		Float current = this.readyWeight.get(seat);
		if (current == null) {
			return 0f;
		}
		float previous = this.prevReadyWeight.getOrDefault(seat, current);
		return MathHelper.lerp(tickDelta, previous, current);
	}

	// ------------------------------------------------------------------
	// Terrain following and travel distance (joint drivers)
	// ------------------------------------------------------------------

	/** Signed distance travelled along the robot's own facing, in blocks - drives spin_speed joints
	 * (wheels and rollers), which therefore turn backwards when reversing. Accumulated in ground
	 * movement only; unchanged in flight, so wheels stop turning in the air. */
	private float travelDistance;
	private float prevTravelDistance;

	/** Eased terrain angles for terrain_axis joints - see TerrainFollow. Plain fields, derived each
	 * tick on both sides from the world itself, like the gait factor. */
	private float terrainPitch;
	private float prevTerrainPitch;
	private float terrainRoll;
	private float prevTerrainRoll;

	private void tudursvehiclemod$updateTerrainFollow() {
		this.prevTerrainPitch = this.terrainPitch;
		this.prevTerrainRoll = this.terrainRoll;
		TerrainFollow terrain = this.tudursvehiclemod$settings().terrainFollow();
		if (!terrain.enabled()) {
			this.terrainPitch = 0f;
			this.terrainRoll = 0f;
			return;
		}
		float targetPitch = 0f;
		float targetRoll = 0f;
		boolean grounded = !this.tudursvehiclemod$getMovementMode().flies() && this.ticksSinceGrounded <= GROUNDED_GRACE_TICKS;
		if (grounded) {
			float scale = this.getDefinition().scale();
			double yawRad = Math.toRadians(this.getYaw());
			double forwardX = -Math.sin(yawRad);
			double forwardZ = Math.cos(yawRad);
			double rightX = Math.cos(yawRad); // vehicle-local +X in world space
			double rightZ = Math.sin(yawRad);
			double front = terrain.frontZ() * scale;
			double back = terrain.backZ() * scale;
			double side = terrain.halfWidth() * scale;
			if (terrain.maxPitch() > 0f && front != back) {
				double hFront = this.tudursvehiclemod$groundHeightAt(this.getX() + forwardX * front, this.getZ() + forwardZ * front);
				double hBack = this.tudursvehiclemod$groundHeightAt(this.getX() + forwardX * back, this.getZ() + forwardZ * back);
				targetPitch = (float) Math.toDegrees(Math.atan2(hBack - hFront, front - back));
				targetPitch = MathHelper.clamp(targetPitch, -terrain.maxPitch(), terrain.maxPitch());
			}
			if (terrain.maxRoll() > 0f && side > 0.0) {
				double hPlusX = this.tudursvehiclemod$groundHeightAt(this.getX() + rightX * side, this.getZ() + rightZ * side);
				double hMinusX = this.tudursvehiclemod$groundHeightAt(this.getX() - rightX * side, this.getZ() - rightZ * side);
				targetRoll = (float) Math.toDegrees(Math.atan2(hPlusX - hMinusX, 2.0 * side));
				targetRoll = MathHelper.clamp(targetRoll, -terrain.maxRoll(), terrain.maxRoll());
			}
		}
		float response = MathHelper.clamp(terrain.response(), 0f, 1f);
		this.terrainPitch += (targetPitch - this.terrainPitch) * response;
		this.terrainRoll += (targetRoll - this.terrainRoll) * response;
	}

	/** Top of the highest collidable block surface in the column at (x, z), searching from a little
	 * above step height down to three blocks below the robot's feet. Falls back to "three blocks
	 * down" over a gap, which the max_pitch/max_roll clamps then limit. */
	private double tudursvehiclemod$groundHeightAt(double x, double z) {
		World world = this.getEntityWorld();
		double feet = this.getY();
		int top = (int) Math.floor(feet + this.getDefinition().stepHeight() + 0.5);
		int bottom = (int) Math.floor(feet - 3.0);
		BlockPos.Mutable pos = new BlockPos.Mutable();
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		for (int y = top; y >= bottom; y--) {
			pos.set(bx, y, bz);
			VoxelShape shape = world.getBlockState(pos).getCollisionShape(world, pos);
			if (!shape.isEmpty()) {
				return y + shape.getMax(Direction.Axis.Y);
			}
		}
		return feet - 3.0;
	}

	// ------------------------------------------------------------------
	// Joints (multi-segment limbs)
	// ------------------------------------------------------------------

	/** Joints as the base mod's per-group render transforms: forward kinematics, each matrix already
	 * holding its whole chain (parent * T(pivot) * R(axis, angle) * T(-pivot)), resolved recursively
	 * with memoisation. A parent may be another joint or a weapon part. */
	@Override
	public Map<String, Matrix4f> tudursvehiclemod$getCustomPartTransforms(float tickDelta) {
		List<Joint> joints = this.tudursvehiclemod$settings().joints();
		if (joints.isEmpty()) {
			return Map.of();
		}
		Map<String, Joint> byPart = new HashMap<>();
		for (Joint joint : joints) {
			byPart.put(joint.part(), joint);
		}
		float gaitPhase = this.tudursvehiclemod$getGaitPhase(tickDelta);
		float gaitFactor = this.tudursvehiclemod$getGaitFactor(tickDelta);
		float glideW = MathHelper.lerp(tickDelta, this.prevGlideWeight, this.glideWeight);
		float flightW = MathHelper.lerp(tickDelta, this.prevFlightWeight, this.flightWeight);

		this.tickDeltaForJoints = tickDelta;
		Map<String, Matrix4f> resolved = new HashMap<>();
		for (Joint joint : joints) {
			this.tudursvehiclemod$resolveJoint(joint, byPart, resolved, new HashSet<>(),
					gaitPhase, gaitFactor, glideW, flightW);
		}
		return resolved;
	}

	/** This frame's tickDelta, for helpers deep inside the joint recursion. Render-thread only. */
	private float tickDeltaForJoints;

	/** A weapon part's own model-space transform, built exactly the way VehicleEntityRenderer draws
	 * it - optional parent stage about the parent pivot, own rotation and recoil about its own pivot -
	 * so a joint hung off it (a forearm on an aiming arm) follows its aim, slewing and recoil. When
	 * Null if no weapon part has that name. (The FLIGHT body lean needs no special handling here: it is
	 * part of the body frame every part is drawn in, and aim corrections already live in the part's
	 * own rotation - see tudursvehiclemod$aimInWeaponFrame().) */
	private Matrix4f tudursvehiclemod$weaponPartMatrix(String partName, float tickDelta) {
		WeaponPart part = null;
		for (WeaponPart candidate : this.getDefinition().weaponParts()) {
			if (candidate.part().equals(partName)) {
				part = candidate;
				break;
			}
		}
		if (part == null) {
			return null;
		}
		Matrix4f matrix = new Matrix4f();
		Quaternionf own = this.tudursvehiclemod$getWeaponPartOwnRotation(part, tickDelta);
		if (part.childInfo().isPresent()) {
			WeaponPart.ChildInfo childInfo = part.childInfo().get();
			Quaternionf parentRotation = this.tudursvehiclemod$getWeaponPartParentRotation(part, tickDelta);
			matrix.translate((float) childInfo.parentPivotX(), (float) childInfo.parentPivotY(), (float) childInfo.parentPivotZ())
					.rotate(parentRotation)
					.translate((float) -childInfo.parentPivotX(), (float) -childInfo.parentPivotY(), (float) -childInfo.parentPivotZ());
		}
		float recoil = this.tudursvehiclemod$getWeaponPartRecoilOffset(part.part(), tickDelta);
		matrix.translate((float) part.pivotX(), (float) part.pivotY(), (float) part.pivotZ())
				.rotate(own)
				.translate(0f, 0f, -recoil)
				.translate((float) -part.pivotX(), (float) -part.pivotY(), (float) -part.pivotZ());
		return matrix;
	}

	private Matrix4f tudursvehiclemod$resolveJoint(Joint joint, Map<String, Joint> byPart,
			Map<String, Matrix4f> resolved, Set<String> visiting,
			float gaitPhase, float gaitFactor, float glideW, float flightW) {
		Matrix4f done = resolved.get(joint.part());
		if (done != null) {
			return done;
		}
		visiting.add(joint.part());
		Matrix4f parentMatrix = new Matrix4f();
		if (joint.parent().isPresent()) {
			String parentName = joint.parent().get();
			Joint parent = byPart.get(parentName);
			if (parent != null && !visiting.contains(parent.part())) {
				parentMatrix = this.tudursvehiclemod$resolveJoint(parent, byPart, resolved, visiting,
						gaitPhase, gaitFactor, glideW, flightW);
			} else if (parent == null) {
				Matrix4f weaponMatrix = this.tudursvehiclemod$weaponPartMatrix(parentName, tickDeltaForJoints);
				if (weaponMatrix != null) {
					parentMatrix = weaponMatrix;
				}
			}
		}

		float angle = joint.restAngle() + glideW * joint.glidePose() + flightW * joint.flightPose();
		if (joint.gait().isPresent()) {
			Joint.Gait gait = joint.gait().get();
			float phase = gaitPhase * gait.cycleScale() + gait.phase();
			angle += (float) (Math.sin(Math.toRadians(phase)) * gait.amplitude() * gaitFactor);
		}
		if (joint.readySeat().isPresent()) {
			float ready = this.tudursvehiclemod$getReadyWeight(joint.readySeat().get(), tickDeltaForJoints);
			angle += (joint.readyAngle() - angle) * ready;
		}
		angle = MathHelper.clamp(angle, Math.min(joint.minAngle(), joint.maxAngle()),
				Math.max(joint.minAngle(), joint.maxAngle()));
		// Unclamped additions: terrain conformance (already limited by terrain_follow's own
		// max_pitch/max_roll) and free spin with travel.
		if (joint.terrainAxis().isPresent()) {
			float td = this.tickDeltaForJoints;
			String axisName = joint.terrainAxis().get();
			if ("pitch".equalsIgnoreCase(axisName)) {
				angle += MathHelper.lerp(td, this.prevTerrainPitch, this.terrainPitch);
			} else if ("roll".equalsIgnoreCase(axisName)) {
				angle += MathHelper.lerp(td, this.prevTerrainRoll, this.terrainRoll);
			}
		}
		if (joint.spinSpeed() != 0f) {
			float distance = MathHelper.lerp(this.tickDeltaForJoints, this.prevTravelDistance, this.travelDistance);
			angle += (distance * joint.spinSpeed()) % 360f;
		}

		Vector3f axis = new Vector3f(joint.axisX(), joint.axisY(), joint.axisZ());
		if (axis.lengthSquared() < 1.0e-8f) {
			axis.set(1f, 0f, 0f);
		}
		axis.normalize();
		Matrix4f matrix = new Matrix4f(parentMatrix)
				.translate(joint.pivotX(), joint.pivotY(), joint.pivotZ())
				.rotate((float) Math.toRadians(angle), axis)
				.translate(-joint.pivotX(), -joint.pivotY(), -joint.pivotZ());
		resolved.put(joint.part(), matrix);
		visiting.remove(joint.part());
		return matrix;
	}

	// ------------------------------------------------------------------
	// Gait
	// ------------------------------------------------------------------

	/** 0..1 eased "how much the legs swing" from horizontal speed (swing_min_speed ->
	 * swing_full_speed), so a standing robot stands still. */
	private void tudursvehiclemod$updateGaitFactor() {
		RobotSettings settings = this.tudursvehiclemod$settings();
		Vec3d velocity = this.getVelocity();
		double speed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);

		float min = settings.swingMinSpeed();
		float full = Math.max(settings.swingFullSpeed(), min + 1.0e-4f);
		// Only WALK has a gait; GLIDE and FLIGHT hold the limbs still (eased, so they don't snap).
		float target = this.tudursvehiclemod$getMovementMode().walks()
				? (float) MathHelper.clamp((speed - min) / (full - min), 0.0, 1.0)
				: 0f;

		this.prevGaitFactor = this.gaitFactor;
		this.gaitFactor = MathHelper.lerp(MathHelper.clamp(settings.swingResponse(), 0.0f, 1.0f),
				this.gaitFactor, target);
	}

	/** Interpolated stride strength for rendering. */
	public float tudursvehiclemod$getGaitFactor(float tickProgress) {
		return MathHelper.lerp(MathHelper.clamp(tickProgress, 0.0f, 1.0f), this.prevGaitFactor, this.gaitFactor);
	}

	// ------------------------------------------------------------------
	// Part driving - the two overrides the whole system hangs off
	// ------------------------------------------------------------------

	/** Re-points a channelled weapon part: a world-derived aim is corrected into the weapon frame while
	 * flying (tudursvehiclemod$aimInWeaponFrame()); otherwise the channel's own vehicle-relative angle;
	 * parts with no channel, or channels with nothing to say, defer to the base mod. */
	@Override
	public float getWeaponAimYaw(int seatIndex, boolean pilotFallback, WeaponAimRange aimRange, float tickProgress) {
		float[] corrected = this.tudursvehiclemod$aimInWeaponFrame(seatIndex, pilotFallback, tickProgress);
		if (corrected != null) {
			return aimRange == null ? corrected[0] : (float) tudursvehiclemod$clampedYawOffsetFromDefault(corrected[0], aimRange);
		}
		Float relative = this.tudursvehiclemod$channelYaw(seatIndex, tickProgress);
		if (relative == null) {
			return super.getWeaponAimYaw(seatIndex, pilotFallback, aimRange, tickProgress);
		}
		if (aimRange == null) {
			return relative;
		}
		return (float) tudursvehiclemod$clampedYawOffsetFromDefault(relative, aimRange);
	}

	/** Pitch counterpart. VEHICLE-RELATIVE, exactly like yaw (see getWeaponAimYaw()'s own doc for
	 * why - tryFireWeapon() and the renderer both add the vehicle's own current pitch on top of
	 * whatever this returns, per WeaponPart's own aim_range doc: "already vehicle-relative, same
	 * convention DefaultYaw/getWeaponAimYaw() itself uses"), and clamped to
	 * {@code [min_pitch, max_pitch]} when an aim range is present. */
	@Override
	public float getWeaponAimPitch(int seatIndex, boolean pilotFallback, WeaponAimRange aimRange, float tickProgress) {
		float[] corrected = this.tudursvehiclemod$aimInWeaponFrame(seatIndex, pilotFallback, tickProgress);
		Float pitch = corrected != null ? Float.valueOf(corrected[1]) : this.tudursvehiclemod$channelPitch(seatIndex, tickProgress);
		if (pitch == null) {
			return super.getWeaponAimPitch(seatIndex, pilotFallback, aimRange, tickProgress);
		}
		if (aimRange == null) {
			return pitch;
		}
		double lower = Math.min(aimRange.minPitch(), aimRange.maxPitch());
		double upper = Math.max(aimRange.minPitch(), aimRange.maxPitch());
		return (float) MathHelper.clamp(pitch, lower, upper);
	}

	/** Null means "this isn't a channel, or this channel has nothing to say right now" - the caller
	 * then defers to the base mod. */
	/** A channel's own WORLD aim {yaw, pitch} (degrees, Minecraft convention) at its current target -
	 * the same yaw channelYaw() derives and the same, ballistic-compensated pitch channelPitch()
	 * derives, before either is made vehicle-relative. Null when the channel has no target. */
	private float[] tudursvehiclemod$channelWorldAim(TrackingChannel channel, int seatIndex) {
		Vec3d toTarget = this.tudursvehiclemod$targetVectorFor(channel, seatIndex);
		if (toTarget == null) {
			return null;
		}
		float worldYaw = (float) Math.toDegrees(Math.atan2(toTarget.z, toTarget.x)) - 90.0f;
		double horizontal = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);
		float worldPitch = (float) (-Math.toDegrees(Math.atan2(toTarget.y, horizontal)));
		WeaponDefinition weapon = this.tudursvehiclemod$weaponForSeat(seatIndex);
		if (weapon != null && this.tudursvehiclemod$settings().ballisticCompensation()) {
			Float compensated = this.tudursvehiclemod$computeBallisticPitch(horizontal, toTarget.y, weapon);
			if (compensated != null) {
				worldPitch = compensated;
			}
		}
		return new float[]{worldYaw, worldPitch};
	}

	/** While flying (or while the lean is still easing out): converts a WORLD aim direction (channel
	 * target or pilot view) into the frame weapon parts are mounted and fired in, by inverse-rotating it
	 * through getBodyOrientation(tickProgress) and reading yaw/pitch off the local vector. Correcting the
	 * TARGET keeps slewing, rendering, recoil and fire direction consistent. Null = no correction: on the
	 * ground, for lean_follow_seats, or for body-relative poses (swing / mode pose / stowed). */
	private float[] tudursvehiclemod$aimInWeaponFrame(int seatIndex, boolean pilotFallback, float tickProgress) {
		boolean attitudeActive = this.tudursvehiclemod$getMovementMode().flies()
				|| this.tudursvehiclemod$getVisualBodyPitch(tickProgress) != 0f;
		if (!attitudeActive) {
			return null;
		}
		if (this.tudursvehiclemod$modeVisuals().leanFollowSeats().contains(seatIndex)) {
			return null;
		}
		Vec3d world = null;
		TrackingChannel channel = this.tudursvehiclemod$settings().channelFor(seatIndex);
		if (channel != null) {
			float[] worldAim = this.tudursvehiclemod$channelWorldAim(channel, seatIndex);
			if (worldAim != null) {
				world = Vec3d.fromPolar(worldAim[1], worldAim[0]);
			} else if (this.tudursvehiclemod$channelYaw(seatIndex, tickProgress) != null
					|| this.tudursvehiclemod$channelPitch(seatIndex, tickProgress) != null) {
				return null; // body-relative (swing / pose / stowed): no correction
			}
		}
		if (world == null) {
			// Follows the pilot's own view (the base mod's pilot fallback, or a channel deferring to
			// it). Only the actual pilot - any other occupant keeps the base mod's own handling.
			Entity occupant = this.tudursvehiclemod$getSeatOccupant(seatIndex);
			if (occupant == null && pilotFallback) {
				occupant = this.getControllingPassenger();
			}
			if (!(occupant instanceof PlayerEntity pilot) || occupant != this.getControllingPassenger()) {
				return null;
			}
			world = this.tudursvehiclemod$effectivePilotViewDirection(pilot, tickProgress);
		}
		Vector3f local = new Vector3f((float) world.x, (float) world.y, (float) world.z);
		this.tudursvehiclemod$getBodyOrientation(tickProgress).conjugate().transform(local);
		float yaw = (float) Math.toDegrees(Math.atan2(-local.x, local.z));
		float pitch = (float) -Math.toDegrees(Math.asin(MathHelper.clamp(local.y, -1f, 1f)));
		return new float[]{yaw, pitch};
	}

	private Float tudursvehiclemod$channelYaw(int seatIndex, float tickProgress) {
		TrackingChannel channel = this.tudursvehiclemod$settings().channelFor(seatIndex);
		if (channel == null) {
			return null;
		}
		Vec3d toTarget = this.tudursvehiclemod$targetVectorFor(channel, seatIndex);
		if (toTarget != null) {
			float worldYaw = (float) Math.toDegrees(Math.atan2(toTarget.z, toTarget.x)) - 90.0f;
			return MathHelper.wrapDegrees(worldYaw - this.getYaw(tickProgress));
		}
		// SWING drives pitch only - a limb swings fore/aft, it doesn't yaw. Returning the resting
		// 0 (straight ahead) rather than null keeps a swing channel from drifting sideways with
		// the pilot's own view.
		if (!this.tudursvehiclemod$isSeatUsableInCurrentMode(seatIndex)) {
			// Unusable in this mode (flight_disabled_seats / flight_only_seats): always the lowered
			// pose - never the pilot-view fallback, whatever kind of channel this is.
			return this.tudursvehiclemod$modePoseAngle(channel, true, tickProgress)
					+ channel.stowPose().map(TrackingChannel.Pose::yaw).orElse(0f);
		}
		if (channel.stowWhenInactive()) {
			if (this.tudursvehiclemod$isChannelRaised(channel, seatIndex)) {
				return null; // raised with nothing to aim at: along the pilot's own view
			}
			return this.tudursvehiclemod$modePoseAngle(channel, true, tickProgress) // lowered
					+ channel.stowPose().map(TrackingChannel.Pose::yaw).orElse(0f);
		}
		if (channel.swing() || channel.hasModePose()) {
			return this.tudursvehiclemod$modePoseAngle(channel, true, tickProgress);
		}
		return null;
	}

	/** A channel's vehicle-relative pitch: the (ballistically compensated) target pitch minus the
	 * body's own pitch while aiming; otherwise swing + stow + mode pose; null to defer to the base mod. */
	private Float tudursvehiclemod$channelPitch(int seatIndex, float tickProgress) {
		TrackingChannel channel = this.tudursvehiclemod$settings().channelFor(seatIndex);
		if (channel == null) {
			return null;
		}
		Vec3d toTarget = this.tudursvehiclemod$targetVectorFor(channel, seatIndex);
		if (toTarget != null) {
			double horizontal = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);
			// Negated so up is negative, matching Minecraft's own pitch convention - which is the
			// convention the base mod's own occupant.getPitch() path already feeds in.
			float absoluteWorldPitch = (float) (-Math.toDegrees(Math.atan2(toTarget.y, horizontal)));

			WeaponDefinition weapon = this.tudursvehiclemod$weaponForSeat(seatIndex);
			if (weapon != null && this.tudursvehiclemod$settings().ballisticCompensation()) {
				Float compensated = this.tudursvehiclemod$computeBallisticPitch(horizontal, toTarget.y, weapon);
				if (compensated != null) {
					absoluteWorldPitch = compensated;
				}
			}
			return absoluteWorldPitch - this.getPitch(tickProgress);
		}
		if (channel.stowWhenInactive() && this.tudursvehiclemod$isChannelRaised(channel, seatIndex)) {
			return null; // raised with nothing to aim at: along the pilot's own view
		}
		boolean usable = this.tudursvehiclemod$isSeatUsableInCurrentMode(seatIndex);
		if (!usable || channel.stowWhenInactive() || channel.swing() || channel.hasModePose()) {
			// A lowered stow_when_inactive channel (or one unusable in this mode) ALWAYS lands here - even with no swing or pose it
			// holds its rest angle (0) rather than falling back to the pilot's view, which is what
			// would raise the weapon.
			float swing = channel.swing() ? this.tudursvehiclemod$swingAngle(channel, tickProgress) : 0f;
			float stow = (channel.stowWhenInactive() || !usable) ? channel.stowPose().map(TrackingChannel.Pose::pitch).orElse(0f) : 0f;
			return swing + stow + this.tudursvehiclemod$modePoseAngle(channel, false, tickProgress);
		}
		return null;
	}

	/** Where this channel should aim, as a world vector, or null: its own weapon's lock first; then, for
	 * mode assist, the candidate; nothing in manual mode, when lowered, or for mode lock without a lock. */
	private Vec3d tudursvehiclemod$targetVectorFor(TrackingChannel channel, int seatIndex) {
		if (!channel.aims() || this.isManualMode()) {
			return null;
		}
		if (!this.tudursvehiclemod$isChannelRaised(channel, seatIndex)) {
			return null;
		}
		Entity ownLock = this.tudursvehiclemod$getLockTarget(seatIndex);
		Entity target = ownLock != null ? ownLock
				: channel.mode() == TrackingChannel.Mode.ASSIST ? this.tudursvehiclemod$getCandidate()
				: null;
		return this.tudursvehiclemod$vectorTo(target);
	}

	/** From this robot's own sensor height to target's own centre, or null if target is null.
	 *
	 * <p>Measured from {@code lock_origin_y} rather than the entity origin: the origin sits at the
	 * robot's feet, so a target twenty blocks away would have the arms visibly angled down at it. */
	private Vec3d tudursvehiclemod$vectorTo(Entity target) {
		if (target == null) {
			return null;
		}
		Vec3d origin = this.getEntityPos().add(0, this.tudursvehiclemod$settings().lockOriginY(), 0);
		Vec3d aimPoint = target.getEntityPos().add(0, target.getHeight() * 0.5, 0);
		return aimPoint.subtract(origin);
	}

	/** The first weapon (in definition order) mounted at seatIndex, or null if none - used to look
	 * up ballistic stats and, for the client renderer, a display name for the lock label. A channel
	 * with no matching weapon (a pure sensor/camera part) simply gets no ballistic compensation and
	 * no label contribution. */
	public WeaponDefinition tudursvehiclemod$weaponForSeat(int seatIndex) {
		for (WeaponDefinition weapon : this.getDefinition().weapons()) {
			if (weapon.seatIndex() == seatIndex) {
				return weapon;
			}
		}
		return null;
	}

	/** Walking swing for a channel: a sine of the gait phase, scaled by the gait factor. */
	private float tudursvehiclemod$swingAngle(TrackingChannel channel, float tickProgress) {
		float gaitPhase = this.tudursvehiclemod$getGaitPhase(tickProgress) * channel.cycleScale() + channel.phase();
		float amplitude = channel.amplitude() * this.tudursvehiclemod$getGaitFactor(tickProgress);
		return (float) (Math.sin(Math.toRadians(gaitPhase)) * amplitude);
	}

	// ------------------------------------------------------------------
	// Ballistic compensation
	// ------------------------------------------------------------------

	/** Launch pitch that lands the weapon's projectile on the target, by bisection over pitch with a
	 * per-tick simulation of the base mod's projectile motion (velocity, gravity, air drag). Null when
	 * the weapon has no gravity (a straight line), or the target is out of reach. */
	private Float tudursvehiclemod$computeBallisticPitch(double horizontalDistance, double heightDifference, WeaponDefinition weapon) {
		float gravity = weapon.gravity();
		float muzzleVelocity = weapon.velocity();
		if (gravity <= 0f || muzzleVelocity <= 0f || horizontalDistance < 1.0e-3) {
			return null;
		}

		double straightLinePitch = Math.toDegrees(Math.atan2(heightDifference, horizontalDistance));
		double maxExtra = this.tudursvehiclemod$settings().maxBallisticCompensationDegrees();

		double low = 0.0;
		double high = Math.max(0.0, Math.min(maxExtra, 89.0 - straightLinePitch));
		if (high <= 0.0) {
			return null;
		}

		// heightAt(extra) - heightDifference is negative at extra=0 (gravity has already pulled the
		// straight-line shot below the target by definition) and increases with extra - monotonic
		// over this span for any realistic direct-fire distance, which is exactly what binary search
		// needs. If even the maximum extra elevation still doesn't reach heightDifference at this
		// distance, the target is simply beyond this weapon's reach at that range; fall back to
		// the plain straight-line pitch rather than overcorrecting into an unrelated high lob.
		double highError = tudursvehiclemod$simulatedHeightAtDistance(
				straightLinePitch + high, horizontalDistance, muzzleVelocity, gravity) - heightDifference;
		if (highError < 0.0) {
			return null;
		}

		for (int i = 0; i < BALLISTIC_SEARCH_ITERATIONS; i++) {
			double mid = (low + high) / 2.0;
			double simulatedHeight = tudursvehiclemod$simulatedHeightAtDistance(
					straightLinePitch + mid, horizontalDistance, muzzleVelocity, gravity);
			if (simulatedHeight < heightDifference) {
				low = mid;
			} else {
				high = mid;
			}
		}
		return (float) -(straightLinePitch + high);
	}

	/** Height of a simulated projectile when it reaches horizontalDistance (or the tick it starts falling
	 * back toward the launch height), matching the base mod's per-tick integration. */
	private static double tudursvehiclemod$simulatedHeightAtDistance(
			double pitchDegrees, double targetHorizontalDistance, float muzzleVelocity, float gravity) {
		double pitchRad = Math.toRadians(pitchDegrees);
		double horizontalVelocity = muzzleVelocity * Math.cos(pitchRad);
		double verticalVelocity = muzzleVelocity * Math.sin(pitchRad);
		double horizontalDistance = 0.0;
		double height = 0.0;

		// A generous, fixed cap rather than MortarMarkerRenderer's own dynamic estimate: this only
		// ever needs to cover THIS weapon's own realistic direct-fire distances (bounded by
		// lock_range), not an arbitrary lob, so a flat safety ceiling is simpler and plenty.
		for (int tick = 0; tick < 1200; tick++) {
			double nextHorizontal = horizontalDistance + horizontalVelocity;
			if (nextHorizontal >= targetHorizontalDistance) {
				double fraction = horizontalVelocity <= 0.0 ? 0.0
						: (targetHorizontalDistance - horizontalDistance) / horizontalVelocity;
				return height + verticalVelocity * fraction;
			}
			horizontalVelocity *= PROJECTILE_AIR_DRAG_PER_TICK;
			verticalVelocity = verticalVelocity * PROJECTILE_AIR_DRAG_PER_TICK - gravity;
			horizontalDistance = nextHorizontal;
			height += verticalVelocity;
			if (verticalVelocity < 0.0 && height < -256.0) {
				break;
			}
		}
		return -1.0e6;
	}

	/** The base mod's own private clamp, reproduced here because an override has to agree with it
	 * exactly: an offset from {@code default_yaw}, wrapped to +-180 first so the shortest way round
	 * is what gets clamped, then held inside {@code [min_yaw, max_yaw]}. */
	private static double tudursvehiclemod$clampedYawOffsetFromDefault(double rawYaw, WeaponAimRange aimRange) {
		float relativeToDefault = MathHelper.wrapDegrees((float) (rawYaw - aimRange.defaultYaw()));
		return MathHelper.clamp(relativeToDefault, aimRange.minYaw(), aimRange.maxYaw());
	}
}
