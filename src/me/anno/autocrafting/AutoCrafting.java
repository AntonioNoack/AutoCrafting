package me.anno.autocrafting;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AutoCrafting extends JavaPlugin implements Listener {

    private static final BlockFace[] directions = new BlockFace[]{
            BlockFace.NORTH,
            BlockFace.EAST,
            BlockFace.SOUTH,
            BlockFace.WEST,
            BlockFace.UP,
            BlockFace.DOWN
    };

    @Override
    public void onEnable() {
        super.onEnable();
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void handleRedstone(BlockRedstoneEvent event) {
        if (event.getOldCurrent() == 0 && event.getNewCurrent() > 0) {
            Block block = event.getBlock();
            for (BlockFace face : directions) {
                Block block1 = block.getRelative(face);
                if (block1.getType() == Material.CRAFTING_TABLE) {
                    tryToCraft(block1);
                }
            }
        }
    }

    private boolean hasSpace(ItemStack it, Material type) {
        return it == null || it.getType() == Material.AIR ||
                it.getType() == type && it.getAmount() < it.getMaxStackSize();
    }

    private boolean hasSpace(Inventory dstInventory, Material type) {
        for (ItemStack stack : dstInventory.getStorageContents()) {
            if (hasSpace(stack, type)) {
                return true;
            }
        }
        return false;
    }

    private void tryToCraft(Block craftingTable) {

        Block[] blocks = getNeighborBlocks(craftingTable);
        boolean hasHopper = false;
        boolean hasAir = false;
        Chest chest = null;
        for (Block block : blocks) {
            Material type = block.getType();
            if (type == Material.HOPPER) hasHopper = true;
            else if (type == Material.AIR) hasAir = true;
            else if (type == Material.CHEST) {
                BlockState state = block.getState();
                if (state instanceof Chest) chest = (Chest) block.getState();
            }
        }
        if (!hasHopper || !hasAir || chest == null) return;

        ItemFrame itemFrame = findItemFrame(blocks, craftingTable);
        if (itemFrame == null) return;

        // check if item could be placed into inventory
        Material resultType = itemFrame.getItem().getType();
        Inventory dstInventory = chest.getSnapshotInventory();
        if (!hasSpace(dstInventory, resultType)) return;

        List<Hopper1> hoppers = findConnectedHoppers(blocks, craftingTable);
        if (hoppers.isEmpty()) return;

        // find available resources
        HashMap<Material, Integer> availableResources = collectAvailableResources(hoppers);
        if (availableResources.isEmpty()) return;

        // find valid recipes
        HashMap<Material, Integer> usedResources = new HashMap<>();
        ItemStack result = findRecipe(itemFrame.getItem(), availableResources, usedResources);
        if (result == null) return;


        // remove x ingredients
        // for every water bottle / bucket, add an empty one to the result
        // if everything worked, commit the state

        int waterBuckets = get(usedResources, Material.WATER_BUCKET) + get(usedResources, Material.LAVA_BUCKET);
        int waterBottles = get(usedResources, Material.POTION) + get(usedResources, Material.HONEY_BOTTLE);

        if (addResultsToInventory(dstInventory, result, waterBuckets, waterBottles) &&
                removeIngredientsFromHoppers(usedResources, hoppers)
        ) commitInventoryChanges(chest, hoppers);

    }

    private void commitInventoryChanges(Chest chest, List<Hopper1> hoppers) {
        chest.update(true);
        for (Hopper1 hopper : hoppers) {
            hopper.hopper.update(true);
        }
    }

    @SuppressWarnings("RedundantIfStatement")
    private boolean addResultsToInventory(
            Inventory dstInventory, ItemStack result,
            int waterBuckets, int waterBottles
    ) {
        if (dstInventory.addItem(result).size() > 0) return false;
        if (waterBuckets > 0 && dstInventory.addItem(new ItemStack(Material.BUCKET, waterBuckets)).size() > 0)
            return false;
        if (waterBottles > 0 && dstInventory.addItem(new ItemStack(Material.GLASS_BOTTLE, waterBottles)).size() > 0)
            return false;
        return true;
    }

    private boolean removeIngredientsFromHoppers(
            HashMap<Material, Integer> usedResources,
            List<Hopper1> hoppers
    ) {
        resources:
        for (Map.Entry<Material, Integer> resource : usedResources.entrySet()) {
            ItemStack toRemove = new ItemStack(resource.getKey(), resource.getValue());
            for (Hopper1 hopper : hoppers) {
                HashMap<Integer, ItemStack> remaining = hopper.inventory.removeItem(toRemove);
                if (remaining.isEmpty()) continue resources; // done :)
                toRemove.setAmount(remaining.values().iterator().next().getAmount());
            }
            if (toRemove.getAmount() > 0) {
                return false;
            }
        }
        return true;
    }

    private List<Hopper1> findConnectedHoppers(Block[] blocks, Block craftingTable) {
        // max valid length is 3, because one block is chest, one is itemframe, and one is redstone component
        ArrayList<Hopper1> result = new ArrayList<>(4);
        for (Block block : blocks) {
            if (block.getType() == Material.HOPPER) {
                org.bukkit.material.Hopper hopper0 = (org.bukkit.material.Hopper) block.getState().getData();
                BlockFace dir = hopper0.getFacing();
                if (craftingTable.equals(block.getRelative(dir))) {
                    Hopper hopper = (Hopper) block.getState();
                    result.add(new Hopper1(hopper));
                }
            }
        }
        return result;
    }

    private int get(HashMap<Material, Integer> resources, Material type) {
        Integer answer = resources.get(type);
        return answer == null ? 0 : answer;
    }

    private Block[] getNeighborBlocks(Block craftingTable) {
        Block[] blocks = new Block[directions.length];
        for (int i = 0; i < directions.length; i++) {
            blocks[i] = craftingTable.getRelative(directions[i]);
        }
        return blocks;
    }

    private ItemFrame findItemFrame(Block[] blocks, Block craftingTable) {
        Location loc = new Location(craftingTable.getWorld(),
                craftingTable.getX() + 0.5, craftingTable.getY() + 0.5, craftingTable.getZ() + 0.5);
        for (Block block : blocks) {
            ItemFrame itemFrame = findItemFrame(block, loc);
            if (itemFrame != null) return itemFrame;
        }
        return null;
    }

    private ItemFrame findItemFrame(Block block, Location center) {
        if (block.getType() == Material.AIR) {
            for (Entity entity : block.getChunk().getEntities()) {
                // actual distance: 0.53125
                if (entity instanceof ItemFrame && entity.getLocation().distance(center) < 0.7) {
                    ItemFrame itemFrame = (ItemFrame) entity;
                    if (itemFrame.getItem().getType() != Material.AIR) {
                        return itemFrame;
                    }
                }
            }
        }
        return null;
    }

    private HashMap<Material, Integer> collectAvailableResources(List<Hopper1> hoppers) {
        HashMap<Material, Integer> availableResources = new HashMap<>();
        for (Hopper1 hopper : hoppers) {
            for (ItemStack stack : hopper.inventory.getStorageContents()) {
                if (stack == null) continue;
                Material type = stack.getType();
                if (type == Material.AIR) continue;
                Integer previous = availableResources.get(type);
                int previous1 = previous == null ? 0 : previous;
                availableResources.put(type, stack.getAmount() + previous1);
            }
        }
        return availableResources;
    }

    private ItemStack findRecipe(ItemStack target, HashMap<Material, Integer> availableResources, HashMap<Material, Integer> usedResources) {
        for (Recipe recipe : getServer().getRecipesFor(target)) {
            if (recipe instanceof ShapedRecipe) {
                if (canUseRecipe((ShapedRecipe) recipe, availableResources, usedResources)) {
                    return recipe.getResult();
                }
            } else if (recipe instanceof ShapelessRecipe) {
                if (canUseRecipe((ShapelessRecipe) recipe, availableResources, usedResources)) {
                    return recipe.getResult();
                }
            }
        }
        return null;
    }

    private boolean canUseRecipe(ShapedRecipe recipe, HashMap<Material, Integer> availableResources, HashMap<Material, Integer> usedResources) {
        usedResources.clear();
        Map<Character, ItemStack> map = recipe.getIngredientMap();
        // println("Checking $map, ${recipe.shape.joinToString("|")}")
        for (String row : recipe.getShape()) {
            for (int i = 0, l = row.length(); i < l; i++) {
                char c = row.charAt(i);
                ItemStack ingredient = map.get(c);
                if (ingredient == null) continue;
                Material type = ingredient.getType();
                if (type == Material.AIR) continue;
                int usedAmount = ingredient.getAmount() + get(usedResources, type);
                int availableAmount = get(availableResources, type);
                if (usedAmount > availableAmount) return false;
                usedResources.put(type, usedAmount);
            }
        }
        return true;
    }

    private boolean canUseRecipe(ShapelessRecipe recipe, HashMap<Material, Integer> availableResources, HashMap<Material, Integer> usedResources) {
        usedResources.clear();
        for (RecipeChoice choice : recipe.getChoiceList()) {
            if (choice instanceof RecipeChoice.MaterialChoice) {
                boolean canBeUsed = false;
                for (Material type : ((RecipeChoice.MaterialChoice) choice).getChoices()) {
                    int usedAmount = 1 + get(usedResources, type);
                    int availableAmount = get(availableResources, type);
                    if (usedAmount > availableAmount) continue;
                    usedResources.put(type, usedAmount);
                    canBeUsed = true;
                }
                if (!canBeUsed) return false;
            }
        }
        return true;
    }

}
