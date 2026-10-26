package net.momirealms.craftengine.core.pack.obfuscation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TextureObfuscatorTest {
    @TempDir Path root;

    @Test
    void updatesTypedReferencesWithoutChangingModelIdsOrText() throws Exception {
        texture("assets/fd/textures/item/food.png");
        write("assets/fd/models/item/food.json", """
                {"parent":"fd:item/food","label":"fd:item/food",
                 "textures":{"layer0":"fd:item/food","particle":{"sprite":"fd:item/food","force_translucent":true}},
                 "elements":[{"faces":{"north":{"texture":"fd:item/food"},"south":{"texture":"#layer0"}}}]}
                """);
        write("assets/fd/items/food.json", "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"fd:item/food\"}}");
        write("assets/fd/particles/food.json", "{\"textures\":[\"fd:item/food\"]}");
        write("assets/fd/font/food.json", "{\"providers\":[{\"type\":\"bitmap\",\"file\":\"fd:item/food.png\"}]}");
        var mapping = TextureObfuscator.apply(root, 41, List.of());
        String name = mapping.get("fd:item/food");
        assertNotNull(name);
        JsonObject model = read("assets/fd/models/item/food.json");
        assertEquals("fd:item/food", model.get("parent").getAsString());
        assertEquals("fd:item/food", model.get("label").getAsString());
        assertEquals(name, model.getAsJsonObject("textures").get("layer0").getAsString());
        assertEquals(name, model.getAsJsonObject("textures").getAsJsonObject("particle").get("sprite").getAsString());
        var faces = model.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("faces");
        assertEquals(name, faces.getAsJsonObject("north").get("texture").getAsString());
        assertEquals("#layer0", faces.getAsJsonObject("south").get("texture").getAsString());
        assertTrue(Files.readString(root.resolve("assets/fd/items/food.json")).contains("fd:item/food"));
        assertEquals(name, read("assets/fd/particles/food.json").getAsJsonArray("textures").get(0).getAsString());
        assertEquals(name + ".png", read("assets/fd/font/food.json").getAsJsonArray("providers").get(0).getAsJsonObject().get("file").getAsString());
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(texturePath(root, name)));
        assertFalse(Files.exists(root.resolve("assets/fd/textures/item/food.png")));
    }

    @Test
    void movesAnimationMetadataAndUsesOneMappingForAllOverlays() throws Exception {
        texture("assets/fd/textures/block/crop.png");
        texture("overlay/assets/fd/textures/block/crop.png");
        write("assets/fd/textures/block/crop.png.mcmeta", "{\"animation\":{\"frametime\":2}}");
        write("overlay/assets/fd/models/block/crop.json", "{\"textures\":{\"all\":\"fd:block/crop\"}}");
        String renamed = TextureObfuscator.apply(root, 12, List.of()).get("fd:block/crop");
        assertTrue(Files.exists(texturePath(root, renamed)));
        assertTrue(Files.exists(texturePath(root.resolve("overlay"), renamed)));
        assertEquals("{\"animation\":{\"frametime\":2}}", Files.readString(Path.of(texturePath(root, renamed) + ".mcmeta")));
        assertEquals(renamed, read("overlay/assets/fd/models/block/crop.json").getAsJsonObject("textures").get("all").getAsString());
    }

    @Test
    void updatesSingleAtlasSourcesAndPreservesDirectoryMembership() throws Exception {
        texture("assets/fd/textures/block/crop.png");
        write("assets/minecraft/atlases/blocks.json", """
                {"sources":[{"type":"minecraft:single","resource":"fd:block/crop","sprite":"fd:block/crop"},
                            {"type":"directory","source":"block","prefix":"block/"}]}
                """);
        String renamed = TextureObfuscator.apply(root, 12, List.of()).get("fd:block/crop");
        var sources = read("assets/minecraft/atlases/blocks.json").getAsJsonArray("sources");
        assertEquals(renamed, sources.get(0).getAsJsonObject().get("resource").getAsString());
        assertEquals(renamed, sources.get(0).getAsJsonObject().get("sprite").getAsString());
        assertEquals("block", sources.get(1).getAsJsonObject().get("source").getAsString());
        assertTrue(renamed.startsWith("fd:block/"));
    }

    @Test
    void keepsExcludedVanillaAndPbrTextures() throws Exception {
        for (String id : List.of("base", "base_n", "exclude", "obfuscate")) texture("assets/fd/textures/item/" + id + ".png");
        texture("assets/minecraft/textures/item/stick.png");
        Map<String, String> mapping = TextureObfuscator.apply(root, 12, List.of("fd:item/excl"));
        assertEquals(1, mapping.size());
        assertTrue(mapping.containsKey("fd:item/obfuscate"));
        assertTrue(Files.exists(root.resolve("assets/fd/textures/item/base.png")));
        assertTrue(Files.exists(root.resolve("assets/fd/textures/item/base_n.png")));
        assertTrue(Files.exists(root.resolve("assets/minecraft/textures/item/stick.png")));
    }

    @Test
    void seedIsStableAcrossIndependentPackCopies() throws Exception {
        for (String dir : List.of("first", "second", "third")) texture(dir + "/assets/fd/textures/item/food.png");
        var first = TextureObfuscator.apply(root.resolve("first"), 25, List.of());
        assertEquals(first, TextureObfuscator.apply(root.resolve("second"), 25, List.of()));
        assertNotEquals(first, TextureObfuscator.apply(root.resolve("third"), 26, List.of()));
    }

    @Test
    void conservativelyKeepsDerivedSpritesAndExternalReferences() throws Exception {
        texture("assets/fd/textures/item/food.png");
        write("assets/minecraft/atlases/items.json", "{\"sources\":[{\"type\":\"directory\",\"source\":\"item\",\"prefix\":\"other/\"}]}");
        assertTrue(TextureObfuscator.apply(root, 25, List.of()).isEmpty());
        Files.delete(root.resolve("assets/minecraft/atlases/items.json"));
        write("assets/minecraft/optifine/cit/custom.properties", "texture=fd:item/food");
        assertTrue(TextureObfuscator.apply(root, 25, List.of()).isEmpty());
        assertTrue(Files.exists(root.resolve("assets/fd/textures/item/food.png")));
    }

    @Test
    void atlasFiltersPreserveTheirMatchesWithoutExcludingAnEntireNamespace() throws Exception {
        texture("assets/fd/textures/item/food.png");
        texture("assets/fd/textures/item/other.png");
        texture("assets/fd/textures/block/crop.png");
        write("assets/minecraft/atlases/items.json", """
                {"sources":[{"type":"filter","pattern":{"namespace":"d","path":"item/food"}},
                            {"type":"filter","pattern":{"namespace":"^fd$","path":"^block/t_"}}]}
                """);
        Map<String, String> mapping = TextureObfuscator.apply(root, 263, List.of());
        assertEquals(java.util.Set.of("fd:item/other"), mapping.keySet());
        assertTrue(Files.exists(root.resolve("assets/fd/textures/item/food.png")));
        assertTrue(Files.exists(root.resolve("assets/fd/textures/block/crop.png")));
        assertFalse(Files.exists(root.resolve("assets/fd/textures/item/other.png")));
        assertEquals("item/food", read("assets/minecraft/atlases/items.json").getAsJsonArray("sources")
                .get(0).getAsJsonObject().getAsJsonObject("pattern").get("path").getAsString());
    }

    private void texture(String path) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{1, 2, 3});
    }

    private void write(String path, String text) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private JsonObject read(String path) throws Exception {
        return JsonParser.parseString(Files.readString(root.resolve(path))).getAsJsonObject();
    }

    private static Path texturePath(Path root, String id) {
        return root.resolve("assets/" + id.replace(':', '/') .replaceFirst("/", "/textures/") + ".png");
    }
}
