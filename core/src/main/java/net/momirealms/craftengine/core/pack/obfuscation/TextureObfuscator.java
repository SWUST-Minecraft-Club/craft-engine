package net.momirealms.craftengine.core.pack.obfuscation;

import com.google.gson.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Pattern;

/** Renames custom block/item PNGs and their typed references in a disposable pack copy. */
public final class TextureObfuscator {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Set<String> PBR_SUFFIXES = Set.of("_n", "_s", "_e");

    private TextureObfuscator() {}

    /** Exclusions are resource IDs or prefixes, e.g. {@code farmersdelight:item/knife}. */
    public static Map<String, String> apply(Path root, long seed, List<String> exclusions) throws IOException {
        List<Path> files;
        try (var paths = Files.walk(root)) {
            files = paths.filter(Files::isRegularFile).sorted().toList();
        }
        Map<Path, Asset> assets = new LinkedHashMap<>();
        Map<Path, JsonObject> documents = new LinkedHashMap<>();
        Map<String, List<Path>> textures = new TreeMap<>();
        boolean externalReferences = false;
        for (Path file : files) {
            Asset asset = asset(root.relativize(file));
            if (asset == null) continue;
            assets.put(file, asset);
            // These formats can refer to textures outside their own namespace. Leave such packs intact.
            if (asset.path.endsWith(".properties") || asset.path.startsWith("optifine/")
                    || asset.path.startsWith("mcpatcher/")
                    || (asset.path.startsWith("shaders/") && !asset.namespace.equals("minecraft"))) {
                externalReferences = true;
            }
            if (!asset.namespace.equals("minecraft") && asset.path.endsWith(".png")
                    && (asset.path.startsWith("textures/block/") || asset.path.startsWith("textures/item/"))) {
                String id = asset.namespace + ':' + asset.path.substring(9, asset.path.length() - 4);
                textures.computeIfAbsent(id, ignored -> new ArrayList<>()).add(file);
            }
            if (asset.path.endsWith(".json") && (asset.path.startsWith("models/")
                    || asset.path.startsWith("particles/") || asset.path.startsWith("font/")
                    || asset.path.startsWith("atlases/"))) {
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    JsonElement document = JsonParser.parseReader(reader);
                    if (!(document instanceof JsonObject object)) throw new IOException("Expected a JSON object: " + file);
                    documents.put(file, object);
                } catch (JsonParseException failure) {
                    throw new IOException("Cannot obfuscate invalid JSON: " + file, failure);
                }
            }
        }
        if (externalReferences) return Map.of();

        long actualSeed = seed == 0 ? new SecureRandom().nextLong() : seed;
        Set<String> excluded = new HashSet<>();
        for (String id : textures.keySet()) {
            if (exclusions.stream().anyMatch(prefix -> matchesPrefix(id, prefix))) excluded.add(id);
            for (String suffix : PBR_SUFFIXES) {
                if (id.endsWith(suffix)) {
                    excluded.add(id);
                    excluded.add(id.substring(0, id.length() - suffix.length()));
                }
                if (textures.containsKey(id + suffix)) excluded.add(id);
            }
        }
        // Atlas directory prefixes and derived sprites are not necessarily file resource IDs.
        // Only rename textures whose sprite IDs can be updated without changing atlas semantics.
        for (var entry : documents.entrySet()) {
            if (!assets.get(entry.getKey()).path.startsWith("atlases/")) continue;
            if (!(entry.getValue().get("sources") instanceof JsonArray sources)) continue;
            for (JsonElement element : sources) {
                if (!(element instanceof JsonObject source)) continue;
                String type = string(source, "type");
                if (type.startsWith("minecraft:")) type = type.substring(10);
                if (type.equals("single")) {
                    String resource = canonical(string(source, "resource"));
                    String sprite = canonical(string(source, "sprite"));
                    if (!sprite.isEmpty() && !sprite.equals(resource)) excluded.add(sprite);
                } else if (type.equals("directory")) {
                    String directory = string(source, "source");
                    if (!string(source, "prefix").equals(directory + '/')) {
                        for (String id : textures.keySet()) {
                            if (id.substring(id.indexOf(':') + 1).startsWith(directory + '/')) excluded.add(id);
                        }
                    }
                } else if (type.equals("filter")) {
                    // A regex depending on filenames can change which sprites survive a rename.
                    if (source.get("pattern") instanceof JsonObject pattern) {
                        Pattern namespace = Pattern.compile(pattern.has("namespace") ? string(pattern, "namespace") : ".*");
                        Pattern path = Pattern.compile(pattern.has("path") ? string(pattern, "path") : ".*");
                        for (String id : textures.keySet()) {
                            int separator = id.indexOf(':');
                            // Minecraft uses Pattern.asPredicate (find), not full-string matches. Preserve
                            // both existing matches and textures whose new names would start matching.
                            if (namespace.matcher(id.substring(0, separator)).find()
                                    && (path.matcher(id.substring(separator + 1)).find()
                                    || path.matcher(renamed(id, actualSeed).substring(separator + 1)).find())) {
                                excluded.add(id);
                            }
                        }
                    }
                } else {
                    excludeReferencedTextures(source, textures.keySet(), excluded);
                }
            }
        }

        Map<String, String> mapping = new LinkedHashMap<>();
        for (String id : textures.keySet()) {
            if (excluded.contains(id)) continue;
            String renamed = renamed(id, actualSeed);
            if (textures.containsKey(renamed) || mapping.containsValue(renamed)) {
                throw new IOException("Obfuscated texture name collision: " + id);
            }
            mapping.put(id, renamed);
        }
        for (var entry : documents.entrySet()) {
            JsonObject document = entry.getValue();
            String path = assets.get(entry.getKey()).path;
            if (path.startsWith("models/")) {
                if (document.get("textures") instanceof JsonObject references) {
                    for (var reference : references.entrySet()) {
                        if (reference.getValue() instanceof JsonObject texture) rewrite(texture, "sprite", mapping, false);
                        else reference.setValue(rewrite(reference.getValue(), mapping, false));
                    }
                }
                if (document.get("elements") instanceof JsonArray elements) {
                    for (JsonElement element : elements) {
                        if (element instanceof JsonObject part && part.get("faces") instanceof JsonObject faces) {
                            for (var face : faces.entrySet()) {
                                if (face.getValue() instanceof JsonObject value) rewrite(value, "texture", mapping, false);
                            }
                        }
                    }
                }
            } else if (path.startsWith("particles/") && document.get("textures") instanceof JsonArray references) {
                for (int i = 0; i < references.size(); i++) references.set(i, rewrite(references.get(i), mapping, false));
            } else if (path.startsWith("font/") && document.get("providers") instanceof JsonArray providers) {
                for (JsonElement provider : providers) {
                    if (provider instanceof JsonObject value && Set.of("bitmap", "minecraft:bitmap").contains(string(value, "type"))) {
                        rewrite(value, "file", mapping, true);
                    }
                }
            } else if (path.startsWith("atlases/") && document.get("sources") instanceof JsonArray sources) {
                for (JsonElement source : sources) {
                    if (source instanceof JsonObject value && Set.of("single", "minecraft:single").contains(string(value, "type"))) {
                        rewrite(value, "resource", mapping, false);
                        rewrite(value, "sprite", mapping, false);
                    }
                }
            }
            try (var writer = Files.newBufferedWriter(entry.getKey(), StandardCharsets.UTF_8)) {
                JSON.toJson(document, writer);
            }
        }
        for (var entry : mapping.entrySet()) {
            String basename = entry.getValue().substring(entry.getValue().lastIndexOf('/') + 1);
            for (Path texture : textures.get(entry.getKey())) {
                Files.move(texture, texture.resolveSibling(basename + ".png"));
                Path metadata = texture.resolveSibling(texture.getFileName() + ".mcmeta");
                if (Files.exists(metadata)) Files.move(metadata, texture.resolveSibling(basename + ".png.mcmeta"));
            }
        }
        return Collections.unmodifiableMap(mapping);
    }

    private static boolean matchesPrefix(String id, String prefix) {
        if (prefix.endsWith("*")) prefix = prefix.substring(0, prefix.length() - 1);
        return !prefix.isEmpty() && id.startsWith(prefix);
    }

    private static void excludeReferencedTextures(JsonElement value, Set<String> textures, Set<String> excluded) {
        if (value instanceof JsonPrimitive primitive && primitive.isString()) {
            String id = canonical(primitive.getAsString());
            if (id.endsWith(".png")) id = id.substring(0, id.length() - 4);
            if (textures.contains(id)) excluded.add(id);
        } else if (value instanceof JsonObject object) {
            for (var entry : object.entrySet()) excludeReferencedTextures(entry.getValue(), textures, excluded);
        } else if (value instanceof JsonArray array) {
            for (JsonElement entry : array) excludeReferencedTextures(entry, textures, excluded);
        }
    }

    private static void rewrite(JsonObject object, String field, Map<String, String> mapping, boolean png) {
        if (object.has(field)) object.add(field, rewrite(object.get(field), mapping, png));
    }

    private static JsonElement rewrite(JsonElement value, Map<String, String> mapping, boolean png) {
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isString()) return value;
        String original = primitive.getAsString();
        String id = png && original.endsWith(".png") ? original.substring(0, original.length() - 4) : original;
        String replacement = mapping.get(canonical(id));
        return replacement == null ? value : new JsonPrimitive(replacement + (png ? ".png" : ""));
    }

    private static String canonical(String id) {
        if (id.isEmpty() || id.startsWith("#")) return id;
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    private static String string(JsonObject object, String key) {
        return object.get(key) instanceof JsonPrimitive value && value.isString() ? value.getAsString() : "";
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String renamed(String id, long seed) {
        return id.substring(0, id.lastIndexOf('/') + 1) + "t_" + digest(seed + ":" + id);
    }

    private static Asset asset(Path relative) {
        for (int i = 0; i + 2 < relative.getNameCount(); i++) {
            if (relative.getName(i).toString().equals("assets")) {
                return new Asset(relative.getName(i + 1).toString(), relative.subpath(i + 2, relative.getNameCount()).toString().replace('\\', '/'));
            }
        }
        return null;
    }

    private record Asset(String namespace, String path) {}
}
