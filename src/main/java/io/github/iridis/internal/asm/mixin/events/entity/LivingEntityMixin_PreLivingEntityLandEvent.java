package io.github.iridis.internal.asm.mixin.events.entity;

import io.github.iridis.internal.events.EntityEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import v1_16_1.net.minecraft.block.IBlockState;
import v1_16_1.net.minecraft.entity.ILivingEntity;
import v1_16_1.net.minecraft.util.math.IBlockPos;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;

@Mixin (Entity.class)
public abstract class LivingEntityMixin_PreLivingEntityLandEvent {
	@Redirect (method = "move",
			at = @At (value = "INVOKE",
					target = "Lnet/minecraft/entity/Entity;fall(DZLnet/minecraft/block/BlockState;Lnet/minecraft/util/math/BlockPos;)V"))
	private void onFall(Entity entity, double heightDifference, boolean onGround, BlockState landedState, BlockPos landedPosition) {
		if (!EntityEvents.preLivingEnitityLandEvent((ILivingEntity) this,
		                                            heightDifference,
		                                            onGround,
		                                            (IBlockState) landedState,
		                                            (IBlockPos) landedPosition)) {
			this.fall(heightDifference, onGround, landedState, landedPosition);
		}
	}

	@Shadow
	protected abstract void fall(double heightDifference, boolean onGround, BlockState landedState, BlockPos landedPosition);
}
