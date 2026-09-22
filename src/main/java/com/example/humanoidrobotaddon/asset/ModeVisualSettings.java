package com.example.humanoidrobotaddon.asset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/** The {@code "mode_visuals"} object: purely visual reactions to the mode - mode-driven
 * toggle_parts progress (this addon overrides getTogglePartProgress() for the listed parts; the
 * base mod keeps the geometry and rendering), the FLIGHT body lean and its pivot, and which weapon
 * seats follow the lean uncorrected. See README.md for the schema. */
public record ModeVisualSettings(
		/** Default easing rate, in progress per second (the base mod's own TogglePart convention),
		 * shared by the body-pitch lean, channel mode poses, and any part without its own speed. */
		float transitionSpeed,
		/** Degrees the whole body leans while in FLIGHT (positive = nose down / forward, Minecraft's
		 * own pitch sign). Visual only - see this record's own doc. */
		float flightBodyPitch,
		/** Point (model units, vehicle-local) the lean pivots around. Around the feet (the model
		 * origin, the default) a tall robot would swing its whole upper body far out over empty air,
		 * away from its own hitbox; around its middle it stays centred. */
		float bodyPitchPivotY,
		float bodyPitchPivotZ,
		List<ModePart> parts,
		/** seat_index values of weapons / weapon parts that should move WITH the body's attitude
		 * (pitch, roll, flight lean) instead of having their aim corrected to stay on target - for
		 * torso-mounted weapons and the like. Their aim then stays relative to the body, so both the
		 * part and its fire direction tilt with it. Empty = every aiming weapon is corrected. */
		List<Integer> leanFollowSeats
) {

	/** One mode-driven toggle part: its target progress in each mode. */
	public record ModePart(String part, float walk, float glide, float flight, float speed) {
		public static final Codec<ModePart> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.STRING.fieldOf("part").forGetter(ModePart::part),
				Codec.FLOAT.optionalFieldOf("walk", 0f).forGetter(ModePart::walk),
				Codec.FLOAT.optionalFieldOf("glide", 0f).forGetter(ModePart::glide),
				Codec.FLOAT.optionalFieldOf("flight", 0f).forGetter(ModePart::flight),
				// Negative = use the shared transition_speed.
				Codec.FLOAT.optionalFieldOf("speed", -1f).forGetter(ModePart::speed)
		).apply(instance, ModePart::new));

		public float targetFor(MovementMode mode) {
			return switch (mode) {
				case WALK -> this.walk;
				case GLIDE -> this.glide;
				case FLIGHT -> this.flight;
			};
		}
	}

	public static final ModeVisualSettings DEFAULT = new ModeVisualSettings(2.0f, 0f, 0f, 0f, List.of(), List.of());

	public static final Codec<ModeVisualSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.FLOAT.optionalFieldOf("transition_speed", 2.0f).forGetter(ModeVisualSettings::transitionSpeed),
			Codec.FLOAT.optionalFieldOf("flight_body_pitch", 0f).forGetter(ModeVisualSettings::flightBodyPitch),
			Codec.FLOAT.optionalFieldOf("body_pitch_pivot_y", 0f).forGetter(ModeVisualSettings::bodyPitchPivotY),
			Codec.FLOAT.optionalFieldOf("body_pitch_pivot_z", 0f).forGetter(ModeVisualSettings::bodyPitchPivotZ),
			ModePart.CODEC.listOf().optionalFieldOf("parts", List.of()).forGetter(ModeVisualSettings::parts),
			Codec.INT.listOf().optionalFieldOf("lean_follow_seats", List.of()).forGetter(ModeVisualSettings::leanFollowSeats)
	).apply(instance, ModeVisualSettings::new));

	public ModePart partFor(String partName) {
		for (ModePart part : this.parts) {
			if (part.part().equals(partName)) {
				return part;
			}
		}
		return null;
	}
}
