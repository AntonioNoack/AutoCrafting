package me.anno.autocrafting;

import org.bukkit.block.Hopper;
import org.bukkit.inventory.Inventory;

public class Hopper1 {

    final Hopper hopper;
    final Inventory inventory;

    protected Hopper1(Hopper hopper) {
        this.hopper = hopper;
        this.inventory = hopper.getSnapshotInventory();
    }
}