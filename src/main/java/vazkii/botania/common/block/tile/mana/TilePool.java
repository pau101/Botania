/**
 * This class was created by <Vazkii>. It's distributed as
 * part of the Botania Mod. Get the Source Code in github:
 * https://github.com/Vazkii/Botania
 * 
 * Botania is Open Source and distributed under the
 * Botania License: http://botaniamod.net/license.php
 * 
 * File Created @ [Jan 26, 2014, 12:23:55 AM (GMT)]
 */
package vazkii.botania.common.block.tile.mana;

import com.google.common.base.Predicates;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import vazkii.botania.api.BotaniaAPI;
import vazkii.botania.api.internal.VanillaPacketDispatcher;
import vazkii.botania.api.item.IManaDissolvable;
import vazkii.botania.api.mana.IKeyLocked;
import vazkii.botania.api.mana.IManaItem;
import vazkii.botania.api.mana.IManaPool;
import vazkii.botania.api.mana.IThrottledPacket;
import vazkii.botania.api.mana.ManaNetworkEvent;
import vazkii.botania.api.mana.spark.ISparkAttachable;
import vazkii.botania.api.mana.spark.ISparkEntity;
import vazkii.botania.api.recipe.RecipeManaInfusion;
import vazkii.botania.api.sound.BotaniaSoundEvents;
import vazkii.botania.api.state.BotaniaStateProps;
import vazkii.botania.api.state.enums.PoolVariant;
import vazkii.botania.client.core.handler.HUDHandler;
import vazkii.botania.client.core.helper.RenderHelper;
import vazkii.botania.client.lib.LibResources;
import vazkii.botania.common.Botania;
import vazkii.botania.common.block.ModBlocks;
import vazkii.botania.common.block.tile.TileMod;
import vazkii.botania.common.core.handler.ConfigHandler;
import vazkii.botania.common.core.handler.ManaNetworkHandler;
import vazkii.botania.common.core.handler.MethodHandles;
import vazkii.botania.common.item.ItemManaTablet;
import vazkii.botania.common.item.ModItems;
import vazkii.botania.common.network.PacketBotaniaEffect;
import vazkii.botania.common.network.PacketHandler;

import javax.annotation.Nonnull;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class TilePool extends TileMod implements IManaPool, IKeyLocked, ISparkAttachable, IThrottledPacket {

	public static final Color PARTICLE_COLOR = new Color(0x00C6FF);
	public static final int MAX_MANA = 1000000;
	public static final int MAX_MANA_DILLUTED = 10000;

	private static final String TAG_MANA = "mana";
	private static final String TAG_KNOWN_MANA = "knownMana";
	private static final String TAG_OUTPUTTING = "outputting";
	private static final String TAG_COLOR = "color";
	private static final String TAG_MANA_CAP = "manaCap";
	private static final String TAG_CAN_ACCEPT = "canAccept";
	private static final String TAG_CAN_SPARE = "canSpare";
	private static final String TAG_FRAGILE = "fragile";
	private static final String TAG_INPUT_KEY = "inputKey";
	private static final String TAG_OUTPUT_KEY = "outputKey";

	private boolean outputting = false;

	public EnumDyeColor color = EnumDyeColor.WHITE;
	int mana;
	private int knownMana = -1;

	public int manaCap = -1;
	private int soundTicks = 0;
	private boolean canAccept = true;
	private boolean canSpare = true;
	public boolean fragile = false;
	boolean isDoingTransfer = false;
	int ticksDoingTransfer = 0;

	private String inputKey = "";
	private final String outputKey = "";

	private int ticks = 0;
	private boolean sendPacket = false;

	@Override
	public boolean shouldRefresh(World world, BlockPos pos, @Nonnull IBlockState oldState, @Nonnull IBlockState newState) {
		if(oldState.getBlock() != newState.getBlock())
			return true;
		if(oldState.getBlock() != ModBlocks.pool || newState.getBlock() != ModBlocks.pool)
			return true;
		return oldState.getValue(BotaniaStateProps.POOL_VARIANT) != newState.getValue(BotaniaStateProps.POOL_VARIANT);
	}

	@Override
	public boolean isFull() {
		Block blockBelow = worldObj.getBlockState(pos.down()).getBlock();
		return blockBelow != ModBlocks.manaVoid && getCurrentMana() >= manaCap;
	}

	@Override
	public void recieveMana(int mana) {
		this.mana = Math.max(0, Math.min(getCurrentMana() + mana, manaCap));
		worldObj.updateComparatorOutputLevel(pos, worldObj.getBlockState(pos).getBlock());
		markDispatchable();
	}

	@Override
	public void invalidate() {
		super.invalidate();
		ManaNetworkEvent.removePool(this);
	}

	@Override
	public void onChunkUnload() {
		super.onChunkUnload();
		invalidate();
	}

	public static RecipeManaInfusion getMatchingRecipe(@Nonnull ItemStack stack, @Nonnull IBlockState state) {
		List<RecipeManaInfusion> matchingNonCatRecipes = new ArrayList<>();
		List<RecipeManaInfusion> matchingCatRecipes = new ArrayList<>();

		for (RecipeManaInfusion recipe : BotaniaAPI.manaInfusionRecipes) {
			if (recipe.matches(stack)) {
				if(recipe.getCatalyst() == null)
					matchingNonCatRecipes.add(recipe);
				else if (recipe.getCatalyst() == state)
					matchingCatRecipes.add(recipe);
			}
		}

		// Recipes with matching catalyst take priority above recipes with no catalyst specified
		return !matchingCatRecipes.isEmpty() ? matchingCatRecipes.get(0) :
				!matchingNonCatRecipes.isEmpty() ? matchingNonCatRecipes.get(0) :
				null;
	}

	public boolean collideEntityItem(EntityItem item) {
		if(worldObj.isRemote || item.isDead)
			return false;

		ItemStack stack = item.getEntityItem();

		if(stack.getItem() instanceof IManaDissolvable) {
			((IManaDissolvable) stack.getItem()).onDissolveTick(this, stack, item);
			if(stack.stackSize == 0)
				item.setDead();
		}

		int age;
		try {
			age = ((int) MethodHandles.itemAge_getter.invokeExact(item));
		} catch (Throwable throwable) { return false; }

		if(age > 100 && age < 130)
			return false;

		RecipeManaInfusion recipe = getMatchingRecipe(stack, worldObj.getBlockState(pos.down()));

		if(recipe != null) {
			int mana = recipe.getManaToConsume();
			if(getCurrentMana() >= mana) {
				recieveMana(-mana);

				stack.stackSize--;
				if(stack.stackSize == 0)
					item.setDead();

				ItemStack output = recipe.getOutput().copy();
				EntityItem outputItem = new EntityItem(worldObj, pos.getX() + 0.5, pos.getY() + 1.5, pos.getZ() + 0.5, output);
				try {
					MethodHandles.itemAge_setter.invokeExact(outputItem, 105);
				} catch (Throwable ignored) {}
				worldObj.spawnEntityInWorld(outputItem);

				craftingFanciness();
				return true;
			}
		}

		return false;
	}

	private void craftingFanciness() {
		if(soundTicks == 0) {
			worldObj.playSound(null, pos, BotaniaSoundEvents.manaPoolCraft, SoundCategory.BLOCKS, 0.4F, 4F);
			soundTicks = 6;
		}

		PacketHandler.sendToNearby(worldObj, getPos(),
				new PacketBotaniaEffect(PacketBotaniaEffect.EffectType.POOL_CRAFT, pos.getX(), pos.getY(), pos.getZ()));
	}

	@Override
	public void update() {
		if(manaCap == -1)
			manaCap = worldObj.getBlockState(getPos()).getValue(BotaniaStateProps.POOL_VARIANT) == PoolVariant.DILUTED ? MAX_MANA_DILLUTED : MAX_MANA;

		if(!ManaNetworkHandler.instance.isPoolIn(this) && !isInvalid())
			ManaNetworkEvent.addPool(this);

		if(worldObj.isRemote) {
			double particleChance = 1F - (double) getCurrentMana() / (double) manaCap * 0.1;
			if(Math.random() > particleChance)
				Botania.proxy.wispFX(pos.getX() + 0.3 + Math.random() * 0.5, pos.getY() + 0.6 + Math.random() * 0.25, pos.getZ() + Math.random(), PARTICLE_COLOR.getRed() / 255F, PARTICLE_COLOR.getGreen() / 255F, PARTICLE_COLOR.getBlue() / 255F, (float) Math.random() / 3F, (float) -Math.random() / 25F, 2F);
			return;
		}

		boolean wasDoingTransfer = isDoingTransfer;
		isDoingTransfer = false;

		if(soundTicks > 0) {
			soundTicks--;
		}

		if(sendPacket && ticks % 10 == 0) {
			VanillaPacketDispatcher.dispatchTEToNearbyPlayers(this);
			sendPacket = false;
		}

		List<EntityItem> items = worldObj.getEntitiesWithinAABB(EntityItem.class, new AxisAlignedBB(pos, pos.add(1, 1, 1)));
		for(EntityItem item : items) {
			if(item.isDead)
				continue;

			ItemStack stack = item.getEntityItem();
			if(stack != null && stack.getItem() instanceof IManaItem) {
				IManaItem mana = (IManaItem) stack.getItem();
				if(outputting && mana.canReceiveManaFromPool(stack, this) || !outputting && mana.canExportManaToPool(stack, this)) {
					boolean didSomething = false;

					int bellowCount = 0;
					if(outputting)
						for(EnumFacing dir : EnumFacing.HORIZONTALS) {
							TileEntity tile = worldObj.getTileEntity(pos.offset(dir));
							if(tile != null && tile instanceof TileBellows && ((TileBellows) tile).getLinkedTile() == this)
								bellowCount++;
						}
					int transfRate = 1000 * (bellowCount + 1);

					if(outputting) {
						if(canSpare) {
							if(getCurrentMana() > 0 && mana.getMana(stack) < mana.getMaxMana(stack))
								didSomething = true;

							int manaVal = Math.min(transfRate, Math.min(getCurrentMana(), mana.getMaxMana(stack) - mana.getMana(stack)));
							mana.addMana(stack, manaVal);
							recieveMana(-manaVal);
						}
					} else {
						if(canAccept) {
							if(mana.getMana(stack) > 0 && !isFull())
								didSomething = true;

							int manaVal = Math.min(transfRate, Math.min(manaCap - getCurrentMana(), mana.getMana(stack)));
							mana.addMana(stack, -manaVal);
							recieveMana(manaVal);
						}
					}

					if(didSomething) {
						if(ConfigHandler.chargingAnimationEnabled && worldObj.rand.nextInt(20) == 0) {
							PacketHandler.sendToNearby(worldObj, getPos(),
									new PacketBotaniaEffect(PacketBotaniaEffect.EffectType.POOL_CHARGE, getPos().getX(), getPos().getY(), getPos().getZ(), outputting ? 1 : 0));
						}
						isDoingTransfer = outputting;
					}
				}
			}
		}

		if(isDoingTransfer)
			ticksDoingTransfer++;
		else {
			ticksDoingTransfer = 0;
			if(wasDoingTransfer)
				VanillaPacketDispatcher.dispatchTEToNearbyPlayers(this);
		}

		ticks++;
	}

	@Override
	public void writePacketNBT(NBTTagCompound cmp) {
		cmp.setInteger(TAG_MANA, mana);
		cmp.setBoolean(TAG_OUTPUTTING, outputting);
		cmp.setInteger(TAG_COLOR, color.getMetadata());

		cmp.setInteger(TAG_MANA_CAP, manaCap);
		cmp.setBoolean(TAG_CAN_ACCEPT, canAccept);
		cmp.setBoolean(TAG_CAN_SPARE, canSpare);
		cmp.setBoolean(TAG_FRAGILE, fragile);

		cmp.setString(TAG_INPUT_KEY, inputKey);
		cmp.setString(TAG_OUTPUT_KEY, outputKey);
	}

	@Override
	public void readPacketNBT(NBTTagCompound cmp) {
		mana = cmp.getInteger(TAG_MANA);
		outputting = cmp.getBoolean(TAG_OUTPUTTING);
		color = EnumDyeColor.byMetadata(cmp.getInteger(TAG_COLOR));

		if(cmp.hasKey(TAG_MANA_CAP))
			manaCap = cmp.getInteger(TAG_MANA_CAP);
		if(cmp.hasKey(TAG_CAN_ACCEPT))
			canAccept = cmp.getBoolean(TAG_CAN_ACCEPT);
		if(cmp.hasKey(TAG_CAN_SPARE))
			canSpare = cmp.getBoolean(TAG_CAN_SPARE);
		fragile = cmp.getBoolean(TAG_FRAGILE);

		if(cmp.hasKey(TAG_INPUT_KEY))
			inputKey = cmp.getString(TAG_INPUT_KEY);
		if(cmp.hasKey(TAG_OUTPUT_KEY))
			inputKey = cmp.getString(TAG_OUTPUT_KEY);

		if(cmp.hasKey(TAG_KNOWN_MANA))
			knownMana = cmp.getInteger(TAG_KNOWN_MANA);
	}

	public void onWanded(EntityPlayer player, ItemStack wand) {
		if(player == null)
			return;

		if(player.isSneaking()) {
			outputting = !outputting;
			VanillaPacketDispatcher.dispatchTEToNearbyPlayers(worldObj, pos);
		}

		if(!worldObj.isRemote) {
			NBTTagCompound nbttagcompound = new NBTTagCompound();
			writePacketNBT(nbttagcompound);
			nbttagcompound.setInteger(TAG_KNOWN_MANA, getCurrentMana());
			if(player instanceof EntityPlayerMP)
				((EntityPlayerMP) player).connection.sendPacket(new SPacketUpdateTileEntity(pos, -999, nbttagcompound));
		}

		worldObj.playSound(null, player.posX, player.posY, player.posZ, BotaniaSoundEvents.ding, SoundCategory.PLAYERS, 0.11F, 1F);
	}

	@SideOnly(Side.CLIENT)
	public void renderHUD(Minecraft mc, ScaledResolution res) {
		ItemStack pool = new ItemStack(ModBlocks.pool, 1, worldObj.getBlockState(getPos()).getValue(BotaniaStateProps.POOL_VARIANT).ordinal());
		String name = I18n.format(pool.getUnlocalizedName().replaceAll("tile.", "tile." + LibResources.PREFIX_MOD) + ".name");
		int color = 0x4444FF;
		HUDHandler.drawSimpleManaHUD(color, knownMana, manaCap, name, res);

		int x = res.getScaledWidth() / 2 - 11;
		int y = res.getScaledHeight() / 2 + 30;

		int u = outputting ? 22 : 0;
		int v = 38;

		GlStateManager.enableBlend();
		GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

		mc.renderEngine.bindTexture(HUDHandler.manaBar);
		RenderHelper.drawTexturedModalRect(x, y, 0, u, v, 22, 15);
		GlStateManager.color(1F, 1F, 1F, 1F);

		ItemStack tablet = new ItemStack(ModItems.manaTablet);
		ItemManaTablet.setStackCreative(tablet);

		net.minecraft.client.renderer.RenderHelper.enableGUIStandardItemLighting();
		mc.getRenderItem().renderItemAndEffectIntoGUI(tablet, x - 20, y);
		mc.getRenderItem().renderItemAndEffectIntoGUI(pool, x + 26, y);
		net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();

		GlStateManager.disableLighting();
		GlStateManager.disableBlend();
	}

	@Override
	public boolean canRecieveManaFromBursts() {
		return true;
	}

	@Override
	public boolean isOutputtingPower() {
		return outputting;
	}

	@Override
	public int getCurrentMana() {
		return worldObj != null && worldObj.getBlockState(getPos()).getValue(BotaniaStateProps.POOL_VARIANT) == PoolVariant.CREATIVE ? MAX_MANA : mana;
	}

	@Override
	public String getInputKey() {
		return inputKey;
	}

	@Override
	public String getOutputKey() {
		return outputKey;
	}

	@Override
	public boolean canAttachSpark(ItemStack stack) {
		return true;
	}

	@Override
	public void attachSpark(ISparkEntity entity) {}

	@Override
	public ISparkEntity getAttachedSpark() {
		List sparks = worldObj.getEntitiesWithinAABB(Entity.class, new AxisAlignedBB(pos.up(), pos.up().add(1, 1, 1)), Predicates.instanceOf(ISparkEntity.class));
		if(sparks.size() == 1) {
			Entity e = (Entity) sparks.get(0);
			return (ISparkEntity) e;
		}

		return null;
	}

	@Override
	public boolean areIncomingTranfersDone() {
		return false;
	}

	@Override
	public int getAvailableSpaceForMana() {
		return Math.max(0, manaCap - getCurrentMana());
	}

	@Override
	public EnumDyeColor getColor() {
		return color;
	}

	@Override
	public void setColor(EnumDyeColor color) {
		this.color = color;
		worldObj.notifyBlockUpdate(pos, worldObj.getBlockState(pos), worldObj.getBlockState(pos), 0b1011);
	}

	@Override
	public void markDispatchable() {
		sendPacket = true;
	}
}
