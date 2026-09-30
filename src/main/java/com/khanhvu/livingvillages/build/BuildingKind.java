package com.khanhvu.livingvillages.build;

import java.util.Locale;

/** What a village building is for (spec v2-GĐ 3.1). */
public enum BuildingKind {
	/** Has a bed and no job site: adds beds. */
	HOUSE,
	/** Farmland with a composter: adds a farmer job and fields. */
	FARM,
	/** Fenced enclosure for animals. */
	PEN,
	/** Has a job site block: adds a job for one profession. */
	WORKSHOP;

	public String langKey() {
		return "livingvillages.kind." + name().toLowerCase(Locale.ROOT);
	}
}
