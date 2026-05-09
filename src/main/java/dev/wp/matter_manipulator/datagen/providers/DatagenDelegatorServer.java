package dev.wp.matter_manipulator.datagen.providers;

import dev.wp.matter_manipulator.datagen.providers.server.MMBlockTagsProvider;
import net.minecraft.data.DataProvider;
import net.minecraftforge.data.event.GatherDataEvent;

import java.util.function.Function;

public class DatagenDelegatorServer {
    public static void configure(GatherDataEvent event) {
        add(event, MMBlockTagsProvider::new);
    }

    private static void add(GatherDataEvent event, Function<GatherDataEvent, DataProvider> provider) {
        event.getGenerator().addProvider(event.includeServer(), provider.apply(event));
    }
}
