package dev.wp.matter_manipulator.datagen.providers.client;

import dev.wp.matter_manipulator.MMMod;
import dev.wp.matter_manipulator.common.items.MMItems;
import net.minecraftforge.common.data.LanguageProvider;
import net.minecraftforge.data.event.GatherDataEvent;

public class MMLanguageProvider extends LanguageProvider {
    public MMLanguageProvider(GatherDataEvent event) {
        super(event.getGenerator().getPackOutput(), MMMod.MODID, "en_us");
    }

    @Override
    protected void addTranslations() {
        add("itemGroup.matter_manipulator", "Matter Manipulator");

        add(MMItems.MANIPULATOR_PROTO.get(), "Matter Manipulator (Prototype)");
        add(MMItems.MANIPULATOR_MK1.get(), "Matter Manipulator MK I");
        add(MMItems.MANIPULATOR_MK2.get(), "Matter Manipulator MK II");
        add(MMItems.MANIPULATOR_MK3.get(), "Matter Manipulator MK III");

        add(guiKey("title"), "Matter Manipulator");
        add(guiKey("mode"), "Place Mode");
        add(guiKey("shape"), "Shape");
        add(guiKey("remove"), "Remove Mode");
        add(guiKey("start"), "Start Build");
        add(guiKey("cancel"), "Cancel Build");
        add(guiKey("clear"), "Clear Coords");

        add("key.categories.matter_manipulator", "Matter Manipulator");
        add(keyKey("lock_positions"), "Lock Position");
        add(keyKey("copy"), "Copy");
        add(keyKey("cut"), "Cut");
        add(keyKey("paste"), "Paste");
        add(keyKey("reset"), "Reset");
    }

    private String guiKey(String key) {
        return "gui.matter_manipulator." + key;
    }

    private String keyKey(String key) {
        return "key.matter_manipulator." + key;
    }
}
