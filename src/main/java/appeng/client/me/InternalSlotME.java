/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.client.me;

import net.minecraft.item.ItemStack;
import appeng.api.storage.data.IAEItemStack;

public class InternalSlotME
{

	private final ItemRepo repo;

	public final int offset;
	public final int xPos;
	public final int yPos;

	public InternalSlotME(ItemRepo def, int offset, int displayX, int displayY) {
		this.repo = def;
		this.offset = offset;
		this.xPos = displayX;
		this.yPos = displayY;
	}

	public ItemStack getStack()
	{
		return repo.getItem( offset );
	}

	public IAEItemStack getAEStack()
	{
		return repo.getReferenceItem( offset );
	}

	public boolean hasPower()
	{
		return repo.hasPower();
	}
}