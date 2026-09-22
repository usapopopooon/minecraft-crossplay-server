// Optional smoke test using an installed, licensed Minecraft 26.2/26.3 client
// and that version's library classpath. Not a rendered-client test.
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.model.cuboid.CuboidModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelDispatcher;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.world.level.block.Blocks;

public final class VerifyJavaPack {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("pass Java pack ZIP path");
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        try (var zip = new ZipFile(args[0])) {
            var metadata = JsonParser.parseReader(reader(zip, "pack.mcmeta")).getAsJsonObject().get("pack");
            var parsed = PackMetadataSection.CLIENT_TYPE.codec().parse(JsonOps.INSTANCE, metadata).getOrThrow();
            System.out.println("PASS actual client pack metadata: " + parsed.supportedFormats());
            var stateJson = JsonParser.parseReader(reader(zip, "assets/minecraft/blockstates/tripwire.json"));
            var states = BlockStateModelDispatcher.CODEC.parse(JsonOps.INSTANCE, stateJson).getOrThrow();
            var instantiated = states.instantiate(Blocks.TRIPWIRE.getStateDefinition(), () -> "usapo-pack-probe");
            if (instantiated.size() != 128) throw new AssertionError("wrong tripwire variant coverage");
            System.out.println("PASS actual client blockstate dispatcher: all 128 tripwire states");
            for (String axis : new String[]{"x", "z"}) {
                var model = CuboidModel.fromStream(reader(zip, "assets/usapo/models/block/cyan_portal_" + axis + ".json"));
                if (model.geometry() == null) throw new AssertionError("missing geometry");
                System.out.println("PASS actual client cuboid model: " + axis);
            }
            var animationJson = JsonParser.parseReader(reader(zip, "assets/usapo/textures/block/cyan_portal.png.mcmeta")).getAsJsonObject().get("animation");
            var animation = AnimationMetadataSection.CODEC.parse(JsonOps.INSTANCE, animationJson).getOrThrow();
            if (animation.frames().orElseThrow().size() != 32 || animation.defaultFrameTime() != 2)
                throw new AssertionError("wrong animation");
            var frame = animation.calculateFrameSize(64, 2048);
            if (frame.width() != 64 || frame.height() != 64) throw new AssertionError("wrong frame size");
            System.out.println("PASS actual client animation: 32 frames, 64x64, 2 ticks");
            try (var pixels = NativeImage.read(zip.getInputStream(zip.getEntry("assets/usapo/textures/block/cyan_portal.png")));
                 var sprite = new SpriteContents(Identifier.fromNamespaceAndPath("usapo", "block/cyan_portal"), frame, pixels,
                        java.util.Optional.of(animation), java.util.List.of(), java.util.Optional.empty())) {
                var transparency = sprite.computeTransparency(0, 0, 1, 1);
                if (!transparency.hasTranslucent()
                        || ChunkSectionLayer.byTransparency(transparency) != ChunkSectionLayer.TRANSLUCENT)
                    throw new AssertionError("alpha is not using the translucent render layer");
                System.out.println("PASS actual client PNG alpha selects TRANSLUCENT, not CUTOUT: " + transparency);
            }
        }
    }

    private static InputStreamReader reader(ZipFile zip, String name) throws Exception {
        return new InputStreamReader(zip.getInputStream(zip.getEntry(name)), StandardCharsets.UTF_8);
    }
}
