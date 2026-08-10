package com.hexagram2021.sweeper_maid.save;

import com.google.common.collect.Lists;
import com.hexagram2021.sweeper_maid.config.SMCommonConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 清洁女仆模组存档数据类喵~
 * <p>
 * 负责管理垃圾箱数据，包括：
 * <ul>
 *     <li>存储清理后的物品到多个垃圾箱容器中喵~</li>
 *     <li>NBT 数据序列化和反序列化喵~</li>
 *     <li>垃圾箱初始化和动态调整喵~</li>
 *     <li>线程安全的垃圾箱访问喵~</li>
 * </ul>
 * </p>
 *
 * @author liudongyu
 */
public final class SMSavedData extends SavedData {
	@SuppressWarnings("java:S3008")
	@Nullable
	private static SMSavedData INSTANCE;
	/**
	 * 存档数据名称喵~
	 */
	public static final String SAVED_DATA_NAME = "SweeperMaid-SavedData";
	/**
	 * 单个容器的最大物品槽位数喵~
	 */
	private static final int MAX_CONTAINER_SIZE = 54;

	/**
	 * 垃圾箱容器列表喵~
	 */
	public final List<SimpleContainer> dustbins = Lists.newArrayList();

	/**
	 * 默认构造方法喵~
	 */
	public SMSavedData() {
		super();
	}

	/**
	 * NBT 数据标签 - 物品列表喵~
	 */
	private static final String TAG_ITEMS = "Items";
	/**
	 * NBT 数据标签 - 垃圾箱列表喵~
	 */
	private static final String TAG_DUSTBINS = "Dustbins";

	/**
	 * 从 NBT 数据加载存档数据喵~
	 *
	 * @param nbt NBT 标签喵~
	 */
	public SMSavedData(CompoundTag nbt) {
		this();
		if (nbt.contains(TAG_DUSTBINS, Tag.TAG_LIST)) {
			ListTag dustbinList = nbt.getList(TAG_DUSTBINS, Tag.TAG_COMPOUND);
			synchronized (this.dustbins) {
				this.dustbins.clear();
				for (int i = 0; i < dustbinList.size(); ++i) {
					CompoundTag dustbinTag = dustbinList.getCompound(i);
					SimpleContainer dustbin = createNewDustbin();
					dustbin.fromTag(dustbinTag.getList(TAG_ITEMS, Tag.TAG_COMPOUND));
					this.dustbins.add(dustbin);
				}
			}
		}
	}

	/**
	 * 将存档数据保存到 NBT 标签喵~
	 *
	 * @param nbt NBT 标签喵~
	 * @return 保存后的 NBT 标签喵~
	 */
	@Override
	public CompoundTag save(CompoundTag nbt) {
		synchronized (this.dustbins) {
			ListTag dustbinList = new ListTag();
			for (SimpleContainer dustbin : this.dustbins) {
				CompoundTag dustbinTag = new CompoundTag();
				dustbinTag.put(TAG_ITEMS, dustbin.createTag());
				dustbinList.add(dustbinTag);
			}
			nbt.put(TAG_DUSTBINS, dustbinList);
		}
		return nbt;
	}

	/**
	 * 获取存档数据实例喵~
	 *
	 * @return 存档数据实例喵~
	 * @throws NullPointerException 如果实例尚未初始化喵~
	 */
	public static SMSavedData getInstance() {
		return Objects.requireNonNull(INSTANCE, "SMSavedData has not been initialized yet!");
	}

	/**
	 * 设置存档数据实例喵~
	 *
	 * @param in 存档数据实例喵~
	 */
	public static void setInstance(SMSavedData in) {
		INSTANCE = in;
	}

	/**
	 * 添加物品到垃圾箱喵~
	 * <p>
	 * 新物品进入第一个垃圾箱的槽位0（最前面），其他物品后移让位喵~
	 * 第一个垃圾箱满时挤出最后一格（最旧的物品），被挤出的物品进入第二个垃圾箱，以此类推形成垃圾箱链喵~
	 * 最后一个垃圾箱挤出的物品消失喵~
	 * </p>
	 *
	 * @param stack 要添加的物品堆喵~
	 */
	public void addItemToDustbin(ItemStack stack) {
		if (stack.isEmpty()) {
			return;
		}
		synchronized (this.dustbins) {
			addToDustbinChain(0, stack.copy());
		}
	}

	/**
	 * 递归添加物品到垃圾箱链喵~
	 * <p>
	 * 在指定索引的垃圾箱内执行"最新最前"逻辑，被挤出的物品递归进入下一个垃圾箱。
	 * </p>
	 *
	 * @param index 垃圾箱索引喵~
	 * @param stack 要添加的物品堆喵~
	 */
	private void addToDustbinChain(int index, ItemStack stack) {
		if (index >= this.dustbins.size() || stack.isEmpty()) {
			return;  // 所有垃圾箱都满，物品消失
		}
		SimpleContainer dustbin = this.dustbins.get(index);
		int size = dustbin.getContainerSize();
		ItemStack remaining = stack.copy();

		// 阶段1：合并到已有同种堆（原版合并逻辑，堆位置不变）
		for (int i = 0; i < size && !remaining.isEmpty(); ++i) {
			ItemStack existing = dustbin.getItem(i);
			if (!existing.isEmpty() && ItemStack.isSameItemSameTags(existing, remaining)) {
				int canGrow = existing.getMaxStackSize() - existing.getCount();
				if (canGrow > 0) {
					int grow = Math.min(remaining.getCount(), canGrow);
					existing.grow(grow);
					remaining.shrink(grow);
				}
			}
		}
		dustbin.setChanged();

		// 完全合并完，无剩余 → 不移动
		if (remaining.isEmpty()) {
			return;
		}

		// 阶段2：有剩余新堆，拆分为多个maxSize堆
		List<ItemStack> newStacks = new ArrayList<>();
		while (!remaining.isEmpty()) {
			int placeCount = Math.min(remaining.getMaxStackSize(), remaining.getCount());
			newStacks.add(remaining.split(placeCount));
		}

		// 从最后一个新堆开始插入槽0，保证第一个最终在槽0（顺序连续排列）
		for (int i = newStacks.size() - 1; i >= 0; --i) {
			// 强制后移所有物品1格：从最后一格开始，每格内容=前一格内容
			// 容器满时最后一格被挤出（丢弃最旧的）；容器不满时空槽被吸收到后面
			ItemStack evicted = dustbin.getItem(size - 1);  // 最后一格被挤出
			for (int j = size - 1; j > 0; --j) {
				dustbin.setItem(j, dustbin.getItem(j - 1));
			}
			// 槽0腾出，放入新堆
			dustbin.setItem(0, newStacks.get(i));
			dustbin.setChanged();

			// 被挤出的物品进入下一个垃圾箱（递归）
			if (!evicted.isEmpty()) {
				addToDustbinChain(index + 1, evicted);
			}
		}
	}

	/**
	 * 获取指定索引的垃圾箱容器喵~
	 *
	 * @param index 垃圾箱索引喵~
	 * @return 垃圾箱容器喵~
	 */
	public static SimpleContainer getDustbinContainer(int index) {
		return getInstance().dustbins.get(index);
	}

	/**
	 * 访问垃圾箱列表喵~
	 * <p>
	 * 以线程安全的方式访问垃圾箱列表，并标记数据为已修改喵~
	 * </p>
	 *
	 * @param consumer 访问垃圾箱列表的消费者函数喵~
	 */
	public void accessDustbins(Consumer<List<SimpleContainer>> consumer) {
		synchronized (this.dustbins) {
			consumer.accept(this.dustbins);
		}
		this.setDirty();
	}

	/**
	 * 初始化垃圾箱列表喵~
	 * <p>
	 * 根据配置调整垃圾箱数量，确保与配置一致喵~
	 * </p>
	 */
	public static void initialize() {
		List<SimpleContainer> dustbins = getInstance().dustbins;

		// Initialize dustbins.
		synchronized (dustbins) {
			int expectedSize = SMCommonConfig.DUSTBIN_COUNT.get();
			if (dustbins.size() == expectedSize) {
				return;
			}
			if (dustbins.size() < expectedSize) {
				for (int i = dustbins.size(); i < expectedSize; ++i) {
					dustbins.add(createNewDustbin());
				}
			} else {
				do {
					dustbins.remove(dustbins.size() - 1);
				} while (dustbins.size() > expectedSize);
			}
		}
	}

	/**
	 * 创建新的垃圾箱容器喵~
	 * <p>
	 * 创建一个具有最大槽位数的简单容器，并在内容变化时标记存档数据为已修改喵~
	 * </p>
	 *
	 * @return 新的垃圾箱容器喵~
	 */
	private static SimpleContainer createNewDustbin() {
		return new SimpleContainer(MAX_CONTAINER_SIZE) {
			@Override
			public void setChanged() {
				super.setChanged();
				if (INSTANCE != null) {
					INSTANCE.setDirty();
				}
			}
		};
	}
}
