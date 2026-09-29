package com.khanhvu.livingvillages.village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * All villages known in one dimension, persisted as data/livingvillages.dat.
 */
public class VillageRegistry extends SavedData {
	private static final String DATA_NAME = "livingvillages";
	private static final SavedData.Factory<VillageRegistry> FACTORY =
			new SavedData.Factory<>(VillageRegistry::new, VillageRegistry::load, null);

	private final List<VillageRecord> villages = new ArrayList<>();

	public static VillageRegistry get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
	}

	public List<VillageRecord> getVillages() {
		return Collections.unmodifiableList(villages);
	}

	public VillageRecord register(BlockPos bellPos) {
		VillageRecord record = new VillageRecord(UUID.randomUUID(), bellPos);
		villages.add(record);
		setDirty();
		return record;
	}

	/** The village whose bell is horizontally closest to {@code pos}, within {@code maxDistance}. */
	@Nullable
	public VillageRecord findNearest(BlockPos pos, double maxDistance) {
		VillageRecord best = null;
		double bestDist = maxDistance * maxDistance;
		for (VillageRecord record : villages) {
			double dist = record.horizontalDistSqr(pos);
			if (dist <= bestDist) {
				best = record;
				bestDist = dist;
			}
		}
		return best;
	}

	/** The active village whose area (see {@link VillageAnalyzer#areaRadius}) contains {@code pos}, the nearest if several. */
	@Nullable
	public VillageRecord findContaining(BlockPos pos) {
		VillageRecord best = null;
		double bestDist = Double.MAX_VALUE;
		for (VillageRecord record : villages) {
			if (!record.isActive()) {
				continue;
			}
			double dist = record.horizontalDistSqr(pos);
			int radius = VillageAnalyzer.areaRadius(record);
			if (dist <= (double) radius * radius && dist < bestDist) {
				best = record;
				bestDist = dist;
			}
		}
		return best;
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
		ListTag list = new ListTag();
		for (VillageRecord record : villages) {
			list.add(record.save());
		}
		tag.put("Villages", list);
		return tag;
	}

	private static VillageRegistry load(CompoundTag tag, HolderLookup.Provider provider) {
		VillageRegistry registry = new VillageRegistry();
		ListTag list = tag.getList("Villages", Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size(); i++) {
			VillageRecord record = VillageRecord.load(list.getCompound(i));
			if (record != null) {
				registry.villages.add(record);
			}
		}
		return registry;
	}
}
