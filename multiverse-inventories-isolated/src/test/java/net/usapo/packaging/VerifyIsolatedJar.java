package net.usapo.packaging;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Verifies the repackaged binary, not a substitute implementation of upstream. */
public final class VerifyIsolatedJar {
    private static final String ORIGINAL = "com/viaversion/nbt/";
    private static final String ISOLATED = "net/usapo/mvinv/internal/nbt/";
    private static final String SHA256 = "6e58e89009283f4be8658abe2b7e46c482010ceccf3c8e0b0e8ca6a0a114ff92";

    public static void main(String[] arguments) throws Exception {
        require(arguments.length == 2, "Expected official and isolated JAR paths");
        Path originalPath = Path.of(arguments[0]);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(originalPath)));
        require(SHA256.equals(digest), "Upstream must be the checksum-pinned official release");
        try (JarFile original = new JarFile(originalPath.toFile());
             JarFile isolated = new JarFile(arguments[1])) {
            Set<String> originalNames = names(original);
            Set<String> isolatedNames = names(isolated);
            long nbtClasses = originalNames.stream().filter(name -> name.startsWith(ORIGINAL)
                    && name.endsWith(".class")).count();
            require(nbtClasses == 47, "Characterization: official release must contain 47 unrelocated NBT classes");
            require(originalNames.stream().noneMatch(name -> name.startsWith(ISOLATED)),
                    "Characterization: official release must not already contain isolated NBT");
            require(isolatedNames.stream().noneMatch(name -> name.startsWith(ORIGINAL)),
                    "Old NBT class/resource namespace must be absent");
            require(isolatedNames.stream().filter(name -> name.startsWith(ISOLATED)
                    && name.endsWith(".class")).count() == nbtClasses,
                    "Every bundled NBT class must be relocated");
            require(Arrays.equals(read(original, "plugin.yml"), read(isolated, "plugin.yml")),
                    "Bukkit plugin metadata must be byte-identical");

            int classes = 0;
            int resources = 0;
            for (String name : originalNames) {
                if (name.matches("META-INF/versions/[^/]+/module-info\\.class")) {
                    require(!isolatedNames.contains(name), "Inapplicable module descriptor must be excluded");
                    continue;
                }
                String relocated = name.startsWith(ORIGINAL) ? ISOLATED + name.substring(ORIGINAL.length()) : name;
                require(isolatedNames.contains(relocated), "Upstream entry lost: " + name);
                if (name.endsWith(".class")) {
                    classes++;
                    String bytecode = new String(read(isolated, relocated), StandardCharsets.ISO_8859_1);
                    require(!bytecode.contains("com/viaversion/nbt") && !bytecode.contains("com.viaversion.nbt"),
                            "Unrelocated binary or reflective class reference: " + name);
                } else if (!name.equals("META-INF/MANIFEST.MF")) {
                    resources++;
                    require(Arrays.equals(read(original, name), read(isolated, relocated)),
                            "Upstream resource changed: " + name);
                }
            }
            System.out.println("PASS: official SHA-256; " + nbtClasses + " isolated NBT classes; "
                    + classes + " class references checked; " + resources
                    + " unchanged resources; byte-identical plugin.yml; no old NBT namespace");
        }
    }

    private static Set<String> names(JarFile jar) {
        Set<String> names = new HashSet<>();
        jar.stream().filter(entry -> !entry.isDirectory()).map(JarEntry::getName).forEach(name -> {
            require(names.add(name), "Duplicate JAR entry: " + name);
        });
        return names;
    }

    private static byte[] read(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name);
        require(entry != null, "Missing JAR entry: " + name);
        try (var stream = jar.getInputStream(entry)) {
            return stream.readAllBytes();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
