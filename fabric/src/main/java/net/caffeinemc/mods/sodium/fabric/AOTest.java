package net.caffeinemc.mods.sodium.fabric;

import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableMesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.client.renderer.v1.render.AltModelBlockRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

@Environment(EnvType.CLIENT)
public class AOTest {

	public static Mesh mesh;
	public static BlockPos drawPos;

	//call in mod initializer or something.
	public static void init() {
		LevelRenderEvents.COLLECT_SUBMITS.register((LevelRenderContext context) -> {
			if (mesh != null) {
				Vec3 pos = context.levelState().cameraRenderState.pos;
				context.poseStack().pushPose();
				context.poseStack().translate(
					drawPos.getX() - pos.x,
					drawPos.getY() - pos.y,
					drawPos.getZ() - pos.z
				);
				Pose pose = context.poseStack().last();
				VertexConsumer buffer = context.bufferSource().getBuffer(RenderTypes.cutoutMovingBlock());
				mesh.forEach((QuadView quad) -> quad.buffer(OverlayTexture.NO_OVERLAY, pose, buffer));
				context.poseStack().popPose();
			}
		});
	}

	//call once you're in a world and have some blocks with AO around you.
	public static void capture() {
		LocalPlayer player = Minecraft.getInstance().player;
		BlockPos playerPos = player.blockPosition();
		MutableBlockPos mutablePos = playerPos.mutable();
		ClientLevel world = (ClientLevel)(player.level());
		MutableMesh mesh = Renderer.get().mutableMesh();
		QuadEmitter emitter = mesh.emitter();
		AltModelBlockRenderer renderer = Renderer.get().altModelBlockRenderer(true, true, Minecraft.getInstance().getBlockColors());
		BlockStateModelSet models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
		for (BlockPos pos : BlockPos.betweenClosed(-5, -5, -5, +5, +5, +5)) {
			BlockState state = world.getBlockState(mutablePos.setWithOffset(playerPos, pos));
			renderer.tesselateBlock(
				emitter,
				pos.getX(),
				pos.getY(),
				pos.getZ(),
				world,
				mutablePos,
				state,
				models.get(state),
				state.getSeed(mutablePos)
			);
		}
		AOTest.mesh = mesh.immutableCopy();
		AOTest.drawPos = playerPos.above(10);
	}
}