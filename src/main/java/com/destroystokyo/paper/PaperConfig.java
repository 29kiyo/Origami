package com.destroystokyo.paper;

/**
 * Marker class only. Plugins such as EssentialsX (PaperLib) detect a Paper server with
 * Class.forName("com.destroystokyo.paper.PaperConfig"). Origami implements the Paper API,
 * so it answers that probe. This class has no members on purpose.
 * Documented exception to the "do not create foreign-package classes" rule (see COMPATIBILITY.md).
 */
public final class PaperConfig {
    private PaperConfig() { }
}
