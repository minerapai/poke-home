package kr.pokehome;

import com.cobblemon.mod.common.battles.BattleRegistry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.command.CommandSource;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Server side homes: /sethome [name], /home [name], /delhome <name>, /homes. Up to MAX_HOMES per player, saved in
 * the world folder (pokehome.json). Not during a battle, and with a short cooldown. Messages are plain Korean text
 * since clients do not have this mod.
 */
public class PokeHome implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("pokehome");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_HOMES = 3;
    private static final long COOLDOWN_MILLIS = 10_000L;
    private static final String DEFAULT_NAME = "home";

    public static final class Home {
        public String dimension;
        public double x;
        public double y;
        public double z;
        public float yaw;
        public float pitch;
    }

    private static Map<UUID, Map<String, Home>> homes = new LinkedHashMap<>();
    private static final Map<UUID, Long> LAST_USE = new HashMap<>();
    private static Path file;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(PokeHome::load);

        SuggestionProvider<ServerCommandSource> own = (ctx, builder) -> CommandSource.suggestMatching(
            homesOf(ctx.getSource().getPlayerOrThrow()).keySet(), builder);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("sethome")
                .executes(ctx -> setHome(ctx.getSource().getPlayerOrThrow(), DEFAULT_NAME))
                .then(CommandManager.argument("name", StringArgumentType.word())
                    .executes(ctx -> setHome(ctx.getSource().getPlayerOrThrow(), name(ctx)))));
            dispatcher.register(CommandManager.literal("home")
                .executes(ctx -> home(ctx.getSource().getPlayerOrThrow(), null))
                .then(CommandManager.argument("name", StringArgumentType.word()).suggests(own)
                    .executes(ctx -> home(ctx.getSource().getPlayerOrThrow(), name(ctx)))));
            dispatcher.register(CommandManager.literal("delhome")
                .then(CommandManager.argument("name", StringArgumentType.word()).suggests(own)
                    .executes(ctx -> delHome(ctx.getSource().getPlayerOrThrow(), name(ctx)))));
            dispatcher.register(CommandManager.literal("homes")
                .executes(ctx -> list(ctx.getSource().getPlayerOrThrow())));
        });
    }

    private static String name(CommandContext<ServerCommandSource> ctx) {
        return StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
    }

    private static Map<String, Home> homesOf(ServerPlayerEntity player) {
        return homes.computeIfAbsent(player.getUuid(), u -> new LinkedHashMap<>());
    }

    private static void tell(ServerPlayerEntity player, String message, Formatting color) {
        player.sendMessage(Text.literal("[집] " + message).formatted(color));
    }

    private static int setHome(ServerPlayerEntity player, String name) {
        Map<String, Home> own = homesOf(player);

        if (!own.containsKey(name) && own.size() >= MAX_HOMES) {
            tell(player, "집은 " + MAX_HOMES + "개까지 저장할 수 있습니다. /delhome <이름> 으로 하나를 지우거나 같은 이름으로 덮어쓰세요. "
                + "(지금: " + String.join(", ", own.keySet()) + ")", Formatting.RED);
            return 0;
        }

        Home home = new Home();
        home.dimension = player.getServerWorld().getRegistryKey().getValue().toString();
        home.x = player.getX();
        home.y = player.getY();
        home.z = player.getZ();
        home.yaw = player.getYaw();
        home.pitch = player.getPitch();
        boolean replaced = own.put(name, home) != null;
        save();
        tell(player, "'" + name + "' 위치를 " + (replaced ? "새로 " : "") + "저장했습니다. (" + (int) home.x + ", " + (int) home.y
            + ", " + (int) home.z + ") /home" + (DEFAULT_NAME.equals(name) ? "" : " " + name) + " 으로 돌아올 수 있습니다.", Formatting.GREEN);
        return 1;
    }

    private static int home(ServerPlayerEntity player, String requested) {
        Map<String, Home> own = homesOf(player);
        String name = requested;

        if (name == null) { // no name: "home", or the only one there is
            name = own.containsKey(DEFAULT_NAME) || own.size() != 1 ? DEFAULT_NAME : own.keySet().iterator().next();
        }

        Home home = own.get(name);

        if (home == null) {
            tell(player, own.isEmpty() ? "저장된 집이 없습니다. /sethome 으로 지금 위치를 저장하세요."
                : "'" + name + "' 집이 없습니다. 저장된 집: " + String.join(", ", own.keySet()), Formatting.RED);
            return 0;
        }

        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            tell(player, "배틀 중에는 집으로 갈 수 없습니다.", Formatting.RED);
            return 0;
        }

        long wait = LAST_USE.getOrDefault(player.getUuid(), 0L) + COOLDOWN_MILLIS - System.currentTimeMillis();

        if (wait > 0) {
            tell(player, (wait + 999) / 1000 + "초 뒤에 다시 쓸 수 있습니다.", Formatting.RED);
            return 0;
        }

        MinecraftServer server = player.getServer();
        Identifier id = Identifier.tryParse(home.dimension);
        ServerWorld world = id != null ? server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id)) : null;

        if (world == null) {
            tell(player, "'" + name + "' 집이 있는 차원을 찾을 수 없습니다.", Formatting.RED);
            return 0;
        }

        player.stopRiding(); // off a ridden pokemon first
        player.teleport(world, home.x, home.y, home.z, home.yaw, home.pitch);
        LAST_USE.put(player.getUuid(), System.currentTimeMillis());
        tell(player, "'" + name + "' (으)로 이동했습니다.", Formatting.GREEN);
        return 1;
    }

    private static int delHome(ServerPlayerEntity player, String name) {
        if (homesOf(player).remove(name) == null) {
            tell(player, "'" + name + "' 집이 없습니다.", Formatting.RED);
            return 0;
        }

        save();
        tell(player, "'" + name + "' 집을 지웠습니다.", Formatting.YELLOW);
        return 1;
    }

    private static int list(ServerPlayerEntity player) {
        Map<String, Home> own = homesOf(player);

        if (own.isEmpty()) {
            tell(player, "저장된 집이 없습니다. /sethome [이름] 으로 지금 위치를 저장하세요. (최대 " + MAX_HOMES + "개)", Formatting.GRAY);
            return 1;
        }

        MutableText text = Text.literal("[집] " + own.size() + "/" + MAX_HOMES + ": ").formatted(Formatting.GOLD);

        for (Map.Entry<String, Home> entry : own.entrySet()) {
            Home home = entry.getValue();
            String command = DEFAULT_NAME.equals(entry.getKey()) ? "/home" : "/home " + entry.getKey();
            text.append(Text.literal("[" + entry.getKey() + "]").formatted(Formatting.AQUA, Formatting.UNDERLINE)
                .styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))))
                .append(Text.literal(" (" + (int) home.x + ", " + (int) home.y + ", " + (int) home.z + ")  ").formatted(Formatting.GRAY));
        }

        player.sendMessage(text);
        return 1;
    }

    // file

    private static void load(MinecraftServer server) {
        file = server.getSavePath(WorldSavePath.ROOT).resolve("pokehome.json");
        homes = new LinkedHashMap<>();

        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Map<UUID, Map<String, Home>> read = GSON.fromJson(reader, new TypeToken<Map<UUID, Map<String, Home>>>() { }.getType());

                if (read != null) {
                    read.forEach((uuid, map) -> homes.put(uuid, new LinkedHashMap<>(map)));
                }
            } catch (Exception e) {
                LOGGER.error("Could not read {}", file, e);
            }
        }
    }

    private static void save() {
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(homes, writer);
        } catch (Exception e) {
            LOGGER.error("Could not save {}", file, e);
        }
    }
}
