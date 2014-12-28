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

package appeng.me.storage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.security.MachineSource;
import appeng.api.networking.security.PlayerSource;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import appeng.me.cache.SecurityCache;
import appeng.util.ItemSorters;

public class NetworkInventoryHandler<T extends IAEStack<T>> implements IMEInventoryHandler<T>
{

	private final static Comparator<Integer> prioritySorter = new Comparator<Integer>() {

		@Override
		public int compare(Integer o1, Integer o2)
		{
			return ItemSorters.compareInt( o2, o1 );
		}

	};

	final StorageChannel myChannel;
	final SecurityCache security;

	// final TreeMultimap<Integer, IMEInventoryHandler<T>> priorityInventory;
	private final NavigableMap<Integer, List<IMEInventoryHandler<T>>> priorityInventory;

	public NetworkInventoryHandler(StorageChannel chan, SecurityCache security) {
		myChannel = chan;
		this.security = security;
		priorityInventory = new ConcurrentSkipListMap<Integer, List<IMEInventoryHandler<T>>>( prioritySorter ); // TreeMultimap.create( prioritySorter, hashSorter );
	}

	public void addNewStorage(IMEInventoryHandler<T> h)
	{
		int priority = h.getPriority();
		List<IMEInventoryHandler<T>> list = priorityInventory.get( priority );
		if ( list == null )
			priorityInventory.put( priority, list = new ArrayList<IMEInventoryHandler<T>>() );

		list.add( h );
	}

	static int currentPass = 0;
	int myPass = 0;
	static final ThreadLocal<LinkedList> depthMod = new ThreadLocal<LinkedList>();
	static final ThreadLocal<LinkedList> depthSim = new ThreadLocal<LinkedList>();

	private LinkedList getDepth(Actionable type)
	{
		ThreadLocal<LinkedList> depth = type == Actionable.MODULATE ? depthMod : depthSim;

		LinkedList s = depth.get();

		if ( s == null )
			depth.set( s = new LinkedList() );

		return s;
	}

	private boolean diveList(NetworkInventoryHandler<T> networkInventoryHandler, Actionable type)
	{
		LinkedList cDepth = getDepth( type );
		if ( cDepth.contains( networkInventoryHandler ) )
			return true;

		cDepth.push( this );
		return false;
	}

	private boolean diveIteration(NetworkInventoryHandler<T> networkInventoryHandler, Actionable type)
	{
		LinkedList cDepth = getDepth( type );
		if ( cDepth.isEmpty() )
		{
			currentPass++;
			myPass = currentPass;
		}
		else
		{
			if ( currentPass == myPass )
				return true;
			else
				myPass = currentPass;
		}

		cDepth.push( this );
		return false;
	}

	private void surface(NetworkInventoryHandler<T> networkInventoryHandler, Actionable type)
	{
		if ( getDepth( type ).pop() != this )
			throw new RuntimeException( "Invalid Access to Networked Storage API detected." );
	}

	private boolean testPermission(BaseActionSource src, SecurityPermissions permission)
	{
		if ( src.isPlayer() )
		{
			if ( !security.hasPermission( ((PlayerSource) src).player, permission ) )
				return true;
		}
		else if ( src.isMachine() )
		{
			if ( security.isAvailable() )
			{
				IGridNode n = ((MachineSource) src).via.getActionableNode();
				if ( n == null )
					return true;

				IGrid gn = n.getGrid();
				if ( gn != security.myGrid )
				{
					int playerID = -1;

					ISecurityGrid sg = gn.getCache( ISecurityGrid.class );
					playerID = sg.getOwner();

					if ( !security.hasPermission( playerID, permission ) )
						return true;
				}
			}
		}

		return false;
	}

	@Override
	public T injectItems(T input, Actionable type, BaseActionSource src)
	{
		if ( diveList( this, type ) )
			return input;

		if ( testPermission( src, SecurityPermissions.INJECT ) )
		{
			surface( this, type );
			return input;
		}

		for (List<IMEInventoryHandler<T>> invList : priorityInventory.values())
		{
			Iterator<IMEInventoryHandler<T>> ii = invList.iterator();
			while (ii.hasNext() && input != null)
			{
				IMEInventoryHandler<T> inv = ii.next();

				if ( inv.validForPass( 1 ) && inv.canAccept( input )
						&& (inv.isPrioritized( input ) || inv.extractItems( input, Actionable.SIMULATE, src ) != null) )
				{
					input = inv.injectItems( input, type, src );
				}
			}

			ii = invList.iterator();
			while (ii.hasNext() && input != null)
			{
				IMEInventoryHandler<T> inv = ii.next();
				if ( inv.validForPass( 2 ) && inv.canAccept( input ) )// ignore crafting on the second pass.
				{
					input = inv.injectItems( input, type, src );
				}
			}
		}

		surface( this, type );

		return input;
	}

	@Override
	public T extractItems(T request, Actionable mode, BaseActionSource src)
	{
		if ( diveList( this, mode ) )
			return null;

		if ( testPermission( src, SecurityPermissions.EXTRACT ) )
		{
			surface( this, mode );
			return null;
		}

		Iterator<List<IMEInventoryHandler<T>>> i = priorityInventory.descendingMap().values().iterator();// priorityInventory.asMap().descendingMap().entrySet().iterator();

		T output = request.copy();
		request = request.copy();
		output.setStackSize( 0 );
		long req = request.getStackSize();

		while (i.hasNext())
		{
			List<IMEInventoryHandler<T>> invList = i.next();

			Iterator<IMEInventoryHandler<T>> ii = invList.iterator();
			while (ii.hasNext() && output.getStackSize() < req)
			{
				IMEInventoryHandler<T> inv = ii.next();

				request.setStackSize( req - output.getStackSize() );
				output.add( inv.extractItems( request, mode, src ) );
			}
		}

		surface( this, mode );

		if ( output.getStackSize() <= 0 )
			return null;

		return output;
	}

	@Override
	public IItemList<T> getAvailableItems(IItemList out)
	{
		if ( diveIteration( this, Actionable.SIMULATE ) )
			return out;

		// for (Entry<Integer, IMEInventoryHandler<T>> h : priorityInventory.entries())
		for (List<IMEInventoryHandler<T>> i : priorityInventory.values())
			for (IMEInventoryHandler<T> j : i)
				out = j.getAvailableItems( out );

		surface( this, Actionable.SIMULATE );

		return out;
	}

	@Override
	public StorageChannel getChannel()
	{
		return myChannel;
	}

	@Override
	public AccessRestriction getAccess()
	{
		return AccessRestriction.READ_WRITE;
	}

	@Override
	public boolean isPrioritized(T input)
	{
		return false;
	}

	@Override
	public boolean canAccept(T input)
	{
		return true;
	}

	@Override
	public int getPriority()
	{
		return 0;
	}

	@Override
	public int getSlot()
	{
		return 0;
	}

	@Override
	public boolean validForPass(int i)
	{
		return true;
	}

}