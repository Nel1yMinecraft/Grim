package ac.grim.grimac.events.packets;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.nmsutil.XMaterial;
import io.github.retrooper.packetevents.event.PacketListenerAbstract;
import io.github.retrooper.packetevents.event.PacketListenerPriority;
import io.github.retrooper.packetevents.event.impl.PacketPlayReceiveEvent;
import io.github.retrooper.packetevents.packettype.PacketType;
import io.github.retrooper.packetevents.packetwrappers.play.in.entityaction.WrappedPacketInEntityAction;
import io.github.retrooper.packetevents.utils.player.ClientVersion;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public class PacketEntityAction extends PacketListenerAbstract {

    Material elytra = XMaterial.ELYTRA.parseMaterial();

    public PacketEntityAction() {
        super(PacketListenerPriority.LOW);
    }

    @Override
    public void onPacketPlayReceive(PacketPlayReceiveEvent event) {
        if (event.getPacketId() == PacketType.Play.Client.ENTITY_ACTION) {
            WrappedPacketInEntityAction action = new WrappedPacketInEntityAction(event.getNMSPacket());
            GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getPlayer());

            if (player == null) return;

            switch (action.getAction()) {
                case START_SPRINTING:
                    player.isSprinting = true;
                    break;
                case STOP_SPRINTING:
                    player.isSprinting = false;
                    break;
                case START_SNEAKING:
                    player.isSneaking = true;
                    break;
                case STOP_SNEAKING:
                    player.isSneaking = false;
                    break;
                case START_FALL_FLYING:
                    // Starting fall flying is client sided on 1.14 and below
                    if (player.getClientVersion().isOlderThan(ClientVersion.v_1_15)) return;
                    ItemStack chestPlate = player.bukkitPlayer.getInventory().getChestplate();

                    // I have a bad feeling that there might be a way to fly without durability using this
                    // The server SHOULD resync by telling the client to stop using the elytra if they can't fly!
                    // TODO: This needs to check elytra durability (How do I do this cross server version?)
                    if (chestPlate != null && chestPlate.getType() == elytra) {
                        player.isGliding = true;
                        player.pointThreeEstimator.updatePlayerGliding();
                    } else {
                        // A client is flying with a ghost elytra, resync
                        player.getSetbackTeleportUtil().executeForceResync();
                    }
                    break;
                case START_RIDING_JUMP:
                    player.vehicleData.nextHorseJump = action.getJumpBoost();
                    break;
            }
        }
    }
}
