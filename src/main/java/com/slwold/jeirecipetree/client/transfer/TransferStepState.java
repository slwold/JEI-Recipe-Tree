package com.slwold.jeirecipetree.client.transfer;

import com.slwold.jeirecipetree.client.data.*;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;
import com.slwold.jeirecipetree.client.storage.*;

import net.minecraft.world.item.ItemStack;

public final class TransferStepState {

    int menuId = -1;

    int resultSlot = -1;

    boolean genericResultProbe;

    boolean ae2Carry;
    boolean ae2NetworkDeposit;
    boolean ae2NetworkRequest;

    boolean ae2NetworkSkip;
    int ae2NetworkCount;
    int ae2PlacementSlot = -1;
    boolean ae2PlacementInFlight;
    int ae2PlacementCount;
    long ae2PlacementInventoryBefore;
    String ae2OutputKey = "";
    ItemStack ae2OutputStack = ItemStack.EMPTY;
    long ae2InventoryBefore;

    boolean vanillaPickup;
    int pickupAttempts;

    long rtsPickupBefore = -1L;

    String rtsPickupKey = "";

    boolean restockPending;

    boolean restockDone;

    RecipeTreeData.CraftStep restockTarget;
    int restockChunk;
    boolean restockRecursive;
    boolean restockMaxTransfer;

    int waitFrames;

    int emptyFrames;

    boolean fillLogged;

    int fillWaitedFrames;

    int stepFrames;

    String destination = "";

    void resetStep() {
        ae2Carry = false;
        ae2NetworkDeposit = false;
        ae2NetworkRequest = false;
        ae2NetworkSkip = false;
        ae2NetworkCount = 0;
        ae2PlacementSlot = -1;
        ae2PlacementInFlight = false;
        ae2PlacementCount = 0;
        ae2PlacementInventoryBefore = 0;
        ae2OutputKey = "";
        ae2OutputStack = ItemStack.EMPTY;
        ae2InventoryBefore = 0;
        vanillaPickup = false;
        pickupAttempts = 0;
        rtsPickupBefore = -1L;
        rtsPickupKey = "";

        restockPending = false;
        restockDone = false;
        restockTarget = null;
        restockChunk = 0;
        restockRecursive = false;
        restockMaxTransfer = false;
        emptyFrames = 0;
        waitFrames = 2;
        fillLogged = false;
        fillWaitedFrames = 0;
        stepFrames = 0;
        destination = "";
    }

    void resetAll() {
        resetStep();
        menuId = -1;
        resultSlot = -1;
        genericResultProbe = false;
        waitFrames = 0;
        emptyFrames = 0;
    }

    String describe() {
        if (restockPending) {
            return "restockPending(" + StorageRestock.describe() + ")";
        }
        if (ae2Carry) {
            return "ae2Carry(placement=" + ae2PlacementSlot
                + ",inFlight=" + ae2PlacementInFlight + ",deposit=" + ae2NetworkDeposit + ")";
        }
        if (vanillaPickup) {
            return "vanillaPickup(attempts=" + pickupAttempts + ")";
        }
        if (genericResultProbe) {
            return "genericResultProbe(emptyFrames=" + emptyFrames + ")";
        }
        if (resultSlot >= 0) {
            return "resultWait(slot=" + resultSlot + ",emptyFrames=" + emptyFrames + ")";
        }
        return "idle";
    }
}
