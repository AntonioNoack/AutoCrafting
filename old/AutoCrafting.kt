package me.anno.autocrafting

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.block.Hopper
import org.bukkit.entity.ItemFrame
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockRedstoneEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.RecipeChoice.MaterialChoice
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.inventory.ShapelessRecipe
import org.bukkit.plugin.java.JavaPlugin

@Suppress("unused")
class AutoCrafting : JavaPlugin(), Listener {

    data class Hopper1(val hopper: Hopper, val inventory: Inventory)

    override fun onEnable() {
        super.onEnable()
        server.pluginManager.registerEvents(this, this)
    }

    private val directions = intArrayOf(
        -1, 0, 0,
        +1, 0, 0,
        0, -1, 0,
        0, +1, 0,
        0, 0, -1,
        0, 0, +1
    )

    @EventHandler
    fun handleRedstone(event: BlockRedstoneEvent) {

        if (!(event.oldCurrent == 0 && event.newCurrent > 0)) return

        // check for nearby crafting tables
        val block = event.block
        val world = block.world

        val x = block.x
        val y = block.y
        val z = block.z
        val directions = directions
        for (di in directions.indices step 3) {
            val block1 = world.getBlockAt(x + directions[di], y + directions[di + 1], z + directions[di + 2])
            if (block1.type == Material.CRAFTING_TABLE) {
                tryToCraft(block1)
            }
        }
    }

    private fun getNeighborBlocks(block: Block): Array<Block> {
        val world = block.world
        return Array(6) {
            val i3 = it * 3
            world.getBlockAt(
                block.x + directions[i3],
                block.y + directions[i3 + 1],
                block.z + directions[i3 + 2]
            )
        }
    }

    private fun hasSpace(it: ItemStack?, type: Material): Boolean {
        return it == null || it.type == Material.AIR ||
                it.type == type && it.amount < it.maxStackSize
    }

    private fun findItemFrame(blocks: Array<Block>, craftingTable: Block): ItemFrame? {
        val loc = Location(craftingTable.world, craftingTable.x + 0.5, craftingTable.y + 0.5, craftingTable.z + 0.5)
        return blocks.firstNotNullOfOrNull { findItemFrame(it, loc) }
    }

    private fun tryToCraft(craftingTable: Block) {

        val blocks = getNeighborBlocks(craftingTable)
        if (blocks.none { it.type == Material.HOPPER }) return

        val chest = blocks.firstOrNull { it.type == Material.CHEST }?.state as? Chest ?: return
        val itemFrame = findItemFrame(blocks, craftingTable) ?: return

        // check if item could be placed into inventory
        val resultType = itemFrame.item.type
        val dstInventory = chest.snapshotInventory
        if (dstInventory.storageContents.none { hasSpace(it, resultType) }) return

        val hoppers = findConnectedHoppers(blocks, craftingTable)
        if (hoppers.isEmpty()) return

        // find available resources
        val availableResources = collectAvailableResources(hoppers)
        if (availableResources.isEmpty()) return

        // find valid recipes
        val usedResources = HashMap<Material, Int>()
        val result = findRecipe(itemFrame.item, availableResources, usedResources) ?: return

        // remove x ingredients
        // for every water bottle / bucket, add an empty one to the result
        // if everything worked, commit the state

        val waterBuckets = (usedResources[Material.WATER_BUCKET] ?: 0) + (usedResources[Material.LAVA_BUCKET] ?: 0)
        val waterBottles = (usedResources[Material.POTION] ?: 0) + (usedResources[Material.HONEY_BOTTLE] ?: 0)

        if (addResultsToInventory(dstInventory, result, waterBuckets, waterBottles) &&
            removeIngredientsFromHoppers(usedResources, hoppers)
        ) commitInventoryChanges(chest, hoppers)

    }

    private fun findConnectedHoppers(blocks: Array<Block>, craftingTable: Block): List<Hopper1> {
        return blocks
            .filter { it.type == Material.HOPPER }
            .filter {
                val dir = (it.state.data as org.bukkit.material.Hopper).facing
                it.getRelative(dir) == craftingTable
            }
            .map {
                val state = it.state as Hopper
                Hopper1(state, state.snapshotInventory)
            }
    }

    private fun collectAvailableResources(hoppers: List<Hopper1>): HashMap<Material, Int> {
        val availableResources = HashMap<Material, Int>()
        for (hopper in hoppers) {
            for (stack in hopper.inventory.contents) {
                stack ?: continue
                if (stack.type == Material.AIR) continue
                availableResources[stack.type] = (availableResources[stack.type] ?: 0) + stack.amount
            }
        }
        return availableResources
    }

    private fun commitInventoryChanges(chest: Chest, hoppers: List<Hopper1>) {
        chest.update(true)
        for (hopper in hoppers) {
            hopper.hopper.update(true)
        }
    }

    @Suppress("RedundantIf")
    private fun addResultsToInventory(
        dstInventory: Inventory, result: ItemStack,
        waterBuckets: Int, waterBottles: Int
    ): Boolean {
        if (dstInventory.addItem(result).isNotEmpty())
            return false
        if (waterBuckets > 0 && dstInventory.addItem(ItemStack(Material.BUCKET, waterBuckets)).isNotEmpty())
            return false
        if (waterBottles > 0 && dstInventory.addItem(ItemStack(Material.GLASS_BOTTLE, waterBottles)).isNotEmpty())
            return false
        return true
    }

    private fun removeIngredientsFromHoppers(
        usedResources: HashMap<Material, Int>,
        hoppers: List<Hopper1>
    ): Boolean {
        resources@ for (resource in usedResources) {
            val toRemove = ItemStack(resource.key, resource.value)
            for (hopper in hoppers) {
                val remaining = hopper.inventory.removeItem(toRemove)
                if (remaining.isEmpty()) continue@resources // done :)
                toRemove.amount = remaining.values.first().amount
            }
            if (toRemove.amount > 0) {
                return false
            }
        }
        return true
    }

    private fun findRecipe(
        target: ItemStack,
        availableResources: HashMap<Material, Int>,
        usedResources: HashMap<Material, Int>
    ): ItemStack? {
        for (recipe in server.getRecipesFor(target)) {
            when (recipe) {
                is ShapedRecipe -> {
                    if (canUseRecipe(recipe, availableResources, usedResources)) {
                        return recipe.result
                    }
                }

                is ShapelessRecipe -> {
                    if (canUseRecipe(recipe, availableResources, usedResources)) {
                        return recipe.result
                    }
                }
            }
        }
        return null
    }

    private fun findItemFrame(block: Block, center: Location): ItemFrame? {
        if (block.type == Material.AIR) {
            for (entity in block.chunk.entities) {
                // actual distance: 0.53125
                if (entity is ItemFrame && entity.location.distance(center) < 0.7) {
                    if (entity.item.type != Material.AIR) {
                        return entity
                    }
                }
            }
        }
        return null
    }

    private fun canUseRecipe(
        recipe: ShapedRecipe,
        availableResources: HashMap<Material, Int>,
        usedResources: HashMap<Material, Int>
    ): Boolean {
        usedResources.clear()
        val map = recipe.ingredientMap
        // println("Checking $map, ${recipe.shape.joinToString("|")}")
        for (row in recipe.shape) {
            for (c in row) {
                val ingredient = map[c] ?: continue
                val usedAmount = ingredient.amount + (usedResources[ingredient.type] ?: 0)
                val availableAmount = availableResources[ingredient.type] ?: return false
                if (usedAmount > availableAmount) return false
                usedResources[ingredient.type] = usedAmount
            }
        }
        return true
    }

    private fun canUseRecipe(
        recipe: ShapelessRecipe,
        availableResources: HashMap<Material, Int>,
        usedResources: HashMap<Material, Int>,
    ): Boolean {
        usedResources.clear()
        // println("Checking ${recipe.choiceList}")
        for (choice in recipe.choiceList) {
            choice as MaterialChoice
            var canBeUsed = false
            for (type in choice.choices) {
                val usedAmount = 1 + (usedResources[type] ?: 0)
                val availableAmount = availableResources[type]
                if (availableAmount == null || usedAmount > availableAmount) continue
                usedResources[type] = usedAmount
                canBeUsed = true
            }
            if (!canBeUsed) return false
        }
        return true
    }

    private fun hasType(b0: Block, b1: Block, b2: Block, b3: Block, b4: Block, b5: Block, type: Material): Boolean {
        return b0.type == type || b1.type == type || b2.type == type || b3.type == type || b4.type == type || b5.type == type
    }

}