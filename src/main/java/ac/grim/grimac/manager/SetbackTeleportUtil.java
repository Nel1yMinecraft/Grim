package ac.grim.grimac.manager;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.type.PostPredictionCheck;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.data.SetBackData;
import io.github.retrooper.packetevents.utils.pair.Pair;
import io.github.retrooper.packetevents.utils.vector.Vector3d;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

public class SetbackTeleportUtil extends PostPredictionCheck {
    // Sync to NETTY (Why does the bukkit thread have to modify this, can we avoid it?)
    // I think it should be safe enough because the worst that can happen is we overwrite another plugin teleport
    //
    // This is required because the required setback position is not sync to bukkit, and we must avoid
    // setting the player back to a position where they were cheating
    public boolean hasAcceptedSetbackPosition = true;
    // Sync to netty, a player MUST accept a teleport on join
    // Bukkit doesn't call this event, so we need to
    public int acceptedTeleports = 0;
    // Sync to anticheat, tracks the number of predictions ran, so we don't set too far back
    public int processedPredictions = 0;
    // Solves race condition between login event and first teleport send
    public boolean hasFirstSpawned = false;
    // Sync to BUKKIT, referenced by only bukkit!  Don't overwrite another plugin's teleport
    public int lastOtherPluginTeleport = 0;
    // This required setback data is sync to the BUKKIT MAIN THREAD (!)
    SetBackData requiredSetBack = null;
    // Sync to the anticheat thread
    // The anticheat thread MUST be the only thread that controls these safe setback position variables
    boolean wasLastMovementSafe = true;
    // Generally safe teleport position (ANTICHEAT THREAD!)
    SetbackLocationVelocity safeTeleportPosition;
    // This makes it more difficult to abuse setbacks to allow impossible jumps etc. (ANTICHEAT THREAD!)
    SetbackLocationVelocity lastGroundTeleportPosition;
    // Sync to anticheat thread
    Vector lastMovementVel = new Vector();
    // Sync to anything, worst that can happen is sending an extra world update (which won't be noticed)
    long lastWorldResync = 0;

    public SetbackTeleportUtil(GrimPlayer player) {
        super(player);
    }

    /**
     * Generates safe setback locations by looking at the current prediction
     */
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        processedPredictions++;

        // We must first check if the player has accepted their setback
        // If the setback isn't complete, then this position is illegitimate
        if (predictionComplete.getData().acceptedSetback) {
            // If there is a new pending setback, don't desync from the netty thread
            if (!requiredSetBack.isComplete()) return;

            hasAcceptedSetbackPosition = true;

            safeTeleportPosition = new SetbackLocationVelocity(new Vector3d(player.x, player.y, player.z), processedPredictions);
            // Discard ground setback location to avoid "glitchiness" when setting back
            lastGroundTeleportPosition = new SetbackLocationVelocity(new Vector3d(player.x, player.y, player.z), processedPredictions);
        } else if (hasAcceptedSetbackPosition) {
            // Do NOT accept teleports as valid setback positions if the player has a current setback
            // This is due to players being able to trigger new teleports with the vanilla anticheat
            // Thanks Mojang... it's quite ironic that your anticheat makes anticheats harder to write.
            if (predictionComplete.getData().isJustTeleported) {
                // Avoid setting the player back to positions before this teleport
                safeTeleportPosition = new SetbackLocationVelocity(new Vector3d(player.x, player.y, player.z), processedPredictions);
                lastGroundTeleportPosition = new SetbackLocationVelocity(new Vector3d(player.x, player.y, player.z), processedPredictions);
            } else if (wasLastMovementSafe) {
                safeTeleportPosition = new SetbackLocationVelocity(new Vector3d(player.lastX, player.lastY, player.lastZ), lastMovementVel, processedPredictions);

                if ((player.onGround || player.exemptOnGround() || player.isGliding) && player.uncertaintyHandler.lastTeleportTicks < -3) {
                    // Avoid setting velocity when teleporting players back to the ground
                    lastGroundTeleportPosition = new SetbackLocationVelocity(new Vector3d(player.lastX, player.lastY, player.lastZ), processedPredictions);
                }
            }
        }
        wasLastMovementSafe = hasAcceptedSetbackPosition;
        lastMovementVel = player.clientVelocity;
    }

    public void executeSetback(boolean allowTeleportToGround) {
        Vector setbackVel = new Vector();

        if (player.firstBreadKB != null) {
            setbackVel = player.firstBreadKB.vector;
        }

        if (player.likelyKB != null) {
            setbackVel = player.likelyKB.vector;
        }

        if (player.firstBreadExplosion != null) {
            setbackVel.add(player.firstBreadExplosion.vector);
        }

        if (player.likelyExplosions != null) {
            setbackVel.add(player.likelyExplosions.vector);
        }

        SetbackLocationVelocity data;
        boolean ground = false;
        if (!allowTeleportToGround) {
            // Don't use ground setback location for non-anticheat thread setbacks
            data = safeTeleportPosition;
        } else if (processedPredictions - lastGroundTeleportPosition.creation < 10 && Math.abs(player.predictedVelocity.vector.getY() - player.actualMovement.getY()) > 0.01
                // And the player, last tick, was currently by the ground
                && (player.lastY > lastGroundTeleportPosition.position.getY())) {
            // The player is likely to be using vertical movement cheats
            // And the player is currently above the setback location (avoids VoidTP cheats) - use lastY to stop abuse
            // And this velocity was within 10 movements (the amount of time it takes to jump and land onto the ground)
            data = lastGroundTeleportPosition;
            ground = true;
        } else {
            data = safeTeleportPosition;
        }

        // If the player has no explosion/velocity, set them back to the data's stored velocity
        if (setbackVel.equals(new Vector())) setbackVel = data.velocity;

        blockMovementsUntilResync(player.playerWorld, data.position,
                // When teleporting to the ground, don't set player velocity to prevent exploiting setback velocities
                // TODO: LiquidBounce MotionNCP "bypasses" step setback because we set it's velocity to jumping
                // which allows jumping, although it should still consume the player's hunger... is this a bypass?
                // It's 100% a legit movement, taking more time than the equivalent legit movement would take...
                player.packetStateData.packetPlayerXRot, player.packetStateData.packetPlayerYRot, ground ? new Vector() : setbackVel,
                player.vehicle, false);
    }

    private void blockMovementsUntilResync(World world, Vector3d position, float xRot, float yRot, Vector velocity, Integer vehicle, boolean force) {
        // Don't teleport cross world, it will break more than it fixes.
        if (world != player.bukkitPlayer.getWorld()) return;

        SetBackData setBack = requiredSetBack;
        if (force || setBack == null || setBack.isComplete()) {
            // Deal with ghost blocks near the player (from anticheat/netty thread)
            // Only let us full resync once every two seconds to prevent unneeded netty load
            if (System.nanoTime() - lastWorldResync > 2e-9) {
                player.getResyncWorldUtil().resyncPositions(player, player.boundingBox.copy().expand(1), false);
                lastWorldResync = System.nanoTime();
            }

            hasAcceptedSetbackPosition = false;
            int transaction = player.lastTransactionReceived;

            Bukkit.getScheduler().runTask(GrimAPI.INSTANCE.getPlugin(), () -> {
                // A plugin teleport has overridden this teleport
                if (lastOtherPluginTeleport >= transaction) {
                    return;
                }

                requiredSetBack = new SetBackData(world, position, xRot, yRot, velocity, vehicle, player.lastTransactionSent.get());

                // Vanilla is terrible at handling regular player teleports when in vehicle, eject to avoid issues
                Entity playerVehicle = player.bukkitPlayer.getVehicle();
                player.bukkitPlayer.eject();

                // Mojang is terrible and tied together:
                // on fire, is crouching, riding, sprinting, swimming, invisible, has glowing effect, fall flying
                // into one byte!  At least this gives me a very easy method to resync metadata on all server versions
                boolean isSneaking = player.bukkitPlayer.isSneaking();
                player.bukkitPlayer.setSneaking(!isSneaking);
                player.bukkitPlayer.setSneaking(isSneaking);

                if (playerVehicle != null) {
                    // Stop the player from being able to teleport vehicles and simply re-enter them to continue
                    playerVehicle.teleport(new Location(world, position.getX(), position.getY(), position.getZ(), playerVehicle.getLocation().getYaw(), playerVehicle.getLocation().getPitch()));
                }

                player.bukkitPlayer.teleport(new Location(world, position.getX(), position.getY(), position.getZ(), xRot, yRot));
                player.bukkitPlayer.setVelocity(vehicle == null ? velocity : new Vector());
            });
        }
    }

    public void tryResendExpiredSetback() {
        SetBackData setBack = requiredSetBack;

        if (setBack != null && !setBack.isComplete() && setBack.getTrans() + 2 < player.packetStateData.packetLastTransactionReceived.get()) {
            resendSetback(true);
        }
    }

    /**
     * @param force - Should we setback the player to the last position regardless of if they have
     *              accepted the teleport, useful for overriding vanilla anticheat teleports.
     */
    public void resendSetback(boolean force) {
        SetBackData setBack = requiredSetBack;

        if (setBack != null && (!setBack.isComplete() || force)) {
            blockMovementsUntilResync(setBack.getWorld(), setBack.getPosition(), setBack.getXRot(), setBack.getYRot(), setBack.getVelocity(), setBack.getVehicle(), force);
        }
    }

    /**
     * @param x - Player X position
     * @param y - Player Y position
     * @param z - Player Z position
     * @return - Whether the player has completed a teleport by being at this position
     */
    public boolean checkTeleportQueue(double x, double y, double z) {
        // Support teleports without teleport confirmations
        // If the player is in a vehicle when teleported, they will exit their vehicle
        int lastTransaction = player.packetStateData.packetLastTransactionReceived.get();
        boolean isTeleport = false;
        player.packetStateData.wasSetbackLocation = false;

        if (!hasFirstSpawned && player.loginLocation.equals(new Vector3d(x, y, z))) {
            hasFirstSpawned = true;
            acceptedTeleports++;
            isTeleport = true;
        }

        while (true) {
            Pair<Integer, Vector3d> teleportPos = player.teleports.peek();
            if (teleportPos == null) break;

            Vector3d position = teleportPos.getSecond();

            if (lastTransaction < teleportPos.getFirst()) {
                break;
            }

            // Don't use prediction data because it doesn't allow positions past 29,999,999 blocks
            if (position.getX() == x && position.getY() == y && position.getZ() == z) {
                player.teleports.poll();
                acceptedTeleports++;

                SetBackData setBack = requiredSetBack;

                // Player has accepted their setback!
                if (setBack != null && requiredSetBack.getPosition().equals(teleportPos.getSecond())) {
                    player.packetStateData.wasSetbackLocation = true;
                    setBack.setComplete(true);
                }

                isTeleport = true;
            } else if (lastTransaction > teleportPos.getFirst() + 2) {
                player.teleports.poll();

                // Ignored teleport!  We should really do something about this!
                continue;
            }

            break;
        }

        return isTeleport;
    }

    /**
     * @param x - Player X position
     * @param y - Player Y position
     * @param z - Player Z position
     * @return - Whether the player has completed a teleport by being at this position
     */
    public boolean checkVehicleTeleportQueue(double x, double y, double z) {
        int lastTransaction = player.packetStateData.packetLastTransactionReceived.get();
        player.packetStateData.wasSetbackLocation = false;

        while (true) {
            Pair<Integer, Vector3d> teleportPos = player.vehicleData.vehicleTeleports.peek();
            if (teleportPos == null) break;
            if (lastTransaction < teleportPos.getFirst()) {
                break;
            }

            Vector3d position = teleportPos.getSecond();
            if (position.getX() == x && position.getY() == y && position.getZ() == z) {
                player.vehicleData.vehicleTeleports.poll();

                return true;
            } else if (lastTransaction > teleportPos.getFirst() + 2) {
                player.vehicleData.vehicleTeleports.poll();

                // Vehicles have terrible netcode so just ignore it if the teleport wasn't from us setting the player back
                // Players don't have to respond to vehicle teleports if they aren't controlling the entity anyways
                continue;
            }

            break;
        }

        return false;
    }

    /**
     * @return Whether the current setback has been completed
     */
    public boolean shouldBlockMovement() {
        SetBackData setBack = requiredSetBack;
        // Block if the player has a pending setback
        // or hasn't spawned in the world yet
        return (setBack != null && !setBack.isComplete()) || acceptedTeleports == 0;
    }

    /**
     * @return The current data for the setback, regardless of whether it is complete or not
     */
    public SetBackData getRequiredSetBack() {
        return requiredSetBack;
    }

    /**
     * This method is unsafe to call outside the bukkit thread
     * This method sets a plugin teleport at this location
     *
     * @param position Position of the teleport
     */
    public void setSetback(Vector3d position) {
        setSafeTeleportPositionFromTeleport(position);

        requiredSetBack = new SetBackData(player.bukkitPlayer.getWorld(), position, player.packetStateData.packetPlayerXRot,
                player.packetStateData.packetPlayerYRot, new Vector(), null, player.lastTransactionSent.get());
        hasAcceptedSetbackPosition = false;
        lastOtherPluginTeleport = player.lastTransactionSent.get();
    }

    /**
     * This method is unsafe to call outside the bukkit thread
     *
     * @param position A safe setback location
     */
    public void setSafeTeleportPositionFromTeleport(Vector3d position) {
        this.safeTeleportPosition = new SetbackLocationVelocity(position, player.movementPackets);
        this.lastGroundTeleportPosition = new SetbackLocationVelocity(position, processedPredictions);
    }
}

class SetbackLocationVelocity {
    Vector3d position;
    Vector velocity = new Vector();
    int creation;

    public SetbackLocationVelocity(Vector3d position, int creation) {
        this.position = position;
        this.creation = creation;
    }

    public SetbackLocationVelocity(Vector3d position, Vector velocity, int creation) {
        this.position = position;
        this.velocity = velocity;
        this.creation = creation;
    }
}
