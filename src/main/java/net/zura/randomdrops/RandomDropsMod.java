package net.zura.randomdrops;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public class RandomDropsMod implements ModInitializer {

    public static final String MOD_ID = "randomdrops";

    // Whether the mod logic is active. Default: off, toggled with /rd_start and /rd_stop
    private static boolean enabled = false;

    // Mob vs item chance (default → 30% mobs, 70% items)
    private static double mobChance = 0.30;
    private static double itemChance = 0.70;

    // Max amount of items/mobs per block break
    private static int maxMultiplier = 10;

    private static final Random RANDOM = new Random();

    private static final List<Item> POSSIBLE_ITEMS = new ArrayList<>();
    private static final List<Item> AVAILABLE_ITEMS = new ArrayList<>();

    private static final List<EntityType<?>> POSSIBLE_MOBS = new ArrayList<>();
    private static final List<EntityType<?>> AVAILABLE_MOBS = new ArrayList<>();

    private static final Map<Block, DropData> DROP_MAP = new HashMap<>();

    @Override
    public void onInitialize() {
        // Load items
        for (Item item : BuiltInRegistries.ITEM) {
            if (item != Items.AIR) {
                POSSIBLE_ITEMS.add(item);
            }
        }

        // Load ALL mobs, including bosses (Wither, Ender Dragon, etc.)
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            MobCategory cat = type.getCategory();
            if (cat == MobCategory.MISC) continue;      // skip arrows, lightning, etc.
            if (type == EntityType.PLAYER) continue;     // skip players

            POSSIBLE_MOBS.add(type);
        }

        resetState();

        // Block break event
        PlayerBlockBreakEvents.BEFORE.register(RandomDropsMod::onBlockBreak);

        // Show a message when a player joins a world/server
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            Player player = handler.getPlayer();

            enabled = false; // always OFF when entering a world

            player.displayClientMessage(
                    Component.literal("RandomDrops initialized, run /rd_start to enable it")
                            .withStyle(ChatFormatting.GREEN),
                    false
            );
        });


        // Commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

            // /rd_start : enable the mod logic
            dispatcher.register(
                    Commands.literal("rd_start")
                            .requires(src -> src.hasPermission(2))
                            .executes(ctx -> {
                                enabled = true;
                                ctx.getSource().sendSuccess(
                                        () -> Component.literal("[RandomDrops] Enabled.").withStyle(ChatFormatting.GREEN),
                                        true
                                );
                                return 1;
                            })
            );

            // /rd_stop : disable the mod logic
            dispatcher.register(
                    Commands.literal("rd_stop")
                            .requires(src -> src.hasPermission(2))
                            .executes(ctx -> {
                                enabled = false;
                                ctx.getSource().sendSuccess(
                                        () -> Component.literal("[RandomDrops] Disabled.").withStyle(ChatFormatting.RED),
                                        true
                                );
                                return 1;
                            })
            );

            // /rd_maxamount <n>
            dispatcher.register(Commands.literal("rd_maxamount")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("value", IntegerArgumentType.integer(1, 1000))
                            .executes(ctx -> {
                                int v = IntegerArgumentType.getInteger(ctx, "value");
                                maxMultiplier = v;
                                ctx.getSource().sendSuccess(
                                        () -> Component.literal("[RandomDrops] Max amount set to " + v),
                                        true
                                );
                                return 1;
                            }))
            );

            // /rd_resetdrops
            dispatcher.register(Commands.literal("rd_resetdrops")
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> {
                        resetState();
                        ctx.getSource().sendSuccess(
                                () -> Component.literal("[RandomDrops] Drops reset.").withStyle(ChatFormatting.YELLOW),
                                true
                        );
                        return 1;
                    })
            );

            // /rd_mobspawnchance <0–100>
            dispatcher.register(Commands.literal("rd_mobspawnchance")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("value", IntegerArgumentType.integer(0, 100))
                            .executes(ctx -> {
                                int percent = IntegerArgumentType.getInteger(ctx, "value");
                                mobChance = percent / 100.0;
                                itemChance = 1.0 - mobChance;

                                ctx.getSource().sendSuccess(
                                        () -> Component.literal(
                                                        "[RandomDrops] Mob chance = " + percent + "%, Item chance = " + ((int) (itemChance * 100)) + "%"
                                                )
                                                .withStyle(ChatFormatting.AQUA),
                                        true
                                );
                                return 1;
                            }))
            );
        });

        System.out.println("[RandomDrops] Mod initialized!");
    }

    private static boolean onBlockBreak(Level level, Player player, BlockPos pos, BlockState state, BlockEntity be) {
        // Only run on server side
        if (level.isClientSide()) return true;

        // If mod is disabled, do nothing special → vanilla behavior
        if (!enabled) return true;

        ServerLevel serverLevel = (ServerLevel) level;
        Block block = state.getBlock();
        if (block == Blocks.AIR) return true;

        // Get or assign a fixed drop for this block type
        DropData data = DROP_MAP.computeIfAbsent(block, RandomDropsMod::createRandomDrop);

        // Remove the block
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);

        // Apply drop/spawn
        if (data.kind == DropKind.ITEM) {
            int count = Math.min(data.count, 64);
            ItemStack stack = new ItemStack(data.item, count);

            serverLevel.addFreshEntity(new ItemEntity(
                    serverLevel,
                    pos.getX() + 0.5,
                    pos.getY() + 0.5,
                    pos.getZ() + 0.5,
                    stack
            ));
        } else {
            for (int i = 0; i < data.count; i++) {
                Entity e = data.entityType.create(
                        serverLevel,
                        null,
                        pos,
                        EntitySpawnReason.EVENT, // change if your mappings use a different name
                        false,
                        false
                );
                if (e != null) {
                    serverLevel.addFreshEntity(e);
                }
            }
        }

        // Cancel vanilla drops
        return false;
    }

    private static DropData createRandomDrop(Block block) {
        int count = RANDOM.nextInt(Math.max(1, maxMultiplier)) + 1;

        boolean canMob = !AVAILABLE_MOBS.isEmpty();
        boolean canItem = !AVAILABLE_ITEMS.isEmpty();

        if (!canMob && !canItem) {
            // Fallback if everything is exhausted
            return DropData.forItem(Items.DIRT, 1);
        }

        boolean chooseMob;
        if (!canItem) {
            chooseMob = true;
        } else if (!canMob) {
            chooseMob = false;
        } else {
            // Use mobChance / itemChance
            chooseMob = RANDOM.nextDouble() < mobChance;
        }

        if (chooseMob) {
            int i = RANDOM.nextInt(AVAILABLE_MOBS.size());
            EntityType<?> type = AVAILABLE_MOBS.remove(i); // remove → unique
            System.out.println("[RandomDrops] "
                    + BuiltInRegistries.BLOCK.getKey(block)
                    + " -> " + count + "x entity " + BuiltInRegistries.ENTITY_TYPE.getKey(type));
            return DropData.forEntity(type, count);
        } else {
            int i = RANDOM.nextInt(AVAILABLE_ITEMS.size());
            Item item = AVAILABLE_ITEMS.remove(i); // remove → unique
            System.out.println("[RandomDrops] "
                    + BuiltInRegistries.BLOCK.getKey(block)
                    + " -> " + count + "x item " + BuiltInRegistries.ITEM.getKey(item));
            return DropData.forItem(item, count);
        }
    }

    private static void resetState() {
        DROP_MAP.clear();
        AVAILABLE_ITEMS.clear();
        AVAILABLE_ITEMS.addAll(POSSIBLE_ITEMS);
        AVAILABLE_MOBS.clear();
        AVAILABLE_MOBS.addAll(POSSIBLE_MOBS);
    }

    private enum DropKind {
        ITEM,
        ENTITY
    }

    private static class DropData {
        final DropKind kind;
        final Item item;
        final EntityType<?> entityType;
        final int count;

        private DropData(DropKind kind, Item item, EntityType<?> entityType, int count) {
            this.kind = kind;
            this.item = item;
            this.entityType = entityType;
            this.count = count;
        }

        static DropData forItem(Item item, int count) {
            return new DropData(DropKind.ITEM, item, null, count);
        }

        static DropData forEntity(EntityType<?> type, int count) {
            return new DropData(DropKind.ENTITY, null, type, count);
        }
    }
}
