package com.khanhvu.livingvillages.chronicle;

import com.khanhvu.livingvillages.LivingVillages;
import com.khanhvu.livingvillages.identity.VillageIdentity;
import com.khanhvu.livingvillages.util.LVText;
import com.khanhvu.livingvillages.village.VillageRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The librarian keeps the chronicle (spec v2-GĐ 7.2): when there are new entries, every librarian's own lectern
 * (from its JOB_SITE) that is empty gets a written book of the chronicle, and a chronicle book already there is
 * rewritten. A lectern holding any other book is never touched, and the mod never places a lectern.
 */
public final class ChronicleBook {
	/** Marks the books this mod wrote, so other books on lecterns are left alone. */
	private static final String MARKER = "livingvillages_chronicle";
	private static final int ENTRIES_PER_PAGE = 3;
	private static final int MAX_TITLE_LENGTH = 32;

	private ChronicleBook() {
	}

	/** Called at the manage interval: writes the books when the chronicle changed. */
	public static void update(ServerLevel level, VillageRecord village, List<Villager> adults) {
		if (!village.isChronicleDirty() || village.getChronicle().isEmpty()) {
			return;
		}
		boolean anyLibrarian = false;
		for (Villager villager : adults) {
			if (villager.getVillagerData().getProfession() != VillagerProfession.LIBRARIAN) {
				continue;
			}
			Optional<GlobalPos> jobSite = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE);
			if (jobSite.isEmpty() || jobSite.get().dimension() != level.dimension() || !level.isLoaded(jobSite.get().pos())) {
				continue;
			}
			anyLibrarian = true;
			write(level, village, villager, jobSite.get().pos());
		}
		// Without a librarian the chronicle is still kept (read with the command); books are written once one exists.
		if (anyLibrarian) {
			village.setChronicleDirty(false);
		}
	}

	private static void write(ServerLevel level, VillageRecord village, Villager librarian, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof LecternBlock) || !(level.getBlockEntity(pos) instanceof LecternBlockEntity lectern)) {
			return;
		}
		ItemStack book = createBook(village, librarian);
		if (!lectern.hasBook()) {
			LecternBlock.tryPlaceBook(null, level, pos, state, book);
			LivingVillages.debug("Village {}: chronicle book placed at {}", village.getId(), pos);
		} else if (isChronicleOf(lectern.getBook(), village)) {
			lectern.setBook(book);
		}
	}

	private static boolean isChronicleOf(ItemStack stack, VillageRecord village) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().getString(MARKER).equals(village.getId().toString());
	}

	private static ItemStack createBook(VillageRecord village, Villager librarian) {
		List<ChronicleEntry> entries = village.getChronicle();
		List<Filterable<Component>> pages = new ArrayList<>();
		for (int start = 0; start < entries.size(); start += ENTRIES_PER_PAGE) {
			MutableComponent page = Component.empty();
			for (int i = start; i < Math.min(entries.size(), start + ENTRIES_PER_PAGE); i++) {
				if (i > start) {
					page.append("\n\n");
				}
				page.append(entries.get(i).render());
			}
			pages.add(Filterable.passThrough(page));
		}
		String title = LVText.format("livingvillages.chronicle.book_title", VillageIdentity.displayName(village));
		if (title.length() > MAX_TITLE_LENGTH) {
			title = title.substring(0, MAX_TITLE_LENGTH);
		}
		String author = librarian.getName().getString();
		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
		book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(title), author, 0, pages, true));
		CompoundTag marker = new CompoundTag();
		marker.putString(MARKER, village.getId().toString());
		book.set(DataComponents.CUSTOM_DATA, CustomData.of(marker));
		return book;
	}
}
