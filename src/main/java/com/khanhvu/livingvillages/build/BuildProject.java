package com.khanhvu.livingvillages.build;

import com.khanhvu.livingvillages.village.VillageType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A house being built. Only the inputs of {@link BuildOrder} and the progress are saved; the step list is
 * rebuilt from them after a reload, so the saved {@code stepIndex} points at the same step again.
 */
public class BuildProject {
	private final ResourceLocation templateId;
	private final BlockPos origin;
	private final Rotation rotation;
	private final BoundingBox footprint;
	private int stepIndex;
	private int skippedCount;
	@Nullable
	private UUID builderUuid;

	// Runtime state, not saved.
	@Nullable
	private HouseTemplate house;
	@Nullable
	private List<BuildStep> steps;
	private int totalBlocks;
	@Nullable
	private BlockPlacer placer;
	private double buildPoints;
	private int ticksOutOfReach;
	/** Builders that got stuck on this project; not chosen again for it. */
	private final Set<UUID> excludedBuilders = new HashSet<>();

	public BuildProject(ResourceLocation templateId, BlockPos origin, Rotation rotation, BoundingBox footprint) {
		this.templateId = templateId;
		this.origin = origin.immutable();
		this.rotation = rotation;
		this.footprint = footprint;
	}

	public static BuildProject create(HouseTemplate house, BuildSite site) {
		return new BuildProject(house.id(), site.origin(), site.rotation(), site.footprint());
	}

	public ResourceLocation getTemplateId() {
		return templateId;
	}

	public BlockPos getOrigin() {
		return origin;
	}

	public Rotation getRotation() {
		return rotation;
	}

	/** World box of the house, reserved while building and saved as the plot when finished. */
	public BoundingBox getFootprint() {
		return footprint;
	}

	public int getStepIndex() {
		return stepIndex;
	}

	public int getSkippedCount() {
		return skippedCount;
	}

	@Nullable
	public UUID getBuilderUuid() {
		return builderUuid;
	}

	public void setBuilderUuid(@Nullable UUID builderUuid) {
		this.builderUuid = builderUuid;
		this.ticksOutOfReach = 0;
	}

	/**
	 * Rebuilds the step list if needed. Returns false when the template no longer exists (datapack or mod removed),
	 * in which case the project cannot continue.
	 */
	public boolean ensureSteps(ServerLevel level, VillageType type) {
		if (steps != null) {
			return true;
		}
		HouseTemplate found = HouseTemplateProvider.findById(level, type, templateId);
		if (found == null) {
			return false;
		}
		house = found;
		steps = BuildOrder.create(found, new BuildSite(origin, rotation, footprint), type.getFoundationBlock().defaultBlockState());
		totalBlocks = 0;
		for (BuildStep step : steps) {
			if (step instanceof BuildStep.Place place && !place.belowFloor()) {
				totalBlocks += place.blocks().size();
			}
		}
		return true;
	}

	/** Step list; {@link #ensureSteps} must have succeeded. */
	public List<BuildStep> getSteps() {
		return steps;
	}

	@Nullable
	public BuildStep currentStep() {
		return steps != null && stepIndex < steps.size() ? steps.get(stepIndex) : null;
	}

	@Nullable
	public BuildStep nextStep() {
		return steps != null && stepIndex + 1 < steps.size() ? steps.get(stepIndex + 1) : null;
	}

	public void advance() {
		stepIndex++;
	}

	public boolean isDone() {
		return steps != null && stepIndex >= steps.size();
	}

	/** Progress in percent, 0 before the steps are known. */
	public int getProgressPercent() {
		return steps == null || steps.isEmpty() ? 0 : Math.min(100, stepIndex * 100 / steps.size());
	}

	public void addSkipped(int count) {
		skippedCount += count;
	}

	/** Template blocks (above the floor) this project places; the base of the skipped ratio. */
	public int getTotalBlocks() {
		return totalBlocks;
	}

	@Nullable
	public HouseTemplate getHouse() {
		return house;
	}

	/** World y of the house floor; {@link #ensureSteps} must have succeeded. */
	public int getFloorY() {
		return origin.getY() + house.floorY();
	}

	public BlockPlacer getPlacer(ServerLevel level) {
		if (placer == null) {
			placer = new BlockPlacer(level, true);
		}
		return placer;
	}

	public double getBuildPoints() {
		return buildPoints;
	}

	public void setBuildPoints(double buildPoints) {
		this.buildPoints = buildPoints;
	}

	public int getTicksOutOfReach() {
		return ticksOutOfReach;
	}

	public void setTicksOutOfReach(int ticksOutOfReach) {
		this.ticksOutOfReach = ticksOutOfReach;
	}

	public void excludeBuilder(UUID uuid) {
		excludedBuilders.add(uuid);
	}

	public boolean isExcluded(UUID uuid) {
		return excludedBuilders.contains(uuid);
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putString("Template", templateId.toString());
		tag.put("Origin", NbtUtils.writeBlockPos(origin));
		tag.putString("Rotation", rotation.getSerializedName());
		tag.putIntArray("Footprint", new int[] {footprint.minX(), footprint.minY(), footprint.minZ(),
				footprint.maxX(), footprint.maxY(), footprint.maxZ()});
		tag.putInt("StepIndex", stepIndex);
		tag.putInt("Skipped", skippedCount);
		if (builderUuid != null) {
			tag.putUUID("Builder", builderUuid);
		}
		return tag;
	}

	@Nullable
	public static BuildProject load(CompoundTag tag) {
		ResourceLocation id = ResourceLocation.tryParse(tag.getString("Template"));
		BlockPos origin = NbtUtils.readBlockPos(tag, "Origin").orElse(null);
		Rotation rotation = rotationByName(tag.getString("Rotation"));
		int[] box = tag.getIntArray("Footprint");
		if (id == null || origin == null || rotation == null || box.length != 6) {
			return null;
		}
		BuildProject project = new BuildProject(id, origin, rotation, new BoundingBox(box[0], box[1], box[2], box[3], box[4], box[5]));
		project.stepIndex = Math.max(0, tag.getInt("StepIndex"));
		project.skippedCount = tag.getInt("Skipped");
		if (tag.hasUUID("Builder")) {
			project.builderUuid = tag.getUUID("Builder");
		}
		return project;
	}

	@Nullable
	private static Rotation rotationByName(String name) {
		for (Rotation rotation : Rotation.values()) {
			if (rotation.getSerializedName().equals(name)) {
				return rotation;
			}
		}
		return null;
	}
}
